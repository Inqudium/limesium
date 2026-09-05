package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The acceptance rule for caller-supplied correlation ids, shared by both twins. */
class CorrelationHeaderValueTest {
    @Test
    fun `should adopt the id shapes callers actually send`() {
        // What is tested: the positive side of the rule - the id formats of gateways, sidecars and
        //   the module's own generator.
        // Success criteria: a UUID, a 21-character base-36 id, a ULID, a hex digest, a value with
        //   token punctuation and a value of exactly the maximum length are returned unchanged.
        // Why it matters: an over-strict rule would silently replace a caller's id with a generated
        //   one - the correlation the header exists for would break without any symptom but a rising
        //   `generated` share.
        // Given/When/Then: each accepted shape comes back verbatim
        listOf(
            "3c9adf3d-6a95-4f2b-9d43-b9351a4d8c11",
            "0000000000000" + "00000000",
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "930bbdc51b6aed5c",
            "req:42/v1.7+alpha_beta~",
            "x".repeat(CorrelationHeaderValue.MAX_LENGTH),
        ).forEach { id ->
            assertThat(CorrelationHeaderValue.accept(id)).describedAs(id).isEqualTo(id)
        }
    }

    @Test
    fun `should treat absent blank over-long and non-visible-ascii values as missing`() {
        // What is tested: the negative side of the rule - every value that must count as an absent
        //   header so that a fresh id is generated.
        // Success criteria: null for null, the empty string, whitespace, one character beyond the
        //   maximum length, an inner space, a tab, a control character and a non-ASCII letter.
        // Why it matters: an accepted value is echoed and written into every log line of the
        //   exchange; without the bound its length and character set would be dictated by the peer
        //   (code analysis of 2026-09-05, finding 11).
        // Given/When/Then: each rejected shape yields null
        listOf(
            null,
            "",
            "   ",
            "x".repeat(CorrelationHeaderValue.MAX_LENGTH + 1),
            "id with space",
            "id\twith-tab",
            "idcontrol",
            "id-é",
        ).forEach { value ->
            assertThat(CorrelationHeaderValue.accept(value)).describedAs(value ?: "<null>").isNull()
        }
    }
}
