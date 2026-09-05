package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.LoggedExchange
import eu.inqudium.limesium.common.TraceMdcKeys
import org.springframework.http.server.reactive.ServerHttpResponse
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * All state one exchange accumulates between filter entry and log emission: the request-side coordinates
 * captured EAGERLY at wiring time (the emission runs from a terminal or commit callback on whatever
 * event-loop thread completes the exchange), the body captures, and the flags the reactive lifecycle
 * marks along the way. Mutable fields are `@Volatile`: signal callbacks and the emission can run on
 * different threads.
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
    val responseCapture: BoundedBodyCapture?,
    /** Charset of the request body for the logged value, resolved from the Content-Type at wiring time. */
    val requestCharset: Charset,
    val response: ServerHttpResponse,
    val startNanos: Long,
    /**
     * Trace context parsed from the incoming W3C `traceparent` header: the trace id is the server span's
     * trace id; the parent-id is the CALLER's span (see [TraceMdcKeys]). Null without the header.
     */
    override val traceId: String? = null,
    override val parentSpanId: String? = null,
) : LoggedExchange {
    /**
     * The lifecycle state - ONE atomic value instead of independent flags, so the legal transitions are
     * enumerable and live HERE, behind [awaitCommit] and [tryComplete] (the servlet twin's
     * `CompletionState` shape): `OPEN` from wiring; `AWAITING_COMMIT` when the chain erred on an
     * uncommitted response and the emission waits for the commit callback; `COMPLETED` exactly once, by
     * whichever of the terminal/commit callbacks wins the transition - gauge-close and emission ride that
     * single transition.
     */
    private val lifecycle = AtomicReference(ExchangeState.OPEN)

    /** The current lifecycle state. */
    val state: ExchangeState
        get() = lifecycle.get()

    /** The error path's deferral to the commit callback: `OPEN` -> `AWAITING_COMMIT`, a no-op from any other state. */
    fun awaitCommit() {
        lifecycle.compareAndSet(ExchangeState.OPEN, ExchangeState.AWAITING_COMMIT)
    }

    /** The exactly-once completion transition: true for the one caller that wins it. */
    fun tryComplete(): Boolean = lifecycle.getAndSet(ExchangeState.COMPLETED) != ExchangeState.COMPLETED

    @Volatile
    var failure: Throwable? = null

    /** True when the subscription was cancelled - client disconnect; the response may never commit. */
    @Volatile
    var cancelled: Boolean = false

    /** The best-matching handler pattern WebFlux recorded, read at the terminal signal; null without it. */
    @Volatile
    var pathTemplate: String? = null

    /**
     * The status at response COMMIT time - the final word for the error path, where the terminal error
     * signal passes this filter BEFORE the upstream exception handler renders the 500.
     */
    @Volatile
    var committedStatus: Int? = null

    /**
     * True once the response accepted the `beforeCommit` callback, which the error path registers at the
     * terminal signal. When the registration itself failed (fail-open - see
     * `ExchangeLifecycle.registerCommitCallback`), the error path must not defer to a commit callback
     * that will never run; it completes at the terminal signal instead.
     */
    @Volatile
    var commitCallbackArmed: Boolean = false
}

/**
 * See [Exchange.state]. An exchange in [AWAITING_COMMIT] whose commit never happens (connection died
 * during error rendering) stays open on the gauge - the module's liveness signal - rather than logging
 * a wrong status.
 */
internal enum class ExchangeState { OPEN, AWAITING_COMMIT, COMPLETED }
