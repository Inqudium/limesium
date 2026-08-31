package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.limesium.common.MdcKeys
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.AsyncEvent
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.Marker
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * Async behavior of [RequestLoggingFilter]: when the request enters async mode (a `suspend` controller,
 * `DeferredResult`, `Callable`), `doFilter` returns before the response exists. The registered
 * [jakarta.servlet.AsyncListener] only MARKS timeout/error on the exchange; the event is emitted at
 * request destruction, which the container orders after async completion. The lifecycle is driven by hand
 * through Spring's [MockAsyncContext] and an explicit `requestDestroyed` - deterministic on the test
 * thread, no waiting, no real container.
 */
class RequestLoggingFilterAsyncTest {
    private val ticker = AtomicLong(0)
    private val properties = RequestLoggingProperties(loggerName = "http-exchange-async-test")
    private val meterRegistry = SimpleMeterRegistry()
    private val filter = RequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, meterRegistry)

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun asyncRequest(): MockHttpServletRequest = MockHttpServletRequest("GET", "/api/async").apply { isAsyncSupported = true }

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    /** The request destruction the container fires once the async cycle has ended - the emission point. */
    private fun destroy(request: MockHttpServletRequest) = filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

    @Nested
    inner class `Lifecycle` {
        @Test
        fun `should defer the exchange line until request destruction and measure the full duration`() {
            // What is tested: the dispatcher-return-before-response problem - for an async exchange,
            //   doFilter returns while the controller is still working, so neither status nor duration exist
            //   yet. Emission happens only at request destruction, AFTER async completion.
            // Success criteria: NOTHING is logged when doFilter returns, and still nothing at onComplete (the
            //   listener only marks); after requestDestroyed exactly one line exists whose duration covers the
            //   async phase (30 ms chain + 70 ms async = 100 ms), marked async=true with the final status.
            // Why it matters: logging synchronously would report duration~=0 and status 200-by-default for
            //   every coroutine controller - systematically wrong data for exactly the endpoints where
            //   latency matters.
            // Given: a chain that starts async processing and returns after 30 ms of measured work
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { req, _ ->
                    ticker.addAndGet(30_000_000)
                    req.startAsync()
                }

            // When: the filter's chain pass completes
            filter.doFilterInternal(request, response, chain)

            // Then: no line yet - the exchange is still in flight
            assertThat(appender.list).isEmpty()

            // When: the async work finishes 70 ms later and the container fires onComplete
            ticker.addAndGet(70_000_000)
            response.status = 201
            val asyncContext = request.asyncContext as MockAsyncContext
            assertThat(asyncContext.listeners).hasSize(1)
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))

            // Then: STILL no line - the async listener marks, it does not emit
            assertThat(appender.list).isEmpty()

            // When: the container destroys the request after the async cycle has ENDED
            destroy(request)

            // Then: exactly one line, marked async, with the final status and the full duration
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_async", true)
                .containsEntry("endpoint_response_status_code", 201)
                .containsEntry("endpoint_duration_ms", 100L)
            // The destruction callback carries no MDC of its own; the emission restores the exchange's MDC,
            // so the encoder still sees the correlation id as an MDC field.
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should skip a destruction fired while async is still running and complete at the final one`() {
            // What is tested: the per-dispatch destruction model (Jetty fires requestDestroyed at the
            //   end of EVERY dispatch) - a destruction observed before the cycle's onComplete must not
            //   emit and must not consume the exchange; after onComplete the completion happens exactly
            //   once, whichever callback gets there first.
            // Success criteria: nothing is logged at the early destruction; exactly one line after the
            //   final one, with the final status.
            // Why it matters: on Jetty the early destruction used to emit a pre-completion 200 for
            //   exchanges whose client later received a 500, and to strip the exchange the
            //   async-dispatch pass depends on (Jetty capture-boundary integration test, 2026-08-30).
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })

            // When: the container destroys the initial dispatch WHILE async is still running
            destroy(request)

            // Then: nothing is emitted - the exchange survives the early destruction
            assertThat(appender.list).isEmpty()

            // When: the cycle ends (onComplete arms the completion); later destructions follow
            response.status = 502
            (request.asyncContext as MockAsyncContext).listeners.single().onComplete(AsyncEvent(request.asyncContext as MockAsyncContext))
            destroy(request)

            // Then: exactly one line with the final status - and no duplicate from the extra destruction
            destroy(request)
            val event = appender.list.single()
            assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 502)
        }

        @Test
        fun `should complete via the onComplete backstop when no destruction follows a raw completion`() {
            // What is tested: the onComplete backstop - a raw async cycle ends via complete() WITHOUT a
            //   further dispatch, so a per-dispatch container never fires another destruction; the
            //   marker completes the exchange exactly when the skipped-destruction flag is armed.
            // Success criteria: the early destruction emits nothing; the container's onComplete then
            //   yields exactly one line - and a late duplicate destruction stays a no-op.
            // Why it matters: without the backstop, raw-async exchanges on Jetty lost their event
            //   entirely (and leaked the open-exchanges gauge) after the early-destruction skip.
            // Given: an exchange in async mode whose initial dispatch was destroyed mid-cycle
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext
            destroy(request)
            assertThat(appender.list).isEmpty()

            // When: the cycle completes without any further dispatch
            response.status = 204
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))

            // Then: the backstop emitted exactly one line with the final status; a stray late
            //   destruction changes nothing
            destroy(request)
            val event = appender.list.single()
            assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 204)
        }

        @Test
        fun `should log exactly once when destruction fires twice after a burst of terminal events`() {
            // What is tested: the exactly-once guard behind the emission.
            // Success criteria: a burst of async events followed by TWO requestDestroyed calls still yields
            //   ONE line.
            // Why it matters: duplicated exchange lines double request counts in every log-derived metric;
            //   the guard must hold against container quirks on either callback.
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext
            val listener = asyncContext.listeners.single()

            // When: the container fires a burst of events and destroys the request twice
            listener.onComplete(AsyncEvent(asyncContext))
            listener.onComplete(AsyncEvent(asyncContext))
            destroy(request)
            destroy(request)

            // Then: exactly one line was emitted
            assertThat(appender.list).hasSize(1)
        }
    }

    @Nested
    inner class `Async dispositions` {
        @Test
        fun `should log outcome timeout at WARN when the container times the exchange out`() {
            // What is tested: the onTimeout path, which - unlike onError - usually carries NO throwable.
            // Success criteria: one WARN line with endpoint_outcome=timeout, no cause.
            // Why it matters: without the explicit timedOut flag a container timeout would be
            //   indistinguishable from a clean completion and log as success.
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext

            // When: the container fires onTimeout without a throwable and then destroys the request
            asyncContext.listeners.single().onTimeout(AsyncEvent(asyncContext))
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))
            destroy(request)

            // Then: the single line is WARN with outcome timeout and no cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.throwableProxy).isNull()
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "timeout")
        }

        @Test
        fun `should keep outcome timeout when the timeout event carries a throwable`() {
            // What is tested: the callback-vs-throwable classification - the servlet API permits onTimeout
            //   WITH a throwable, and inferring the outcome
            //   from throwable presence misfiled such a timeout as a generic failure.
            // Success criteria: WARN with endpoint_outcome=timeout - the CALLBACK classifies - while the
            //   supplied throwable still rides the event as its cause.
            // Why it matters: timeout dashboards key on the outcome; a timeout that shape-shifts into
            //   "failure" whenever the container supplies a cause makes the timeout rate unusable.
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext

            // When: the container fires onTimeout WITH a throwable and then destroys the request
            asyncContext.listeners.single().onTimeout(AsyncEvent(asyncContext, IllegalStateException("timeout cause")))
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))
            destroy(request)

            // Then: still WARN/timeout, with the throwable attached as cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "timeout")
            assertThat(event.throwableProxy?.message).isEqualTo("timeout cause")
        }

        @Test
        fun `should log outcome failure at ERROR when onError arrives without a throwable`() {
            // What is tested: the complement of the previous case - the servlet API
            //   explicitly permits an onError event with a NULL throwable, and an implementation keying on
            //   the throwable logged such an exchange as a clean success.
            // Success criteria: ERROR with endpoint_outcome=failure and no cause on the event.
            // Why it matters: a failed async exchange logging as success is silent data corruption in every
            //   error-rate dashboard - the exact defect class the outcome field exists to prevent.
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext

            // When: the container fires onError WITHOUT a throwable and then destroys the request
            asyncContext.listeners.single().onError(AsyncEvent(asyncContext))
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))
            destroy(request)

            // Then: ERROR/failure, no invented cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy).isNull()
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should escalate to ERROR with the cause when async processing ends in onError`() {
            // Given: an exchange in async mode
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            filter.doFilterInternal(request, response, FilterChain { req, _ -> req.startAsync() })
            val asyncContext = request.asyncContext as MockAsyncContext

            // When: the async phase fails and the container then destroys the request
            val failure = IllegalStateException("async boom")
            asyncContext.listeners.single().onError(AsyncEvent(asyncContext, failure))
            asyncContext.listeners.single().onComplete(AsyncEvent(asyncContext))
            destroy(request)

            // Then: the single line is ERROR with outcome failure and carries the async failure as cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("async boom")
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }
    }

    @Nested
    inner class `Async dispatch participation` {
        /** Runs the filter through its OncePerRequestFilter entry, as the container does. */
        private fun dispatch(
            request: MockHttpServletRequest,
            response: MockHttpServletResponse,
            chain: FilterChain,
        ) = filter.doFilter(
            request,
            response,
            MockFilterChain(
                object : jakarta.servlet.http.HttpServlet() {},
                object : jakarta.servlet.Filter {
                    override fun doFilter(
                        req: jakarta.servlet.ServletRequest,
                        res: jakarta.servlet.ServletResponse,
                        c: FilterChain,
                    ) = chain.doFilter(req, res)
                },
            ),
        )

        @Test
        fun `should carry the MDC into the async dispatch and record its failure on the existing exchange`() {
            // What is tested: the second filter pass for the container's ASYNC dispatch - Spring MVC renders
            //   the async result or rethrows the
            //   async failure in that dispatch; the filter used to skip it entirely.
            // Success criteria: the dispatch chain observes the endpoint_* MDC; an exception thrown
            //   there propagates unchanged; at destruction exactly ONE event exists, ERROR/failure with
            //   that exception as cause, endpoint_async=true, under the ORIGINAL correlation id (the
            //   exchange was reused, not re-wired); the test thread's MDC is clean afterwards.
            // Why it matters: a failing Callable used to log WARN without a cause while the sync
            //   equivalent logged ERROR with it - alerting disagreed between sync and async endpoints.
            // Given: an exchange whose initial dispatch started async processing
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            dispatch(request, response, FilterChain { req, _ -> req.startAsync() })
            assertThat(appender.list).isEmpty()

            // When: the container dispatches ASYNC and the rendering throws; MDC is observed inside
            request.dispatcherType = DispatcherType.ASYNC
            var dispatchMdc: String? = null
            val boom = IllegalStateException("async handler boom")
            val thrown =
                catchThrowable {
                    dispatch(
                        request,
                        response,
                        FilterChain { _, _ ->
                            dispatchMdc = MDC.get(MdcKeys.REQUEST_ID)
                            throw boom
                        },
                    )
                }
            response.status = 500
            (request.asyncContext as MockAsyncContext).listeners.single().onComplete(AsyncEvent(request.asyncContext as MockAsyncContext))
            destroy(request)

            // Then
            assertThat(thrown).isSameAs(boom)
            assertThat(dispatchMdc).isEqualTo("generated-42")
            assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("async handler boom")
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "failure")
                .containsEntry("endpoint_async", true)
                .containsEntry("endpoint_response_status_code", 500)
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should rethrow the application exception although the async breadcrumb backend throws`() {
            // What is tested: the fail-open guard around the async dispatch's breadcrumb - a double fault:
            //   the async handler fails AND the
            //   module logger's backend throws (a throwing TurboFilter, consulted inside warn()).
            // Success criteria: the container receives the APPLICATION exception, not the backend's; the
            //   failure is recorded on the exchange (the event at destruction carries it); the breadcrumb
            //   loss is counted stage=wiring.
            // Why it matters: before the guard the backend exception replaced the handler's on its way to
            //   the container's error handling - wrong diagnostics and a violated fail-open contract.
            // Given: an async exchange, and a backend that throws for the module's own logger
            val request = asyncRequest()
            val response = MockHttpServletResponse()
            dispatch(request, response, FilterChain { req, _ -> req.startAsync() })
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val bomb =
                object : TurboFilter() {
                    override fun decide(
                        marker: Marker?,
                        logger: Logger,
                        level: Level,
                        format: String?,
                        params: Array<out Any>?,
                        t: Throwable?,
                    ): FilterReply =
                        if (logger.name == RequestLoggingFilter::class.java.name) {
                            throw IllegalStateException("backend boom")
                        } else {
                            FilterReply.NEUTRAL
                        }
                }
            loggerContext.addTurboFilter(bomb)
            val boom = IllegalStateException("async handler boom")
            val thrown =
                try {
                    // When: the async dispatch throws while the breadcrumb backend is broken
                    request.dispatcherType = DispatcherType.ASYNC
                    catchThrowable { dispatch(request, response, FilterChain { _, _ -> throw boom }) }
                } finally {
                    loggerContext.turboFilterList.remove(bomb)
                }
            response.status = 500
            (request.asyncContext as MockAsyncContext).listeners.single().onComplete(AsyncEvent(request.asyncContext as MockAsyncContext))
            destroy(request)

            // Then: the application exception reached the container; the failure is on the event
            assertThat(thrown).isSameAs(boom)
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("async handler boom")
            assertThat(
                meterRegistry
                    .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }

        @Test
        fun `should pass an async dispatch through untouched when no exchange is attached`() {
            // Given: an ASYNC dispatch of a request the filter never wired (excluded or failed open)
            val request = asyncRequest().apply { dispatcherType = DispatcherType.ASYNC }
            var chainRan = false

            // When
            dispatch(request, MockHttpServletResponse(), FilterChain { _, _ -> chainRan = true })

            // Then: served, nothing logged, nothing wired
            assertThat(chainRan).isTrue()
            assertThat(appender.list).isEmpty()
            destroy(request)
            assertThat(appender.list).isEmpty()
        }
    }
}
