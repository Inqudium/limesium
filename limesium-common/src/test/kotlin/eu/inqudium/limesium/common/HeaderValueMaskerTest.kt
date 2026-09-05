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
        // What is tested: stability of the keyed fingerprint - within one masker and across two
        //   maskers built from the same key.
        // Success criteria: the same value renders the same string in both cases.
        // Why it matters: a per-instance nonce or salt would keep the shape but break the
        //   correlation of a masked token across events, twins and the outbound sibling.
        // Given/When/Then: stability is what keeps a masked token correlatable
        val masker = HeaderValueMasker.keyed("k")
        assertThat(masker.mask("Bearer x")).isEqualTo(masker.mask("Bearer x"))
        assertThat(masker.mask("Bearer x")).isEqualTo(HeaderValueMasker.keyed("k").mask("Bearer x"))
    }

    @Test
    fun `should select the unkeyed default for an empty key and the keyed variant otherwise`() {
        // What is tested: `forKey` - the factory the `masking-key` property drives.
        // Success criteria: the empty key yields the DEFAULT instance itself; a key yields the
        //   keyed fingerprint with its pinned literal.
        // Why it matters: the property is the one source of truth for keying; a factory that keyed
        //   an empty key or unkeyed a set one would silently change every logged fingerprint.
        // Given/When/Then
        assertThat(HeaderValueMasker.forKey("")).isSameAs(HeaderValueMasker.DEFAULT)
        assertThat(HeaderValueMasker.forKey("k").mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
    }

    @Test
    fun `should reject a blank key`() {
        // What is tested: the blank-key guard of `keyed` and `forKey`.
        // Success criteria: both throw IllegalArgumentException for whitespace-only keys.
        // Why it matters: whitespace is not a secret; a masker keyed with it would look guess-proof
        //   and be trivially guessable.
        // Given/When/Then: whitespace is not a secret
        assertThat(catchThrowable { HeaderValueMasker.keyed(" ") }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(catchThrowable { HeaderValueMasker.forKey("  ") }).isInstanceOf(IllegalArgumentException::class.java)
    }
}
