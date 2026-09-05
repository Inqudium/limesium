package eu.inqudium.limesium.reactive.logging

import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reactive tee: every [DataBuffer] that flows is COUNTED in full, at most the capture's remaining
 * capacity is copied out of it (a non-advancing read - the read position stays untouched), and the
 * ORIGINAL buffer continues downstream unchanged - ownership, pooling and release semantics are exactly
 * those of an undecorated exchange (the reactive counterpart of the servlet module's tee streams: a
 * passive copy, never a pre-read or replay). Transient allocation is bounded by
 * [RequestLoggingProperties.maxBodyBytes], not by the buffer size, and count-only captures (limit 0)
 * copy nothing at all.
 */
private fun tee(
    capture: BoundedBodyCapture,
    buffer: DataBuffer,
): DataBuffer {
    val length = buffer.readableByteCount()
    val wanted = minOf(length, capture.remainingCapacity())
    if (wanted > 0) {
        val prefix = ByteArray(wanted)
        buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(prefix), 0, wanted)
        capture.capture(prefix, 0, wanted)
        capture.count(length - wanted)
    } else {
        capture.count(length)
    }
    return buffer
}

/**
 * Tees the request body into [capture] as the APPLICATION subscribes and reads it - an unconsumed body
 * flows nowhere and is logged as absent, exactly like the servlet twin's read-side tee. The tee is
 * SUBSCRIPTION-AWARE: only the FIRST subscription to the body feeds the capture; a later subscription
 * (legal for the owner of the request, and real with a replay-capable request or a caching filter)
 * passes through untouched - the logical request body is one body, and logging or counting it twice
 * would duplicate the logged text and inflate the size sample.
 *
 * The claiming subscription also records the READ STATE on the capture: the subscription marks
 * consumption as started, the publisher's completion signal marks it complete. A cancellation or an
 * error leaves the state at PARTIAL - both are observations of what the application did, the tee
 * neither requests nor drains anything itself.
 */
internal class CapturingRequestDecorator(
    delegate: ServerHttpRequest,
    private val capture: BoundedBodyCapture,
) : ServerHttpRequestDecorator(delegate) {
    private val teeClaimed = AtomicBoolean(false)

    override fun getBody(): Flux<DataBuffer> =
        Flux.defer {
            val source = super.getBody()
            if (teeClaimed.compareAndSet(false, true)) {
                capture.markStarted()
                source.map { tee(capture, it) }.doOnComplete { capture.markCompleted() }
            } else {
                source
            }
        }
}

/**
 * Tees the response body into [capture] as it is written. There is no reactive analog of the servlet
 * `reset()`/`resetBuffer()` concern: a buffer the publisher emits into `writeWith` is handed to the
 * write path and cannot be recalled, so what the tee sees is what crossed the response write path.
 * That is the observation boundary: client receipt is not observable at this layer - an I/O error or
 * cancellation can still discard in-flight bytes downstream.
 *
 * ZERO-COPY: this decorator deliberately does NOT implement `ZeroCopyHttpOutputMessage`. Writers check
 * the RESPONSE instance for that interface, so wrapping makes file-serving handlers fall back to the
 * buffered path - the bytes then flow THROUGH this tee and are captured correctly, at the price of
 * losing the zero-copy optimization while body capture/measuring is enabled (capture off means no
 * decoration and untouched zero-copy). Implementing the interface here would
 * silently re-open a capture bypass - the mechanism is pinned by test.
 *
 * BOUNDARY - outer error rendering: this decorator sees only what is written through the MUTATED
 * exchange the filter passes down the chain. An UNHANDLED error travels up to Spring's outer
 * `WebExceptionHandler`s (`ExceptionHandlingWebHandler` invokes them after the filtered delegate
 * failed), and Boot's error renderer writes the 500 body through the ORIGINAL response - those bytes
 * bypass this tee. The exchange event still carries the rendered status (the commit callback observes
 * the shared delegate), but `endpoint_response_body` and the response-size sample stay absent for
 * globally rendered error responses; locally handled controller/advice responses traverse the tee
 * normally. Documented as a capture boundary and pinned by `RequestLoggingWebFilterIntegrationTest`.
 */
internal class CapturingResponseDecorator(
    delegate: ServerHttpResponse,
    private val capture: BoundedBodyCapture,
) : ServerHttpResponseDecorator(delegate) {
    /**
     * Preserves the publisher SPECIALIZATION: Spring's `AbstractServerHttpResponse.writeWith` has an
     * optimized branch for a `Mono` body (the common single-buffer response) that bypasses the
     * `ChannelSendOperator` coordination it needs for a `Flux`; wrapping every body in a `Flux` would
     * defeat that branch whenever capture is enabled.
     */
    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
        when (body) {
            is Mono -> super.writeWith(body.map { tee(capture, it) })
            else -> super.writeWith(Flux.from(body).map { tee(capture, it) })
        }

    override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
        super.writeAndFlushWith(
            Flux.from(body).map { inner -> Flux.from(inner).map { tee(capture, it) } },
        )
}
