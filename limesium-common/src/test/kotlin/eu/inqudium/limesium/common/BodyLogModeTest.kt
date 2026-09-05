package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The truth table of [BodyLogMode]: what is captured, and what reaches the line for which outcome. */
class BodyLogModeTest {
    @Test
    fun `should capture in every mode but never`() {
        // What is tested: the `captures` property of the three modes - whether a bounded capture
        //   must be installed before the outcome is known.
        // Success criteria: NEVER false, ON_FAILURE and ALWAYS true.
        // Why it matters: on-failure needs the bytes although it may discard them; a mode that
        //   captured nothing would log an empty body on the one line an operator wants it.
        assertThat(BodyLogMode.NEVER.captures).isFalse()
        assertThat(BodyLogMode.ON_FAILURE.captures).isTrue()
        assertThat(BodyLogMode.ALWAYS.captures).isTrue()
    }

    @Test
    fun `should log on failure only when the exchange failed`() {
        // What is tested: the one decision the emitters delegate - on-failure discards a success.
        // Success criteria: true for a failed exchange (outcome not success, or a 4xx), false otherwise.
        // Why it matters: this single predicate is the volume switch of ADR-0006.
        assertThat(BodyLogMode.ON_FAILURE.logs(failed = false)).isFalse()
        assertThat(BodyLogMode.ON_FAILURE.logs(failed = true)).isTrue()
    }

    @Test
    fun `should log always and never regardless of the outcome`() {
        // What is tested: the two unconditional modes of `logs(failed)` - ALWAYS and NEVER answer
        //   the same for a clean and for a failed exchange.
        // Success criteria: ALWAYS true for both outcomes, NEVER false for both.
        // Why it matters: only ON_FAILURE is outcome-gated (ADR-0006); the other two must not
        //   silently pick up a gate through a shared code path.
        assertThat(BodyLogMode.ALWAYS.logs(failed = false)).isTrue()
        assertThat(BodyLogMode.ALWAYS.logs(failed = true)).isTrue()
        assertThat(BodyLogMode.NEVER.logs(failed = false)).isFalse()
        assertThat(BodyLogMode.NEVER.logs(failed = true)).isFalse()
    }
}
