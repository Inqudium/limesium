package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W3C conformance of [Traceparent], driven by the fixture `traceparent/conformance.txt` - the
 * conformance rules are thereby a build contract, not a KDoc rule (the rules themselves originate
 * from internal code analyses).
 */
class TraceparentTest {
    @Test
    fun `should accept every conformant header of the shared fixture with the expected identifiers`() {
        // What is tested: Traceparent.parse against every valid line of the shared conformance
        //   fixture.
        // Success criteria: each header parses to exactly the trace id and span id the fixture
        //   names.
        // Why it matters: the fixture is the one copy of the W3C contract both twins and Legatium
        //   share; a parser drifting from it would join or drop traces differently per module.
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
        // What is tested: parse(null) - the traceless request.
        // Success criteria: null, no exception.
        // Why it matters: most requests carry no traceparent; the absent case is the hot path and
        //   must not throw on the fail-open route.
        // Given/When/Then
        assertThat(Traceparent.parse(null)).isNull()
    }
}
