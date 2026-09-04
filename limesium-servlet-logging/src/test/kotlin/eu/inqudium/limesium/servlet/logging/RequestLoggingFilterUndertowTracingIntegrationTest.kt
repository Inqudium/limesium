package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import eu.inqudium.limesium.common.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The ADR-0002 trace contract beside a LIVE Brave bridge on embedded UNDERTOW - the third-engine
 * sibling of [RequestLoggingFilterJettyTracingIntegrationTest], and like the Undertow capture-boundary
 * suite deliberately UNSUPPORTED territory (see that class and the README's container note): whatever
 * thread and ambient MDC Undertow's destruction timing hands the emission, the emission scope's
 * ownership of the trace keys must displace the bridge's `traceId`/`spanId` - parsed pair wins, no
 * local `spanId` rides along. The bridge-propagation assertions themselves (Boot continuing the
 * caller's W3C trace) are container-independent and stay pinned by the Tomcat twin only.
 *
 * Deliberately slim (two tests): the sync join-and-suppression case, and the async case whose emission
 * travels Undertow's destruction/backstop choreography. Determinism as everywhere: pinned time/id
 * beans, event-driven [AwaitingAppender].
 */
@SpringBootTest(
    classes = [RequestLoggingFilterTomcatIntegrationTest.ItApp::class, RequestLoggingFilterUndertowIntegrationTest.UndertowFactory::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-undertow-tracing-integration-test",
        "management.tracing.sampling.probability=1.0",
    ],
)
class RequestLoggingFilterUndertowTracingIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("endpoint-http-exchange-undertow-tracing-integration-test") as Logger
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

    @Test
    fun `should emit the parsed trace pair whatever bridge MDC Undertow's emission thread carries`() {
        // What is tested: the emission scope's OWNERSHIP of the trace keys on the third engine -
        //   whatever ambient bridge MDC Undertow's destruction timing places around the emission
        //   (live scope or stale leftovers), the parsed pair must win and no local spanId may ride.
        // Success criteria: the event carries exactly the sent traceId and parentSpanId, NO spanId key,
        //   the request id IS the trace id, and the traced exchange gets no X-Correlation-Id echo
        //   although the caller supplied one.
        // Why it matters: the suppression is per-emission-thread behavior and therefore per-engine;
        //   this is the Undertow data point beside the Tomcat (stale keys) and Jetty (live scope) pins.
        // Given/When: the real Undertow application with tracing on; a traced GET that also sends a
        //   correlation header
        val response =
            get(
                "/it/things/5",
                "traceparent" to "00-$TRACE_ID-$PARENT_SPAN_ID-01",
                "X-Correlation-Id" to "caller-corr-1",
            )

        // Then: no echo on the wire, and the single event joins the CALLER's context - nothing of the
        //   bridge's live MDC rides along
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty()
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", TRACE_ID)
            .containsEntry("parentSpanId", PARENT_SPAN_ID)
            .containsEntry(MdcKeys.REQUEST_ID, TRACE_ID)
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).contains("[endpoint_request_id=$TRACE_ID traceId=$TRACE_ID parentSpanId=$PARENT_SPAN_ID]")
    }

    @Test
    fun `should keep the parsed trace context across Undertow's async choreography`() {
        // What is tested: the trace context surviving the async emission path on Undertow - the
        //   completion choreography (destruction and/or onComplete backstop) must emit with the
        //   traceparent-derived pair that lives on the Exchange, whichever thread it ends on.
        // Success criteria: the async exchange's event carries the sent trace id as trace field AND as
        //   request id, with no spanId leaked from the bridge's worker/dispatch MDC.
        // Why it matters: async is exactly where the emission thread and its ambient MDC differ most
        //   between the engines; this is the async half of the suppression pin above.
        // Given/When: the real Undertow application with tracing on; a traced async (Callable) GET
        val response = get("/it/async", "traceparent" to "00-$TRACE_ID-$PARENT_SPAN_ID-01")

        // Then
        assertThat(response.statusCode()).isEqualTo(200)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", TRACE_ID)
            .containsEntry(MdcKeys.REQUEST_ID, TRACE_ID)
            .doesNotContainKey("spanId")
        assertThat(keyValues(event)).containsEntry("endpoint_async", true)
    }

    private fun keyValues(event: ch.qos.logback.classic.spi.ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val PARENT_SPAN_ID = "00f067aa0ba902b7"
    }
}
