package eu.inqudium.limesium.reactive.logging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Configuration surface of the endpoint-logging WebFilter, bound from the `endpoint-logging.*`
 * namespace - the SHARED keys and defaults are identical to limesium-servlet-logging's, key for key
 * and default for default, plus exactly one reactive-only key: [variant].
 * `EndpointLoggingReferenceConfigTest` enforces exactly that contract by binding the servlet module's
 * reference YAML against THIS class.
 *
 * Everything an operator may tune is a Boot property with a safe default, and everything a host
 * application may want to replace wholesale (time source, id generator, the filter itself) is an
 * overridable bean instead of a constructor argument.
 * A many-parameter filter constructor is exactly what this design avoids.
 *
 * Body values are logged verbatim. Header values are verbatim too unless a header is listed in its
 * section's [HeaderLogProperties.masked] - then a stable short fingerprint replaces the value.
 */
@ConfigurationProperties("endpoint-logging")
data class RequestLoggingProperties(
    /** Master switch; `false` removes the filter from the chain entirely (auto-configuration backs off). */
    val enabled: Boolean = true,
    /**
     * Which filter variant this module registers - a REACTIVE-ONLY key (the servlet twin has nothing to
     * select). [Variant.AUTO] (default) keeps the classpath-based choice: the coroutine variant when
     * `kotlinx-coroutines-reactor` and `kotlinx-coroutines-slf4j` are present, the Reactor variant
     * otherwise. [Variant.REACTOR] forces the Reactor variant even with the coroutine libraries present
     * (e.g. pulled in transitively by a Reactor-only host); [Variant.COROUTINE] requires them and fails
     * the context start when they are missing, instead of silently falling back.
     */
    val variant: Variant = Variant.AUTO,
    /**
     * Name of the logger the exchange lines are emitted on. The default is a dedicated, stable name,
     * so log routing and level configuration can target exactly these lines.
     */
    val loggerName: String = "http-exchange",
    /**
     * Header the correlation id is read from on TRACELESS exchanges (no conformant `traceparent` -
     * ADR-0002); when absent or blank a new id is generated. Only such an exchange echoes the id back
     * on the response under the same header name; a traced exchange takes its request id from the
     * `traceparent` trace id, ignores this header, and echoes nothing - the wire stays untouched.
     */
    val correlationIdHeader: String = "X-Correlation-Id",
    /** Whether the request's query string is appended to the logged path. */
    val includeQueryString: Boolean = true,
    /**
     * Optionally logs a first line the moment the request ARRIVES, before the chain runs - so
     * long-running or never-completing exchanges are visible while still in flight. The completion event
     * remains the single line carrying `endpoint_outcome`, so outcome-keyed dashboards are unaffected by
     * enabling this.
     */
    val logRequestStart: Boolean = false,
    /**
     * URL patterns (Spring `PathPattern` syntax, e.g. `/api/{*path}`; a trailing double-asterisk wildcard
     * is supported as well) that determine for which endpoints the filter is active AT ALL. Empty (the default) means every endpoint. A request is
     * logged when it matches ANY include pattern and NO exclude prefix - an exclude always wins,
     * mirroring the header sections' rule. Patterns match the path WITHIN the application (a
     * configured context/base path is stripped first, as in Spring's own handler mapping). Invalid patterns fail the context start (parsed once at
     * filter construction).
     */
    val includePathPatterns: List<String> = emptyList(),
    /**
     * Request-URI prefixes that are not logged at all (the filter does not even run for them). Typical
     * value: `/actuator/health`. Prefix match against the DECODED path within the application
     * (percent-encoding resolved, path parameters dropped, context/base path stripped - the
     * representation the router matches), subtracted from the include set.
     */
    val excludePathPrefixes: List<String> = emptyList(),
    /**
     * At or above this duration the exchange line escalates from INFO to WARN. Compared at full
     * precision; the logged `endpoint_duration_ms` has millisecond resolution, so the threshold must be
     * at least one millisecond - a sub-millisecond value would flag exchanges whose logged duration is 0.
     */
    val slowRequestThreshold: Duration = Duration.ofSeconds(5),
    /** Selection and masking of the REQUEST headers on the exchange line; nothing is logged by default. */
    val requestHeaders: HeaderLogProperties = HeaderLogProperties(),
    /** Selection and masking of the RESPONSE headers on the exchange line; nothing is logged by default. */
    val responseHeaders: HeaderLogProperties = HeaderLogProperties(),
    /** Whether the request body (up to [maxBodyBytes]) is captured and logged. */
    val logRequestBody: Boolean = false,
    /** Whether the response body (up to [maxBodyBytes]) is captured and logged. */
    val logResponseBody: Boolean = false,
    /**
     * Whether the request body SIZE is measured (meter `endpoint.request.body.size`, tagged by the
     * handler pattern). Deliberately independent of [logRequestBody]: a metric must not appear and
     * disappear with a logging flag. Measure-only installs a count-only tee - nothing is buffered.
     * Recorded at emission (see the emission point on `RequestLoggingWebFilter`), and only for bodies
     * that actually flowed (zero bytes record no sample).
     */
    val measureRequestBodySize: Boolean = false,
    /** As [measureRequestBodySize], for the response (`endpoint.response.body.size`). */
    val measureResponseBodySize: Boolean = false,
    /**
     * Capture limit per body. The limit bounds MEMORY, not the exchange: bytes beyond it still flow to the
     * application respectively the client unchanged, only the log line is truncated (and says so).
     */
    val maxBodyBytes: Int = 16384,
) {
    init {
        require(loggerName.isNotBlank()) { "loggerName must not be blank" }
        require(correlationIdHeader.isNotBlank()) { "correlationIdHeader must not be blank" }
        require(HTTP_FIELD_NAME.matches(correlationIdHeader)) {
            "correlationIdHeader must be a valid HTTP field name (RFC 9110 token), got: '$correlationIdHeader'"
        }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive, got: $maxBodyBytes" }
        require(slowRequestThreshold.toMillis() >= 1) {
            "slowRequestThreshold must be at least 1 millisecond, got: $slowRequestThreshold"
        }
        require(includePathPatterns.none { it.isBlank() }) {
            "includePathPatterns contains blank entries: $includePathPatterns"
        }
        require(excludePathPrefixes.none { it.isBlank() }) {
            "excludePathPrefixes contains blank entries: $excludePathPrefixes"
        }
    }

    companion object {
        /**
         * RFC 9110 `token` grammar for a field name. The configured name is written to every response;
         * a server adapter that validates field names would reject a non-token at runtime on EVERY
         * request, degrading the filter to an unlogged pass-through without ever failing startup
         * - so it is validated at binding time.
         */
        private val HTTP_FIELD_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    }
}

/** The filter variants of this module; see [RequestLoggingProperties.variant]. */
enum class Variant { AUTO, REACTOR, COROUTINE }

/**
 * One header section (`request-headers` / `response-headers`): which header names are logged, and which
 * of the logged values are masked.
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
         * the twin module uses. STABLE: identical values render
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
