package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.server.servlet.ServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The CAPTURE-BOUNDARY contract against a SECOND real container: everything the module documents about
 * what the body tees can and cannot see is spec-derived convention - buffer discard on `sendError`,
 * error rendering through the ORIGINAL response in the ERROR dispatch, zero-argument `startAsync()`
 * initializing its context with the original request/response, `requestDestroyed` as the emission
 * point. [RequestLoggingFilterTomcatIntegrationTest] pins all of it against embedded Tomcat; this class runs
 * the SAME application (the shared [RequestLoggingFilterTomcatIntegrationTest.ItApp]) on embedded JETTY, so a
 * container that interprets those spec corners differently breaks the build instead of silently logging
 * bodies the client never received (blind spot named by the repo-wide code analysis of 2026-08-30:
 * "behavior under real production containers other than embedded Tomcat").
 *
 * Jetty is opted into per-context through an explicit [JettyServletWebServerFactory] bean (Tomcat is
 * also on the test classpath and remains the auto-configured default everywhere else). Determinism as
 * in the Tomcat twin: pinned time/id beans, event-driven [AwaitingAppender], no sleeps.
 */
@SpringBootTest(
    classes = [RequestLoggingFilterTomcatIntegrationTest.ItApp::class, RequestLoggingFilterJettyIntegrationTest.JettyFactory::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=http-exchange-jetty-integration-test",
        "endpoint-logging.log-request-body=true",
        "endpoint-logging.log-response-body=true",
        // The tracing jars sit on the test classpath for the tracing integration test; THIS context
        // excludes the bridge explicitly, so the exact-message assertions here stay trace-free.
        "spring.autoconfigure.exclude=org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration",
    ],
)
class RequestLoggingFilterJettyIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    // Every real-HTTP call carries its own deadline: a stalled embedded endpoint must produce a bounded
    // failing test, not a hung executor. The appender's wait is a SEPARATE bound for the post-response
    // emission.
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("http-exchange-jetty-integration-test") as Logger
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
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    private fun causeMessages(proxy: IThrowableProxy?): List<String> = generateSequence(proxy) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `should tee both bodies through Jetty's real streams and emit at request destruction`() {
        // What is tested: the baseline on the second container - Jetty's real request/response streams
        //   flow through the tee wrappers, and Jetty fires requestDestroyed, the emission point.
        // Success criteria: the echoed round trip succeeds and exactly one INFO event carries both
        //   bodies exactly as they flowed.
        // Why it matters: every boundary assertion below is only meaningful if the capture demonstrably
        //   WORKS on this container in the regular case.
        // Given/When: the real Jetty application; a real POST whose controller reads and echoes the body
        val response = post("/it/echo", "hello jetty")

        // Then: served normally, and the single event carries both teed bodies
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("echo:hello jetty")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_request_body", "hello jetty")
            .containsEntry("endpoint_response_body", "echo:hello jetty")
    }

    @Test
    fun `should discard buffered output replaced by sendError and log no stale body on Jetty`() {
        // What is tested: the sendError boundary on Jetty - the controller writes into the buffer and
        //   then replaces the response via sendError(503); the buffer discard and the wrapper's capture
        //   discard must stay in lockstep, and Jetty's error rendering must bypass the tee.
        // Success criteria: the client sees the 503 error rendering (never the discarded bytes), and the
        //   WARN/failure event carries NO endpoint_response_body.
        // Why it matters: sendError's buffer-clearing is exactly the spec corner where containers could
        //   diverge - a divergence here logs bodies the client never received on the failure responses
        //   operators investigate.
        // Given/When: the real Jetty application; a GET against the partial-write-then-sendError controller
        val response = get("/it/partial-error")

        // Then: error rendering reached the client, the discarded bytes never prefix the body
        assertThat(response.statusCode()).isEqualTo(503)
        assertThat(response.body()).doesNotStartWith("partial")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 503)
            .doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should log the final 500 of an unhandled exception without capturing Jetty's error body`() {
        // What is tested: the error-dispatch boundary on Jetty - an unhandled controller exception is
        //   rethrown unchanged, Jetty renders the 500 through the ORIGINAL response (the filter skips
        //   the ERROR dispatch), and the emission at request destruction still reports the FINAL status.
        // Success criteria: client 500 with a rendered body; one ERROR event with outcome failure, the
        //   original cause, status 500 - and NO endpoint_response_body.
        // Why it matters: the "final status, no error body" pair is the exact combination that depends
        //   on the container's dispatch ordering; both halves must hold per container.
        // Given/When: the real Jetty application; a GET against a throwing controller
        val response = get("/it/boom")

        // Then: final status logged, error body rendered for the client but absent from the event
        assertThat(response.statusCode()).isEqualTo(500)
        assertThat(response.body()).isNotEmpty()
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 500")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
            .doesNotContainKey("endpoint_response_body")
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("it boom") }
    }

    @Test
    fun `should pin that zero-argument servlet async bypasses the body tee on Jetty too`() {
        // What is tested: the raw-async boundary on Jetty - the Servlet spec initializes a
        //   zero-argument startAsync() context with the ORIGINAL request/response, so the raw worker
        //   writes beside the tee.
        // Success criteria: the raw-async response reaches the client, the exchange event exists and
        //   carries NO endpoint_response_body although log-response-body is enabled class-wide.
        // Why it matters: a container that handed the wrappers to the zero-argument context would
        //   silently change what gets captured - the pin keeps the boundary identical across containers.
        // Given/When: the real Jetty application; a GET against the raw servlet using zero-argument startAsync
        val response = get("/it/raw-async")

        // Then: served through the original response, event present, body absent by documented contract
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("raw-async")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event)).doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should log a completed MVC async exchange with its body under Jetty's per-dispatch destruction`() {
        // What is tested: the emission point under Jetty's destruction model - Jetty fires
        //   requestDestroyed at the end of EVERY dispatch, so the initial dispatch of an async
        //   exchange destroys early; the exchange must survive that, the async-dispatch pass must
        //   still see it, and the event must carry the COMPLETED state.
        // Success criteria: one INFO event with endpoint_async=true, the final status and the response
        //   body the async worker wrote - and the render-side MDC observed by the advice.
        // Why it matters: before the per-dispatch handling, Jetty emitted a bodyless pre-completion
        //   event and stripped the exchange from the async dispatch (found by this suite, 2026-08-30).
        // Given/When: the real Jetty application; a real GET against the Callable controller
        val response = get("/it/async")

        // Then: worker and render dispatch both saw the identity, and the event is complete and final
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("async-done:it-generated|render:it-generated")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_async", true)
            .containsEntry("endpoint_response_status_code", 200)
            .containsEntry("endpoint_response_body", "async-done:it-generated|render:it-generated")
    }

    @Test
    fun `should log a failing Callable at ERROR with the final 500 under Jetty's per-dispatch destruction`() {
        // What is tested: the async error path on Jetty - the failure is rethrown in the ASYNC
        //   dispatch (which needs the exchange to still be attached there) and the emission carries
        //   the FINAL rendered status, not the pre-completion 200 the early destruction used to log.
        // Success criteria: client 500; one ERROR event, outcome failure, endpoint_async=true,
        //   status 500, the cause chain naming the Callable's exception.
        // Why it matters: an async crash that logs "200 success" is wrong data on exactly the
        //   exchanges an operator investigates - the defect this suite originally exposed.
        // Given/When: the real Jetty application; a GET against the failing Callable
        val response = get("/it/async-boom")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_async", true)
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("async boom") }
    }

    @Test
    fun `should log a DeferredResult error result at ERROR under Jetty's per-dispatch destruction`() {
        // Given/When: the real Jetty application; a DeferredResult completed with an error result
        val response = get("/it/deferred-boom")

        // Then: same classification as on Tomcat
        assertThat(response.statusCode()).isEqualTo(500)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_async", true)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("deferred boom") }
    }

    @Test
    fun `should echo the correlation id on Jetty including the error-dispatched 500`() {
        // What is tested: the identity contract on the second container - traceless echo at filter
        //   entry, surviving Jetty's error dispatch.
        // Success criteria: the pinned generator's id is echoed on a happy response and on the 500.
        // Why it matters: failures are exactly the responses a support case needs to correlate; the
        //   echo's survival across the error dispatch is container behavior, not module code.
        // Given/When: the real Jetty application; a happy GET and a crashing GET
        val ok = get("/it/things/9")
        val boom = get("/it/boom")

        // Then: both responses carry the echo
        assertThat(ok.headers().firstValue("X-Correlation-Id")).contains("it-generated")
        assertThat(boom.headers().firstValue("X-Correlation-Id")).contains("it-generated")
        appender.awaitEvents(2)
    }

    /** Opts THIS context into Jetty; the auto-configured Tomcat factory backs off to the user bean. */
    @Configuration(proxyBeanMethods = false)
    internal class JettyFactory {
        @Bean
        fun jettyServletWebServerFactory(): ServletWebServerFactory = JettyServletWebServerFactory(0)
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
