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

    /**
     * The exactly-once guard of the COMPLETION bookkeeping (gauge close + emission trigger): the
     * destruction listener and the async onComplete backstop can both reach the end of an exchange
     * (see [destroyedDuringAsync]); whoever wins this CAS completes. [logged] stays the emitter's own
     * inner backstop.
     */
    val completed = AtomicBoolean(false)

    /**
     * True once a `requestDestroyed` was observed WHILE async processing was still running. Tomcat
     * fires destruction once, after async completion; Jetty fires it at the end of every DISPATCH, so
     * the initial dispatch of an async exchange destroys early - the listener skips that firing and
     * records it here. An async cycle that later ends WITHOUT another dispatch (`complete()` from a
     * raw worker) would then never see a destruction again; the [AsyncOutcomeMarker.onComplete]
     * backstop completes the exchange exactly when this flag says the destruction-based path has
     * already come and gone (found by the Jetty capture-boundary integration test, 2026-08-30).
     */
    @Volatile
    var destroyedDuringAsync: Boolean = false

    @Volatile
    var failure: Exception? = null

    /** The best-matching handler pattern Spring MVC recorded, read after the chain; null without MVC. */
    @Volatile
    var pathTemplate: String? = null

    /** True once the chain returned with async processing started; set in the filter's `finally`. */
    @Volatile
    var asyncStarted: Boolean = false

    /**
     * True once the [AsyncOutcomeMarker] was successfully registered: only then is an `onComplete`
     * guaranteed to reach this exchange, and only then may the destruction listener defer to it. A
     * failed registration (fail-open) degrades to completing at whatever destruction fires - possibly
     * with pre-completion state, never with a lost event.
     */
    @Volatile
    var asyncMarkerArmed: Boolean = false

    /**
     * True once the container signalled `onComplete` - the spec-guaranteed end of EVERY async cycle
     * (after onError/onTimeout handling and after the error dispatch). The destruction listener keys on
     * THIS, never on `request.isAsyncStarted()`: Tomcat's request facade throws when the async state is
     * queried inside `requestDestroyed` after an errored cycle.
     */
    @Volatile
    var asyncCompleted: Boolean = false

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
 * MARKS the async outcome on the exchange - and, in ONE case, completes it: emission normally happens
 * at request destruction (Tomcat orders that after these events; the volatile fields make the marks
 * visible there), but a container that destroys per DISPATCH has already fired - and been skipped -
 * during a raw async cycle that ends via `complete()` without a further dispatch. [onComplete] is the
 * backstop for exactly that case: it invokes [onSettled] (the filter's exactly-once completion) only
 * when [Exchange.destroyedDuringAsync] says no destruction is coming any more. The Servlet spec
 * guarantees onComplete fires at the end of EVERY async cycle - after onError/onTimeout handling and
 * after the error dispatch - so the state it completes with is final. On a re-entrant `startAsync` the
 * container does NOT carry listeners over, so [onStartAsync] re-registers this one.
 */
internal class AsyncOutcomeMarker(
    private val exchange: Exchange,
    private val onSettled: (Exchange) -> Unit,
) : AsyncListener {
    override fun onComplete(event: AsyncEvent) {
        exchange.asyncCompleted = true
        if (exchange.destroyedDuringAsync) {
            onSettled(exchange)
        }
    }

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
