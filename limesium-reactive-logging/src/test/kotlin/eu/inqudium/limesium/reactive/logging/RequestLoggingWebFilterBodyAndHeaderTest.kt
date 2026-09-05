package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.BodyLogMode
import eu.inqudium.limesium.common.HeaderLogProperties
import eu.inqudium.limesium.common.HeaderValueMasker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ZeroCopyHttpOutputMessage
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Header selection/masking and the DataBuffer tee of [RequestLoggingWebFilter]: bodies are logged exactly
 * as they flowed - what the application subscribed to, what it wrote - bounded by
 * [RequestLoggingProperties.maxBodyBytes], and the downstream sees content-identical buffers.
 */
class RequestLoggingWebFilterBodyAndHeaderTest {
    private val ticker = AtomicLong(0)
    private val properties =
        RequestLoggingProperties(
            loggerName = "endpoint-http-exchange-reactive-body-test",
            requestHeaders = HeaderLogProperties(includes = listOf("Accept"), masked = listOf("X-Api-Key")),
            responseHeaders = HeaderLogProperties(includes = listOf("Content-Type"), unmasked = listOf("Content-Type")),
            logRequestBody = BodyLogMode.ALWAYS,
            logResponseBody = BodyLogMode.ALWAYS,
            maxBodyBytes = 8,
        )
    private val filter =
        RequestLoggingWebFilter(properties, { ticker.get() }, { "generated-42" }, SimpleMeterRegistry())

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    private fun keyValues(): Map<String, Any?> =
        appender.list
            .single()
            .keyValuePairs
            ?.associate { it.key to it.value } ?: emptyMap()

    @Nested
    inner class `Request body tee` {
        private fun postExchange(body: String): MockServerWebExchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/things").contentType(MediaType.TEXT_PLAIN).body(body),
            )

        @Test
        fun `should log the request body the application actually consumed`() {
            // What is tested: the request-body tee on a chain that subscribes to the decorated
            //   body.
            // Success criteria: endpoint_request_body carries the bytes that flowed.
            // Why it matters: the tee mirrors consumption; a decorator the chain could bypass would
            //   log an empty body without another symptom.
            // Given: a chain that subscribes to the (decorated) body
            val chain =
                WebFilterChain { ex ->
                    ex.response.statusCode = HttpStatus.OK
                    ex.request.body.then(Mono.empty())
                }

            // When: the filter handles the exchange
            filter.filter(postExchange("hello"), chain).block()

            // Then: the logged body is what flowed
            assertThat(keyValues()).containsEntry("endpoint_request_body", "hello")
        }

        @Test
        fun `should omit the request body key when the application never subscribed`() {
            // What is tested: the tee's truthfulness in reactive terms - an unsubscribed body flows
            //   nowhere.
            // Success criteria: no endpoint_request_body key, rather than an empty or fabricated value.
            // Why it matters: 'logged' must mean 'actually flowed', identical to the servlet twin's rule.
            // Given/When: a POST whose chain ignores the body
            filter
                .filter(
                    postExchange("ignored"),
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: no body key
            assertThat(keyValues()).doesNotContainKey("endpoint_request_body")
        }

        @Test
        fun `should forward the original buffer untouched and copy only the bounded prefix`() {
            // What is tested: the tee's memory contract -
            //   counting never clones the buffer; at most the capture's remaining capacity is copied
            //   via a non-advancing read, and the ORIGINAL buffer flows downstream with its read
            //   position untouched.
            // Success criteria: downstream receives the identical buffer instance, fully readable; the
            //   capture holds exactly the 8-byte prefix and counted all 16 bytes.
            // Why it matters: the old full-buffer copy made transient memory scale with response buffer
            //   sizes instead of maxBodyBytes - the bound is the entire point of the cap, and an
            //   operator sizing heap by the cap must be able to rely on it.
            // Given: a 16-byte buffer against an 8-byte capture
            val capture = BoundedBodyCapture(8)
            val source = DefaultDataBufferFactory.sharedInstance.wrap("0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8))
            val request =
                MockServerHttpRequest.post("/api/things").body(Flux.just(source))

            // When: the decorated body is consumed
            val forwarded = CapturingRequestDecorator(request, capture).body.blockFirst()

            // Then: identical instance downstream, untouched read position, bounded capture, full count
            assertThat(forwarded).isSameAs(source)
            assertThat(forwarded!!.readableByteCount()).isEqualTo(16)
            assertThat(capture.totalBytes).isEqualTo(16L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("01234567... [truncated, 16 bytes total]")
        }

        @Test
        fun `should copy nothing at all in count-only mode while still counting every byte`() {
            // What is tested: the count-only path of the tee (limit 0, the body-size-metrics mode) -
            //   its documentation says nothing is buffered, and after the finding-6 fix nothing is
            //   COPIED either.
            // Success criteria: the buffer passes through as the same instance, the count is exact, and
            //   the capture holds zero buffered bytes (loggedValue carries only the truncation note).
            // Why it matters: measure-only deployments run this path for EVERY body byte of every
            //   exchange; an unbounded hidden copy there is the finding's worst-case configuration.
            // Given: a count-only capture
            val capture = BoundedBodyCapture(0)
            val source = DefaultDataBufferFactory.sharedInstance.wrap("0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8))
            val request =
                MockServerHttpRequest.post("/api/things").body(Flux.just(source))

            // When: the decorated body is consumed
            val forwarded = CapturingRequestDecorator(request, capture).body.blockFirst()

            // Then: pass-through, exact count, nothing buffered
            assertThat(forwarded).isSameAs(source)
            assertThat(capture.totalBytes).isEqualTo(16L)
            assertThat(capture.remainingCapacity()).isEqualTo(0)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("... [truncated, 16 bytes total]")
        }

        @Test
        fun `should tee only the first subscription of a replayable request body`() {
            // What is tested: the subscription-aware tee - a replay-capable request (Flux.just replays)
            //   subscribed twice, as a caching
            //   filter or a second reader may do.
            // Success criteria: both subscriptions receive the body, but the capture holds the logical
            //   body ONCE - text not duplicated, size not doubled.
            // Why it matters: a doubled capture logs "hellohello" and inflates endpoint.request.body.size.
            // Given: a replayable body
            val capture = BoundedBodyCapture(32)
            val request =
                MockServerHttpRequest.post("/api/things").body(
                    Flux.defer { Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes("hello"))) },
                )
            val decorated = CapturingRequestDecorator(request, capture)

            // When: subscribed twice
            val first = decorated.body.map { it.toString(StandardCharsets.UTF_8) }.blockLast()
            val second = decorated.body.map { it.toString(StandardCharsets.UTF_8) }.blockLast()

            // Then: both readers saw the body, the capture saw it once
            assertThat(first).isEqualTo("hello")
            assertThat(second).isEqualTo("hello")
            assertThat(capture.totalBytes).isEqualTo(5L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        }

        @Test
        fun `should truncate the logged request body at the capture limit and say so`() {
            // What is tested: a 16-byte body against an 8-byte cap.
            // Success criteria: the captured prefix plus the explicit truncation note with the
            //   total.
            // Why it matters: the cap bounds memory, not the exchange; the note is what tells an
            //   operator the body was longer than what they see.
            // Given: a 16-byte body against the 8-byte cap
            val chain = WebFilterChain { ex -> ex.request.body.then(Mono.empty()) }

            // When: the filter handles the exchange
            filter.filter(postExchange("0123456789ABCDEF"), chain).block()

            // Then: captured prefix plus the explicit truncation note
            assertThat(keyValues()).containsEntry("endpoint_request_body", "01234567... [truncated, 16 bytes total]")
        }
    }

    @Nested
    inner class `Response body tee` {
        @Test
        fun `should log the response body and deliver identical content downstream`() {
            // What is tested: the response-body tee on a chain writing through the decorated
            //   response.
            // Success criteria: the body is logged AND arrives at the client unchanged.
            // Why it matters: a tee, not a cache: a decorator that consumed or altered the buffers
            //   would break the response it observes.
            // Given: a chain writing through the decorated response
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ex.response.statusCode = HttpStatus.OK
                    ex.response.headers.contentType = MediaType.TEXT_PLAIN
                    ex.response.writeWith(
                        Mono.just(DefaultDataBufferFactory.sharedInstance.wrap("resp!".toByteArray(StandardCharsets.UTF_8))),
                    )
                }

            // When: the filter handles the exchange
            filter.filter(exchange, chain).block()

            // Then: logged AND fully delivered (tee, not cache)
            assertThat(keyValues()).containsEntry("endpoint_response_body", "resp!")
            assertThat(exchange.response.bodyAsString.block()).isEqualTo("resp!")
        }
    }

    @Nested
    inner class `Publisher specialization` {
        @Test
        fun `should hand a Mono body to the delegate as a Mono so Spring keeps its optimized path`() {
            // What is tested: publisher specialization through the tee - AbstractServerHttpResponse.writeWith
            //   has an optimized
            //   Mono branch that a Flux-wrapped body would defeat whenever capture is enabled.
            // Success criteria: a Mono body arrives at the delegate as a Mono, a Flux as a Flux; the
            //   capture sees the bytes in both cases.
            // Why it matters: single-buffer responses are the common case; capture must not cost them
            //   the framework's fast path.
            // Given: a recording delegate under the tee
            val capture = BoundedBodyCapture(32)
            val delegate = RecordingResponse()
            val decorated = CapturingResponseDecorator(delegate, capture)

            // When: a Mono body is written
            decorated.writeWith(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes("mono")))).block()

            // Then: still a Mono downstream, captured
            assertThat(delegate.received).isInstanceOf(Mono::class.java)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("mono")
        }

        @Test
        fun `should hand a Flux body to the delegate as a Flux`() {
            // What is tested: the publisher specialization of the response decorator - a two-buffer
            //   Flux written through it.
            // Success criteria: the delegate receives a Flux (not a Mono) and the capture holds
            //   both buffers' bytes.
            // Why it matters: servers take a different write path for a single-buffer Mono; a
            //   decorator that changed the publisher type would change the server's behaviour, not
            //   only the log.
            // Given
            val capture = BoundedBodyCapture(32)
            val delegate = RecordingResponse()
            val decorated = CapturingResponseDecorator(delegate, capture)

            // When: a two-buffer Flux is written
            decorated
                .writeWith(
                    Flux.just(
                        DefaultDataBufferFactory.sharedInstance.wrap(bytes("fl")),
                        DefaultDataBufferFactory.sharedInstance.wrap(bytes("ux")),
                    ),
                ).block()

            // Then
            assertThat(delegate.received).isInstanceOf(Flux::class.java).isNotInstanceOf(Mono::class.java)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("flux")
        }
    }

    @Nested
    inner class `Zero-copy boundary` {
        @Test
        fun `should not advertise zero-copy so file responses fall back through the tee`() {
            // What is tested: the mechanism the capture correctness rests on -
            //   writers check the RESPONSE instance for ZeroCopyHttpOutputMessage; because the decorator
            //   does not implement it, file-serving handlers fall back to the buffered path and their
            //   bytes flow THROUGH the tee.
            // Success criteria: the decorator is not assignable to the zero-copy interface.
            // Why it matters: implementing the interface here would silently re-open a capture bypass -
            //   this pin forces that change to revisit the trade-off consciously.
            // Given/When/Then: the decorator's type alone decides
            val decorated =
                CapturingResponseDecorator(
                    MockServerHttpResponse(),
                    BoundedBodyCapture(8),
                )
            assertThat(decorated).isNotInstanceOf(ZeroCopyHttpOutputMessage::class.java)
        }
    }

    @Nested
    inner class `Header selection and masking` {
        @Test
        fun `should log selected headers multi-value and mask the configured ones stably`() {
            // What is tested: header selection and masking on the reactive request - a repeated
            //   Accept header and a secret header masked by lower-case name.
            // Success criteria: multi-value joined with a comma, the secret fingerprinted and never
            //   verbatim, matching case-insensitive.
            // Why it matters: a single-value getFirst would truncate repeated headers, and a case-
            //   sensitive mask list would leak a secret under a differently cased name.
            // Given: a repeated Accept header and a secret header, selection and masking as configured
            val maskingFilter =
                RequestLoggingWebFilter(
                    properties.copy(
                        requestHeaders =
                            HeaderLogProperties(
                                includes = listOf("Accept", "X-Api-Key"),
                                masked = listOf("x-api-key"),
                            ),
                    ),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val exchange =
                MockServerWebExchange.from(
                    MockServerHttpRequest
                        .get("/api/things")
                        .header("Accept", "text/plain", "text/html")
                        .header("X-Api-Key", "super-secret-token"),
                )

            // When: the filter handles the exchange
            maskingFilter
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: multi-value joined, secret fingerprinted (never verbatim), case-insensitive matching
            val headers = keyValues()["endpoint_request_headers"].toString()
            assertThat(headers).contains("Accept:\"text/plain, text/html\"")
            assertThat(headers).doesNotContain("super-secret-token")
            assertThat(headers).contains("X-Api-Key:\"${HeaderValueMasker.DEFAULT.mask("super-secret-token")}\"")
        }

        @Test
        fun `should mask every selected header by default so a wildcard include never leaks plaintext`() {
            // What is tested: ADR-0005 at the filter - `includes: ["*"]` with nothing said about masking.
            // Success criteria: every logged request header is a fingerprint; the secret appears nowhere.
            // Why it matters: with masking as a second, empty list the same configuration logged
            //   everything in plaintext - the unsafe combination was the convenient one.
            // Given
            val everything =
                RequestLoggingWebFilter(
                    properties.copy(requestHeaders = HeaderLogProperties(includes = listOf("*"))),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things").header("Authorization", "Bearer secret-token"))

            // When
            everything
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then
            val headers = keyValues()["endpoint_request_headers"].toString()
            assertThat(headers).contains("Authorization:\"${HeaderValueMasker.DEFAULT.mask("Bearer secret-token")}\"")
            assertThat(headers).doesNotContain("secret-token")
        }

        @Test
        fun `should render masked values through a host-provided masker`() {
            // What is tested: the masker is an injected collaborator - a filter built with a host bean
            //   masks request AND response headers with it.
            // Success criteria: both selected, masked headers carry the host masker's output, never the
            //   plaintext and never the built-in fingerprint.
            // Why it matters: a compliance regime forbidding unkeyed hashes must be satisfiable without
            //   forking the module.
            // Given: a keyed stand-in masker on both sections
            val keyed = HeaderValueMasker { "hmac:${it.length}" }
            val keyedFilter =
                RequestLoggingWebFilter(
                    properties.copy(
                        requestHeaders = HeaderLogProperties(includes = listOf("X-Api-Key"), masked = listOf("X-Api-Key")),
                        responseHeaders = HeaderLogProperties(includes = listOf("Set-Cookie"), masked = listOf("Set-Cookie")),
                    ),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                    keyed,
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things").header("X-Api-Key", "super-secret-token"))

            // When: the chain sets a masked response header
            keyedFilter
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        ex.response.headers.set("Set-Cookie", "session=1")
                        Mono.empty()
                    },
                ).block()

            // Then
            assertThat(keyValues()["endpoint_request_headers"].toString()).isEqualTo("[X-Api-Key:\"hmac:18\"]")
            assertThat(keyValues()["endpoint_response_headers"].toString()).isEqualTo("[Set-Cookie:\"hmac:9\"]")
        }

        @Test
        fun `should log the selected response header set by the chain`() {
            // What is tested: the response-header selection read at emission from a header the
            //   chain set.
            // Success criteria: endpoint_response_headers carries the Content-Type.
            // Why it matters: response headers exist only after the chain ran; reading them earlier
            //   would log nothing.
            // Given: a chain that sets the selected response header
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ex.response.statusCode = HttpStatus.OK
                    ex.response.headers.contentType = MediaType.APPLICATION_JSON
                    Mono.empty()
                }

            // When / Then: the response header rides its field
            filter.filter(exchange, chain).block()
            assertThat(keyValues()["endpoint_response_headers"].toString()).contains("Content-Type:\"application/json\"")
        }
    }

    @Nested
    inner class `Outcome-gated bodies` {
        private val onFailure = properties.copy(logRequestBody = BodyLogMode.ON_FAILURE, logResponseBody = BodyLogMode.ON_FAILURE)
        private val gated = RequestLoggingWebFilter(onFailure, { ticker.get() }, { "generated-42" }, SimpleMeterRegistry())

        private fun posted(text: String): MockServerWebExchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/things").contentType(MediaType.TEXT_PLAIN).body(text))

        /** A chain that consumes the request body, sets [status] and writes "answer". */
        private fun answering(status: HttpStatus) =
            WebFilterChain { ex ->
                ex.response.statusCode = status
                ex.request.body.then(ex.response.writeWith(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes("answer")))))
            }

        @Test
        fun `should withhold both bodies from a successful exchange in on-failure mode`() {
            // What is tested: the volume switch - on-failure tees the request body (the outcome is unknown
            //   while it flows) and discards both captures at emission when the outcome is success.
            // Success criteria: the client receives the response body; the line carries neither body.
            // Why it matters: this is the mode that keeps body logging affordable outside a debug session.
            // Given/When
            val exchange = posted("sent")
            gated.filter(exchange, answering(HttpStatus.OK)).block()

            // Then
            assertThat(exchange.response.bodyAsString.block()).isEqualTo("answer")
            assertThat(keyValues())
                .containsEntry("endpoint_outcome", "success")
                .doesNotContainKeys("endpoint_request_body", "endpoint_response_body")
        }

        @Test
        fun `should log both bodies of a 5xx response in on-failure mode`() {
            // What is tested: on-failure body logging for a failure outcome WITHOUT an error signal
            //   - a 502 the handler answered.
            // Success criteria: outcome failure, request and response body on the line.
            // Why it matters: a 5xx is a failure to the operator whether or not an exception was
            //   involved; the body gate must follow the outcome, not the signal.
            // Given/When: a failure outcome without an error signal
            gated.filter(posted("sent"), answering(HttpStatus.BAD_GATEWAY)).block()

            // Then
            assertThat(keyValues())
                .containsEntry("endpoint_outcome", "failure")
                .containsEntry("endpoint_request_body", "sent")
                .containsEntry("endpoint_response_body", "answer")
        }

        @Test
        fun `should log the teed request body of an exchange whose handler errored in on-failure mode`() {
            // What is tested: on-failure body logging when the handler consumed the body and then
            //   errored, with the emission deferred to the commit.
            // Success criteria: the exception propagates; the event carries outcome failure and the
            //   request body, and no response body.
            // Why it matters: the request body flowed before the outcome was known; on-failure must
            //   keep it for exactly this line and drop it for a clean one.
            // Given: a handler that consumes the body and then errors
            val failing = WebFilterChain { ex -> ex.request.body.then(Mono.error<Void>(IllegalStateException("handler broke"))) }
            val exchange = posted("sent")

            // When: the error passes the filter, then the upstream error handling renders and commits the
            //   response - the deferred emission point of an error signal (see the core filter tests)
            val thrown = catchThrowable { gated.filter(exchange, failing).block() }
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            exchange.response.setComplete().block()

            // Then: the request body that was captured before the outcome was known is on the line
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(keyValues())
                .containsEntry("endpoint_outcome", "failure")
                .containsEntry("endpoint_request_body", "sent")
                .doesNotContainKey("endpoint_response_body")
        }

        @Test
        fun `should log both bodies of a 4xx response although its outcome stays success`() {
            // What is tested: the gate is wider than the outcome vocabulary by one status class - a 4xx keeps
            //   its success outcome (the application answered; the client's request was wrong) but is exactly the case a body explains.
            // Success criteria: outcome success, and BOTH bodies on the line.
            // Why it matters: a validation error\'s response body is the most wanted body of all; hiding it
            //   behind the outcome vocabulary would make on-failure useless for client errors.
            // Given/When: a 404 with a body
            gated.filter(posted("sent"), answering(HttpStatus.NOT_FOUND)).block()

            // Then
            assertThat(keyValues())
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_request_body", "sent")
                .containsEntry("endpoint_response_body", "answer")
        }

        @Test
        fun `should still measure the size of a body it withholds`() {
            // What is tested: on-failure plus request-body measuring on a successful exchange.
            // Success criteria: the size sample is recorded, the body field is absent.
            // Why it matters: the meter and the field are independent opt-ins; a body the mode
            //   withholds from the log is still bytes that flowed.
            // Given: on-failure plus measuring, on an own registry
            val registry = SimpleMeterRegistry()
            val measuring = RequestLoggingWebFilter(onFailure.copy(measureRequestBodySize = true), { ticker.get() }, { "generated-42" }, registry)

            // When: a successful exchange with a 4-byte request body
            measuring.filter(posted("four"), answering(HttpStatus.OK)).block()

            // Then: the sample is recorded, the field is not
            assertThat(registry.get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summary().totalAmount()).isEqualTo(4.0)
            assertThat(keyValues()).doesNotContainKey("endpoint_request_body")
        }
    }
}

/** Records the publisher type the delegate receives - a `Mono` must stay a `Mono`. */
private class RecordingResponse : ServerHttpResponseDecorator(MockServerHttpResponse()) {
    var received: Publisher<out DataBuffer>? = null

    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
        received = body
        return super.writeWith(body)
    }
}
