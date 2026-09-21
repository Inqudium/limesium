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
 * What a failure of stage `wiring` COST the exchange - the one choice a wiring guard makes when it
 * reports. The level of its breadcrumb and whether the stack trace goes along follow from the cost, so
 * the guards of both twins share one form and cannot drift apart in it (they had, until 2026-09-21:
 * the level and the trace were free arguments of the shared report, chosen per site without a rule
 * behind the difference). The sentence stays the guard's own. Ported from the outbound sibling
 * legatium, where the rule was decided the same day; the two projects keep it identical.
 */
internal enum class WiringCost(
    /** The breadcrumb's level. */
    val level: Level,
    /** Whether the breadcrumb carries the stack trace beside the exception's `toString`. */
    val withStackTrace: Boolean,
) {
    /**
     * The request runs without a feature the guard was wiring - the logging altogether, the identity on
     * the serving or the async worker thread, the deferred error path: ERROR, with the stack trace,
     * because the operator has to find the cause to get the feature back and nothing else will show it.
     */
    LOST_FEATURE(Level.ERROR, true),

    /**
     * The line is out (or the worker task is done), but a scope's teardown failed and the pooled thread
     * may keep keys that are not its own: WARN - the exchange IS logged - with the stack trace, because
     * the stale keys outlive the exchange and join the thread's next lines to the wrong request.
     */
    DIRTY_TEARDOWN(Level.WARN, true),

    /**
     * The event follows, degraded - without a sample, the handler template, the async marker, the
     * breadcrumb or the handler's ambient MDC: WARN with the exception's `toString` only; the event
     * itself shows what is missing.
     */
    DEGRADED_EVENT(Level.WARN, false),
}

/**
 * The report of a `stage=wiring` guard, in ONE shape for every such guard of both twins: the fail-open
 * counter, then the breadcrumb on [log] - [message] with its [args] as SLF4J placeholders, the
 * exception's `toString` appended as the last placeholder, level and stack trace per [cost] - the whole
 * under [reportQuietly], so a broken diagnostics channel cannot escape either. The catch keeps its own
 * control flow - a rethrow, a produced value, work that must still happen - only the report is shared,
 * which is why this is not [failOpen]. The metrics owner's own once-per-meter warning keeps its shape:
 * it throttles, which no other wiring guard does; an interrupt keeps its DEBUG line beside the counter.
 */
internal fun reportWiringFailure(
    metrics: EndpointLoggingMetrics,
    log: Logger,
    cost: WiringCost,
    e: Exception,
    message: String,
    vararg args: Any?,
) = reportQuietly {
    metrics.wiringFailure()
    log
        .atLevel(cost.level)
        .setCauseIfPresent(e.takeIf { cost.withStackTrace })
        .log("$message: {}", *args, e.toString())
}
