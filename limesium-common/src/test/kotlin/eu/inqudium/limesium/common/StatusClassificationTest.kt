package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.event.Level

/**
 * The status half of the classification, shared by both twins (ADR-0007): the outcome by status class,
 * every 4xx at INFO. The twins' suites pin that they call it; this one pins the table.
 */
class StatusClassificationTest {
    @ParameterizedTest
    @ValueSource(ints = [200, 201, 204, 301, 304, 399])
    fun `should classify a non-error status as INFO success`(status: Int) {
        // What is tested: every status below 400 is a success.
        // Success criteria: INFO, success.
        // Why it matters: a redirect or a 304 completes the exchange as designed.
        // Given/When/Then
        assertThat(StatusClassification.levelAndOutcome(status)).isEqualTo(Level.INFO to "success")
    }

    @Test
    fun `should classify a missing status as INFO success`() {
        // What is tested: the null branch - a reactive exchange handed over without a committed status.
        // Success criteria: INFO, success.
        // Why it matters: the twins invent no status; a missing one must not read as a rejection.
        // Given/When/Then
        assertThat(StatusClassification.levelAndOutcome(null)).isEqualTo(Level.INFO to "success")
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 405, 408, 409, 412, 415, 422, 429, 499])
    fun `should classify every 4xx as INFO rejected`(status: Int) {
        // What is tested: the whole 4xx class, the four statuses the outbound sibling escalates included.
        // Success criteria: INFO, rejected.
        // Why it matters: inbound, the caller is the foreign party - its refused request is not the
        //   operator's to act on, and at WARN scanners and expired tokens would drown the channel
        //   (ADR-0007).
        // Given/When/Then
        assertThat(StatusClassification.levelAndOutcome(status)).isEqualTo(Level.INFO to "rejected")
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503, 504, 599])
    fun `should classify a 5xx as WARN failure`(status: Int) {
        // What is tested: the 5xx class - the application answered that it is broken.
        // Success criteria: WARN, failure.
        // Why it matters: the level keeps a broken endpoint visible; the outcome counts it as failed.
        // Given/When/Then
        assertThat(StatusClassification.levelAndOutcome(status)).isEqualTo(Level.WARN to "failure")
    }
}
