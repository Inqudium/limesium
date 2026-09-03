package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import eu.inqudium.limesium.common.BodyLogMode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchangeDecorator
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicLong

/**
 * The meters of the reactive twin - identical names and semantics to the servlet module's, with the
 * reactive outcome vocabulary (`cancelled`), plus the fail-open stages proven by injected failures.
 */
class RequestLoggingWebFilterMetricsTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = RequestLoggingProperties(loggerName = "http-exchange-reactive-metrics-test")
    private val filter =
        RequestLoggingWebFilter(properties, { ticker.get() }, { "generated-42" }, meterRegistry)

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

    private fun eventCount(outcome: String): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.EVENTS_METER)
            .tag("outcome", outcome)
            .counter()
            .count()

    private fun stageCount(stage: String): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
            .tag("stage", stage)
            .counter()
            .count()

    private fun openExchanges(): Double = meterRegistry.get(EndpointLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()

    @Nested
    inner class `Counters and gauge` {
        @Test
        fun `should pre-register the reactive outcome vocabulary and count emitted events per outcome`() {
            // What is tested: the reactive events counter carries the cancelled outcome the servlet twin
            //   does not have, pre-registered like everything else.
            // Success criteria: success/failure/cancelled exist at zero; a success and a cancellation count
            //   one each on their side.
            // Why it matters: the reconciliation ground truth must cover every disposition this stack emits.
            // Given: a fresh registry - all three outcomes already exist at zero
            assertThat(eventCount("success")).isEqualTo(0.0)
            assertThat(eventCount("failure")).isEqualTo(0.0)
            assertThat(eventCount("cancelled")).isEqualTo(0.0)

            // When: one clean exchange and one cancelled exchange
            val ok = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            filter
                .filter(
                    ok,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()
            val cancelled = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            filter.filter(cancelled, WebFilterChain { Mono.never() }).subscribe().dispose()

            // Then: one count on each side
            assertThat(eventCount("success")).isEqualTo(1.0)
            assertThat(eventCount("cancelled")).isEqualTo(1.0)
        }

        @Test
        fun `should share one metrics owner between two filters on the same registry`() {
            // What is tested: the per-registry metrics ownership - a second filter wired against the
            //   SAME registry must observe through the shared owner, not through a duplicate instance
            //   whose gauge registration Micrometer would silently ignore.
            // Success criteria: an exchange handled by the SECOND filter moves the registry's
            //   open-exchanges gauge to 1 mid-flight and back to 0 at completion.
            // Why it matters: with a duplicate owner the second filter's live exchanges were invisible
            //   on the gauge - exactly the wiring a host reaches by constructing filters manually.
            // Given: a second filter against the same registry, a chain observing the gauge mid-flight
            val second = RequestLoggingWebFilter(properties, { ticker.get() }, { "generated-43" }, meterRegistry)
            var openDuringChain = -1.0
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the second filter handles a clean exchange
            second
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        openDuringChain = openExchanges()
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: the shared gauge saw the second filter's exchange
            assertThat(openDuringChain).isEqualTo(1.0)
            assertThat(openExchanges()).isEqualTo(0.0)
        }

        @Test
        fun `should keep the open-exchanges gauge up while an error waits for its commit`() {
            // What is tested: the gauge as the liveness signal of the commit-deferred error path.
            // Success criteria: after the error signal the gauge still reads 1 (the exchange is awaiting the
            //   rendered status); after the commit it reads 0 and the event exists.
            // Why it matters: an error whose commit never happens must stay VISIBLE - the gauge baseline is
            //   the only signal for that silent-loss mode, exactly as in the servlet twin.
            // Given: a failing chain
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            runCatching { filter.filter(exchange, WebFilterChain { Mono.error(IllegalStateException("boom")) }).block() }

            // Then: still open, nothing emitted
            assertThat(openExchanges()).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()

            // When: the upstream handler renders and the response commits
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            exchange.response.setComplete().block()

            // Then: closed and emitted
            assertThat(openExchanges()).isEqualTo(0.0)
            assertThat(appender.list).hasSize(1)
        }
    }

    @Nested
    inner class `Fail-open stages` {
        @Test
        fun `should degrade to a pass-through and count stage wiring when a host bean throws`() {
            // What is tested: the wiring fail-open contract, identical to the servlet twin.
            // Success criteria: the chain still runs, nothing propagates, wiring=1, no event.
            // Why it matters: a logging component must never fail the request it describes.
            // Given: a filter whose id generator throws
            val brokenWiring =
                RequestLoggingWebFilter(
                    properties,
                    { ticker.get() },
                    { throw IllegalStateException("id generator boom") },
                    meterRegistry,
                )
            var chainRan = false
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the exchange runs
            brokenWiring
                .filter(
                    exchange,
                    WebFilterChain {
                        chainRan = true
                        Mono.empty()
                    },
                ).block()

            // Then: served unlogged, counted
            assertThat(chainRan).isTrue()
            assertThat(stageCount("wiring")).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should keep serving and logging when the host registry already owns an endpoint meter of another type`() {
            // What is tested: fail-open meter registration - Micrometer rejects an id that exists
            //   with a different type.
            // Success criteria: the filter constructs against a registry that pre-registered the fail-open
            //   meter as a GAUGE and the body-size meter as a COUNTER; the exchange runs and its event is
            //   emitted; the conflicting meters stay private (the host's meters are untouched).
            // Why it matters: unguarded, the construction throw aborts the application context and the lazy
            //   body-size throw suppresses the whole exchange event - a logging library must do neither.
            // Given: a host registry with conflicting meter types
            val hostRegistry = SimpleMeterRegistry()
            Gauge.builder(EndpointLoggingMetrics.FAIL_OPEN_METER) { 1.0 }.tag("stage", "emission").register(hostRegistry)
            Counter.builder(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).tag("uri", "UNKNOWN").register(hostRegistry)
            val conflicting =
                RequestLoggingWebFilter(
                    properties.copy(measureResponseBodySize = true, logResponseBody = BodyLogMode.ALWAYS),
                    { ticker.get() },
                    { "generated-42" },
                    hostRegistry,
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: an exchange with a response body completes
            conflicting
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        ex.response.writeWith(
                            Mono.just(DefaultDataBufferFactory.sharedInstance.wrap("payload".toByteArray())),
                        )
                    },
                ).block()

            // Then: the event was emitted with the body, and the host's own meters are untouched
            val event = appender.list.single()
            assertThat(event.keyValuePairs?.associate { it.key to it.value }).containsEntry("endpoint_response_body", "payload")
            assertThat(hostRegistry.find(EndpointLoggingMetrics.FAIL_OPEN_METER).gauge()).isNotNull()
            assertThat(hostRegistry.find(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).counter()).isNotNull()
            assertThat(hostRegistry.find(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).summary()).isNull()
            // And: the non-conflicting meters still landed in the host registry
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

        @Test
        fun `should keep logging the exchange when the correlation-source counter throws at wiring`() {
            // What is tested: the isolation of an OPERATIONAL counter from the exchange it observes
            // - registration succeeds, the increment
            //   throws, and everything else is healthy.
            // Success criteria: the event is emitted as usual; the counter failure is counted
            //   stage=wiring; nothing propagates.
            // Why it matters: unguarded, the throw aborted the wiring and degraded the request to an
            //   unlogged pass-through - one broken metric suppressed viable exchange logging.
            // Given: a registry throwing on the correlation-source counter only
            val registry = registryThrowingOn(EndpointLoggingMetrics.CORRELATION_METER)
            val filter = RequestLoggingWebFilter(properties, { ticker.get() }, { "generated-42" }, registry)
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the exchange runs
            val thrown =
                catchThrowable {
                    filter
                        .filter(
                            exchange,
                            WebFilterChain { ex ->
                                ex.response.statusCode = HttpStatus.OK
                                Mono.empty()
                            },
                        ).block()
                }

            // Then: logged, nothing escaped, the lost sample counted as wiring
            assertThat(thrown).isNull()
            assertThat(appender.list).singleElement().satisfies({ assertThat(it.formattedMessage).contains("-> 200") })
            assertThat(
                registry
                    .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
            assertThat(
                registry
                    .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "emission")
                    .counter()
                    .count(),
            ).isEqualTo(0.0)
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
            val filter = RequestLoggingWebFilter(properties, { ticker.get() }, { "generated-42" }, registry)
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the exchange runs
            filter
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: emitted, not counted as lost
            assertThat(appender.list).singleElement().satisfies({ assertThat(it.formattedMessage).contains("-> 200") })
            assertThat(
                registry
                    .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "emission")
                    .counter()
                    .count(),
            ).isEqualTo(0.0)
            assertThat(
                registry
                    .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                    .tag("stage", "wiring")
                    .counter()
                    .count(),
            ).isEqualTo(1.0)
        }

        @Test
        fun `should still serve the request when the fail-open diagnostics themselves throw`() {
            // What is tested: the secondary guard around the catch handlers' diagnostics - the wiring
            //   fails AND the fail-open counter's increment throws.
            // Success criteria: the chain runs and nothing propagates out of the filter.
            // Why it matters: a throw escaping a catch handler before the chain fails request assembly -
            //   the one outcome the fail-open contract forbids.
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
                RequestLoggingWebFilter(
                    properties,
                    { ticker.get() },
                    { throw IllegalStateException("id generator boom") },
                    throwingRegistry,
                )
            var chainRan = false
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the exchange runs
            val thrown =
                catchThrowable {
                    brokenTwice
                        .filter(
                            exchange,
                            WebFilterChain {
                                chainRan = true
                                Mono.empty()
                            },
                        ).block()
                }

            // Then: served, nothing escaped
            assertThat(thrown).isNull()
            assertThat(chainRan).isTrue()
        }

        @Test
        fun `should count a broken emission as stage emission and never disturb the exchange`() {
            // What is tested: the emission guard covers the whole emission including its pre-gate section -
            //   here the injected time source throws on its emission-time read.
            // Success criteria: no exception surfaces, no event, emission=1.
            // Why it matters: the emission counter is the metric channel for exactly this loss.
            // Given: a time source that works at wiring time and throws at emission time
            var calls = 0
            val brokenClock =
                RequestLoggingWebFilter(
                    properties,
                    { if (calls++ == 0) 0L else throw IllegalStateException("clock boom") },
                    { "generated-42" },
                    meterRegistry,
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the exchange completes
            brokenClock
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: confined and counted
            assertThat(appender.list).isEmpty()
            assertThat(stageCount("emission")).isEqualTo(1.0)
        }

        @Test
        fun `should confine a terminal-callback failure and still emit the event`() {
            // What is tested: the doFinally guard - bookkeeping there is fallible
            //   (attribute access, breadcrumb), and an escaping exception would be rethrown into Reactor's
            //   signal propagation.
            // Success criteria: with an attributes map that throws, nothing propagates, wiring=1, and the
            //   event is STILL emitted - a broken breadcrumb costs detail, never the event.
            // Why it matters: this callback runs for every exchange; it must be as fail-open as the rest.
            // Given: an exchange whose attributes access throws inside the terminal callback
            val mock = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val brokenAttributes =
                object : ServerWebExchangeDecorator(mock) {
                    override fun getAttributes(): MutableMap<String, Any> = throw IllegalStateException("attributes boom")
                }

            // When: the exchange completes
            filter
                .filter(
                    brokenAttributes,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: confined, counted, and the event survived
            assertThat(stageCount("wiring")).isEqualTo(1.0)
            assertThat(appender.list).hasSize(1)
        }

        @Test
        fun `should confine a commit-callback failure instead of disturbing the response commit`() {
            // What is tested: the beforeCommit guard - the callback runs INSIDE the
            //   response-commit chain, where an escaping exception would disturb the commit itself.
            // Success criteria: with a status read that throws at commit time, setComplete() completes
            //   normally, the deferred event is lost but COUNTED as an emission failure.
            // Why it matters: disturbing the commit is the one outcome the fail-open contract forbids.
            // Given: a deferred-error exchange whose response throws on the commit-time status read
            val mock = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val throwingResponse =
                object : ServerHttpResponseDecorator(mock.response) {
                    override fun getStatusCode(): HttpStatusCode = throw IllegalStateException("status boom")
                }
            val decorated =
                object : ServerWebExchangeDecorator(mock) {
                    override fun getResponse(): ServerHttpResponse = throwingResponse
                }
            runCatching { filter.filter(decorated, WebFilterChain { Mono.error(IllegalStateException("boom")) }).block() }

            // When: the upstream handler renders and the response commits
            mock.response.setComplete().block()

            // Then: the commit went through, the lost emission is counted
            assertThat(stageCount("emission")).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should route a synchronous downstream throw into the deferred error path instead of leaking the gauge`() {
            // What is tested: a downstream WebFilter that THROWS while assembling its publisher instead of
            //   returning Mono.error - before the fix the
            //   exception bypassed doOnError/doFinally entirely, lost the event, and left the open-exchange
            //   gauge permanently inflated.
            // Success criteria: the throw surfaces as the pipeline's error signal (block() throws it), the
            //   exchange stays OPEN awaiting the rendered status, and the commit then closes the gauge and
            //   emits exactly one ERROR event - byte-identical semantics to the Mono.error path.
            // Why it matters: repeated occurrences of the leak would make the liveness gauge report a false
            //   logging-pipeline outage, and each occurrence silently lost an exchange event.
            // Given: a chain that throws synchronously
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val thrown =
                org.assertj.core.api.Assertions.catchThrowable {
                    filter.filter(exchange, WebFilterChain { throw IllegalStateException("sync boom") }).block()
                }

            // Then: surfaced as an error signal, exchange open and deferred - not leaked
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessage("sync boom")
            assertThat(openExchanges()).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()

            // When: the upstream handler renders and the response commits
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            exchange.response.setComplete().block()

            // Then: gauge closed, one ERROR event with the rendered status
            assertThat(openExchanges()).isEqualTo(0.0)
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
        }

        @Test
        fun `should leave the exchange open when an earlier commit action fails the commit`() {
            // What is tested: the residual boundary of the late-registered commit callback - Spring
            //   concatenates the actions, so an action
            //   registered by the chain that FAILS stops the sequence before the module's callback runs.
            // Success criteria: the commit attempt fails, no event is emitted, and the exchange stays on
            //   the open-exchanges gauge - the documented never-commits semantics - instead of logging a
            //   status the client never received.
            // Why it matters: the gauge is the liveness signal for this loss mode; an early callback
            //   would have logged a "committed" status for a commit that then failed.
            // Given: a chain whose commit action fails, then errors
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ex.response.beforeCommit { Mono.error(IllegalStateException("commit action boom")) }
                    Mono.error(IllegalStateException("boom"))
                }
            runCatching { filter.filter(exchange, chain).block() }

            // When: the commit is attempted and fails in the earlier action
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            val commit =
                org.assertj.core.api.Assertions
                    .catchThrowable { exchange.response.setComplete().block() }

            // Then: failed commit, nothing logged, exchange still open
            assertThat(commit).hasMessage("commit action boom")
            assertThat(appender.list).isEmpty()
            assertThat(openExchanges()).isEqualTo(1.0)
        }

        @Test
        fun `should stay fail-open when the commit-callback registration itself throws`() {
            // What is tested: the registration half of the commit-callback contract - beforeCommit runs
            //   against a possibly host-provided response facade,
            //   and before the fix a throw there failed the request AFTER the gauge was incremented. The
            //   registration happens at the ERROR signal,
            //   so an erroring chain is what exercises it.
            // Success criteria: nothing propagates beyond the chain's own error, the failure is counted
            //   as stage wiring, and the exchange STILL completes at the terminal signal instead of
            //   deferring to a callback that will never run - event emitted, gauge back at zero.
            // Why it matters: this was the one wiring step outside the fail-open boundary; a logging
            //   component must never fail the request it describes.
            // Given: a response facade that rejects beforeCommit registration
            val mock = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val rejectingResponse =
                object : ServerHttpResponseDecorator(mock.response) {
                    override fun beforeCommit(action: java.util.function.Supplier<out Mono<Void>>): Unit = throw IllegalStateException("register boom")
                }
            val decorated =
                object : ServerWebExchangeDecorator(mock) {
                    override fun getResponse(): ServerHttpResponse = rejectingResponse
                }
            var chainRan = false

            // When: the exchange runs and errors - the chain's own error is all that surfaces
            val thrown =
                org.assertj.core.api.Assertions.catchThrowable {
                    filter
                        .filter(
                            decorated,
                            WebFilterChain {
                                chainRan = true
                                Mono.error(IllegalStateException("boom"))
                            },
                        ).block()
                }

            // Then: served, confined and counted, event emitted at the terminal signal, gauge closed
            assertThat(chainRan).isTrue()
            assertThat(thrown).hasMessage("boom")
            assertThat(stageCount("wiring")).isEqualTo(1.0)
            assertThat(appender.list).hasSize(1)
            assertThat(openExchanges()).isEqualTo(0.0)
        }

        @Test
        fun `should confine an arrival-line backend failure and count stage arrival`() {
            // What is tested: the arrival guard's coverage -
            //   the logger-level gate and MDC-scope construction are backend calls and must sit INSIDE the
            //   fail-open guard; before the fix an exception from the level lookup escaped logRequestStart
            //   and failed the request during filter assembly.
            // Success criteria: with a logging backend whose level check throws (a throwing TurboFilter -
            //   logback consults turbo filters inside isInfoEnabled), the request is served untouched and
            //   the loss is counted as stage arrival.
            // Why it matters: the arrival line is OPTIONAL observability; it failing the exchange would
            //   invert the module's central contract.
            // Given: start-line logging against a backend that throws on the level check for this logger
            val arrivalLoggerName = "http-exchange-reactive-arrival-boom"
            val arrivalFilter =
                RequestLoggingWebFilter(
                    properties.copy(logRequestStart = true, loggerName = arrivalLoggerName),
                    { ticker.get() },
                    { "generated-42" },
                    meterRegistry,
                )
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val bomb =
                object : TurboFilter() {
                    override fun decide(
                        marker: org.slf4j.Marker?,
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
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
                var chainRan = false

                // When: the exchange runs
                arrivalFilter
                    .filter(
                        exchange,
                        WebFilterChain { ex ->
                            chainRan = true
                            ex.response.statusCode = HttpStatus.OK
                            Mono.empty()
                        },
                    ).block()

                // Then: served, the arrival loss confined and counted (the completion emission hits the
                //   same broken backend and is confined by ITS guard as an emission loss)
                assertThat(chainRan).isTrue()
                assertThat(stageCount("arrival")).isEqualTo(1.0)
                assertThat(stageCount("emission")).isEqualTo(1.0)
            } finally {
                loggerContext.turboFilterList.remove(bomb)
            }
        }
    }

    @Nested
    inner class `Body size distributions` {
        @Test
        fun `should record body sizes under the handler pattern independent of the level gate`() {
            // Given: measuring on, logging gated to ERROR, a chain that writes and carries a pattern
            val measuring =
                RequestLoggingWebFilter(
                    properties.copy(measureResponseBodySize = true, loggerName = "http-exchange-reactive-metrics-gated"),
                    { ticker.get() },
                    { "generated-42" },
                    meterRegistry,
                )
            val gatedLogger = LoggerFactory.getLogger("http-exchange-reactive-metrics-gated") as Logger
            gatedLogger.level = Level.ERROR
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things/9"))
            val chain =
                WebFilterChain { ex ->
                    ex.attributes[RequestLoggingWebFilter.BEST_MATCHING_PATTERN_ATTRIBUTE] = "/api/things/{id}"
                    ex.response.statusCode = HttpStatus.OK
                    ex.response.writeWith(
                        Mono.just(
                            DefaultDataBufferFactory.sharedInstance
                                .wrap("data".toByteArray()),
                        ),
                    )
                }

            // When: the exchange completes (no event - the gate is at ERROR)
            try {
                measuring.filter(exchange, chain).block()
            } finally {
                gatedLogger.level = null
            }

            // Then: the size sample exists under the template tag anyway
            val summary =
                meterRegistry
                    .get(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER)
                    .tag("uri", "/api/things/{id}")
                    .summary()
            assertThat(summary.totalAmount()).isEqualTo(4.0)
        }
    }

    @Nested
    inner class `Request body read counter` {
        private val measuring =
            RequestLoggingWebFilter(properties.copy(measureRequestBodySize = true), { ticker.get() }, { "generated-42" }, meterRegistry)

        private fun readCount(
            state: String,
            uri: String = EndpointLoggingMetrics.UNTEMPLATED_URI,
        ): Double =
            meterRegistry
                .find(EndpointLoggingMetrics.REQUEST_BODY_READ_METER)
                .tag("uri", uri)
                .tag("state", state)
                .counter()
                ?.count() ?: 0.0

        private fun postExchange(vararg chunks: String): MockServerWebExchange =
            MockServerWebExchange.from(
                MockServerHttpRequest
                    .post("/api/things")
                    .body(Flux.fromIterable(chunks.map { DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()) })),
            )

        @Test
        fun `should count a fully consumed body as complete under the handler pattern`() {
            // Given: a chain that drains the body and records a pattern
            val chain =
                WebFilterChain { ex ->
                    ex.attributes[RequestLoggingWebFilter.BEST_MATCHING_PATTERN_ATTRIBUTE] = "/api/things"
                    ex.request.body.then()
                }

            // When: the exchange completes
            measuring.filter(postExchange("hel", "lo"), chain).block()

            // Then: complete under the template
            assertThat(readCount("complete", "/api/things")).isEqualTo(1.0)
            assertThat(readCount("partial", "/api/things")).isEqualTo(0.0)
        }

        @Test
        fun `should count a body the application cancelled mid-stream as partial`() {
            // What is tested: the tee observes the application's subscription only - a `take(1)` over a
            //   two-chunk body cancels upstream, so no completion signal ever arrives.
            // Success criteria: partial, with only the first chunk in the size sample.
            // Why it matters: the reactive counterpart of a parser bailing out early; a tee that drained
            //   the rest to find out would alter the request's backpressure.
            // Given: a chain taking one chunk of two
            val chain =
                WebFilterChain { ex ->
                    ex.request.body
                        .take(1)
                        .then()
                }

            // When: the exchange completes
            measuring.filter(postExchange("hel", "lo"), chain).block()

            // Then: partial, three bytes flowed
            assertThat(readCount("partial")).isEqualTo(1.0)
            assertThat(readCount("complete")).isEqualTo(0.0)
            val summary =
                meterRegistry
                    .get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER)
                    .tag("uri", EndpointLoggingMetrics.UNTEMPLATED_URI)
                    .summary()
            assertThat(summary.totalAmount()).isEqualTo(3.0)
        }

        @Test
        fun `should count a body the application never subscribed to as unread`() {
            // What is tested: the distinction neither the logged body nor the size sample can make - a
            //   body that was SENT but never READ.
            // Success criteria: unread counted, no size sample.
            // Why it matters: an endpoint silently ignoring its payload looks identical to a bodyless
            //   request in every other signal of this module.
            // Given: a chain that ignores the request
            val chain = WebFilterChain { _ -> Mono.empty() }

            // When: the exchange completes
            measuring.filter(postExchange("hello"), chain).block()

            // Then
            assertThat(readCount("unread")).isEqualTo(1.0)
            assertThat(meterRegistry.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summaries()).isEmpty()
        }

        @Test
        fun `should record nothing when request body measuring is off`() {
            // Given: the default filter (no measuring), a chain that reads
            val chain = WebFilterChain { ex -> ex.request.body.then() }

            // When
            filter.filter(postExchange("hello"), chain).block()

            // Then: the counter does not exist - the opt-in is the measuring flag
            assertThat(meterRegistry.find(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).counters()).isEmpty()
        }
    }
}
