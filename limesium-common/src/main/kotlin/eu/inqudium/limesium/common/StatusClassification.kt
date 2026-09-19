package eu.inqudium.limesium.common

import org.slf4j.event.Level

/**
 * The status half of both twins' level/outcome resolution (ADR-0007), for an exchange that neither
 * threw nor took the stack's own disposition: a 5xx is WARN `failure` (the application answered that it
 * is broken; it already handled it), a 4xx is INFO `rejected` (the application answered that the
 * caller's request was refused - the caller is the foreign party, so nothing here is the operator's to
 * act on; the status is on the line for the split), and everything else, a missing status included, is
 * INFO `success`. One function for both stacks (ADR-0003), so a 4xx means the same on the servlet line
 * and the reactive line; the outbound sibling legatium classifies the same way and escalates four
 * rejections to WARN there, where the caller is the application itself.
 */
internal object StatusClassification {
    /** The level and the [EndpointLoggingMetrics] outcome literal an answered exchange resolves to by its status. */
    fun levelAndOutcome(status: Int?): Pair<Level, String> =
        when {
            status == null -> Level.INFO to EndpointLoggingMetrics.OUTCOME_SUCCESS
            status >= 500 -> Level.WARN to EndpointLoggingMetrics.OUTCOME_FAILURE
            status >= 400 -> Level.INFO to EndpointLoggingMetrics.OUTCOME_REJECTED
            else -> Level.INFO to EndpointLoggingMetrics.OUTCOME_SUCCESS
        }
}
