package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.EndpointLoggingPropertyOrigins
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.slf4j.MDCContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.env.Environment
import org.springframework.web.server.CoWebFilter

/**
 * Registers the COROUTINE variant ([CoRequestLoggingWebFilter]) when the coroutine libraries are present
 * - `kotlinx-coroutines-reactor` (for [CoWebFilter]) and `kotlinx-coroutines-slf4j` (for [MDCContext]),
 * both optional dependencies of this module; their presence is the whole opt-in, so the
 * `endpoint-logging.*` namespace stays identical across the variants - and, apart from the
 * reactive-only `variant` key, across the twins.
 *
 * Ordered BEFORE [RequestLoggingAutoConfiguration]: whichever variant registers first claims the
 * [EndpointLoggingFilter] slot, and the other backs off via `@ConditionalOnMissingBean` - exactly one
 * filter is ever active. The time-source, id-generator and header-masker defaults still come from the
 * main auto-configuration (bean creation is independent of registration order).
 *
 * The classpath-based choice can be overridden explicitly: `endpoint-logging.variant=reactor` makes
 * this configuration back off although the libraries are present (see [NotForcedToReactor]);
 * `endpoint-logging.variant=coroutine` is enforced by the Reactor auto-configuration, which refuses to
 * register its fallback when the coroutine variant was demanded but its libraries are missing.
 *
 * Reports on the Reactor configuration's wiring logger ([RequestLoggingAutoConfiguration.wiringLog]):
 * the bean line naming this variant at DEBUG, the property origins at TRACE - one logger per twin.
 */
@AutoConfiguration(before = [RequestLoggingAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "endpoint-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(CoWebFilter::class, MDCContext::class)
@Conditional(CoRequestLoggingAutoConfiguration.NotForcedToReactor::class)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class CoRequestLoggingAutoConfiguration {
    /** Matches unless `endpoint-logging.variant=reactor` is configured - the explicit opt-out of this variant. */
    class NotForcedToReactor : NoneNestedConditions(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION) {
        /**
         * The nested POSITIVE condition [NotForcedToReactor] inverts - framework plumbing, not API;
         * Spring reads member conditions from class metadata, so `private` suffices.
         */
        @ConditionalOnProperty(prefix = "endpoint-logging", name = ["variant"], havingValue = "reactor")
        private class ForcedToReactor
    }

    @Bean
    @ConditionalOnMissingBean(EndpointLoggingFilter::class)
    fun coRequestLoggingWebFilter(
        properties: RequestLoggingProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        boundProperties: ObjectProvider<BoundConfigurationProperties>,
    ): CoRequestLoggingWebFilter {
        val log = RequestLoggingAutoConfiguration.wiringLog
        log.debug("Endpoint logging registered its CoRequestLoggingWebFilter bean (coroutine variant, ordered at HIGHEST_PRECEDENCE + 10, collected by WebFlux) with {}", properties)
        EndpointLoggingPropertyOrigins.report(log, environment, boundProperties.ifAvailable)
        return CoRequestLoggingWebFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { SimpleMeterRegistry() }, masker)
    }
}
