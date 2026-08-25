package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import io.micrometer.context.ContextRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
 * The COROUTINE variant ([CoRequestLoggingWebFilter]) against a real Netty server - the twin of
 * [RequestLoggingWebFilterReactorIntegrationTest] for `suspend fun` applications. The variant is
 * demanded explicitly (`endpoint-logging.variant=coroutine`) rather than left to classpath detection,
 * so the test also proves the explicit selection path of the auto-configuration. Every handler here
 * is a suspend function, so the chain runs through [org.springframework.web.server.CoWebFilter]'s
 * coroutine-to-Reactor bridge exactly as WebFlux drives it in a coroutine application.
 *
 * Proves what the mock-exchange [CoRequestLoggingWebFilterTest] cannot: the suspend try/catch signal
 * mapping against Boot's real error handler (commit-deferred emission of the RENDERED 500, with the
 * original cause reachable across kotlinx's stack-trace recovery), the DataBuffer tee under a suspend
 * handler's real body read, the commit-action ordering against a real container, and the coroutine
 * variant's defining feature end to end: a log line written INSIDE a suspend handler, after a real
 * dispatcher hop, carries the `endpoint_*` identity - without `context-propagation` accessors, which the
 * auto-configuration must NOT install while this variant owns the slot.
 *
 * Determinism: pinned time and id beans; events awaited via [AwaitingAppender]. FLAT class with an inner
 * static configuration - see the Spring Boot test isolation caveat in CLAUDE.md.
 */
@SpringBootTest(
    classes = [CoRequestLoggingWebFilterCoroutineIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.variant=coroutine",
        "endpoint-logging.logger-name=http-exchange-coroutine-integration-test",
        "endpoint-logging.log-request-body=true",
        "endpoint-logging.log-response-body=true",
        "endpoint-logging.response-headers.includes=X-Late",
    ],
)
class CoRequestLoggingWebFilterCoroutineIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()
    private lateinit var exchangeLogger: Logger
    private lateinit var exchangeAppender: AwaitingAppender
    private lateinit var handlerLogger: Logger
    private lateinit var handlerAppender: AwaitingAppender

    @BeforeEach
    fun setUp() {
        exchangeLogger = LoggerFactory.getLogger("http-exchange-coroutine-integration-test") as Logger
        exchangeAppender = AwaitingAppender().apply { start() }
        exchangeLogger.addAppender(exchangeAppender)
        exchangeLogger.level = Level.INFO
        handlerLogger = LoggerFactory.getLogger(HANDLER_LOGGER) as Logger
        handlerAppender = AwaitingAppender().apply { start() }
        handlerLogger.addAppender(handlerAppender)
        handlerLogger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        exchangeLogger.detachAppender(exchangeAppender)
        exchangeAppender.stop()
        handlerLogger.detachAppender(handlerAppender)
        handlerAppender.stop()
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

    private fun causeMessages(proxy: IThrowableProxy?): List<String> =
        generateSequence(proxy) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `should run the coroutine variant as the single active filter without context-propagation accessors`() {
        // What is tested: the explicit variant selection (variant=coroutine) and the README's promise
        //   that the endpoint_* ThreadLocalAccessors are installed ONLY while the Reactor variant owns
        //   the slot - the coroutine variant carries the MDC natively via MDCContext.
        // Success criteria: exactly one EndpointLoggingFilter bean, of the coroutine type; no Reactor
        //   filter; no endpoint_* accessor in the JVM-global ContextRegistry.
        // Why it matters: accessors registered alongside the coroutine variant would be dead weight at
        //   best and a false startup warning at worst (finding 8 of CODE_ANALYSIS-2026-08-22T16-35-46.md).
        // Given/When: the application started with the coroutine variant demanded
        // Then: the coroutine filter owns the slot, the Reactor variant is absent, no accessors registered
        assertThat(context.getBeansOfType(EndpointLoggingFilter::class.java).values)
            .singleElement()
            .isInstanceOf(CoRequestLoggingWebFilter::class.java)
        assertThat(context.getBeansOfType(RequestLoggingWebFilter::class.java)).isEmpty()
        assertThat(ContextRegistry.getInstance().threadLocalAccessors.map { it.key() })
            .doesNotContainAnyElementsOf(EndpointMdcContextPropagation.KEYS)
    }

    @Test
    fun `should log a real round trip with both bodies through a suspend handler`() {
        // Given/When: the real Netty application; a real POST whose suspend handler reads the body and echoes it
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/co/echo"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .header("X-Correlation-Id", "co-corr-1")
                .POST(HttpRequest.BodyPublishers.ofString("hello coroutine"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: served with the echo, one INFO event with both bodies and the handler pattern - the
        //   identical line the Reactor variant produces (same ExchangeLifecycle)
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("echo:hello coroutine")
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("co-corr-1")
        val event = exchangeAppender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange POST /co/echo -> 200 [endpoint_request_id=co-corr-1]")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_url_template", "/co/echo")
            .containsEntry("endpoint_request_body", "hello coroutine")
            .containsEntry("endpoint_response_body", "echo:hello coroutine")
            .doesNotContainKey("endpoint_async")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "co-corr-1")
    }

    @Test
    fun `should carry the endpoint MDC into a log line written inside a suspend handler after a dispatcher hop`() {
        // What is tested: the coroutine variant's defining feature end to end - CoWebFilter publishes the
        //   MDCContext-carrying coroutine context to the handler invocation, and MDCContext restores the
        //   map on EVERY resumption, so a log statement after `withContext(Dispatchers.Default)` - a real
        //   thread change away from the event loop - still carries the identity.
        // Success criteria: the handler's own log event (a different logger, captured independently)
        //   carries endpoint_request_id, endpoint_method and endpoint_route in its MDC; the response body
        //   confirms the handler read the same id from MDC on the hopped thread.
        // Why it matters: handler-side correlation is the whole reason the variant exists; the unit test
        //   proves it over a mock exchange, only a real runtime proves the WebFlux hand-off.
        // Given/When: the real Netty application; a real GET against the hopping suspend handler
        val response = get("/co/hop/9", "X-Correlation-Id" to "co-hop")

        // Then: the handler saw the identity after the hop
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("co-hop")

        // And: the handler's log line carries the full identity, written from the hopped thread
        val handlerEvent = handlerAppender.awaitEvents(1).single()
        assertThat(handlerEvent.formattedMessage).isEqualTo("inside suspend handler after hop")
        assertThat(handlerEvent.mdcPropertyMap)
            .containsEntry(MdcKeys.REQUEST_ID, "co-hop")
            .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
            .containsEntry(MdcKeys.ROUTE, "/co/hop/9")
        assertThat(handlerEvent.threadName).isNotEqualTo(exchangeAppender.awaitEvents(1).single().threadName)
    }

    @Test
    fun `should log the rendered 500 via the commit-deferred emission when a suspend handler throws`() {
        // What is tested: the suspend try/catch signal mapping against Boot's REAL error handler - the
        //   Throwable branch rethrows, the terminal handling defers the emission to the commit callback,
        //   and across the coroutine-to-Reactor bridge kotlinx's stack-trace recovery may hand the
        //   container a COPY whose cause is the original.
        // Success criteria: the client sees 500, the single ERROR event carries 500, outcome failure,
        //   and the original message is reachable through the cause chain; the echo survives.
        // Why it matters: the variant must not lose error semantics or the status to the bridge.
        // Given/When: the real Netty application; a real GET hits a throwing suspend handler
        val response = get("/co/boom", "X-Correlation-Id" to "co-corr-boom")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("co-corr-boom")
        val event = exchangeAppender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 500")
        assertThat(keyValues(event))
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("co boom") }
    }

    @Test
    fun `should log the status and header a later commit action applies on a real suspend error response`() {
        // What is tested: the commit-action ordering against a REAL container, reached through the
        //   coroutine variant's error branch - a downstream filter's beforeCommit action turns the
        //   rendered 500 into a 503 and adds a header; the module's callback, registered at the error
        //   signal, must run behind it.
        // Success criteria: the client receives 503 plus the header, and the single ERROR event carries
        //   503 and the header.
        // Why it matters: the deferral is shared lifecycle code, but the coroutine branch reaches it
        //   through a rethrow across the bridge - the container must still order it correctly.
        // Given/When: the real Netty application; a real GET hits the throwing suspend handler behind the late filter
        val response = get("/co/boom-late")

        // Then: the client sees the mutated response, the event agrees with it
        assertThat(response.statusCode()).isEqualTo(503)
        assertThat(response.headers().firstValue("X-Late")).contains("late")
        val event = exchangeAppender.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 503")
        assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 503)
        assertThat(keyValues(event)["endpoint_response_headers"].toString()).contains("X-Late:\"late\"")
    }

    @Test
    fun `should record the handler pattern of a real suspend dispatch`() {
        // Given/When: the real Netty application; a GET against a templated suspend route
        val response = get("/co/things/42")

        // Then
        assertThat(response.body()).isEqualTo("thing-42")
        val event = exchangeAppender.awaitEvents(1).single()
        assertThat(keyValues(event))
            .containsEntry("endpoint_url_path", "/co/things/42")
            .containsEntry("endpoint_url_template", "/co/things/{id}")
    }

    /** Minimal coroutine application: the module's auto-configuration plus pinned beans and suspend handlers. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    internal class ItApp {
        @Bean
        fun pinnedNanoTimeSource(): NanoTimeSource = NanoTimeSource { 0L }

        @Bean
        fun pinnedCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator { "co-generated" }

        @Bean
        fun coController(): CoController = CoController()

        /**
         * A downstream filter (ordered after the endpoint filter) that registers a LATER commit action
         * for the `/co/boom-late` route - the shape security/session header writers take.
         */
        @Bean
        fun lateCommitActionFilter(): WebFilter =
            object : WebFilter, Ordered {
                override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

                override fun filter(
                    exchange: ServerWebExchange,
                    chain: WebFilterChain,
                ): Mono<Void> {
                    if (exchange.request.uri.path == "/co/boom-late") {
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
    internal class CoController {
        private val log = LoggerFactory.getLogger(HANDLER_LOGGER)

        @GetMapping("/co/things/{id}")
        suspend fun thing(
            @PathVariable id: String,
        ): String = "thing-$id"

        @PostMapping("/co/echo")
        suspend fun echo(
            @RequestBody body: String,
        ): String = "echo:$body"

        /**
         * Hops to a real dispatcher thread, logs there, and returns the correlation id it reads from
         * the hopped thread's MDC - the proof endpoint for the MDCContext hand-off.
         */
        @GetMapping("/co/hop/{id}")
        suspend fun hop(
            @PathVariable id: String,
        ): String =
            withContext(Dispatchers.Default) {
                log.info("inside suspend handler after hop")
                MDC.get(MdcKeys.REQUEST_ID) ?: "absent"
            }

        @GetMapping("/co/boom")
        suspend fun boom(): String = throw IllegalStateException("co boom")

        @GetMapping("/co/boom-late")
        suspend fun boomLate(): String = throw IllegalStateException("co boom late")
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val HANDLER_LOGGER = "co-integration-test-handler"
    }
}
