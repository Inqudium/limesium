package eu.inqudium.limesium.reactive.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W3C conformance of [Traceparent], driven by the SHARED fixture `traceparent/conformance.txt` that
 * web-client's `ExchangeDiaryLoggingTest` consumes as well - the lockstep between the two parsers is
 * thereby a build contract, not a KDoc rule (finding 5 of COMMENT_AUDIT-2026-08-22T22-11-51.md; the
 * conformance rules themselves are findings 5 of the 2026-08-22T16-35-46 and 2 of the 20-06-45 analysis).
 */
class TraceparentTest {
    @Test
    fun `should accept every conformant header of the shared fixture with the expected identifiers`() {
        // Given: the valid lines of the shared fixture
        val cases = TraceparentConformanceFixture.valid()
        assertThat(cases).isNotEmpty()

        // When/Then: each parses to the expected pair
        cases.forEach { (header, traceId, spanId) ->
            assertThat(Traceparent.parse(header)).describedAs(header).isEqualTo(traceId to spanId)
        }
    }

    @Test
    fun `should reject every non-conformant header of the shared fixture`() {
        // What is tested: identifier shape, all-zero ids, version (two lowercase hex, not ff, exactly four
        //   fields for 00), flags (two lowercase hex) and structure - the cases the fixture enumerates.
        // Success criteria: null for each.
        // Why it matters: an accepted invalid header lands under the traceId/parentSpanId MDC keys and
        //   produces joins the tracing infrastructure does not contain.
        // Given: the invalid lines of the shared fixture
        val cases = TraceparentConformanceFixture.invalid()
        assertThat(cases).isNotEmpty()

        // When/Then
        cases.forEach { header -> assertThat(Traceparent.parse(header)).describedAs(header).isNull() }
    }

    @Test
    fun `should treat an absent header as no trace context`() {
        // Given/When/Then
        assertThat(Traceparent.parse(null)).isNull()
    }
}
