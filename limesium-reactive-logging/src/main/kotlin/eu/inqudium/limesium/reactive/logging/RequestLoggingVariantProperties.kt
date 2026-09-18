package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.RequestLoggingProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The ONE reactive-only key of the `endpoint-logging.*` namespace, bound beside the shared
 * [RequestLoggingProperties] under the same prefix: which filter variant this module registers -
 * `variant` in this module's own reference file (`docs/endpoint-logging-reference.yml`), which
 * `EndpointLoggingReferenceConfigTest` pins to document nothing else.
 *
 * A class of its own rather than a field on the shared class, so the servlet twin's namespace has no
 * key it cannot honour; two `@ConfigurationProperties` beans on one prefix are ordinary Boot - each
 * binds the keys it knows and ignores the rest.
 */
@ConfigurationProperties("endpoint-logging")
data class RequestLoggingVariantProperties(
    /** Which filter variant this module registers - `variant`. */
    val variant: Variant = Variant.AUTO,
)

/** The filter variants of this module; see [RequestLoggingVariantProperties.variant]. */
enum class Variant { AUTO, REACTOR, COROUTINE }
