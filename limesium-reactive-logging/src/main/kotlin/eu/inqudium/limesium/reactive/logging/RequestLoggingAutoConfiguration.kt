package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.EndpointLoggingPropertyOrigins
import eu.inqudium.limesium.common.EndpointObservationWiring
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.RequestLoggingProperties
import io.micrometer.context.ContextRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Registers the [RequestLoggingWebFilter] in a REACTIVE (WebFlux) Spring Boot application - drop the
 * module on the classpath and every exchange is logged; `endpoint-logging.enabled=false` removes it
 * again. The property namespace is the servlet twin's - one shared [RequestLoggingProperties] - plus the
 * reactive-only `endpoint-logging.variant` selector ([RequestLoggingVariantProperties]); the two
 * auto-configurations can never clash, as each is conditional on its own web-application type.
 *
 * Every bean backs off to a host-provided one. The meter registry arrives as an [ObjectProvider] and is
 * CONSUMED, never exported - a logging library must not define the host's `MeterRegistry`; without one
 * (no actuator) a private [SimpleMeterRegistry] absorbs the counts and the module works unchanged. WebFlux picks the
 * `WebFilter` bean up automatically and orders it via its [org.springframework.core.Ordered] contract.
 *
 * ## Observing the wiring
 *
 * At DEBUG on this class's logger - ONE logger for both variants, the coroutine auto-configuration
 * reports on it too - the auto-configurations report what they did, so a host can tell from its own
 * log whether the module is switched on and which filter was actually wired: one line when this
 * configuration is active (the switch is on), one when a filter bean is registered, naming the variant
 * (with the bound properties, the masking key redacted), and one when the `endpoint_*` MDC accessors
 * are registered. With `endpoint-logging.enabled=false` none of them appears - Boot's condition
 * evaluation report (DEBUG on `org.springframework.boot.autoconfigure`) then names the property as
 * the reason.
 *
 * At TRACE the bean line is followed by the ORIGIN of every `endpoint-logging.*` value Boot bound - the
 * file and line, the environment variable, the property source - and by every value of the same name
 * a lower-precedence source also holds, marked as shadowed ([EndpointLoggingPropertyOrigins]).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "endpoint-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestLoggingProperties::class, RequestLoggingVariantProperties::class)
class RequestLoggingAutoConfiguration {
    init {
        wiringLog.debug("Endpoint logging is enabled - the auto-configuration is active (endpoint-logging.enabled is not false)")
    }

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingNanoTimeSource(): NanoTimeSource = NanoTimeSource.SYSTEM

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator.DEFAULT

    /** How masked header values render - a host pins a keyed or fixed masker; both variants and both twins take the same bean. */
    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingHeaderValueMasker(properties: RequestLoggingProperties): HeaderValueMasker = HeaderValueMasker.forKey(properties.maskingKey.value)

    /**
     * The Reactor variant, registered only when NO [EndpointLoggingFilter] exists yet: the coroutine
     * auto-configuration runs BEFORE this one and claims the slot when the coroutine libraries are
     * present, and a host-defined bean of either variant backs both off.
     *
     * `endpoint-logging.variant=coroutine` reaching this method means the coroutine variant was demanded
     * but did not register (its libraries are missing): that fails the context start with a message
     * naming the missing libraries, instead of silently serving the other variant.
     */
    @Bean
    @ConditionalOnMissingBean(EndpointLoggingFilter::class)
    fun requestLoggingWebFilter(
        properties: RequestLoggingProperties,
        variantProperties: RequestLoggingVariantProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        boundProperties: ObjectProvider<BoundConfigurationProperties>,
    ): RequestLoggingWebFilter {
        check(variantProperties.variant != Variant.COROUTINE) {
            "endpoint-logging.variant=coroutine requires kotlinx-coroutines-reactor and kotlinx-coroutines-slf4j " +
                "on the classpath; neither a coroutine filter nor those libraries were found"
        }
        wiringLog.debug("Endpoint logging registered its RequestLoggingWebFilter bean (Reactor variant, ordered at HIGHEST_PRECEDENCE + 10, collected by WebFlux) with {}", properties)
        EndpointLoggingPropertyOrigins.report(wiringLog, environment, boundProperties.ifAvailable)
        return RequestLoggingWebFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { SimpleMeterRegistry() }, masker)
    }

    /**
     * Handler-MDC parity with the servlet twin: with `io.micrometer:context-propagation` on the classpath
     * (an optional dependency - no extra `endpoint-logging.*` key) the `endpoint_*` accessors are
     * registered and the propagation-mode prerequisite is validated at startup - see
     * [EndpointMdcContextPropagation] for the mechanism and the prerequisite.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ContextRegistry::class)
    class MdcContextPropagationConfiguration {
        /**
         * Runs only while the REACTOR variant owns the filter slot (the module's own or a host-defined
         * [RequestLoggingWebFilter]): the accessors read the Reactor context THAT variant writes, and the
         * propagation-mode warning is about that variant's handler MDC. With the coroutine variant
         * active (handler MDC natively via `MDCContext`) or a host filter of another type, nothing here
         * applies and a startup warning would be false noise. Resolved at initialization time rather than by
         * `@ConditionalOnBean`, whose evaluation order against the sibling bean methods is not guaranteed.
         * Resolved over ALL filter beans (`stream()`), never through `getIfAvailable()`: that call throws
         * `NoUniqueBeanDefinitionException` for two host-defined filters - a constellation the module
         * otherwise permits - and would fail the context start from a logging library.
         */
        @Bean
        fun endpointMdcContextPropagationInitializer(
            environment: Environment,
            activeFilter: ObjectProvider<EndpointLoggingFilter>,
        ): InitializingBean =
            InitializingBean {
                if (activeFilter.stream().anyMatch { it is RequestLoggingWebFilter }) {
                    EndpointMdcContextPropagation.registerAccessors()
                    wiringLog.debug("Endpoint logging registered the endpoint_* MDC accessors with Micrometer's ContextRegistry (Reactor variant)")
                    EndpointMdcContextPropagation.warnUnlessAutomaticPropagation(
                        environment.getProperty(EndpointMdcContextPropagation.PROPAGATION_MODE_PROPERTY),
                    )
                }
            }
    }

    /**
     * The observation line of the wiring report ([EndpointObservationWiring]) - logged once every singleton
     * exists, for both variants (this configuration is active whichever claimed the slot). On this stack
     * the server observation is no `WebFilter`: WebFlux's `HttpWebHandlerAdapter` observes every request
     * as soon as an `ObservationRegistry` bean exists, outside the whole filter chain - so it always
     * wraps the module's filter and no order is compared.
     */
    @Bean
    fun endpointLoggingObservationReport(beanFactory: ListableBeanFactory): SmartInitializingSingleton =
        SmartInitializingSingleton {
            if (wiringLog.isDebugEnabled) {
                val observation =
                    if (EndpointObservationWiring.hasBean(beanFactory, EndpointObservationWiring.OBSERVATION_REGISTRY)) {
                        EndpointObservationWiring.Observation("the HttpWebHandlerAdapter observes every request outside all WebFilters", wraps = true)
                    } else {
                        null
                    }
                wiringLog.debug(EndpointObservationWiring.describe(beanFactory, observation))
            }
        }

    companion object {
        /**
         * The wiring report of the class KDoc, at DEBUG and TRACE - shared with
         * [CoRequestLoggingAutoConfiguration], so a host reads one logger per twin; the exchange lines
         * have their own logger.
         */
        internal val wiringLog = LoggerFactory.getLogger(RequestLoggingAutoConfiguration::class.java)
    }
}
