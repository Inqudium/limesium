package eu.inqudium.limesium.common

import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * Runs the diagnostics of a fail-open catch handler - the fail-open counter increment and the internal
 * log line - so that a failure of the DIAGNOSTICS channel itself can never escape into the request.
 *
 * Every catch block in the twins reports through a Micrometer counter and an SLF4J logger; both run
 * against host-provided components (a throwing `Counter` implementation, a global throwing
 * appender/TurboFilter that also covers the internal logger). Unguarded, such a throw would leave the
 * catch handler and fail request assembly or disturb the response commit - the one outcome the fail-open
 * contract forbids. There is nothing left to report
 * to when the reporting channel is broken, so the secondary failure is deliberately dropped.
 */
internal inline fun reportQuietly(report: () -> Unit) {
    try {
        report()
    } catch (_: Exception) {
        // The diagnostics channel is itself broken; the original failure was already contained.
    }
}

/**
 * The FULLY-CONFINING fail-open guard shape the emitters and callbacks share: [operation] runs; an
 * [InterruptedException] first restores the thread's interrupt flag (the JVM cleared it when it threw,
 * and on a request-serving or event-loop thread the interrupt must still reach its addressee), then -
 * like every other [Exception] - the failure goes to its handler, itself wrapped in [reportQuietly] so
 * a broken diagnostics channel cannot escape either. Nothing is rethrown and nothing runs after a
 * failure.
 *
 * Deliberately NOT used by guards with richer semantics - a rethrow of the original exception (the
 * filters' chain calls), a produced value (fail-open wiring), or work that must still happen after a
 * confined failure (`ExchangeLifecycle.onTerminal` completes the exchange) - those keep their explicit
 * try/catch, where the deviation is visible.
 */
internal inline fun failOpen(
    onInterrupted: (InterruptedException) -> Unit,
    onFailure: (Exception) -> Unit,
    operation: () -> Unit,
) {
    try {
        operation()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        reportQuietly { onInterrupted(e) }
    } catch (e: Exception) {
        reportQuietly { onFailure(e) }
    }
}

/**
 * The REPORTING half every fail-open catch shares (code-style audit of 2026-09-05, pattern S1): the
 * stage counter ([count], e.g. `metrics::wiringFailure`) and ONE line on the module's own [log] at
 * [level], both inside [reportQuietly]. The catch keeps its own control flow - a rethrow, a produced
 * value, work that must still happen - only the report is shared, which is why this is not [failOpen].
 * [cause] rides the event as its throwable (stack trace) when given; what the line shows inline (the
 * exchange coordinates, `e.toString()`) travels as [args].
 */
internal fun reportFailOpen(
    count: () -> Unit,
    log: Logger,
    level: Level,
    cause: Throwable?,
    message: String,
    vararg args: Any?,
) {
    reportQuietly {
        count()
        val line = log.atLevel(level)
        (if (cause == null) line else line.setCause(cause)).log(message, *args)
    }
}
