package eu.inqudium.limesium.common

import org.slf4j.MDC

/**
 * MDC keys the twins maintain while a request is being handled. The values carry the module's
 * `endpoint_` prefix, so an encoder that emits MDC entries as fields lands them in the same namespace
 * as the `EndpointLogField` family each twin owns. [ROUTE] carries the request path: the scope opens
 * BEFORE the chain, when the handler pattern is not yet known.
 */
object MdcKeys {
    const val REQUEST_ID = "endpoint_request_id"
    const val REQUEST_METHOD = "endpoint_method"
    const val ROUTE = "endpoint_route"
}

/**
 * Trace context keys of the exchange event, parsed from the incoming W3C `traceparent` header (ADR-0002:
 * both twins source the trace id from the header, never from a tracing bridge). [TRACE_ID] is Boot's
 * logging-correlation key: the header's trace id IS the trace the server span runs under, so the join
 * holds. The header's parent-id is the CALLER's span and is published as [PARENT_SPAN_ID] - never as
 * [BRIDGE_SPAN_ID], where it would read as the local span (finding 2 of an internal code analysis).
 * Absent header means not logged.
 */
internal object TraceMdcKeys {
    const val TRACE_ID = "traceId"
    const val PARENT_SPAN_ID = "parentSpanId"

    /**
     * The host bridge's local-span key. Never written by the twins; an emission scope that owns the
     * trace keys SUPPRESSES it ([MdcScope] with `ownsTraceKeys`), so a stale bridge id on a pooled
     * emission thread cannot join the event to a foreign span.
     */
    const val BRIDGE_SPAN_ID = "spanId"
}

/**
 * Puts the exchange identity - and, when parsed, the trace context - into the MDC and restores the
 * PREVIOUS values on close: threads are pooled, and an outer filter may own the same keys.
 * The servlet twin opens it around the chain (there without the trace overlay: a tracing bridge's own
 * scope may be active and is authoritative for the chain, so the trace keys are left alone) and both
 * twins open it around the emission; with [ownsTraceKeys] the scope is the ONLY authority on the trace
 * keys - a parsed id is installed, an unparsed one is REMOVED for the scope's lifetime, so a stale id
 * on the pooled emission thread cannot be attached to the event (finding 5 of an internal code
 * analysis). Either way every touched key is restored on close.
 */
internal class MdcScope(
    requestId: String,
    method: String,
    path: String,
    traceId: String? = null,
    parentSpanId: String? = null,
    ownsTraceKeys: Boolean = false,
) : AutoCloseable {
    private val applied: Map<String, String> =
        buildMap {
            put(MdcKeys.REQUEST_ID, requestId)
            put(MdcKeys.REQUEST_METHOD, method)
            put(MdcKeys.ROUTE, path)
            traceId?.let { put(TraceMdcKeys.TRACE_ID, it) }
            parentSpanId?.let { put(TraceMdcKeys.PARENT_SPAN_ID, it) }
        }

    /**
     * The trace keys the scope suppresses: owned but not parsed - plus the bridge's local-span key,
     * which the twins never write but must not let linger into the event (see
     * [TraceMdcKeys.BRIDGE_SPAN_ID]).
     */
    private val removed: Set<String> =
        if (ownsTraceKeys) {
            setOf(TraceMdcKeys.TRACE_ID, TraceMdcKeys.PARENT_SPAN_ID, TraceMdcKeys.BRIDGE_SPAN_ID) - applied.keys
        } else {
            emptySet()
        }

    private val previous: Map<String, String?> = (applied.keys + removed).associateWith { MDC.get(it) }

    init {
        try {
            applied.forEach { (key, value) -> MDC.put(key, value) }
            removed.forEach { MDC.remove(it) }
        } catch (e: Exception) {
            // Roll back a PARTIAL install before propagating: a broken MDC adapter failing mid-put must
            // not leave half an identity on a pooled thread (finding 8 of an internal code analysis).
            try {
                close()
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
            }
            throw e
        }
    }

    /**
     * Restores every key BEST-EFFORT: one failing adapter call must not leave the remaining module-owned
     * entries on a pooled thread. The first failure is rethrown after the loop, later ones attached as
     * suppressed; the partial-install rollback above attaches a restoration failure to the ORIGINAL
     * install exception instead of replacing it.
     */
    override fun close() {
        var failure: Exception? = null
        previous.forEach { (key, value) ->
            try {
                if (value == null) MDC.remove(key) else MDC.put(key, value)
            } catch (e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
