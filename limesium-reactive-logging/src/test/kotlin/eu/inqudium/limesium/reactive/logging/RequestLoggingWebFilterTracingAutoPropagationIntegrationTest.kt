package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import eu.inqudium.limesium.common.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import reactor.core.publisher.Hooks
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The ADR-0002 trace contract beside a LIVE Brave bridge on Netty under `spring.reactor.context-propagation=auto`
 * - the mode the module's own handler-MDC parity asks for, and the one under which the bridge's
 * `traceId`/`spanId` are restored into the thread-local MDC around EVERY operator, the filter's terminal
 * and commit callbacks included. [RequestLoggingWebFilterTracingIntegrationTest] pins the same contract
 * under Boot's default `limited` mode, where the emitting thread carries no bridge MDC at all; this suite
 * is the constellation in which the emission scope's OWNERSHIP of the trace keys decides (code analysis
 * of 2026-09-05, finding 1): the parsed pair wins, the bridge's local `spanId` never rides along, and a
 * traceless exchange carries no trace context although the bridge traces it.
 *
 * Global JVM state: Boot's `ReactorAutoConfiguration` enables Reactor's automatic propagation hook for
 * this context; the class-level teardown disables it again and removes the module-owned accessors, so
 * no later test class inherits either. Determinism as everywhere: pinned time and id beans, events
 * awaited via [AwaitingAppender].
 */
@SpringBootTest(
    classes = [RequestLoggingWebFilterTracingIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.variant=reactor",
        "endpoint-logging.logger-name=endpoint-http-exchange-reactive-tracing-auto-integration-test",
        "management.tracing.sampling.probability=1.0",
        "spring.reactor.context-propagation=auto",
    ],
)
// Netty explicitly: with three servers on the test classpath Boot would otherwise start Jetty (see Servers.kt).
@Import(NettyServer::class)
class RequestLoggingWebFilterTracingAutoPropagationIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("endpoint-http-exchange-reactive-tracing-auto-integration-test") as Logger
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
    fun `should publish the parsed pair and never the bridge's live spanId under automatic propagation`() {
        // What is tested: the emission scope's ownership of the trace keys against a bridge MDC that is
        //   LIVE around the terminal callback - under `auto` the ObservationThreadLocalAccessor restores
        //   the server span's traceId/spanId around every operator, doFinally included.
        // Success criteria: the bridge continued the caller's trace under a span of its own; the event
        //   carries the caller's trace id (as traceId and as request id) and the caller's parent-id as
        //   parentSpanId, and NO spanId key although the bridge's local span id sat in the MDC.
        // Why it matters: under the module's own recommended propagation mode the reactive twin used to
        //   inherit whatever the bridge had put there; a local span id on the event contradicts ADR-0002
        //   and the servlet twin, and dashboards keying on its absence would see stack-dependent data.
        // Given/When: the real Netty application in auto mode; a real GET carrying a W3C traceparent
        val response = get("/tr/things/9", "traceparent" to "00-$CALLER_TRACE_ID-$CALLER_PARENT_ID-01")

        // Then: the bridge traced the exchange under a local span the event does not publish
        assertThat(response.statusCode()).isEqualTo(200)
        val (bridgeTraceId, bridgeSpanId) = bridgeIds(response)
        assertThat(bridgeTraceId).isEqualTo(CALLER_TRACE_ID)
        assertThat(bridgeSpanId).matches("\\p{XDigit}{16}").isNotEqualTo(CALLER_PARENT_ID)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", CALLER_TRACE_ID)
            .containsEntry("parentSpanId", CALLER_PARENT_ID)
            .containsEntry(MdcKeys.REQUEST_ID, CALLER_TRACE_ID)
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).contains(" traceId=$CALLER_TRACE_ID parentSpanId=$CALLER_PARENT_ID")
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty()
    }

    @Test
    fun `should log no trace context for a traceless exchange although the bridge's trace is live around the emission`() {
        // What is tested: the no-traceparent boundary under `auto` - the bridge mints a trace of its own
        //   and its traceId/spanId are live in the MDC when the emission runs.
        // Success criteria: the bridge reports a well-formed trace id, yet the event carries neither
        //   traceId nor parentSpanId nor spanId and no trace suffix; the request id is the generated
        //   correlation id and is echoed.
        // Why it matters: without ownership the event would carry a traceId that is NOT its request id -
        //   the exact inconsistency ADR-0002 rules out (the trace id IS the request id, or there is none).
        // Given/When: the real Netty application in auto mode; a real GET without any trace header
        val response = get("/tr/things/3")

        // Then: traced by the bridge, untraced in the event, correlation contract intact
        assertThat(response.statusCode()).isEqualTo(200)
        val (bridgeTraceId, _) = bridgeIds(response)
        assertThat(bridgeTraceId).matches("\\p{XDigit}{32}")
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("parentSpanId")
            .doesNotContainKey("spanId")
            .containsEntry(MdcKeys.REQUEST_ID, "tr-generated")
        assertThat(event.formattedMessage).doesNotContain("traceId=")
        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("tr-generated")
    }

    @Test
    fun `should keep the ownership on the commit-deferred error path under automatic propagation`() {
        // What is tested: the deferred emission runs inside the response-commit chain, subscribed from
        //   Boot's error renderer under the observation filter's context - the bridge MDC is live there
        //   too.
        // Success criteria: the 500 event carries the caller's trace id and parent, no spanId.
        // Why it matters: the error path is the one place the emission leaves the terminal signal's
        //   operator; ownership must hold on that thread as well.
        // Given/When: the real Netty application in auto mode; a traced GET hits a throwing handler
        val response = get("/tr/boom", "traceparent" to "00-$CALLER_TRACE_ID-$CALLER_PARENT_ID-01")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", CALLER_TRACE_ID)
            .containsEntry("parentSpanId", CALLER_PARENT_ID)
            .doesNotContainKey("spanId")
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
        fun restoreGlobalState() {
            accessorRegistry.restore()
            // ReactorAutoConfiguration enabled the JVM-global hook for this context; no later test class
            // may inherit it.
            Hooks.disableAutomaticContextPropagation()
        }
    }
}
