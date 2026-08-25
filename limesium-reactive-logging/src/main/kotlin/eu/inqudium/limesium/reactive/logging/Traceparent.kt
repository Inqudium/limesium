package eu.inqudium.limesium.reactive.logging

/**
 * Minimal W3C `traceparent` parsing (`version-traceid-parentid-flags`). On the INBOUND side the header carries the
 * caller's context: the trace id is shared with the server span this exchange runs under (that is what
 * makes the log-to-trace join work), the parent-id is the CALLER's span - it is published as
 * `parentSpanId`, never under the conventional local `spanId` key (see [TraceMdcKeys]).
 *
 * Validity follows the W3C Trace Context Recommendation in full (finding 5 of
 * an internal code analysis, completed by finding 2 of an internal code analysis):
 * both ids are lowercase hexadecimal of fixed length and neither may be all zeros; the version is two
 * lowercase-hex characters and `ff` is forbidden; the flags are two lowercase-hex characters. Version
 * `00` is exactly four fields; a higher version is parsed by the version-00 rules for its first four
 * fields and may carry additional fields, as the specification prescribes for forward compatibility.
 * Conformance is pinned by the `traceparent/conformance.txt` fixture the tests read.
 */
internal object Traceparent {
    const val HEADER = "traceparent"

    private val VERSION = Regex("[0-9a-f]{2}")
    private val TRACE_ID = Regex("[0-9a-f]{32}")
    private val SPAN_ID = Regex("[0-9a-f]{16}")
    private val FLAGS = Regex("[0-9a-f]{2}")
    private const val INVALID_VERSION = "ff"
    private const val CURRENT_VERSION = "00"

    /**
     * Extracts `(traceId, parentSpanId)` or null when the value is absent or not a conformant
     * `traceparent` (malformed structure, invalid version or flags, non-lowercase-hex or all-zero ids).
     */
    fun parse(value: String?): Pair<String, String>? {
        val parts = (value ?: return null).split('-')
        if (parts.size < 4) {
            return null
        }
        val version = parts[0]
        if (!VERSION.matches(version) || version == INVALID_VERSION) {
            return null
        }
        if (version == CURRENT_VERSION && parts.size != 4) {
            return null
        }
        val traceId = parts[1]
        val parentSpanId = parts[2]
        if (!TRACE_ID.matches(traceId) || !SPAN_ID.matches(parentSpanId) || !FLAGS.matches(parts[3])) {
            return null
        }
        if (traceId.all { it == '0' } || parentSpanId.all { it == '0' }) {
            return null
        }
        return traceId to parentSpanId
    }
}
