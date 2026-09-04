package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The REACTOR variant ([RequestLoggingWebFilter]) against a real Netty server. The module's test
 * classpath carries the coroutine libraries, so the shipped auto-configuration selects the coroutine
 * variant in [RequestLoggingWebFilterIntegrationTest]; this class excludes the coroutine
 * auto-configuration at context discovery, which leaves the Reactor filter as the active bean - the
 * majority consumer configuration, where the optional coroutine dependencies are absent. Proves
 * the Reactor-specific `Mono.defer`/`doFinally`
 * lifecycle, the DataBuffer tee on real Netty buffers, and the commit-deferred error emission.
 *
 * Determinism: pinned time and id beans; events awaited via [AwaitingAppender]. FLAT class with an inner
 * static configuration - see the Spring Boot test isolation caveat. The Reactor variant's
 * initializer writes the accessors into the JVM-global ContextRegistry at context start; the class-level
 * guard takes them out again afterwards.
 */
@SpringBootTest(
    classes = [RequestLoggingWebFilterReactorIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-reactor-integration-test",
        "endpoint-logging.log-request-body=always",
        "endpoint-logging.log-response-body=always",
        "endpoint-logging.response-headers.includes=X-Late",
        "endpoint-logging.response-headers.unmasked=X-Late",
        "spring.autoconfigure.exclude=eu.inqudium.limesium.reactive.logging.CoRequestLoggingAutoConfiguration",
    ],
)
class RequestLoggingWebFilterReactorIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("endpoint-http-exchange-reactor-integration-test") as Logger
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

    @Test
    fun `should run the reactor variant as the single active filter`() {
        // Given/When: the application started with the coroutine auto-configuration excluded
        // Then: the Reactor filter owns the slot; the coroutine variant is absent
        assertThat(context.getBeansOfType(EndpointLoggingFilter::class.java).values)
            .singleElement()
            .isInstanceOf(RequestLoggingWebFilter::class.java)
        assertThat(context.getBeansOfType(CoRequestLoggingWebFilter::class.java)).isEmpty()
    }

    @Test
    fun `should log a real round trip with both bodies through the reactor lifecycle`() {
        // Given/When: the real Netty application; a real POST whose handler reads the body and echoes it
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/rx/echo"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .header("X-Correlation-Id", "rx-corr-1")
                .POST(HttpRequest.BodyPublishers.ofString("hello reactor"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: served with the echo, one INFO event with both bodies and the handler pattern
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("echo:hello reactor")
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("rx-corr-1")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange POST /rx/echo -> 200 [endpoint_request_id=rx-corr-1]")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_url_template", "/rx/echo")
            .containsEntry("endpoint_request_body", "hello reactor")
            .containsEntry("endpoint_response_body", "echo:hello reactor")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "rx-corr-1")
    }

    @Test
    fun `should log the rendered 500 via the commit-deferred emission of the reactor variant`() {
        // What is tested: the Reactor-specific error path - the ERROR signal passes doOnError/doFinally
        //   before Boot's error handler renders; the emission must wait for the commit callback.
        // Success criteria: the client sees 500, the single ERROR event carries 500, outcome failure
        //   and the cause, and the correlation echo survives onto the error response.
        // Why it matters: until now this path was proven only in a mock exchange; a real container
        //   orders the signals, not the test.
        // Given/When: the real Netty application; a real GET hits a throwing handler
        val response = get("/rx/boom", "X-Correlation-Id" to "rx-corr-boom")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("rx-corr-boom")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 500")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(event.throwableProxy).isNotNull()
    }

    @Test
    fun `should log the status and header a later commit action applies on a real error response`() {
        // What is tested: the commit-action ordering against a REAL container - a downstream WebFilter
        //   registers a beforeCommit action that
        //   turns Boot's rendered 500 into a 503 and adds a header; the module's callback, registered at
        //   the error signal, must run behind it.
        // Success criteria: the client receives 503 plus the header, and the single ERROR event carries
        //   503 and the header - what the response applied, not the pre-action 500.
        // Why it matters: security/session filters register their header writers exactly this way; the
        //   real container, not the test, orders the actions.
        // Given/When: the real Netty application; a real GET hits the throwing handler behind the late filter
        val response = get("/rx/boom-late")

        // Then: the client sees the mutated response, the event agrees with it
        assertThat(response.statusCode()).isEqualTo(503)
        assertThat(response.headers().firstValue("X-Late")).contains("late")
        val event = appender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 503")
        assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 503)
        assertThat(keyValues(event)["endpoint_response_headers"].toString()).contains("X-Late:\"late\"")
    }

    @Test
    fun `should record the handler pattern of a real dispatch`() {
        // Given/When: the real Netty application; a GET against a templated route
        val response = get("/rx/things/42")

        // Then
        assertThat(response.body()).isEqualTo("thing-42")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event))
            .containsEntry("endpoint_url_path", "/rx/things/42")
            .containsEntry("endpoint_url_template", "/rx/things/{id}")
    }

    /** Minimal reactive application: the module's auto-configuration plus pinned beans and handlers. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    internal class ItApp {
        @Bean
        fun pinnedNanoTimeSource(): NanoTimeSource = NanoTimeSource { 0L }

        @Bean
        fun pinnedCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator { "rx-generated" }

        @Bean
        fun rxController(): RxController = RxController()

        /**
         * A downstream filter (ordered after the endpoint filter) that registers a LATER commit action
         * for the `/rx/boom-late` route - the shape security/session header writers take.
         */
        @Bean
        fun lateCommitActionFilter(): WebFilter =
            object : WebFilter, Ordered {
                override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

                override fun filter(
                    exchange: ServerWebExchange,
                    chain: WebFilterChain,
                ): Mono<Void> {
                    if (exchange.request.uri.path == "/rx/boom-late") {
                        exchange.response.beforeCommit {
                            Mono.fromRunnable {
                                exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                                exchange.response.headers.add("X-Late", "late")
                            }
                        }
                    }
                    return chain.filter(exchange)
                }
            }
    }

    @RestController
    internal class RxController {
        @GetMapping("/rx/things/{id}")
        fun thing(
            @PathVariable id: String,
        ): Mono<String> = Mono.just("thing-$id")

        @PostMapping("/rx/echo")
        fun echo(
            @RequestBody body: String,
        ): Mono<String> = Mono.just("echo:$body")

        @GetMapping("/rx/boom")
        fun boom(): Mono<String> = Mono.error(IllegalStateException("rx boom"))

        @GetMapping("/rx/boom-late")
        fun boomLate(): Mono<String> = Mono.error(IllegalStateException("rx boom late"))
    }

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val accessorRegistry = EndpointAccessorRegistryGuard()

        // Static lifecycle on purpose: the context (and with it the accessor registration) is loaded
        // when the first test INSTANCE is prepared, i.e. after @BeforeAll and before any @BeforeEach.
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
