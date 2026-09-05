package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/** The secret-bearing value the `masking-key` property binds to: its guard, its equality and its redaction. */
class MaskingKeyTest {
    @Test
    fun `should reject a blank key but accept an empty one`() {
        // What is tested: the binding-time rule of the value - empty means unkeyed, blank is a
        //   misconfiguration.
        // Success criteria: whitespace fails construction naming the property; the empty key is NONE.
        // Why it matters: a whitespace key would silently key the fingerprint with a worthless secret.
        // Given/When/Then
        assertThat(catchThrowable { MaskingKey("  ") })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maskingKey")
        assertThat(MaskingKey("")).isEqualTo(MaskingKey.NONE)
        assertThat(MaskingKey.NONE.value).isEmpty()
    }

    @Test
    fun `should redact a key in toString and keep an empty one visibly empty`() {
        // What is tested: the rendering the properties classes inherit through their generated toString.
        // Success criteria: a key renders as the marker and never as its text; the empty key renders empty.
        // Why it matters: a properties dump must not leak the secret, and an operator must still see
        //   whether a key is set at all.
        // Given/When/Then
        assertThat(MaskingKey("pepper").toString()).isEqualTo("<redacted>").doesNotContain("pepper")
        assertThat(MaskingKey.NONE.toString()).isEmpty()
    }

    @Test
    fun `should compare by value`() {
        // What is tested: value equality, which the properties data classes' equals/copy rely on.
        // Success criteria: equal keys are equal with equal hash codes; different keys are not.
        // Why it matters: a data class holding an identity-compared key would break its own copy/equals contract.
        // Given/When/Then
        assertThat(MaskingKey("k")).isEqualTo(MaskingKey("k")).hasSameHashCodeAs(MaskingKey("k"))
        assertThat(MaskingKey("k")).isNotEqualTo(MaskingKey("pepper"))
    }
}
