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
 * Pins the CONVENTION CHAIN the trace capture rests on, against a REAL Micrometer Tracing bridge (Brave)
 * and a real embedded Tomcat: Boot's `WebMvcObservationAutoConfiguration` registers the
 * `ServerHttpObservationFilter` at `HIGHEST_PRECEDENCE + 1` (order `-2147483647`, read from the bytecode
 * of `spring-boot-webmvc` 4.1.0 - the constant is not public API; before this module's `+ 10`), that
 * filter opens the observation scope AROUND
 * the chain, the bridge's correlation writes `traceId`/`spanId` into the MDC synchronously on scope open,
 * the filter captures them at entry, and the emission at request destruction restores them. None of that
 * is API-guaranteed - it is ordering and scope convention - so this test turns it into a build-breaking
 * contract: if a Boot upgrade reorders the observation filter or stops scoping the chain, these
 * assertions fail instead of the exchange events silently losing their trace ids.
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

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(REQUEST_TIMEOUT).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `should join the exchange event with the real server trace`() {
        // What is tested: the full convention chain under a real bridge - observation filter order, scope
        //   around the chain, MDC correlation, entry capture, overlay at destruction.
        // Success criteria: the emitted event carries a well-formed traceId (32 hex) and spanId (16 hex)
        //   as MDC fields AND inline in the message.
        // Why it matters: every link in the chain is convention, not API; this is the assertion that
        //   breaks the build when a Boot upgrade changes any of it.
        // Given/When: the real Tomcat application; a real traced GET completes
        val response = get("/it/things/9")

        // Then: served normally, and the single event is joinable with its trace
        assertThat(response.statusCode()).isEqualTo(200)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap["traceId"]).matches("\\p{XDigit}{32}")
        assertThat(event.mdcPropertyMap["spanId"]).matches("\\p{XDigit}{16}")
        assertThat(event.formattedMessage).contains(" traceId=${event.mdcPropertyMap["traceId"]}")
    }

    @Test
    fun `should keep the trace context across a real async exchange`() {
        // What is tested: the entry-time capture surviving the async lifecycle - the emission runs at
        //   request destruction, on a container thread that never carried this exchange's bridge MDC.
        // Success criteria: the async exchange's event carries a well-formed traceId.
        // Why it matters: async is exactly where thread-local trace state gets lost; the capture in the
        //   Exchange is what bridges it.
        // Given/When: the real Tomcat application; a real traced async (Callable) GET completes
        val response = get("/it/async")

        // Then: the deferred event still joins with the trace
        assertThat(response.statusCode()).isEqualTo(200)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap["traceId"]).matches("\\p{XDigit}{32}")
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
