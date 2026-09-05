package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import eu.inqudium.limesium.common.AwaitingAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * End-to-end test of the AUTO-SELECTED filter against a REAL Netty server: the shipped auto-configuration
 * pair registers exactly one filter (reactive condition), and because this module's test classpath
 * carries the coroutine libraries, that filter is the [CoRequestLoggingWebFilter] - the majority
 * consumer configuration without those libraries, the Reactor variant [RequestLoggingWebFilter], is
 * pinned per server by [ServerContract]. WebFlux dispatches to real annotated handlers, requests arrive
 * over real HTTP, and the exchange events are observed on the configured logger. Covers what the
 * mock-exchange tests cannot: the DataBuffer tee on real Netty buffers (pooled, reference-counted), the
 * handler pattern recorded by real WebFlux dispatch, and the commit-deferred error emission against
 * Boot's real error handler (the event must carry the RENDERED 500).
 *
 * Determinism: pinned time and id beans (auto-configured defaults back off); event arrival awaited via
 * the semaphore-based [AwaitingAppender], never a sleep. FLAT class with an inner static configuration -
 * see the Spring Boot test isolation caveat.
 */
@SpringBootTest(
    classes = [RequestLoggingWebFilterIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-reactive-integration-test",
        "endpoint-logging.log-request-body=always",
        "endpoint-logging.log-response-body=always",
        "endpoint-logging.request-headers.includes=Accept",
        "endpoint-logging.request-headers.unmasked=Accept",
        "endpoint-logging.exclude-path-prefixes=/it/excluded",
    ],
)
// Netty explicitly: with three servers on the test classpath Boot would otherwise start Jetty (see Servers.kt).
@Import(NettyServer::class)
class RequestLoggingWebFilterIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    // Every real-HTTP call carries its own deadline: a stalled endpoint must produce a bounded failing
    // test, not a hung executor. The appender's
    // wait is a SEPARATE bound for the post-response emission.
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("endpoint-http-exchange-reactive-integration-test") as Logger
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

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    private fun causeMessages(proxy: IThrowableProxy?): List<String> = generateSequence(proxy) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `should log one complete event for a real reactive exchange including template headers and bodies`() {
        // What is tested: the full happy path through real Netty - registration by the reactive
        //   auto-configuration, WebFlux dispatch, the DataBuffer tee on pooled buffers, the recorded
        //   handler pattern, and the correlation echo.
        // Success criteria: correct response AND one INFO event, format-identical to the servlet twin.
        // Why it matters: only a real reactive runtime proves the buffer handling (release/rewrap) and
        //   the attribute names hold outside the mocks.
        // Given/When: the real Netty application; a real GET with correlation id and Accept header
        val response = get("/it/things/7", "X-Correlation-Id" to "it-corr-1", "Accept" to "text/plain")

        // Then: served and echoed
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("thing-7")
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-corr-1")

        // And: one INFO event with the full family
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange GET /it/things/7 -> 200 [endpoint_request_id=it-corr-1]")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_url_path", "/it/things/7")
            .containsEntry("endpoint_url_template", "/it/things/{id}")
            .containsEntry("endpoint_response_status_code", 200)
            .containsEntry("endpoint_duration_ms", 0L)
            .containsEntry("endpoint_request_headers", "[Accept:\"text/plain\"]")
            .containsEntry("endpoint_response_body", "thing-7")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "it-corr-1")
    }

    @Test
    fun `should capture both bodies of a real reactive round trip`() {
        // What is tested: both tees on real Netty buffers - a POST whose handler reads and echoes
        //   the body.
        // Success criteria: the event carries the request body and the echoed response body.
        // Why it matters: pooled Netty buffers differ from the mock DataBuffers; only the real
        //   server proves the tee reads them without consuming them.
        // Given/When: the real Netty application; a real POST whose handler reads the body and echoes it
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/it/echo"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello reactive"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: both tees captured what actually flowed through Netty's buffers
        assertThat(response.body()).isEqualTo("echo:hello reactive")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event))
            .containsEntry("endpoint_request_body", "hello reactive")
            .containsEntry("endpoint_response_body", "echo:hello reactive")
    }

    @Test
    fun `should log the rendered 500 for an unhandled handler error via the commit-deferred emission`() {
        // What is tested: the commit-deferred error path against Boot's REAL error handler - the error
        //   signal passes the filter before rendering, the emission must wait for the commit.
        // Success criteria: the client sees 500 and the single ERROR event carries status 500 (not a
        //   stale pre-rendering value), outcome failure, and the original cause; the correlation echo
        //   survives onto the error response.
        // Why it matters: this is the reactive twin of the servlet module's request-destruction fix -
        //   the semantics the two stacks must share.
        // Given/When: the real Netty application; a real GET hits a throwing handler
        val response = get("/it/boom", "X-Correlation-Id" to "it-corr-boom")

        // Then: rendered 500 with the echo, and the event carries the final status
        assertThat(response.statusCode()).isEqualTo(500)
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-corr-boom")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 500")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("it boom") }

        // And: the documented capture BOUNDARY - Boot's
        //   OUTER error renderer writes the 500 body through the original, undecorated response, so the
        //   client receives a body but the event must NOT carry endpoint_response_body although
        //   log-response-body is enabled class-wide. This pin keeps the boundary a conscious contract:
        //   whoever moves capture to a boundary that also sees outer error rendering must flip it.
        assertThat(response.body()).isNotEmpty()
        assertThat(keyValues(event)).doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should join the event with the caller trace via the real traceparent header`() {
        // What is tested: the traceparent parse on a real request carrying a W3C header.
        // Success criteria: the event's MDC and message carry the header's trace id.
        // Why it matters: the log-to-trace join rests on this id; a header lost or rewritten by the
        //   real server would break it silently.
        // Given/When: the real Netty application; a real GET carrying a W3C traceparent
        val response = get("/it/things/3", "traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")

        // Then: the event carries the trace id as an MDC field and inline
        assertThat(response.statusCode()).isEqualTo(200)
        val event = appender.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap).containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c")
        assertThat(event.formattedMessage).contains(" traceId=0af7651916cd43dd8448eb211c80319c")
    }

    @Test
    fun `should hand a real suspend handler the endpoint MDC via the coroutine variant`() {
        // What is tested: the whole coroutine chain against real Netty - the auto-configuration picks
        //   the CoRequestLoggingWebFilter (coroutine libs are on this classpath), CoWebFilter publishes
        //   the MDCContext-carrying coroutine context, and WebFlux's suspend-handler invocation
        //   inherits it.
        // Success criteria: the suspend handler READS its own MDC and returns the correlation id it
        //   saw there - the response body is the proof.
        // Why it matters: handler-side correlation in coroutine apps is the coroutine variant's whole
        //   reason to exist; only a real runtime proves the context handoff end to end.
        // Given/When: the real Netty application; a real GET against a suspend handler that echoes its MDC
        val response = get("/it/suspend-mdc", "X-Correlation-Id" to "it-co-mdc")

        // Then: the handler saw the identity in ITS thread-local MDC
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("it-co-mdc")
        appender.awaitEvents(1)
    }

    @Test
    fun `should not log an excluded path while still logging the next regular exchange`() {
        // What is tested: the exclude prefix on the real server, followed by a regular request.
        // Success criteria: both served with 200; exactly one event, for the regular path.
        // Why it matters: an exclusion must not leave state behind that suppresses or duplicates
        //   the next exchange on the same server.
        // Given/When: the real Netty application; an excluded request followed by a regular one
        val excluded = get("/it/excluded/ping")
        val regular = get("/it/things/2")

        // Then: both served, one event
        assertThat(excluded.statusCode()).isEqualTo(200)
        assertThat(regular.statusCode()).isEqualTo(200)
        val events = appender.awaitEvents(1)
        assertThat(events).hasSize(1)
        assertThat(keyValues(events.single())).containsEntry("endpoint_url_path", "/it/things/2")
    }

    /** Minimal reactive application: the module's auto-configuration plus pinned beans and handlers. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    internal class ItApp {
        @Bean
        fun pinnedNanoTimeSource(): NanoTimeSource = NanoTimeSource { 0L }

        @Bean
        fun pinnedCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator { "it-generated" }

        @Bean
        fun itController(): ItController = ItController()
    }

    @RestController
    internal class ItController {
        @GetMapping("/it/things/{id}")
        fun thing(
            @PathVariable id: String,
        ): Mono<String> = Mono.just("thing-$id")

        @PostMapping("/it/echo")
        fun echo(
            @RequestBody body: String,
        ): Mono<String> = Mono.just("echo:$body")

        @GetMapping("/it/boom")
        fun boom(): Mono<String> = Mono.error(IllegalStateException("it boom"))

        /** A real suspend handler echoing the MDC it observes - the coroutine-variant proof endpoint. */
        @GetMapping("/it/suspend-mdc")
        suspend fun suspendMdc(): String = MDC.get(MdcKeys.REQUEST_ID) ?: "absent"

        @GetMapping("/it/excluded/ping")
        fun excluded(): Mono<String> = Mono.just("pong")
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
