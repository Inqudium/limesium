package eu.inqudium.limesium.common

/**
 * The `masking-key` property as a SECRET-bearing value (code-style audit of 2026-09-05, finding 5):
 * the string that keys the built-in fingerprint ([HeaderValueMasker.forKey]), or the empty string
 * for the unkeyed default. A type of its own rather than a `String` so that the properties classes
 * keep their GENERATED `toString`: this class renders itself as `<redacted>` whenever it holds a key,
 * and a properties dump - a startup log, a debug endpoint - can never print the secret.
 *
 * Spring Boot binds the property through the single-`String` constructor; an empty key means
 * unkeyed, a blank one is rejected at binding time (whitespace is a worthless secret).
 */
class MaskingKey(
    /** The raw key; empty for the unkeyed fingerprint. Read it only to build the masker. */
    val value: String,
) {
    init {
        require(value.isEmpty() || value.isNotBlank()) { "maskingKey must not be blank (leave it empty for the unkeyed fingerprint)" }
    }

    override fun equals(other: Any?): Boolean = other is MaskingKey && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** The redaction: empty stays visibly empty, everything else is `<redacted>`. */
    override fun toString(): String = if (value.isEmpty()) "" else "<redacted>"

    companion object {
        /** The unkeyed default - what the property binds to when it is absent or empty. */
        @JvmField
        val NONE = MaskingKey("")
    }
}
