package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * The coroutine variant of the web filter: output identity with the Reactor variant (same
 * [ExchangeLifecycle]), the signal mapping expressed as suspend try/catch, and the coroutine-native
 * addition - suspend handlers inherit the `endpoint_*` MDC via [kotlinx.coroutines.slf4j.MDCContext],
 * proven across a REAL dispatcher hop. Driven through [CoWebFilter]'s final Reactor bridge
 * (`filter(exchange, chain).block()`), exactly as the framework drives it.
 */
class CoRequestLoggingWebFilterTest {
    private val ticker = AtomicLong(0)
    private val properties = RequestLoggingProperties(loggerName = "http-exchange-co-test")
    private val meterRegistry = SimpleMeterRegistry()
    private val filter =
        CoRequestLoggingWebFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            meterRegistry,
        )
    private lateinit var originalMdcAdapter: MDCAdapter

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        originalMdcAdapter = MDC.getMDCAdapter()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        installMdcAdapter(originalMdcAdapter)
        logger.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    @Test
    fun `should log the identical line format of the reactor variant`() {
        // What is tested: output identity - both variants share the ExchangeLifecycle, and this pins it
        //   end to end through the CoWebFilter bridge.
        // Success criteria: the exact message string and field family of the reactor variant's test.
        // Why it matters: the variant choice is a classpath detail; dashboards must never see it.
        // Given: a GET answered 200 after 42 ms of measured work
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
        val chain =
            WebFilterChain { ex ->
                ticker.addAndGet(42_000_000)
                ex.response.statusCode = HttpStatus.OK
                Mono.empty()
            }

        // When: the filter handles the exchange
        filter.filter(exchange, chain).block()

        // Then: one INFO line, format-identical
        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage)
            .isEqualTo("Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_duration_ms", 42L)
            .containsEntry("endpoint_response_status_code", 200)
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
    }

    @Test
    fun `should hand suspend handlers the endpoint MDC across a real dispatcher hop`() {
        // What is tested: the coroutine-native MDC parity - the chain runs under MDCContext, CoWebFilter
        //   publishes the coroutine context to the handler invocation, and MDCContext restores the
        //   identity on every resumption.
        // Success criteria: a simulated suspend handler (running the published context, hopping to
        //   Dispatchers.Default) sees all three endpoint_* MDC entries on a foreign thread; nothing
        //   leaks onto the calling thread.
        // Why it matters: this is THE reason the coroutine variant exists - handler logs in coroutine
        //   apps carry the correlation id with no context-propagation dependency.
        // Given: a chain that behaves like WebFlux invoking a suspend handler: it picks up the published
        //   coroutine context and resumes on another dispatcher
        var handlerMdc: Map<String, String?> = emptyMap()
        var handlerThread = ""
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things/7"))
        val chain =
            WebFilterChain { ex ->
                @Suppress("UNCHECKED_CAST")
                val publishedContext = ex.attributes[COROUTINE_CONTEXT_ATTRIBUTE] as CoroutineContext
                mono(publishedContext) {
                    withContext(Dispatchers.Default) {
                        handlerThread = Thread.currentThread().name
                        handlerMdc =
                            listOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE).associateWith { MDC.get(it) }
                    }
                    ex.response.statusCode = HttpStatus.OK
                }.then()
            }

        // When: the filter handles the exchange
        filter.filter(exchange, chain).block()

        // Then: the identity was visible inside the handler on a foreign thread, and did not leak here
        assertThat(handlerThread).isNotEqualTo(Thread.currentThread().name).contains("DefaultDispatcher")
        assertThat(handlerMdc)
            .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
            .containsEntry(MdcKeys.ROUTE, "/api/things/7")
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
    }

    @Test
    fun `should run the chain without handler MDC and still log when the ambient MDC snapshot throws`() {
        // What is tested: the fail-open boundary around the ambient MDC snapshot (finding 2 of the
        //   internal analysis) - MDC.getCopyOfContextMap() is a host-adapter call made by the
        //   filter, outside the handler's try/catch.
        // Success criteria: the chain runs and completes normally, nothing propagates to the host, the
        //   exchange event is emitted with outcome success, the degradation is counted stage=wiring, and
        //   the open-exchanges gauge is back at zero.
        // Why it matters: before the guard the adapter failure failed every coroutine request AND leaked
        //   the gauge entry, blamed on application code - the one outcome the fail-open contract forbids.
        // Given: an adapter whose snapshot throws
        installMdcAdapter(
            object : MDCAdapter by originalMdcAdapter {
                override fun getCopyOfContextMap(): MutableMap<String, String>? = throw IllegalStateException("adapter snapshot boom")
            },
        )
        var chainRan = false
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
        val chain =
            WebFilterChain { ex ->
                chainRan = true
                ex.response.statusCode = HttpStatus.OK
                Mono.empty()
            }

        // When: the filter handles the exchange
        val thrown = catchThrowable { filter.filter(exchange, chain).block() }

        // Then: served and logged without the handler MDC, degradation counted, gauge closed
        assertThat(thrown).isNull()
        assertThat(chainRan).isTrue()
        assertThat(appender.list).singleElement().satisfies({ assertThat(keyValues(it)).containsEntry("endpoint_outcome", "success") })
        assertThat(
            meterRegistry
                .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
                .tag("stage", "wiring")
                .counter()
                .count(),
        ).isEqualTo(1.0)
        assertThat(meterRegistry.get(EndpointLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()).isEqualTo(0.0)
    }

    @Test
    fun `should preserve ambient MDC entries under the endpoint overlay inside suspend handlers`() {
        // What is tested: the ADDITIVE MDC contract (review finding 2) -
        //   MDCContext installs its map as the coroutine's COMPLETE MDC, so the filter must snapshot the
        //   ambient MDC (trace ids, host keys) and overlay only the endpoint_* identity; an
        //   implementation handing MDCContext just the three identity keys would delete everything else
        //   on every resumption.
        // Success criteria: inside the handler on a foreign dispatcher thread, the seeded trace and host
        //   entries are visible TOGETHER with all three endpoint_* keys; after completion the calling
        //   thread's MDC still carries the seeded entries and no endpoint_* residue.
        // Why it matters: replacing the ambient MDC silently destroys trace correlation in every
        //   coroutine application that already carries tracing or tenant context - the exact data loss
        //   the previous single-key proof (finding 9) could never detect.
        // Given: ambient MDC carrying a trace id and an unrelated host entry
        MDC.put("traceId", "ambient-trace")
        MDC.put("tenant", "acme")
        var handlerMdc: Map<String, String?> = emptyMap()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things/7"))
        val chain =
            WebFilterChain { ex ->
                @Suppress("UNCHECKED_CAST")
                val publishedContext = ex.attributes[COROUTINE_CONTEXT_ATTRIBUTE] as CoroutineContext
                mono(publishedContext) {
                    withContext(Dispatchers.Default) {
                        handlerMdc =
                            listOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE, "traceId", "tenant")
                                .associateWith { MDC.get(it) }
                    }
                    ex.response.statusCode = HttpStatus.OK
                }.then()
            }

        // When: the filter handles the exchange
        filter.filter(exchange, chain).block()

        // Then: ambient and identity entries were visible together inside the handler
        assertThat(handlerMdc)
            .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
            .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
            .containsEntry(MdcKeys.ROUTE, "/api/things/7")
            .containsEntry("traceId", "ambient-trace")
            .containsEntry("tenant", "acme")

        // And: the calling thread's MDC was restored exactly - ambient intact, no endpoint residue
        assertThat(MDC.get("traceId")).isEqualTo("ambient-trace")
        assertThat(MDC.get("tenant")).isEqualTo("acme")
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull()
    }

    @Test
    fun `should defer the error emission until the response commits with the rendered status`() {
        // Given: a failing chain (the suspend variant surfaces it as a thrown exception)
        val boom = IllegalStateException("boom")
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

        // When: the chain errors (response still uncommitted)
        val thrown = catchThrowable { filter.filter(exchange, WebFilterChain { Mono.error(boom) }).block() }

        // Then: propagated - possibly as kotlinx's stacktrace-RECOVERED copy with the original as its
        //   cause (the same coroutine-boundary behavior sync-bridge documents); type, message and the
        //   reachable original are what upstream error handling classifies on. No event yet.
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessage("boom")
        assertThat(thrown === boom || thrown!!.cause === boom).isTrue()
        assertThat(appender.list).isEmpty()

        // When: the upstream error handling renders and commits the response
        exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
        exchange.response.setComplete().block()

        // Then: one ERROR event with the rendered status and the cause
        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
    }

    @Test
    fun `should observe status and header mutations of later commit actions on the deferred error path`() {
        // What is tested: the commit-action ordering of the deferred error path in the COROUTINE
        //   variant (finding 1 of the internal analysis) - the callback is registered in the
        //   catch block, after the chain ran, behind every action a downstream filter registered.
        // Success criteria: a later action turns the rendered 500 into a 503 and adds a selected
        //   header; the single ERROR event carries both.
        // Why it matters: parity - both variants share the lifecycle, and both must log what the
        //   response applied, not what an early callback saw.
        // Given: a filter selecting the late header, and a chain registering the later action, then failing
        val selecting =
            CoRequestLoggingWebFilter(
                properties.copy(responseHeaders = HeaderLogProperties(includes = listOf("X-Late"))),
                NanoTimeSource { ticker.get() },
                CorrelationIdGenerator { "generated-42" },
                SimpleMeterRegistry(),
            )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
        val chain =
            WebFilterChain { ex ->
                ex.response.beforeCommit {
                    Mono.fromRunnable {
                        ex.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                        ex.response.headers.add("X-Late", "late")
                    }
                }
                Mono.error(IllegalStateException("boom"))
            }
        catchThrowable { selecting.filter(exchange, chain).block() }
        assertThat(appender.list).isEmpty()

        // When: the upstream error handling renders 500 and commits - the later action then mutates
        exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
        exchange.response.setComplete().block()

        // Then: the event carries what the response applied
        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 503)
        assertThat(keyValues(event)["endpoint_response_headers"].toString()).contains("X-Late:\"late\"")
    }

    @Test
    fun `should log outcome cancelled when the client disconnects`() {
        // Given: a chain that never completes
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
        val subscription = filter.filter(exchange, WebFilterChain { Mono.never() }).subscribe()

        // When: the client disconnects
        subscription.dispose()

        // Then: WARN, cancelled, no invented status
        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "cancelled")
            .doesNotContainKey("endpoint_response_status_code")
    }

    private companion object {
        /** The attribute [CoWebFilter] publishes the active coroutine context under. */
        val COROUTINE_CONTEXT_ATTRIBUTE: String = CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE
    }
}
