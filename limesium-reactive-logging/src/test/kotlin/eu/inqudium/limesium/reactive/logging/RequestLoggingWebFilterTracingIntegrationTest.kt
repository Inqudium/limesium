package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.tracing.handler.TracingObservationHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The trace contract against a REAL Micrometer Tracing bridge (Brave) and a real Netty server - the
 * reactive counterpart of the servlet twin's `RequestLoggingFilterTomcatTracingIntegrationTest`, for a
 * DIFFERENT design: this module never reads the bridge's MDC (the event-loop thread carries none at
 * filter time); it parses the caller's W3C `traceparent` header. What that design promises, and what
 * only a real bridge can prove, is pinned here:
 *
 * - **The join holds.** The `traceId` the module logs is the trace the server span actually runs under:
 *   Boot's default propagation consumes W3C, so the bridge continues the caller's trace and the two ids
 *   agree - the handler reads the bridge's span through the observation context and returns it, and the
 *   event's MDC must carry the same trace id.
 * - **The caller's span is never the local span.** The header's parent-id is published as
 *   `parentSpanId`; the `spanId` key belongs to the bridge and is never written by this module - the server
 *   span's own id differs from the parent, and
 *   the event must not carry the parent under `spanId`.
 * - **The boundary is explicit.** Without a caller `traceparent` the bridge still traces the exchange,
 *   but the module logs no trace context at all - the documented limitation, pinned so that a change of that
 *   decision is conscious.
 * - **The identity follows the trace (ADR-0002).** A traced exchange's `endpoint_request_id` IS the
 *   caller's trace id and gets NO `X-Correlation-Id` echo; a traceless exchange keeps the correlation
 *   contract - generated id, echoed.
 *
 * Runs the REACTOR variant (demanded explicitly - the coroutine libraries sit on this classpath), with
 * sampling pinned to 1.0. The other integration tests of this module are unaffected by the bridge on the
 * classpath: their events carry trace ids only when a request sends a `traceparent`. Determinism: pinned
 * time and id beans; events awaited via [AwaitingAppender]. FLAT class with an inner static
 * configuration - see the Spring Boot test isolation caveat. The Reactor variant registers
 * the `endpoint_*` accessors in the JVM-global ContextRegistry; the class-level guard removes them again.
 */
@SpringBootTest(
    classes = [RequestLoggingWebFilterTracingIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.variant=reactor",
        "endpoint-logging.logger-name=http-exchange-reactive-tracing-integration-test",
        "management.tracing.sampling.probability=1.0",
    ],
)
class RequestLoggingWebFilterTracingIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("http-exchange-reactive-tracing-integration-test") as Logger
        appender = AwaitingAppender().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        // The JDK client is AutoCloseable (Java 21+); JUnit creates one instance per test method,
        // so each client - selector thread, sockets, buffers - must end with its test.
        http.close()
    }

    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(REQUEST_TIMEOUT).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The handler's view of the bridge: `traceId:spanId` of the server span, as the body encodes it. */
    private fun bridgeIds(response: HttpResponse<String>): Pair<String, String> {
        val (traceId, spanId) = response.body().split(':')
        return traceId to spanId
    }

    @Test
    fun `should log the trace id the real server span runs under when the caller sends a traceparent`() {
        // What is tested: the log-to-trace join under a real bridge - the module's traceId comes from
        //   the header, the bridge's server span comes from Boot's W3C propagation of the same header;
        //   the two must agree, or every "open the trace from the log line" link would be dead.
        // Success criteria: the bridge's trace id (read by the handler from the observation context)
        //   equals the header's and the event's MDC traceId; the event carries the header's parent-id
        //   as parentSpanId; the server span's own id differs from the parent; and the event does not
        //   publish the parent under spanId.
        // Why it matters: the join is the design's justification for parsing the header at all, and it
        //   rests on the bridge's propagation policy - a real bridge, not a mock, must confirm it.
        // Given/When: the real Netty application; a real GET carrying a W3C traceparent
        val response =
            get("/tr/things/9", "traceparent" to "00-$CALLER_TRACE_ID-$CALLER_PARENT_ID-01")

        // Then: the bridge continued the caller's trace under a span of its own
        assertThat(response.statusCode()).isEqualTo(200)
        val (bridgeTraceId, bridgeSpanId) = bridgeIds(response)
        assertThat(bridgeTraceId).isEqualTo(CALLER_TRACE_ID)
        assertThat(bridgeSpanId).matches("\\p{XDigit}{16}").isNotEqualTo(CALLER_PARENT_ID)

        // And: the event joins that trace, names the caller's span as the parent, and never as spanId
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", bridgeTraceId)
            .containsEntry("parentSpanId", CALLER_PARENT_ID)
        assertThat(event.mdcPropertyMap["spanId"]).isNotEqualTo(CALLER_PARENT_ID)
        assertThat(event.formattedMessage).contains(" traceId=$bridgeTraceId parentSpanId=$CALLER_PARENT_ID")

        // And: the trace id doubles as the request id and the wire stays untouched (ADR-0002)
        assertThat(event.mdcPropertyMap[MdcKeys.REQUEST_ID]).isEqualTo(CALLER_TRACE_ID)
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty()
    }

    @Test
    fun `should log no trace context without a caller traceparent although the bridge traces the exchange`() {
        // What is tested: the documented boundary of the header-based design - a trace the bridge mints
        //   itself (no incoming context) is NOT joined, because the event-loop thread carries no bridge
        //   MDC at filter time and the module deliberately avoids an observation-context dependency.
        // Success criteria: the bridge reports a well-formed trace id for the exchange, yet the event
        //   carries neither traceId nor parentSpanId in its MDC and no trace suffix in the message.
        // Why it matters: this is a conscious limitation;
        //   pinning it makes a future change of that decision deliberate and visible in both twins.
        // Given/When: the real Netty application; a real GET without any trace header
        val response = get("/tr/things/3")

        // Then: traced by the bridge, untraced in the event
        assertThat(response.statusCode()).isEqualTo(200)
        val (bridgeTraceId, _) = bridgeIds(response)
        assertThat(bridgeTraceId).matches("\\p{XDigit}{32}")
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("parentSpanId")
        assertThat(event.formattedMessage).doesNotContain("traceId=")

        // And: the traceless exchange keeps the correlation contract - generated id, echoed (ADR-0002)
        assertThat(event.mdcPropertyMap[MdcKeys.REQUEST_ID]).isEqualTo("tr-generated")
        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("tr-generated")
    }

    @Test
    fun `should keep the join on the commit-deferred error path under a real bridge`() {
        // What is tested: the trace context survives the deferred emission - the event is written from
        //   the commit callback, on whatever thread commits the error response, not from the filter.
        // Success criteria: the 500 event still carries the caller's trace id and parent.
        // Why it matters: the trace ids live on the Exchange, captured at wiring; the error path is the
        //   one place the emission leaves the terminal signal's thread.
        // Given/When: the real Netty application; a traced GET hits a throwing handler
        val response = get("/tr/boom", "traceparent" to "00-$CALLER_TRACE_ID-$CALLER_PARENT_ID-01")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", CALLER_TRACE_ID)
            .containsEntry("parentSpanId", CALLER_PARENT_ID)
    }

    /** Minimal reactive application with tracing on: the module's auto-configuration plus pinned beans and handlers. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    internal class ItApp {
        @Bean
        fun pinnedNanoTimeSource(): NanoTimeSource = NanoTimeSource { 0L }

        @Bean
        fun pinnedCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator { "tr-generated" }

        @Bean
        fun trController(): TrController = TrController()
    }

    @RestController
    internal class TrController {
        /**
         * Returns `traceId:spanId` of the server span the bridge opened for this exchange, read from
         * the observation context WebFlux stores on the exchange - deterministic without any
         * thread-local propagation, which is exactly what this module does not rely on either.
         */
        private fun bridgeIds(exchange: ServerWebExchange): String {
            val observationContext: ServerRequestObservationContext =
                ServerRequestObservationContext.findCurrent(exchange.attributes).orElse(null) ?: return "absent:absent"
            val tracingContext: TracingObservationHandler.TracingContext? =
                observationContext.get(TracingObservationHandler.TracingContext::class.java)
            val spanContext = tracingContext?.span?.context() ?: return "absent:absent"
            return "${spanContext.traceId()}:${spanContext.spanId()}"
        }

        @GetMapping("/tr/things/{id}")
        fun thing(
            @PathVariable id: String,
            exchange: ServerWebExchange,
        ): Mono<String> = Mono.just(bridgeIds(exchange))

        @GetMapping("/tr/boom")
        fun boom(): Mono<String> = Mono.error(IllegalStateException("tr boom"))
    }

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        private const val CALLER_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
        private const val CALLER_PARENT_ID = "b7ad6b7169203331"
        private val accessorRegistry = EndpointAccessorRegistryGuard()

        @JvmStatic
        @BeforeAll
        fun snapshotAccessors() {
            accessorRegistry.snapshot()
        }

        @JvmStatic
        @AfterAll
        fun restoreAccessors() {
            accessorRegistry.restore()
        }
    }
}
