package eu.inqudium.limesium.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * One header section (`request-headers` / `response-headers`): which header names are logged, and which
 * of the logged values are masked. Shared by both endpoint-logging twins (ADR-0003 amendment): selection
 * semantics and the masking fingerprint are a cross-twin contract, and the twins' copies were
 * byte-identical.
 *
 * - [includes] names the headers to log; empty means NONE (the safe default). The entry `*` includes
 *   every header the message carries.
 * - [excludes] removes names from the included set - meaningful mainly together with the `*` include;
 *   an exclude always wins over an include. The `*` wildcard is NOT supported here and rejected at
 *   binding time - an empty [includes] already logs nothing, so a wildcard exclude could only be a
 *   misconfiguration that would otherwise fail silently.
 * - [masked] replaces the VALUE of a logged header by a stable short fingerprint (see [mask]); `*`
 *   masks every logged header. Masking only affects headers that are logged at all - listing a name
 *   here does not include it.
 *
 * All matching is case-insensitive, as header names are.
 */
data class HeaderLogProperties(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val masked: List<String> = emptyList(),
) {
    init {
        require(includes.none { it.isBlank() }) { "includes contains blank entries: $includes" }
        require(excludes.none { it.isBlank() }) { "excludes contains blank entries: $excludes" }
        require(WILDCARD !in excludes) {
            "excludes does not support the '$WILDCARD' wildcard - an empty includes list already logs nothing"
        }
        require(masked.none { it.isBlank() }) { "masked contains blank entries: $masked" }
    }

    // Derived ONCE from the immutable configuration (after the validation above): rebuilding these
    // sets on every call was a measured allocation cost on the per-request path (header-selection
    // finding of the module's performance analysis of 2026-08-29, confirmed by benchmark).
    private val excludedLower: Set<String> = excludes.map { it.lowercase() }.toSet()
    private val maskedLower: Set<String> = masked.map { it.lowercase() }.toSet()
    private val maskAll: Boolean = WILDCARD in masked
    private val wildcardInclude: Boolean = WILDCARD in includes

    /**
     * The headers this section logs, as `(name, logged value)` pairs: included minus excluded, values
     * masked where configured. [availableNames] is consulted only for the `*` include (deduplicated
     * case-insensitively there, since servlet enumerations may repeat names); explicit includes are
     * looked up directly and keep their configured spelling.
     */
    fun select(
        availableNames: Collection<String>,
        valueOf: (String) -> String?,
    ): List<Pair<String, String>> {
        if (includes.isEmpty()) {
            return emptyList()
        }
        val names = if (wildcardInclude) availableNames.distinctBy { it.lowercase() } else includes
        return names.mapNotNull { name ->
            val lower = name.lowercase()
            if (lower in excludedLower) {
                return@mapNotNull null
            }
            valueOf(name)?.let { value ->
                name to if (maskAll || lower in maskedLower) mask(value) else value
            }
        }
    }

    companion object {
        const val WILDCARD = "*"

        /**
         * Redacts a header [value] to its character length followed by the first 64 bits of its SHA-256
         * digest (UTF-8) in lowercase hex (e.g. `18:930bbdc51b6aed5c`) - the same fingerprint
         * in both twin modules. STABLE: identical values render
         * identically, so a masked token can still be correlated across events and modules without
         * exposing the secret itself; a 64-bit cryptographic prefix makes accidental collisions
         * negligible (the former 32-bit `String.hashCode` fingerprint collided trivially).
         *
         * Privacy model: the fingerprint is unsalted and unkeyed - it prevents PLAINTEXT exposure, not
         * offline guessing. A log reader with a candidate list (low-entropy values: usernames, tenant
         * names, short API keys) can confirm a candidate by hashing it. Do not treat `masked` as a
         * security boundary for guessable values; omit such headers from the selection instead.
         */
        fun mask(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            // HexFormat instead of a per-byte "%02x".format: byte-identical output at a fraction
            // of the allocation (masking finding of the module's performance analysis of
            // 2026-08-29, confirmed by benchmark).
            return "${value.length}:${HEX.formatHex(digest, 0, FINGERPRINT_BYTES)}"
        }

        private val HEX = HexFormat.of()
        private const val FINGERPRINT_BYTES = 8
    }
}
