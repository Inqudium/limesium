package eu.inqudium.limesium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory

/**
 * The one wiring report every `stage=wiring` guard of both twins goes through - level, stack trace and
 * counter per [WiringCost], pinned here once instead of at the callers. Ported from the outbound
 * sibling legatium, whose defect analysis of 2026-09-21 found the rule unpinned: the twins' tests read
 * levels and sentences, never the breadcrumb's cause.
 */
class FailOpenDiagnosticsTest {
    @JvmField
    @RegisterExtension
    internal val breadcrumbs = CapturedLogger(BREADCRUMB_LOGGER, Level.DEBUG)

    private val registry = SimpleMeterRegistry()
    private val metrics = EndpointLoggingMetrics.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_TIMEOUT)

    private fun wiringFailures(): Double =
        registry
            .get(EndpointLoggingMetrics.FAIL_OPEN_METER)
            .tags("stage", "wiring")
            .counter()
            .count()

    @ParameterizedTest
    @EnumSource(WiringCost::class)
    internal fun `should count stage wiring and log the breadcrumb at the level and with the stack trace the cost decides`(cost: WiringCost) {
        // What is tested: reportWiringFailure for every WiringCost - the counter, the level, the message
        //   with its placeholders and the exception's toString appended, and whether the event carries
        //   the exception as its cause (the stack trace), all decided by the cost alone.
        // Success criteria: stage=wiring at 1; exactly one event on the breadcrumb logger at
        //   cost.level, formatted "<sentence>: <exception>"; the event's cause is the exception iff
        //   cost.withStackTrace.
        // Why it matters: the guards in both twins name a cost and nothing else; their tests read levels
        //   and sentences but not the stack trace, so a regression of the rule - a lost trace on a failed
        //   feature, a trace on every degraded event - would pass them.
        // Given
        val failure = IllegalStateException("adapter refused")

        // When
        reportWiringFailure(metrics, breadcrumbs.logger, cost, failure, "Feature lost for {} {}", "GET", "/things")

        // Then
        assertThat(wiringFailures()).isEqualTo(1.0)
        val event = breadcrumbs.events.single()
        assertThat(event.level).isEqualTo(Level.toLevel(cost.level.name))
        assertThat(event.formattedMessage).isEqualTo("Feature lost for GET /things: java.lang.IllegalStateException: adapter refused")
        if (cost.withStackTrace) {
            assertThat(event.throwableProxy?.message).isEqualTo("adapter refused")
        } else {
            assertThat(event.throwableProxy).isNull()
        }
    }

    @Test
    fun `should pin the level and stack trace of each cost`() {
        // What is tested: the rule itself - which cost errors, which warns, which carries the trace.
        // Success criteria: LOST_FEATURE is ERROR with the trace, DIRTY_TEARDOWN WARN with the trace,
        //   DEGRADED_EVENT WARN without it, and there is no fourth cost.
        // Why it matters: the enum IS the documented rule (guide, "Fail-open contract"), identical in
        //   legatium; a changed flag would silently change every breadcrumb of that cost in both twins.
        // Given/When/Then
        assertThat(WiringCost.entries).containsExactly(WiringCost.LOST_FEATURE, WiringCost.DIRTY_TEARDOWN, WiringCost.DEGRADED_EVENT)
        assertThat(WiringCost.LOST_FEATURE.level).isEqualTo(org.slf4j.event.Level.ERROR)
        assertThat(WiringCost.LOST_FEATURE.withStackTrace).isTrue()
        assertThat(WiringCost.DIRTY_TEARDOWN.level).isEqualTo(org.slf4j.event.Level.WARN)
        assertThat(WiringCost.DIRTY_TEARDOWN.withStackTrace).isTrue()
        assertThat(WiringCost.DEGRADED_EVENT.level).isEqualTo(org.slf4j.event.Level.WARN)
        assertThat(WiringCost.DEGRADED_EVENT.withStackTrace).isFalse()
    }

    @Test
    fun `should still count the failure and let nothing escape when the breadcrumb appender throws`() {
        // What is tested: the wiring report under a broken diagnostics channel - the counter runs before
        //   the line, and the throwing appender is confined by reportQuietly.
        // Success criteria: no exception reaches the caller; stage=wiring is at 1.
        // Why it matters: the report runs inside catch blocks of the request path; an escaping appender
        //   failure would fail the request the guard exists to protect.
        // Given
        val throwing =
            object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) = error("appender broken")
            }.apply { start() }
        breadcrumbs.logger.addAppender(throwing)

        // When/Then
        try {
            assertThatCode {
                reportWiringFailure(metrics, breadcrumbs.logger, WiringCost.LOST_FEATURE, IllegalStateException("x"), "Feature lost")
            }.doesNotThrowAnyException()
        } finally {
            breadcrumbs.logger.detachAppender(throwing)
        }
        assertThat(wiringFailures()).isEqualTo(1.0)
    }

    private companion object {
        val BREADCRUMB_LOGGER: String = LoggerFactory.getLogger(FailOpenDiagnosticsTest::class.java).name
    }
}
