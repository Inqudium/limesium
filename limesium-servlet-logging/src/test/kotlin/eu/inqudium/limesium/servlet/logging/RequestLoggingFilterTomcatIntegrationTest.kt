package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import eu.inqudium.limesium.common.AwaitingAppender
import eu.inqudium.limesium.common.CapturedLogger
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.keyValues
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Callable

/**
 * End-to-end test of the auto-configured [RequestLoggingFilter] against a REAL embedded Tomcat: the
 * auto-configuration registers the filter, Spring MVC dispatches to real controllers, requests arrive over
 * real HTTP (JDK [HttpClient]), and the exchange events are observed on the configured exchange logger.
 *
 * This covers what the servlet-mock tests cannot: the tee wrappers under Tomcat's real request/response
 * streams, the handler-pattern attribute recorded by real MVC dispatch, the genuine async lifecycle of a
 * [Callable] controller (startAsync, async dispatch, container-fired onComplete), and the error dispatch
 * of an unhandled controller exception.
 *
 * Determinism: time and correlation ids come from pinned beans (the auto-configured defaults back off), so
 * durations are exactly 0 ms and generated ids are a known constant. Emission happens on container
 * threads, so arrival is awaited via a [Semaphore]-based appender - event-driven with a hard timeout,
 * never a sleep.
 *
 * The class is deliberately FLAT and uses an inner static configuration - see the Spring Boot test
 * isolation caveat on @Nested classes. Each test drives its own endpoint, so the shared
 * context holds no per-test state beyond the appender, which is fresh per test.
 */
@SpringBootTest(
    classes = [RequestLoggingFilterTomcatIntegrationTest.ItApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "endpoint-logging.logger-name=endpoint-http-exchange-integration-test",
        "endpoint-logging.log-request-body=always",
        "endpoint-logging.log-response-body=always",
        "endpoint-logging.request-headers.includes=Accept",
        "endpoint-logging.request-headers.unmasked=Accept",
        "endpoint-logging.response-headers.includes=Content-Type",
        "endpoint-logging.response-headers.unmasked=Content-Type",
        "endpoint-logging.exclude-path-prefixes=/it/excluded",
        // The tracing jars sit on the test classpath for the tracing integration test; THIS context
        // excludes the bridge explicitly, so the exact-message assertions here stay trace-free. Jetty
        // sits on the classpath for the Jetty capture-boundary test and its auto-configuration would
        // WIN the server slot (Boot 4 orders the per-server auto-configurations alphabetically), so
        // this context pins Tomcat by excluding it.
        "spring.autoconfigure.exclude=org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration," +
            "org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration",
    ],
)
class RequestLoggingFilterTomcatIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    // Every real-HTTP call carries its own deadline: a stalled embedded endpoint must produce a bounded
    // failing test, not a hung executor. The
    // appender's wait is a SEPARATE bound for the post-response emission.
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()

    @JvmField
    @RegisterExtension
    final val exchangeLog = CapturedLogger("endpoint-http-exchange-integration-test")

    @AfterEach
    fun tearDown() {
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

    private fun post(
        path: String,
        body: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun causeMessages(proxy: IThrowableProxy?): List<String> = generateSequence(proxy) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `should log one complete event for a real synchronous exchange including template headers and bodies`() {
        // What is tested: the full happy path through real Tomcat - filter registration by the
        //   auto-configuration, MVC dispatch, the tee wrappers on Tomcat's real streams, the recorded
        //   handler pattern, header selection, and the correlation echo.
        // Success criteria: the response is correct AND exactly one INFO event carries every endpoint_*
        //   field with the values the exchange really had.
        // Why it matters: the servlet-mock tests prove the filter logic; only a real container proves the
        //   wiring - stream wrapping, attribute names, filter ordering - actually holds.
        // Given/When: the real Tomcat application; a real GET with a correlation id and an Accept header
        val response = get("/it/things/7", "X-Correlation-Id" to "it-corr-1", "Accept" to "text/plain")

        // Then: the response is served normally and echoes the correlation id
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("thing-7")
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-corr-1")

        // And: exactly one INFO event with the full field family
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange GET /it/things/7 -> 200 [endpoint_request_id=it-corr-1]")
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_request_method", "GET")
            .containsEntry("endpoint_url_path", "/it/things/7")
            .containsEntry("endpoint_url_template", "/it/things/{id}")
            .containsEntry("endpoint_response_status_code", 200)
            .containsEntry("endpoint_duration_ms", 0L)
            .containsEntry("endpoint_async", false)
            .containsEntry("endpoint_request_headers", "[Accept:\"text/plain\"]")
            .containsEntry("endpoint_response_body", "thing-7")
        assertThat(event.keyValues()["endpoint_response_headers"].toString()).contains("Content-Type")
        assertThat(event.mdcPropertyMap)
            .containsEntry(MdcKeys.REQUEST_ID, "it-corr-1")
            .containsEntry(MdcKeys.REQUEST_METHOD, "GET")
            .containsEntry(MdcKeys.ROUTE, "/it/things/7")
    }

    @Test
    fun `should capture both bodies of a real round trip through the tee wrappers`() {
        // What is tested: both tees on real Tomcat streams - a POST whose controller reads and
        //   echoes the body.
        // Success criteria: the client sees the echo; the event carries the request body and the
        //   echoed response body.
        // Why it matters: Tomcat's streams differ from the mocks (buffering, flush timing); only
        //   the real container proves the wrappers observe without disturbing.
        // Given/When: the real Tomcat application; a real POST whose controller reads the body and echoes it back
        val response = post("/it/echo", "hello integration")

        // Then: the exchange worked and both tees captured what actually flowed through Tomcat's streams
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("echo:hello integration")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.keyValues())
            .containsEntry("endpoint_request_body", "hello integration")
            .containsEntry("endpoint_response_body", "echo:hello integration")
    }

    @Test
    fun `should generate a correlation id from the pinned generator when the caller sends none`() {
        // What is tested: the generated-id path on real Tomcat.
        // Success criteria: the pinned id is echoed on the wire and carried in the event's MDC.
        // Why it matters: the echo header must survive the real container's response handling, or
        //   callers could never quote the id.
        // Given/When: the real Tomcat application; a real GET without a correlation header
        val response = get("/it/things/1")

        // Then: the pinned generator's id is echoed on the wire and carried in the event's MDC
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-generated")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "it-generated")
    }

    @Test
    fun `should log a real async exchange after completion with the async flag set`() {
        // What is tested: the genuine servlet async lifecycle - MVC's Callable support calls startAsync,
        //   the response is written on a worker thread, the CONTAINER fires onComplete - AND the
        //   worker-thread MDC: the Callable itself reads its
        //   own MDC and echoes the correlation id it saw there, so the response body is the proof that
        //   the identity reached the ASYNC WORKER, not merely the final emitter overlay.
        // Success criteria: the body carries the correlation id the worker observed in ITS MDC; one INFO
        //   event, endpoint_async=true, correct status, correlation id in the event's MDC.
        // Why it matters: the async path is where naive synchronous logging silently reports
        //   wrong data, and worker logs without the identity were exactly the earlier defect - the mock
        //   test drives the listener by hand, only Tomcat proves the real thread hand-off.
        // Given/When: the real Tomcat application; a real GET against a Callable controller method that echoes its worker MDC
        val response = get("/it/async")

        // Then: the worker saw the identity in ITS thread-local MDC, and the event is complete
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("async-done:it-generated|render:it-generated")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "success")
            .containsEntry("endpoint_async", true)
            .containsEntry("endpoint_response_status_code", 200)
            .containsEntry("endpoint_response_body", "async-done:it-generated|render:it-generated")
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "it-generated")
    }

    @Test
    fun `should carry the endpoint MDC into the real async dispatch that renders the result`() {
        // What is tested: the ASYNC-dispatch pass against real Tomcat and real MVC - the Callable's result is
        //   rendered in the container's ASYNC
        //   dispatch, where a ResponseBodyAdvice observes the MDC.
        // Success criteria: the advice appends the correlation id it saw during rendering; the body
        //   therefore carries it twice - from the worker AND from the rendering dispatch.
        // Why it matters: converters, advice and interceptors log in that phase; before the fix their
        //   lines lost the identity.
        // Given/When: a real GET against the Callable endpoint
        val response = get("/it/async")

        // Then: worker-side and render-side MDC both observed
        assertThat(response.body()).isEqualTo("async-done:it-generated|render:it-generated")
        exchangeLog.awaitEvents(1)
    }

    @Test
    fun `should log a failing Callable at ERROR with its cause via the async dispatch`() {
        // What is tested: the async failure path end to end - the Callable throws on the worker, MVC
        //   rethrows in the ASYNC dispatch, the filter's second pass records it, Tomcat renders 500.
        // Success criteria: client 500; one ERROR event, outcome failure, endpoint_async=true, the
        //   cause chain naming the Callable's exception.
        // Why it matters: parity with the sync /it/boom case - the same crash must classify the same.
        // Given/When
        val response = get("/it/async-boom")

        // Then
        assertThat(response.statusCode()).isEqualTo(500)
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_async", true)
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("async boom") }
    }

    @Test
    fun `should log a DeferredResult error result at ERROR with its cause via the async dispatch`() {
        // What is tested: a DeferredResult completed with an error result on real Tomcat - the
        //   async dispatch path.
        // Success criteria: the client sees 500; one ERROR event with outcome failure,
        //   endpoint_async true and the deferred failure in the cause chain.
        // Why it matters: a DeferredResult error reaches the filter through the ASYNC dispatch, not
        //   the initial one; the classification must hold across that boundary.
        // Given/When: a DeferredResult completed with an error result
        val response = get("/it/deferred-boom")

        // Then: same classification as the Callable failure
        assertThat(response.statusCode()).isEqualTo(500)
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_async", true)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("deferred boom") }
    }

    @Test
    fun `should pin that zero-argument servlet async bypasses the body tee by contract`() {
        // What is tested: the documented async capture BOUNDARY - Jakarta Servlet specifies that
        //   zero-argument startAsync() initializes its
        //   AsyncContext with the ORIGINAL request/response, so a raw async worker writes beside the
        //   tee; only the wrapper-preserving two-argument path (which Spring MVC uses) captures.
        // Success criteria: the raw-async response reaches the client, the exchange event exists, and it
        //   carries NO endpoint_response_body although log-response-body is enabled class-wide.
        // Why it matters: this pin keeps the boundary a conscious contract - whoever makes wrapper
        //   retention an owned invariant across the zero-argument API must flip it deliberately.
        // Given/When: the real Tomcat application; a real GET against the raw servlet using zero-argument startAsync
        val response = get("/it/raw-async")

        // Then: served through the original response, event present, body absent by documented contract
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("raw-async")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.keyValues()).doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should discard buffered output replaced by sendError and log no stale body`() {
        // What is tested: the sendError half of the buffer-replacement handling against the
        //   real container - the controller writes into the buffer and then replaces the response via
        //   sendError; the wrapper's sendError override discards the capture with the buffer, and the
        //   rendered error page is written through the ERROR dispatch outside the tee (the documented
        //   boundary).
        // Success criteria: the client sees the 503 error rendering (not the discarded bytes), and the
        //   WARN/failure event carries NO endpoint_response_body - neither the stale pre-error bytes nor
        //   a fabricated error body.
        // Why it matters: stale bodies on exactly the failure responses operators investigate assert
        //   content the client never received.
        // Given/When: the real Tomcat application; a real GET against the partial-write-then-sendError controller
        val response = get("/it/partial-error")

        // Then: error rendering reached the client - the discarded bytes never prefix the body (the
        //   error JSON legitimately contains the request PATH, so the check pins the body START)
        assertThat(response.statusCode()).isEqualTo(503)
        assertThat(response.body()).doesNotStartWith("partial").contains("\"status\":503")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 503)
            .doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should echo the correlation id even on the error-dispatched 500 response`() {
        // What is tested: the client-visible half of the error path - whether the correlation echo set at
        //   filter entry survives the container's error dispatch.
        // Success criteria: the 500 response carries the X-Correlation-Id header.
        // Why it matters: the reference configuration promises the id is ALWAYS echoed; failures are
        //   exactly the responses a support case needs to correlate.
        // Given/When: the real Tomcat application; a real GET hits a throwing controller
        val response = get("/it/boom", "X-Correlation-Id" to "it-corr-boom")

        // Then: the container answered 500 and the echo survived the error dispatch
        assertThat(response.statusCode()).isEqualTo(500)
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("it-corr-boom")
        exchangeLog.awaitEvents(1)
    }

    @Test
    fun `should log an unhandled controller exception at ERROR with outcome failure and still answer 500`() {
        // What is tested: the real error path - an unhandled controller exception propagates through
        //   DispatcherServlet and the filter chain, the filter records and rethrows it, Tomcat's error
        //   dispatch renders the 500.
        // Success criteria: the client sees 500, exactly one ERROR event with outcome failure whose cause
        //   chain names the original exception.
        // Why it matters: the rethrow-unchanged contract is what keeps container error handling working;
        //   a filter that swallowed the exception would turn every crash into a half-written 200.
        // Given/When: the real Tomcat application; a real GET against a throwing controller method
        val response = get("/it/boom")

        // Then: the container answered 500 and the single event is ERROR/failure with the original cause
        //   AND the FINAL status - emission at request destruction runs after the error dispatch, so the
        //   event carries the 500 the client really received, not the pre-dispatch 200
        assertThat(response.statusCode()).isEqualTo(500)
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.formattedMessage).contains("-> 500")
        assertThat(event.keyValues())
            .containsEntry("endpoint_outcome", "failure")
            .containsEntry("endpoint_response_status_code", 500)
        assertThat(causeMessages(event.throwableProxy)).anySatisfy { assertThat(it).contains("it boom") }

        // And: the documented capture BOUNDARY - the
        //   error body is rendered by the container's ERROR dispatch through the ORIGINAL response, so
        //   the client receives a body while the event must NOT carry endpoint_response_body although
        //   log-response-body is enabled class-wide. Whoever routes error rendering through an
        //   equivalent capture must flip this pin consciously.
        assertThat(response.body()).isNotEmpty()
        assertThat(event.keyValues()).doesNotContainKey("endpoint_response_body")
    }

    @Test
    fun `should not log an excluded path while still logging the next regular exchange`() {
        // What is tested: the exclude prefix on real Tomcat, followed by a regular request.
        // Success criteria: both served with 200; exactly one event, for the regular path.
        // Why it matters: an exclusion must not leave request state behind that suppresses or
        //   duplicates the next exchange on the same container.
        // Given/When: the real Tomcat application; a request below the excluded prefix, followed by a regular one
        val excluded = get("/it/excluded/ping")
        val regular = get("/it/things/2")

        // Then: both were served, but only the regular exchange produced an event
        assertThat(excluded.statusCode()).isEqualTo(200)
        assertThat(regular.statusCode()).isEqualTo(200)
        val events = exchangeLog.awaitEvents(1)
        assertThat(events).hasSize(1)
        assertThat(events.single().keyValues()).containsEntry("endpoint_url_path", "/it/things/2")
    }

    @Test
    fun `should pin that a form body the container parses bypasses the request tee`() {
        // What is tested: the documented capture BOUNDARY for framework-parsed bodies - a form POST
        //   read through @RequestParam makes the container parse the ORIGINAL request's stream; the
        //   wrapper's getInputStream()/getReader() are never selected.
        // Success criteria: the controller sees the field, the exchange event exists, and it carries NO
        //   endpoint_request_body although log-request-body is enabled class-wide (the response body,
        //   written through the tee, is present).
        // Why it matters: form and multipart endpoints sit at `unread` on the read counter by
        //   construction; the pin keeps that boundary a conscious, documented contract (code analysis
        //   of 2026-09-05, finding 2).
        // Given/When: the real Tomcat application; a real form POST against the @RequestParam controller
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/it/form"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=form-value"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Then: served from the parsed field, event present, request body absent by documented contract
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("form:form-value")
        val event = exchangeLog.awaitEvents(1).single()
        assertThat(event.keyValues())
            .doesNotContainKey("endpoint_request_body")
            .containsEntry("endpoint_response_body", "form:form-value")
    }

    /**
     * Minimal servlet application for the test: the module's auto-configuration via
     * [EnableAutoConfiguration], pinned time/id beans (the auto-configured defaults back off), and the
     * controllers under test.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    internal class ItApp {
        @Bean
        fun pinnedNanoTimeSource(): NanoTimeSource = NanoTimeSource { 0L }

        @Bean
        fun pinnedCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator { "it-generated" }

        @Bean
        fun itController(): ItController = ItController()

        @Bean
        fun renderMdcAdvice(): RenderMdcAdvice = RenderMdcAdvice()

        /**
         * A RAW servlet using the Servlet-specified zero-argument startAsync(): per spec its
         * AsyncContext holds the ORIGINAL request/response, so its worker writes beside the tee - the
         * documented capture boundary, pinned by the raw-async test.
         */
        @Bean
        fun rawAsyncServlet(): ServletRegistrationBean<HttpServlet> {
            val servlet =
                object : HttpServlet() {
                    override fun doGet(
                        req: HttpServletRequest,
                        resp: HttpServletResponse,
                    ) {
                        val ctx = req.startAsync()
                        ctx.start {
                            ctx.response.outputStream.write("raw-async".toByteArray())
                            ctx.complete()
                        }
                    }
                }
            return org.springframework.boot.web.servlet
                .ServletRegistrationBean<HttpServlet>(servlet, "/it/raw-async")
                .apply { setAsyncSupported(true) }
        }
    }

    @RestController
    internal class ItController {
        @GetMapping("/it/things/{id}")
        fun thing(
            @PathVariable id: String,
        ): String = "thing-$id"

        @PostMapping("/it/echo")
        fun echo(
            @RequestBody body: String,
        ): String = "echo:$body"

        /**
         * A genuinely asynchronous MVC endpoint: startAsync, worker-thread completion, async dispatch.
         * The Callable echoes the correlation id it observes in ITS OWN MDC - the worker-side proof of
         * the async MDC propagation.
         */
        @GetMapping("/it/async")
        fun async(): Callable<String> = Callable { "async-done:" + (MDC.get(MdcKeys.REQUEST_ID) ?: "absent") }

        @GetMapping("/it/boom")
        fun boom(): String = throw IllegalStateException("it boom")

        /** A Callable that fails on the worker - MVC rethrows it in the ASYNC dispatch. */
        @GetMapping("/it/async-boom")
        fun asyncBoom(): Callable<String> = Callable { throw IllegalStateException("async boom") }

        /** A DeferredResult completed with an error result - MVC rethrows it in the ASYNC dispatch. */
        @GetMapping("/it/deferred-boom")
        fun deferredBoom(): DeferredResult<String> =
            DeferredResult<String>().apply {
                setErrorResult(
                    IllegalStateException("deferred boom"),
                )
            }

        /** Writes into the buffer, then replaces the response via sendError - the buffer-replacement case. */
        @GetMapping("/it/partial-error")
        fun partialError(response: HttpServletResponse) {
            response.outputStream.write("partial".toByteArray())
            response.sendError(503)
        }

        @GetMapping("/it/excluded/ping")
        fun excluded(): String = "pong"

        /** Reads a form field through the PARAMETER API - the container parses that body beside the tee. */
        @PostMapping("/it/form")
        fun form(
            @RequestParam name: String,
        ): String = "form:$name"
    }

    /**
     * Observes the MDC during RESULT RENDERING - for the Callable endpoint that is the container's
     * ASYNC dispatch - and appends what it saw, so the body proves the render-side identity.
     */
    @RestControllerAdvice
    internal class RenderMdcAdvice : ResponseBodyAdvice<Any> {
        override fun supports(
            returnType: MethodParameter,
            converterType: Class<out HttpMessageConverter<*>>,
        ): Boolean = true

        override fun beforeBodyWrite(
            body: Any?,
            returnType: MethodParameter,
            selectedContentType: MediaType,
            selectedConverterType: Class<out HttpMessageConverter<*>>,
            request: ServerHttpRequest,
            response: ServerHttpResponse,
        ): Any? =
            if (request.uri.path == "/it/async") {
                "$body|render:" + (MDC.get(MdcKeys.REQUEST_ID) ?: "absent")
            } else {
                body
            }
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
