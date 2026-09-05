package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyLogMode
import eu.inqudium.limesium.common.HeaderLogProperties
import eu.inqudium.limesium.common.MaskingKey
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
 *
 * Every property's semantics, rules and default are documented ONCE, in the repository-shared reference
 * configuration `/docs/endpoint-logging-reference.yml` (bound against this class by
 * `EndpointLoggingReferenceConfigTest`); the KDoc here names the key.
 */
@ConfigurationProperties("endpoint-logging")
data class RequestLoggingProperties(
    /** Master switch; `false` backs the auto-configuration off entirely - `enabled`. */
    val enabled: Boolean = true,
    /** Logger the exchange lines are emitted on - `logger-name`. */
    val loggerName: String = "endpoint-http-exchange",
    /** Header the correlation id is read from on traceless exchanges (ADR-0002; a value outside `CorrelationHeaderValue` counts as absent) - `correlation-id-header`. */
    val correlationIdHeader: String = "X-Correlation-Id",
    /** Whether the query string is logged as its own field - `include-query-string`. */
    val includeQueryString: Boolean = true,
    /** Whether a first line is logged the moment the request arrives - `log-request-start`. */
    val logRequestStart: Boolean = false,
    /** `PathPattern`s deciding where the filter is active at all; invalid patterns fail the context start - `include-path-patterns`. */
    val includePathPatterns: List<String> = emptyList(),
    /** Decoded-path prefixes the filter does not run for; an exclude always wins - `exclude-path-prefixes`. */
    val excludePathPrefixes: List<String> = emptyList(),
    /** At or above this duration the line escalates from INFO to WARN; at least one millisecond - `slow-request-threshold`. */
    val slowRequestThreshold: Duration = Duration.ofSeconds(5),
    /** Selection and masking of the REQUEST headers on the exchange line - `request-headers.*`. */
    val requestHeaders: HeaderLogProperties = HeaderLogProperties(),
    /** Selection and masking of the RESPONSE headers on the exchange line - `response-headers.*`. */
    val responseHeaders: HeaderLogProperties = HeaderLogProperties(),
    /** When the request body is logged, as a [BodyLogMode] (ADR-0006) - `log-request-body`. */
    val logRequestBody: BodyLogMode = BodyLogMode.NEVER,
    /** As [logRequestBody], for the response body - `log-response-body`. */
    val logResponseBody: BodyLogMode = BodyLogMode.NEVER,
    /** Whether the request body size and read state are measured, independent of logging - `measure-request-body-size`. */
    val measureRequestBodySize: Boolean = false,
    /** As [measureRequestBodySize], for the response - `measure-response-body-size`. */
    val measureResponseBodySize: Boolean = false,
    /** Capture limit per body in bytes; bounds memory, never the exchange - `max-body-bytes`. */
    val maxBodyBytes: Int = 16384,
    /** Keys the masking fingerprint (HMAC-SHA256) - a [MaskingKey], the secret its own `toString` redacts - `masking-key`. */
    val maskingKey: MaskingKey = MaskingKey.NONE,
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
