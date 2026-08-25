package eu.inqudium.limesium.servlet.logging

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
 * Trace/span ids the host's Micrometer tracing bridge writes to the MDC (Spring Boot's
 * logging-correlation convention `%X{traceId}` / `%X{spanId}`). The filter captures them on the
 * container thread at entry - the destruction callback the emission runs on carries no MDC of its own,
 * so without the capture the exchange event could not be joined with its trace. Absent (no bridge,
 * observation off) means not captured and not logged. Standalone by design: this module owns the
 * literals, no cross-module dependency - the same choice `ExchangeDiaryLogging` makes in web-client.
 */
internal object TraceMdcKeys {
    const val TRACE_ID = "traceId"
    const val SPAN_ID = "spanId"
}

/**
 * Puts the exchange identity - and, when captured, the trace context - into the MDC and restores the
 * PREVIOUS values on close: container threads are pooled, and an outer filter may own the same keys.
 * Opened by the filter around the chain (there without the trace overlay: the bridge's own scope is
 * still active and authoritative, so the trace keys are left alone) and by the emitter around the
 * emission with [ownsTraceKeys]: there the scope is the ONLY authority on the trace keys - a captured id
 * is installed, an uncaptured one is REMOVED for the scope's lifetime, so a stale id on the pooled
 * destruction thread cannot be attached to the event (finding 5 of CODE_ANALYSIS-2026-08-22T23-19-06.md).
 * Either way every touched key is restored on close.
 */
internal class MdcScope(
    correlationId: String,
    method: String,
    path: String,
    traceId: String? = null,
    spanId: String? = null,
    ownsTraceKeys: Boolean = false,
) : AutoCloseable {
    private val applied: Map<String, String> =
        buildMap {
            put(MdcKeys.REQUEST_ID, correlationId)
            put(MdcKeys.REQUEST_METHOD, method)
            put(MdcKeys.ROUTE, path)
            traceId?.let { put(TraceMdcKeys.TRACE_ID, it) }
            spanId?.let { put(TraceMdcKeys.SPAN_ID, it) }
        }

    /** The trace keys the scope suppresses: owned but not captured. */
    private val removed: Set<String> =
        if (ownsTraceKeys) {
            setOf(TraceMdcKeys.TRACE_ID, TraceMdcKeys.SPAN_ID) - applied.keys
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
            // not leave half an identity on a pooled thread (finding 8 of CODE_ANALYSIS-2026-08-22.md).
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
