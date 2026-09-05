package eu.inqudium.limesium.common

/**
 * The acceptance rule for a CALLER-supplied correlation id (the value of the configured correlation
 * header on a traceless exchange, ADR-0002). Shared by both endpoint-logging twins (ADR-0003 amendment).
 *
 * An accepted value is echoed on the response, written into the message, the MDC, the Reactor context
 * or the coroutine `MDCContext`, and thereby into every log line of the exchange. Left unbounded, its
 * length and character set would be dictated by the peer up to the server's header limit (typically
 * 8 KiB): log volume and MDC size per request would be foreign-controlled, and dashboards keying on the
 * generator's 21-character format would see arbitrary shapes. The rule therefore accepts exactly what a
 * correlation id is - a short opaque token - and treats everything else like a MISSING header: a fresh id
 * is generated and echoed, counted as `generated` on `endpoint.logging.correlation.id`.
 *
 * Accepted: 1 to [MAX_LENGTH] characters, every one a visible ASCII character (`0x21`-`0x7E`). That
 * covers UUIDs, base-36/62 ids, ULIDs, hex and every token with punctuation; it rejects whitespace,
 * control and non-ASCII characters. Servers already reject CR/LF in header values at parse time, so the
 * rule is about volume and shape, not about log injection (which the raw request target guards address).
 */
internal object CorrelationHeaderValue {
    /** The longest caller-supplied id that is adopted; longer values count as absent. */
    const val MAX_LENGTH = 128

    /** [value] when it is an acceptable correlation id, `null` (= treat as absent) otherwise. */
    fun accept(value: String?): String? =
        value?.takeIf { candidate ->
            candidate.length in 1..MAX_LENGTH && candidate.all { it in '!'..'~' }
        }
}
