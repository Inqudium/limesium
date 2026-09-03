package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyLogMode
import eu.inqudium.limesium.common.HeaderLogProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration surface of the endpoint-logging filter, bound from the `endpoint-logging.*` namespace.
 *
 * Everything an operator may tune is a Boot property with a safe default, and everything a host
 * application may want to replace wholesale (time source, id generator, the filter itself) is an
 * overridable bean instead of a constructor argument.
 * A many-parameter filter constructor is exactly what this design avoids.
 *
 * Body values are logged verbatim. Header values are verbatim too unless a header is listed in its
 * section's [HeaderLogProperties.masked] - then the `HeaderValueMasker` bean's rendering replaces the
 * value (by default a stable short fingerprint).
 */
@ConfigurationProperties("endpoint-logging")
data class RequestLoggingProperties(
    /** Master switch; `false` removes the filter from the chain entirely (auto-configuration backs off). */
    val enabled: Boolean = true,
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
    /**
     * When the request body (up to [maxBodyBytes]) is logged: [BodyLogMode.NEVER] (the default),
     * [BodyLogMode.ON_FAILURE] - captured on every exchange, written only when the outcome is not
     * `success` or the status is a 4xx - or [BodyLogMode.ALWAYS]. The mode, not a switch, is what
     * decides the log volume (ADR-0006).
     */
    val logRequestBody: BodyLogMode = BodyLogMode.NEVER,
    /** As [logRequestBody], for the response body; the outcome is final at emission, so nothing is captured in vain. */
    val logResponseBody: BodyLogMode = BodyLogMode.NEVER,
    /**
     * Whether the request body SIZE is measured (meter `endpoint.request.body.size`, tagged by the
     * handler pattern). Deliberately independent of [logRequestBody]: a metric must not appear and
     * disappear with a logging flag. Measure-only installs a count-only tee - nothing is buffered.
     * Recorded at request destruction, and only for bodies that actually flowed (zero bytes record no
     * sample).
     */
    val measureRequestBodySize: Boolean = false,
    /** As [measureRequestBodySize], for the response (`endpoint.response.body.size`). */
    val measureResponseBodySize: Boolean = false,
    /**
     * Capture limit per body. The limit bounds MEMORY, not the exchange: bytes beyond it still flow to the
     * application respectively the client unchanged, only the log line is truncated (and says so).
     */
    val maxBodyBytes: Int = 16384,
    /**
     * Keys the masking fingerprint: empty (the default) keeps the unkeyed `length:hash` fingerprint,
     * any other value turns it into an HMAC-SHA256 under this key - same shape, same stability under
     * the same key, but guess-proof for a log reader without the key. A SECRET: supply it like one
     * (an environment variable, a vault-backed property), never as a checked-in literal; the
     * properties' `toString` redacts it. Ignored when a host pins its own `HeaderValueMasker` bean.
     */
    val maskingKey: String = "",
) {
    init {
        require(loggerName.isNotBlank()) { "loggerName must not be blank" }
        require(correlationIdHeader.isNotBlank()) { "correlationIdHeader must not be blank" }
        require(HTTP_FIELD_NAME.matches(correlationIdHeader)) {
            "correlationIdHeader must be a valid HTTP field name (RFC 9110 token), got: '$correlationIdHeader'"
        }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive, got: $maxBodyBytes" }
        require(maskingKey.isEmpty() || maskingKey.isNotBlank()) { "maskingKey must not be blank (leave it empty for the unkeyed fingerprint)" }
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

    /** The data-class rendering minus the secret: a properties dump must never print the masking key. */
    override fun toString(): String = copy(maskingKey = if (maskingKey.isEmpty()) "" else "<redacted>").render()

    private fun render(): String = "RequestLoggingProperties(enabled=$enabled, loggerName=$loggerName, correlationIdHeader=$correlationIdHeader, includeQueryString=$includeQueryString, logRequestStart=$logRequestStart, includePathPatterns=$includePathPatterns, excludePathPrefixes=$excludePathPrefixes, slowRequestThreshold=$slowRequestThreshold, requestHeaders=$requestHeaders, responseHeaders=$responseHeaders, logRequestBody=$logRequestBody, logResponseBody=$logResponseBody, measureRequestBodySize=$measureRequestBodySize, measureResponseBodySize=$measureResponseBodySize, maxBodyBytes=$maxBodyBytes, maskingKey=$maskingKey)"

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
