package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.TraceMdcKeys
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * All state one exchange accumulates between filter entry and log emission at request destruction: the
 * request-side coordinates captured EAGERLY by the filter (for an async exchange the emission runs from a
 * container callback, and the servlet contract only guarantees the request object until then), the body
 * captures, and the flags the async lifecycle marks along the way. Fields written after construction are
 * `@Volatile`: the marking (async events) and the reading (emission) can happen on different container
 * threads.
 */
internal class Exchange(
    val method: String,
    val path: String,
    val query: String?,
    /**
     * The exchange identity (`endpoint_request_id`, ADR-0002): the `traceparent` trace id when the
     * request carried a conformant one, otherwise the accepted or generated correlation id.
     */
    val requestId: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestCapture: BoundedBodyCapture?,
    val requestWrapper: CapturingRequestWrapper?,
    val responseCapture: BoundedBodyCapture?,
    val responseWrapper: CapturingResponseWrapper?,
    val response: HttpServletResponse,
    val startNanos: Long,
    /**
     * Trace context parsed from the incoming W3C `traceparent` header: the trace id is the server span's
     * trace id; the parent-id is the CALLER's span (see [TraceMdcKeys]). Null without the header.
     * Carried here because the emission runs on a destruction callback thread of its own.
     */
    val traceId: String? = null,
    val parentSpanId: String? = null,
) {
    /** The exactly-once guard of the emission; whoever wins the CAS emits. */
    val logged = AtomicBoolean(false)

    @Volatile
    var failure: Exception? = null

    /** The best-matching handler pattern Spring MVC recorded, read after the chain; null without MVC. */
    @Volatile
    var pathTemplate: String? = null

    /** True once the chain returned with async processing started; set in the filter's `finally`. */
    @Volatile
    var asyncStarted: Boolean = false

    /**
     * Which async callback ENDED the exchange - one value, set through [markTimedOut]/[markErrored],
     * carrying its own precedence (see [AsyncDisposition]). The disposition is the callback that occurred,
     * never inferred from throwable presence: the servlet API permits an `AsyncEvent` WITHOUT a throwable
     * on `onError`, and `onTimeout` MAY carry one (finding 5 of an internal code analysis).
     */
    val asyncDisposition: AsyncDisposition
        get() = disposition.get()

    // The precedence is an ATOMIC transition, not a volatile check-then-set: the container does not
    // promise that onTimeout and onError run on one thread, and an onError reading NONE, losing the
    // race to onTimeout and then writing ERRORED would erase the absorbing timeout (finding 2 of
    // an internal code analysis).
    private val disposition = AtomicReference(AsyncDisposition.NONE)

    /** TIMED_OUT is absorbing: set unconditionally, whatever was recorded before or concurrently. */
    fun markTimedOut() {
        disposition.set(AsyncDisposition.TIMED_OUT)
    }

    /** ERRORED replaces NONE only - a timeout recorded before or concurrently is never reclassified. */
    fun markErrored() {
        disposition.compareAndSet(AsyncDisposition.NONE, AsyncDisposition.ERRORED)
    }

    /**
     * The throwable of an async `onError`/`onTimeout` event, when the container supplied one. Attached
     * to the event as its cause; which CALLBACK occurred is [asyncDisposition] and is never inferred
     * from this field.
     */
    @Volatile
    var asyncFailure: Throwable? = null
}

/**
 * The async disposition of an exchange, as a single value with its precedence built in: [TIMED_OUT]
 * always wins - the container's timeout is what ENDED the exchange, a subsequent `onError` (the
 * container aborting the timed-out cycle) does not reclassify it; [ERRORED] is recorded only from
 * [NONE]. Replaces two independent boolean flags whose precedence lived in the emitter's `when`
 * (finding 2 of an internal architecture review).
 */
internal enum class AsyncDisposition { NONE, TIMED_OUT, ERRORED }

/**
 * MARKS the async outcome on the exchange - it does not emit. Emission happens at request destruction,
 * which the container orders after these events; the volatile fields make the marks visible there. On a
 * re-entrant `startAsync` the container does NOT carry listeners over, so [onStartAsync] re-registers
 * this one.
 */
internal class AsyncOutcomeMarker(
    private val exchange: Exchange,
) : AsyncListener {
    override fun onComplete(event: AsyncEvent) = Unit

    override fun onTimeout(event: AsyncEvent) {
        exchange.markTimedOut()
        event.throwable?.let { exchange.asyncFailure = it }
    }

    override fun onError(event: AsyncEvent) {
        exchange.markErrored()
        event.throwable?.let { exchange.asyncFailure = it }
    }

    override fun onStartAsync(event: AsyncEvent) {
        event.asyncContext.addListener(this)
    }
}
