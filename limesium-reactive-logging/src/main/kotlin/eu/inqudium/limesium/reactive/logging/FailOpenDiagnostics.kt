package eu.inqudium.limesium.reactive.logging

/**
 * Runs the diagnostics of a fail-open catch handler - the fail-open counter increment and the internal
 * log line - so that a failure of the DIAGNOSTICS channel itself can never escape into the request.
 *
 * Every catch block in the module reports through a Micrometer counter and an SLF4J logger; both run
 * against host-provided components (a throwing `Counter` implementation, a global throwing
 * appender/TurboFilter that also covers the internal logger). Unguarded, such a throw would leave the
 * catch handler and fail request assembly or disturb the response commit - the one outcome the fail-open
 * contract forbids (finding 3 of CODE_ANALYSIS-2026-08-22T16-35-46.md). There is nothing left to report
 * to when the reporting channel is broken, so the secondary failure is deliberately dropped.
 */
internal inline fun reportQuietly(report: () -> Unit) {
    try {
        report()
    } catch (ignored: Exception) {
        // The diagnostics channel is itself broken; the original failure was already contained.
    }
}
