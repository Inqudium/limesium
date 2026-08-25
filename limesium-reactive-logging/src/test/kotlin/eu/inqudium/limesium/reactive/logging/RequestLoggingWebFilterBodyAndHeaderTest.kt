package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
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
            loggerName = "http-exchange-reactive-body-test",
            requestHeaders = HeaderLogProperties(includes = listOf("Accept"), masked = listOf("X-Api-Key")),
            responseHeaders = HeaderLogProperties(includes = listOf("Content-Type")),
            logRequestBody = true,
            logResponseBody = true,
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

    private fun keyValues(): Map<String, Any?> = appender.list.single().keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    @Nested
    inner class `Request body tee` {
        private fun postExchange(body: String): MockServerWebExchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/things").contentType(MediaType.TEXT_PLAIN).body(body),
            )

        @Test
        fun `should log the request body the application actually consumed`() {
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
            // What is tested: the tee's memory contract (assessment finding 6, 2026-08-22 analysis) -
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
            // What is tested: the subscription-aware tee (finding 7 of the 2026-08-22T20-06-45
            //   analysis) - a replay-capable request (Flux.just replays) subscribed twice, as a caching
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
            // What is tested: publisher specialization through the tee (finding 9 of the
            //   2026-08-22T20-06-45 analysis) - AbstractServerHttpResponse.writeWith has an optimized
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
            // What is tested: the mechanism the capture correctness rests on (finding 3 of CODE_ANALYSIS-2026-08-21.md) -
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
            assertThat(headers).contains("X-Api-Key:\"${HeaderLogProperties.mask("super-secret-token")}\"")
        }

        @Test
        fun `should log the selected response header set by the chain`() {
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
}

/** Records the publisher type the delegate receives - a `Mono` must stay a `Mono`. */
private class RecordingResponse : ServerHttpResponseDecorator(MockServerHttpResponse()) {
    var received: Publisher<out DataBuffer>? = null

    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
        received = body
        return super.writeWith(body)
    }
}
