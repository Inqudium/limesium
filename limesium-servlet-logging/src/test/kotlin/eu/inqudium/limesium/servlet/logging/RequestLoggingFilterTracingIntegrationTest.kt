package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
 * Pins the ADR-0002 trace contract against a real embedded Tomcat WITH a real Micrometer Tracing
 * bridge (Brave) active: the trace context of the exchange event comes from the incoming
 * `traceparent` header - parsed by this module, not captured from the bridge - the trace id doubles
 * as the request id, the caller's span is published as `parentSpanId` (never as the bridge's local
 * `spanId`, which the emission suppresses), and a traced exchange gets NO `X-Correlation-Id` echo.
 * Running beside the live bridge is the point: its MDC writes and its own server span must not leak
 * into the event or displace the parsed context.
 *
 * Runs its own context (tracing on, sampling pinned to 1.0); the plain integration test disables tracing
 * in its context so its exact-message assertions stay trace-free.
 */
@SpringBootTest(
    classes = [RequestLoggingFilterIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=http-exchange-tracing-integration-test",
        "management.tracing.sampling.probability=1.0",
    ],
)
class RequestLoggingFilterTracingIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    // Every real-HTTP call carries its own deadline: a stalled embedded endpoint must produce a bounded
    // failing test, not a hung executor (review finding 6). The
    // appender's wait is a SEPARATE bound for the post-response emission.
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("http-exchange-tracing-integration-test") as Logger
        appender = AwaitingAppender().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(REQUEST_TIMEOUT)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `should join the exchange event with the caller's traceparent beside a live bridge`() {
        // What is tested: the ADR-0002 identity and trace decision under a real bridge - the incoming
        //   traceparent is parsed by the module, its trace id becomes the request id, the caller's span
        //   rides parentSpanId, the bridge's local spanId is suppressed, and no correlation echo is
        //   written.
        // Success criteria: the event carries exactly the sent trace id (MDC and inline), parentSpanId
        //   equals the sent parent-id, no spanId MDC entry, and the response has no X-Correlation-Id.
        // Why it matters: the live bridge writes its own traceId/spanId into the MDC around the chain -
        //   this is the assertion that the parsed header context wins over that ambient state and that
        //   a traced exchange passes through observationally untouched.
        // Given/When: the real Tomcat application; a GET carrying a conformant traceparent completes
        val response = get("/it/things/9", "traceparent" to "00-$TRACE_ID-$PARENT_SPAN_ID-01")

        // Then: served normally, no echo, and the single event joins the CALLER's trace
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("X-Correlation-Id")).isEmpty()
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap["traceId"]).isEqualTo(TRACE_ID)
        assertThat(event.mdcPropertyMap["parentSpanId"]).isEqualTo(PARENT_SPAN_ID)
        assertThat(event.mdcPropertyMap).doesNotContainKey("spanId")
        assertThat(event.mdcPropertyMap[MdcKeys.REQUEST_ID]).isEqualTo(TRACE_ID)
        assertThat(event.formattedMessage).contains("[endpoint_request_id=$TRACE_ID traceId=$TRACE_ID")
    }

    @Test
    fun `should keep the trace context across a real async exchange`() {
        // What is tested: the entry-time parse surviving the async lifecycle - the emission runs at
        //   request destruction, on a container thread that never saw this exchange's request headers.
        // Success criteria: the async exchange's event carries exactly the sent trace id as request id
        //   and trace field.
        // Why it matters: async is exactly where per-request state gets lost; the parse carried in the
        //   Exchange is what bridges it.
        // Given/When: the real Tomcat application; a traced async (Callable) GET completes
        val response = get("/it/async", "traceparent" to "00-$TRACE_ID-$PARENT_SPAN_ID-01")

        // Then: the deferred event still joins with the trace
        assertThat(response.statusCode()).isEqualTo(200)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap["traceId"]).isEqualTo(TRACE_ID)
        assertThat(event.mdcPropertyMap[MdcKeys.REQUEST_ID]).isEqualTo(TRACE_ID)
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val PARENT_SPAN_ID = "00f067aa0ba902b7"
    }
}
