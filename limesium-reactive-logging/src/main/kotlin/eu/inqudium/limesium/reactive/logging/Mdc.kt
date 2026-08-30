package eu.inqudium.limesium.reactive.logging

import org.slf4j.MDC

/**
 * MDC keys the module maintains while a request is being handled. The values carry the module's
 * `endpoint_` prefix, so an encoder that emits MDC entries as fields lands them in the same namespace as
 * the [EndpointLogField] family. [ROUTE] carries the request path: the scope opens BEFORE the chain, when
 * the handler pattern is not yet known.
 */
object MdcKeys {
    const val REQUEST_ID = "endpoint_request_id"
    const val REQUEST_METHOD = "endpoint_method"
    const val ROUTE = "endpoint_route"
}

/**
 * Trace context keys of the exchange event, parsed from the incoming W3C `traceparent` header (the
 * event-loop thread carries no bridge MDC at filter time). [TRACE_ID] is Boot's logging-correlation key:
 * the header's trace id IS the trace the server span runs under, so the join holds. The header's
 * parent-id is the CALLER's span and is published as [PARENT_SPAN_ID] - never as `spanId`, where it would
 * read as the local span and, with a tracing bridge active, overwrite the real one inside the emission
 * scope (finding 2 of an internal code analysis). Absent header means not logged. This module
 * owns the literals - no cross-module dependency.
 */
internal object TraceMdcKeys {
    const val TRACE_ID = "traceId"
    const val PARENT_SPAN_ID = "parentSpanId"
}

/**
 * Puts the exchange identity - and, when captured, the trace context - into the MDC and restores the
 * PREVIOUS values on close: container threads are pooled, and an outer filter may own the same keys.
 * In the REACTIVE stack there is no chain-wide thread-local MDC (handlers hop event-loop threads);
 * the scope is opened by the emitter around the emission only, where the overlay makes the encoder
 * emit the exchange's identity and trace ids.
 */
internal class MdcScope(
    requestId: String,
    method: String,
    path: String,
    traceId: String? = null,
    parentSpanId: String? = null,
) : AutoCloseable {
    private val applied: Map<String, String> =
        buildMap {
            put(MdcKeys.REQUEST_ID, requestId)
            put(MdcKeys.REQUEST_METHOD, method)
            put(MdcKeys.ROUTE, path)
            traceId?.let { put(TraceMdcKeys.TRACE_ID, it) }
            parentSpanId?.let { put(TraceMdcKeys.PARENT_SPAN_ID, it) }
        }

    private val previous: Map<String, String?> = applied.keys.associateWith { MDC.get(it) }

    init {
        try {
            applied.forEach { (key, value) -> MDC.put(key, value) }
        } catch (e: Exception) {
            // Roll back a PARTIAL install before propagating: a broken MDC adapter failing mid-put must
            // not leave half an identity on a pooled thread (twin parity with the servlet module's
            // finding 8 of an internal code analysis).
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
