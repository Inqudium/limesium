package eu.inqudium.limesium.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/** The two built-in maskers: the unkeyed fingerprint and its keyed (HMAC) variant, and the property's factory. */
class HeaderValueMaskerTest {
    @Test
    fun `should key the fingerprint with an HMAC that keeps the shape and changes the digits`() {
        // What is tested: the keyed variant - same `length:hex16` shape, different 64 bits, pinned as
        //   known answers (HMAC-SHA256 over UTF-8, first 8 bytes) so the format cannot drift silently -
        //   the same literals the outbound sibling legatium pins.
        // Success criteria: two keys give two different fingerprints, both differ from the unkeyed one,
        //   and each matches its literal.
        // Why it matters: a keyed fingerprint is what makes `masked` guess-proof; the literals are the
        //   contract a peer sharing the key can rely on.
        // Given/When/Then
        assertThat(HeaderValueMasker.DEFAULT.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
        assertThat(HeaderValueMasker.keyed("k").mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
        assertThat(HeaderValueMasker.keyed("pepper").mask("secret-token")).isEqualTo("12:3f86c6d54e06207d")
    }

    @Test
    fun `should render identical values identically under the same key`() {
        // Given/When/Then: stability is what keeps a masked token correlatable
        val masker = HeaderValueMasker.keyed("k")
        assertThat(masker.mask("Bearer x")).isEqualTo(masker.mask("Bearer x"))
        assertThat(masker.mask("Bearer x")).isEqualTo(HeaderValueMasker.keyed("k").mask("Bearer x"))
    }

    @Test
    fun `should select the unkeyed default for an empty key and the keyed variant otherwise`() {
        // Given/When/Then
        assertThat(HeaderValueMasker.forKey("")).isSameAs(HeaderValueMasker.DEFAULT)
        assertThat(HeaderValueMasker.forKey("k").mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
    }

    @Test
    fun `should reject a blank key`() {
        // Given/When/Then: whitespace is not a secret
        assertThat(catchThrowable { HeaderValueMasker.keyed(" ") }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(catchThrowable { HeaderValueMasker.forKey("  ") }).isInstanceOf(IllegalArgumentException::class.java)
    }
}
