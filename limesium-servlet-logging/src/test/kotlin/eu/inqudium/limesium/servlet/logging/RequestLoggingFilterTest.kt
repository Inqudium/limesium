package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.util.pattern.PatternParseException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Core behavior of [RequestLoggingFilter]: the exchange line, level escalation, correlation id handling,
 * MDC scope, and path exclusions. Time is an `AtomicLong` behind [NanoTimeSource], the correlation id
 * generator is pinned, and log output is observed via a Logback [ListAppender] - fully deterministic, no
 * mocking library.
 */
class RequestLoggingFilterTest {
    private val ticker = AtomicLong(0)
    private val properties =
        RequestLoggingProperties(
            loggerName = "endpoint-http-exchange-core-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val filter =
        RequestLoggingFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            SimpleMeterRegistry(),
        )

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    /**
     * Drives the full production sequence by hand: the filter pass, then the request destruction the
     * container would fire - which is where the event is emitted since the move to
     * ServletRequestListener.requestDestroyed. Destruction runs in a finally so the exception path is
     * covered exactly like a real container covers it.
     */
    private fun handle(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
        chain: FilterChain,
        filterUnderTest: RequestLoggingFilter = filter,
    ) {
        try {
            filterUnderTest.doFilterInternal(request, response, chain)
        } finally {
            filterUnderTest.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
        }
    }

    private fun singleEvent(): ILoggingEvent = appender.list.single()

    @Nested
    inner class `The exchange line` {
        @Test
        fun `should log method path status and duration of a completed exchange`() {
            // Given: a GET request and a chain that answers 200 after 42 ms of measured work
            val request = MockHttpServletRequest("GET", "/api/things")
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { _, res ->
                    ticker.addAndGet(42_000_000)
                    (res as HttpServletResponse).status = 200
                }

            // When: the filter handles the exchange
            handle(request, response, chain)

            // Then: exactly one INFO line carries the human-readable gist and the endpoint_* key-values
            val event = singleEvent()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(event.formattedMessage)
                .isEqualTo("Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]")
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_request_method", "GET")
                .containsEntry("endpoint_url_path", "/api/things")
                .containsEntry("endpoint_response_status_code", 200)
                .containsEntry("endpoint_duration_ms", 42L)
                .containsEntry("endpoint_async", false)
                .doesNotContainKey("endpoint_slow")
        }

        @Test
        fun `should log the query string as its own field beside the query-free path`() {
            // Given: a request with a query string
            val request = MockHttpServletRequest("GET", "/api/things")
            request.queryString = "page=2"

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the query rides its own field so grouping by path is not defeated by varying queries
            val event = singleEvent()
            assertThat(keyValues(event))
                .containsEntry("endpoint_url_path", "/api/things")
                .containsEntry("endpoint_url_query", "page=2")
        }

        @Test
        fun `should log the handler pattern as the url template field when the dispatch recorded one`() {
            // Given: a request on which Spring MVC recorded its best-matching pattern during the chain
            val request = MockHttpServletRequest("GET", "/api/things/7")
            val chain =
                FilterChain { req, _ ->
                    req.setAttribute(RequestLoggingFilter.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/things/{id}")
                }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: the low-cardinality template accompanies the expanded path
            assertThat(keyValues(singleEvent()))
                .containsEntry("endpoint_url_path", "/api/things/7")
                .containsEntry("endpoint_url_template", "/api/things/{id}")
        }

        @Test
        fun `should omit the query string when disabled`() {
            // Given: a filter configured without query logging and a request with a query string
            val quietProperties = properties.copy(includeQueryString = false)
            val quietFilter = RequestLoggingFilter(quietProperties, { ticker.get() }, { "generated-42" }, SimpleMeterRegistry())
            val request = MockHttpServletRequest("GET", "/api/things")
            request.queryString = "secret=1"

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), FilterChain { _, _ -> }, quietFilter)

            // Then: the query string appears nowhere in the event
            val event = singleEvent()
            assertThat(event.formattedMessage).doesNotContain("secret=1")
            assertThat(keyValues(event)).doesNotContainKey("endpoint_url_query")
        }
    }

    @Nested
    inner class `The optional start line` {
        private val startLoggingFilter =
            RequestLoggingFilter(
                properties.copy(logRequestStart = true),
                { ticker.get() },
                { "generated-42" },
                SimpleMeterRegistry(),
            )

        @Test
        fun `should announce the exchange before the chain runs when enabled`() {
            // What is tested: the arrival line fires BEFORE the chain, and the completion event still
            //   follows as the only outcome-carrying line.
            // Success criteria: at chain time exactly one line exists and it is the start line; after
            //   completion there are two, and only the second carries endpoint_outcome.
            // Why it matters: the option exists for exchanges that hang or never complete - a start line
            //   that fired only afterwards would be worthless for exactly that case; and a start line
            //   carrying an outcome would double every count keyed on the outcome field.
            // Given: a chain that records what the log stream looks like while it runs
            var eventsAtChainTime = listOf<String>()
            val request = MockHttpServletRequest("GET", "/api/things")
            request.queryString = "page=2"
            val chain = FilterChain { _, _ -> eventsAtChainTime = appender.list.map { it.formattedMessage } }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain, startLoggingFilter)

            // Then: the start line was already visible while the chain ran
            assertThat(eventsAtChainTime)
                .containsExactly("Endpoint http exchange started GET /api/things [endpoint_request_id=generated-42]")

            // And: the start line carries the request-side fields but no outcome; the completion line has it
            assertThat(appender.list).hasSize(2)
            val start = appender.list.first()
            assertThat(keyValues(start))
                .containsEntry("endpoint_request_method", "GET")
                .containsEntry("endpoint_url_path", "/api/things")
                .containsEntry("endpoint_url_query", "page=2")
                .doesNotContainKey("endpoint_outcome")
                .doesNotContainKey("endpoint_response_status_code")
                .doesNotContainKey("endpoint_duration_ms")
            assertThat(start.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            assertThat(keyValues(appender.list.last())).containsEntry("endpoint_outcome", "success")
        }

        @Test
        fun `should not log a start line by default`() {
            // Given/When: a default-configured filter handling an exchange
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: only the completion event exists
            assertThat(appender.list).hasSize(1)
            assertThat(appender.list.single().formattedMessage).doesNotContain("started")
        }
    }

    @Nested
    inner class `Level escalation` {
        @Test
        fun `should escalate to WARN for a server error status`() {
            // Given: a chain that answers 503
            val chain = FilterChain { _, res -> (res as HttpServletResponse).status = 503 }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse(), chain)

            // Then: the line is WARN and the outcome field says failure - severity and semantic decoupled
            val event = singleEvent()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event))
                .containsEntry("endpoint_response_status_code", 503)
                .containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should escalate to WARN and flag the exchange when the slow threshold is reached`() {
            // Given: a chain that consumes exactly the configured slow threshold (200 ms)
            val chain = FilterChain { _, _ -> ticker.addAndGet(200_000_000) }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/slow"), MockHttpServletResponse(), chain)

            // Then: the line is WARN and marked slow, but the OUTCOME stays success - slowness raises
            //   severity, it does not turn a completed exchange into a failure
            val event = singleEvent()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event))
                .containsEntry("endpoint_slow", true)
                .containsEntry("endpoint_duration_ms", 200L)
                .containsEntry("endpoint_outcome", "success")
        }

        @Test
        fun `should compare the slow threshold at full precision instead of truncated milliseconds`() {
            // What is tested: twin parity with the reactive module's threshold fix - a 1.5 ms
            //   threshold used to truncate to 1 ms and flag a 1 ms exchange.
            // Success criteria: 1.0 ms is NOT slow, 1.5 ms IS slow, under a 1.5 ms threshold.
            // Why it matters: both twins must classify identically, or the same request would be WARN on
            //   one stack and INFO on the other.
            // Given: a filter with a 1.5 ms threshold
            val precise =
                RequestLoggingFilter(
                    properties.copy(slowRequestThreshold = Duration.ofNanos(1_500_000)),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    SimpleMeterRegistry(),
                )

            fun slowFlagAfter(elapsedNanos: Long): Boolean {
                appender.list.clear()
                val request = MockHttpServletRequest("GET", "/api/things")
                try {
                    precise.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> ticker.addAndGet(elapsedNanos) })
                } finally {
                    precise.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
                }
                return keyValues(singleEvent()).containsKey("endpoint_slow")
            }

            // When/Then: just below is not slow, exactly at is slow
            assertThat(slowFlagAfter(1_000_000)).isFalse()
            assertThat(slowFlagAfter(1_500_000)).isTrue()
        }

        @Test
        fun `should log an immediate WARN breadcrumb on the module logger when the chain throws`() {
            // What is tested: the breadcrumb logged in the filter's finally, at the failure site.
            // Success criteria: one WARN line on the FILTER's own logger (not the exchange logger) naming
            //   method, path, exception and correlation id - before the full event, which only arrives at
            //   request destruction.
            // Why it matters: the full ERROR event is deferred until after the container's error dispatch;
            //   without the breadcrumb the log stream would show the container's stack trace with no
            //   immediate hint which exchange it belongs to.
            // Given: an appender on the module's own logger and a failing chain
            val moduleLogger = LoggerFactory.getLogger(RequestLoggingFilter::class.java) as Logger
            val moduleAppender = ListAppender<ILoggingEvent>().apply { start() }
            moduleLogger.addAppender(moduleAppender)
            moduleLogger.level = Level.WARN
            try {
                val boom = IllegalStateException("boom")
                val request = MockHttpServletRequest("POST", "/api/things")

                // When: only the filter pass runs (no request destruction yet)
                catchThrowable { filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> throw boom }) }

                // Then: the breadcrumb is already there while the exchange logger is still silent
                assertThat(appender.list).isEmpty()
                val breadcrumb = moduleAppender.list.single()
                assertThat(breadcrumb.level).isEqualTo(Level.WARN)
                assertThat(breadcrumb.formattedMessage)
                    .isEqualTo(
                        "Endpoint http exchange failed: POST /api/things - " +
                            "java.lang.IllegalStateException: boom [endpoint_request_id=generated-42]",
                    )
            } finally {
                moduleLogger.detachAppender(moduleAppender)
                moduleAppender.stop()
            }
        }

        @Test
        fun `should log a chain exception at ERROR and rethrow the same instance`() {
            // What is tested: the failure path must ADD visibility without CHANGING error semantics - the
            //   container's error handling classifies on the exception instance.
            // Success criteria: the thrown object is the identical instance (not a copy or wrapper), and
            //   exactly one WARN line carries it as cause.
            // Why it matters: a filter that wraps or swallows exceptions silently breaks error pages,
            //   @ControllerAdvice handlers, and retry semantics of everything downstream.
            // Given: a chain that fails
            val boom = IllegalStateException("boom")
            val chain = FilterChain { _, _ -> throw boom }

            // When: the filter handles the exchange
            val thrown =
                catchThrowable {
                    handle(MockHttpServletRequest("POST", "/api/things"), MockHttpServletResponse(), chain)
                }

            // Then: the identical exception propagates and the single ERROR line names it as cause
            assertThat(thrown).isSameAs(boom)
            val event = singleEvent()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("boom")
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }
    }

    @Nested
    inner class `Correlation id and MDC` {
        @Test
        fun `should adopt the correlation id from the request header and echo it on the response`() {
            // Given: a request already carrying a correlation id
            val request = MockHttpServletRequest("GET", "/api/things")
            request.addHeader(properties.correlationIdHeader, "caller-supplied-id")
            val response = MockHttpServletResponse()

            // When: the filter handles the exchange
            handle(request, response, FilterChain { _, _ -> })

            // Then: the caller's id is echoed, rides the event's MDC (the encoder emits MDC fields), and
            //   is repeated inline for plain-text appenders
            assertThat(response.getHeader(properties.correlationIdHeader)).isEqualTo("caller-supplied-id")
            val event = singleEvent()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "caller-supplied-id")
            assertThat(event.formattedMessage).contains("[endpoint_request_id=caller-supplied-id]")
        }

        @Test
        fun `should generate a correlation id when the request carries none`() {
            // Given: a request without a correlation header
            val response = MockHttpServletResponse()

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, FilterChain { _, _ -> })

            // Then: the pinned generator's id is echoed and rides the event's MDC
            assertThat(response.getHeader(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(singleEvent().mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should expose the exchange identity in the MDC while the chain runs`() {
            // Given: a chain that records the MDC it observes
            var observedMdc: Map<String, String?> = emptyMap()
            val chain =
                FilterChain { _, _ ->
                    observedMdc =
                        mapOf(
                            MdcKeys.REQUEST_ID to MDC.get(MdcKeys.REQUEST_ID),
                            MdcKeys.REQUEST_METHOD to MDC.get(MdcKeys.REQUEST_METHOD),
                            MdcKeys.ROUTE to MDC.get(MdcKeys.ROUTE),
                        )
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("PUT", "/api/things/7"), MockHttpServletResponse(), chain)

            // Then: downstream code saw the full identity
            assertThat(observedMdc)
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                .containsEntry(MdcKeys.REQUEST_METHOD, "PUT")
                .containsEntry(MdcKeys.ROUTE, "/api/things/7")
        }

        @Test
        fun `should restore the previous MDC values after the exchange`() {
            // What is tested: MDC restoration on a POOLED container thread, including the case where an
            //   OUTER component owns the same keys.
            // Success criteria: a pre-existing value is back after the filter ran, and a key that did not
            //   exist before is absent again (not left as an empty leftover).
            // Why it matters: leaked MDC entries attach a finished request's identity to the NEXT request
            //   handled by the same pooled thread - the classic source of wrong correlation ids in logs.
            // Given: an outer owner of the correlation key, and no method key at all
            MDC.put(MdcKeys.REQUEST_ID, "outer-scope-id")

            // When: the filter handles an exchange in between
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the outer value is restored and the filter's other keys are gone
            assertThat(MDC.get(MdcKeys.REQUEST_ID)).isEqualTo("outer-scope-id")
            assertThat(MDC.get(MdcKeys.REQUEST_METHOD)).isNull()
            assertThat(MDC.get(MdcKeys.ROUTE)).isNull()
        }
    }

    @Nested
    inner class `Path exclusions` {
        private val excludingFilter =
            RequestLoggingFilter(
                properties.copy(excludePathPrefixes = listOf("/actuator/health")),
                { ticker.get() },
                { "generated-42" },
                SimpleMeterRegistry(),
            )

        @Test
        fun `should not log an excluded path at all`() {
            // Given: a request below an excluded prefix, run through the full OncePerRequestFilter entry point
            val request = MockHttpServletRequest("GET", "/actuator/health/liveness")

            // When: the filter participates in the chain
            excludingFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            excludingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: no exchange line is emitted - the destruction callback finds no exchange to log
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should match activation against the path within the application under a context path`() {
            // What is tested: finding 3 of the repo-wide code analysis of 2026-08-30 - activation must
            //   match what Spring MVC's handler mapping matches, the path WITHIN the application, not
            //   the full request URI that includes server.servlet.context-path.
            // Success criteria: under context path /app, /app/api/things is logged by include /api/**
            //   and /app/actuator/health is excluded by /actuator/health; matching against the full
            //   URI would silently invert both.
            // Why it matters: on a non-root deployment, include patterns modeled after MVC routes
            //   otherwise match nothing - total, silent loss of exchange logging exactly where the
            //   operator configured it.
            // Given: a filter scoped to /api/** with /actuator/health excluded, under context path /app
            val scoped =
                RequestLoggingFilter(
                    properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/actuator/health")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val included =
                MockHttpServletRequest("GET", "/app/api/things").apply { contextPath = "/app" }
            val excluded =
                MockHttpServletRequest("GET", "/app/actuator/health").apply { contextPath = "/app" }

            // When: both requests run through the full OncePerRequestFilter entry point
            scoped.doFilter(included, MockHttpServletResponse(), MockFilterChain())
            scoped.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(included.servletContext, included))
            scoped.doFilter(excluded, MockHttpServletResponse(), MockFilterChain())
            scoped.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(excluded.servletContext, excluded))

            // Then: exactly the included exchange is logged, under its full (context-path-keeping) path
            val event = appender.list.single()
            assertThat(event.formattedMessage).contains("GET /app/api/things")
        }

        @Test
        fun `should be active only for endpoints matching an include pattern`() {
            // What is tested: the include side of the activation rule - patterns determine for which
            //   endpoints the filter runs AT ALL.
            // Success criteria: a matching path produces an event with correlation echo; a non-matching
            //   path produces neither (the filter never ran).
            // Why it matters: hosts scope exchange logging to their API surface; a filter that still ran
            //   on static resources would log and echo where it should be invisible.
            // Given: a filter restricted to /api/**
            val includingFilter =
                RequestLoggingFilter(
                    properties.copy(includePathPatterns = listOf("/api/**")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )

            // When: one matching and one non-matching request run through the full entry point
            val matching = MockHttpServletRequest("GET", "/api/things")
            val matchingResponse = MockHttpServletResponse()
            includingFilter.doFilter(matching, matchingResponse, MockFilterChain())
            includingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(matching.servletContext, matching))
            val other = MockHttpServletRequest("GET", "/static/logo.png")
            val otherResponse = MockHttpServletResponse()
            includingFilter.doFilter(other, otherResponse, MockFilterChain())
            includingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(other.servletContext, other))

            // Then: only the matching exchange was logged and echoed
            assertThat(appender.list).hasSize(1)
            assertThat(keyValues(appender.list.single())).containsEntry("endpoint_url_path", "/api/things")
            assertThat(matchingResponse.getHeader(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(otherResponse.getHeader(properties.correlationIdHeader)).isNull()
        }

        @Test
        fun `should let an exclude win inside an included pattern`() {
            // Given: /api/** included, /api/internal excluded
            val filterUnderTest =
                RequestLoggingFilter(
                    properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/api/internal")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val request = MockHttpServletRequest("GET", "/api/internal/jobs")

            // When: the excluded-inside-included request runs
            filterUnderTest.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            filterUnderTest.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the exclude won
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should reject an invalid include pattern at construction time`() {
            // Given/When: a syntactically broken PathPattern
            val thrown =
                catchThrowable {
                    RequestLoggingFilter(
                        properties.copy(includePathPatterns = listOf("/api/{unclosed")),
                        { ticker.get() },
                        { "generated-42" },
                        SimpleMeterRegistry(),
                    )
                }

            // Then: it fails fast with the PARSER's exception, and the diagnostic names the malformed
            //   pattern - a bare non-null check would pass for any unrelated constructor failure.
            assertThat(thrown).isInstanceOf(PatternParseException::class.java)
            assertThat((thrown as PatternParseException).toDetailedString())
                .contains("/api/{unclosed")
        }

        @Test
        fun `should exclude a percent-encoded variant of an excluded prefix`() {
            // What is tested: the exclude rule sees the request target the way the router does - a byte-wise
            //   startsWith on the raw URI let
            //   `/%61ctuator/health` through although the container serves it as /actuator/health.
            // Success criteria: the encoded variant produces no event, exactly like the plain one.
            // Why it matters: operators exclude paths to keep probes out of the log - or to keep
            //   sensitive routes out of it; an encoding trick must not undo either.
            // Given: the excluded prefix, reached through a percent-encoded request target
            val request = MockHttpServletRequest("GET", "/%61ctuator/health")

            // When: the filter participates in the chain
            excludingFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            excludingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: excluded
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should include a percent-encoded variant of an included pattern and log the raw path`() {
            // What is tested: the include side decodes per segment like Spring MVC's handler mapping, so
            //   activation and routing agree on `/%61pi/things`; the logged path stays raw as sent.
            // Success criteria: one event, endpoint_url_path carries the encoded form.
            // Why it matters: a request the router serves under /api/** must not evade include-scoped
            //   logging, and the log must not decode control sequences (twin parity with the reactive
            //   module's raw-path contract).
            // Given: /api/** included, an encoded request target
            val includingFilter =
                RequestLoggingFilter(
                    properties.copy(includePathPatterns = listOf("/api/**")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val request = MockHttpServletRequest("GET", "/%61pi/things")

            // When
            includingFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            includingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then
            assertThat(keyValues(singleEvent())).containsEntry("endpoint_url_path", "/%61pi/things")
        }

        @Test
        fun `should still log a path that merely resembles an excluded prefix`() {
            // Given: a path that shares characters but not the prefix
            val request = MockHttpServletRequest("GET", "/actuator-dashboard")

            // When: the filter participates in the chain
            excludingFilter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
            excludingFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the exchange is logged
            assertThat(appender.list).hasSize(1)
        }
    }
}
