package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.LoggedExchange
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
    override val method: String,
    override val path: String,
    override val query: String?,
    /**
     * The exchange identity (`endpoint_request_id`, ADR-0002): the `traceparent` trace id when the
     * request carried a conformant one, otherwise the accepted or generated correlation id.
     */
    override val requestId: String,
    override val requestHeaders: List<Pair<String, String>>,
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
    override val traceId: String? = null,
    override val parentSpanId: String? = null,
) : LoggedExchange {
    /** The exactly-once guard of the emission; whoever wins the CAS emits - the emitter's own inner backstop. */
    val logged = AtomicBoolean(false)

    /**
     * The completion LIFECYCLE as ONE atomic value, so the legal transitions are enumerable
     * ([CompletionState]) instead of living in the interplay of separate flags - the servlet mirror of
     * the reactive twin's `ExchangeState` (architecture review of 2026-09-05, finding 4). `OPEN` from
     * wiring; `ASYNC_ARMED` once the [AsyncOutcomeMarker] is registered and an `onComplete` is therefore
     * guaranteed; `DESTROYED_DURING_ASYNC` when a per-dispatch container (Jetty) destroyed the request
     * while the armed cycle was still running - the listener skips that firing; `ASYNC_COMPLETED` when
     * the cycle ended before any destruction; `COMPLETED` exactly once, by whichever of the destruction
     * listener and the marker's `onComplete` backstop wins the transition. Every transition is a CAS, so
     * a destruction racing an `onComplete` on another thread resolves without a re-check protocol.
     */
    private val completion = AtomicReference(CompletionState.OPEN)

    /** The current lifecycle state - exposed for tests. */
    val completionState: CompletionState
        get() = completion.get()

    /**
     * The [AsyncOutcomeMarker] was registered: from now on a destruction may DEFER to its `onComplete`.
     * A no-op when the cycle already ended between registration and this call - the destruction then
     * completes right away, as it does for an exchange whose marker could not be armed (fail-open).
     */
    fun markAsyncArmed() {
        completion.compareAndSet(CompletionState.OPEN, CompletionState.ASYNC_ARMED)
    }

    /**
     * The container's `onComplete`. Returns true when THIS call must complete the exchange: a
     * destruction already came and went while the cycle was running (per-dispatch container, raw
     * `complete()` without a further dispatch - found by the Jetty capture-boundary integration test,
     * 2026-08-30). Otherwise records the ended cycle and leaves completion to the destruction still to
     * come, exactly as Tomcat's single late destruction expects.
     */
    fun onAsyncCompleted(): Boolean {
        while (true) {
            when (val state = completion.get()) {
                CompletionState.OPEN, CompletionState.ASYNC_ARMED -> {
                    if (completion.compareAndSet(state, CompletionState.ASYNC_COMPLETED)) return false
                }

                CompletionState.DESTROYED_DURING_ASYNC -> {
                    return true
                }

                CompletionState.ASYNC_COMPLETED, CompletionState.COMPLETED -> {
                    return false
                }
            }
        }
    }

    /**
     * A `requestDestroyed`. Returns true when THIS destruction ends the exchange; false when it fired
     * while an armed async cycle is still running (skipped: either a later destruction or the marker's
     * backstop completes - never `request.isAsyncStarted()`, which Tomcat's facade rejects inside
     * `requestDestroyed` after an errored cycle) or when an earlier one was already skipped.
     */
    fun onDestroyed(): Boolean {
        while (true) {
            when (val state = completion.get()) {
                CompletionState.ASYNC_ARMED -> {
                    if (completion.compareAndSet(state, CompletionState.DESTROYED_DURING_ASYNC)) return false
                }

                CompletionState.DESTROYED_DURING_ASYNC -> {
                    return false
                }

                CompletionState.OPEN, CompletionState.ASYNC_COMPLETED, CompletionState.COMPLETED -> {
                    return true
                }
            }
        }
    }

    /** The exactly-once completion transition: true for the one caller that wins it. */
    fun tryComplete(): Boolean = completion.getAndSet(CompletionState.COMPLETED) != CompletionState.COMPLETED

    @Volatile
    var failure: Exception? = null

    /** The best-matching handler pattern Spring MVC recorded, read after the chain; null without MVC. */
    @Volatile
    var pathTemplate: String? = null

    /**
     * True once the chain returned with async processing started; set in the filter's `finally`. A
     * FACT about the exchange (the `endpoint_async` field), not a lifecycle state - the lifecycle is
     * [completionState].
     */
    @Volatile
    var asyncStarted: Boolean = false

    /**
     * Which async callback ENDED the exchange - one value, set through [markTimedOut]/[markErrored],
     * carrying its own precedence (see [AsyncDisposition]). The disposition is the callback that occurred,
     * never inferred from throwable presence: the servlet API permits an `AsyncEvent` WITHOUT a throwable
     * on `onError`, and `onTimeout` MAY carry one.
     */
    val asyncDisposition: AsyncDisposition
        get() = disposition.get()

    // The precedence is an ATOMIC transition, not a volatile check-then-set: the container does not
    // promise that onTimeout and onError run on one thread, and an onError reading NONE, losing the
    // race to onTimeout and then writing ERRORED would erase the absorbing timeout.
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

/** See [Exchange.completionState]. */
internal enum class CompletionState { OPEN, ASYNC_ARMED, DESTROYED_DURING_ASYNC, ASYNC_COMPLETED, COMPLETED }

/**
 * The async disposition of an exchange, as a single value with its precedence built in: [TIMED_OUT]
 * always wins - the container's timeout is what ENDED the exchange, a subsequent `onError` (the
 * container aborting the timed-out cycle) does not reclassify it; [ERRORED] is recorded only from
 * [NONE]. Replaces two independent boolean flags whose precedence lived in the emitter's `when`.
 */
internal enum class AsyncDisposition { NONE, TIMED_OUT, ERRORED }

/**
 * MARKS the async outcome on the exchange - and, in ONE case, completes it: emission normally happens
 * at request destruction (Tomcat orders that after these events; the volatile fields make the marks
 * visible there), but a container that destroys per DISPATCH has already fired - and been skipped -
 * during a raw async cycle that ends via `complete()` without a further dispatch. [onComplete] is the
 * backstop for exactly that case: it invokes [onSettled] (the filter's exactly-once completion) only
 * when [Exchange.onAsyncCompleted] says no destruction is coming any more. The Servlet spec
 * guarantees onComplete fires at the end of EVERY async cycle - after onError/onTimeout handling and
 * after the error dispatch - so the state it completes with is final. On a re-entrant `startAsync` the
 * container does NOT carry listeners over, so [onStartAsync] re-registers this one.
 */
internal class AsyncOutcomeMarker(
    private val exchange: Exchange,
    private val onSettled: (Exchange) -> Unit,
) : AsyncListener {
    override fun onComplete(event: AsyncEvent) {
        if (exchange.onAsyncCompleted()) {
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
