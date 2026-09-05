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
 * The capture-boundary contract against UNDERTOW - the servlet engine WildFly embeds, and deliberately
 * UNSUPPORTED territory: Undertow has no Jakarta Servlet 6.1 release and Spring Boot 4 dropped its
 * Undertow integration, but a bytecode scan of the servlet-MVC path found no hard 6.1 invocation (see
 * the container-support note in the README), so this suite pins empirically whether the module's
 * spec-derived boundary conventions hold on the third engine too. It runs the shared
 * [RequestLoggingFilterTomcatIntegrationTest.ItApp] on the hand-rolled [UndertowTestServer] factory
 * (Boot ships none any more). If a Spring patch release ever adopts Servlet 6.1 API on this path, THIS
 * suite is the tripwire that turns the documented "might factually start" into a red build instead of
 * a production surprise.
 *
 * Assertion set mirrors the Jetty suite, with two PINNED Undertow deviations found on first contact
 * (2026-08-30): the engine hands the WRAPPERS to zero-argument `startAsync()` (so the raw-async body
 * IS captured, unlike Tomcat/Jetty), and its default error rendering rebuilds the response, dropping
 * the correlation echo from the 500 (the documented set-once residual of `correlation-id-header`).
 * Determinism as everywhere: pinned time/id beans, event-driven [AwaitingAppender], no sleeps.
 */
@SpringBootTest(
    classes = [RequestLoggingFilterTomcatIntegrationTest.ItApp::class, RequestLoggingFilterUndertowIntegrationTest.UndertowFactory::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-undertow-integration-test",
        "endpoint-logging.log-request-body=always",
        "endpoint-logging.log-response-body=always",
        // The tracing jars sit on the test classpath for the tracing integration tests; THIS context
        // excludes the bridge explicitly, so the exact-message assertions here stay trace-free.
        "spring.autoconfigure.exclude=org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration",
    ],
)
class RequestLoggingFilterUndertowIntegrationTest {
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
        logger = LoggerFactory.getLogger("endpoint-http-exchange-undertow-integration-test") as Logger
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
    fun `should tee both bodies through Undertow's real streams and emit at request destruction`() {
        // What is tested: the baseline on the third engine - Undertow's real request/response streams
        //   flow through the tee wrappers, and Undertow fires requestDestroyed, the emission point.
        // Success criteria: the echoed round trip succeeds and exactly one INFO event carries both
        //   bodies exactly as they flowed.
        // Why it matters: every boundary assertion below is only meaningful if the capture demonstrably
        //   WORKS on this engine in the regular case - and this suite is the tripwire for the
        //   unsupported-territory status of the WildFly engine (see the class KDoc).
        // Given/When: the real Undertow application; a real POST whose controller reads and echoes the body
        val response = post("/it/echo", "hello undertow")

        // Then: served normally, and the single event carries both teed bodies
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("echo:hello undertow")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_request_body", "hello undertow")
            .containsEntry("endpoint_response_body", "echo:hello undertow")
    }

    @Test
    fun `should discard buffered output replaced by sendError and log no stale body on Undertow`() {
        // What is tested: the sendError boundary on Undertow - the controller writes into the buffer
        //   and then replaces the response via sendError(503); the buffer discard and the wrapper's
        //   capture discard must stay in lockstep, and Undertow's error rendering must bypass the tee.
        // Success criteria: the client sees the 503 error rendering (never the discarded bytes), and
        //   the WARN/failure event carries NO endpoint_response_body.
        // Why it matters: sendError's buffer-clearing is exactly the spec corner where engines could
        //   diverge - a divergence here logs bodies the client never received.
        // Given/When: the real Undertow application; a GET against the partial-write-then-sendError controller
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
    fun `should log the final 500 of an unhandled exception without capturing Undertow's error body`() {
        // What is tested: the error-dispatch boundary on Undertow - an unhandled controller exception
        //   is rethrown unchanged, the engine renders the error response outside the tee, and the
        //   emission still reports the FINAL status.
        // Success criteria: client 500; one ERROR event, outcome failure, the original cause, status
        //   500 - and NO endpoint_response_body.
        // Why it matters: the "final status, no error body" pair depends on the engine's dispatch
        //   ordering; both halves must hold per engine.
        // Given/When: the real Undertow application; a GET against a throwing controller
        val response = get("/it/boom")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
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
    fun `should pin that Undertow hands the wrappers to zero-argument startAsync and the body IS captured`() {
        // What is tested: the raw-async boundary on the third engine - and a PINNED DEVIATION: the
        //   Servlet spec words zero-argument startAsync() as initializing its context with the
        //   ORIGINAL request/response (Tomcat and Jetty do, so their raw workers write beside the
        //   tee), but Undertow hands the CURRENT, wrapped objects to the context - the raw worker
        //   therefore writes THROUGH the tee here.
        // Success criteria: the raw-async response reaches the client, the exchange event exists
        //   (completion survives the raw cycle) and - Undertow-specifically - CARRIES the body.
        // Why it matters: the capture boundary for raw async is engine-defined, not module-defined;
        //   pinning each engine's actual behavior keeps the documented boundary honest and makes an
        //   engine-side change (either direction) a red build instead of a silent contract shift.
        // Given/When: the real Undertow application; a GET against the raw servlet using zero-argument startAsync
        val response = get("/it/raw-async")

        // Then: served, event present - and the body captured, because the tee sat in the async context
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("raw-async")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event)).containsEntry("endpoint_response_body", "raw-async")
    }

    @Test
    fun `should log a completed MVC async exchange with its body under Undertow's destruction model`() {
        // What is tested: the emission point under Undertow's destruction timing - whether it fires
        //   once after completion (Tomcat model) or per dispatch (Jetty model), the completion
        //   choreography must deliver ONE event with the completed state.
        // Success criteria: one INFO event with endpoint_async=true, the final status and the response
        //   body the async worker wrote - and the render-side MDC observed by the advice.
        // Why it matters: the async lifecycle is where the engines diverged before (Jetty, 2026-08-30);
        //   this is the third data point for the container-agnostic completion design.
        // Given/When: the real Undertow application; a real GET against the Callable controller
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
    fun `should log a failing Callable at ERROR with the final 500 under Undertow's destruction model`() {
        // What is tested: the async error path on Undertow - the failure is rethrown in the ASYNC
        //   dispatch and the emission carries the FINAL rendered status.
        // Success criteria: client 500; one ERROR event, outcome failure, endpoint_async=true,
        //   status 500, the cause chain naming the Callable's exception.
        // Why it matters: an async crash that logs "200 success" is wrong data on exactly the
        //   exchanges an operator investigates - the Jetty-found defect, pinned per engine.
        // Given/When: the real Undertow application; a GET against the failing Callable
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
    fun `should log a DeferredResult error result at ERROR under Undertow's destruction model`() {
        // What is tested: a DeferredResult completed with an error result on real Undertow.
        // Success criteria: the client sees 500; one ERROR event with outcome failure,
        //   endpoint_async true and the deferred failure in the cause chain.
        // Why it matters: Undertow is unsupported territory with its own destruction timing; the
        //   suite pins that the classification still holds there.
        // Given/When: the real Undertow application; a DeferredResult completed with an error result
        val response = get("/it/deferred-boom")

        // Then: same classification as on Tomcat and Jetty
        assertThat(response.statusCode()).isEqualTo(500)
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_async", true)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("deferred boom") }
    }

    @Test
    fun `should echo the correlation id on Undertow except on its rebuilt error response`() {
        // What is tested: the identity contract on the third engine - and a PINNED DEVIATION: the
        //   traceless echo set at filter entry survives Tomcat's and Jetty's error dispatch, but
        //   Undertow's default error rendering REBUILDS the response and drops the header from the
        //   500. That is the documented set-once residual of `correlation-id-header` ("downstream ...
        //   decides what the client finally sees"), surfacing as engine behavior.
        // Success criteria: the happy response carries the echo; the 500 does NOT; both exchanges are
        //   still logged with their (module-side) request id.
        // Why it matters: operators correlating failures on Undertow-based hosts must know the id is
        //   in the LOG EVENT but not on the error response's wire - the opposite assumption reads the
        //   absence as a module bug.
        // Given/When: the real Undertow application; a happy GET and a crashing GET
        val ok = get("/it/things/9")
        val boom = get("/it/boom")

        // Then: echo on the happy wire, none on the rebuilt error response - both events logged
        assertThat(ok.headers().firstValue("X-Correlation-Id")).contains("it-generated")
        assertThat(boom.headers().firstValue("X-Correlation-Id")).isEmpty()
        val events = appender.awaitEvents(2)
        assertThat(events).allSatisfy { assertThat(it.mdcPropertyMap["endpoint_request_id"]).isEqualTo("it-generated") }
    }

    /** Opts THIS context into the hand-rolled Undertow factory; the auto-configured factories back off. */
    @Configuration(proxyBeanMethods = false)
    internal class UndertowFactory {
        @Bean
        fun undertowServletWebServerFactory(): ServletWebServerFactory = UndertowTestServer()
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
