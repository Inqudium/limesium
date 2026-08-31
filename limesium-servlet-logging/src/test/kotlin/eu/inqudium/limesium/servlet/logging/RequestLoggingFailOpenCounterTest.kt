package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.HeaderLogProperties
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * The fail-open counter ([EndpointLoggingMetrics.FAIL_OPEN_METER]): every logging failure the fail-open
 * path swallows is counted, per stage, on a [SimpleMeterRegistry] - because the metric exists precisely
 * for the state in which the LOG report about the failure cannot be trusted. Failures are injected
 * through the servlet objects the filter touches inside its guarded blocks (a throwing header
 * enumeration, a lying async state), so no mocking library is needed and the requests still complete.
 */
class RequestLoggingFailOpenCounterTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        RequestLoggingProperties(
            loggerName = "http-exchange-failopen-test",
            responseHeaders = HeaderLogProperties(includes = listOf("Content-Type")),
        )
    private val filter =
        RequestLoggingFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            meterRegistry,
        )

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

    private fun stageCount(stage: String): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
            .tag("stage", stage)
            .counter()
            .count()

    private fun handle(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
    ) {
        filter.doFilterInternal(request, response, FilterChain { _, _ -> })
        filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
    }

    @Nested
    inner class `Pre-registration` {
        @Test
        fun `should pre-register all three stages at zero and stay at zero for a healthy exchange`() {
            // What is tested: the counters exist from construction (a dashboard can alert on them before the
            //   first failure) and a normal exchange touches none of them.
            // Success criteria: emission, arrival and wiring all read exactly 0 after a clean exchange.
            // Why it matters: a counter created lazily on first failure is invisible to a rate() alert until
            //   the very moment it should already be firing.
            // Given/When: fresh counters; a healthy exchange runs
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse())

            // Then: one event, no fail-open occurrences
            assertThat(appender.list).hasSize(1)
            assertThat(stageCount("emission")).isEqualTo(0.0)
            assertThat(stageCount("arrival")).isEqualTo(0.0)
            assertThat(stageCount("wiring")).isEqualTo(0.0)
        }
    }

    @Nested
    inner class `Emission stage` {
        @Test
        fun `should count a broken emission as stage emission and still complete the request`() {
            // What is tested: the emission fail-open path - the exchange event is LOST, the request is not.
            // Success criteria: no exception reaches the caller, no event is emitted, the emission counter
            //   reads 1 and the other stages stay 0.
            // Why it matters: this is exactly the state the metric exists for - the missing line is the
            //   symptom, so only the counter makes the loss observable.
            // Given: a response whose header enumeration throws INSIDE the guarded emission (the configured
            //   response-header selection reads it there)
            val brokenResponse =
                object : MockHttpServletResponse() {
                    override fun getHeaderNames(): Collection<String> = throw IllegalStateException("header enumeration boom")
                }

            // When: the exchange runs to destruction
            handle(MockHttpServletRequest("GET", "/api/things"), brokenResponse)

            // Then: the event is lost, counted, and nothing propagated
            assertThat(appender.list).isEmpty()
            assertThat(stageCount("emission")).isEqualTo(1.0)
            assertThat(stageCount("arrival")).isEqualTo(0.0)
            assertThat(stageCount("wiring")).isEqualTo(0.0)
        }

        @Test
        fun `should count a pre-gate emission failure instead of letting it escape the destruction callback`() {
            // What is tested: the widened emission guard - the section BEFORE the
            //   level gate (here: the status read) is fallible too.
            // Success criteria: a response whose status getter throws at destruction time yields no
            //   exception, no event, and emission=1.
            // Why it matters: before the fix this escaped into the container's listener invocation and the
            //   event was lost WITHOUT the emission counter seeing it - the counter's own blind spot.
            // Given: a response that breaks on the pre-gate status read
            val brokenStatusResponse =
                object : MockHttpServletResponse() {
                    override fun getStatus(): Int = throw IllegalStateException("status boom")
                }

            // When: the exchange runs to destruction
            handle(MockHttpServletRequest("GET", "/api/things"), brokenStatusResponse)

            // Then: confined and counted
            assertThat(appender.list).isEmpty()
            assertThat(stageCount("emission")).isEqualTo(1.0)
        }

        @Test
        fun `should re-raise the interrupt flag and count the emission when the emission is interrupted`() {
            // What is tested: the InterruptedException branch of the emission guard -
            //   an async appender can block interruptibly, so this path is reachable in production.
            // Success criteria: the event is dropped, emission=1, and the INTERRUPT FLAG is set again on the
            //   thread so a shutdown signal still reaches its addressee.
            // Why it matters: consuming an interrupt on a request-serving thread leaves whoever sent it
            //   waiting; the flag restoration is the whole point of the dedicated catch.
            // Given: a response whose header enumeration interrupts inside the guarded emission
            val interruptingResponse =
                object : MockHttpServletResponse() {
                    override fun getHeaderNames(): Collection<String> = throw InterruptedException("appender interrupt")
                }

            // When: the exchange runs to destruction
            handle(MockHttpServletRequest("GET", "/api/things"), interruptingResponse)

            // Then: dropped, counted, and the flag is set (read-and-clear so the test thread leaves clean)
            assertThat(appender.list).isEmpty()
            assertThat(stageCount("emission")).isEqualTo(1.0)
            assertThat(Thread.interrupted()).isTrue()
        }
    }

    @Nested
    inner class `Wiring stage` {
        @Test
        fun `should degrade to a pass-through when the wiring itself fails and still serve the request`() {
            // What is tested: the fail-open contract for the WIRING - a throwing
            //   host-provided CorrelationIdGenerator must not fail the request.
            // Success criteria: the chain still runs, nothing propagates, the wiring counter reads 1, and no
            //   event is emitted (the exchange was never wired).
            // Why it matters: before the fix, any pre-chain exception failed the request with a 500 -
            //   the exact outcome the documented fail-open contract rules out.
            // Given: a filter whose id generator throws
            val brokenWiringFilter =
                RequestLoggingFilter(
                    properties,
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { throw IllegalStateException("id generator boom") },
                    meterRegistry,
                )
            var chainRan = false
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: the exchange runs, destruction included
            brokenWiringFilter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> chainRan = true })
            brokenWiringFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the request was served unlogged, counted as a wiring failure
            assertThat(chainRan).isTrue()
            assertThat(stageCount("wiring")).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should keep serving and logging when the host registry already owns an endpoint meter of another type`() {
            // What is tested: fail-open meter registration (twin parity with the reactive module) -
            //   Micrometer rejects an id that exists with a different type.
            // Success criteria: the filter constructs against a registry that pre-registered the fail-open
            //   meter as a GAUGE and the body-size meter as a COUNTER; the exchange runs and its event is
            //   emitted; the host's meters are untouched and the conflicting ones stay private.
            // Why it matters: unguarded, the construction throw aborts the application context and the lazy
            //   body-size throw suppresses the whole exchange event.
            // Given: a host registry with conflicting meter types
            val hostRegistry = SimpleMeterRegistry()
            Gauge.builder(EndpointLoggingMetrics.FAIL_OPEN_METER) { 1.0 }.tag("stage", "emission").register(hostRegistry)
            Counter.builder(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).tag("uri", "UNKNOWN").register(hostRegistry)
            val conflicting =
                RequestLoggingFilter(
                    properties.copy(measureResponseBodySize = true, logResponseBody = true),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    hostRegistry,
                )
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: an exchange with a response body completes
            conflicting.doFilterInternal(
                request,
                MockHttpServletResponse(),
                FilterChain {
                    _,
                    res,
                    ->
                    res.outputStream.write("payload".toByteArray())
                },
            )
            conflicting.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the event was emitted with the body; host meters untouched; success counter in the host registry
            val event = appender.list.single()
            assertThat(event.keyValuePairs?.associate { it.key to it.value }).containsEntry("endpoint_response_body", "payload")
            assertThat(hostRegistry.find(EndpointLoggingMetrics.FAIL_OPEN_METER).gauge()).isNotNull()
            assertThat(hostRegistry.find(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary()).isNull()
            assertThat(
                hostRegistry
                    .find(EndpointLoggingMetrics.EVENTS_METER)
                    .tag("outcome", "success")
                    .counter()
                    ?.count(),
            ).isEqualTo(1.0)
        }

        /** A registry whose counters of [meterName] throw on increment; every other meter is healthy. */
        private fun registryThrowingOn(meterName: String): SimpleMeterRegistry =
            object : SimpleMeterRegistry() {
                override fun newCounter(id: Meter.Id): Counter =
                    if (id.name != meterName) {
                        super.newCounter(id)
                    } else {
                        object : Counter {
                            override fun increment(amount: Double) = throw IllegalStateException("counter boom")

                            override fun count(): Double = 0.0

                            override fun getId(): Meter.Id = id
                        }
                    }
            }

        private fun stageCount(
            registry: SimpleMeterRegistry,
            stage: String,
        ): Double =
            registry
                .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                .tag("stage", stage)
                .counter()
                .count()

        @Test
        fun `should keep logging the exchange when the correlation-source counter throws at wiring`() {
            // What is tested: the isolation of an OPERATIONAL counter from the exchange it observes
            // - registration succeeds, the increment
            //   throws, everything else is healthy.
            // Success criteria: the event is emitted as usual; the counter failure is counted
            //   stage=wiring; nothing propagates.
            // Why it matters: unguarded, the throw aborted the wiring and degraded the request to an
            //   unlogged pass-through - one broken metric suppressed viable exchange logging.
            // Given: a registry throwing on the correlation-source counter only
            val registry = registryThrowingOn(EndpointLoggingMetrics.CORRELATION_METER)
            val filter =
                RequestLoggingFilter(
                    properties,
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    registry,
                )
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: the exchange runs to destruction
            val thrown = catchThrowable { filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> }) }
            filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: logged, nothing escaped, the lost sample counted as wiring
            assertThat(thrown).isNull()
            assertThat(appender.list).singleElement().satisfies({ assertThat(it.formattedMessage).contains("-> 200") })
            assertThat(stageCount(registry, "wiring")).isEqualTo(1.0)
            assertThat(stageCount(registry, "emission")).isEqualTo(0.0)
        }

        @Test
        fun `should not report an emitted event as an emission failure when the events counter throws`() {
            // What is tested: the post-log() increment of the events counter - the line is already on the
            //   logger when it throws.
            // Success criteria: the event is on the appender, stage=emission stays 0 (the event was NOT
            //   lost) and the counter failure lands on stage=wiring.
            // Why it matters: the fail-open and events meters are the reconciliation signals for
            //   log-pipeline loss; a false "emission failed" for an emitted line makes them lie.
            // Given: a registry throwing on the events counter only
            val registry = registryThrowingOn(EndpointLoggingMetrics.EVENTS_METER)
            val filter =
                RequestLoggingFilter(
                    properties,
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    registry,
                )
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: the exchange runs to destruction
            filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
            filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: emitted, not counted as lost
            assertThat(appender.list).singleElement().satisfies({ assertThat(it.formattedMessage).contains("-> 200") })
            assertThat(stageCount(registry, "emission")).isEqualTo(0.0)
            assertThat(stageCount(registry, "wiring")).isEqualTo(1.0)
        }

        @Test
        fun `should still serve the request when the fail-open diagnostics themselves throw`() {
            // What is tested: the secondary guard around the catch handlers' diagnostics (twin parity
            //   with the reactive module) - the wiring fails AND the fail-open counter's increment throws.
            // Success criteria: the chain runs and nothing propagates out of the filter.
            // Why it matters: a throw escaping a catch handler before the chain fails the request with a 500.
            // Given: a registry whose counters throw on increment, and a filter whose wiring throws
            val throwingRegistry =
                object : SimpleMeterRegistry() {
                    override fun newCounter(id: Meter.Id): Counter =
                        object : Counter {
                            override fun increment(amount: Double) = throw IllegalStateException("counter boom")

                            override fun count(): Double = 0.0

                            override fun getId(): Meter.Id = id
                        }
                }
            val brokenTwice =
                RequestLoggingFilter(
                    properties,
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { throw IllegalStateException("id generator boom") },
                    throwingRegistry,
                )
            var chainRan = false
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: the exchange runs
            val thrown =
                catchThrowable {
                    brokenTwice.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> chainRan = true })
                }

            // Then: served, nothing escaped
            assertThat(thrown).isNull()
            assertThat(chainRan).isTrue()
        }

        @Test
        fun `should count broken post-chain wiring as stage wiring while the event itself still arrives`() {
            // What is tested: the wiring fail-open path in the filter's finally - here the async-marker
            //   registration fails, but the emission at request destruction is independent of it.
            // Success criteria: the wiring counter reads 1 AND the exchange event still exists - proving the
            //   stages are distinct: wiring loss degrades, emission loss silences.
            // Why it matters: an operator reading the metric must know whether an increment means "a line is
            //   missing" (emission) or "a line may be incomplete" (wiring).
            // Given: a request that CLAIMS async mode but has no async context - the listener registration in
            //   the finally then fails
            val lyingRequest = MockHttpServletRequest("GET", "/api/things")
            lyingRequest.setAsyncStarted(true)

            // When: the exchange runs to destruction (the marker registration fails in the finally,
            //   so the destruction completes immediately instead of deferring to an onComplete that
            //   can never come)
            handle(lyingRequest, MockHttpServletResponse())

            // Then: wiring counted once, and the event was still emitted at destruction
            assertThat(stageCount("wiring")).isEqualTo(1.0)
            assertThat(stageCount("emission")).isEqualTo(0.0)
            assertThat(appender.list).hasSize(1)
        }
    }

    @Nested
    inner class `Arrival stage` {
        @Test
        fun `should confine an arrival-line backend failure and count stage arrival`() {
            // What is tested: the arrival guard's coverage -
            //   the logger's level gate is a call into the host's logging backend and must sit INSIDE the
            //   fail-open guard; before the fix an exception from isInfoEnabled escaped logRequestStart and
            //   failed the request before the chain ran.
            // Success criteria: with a logging backend whose level check throws (a throwing TurboFilter -
            //   logback consults turbo filters inside isInfoEnabled), the request is served untouched and
            //   the loss is counted as stage arrival; the completion emission hits the same broken backend
            //   and is confined by ITS guard as an emission loss.
            // Why it matters: the arrival line is OPTIONAL observability; it failing the exchange would
            //   invert the module's central fail-open contract.
            // Given: start-line logging against a backend that throws on the level check for this logger
            val arrivalLoggerName = "http-exchange-servlet-arrival-boom"
            val arrivalFilter =
                RequestLoggingFilter(
                    properties.copy(logRequestStart = true, loggerName = arrivalLoggerName),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    meterRegistry,
                )
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
                        if (logger.name == arrivalLoggerName) {
                            throw IllegalStateException("backend boom")
                        } else {
                            FilterReply.NEUTRAL
                        }
                }
            loggerContext.addTurboFilter(bomb)
            try {
                val request = MockHttpServletRequest("GET", "/api/things")
                var chainRan = false

                // When: the exchange runs to destruction
                arrivalFilter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> chainRan = true })
                arrivalFilter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

                // Then: served, the arrival loss confined and counted, the emission loss likewise
                assertThat(chainRan).isTrue()
                assertThat(stageCount("arrival")).isEqualTo(1.0)
                assertThat(stageCount("emission")).isEqualTo(1.0)
            } finally {
                loggerContext.turboFilterList.remove(bomb)
            }
        }
    }
}
