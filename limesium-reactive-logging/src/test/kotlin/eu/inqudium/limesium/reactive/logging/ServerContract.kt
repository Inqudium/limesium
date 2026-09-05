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
import org.springframework.boot.web.server.reactive.ReactiveWebServerFactory
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
 * The server-agnosticism contract of the reactive twin, run once per reactive server Spring Boot 4 ships
 * (Reactor Netty, Tomcat and Jetty through their `HttpHandler` adapters; Undertow left Boot with 4.0):
 * the REACTOR variant ([RequestLoggingWebFilter]) as the single active filter, a real round trip with
 * both bodies teed on the server's own buffers (Netty `ByteBuf`s, the servlet adapters' pooled
 * `DataBuffer`s), the commit-deferred error emission behind the server's error rendering, a later commit
 * action's status and header as the server orders the actions, and the handler pattern of a real
 * dispatch. A subclass supplies the `ReactiveWebServerFactory`; every scenario, assertion and expected
 * line is the same.
 *
 * The module's test classpath carries the coroutine libraries, so the shipped auto-configuration selects
 * the coroutine variant in [RequestLoggingWebFilterIntegrationTest]; this contract excludes the coroutine
 * auto-configuration at context discovery, which leaves the Reactor filter as the active bean - the
 * majority consumer configuration, where the optional coroutine dependencies are absent. Boot still
 * deduces a REACTIVE application with Tomcat's and Jetty's servlet APIs on the classpath (no
 * `DispatcherServlet` there), so nothing needs pinning beyond the factory bean each subclass declares.
 *
 * Determinism: pinned time and id beans; events awaited via [AwaitingAppender]. The Reactor variant's
 * initializer writes the accessors into the JVM-global ContextRegistry at context start; the class-level
 * guard takes them out again afterwards.
 */
@SpringBootTest(
    classes = [ServerContract.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-server-contract",
        "endpoint-logging.log-request-body=always",
        "endpoint-logging.log-response-body=always",
        "endpoint-logging.response-headers.includes=X-Late",
        "endpoint-logging.response-headers.unmasked=X-Late",
        "spring.autoconfigure.exclude=eu.inqudium.limesium.reactive.logging.CoRequestLoggingAutoConfiguration",
    ],
)
abstract class ServerContract {
    /** The factory type this suite's server bean must have - the pin that the intended engine is running. */
    protected abstract val server: Class<out ReactiveWebServerFactory>

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var logger: Logger
    private lateinit var appender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger("endpoint-http-exchange-server-contract") as Logger
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
    fun `should run on this server`() {
        // What is tested: the ReactiveWebServerFactory bean the context started the application with.
        // Success criteria: exactly the factory type this suite declares - Boot's own server
        //   auto-configuration backed off behind the explicit bean.
        // Why it matters: with three servers on the test classpath, a suite that silently ran on the
        //   wrong one would pin nothing about the engine it is named after.
        // Given/When/Then
        assertThat(context.getBean(ReactiveWebServerFactory::class.java)).isInstanceOf(server)
    }

    @Test
    fun `should run the reactor variant as the single active filter on this server`() {
        // What is tested: the variant selection of the auto-configuration with the coroutine
        //   auto-configuration excluded - the majority consumer configuration - on this server.
        // Success criteria: exactly one EndpointLoggingFilter bean, the Reactor variant; no coroutine
        //   variant in the context.
        // Why it matters: two active filters would emit two lines per exchange; the wrong variant would
        //   run a lifecycle the rest of this contract does not exercise.
        // Given/When: the application started on this server with the coroutine auto-configuration excluded
        // Then: the Reactor filter owns the slot; the coroutine variant is absent
        assertThat(context.getBeansOfType(EndpointLoggingFilter::class.java).values)
            .singleElement()
            .isInstanceOf(RequestLoggingWebFilter::class.java)
        assertThat(context.getBeansOfType(CoRequestLoggingWebFilter::class.java)).isEmpty()
    }

    @Test
    fun `should log a real round trip with both bodies through this server`() {
        // What is tested: the full happy path on this server - the request body teed as the server's
        //   buffers arrive, the response body teed as the handler writes, the correlation echo, the
        //   handler pattern - through the Reactor variant's Mono.defer/doFinally lifecycle.
        // Success criteria: the client sees the echo and its correlation id; one INFO event carries both
        //   bodies, the template and the id in the MDC, format-identical across servers.
        // Why it matters: the tee reads whatever DataBuffer implementation the server hands out; a
        //   server whose buffers the tee could not read would log empty bodies without another symptom.
        // Given/When: the real application on this server; a real POST whose handler reads the body and echoes it
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
    fun `should log the rendered 500 via the commit-deferred emission on this server`() {
        // What is tested: the Reactor-specific error path - the ERROR signal passes doOnError/doFinally
        //   before Boot's error handler renders; the emission must wait for the commit callback.
        // Success criteria: the client sees 500, the single ERROR event carries 500, outcome failure
        //   and the cause, and the correlation echo survives onto the error response.
        // Why it matters: a real server orders the signals, not the test - and each server renders the
        //   error on its own path.
        // Given/When: the real application on this server; a real GET hits a throwing handler
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
    fun `should log the status and header a later commit action applies on this server's error response`() {
        // What is tested: the commit-action ordering against a REAL container - a downstream WebFilter
        //   registers a beforeCommit action that
        //   turns Boot's rendered 500 into a 503 and adds a header; the module's callback, registered at
        //   the error signal, must run behind it.
        // Success criteria: the client receives 503 plus the header, and the single ERROR event carries
        //   503 and the header - what the response applied, not the pre-action 500.
        // Why it matters: security/session filters register their header writers exactly this way; the
        //   real server, not the test, orders the actions.
        // Given/When: the real application on this server; a real GET hits the throwing handler behind the late filter
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
    fun `should record the handler pattern of a real dispatch on this server`() {
        // What is tested: the BEST_MATCHING_PATTERN attribute WebFlux sets during a real dispatch, read
        //   at emission time on this server.
        // Success criteria: endpoint_url_path carries the expanded path, endpoint_url_template the
        //   pattern with its placeholder.
        // Why it matters: the template is the aggregation half of the path pair; a server whose dispatch
        //   left the attribute unset would collapse every route into one bucket.
        // Given/When: the real application on this server; a GET against a templated route
        val response = get("/rx/things/42")

        // Then
        assertThat(response.body()).isEqualTo("thing-42")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event))
            .containsEntry("endpoint_url_path", "/rx/things/42")
            .containsEntry("endpoint_url_template", "/rx/things/{id}")
    }

    @Test
    fun `should pin that a form body WebFlux parses bypasses the request tee on this server`() {
        // What is tested: the documented capture BOUNDARY for framework-parsed bodies - a form POST read
        //   through `getFormData()` (what a @ModelAttribute binding resolves too), which the mutated
        //   exchange delegates to the ORIGINAL exchange whose form-data publisher reads the undecorated
        //   request.
        // Success criteria: the handler sees the field, the exchange event exists, and it carries NO
        //   endpoint_request_body although log-request-body is enabled class-wide (the response body,
        //   written through the tee, is present).
        // Why it matters: form and multipart endpoints sit at `unread` on the read counter by
        //   construction; the pin keeps that boundary a conscious, documented contract on every server
        //   (code analysis of 2026-09-05, finding 2).
        // Given/When: the real application on this server; a real form POST against the form-data handler
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/rx/form"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=form-value"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: served from the parsed field, event present, request body absent by documented contract
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("form:form-value")
        val event = appender.awaitEvents(1).single()
        assertThat(keyValues(event))
            .doesNotContainKey("endpoint_request_body")
            .containsEntry("endpoint_response_body", "form:form-value")
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

        /** Reads a form field through the exchange's form data - which the mutated exchange delegates to the ORIGINAL one. */
        @PostMapping("/rx/form")
        fun form(exchange: ServerWebExchange): Mono<String> = exchange.formData.map { "form:${it.getFirst("name")}" }
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
