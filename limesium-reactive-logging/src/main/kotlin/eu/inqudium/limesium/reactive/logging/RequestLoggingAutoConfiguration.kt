package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.context.ContextRegistry
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Registers the [RequestLoggingWebFilter] in a REACTIVE (WebFlux) Spring Boot application - drop the
 * module on the classpath and every exchange is logged; `endpoint-logging.enabled=false` removes it
 * again. The property namespace matches limesium-servlet-logging's key for key, plus the reactive-only
 * `endpoint-logging.variant` selector; the two auto-configurations can never clash, as each is
 * conditional on its own web-application type.
 *
 * Every bean backs off to a host-provided one. The meter registry arrives as an [ObjectProvider] and is
 * CONSUMED, never exported - a logging library must not define the host's `MeterRegistry`; without one
 * (no actuator) a private [SimpleMeterRegistry] absorbs the counts and the module works unchanged. WebFlux picks the
 * `WebFilter` bean up automatically and orders it via its [org.springframework.core.Ordered] contract.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "endpoint-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class RequestLoggingAutoConfiguration {
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
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): RequestLoggingWebFilter {
        check(properties.variant != Variant.COROUTINE) {
            "endpoint-logging.variant=coroutine requires kotlinx-coroutines-reactor and kotlinx-coroutines-slf4j " +
                "on the classpath; neither a coroutine filter nor those libraries were found"
        }
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
                    EndpointMdcContextPropagation.warnUnlessAutomaticPropagation(
                        environment.getProperty(EndpointMdcContextPropagation.PROPAGATION_MODE_PROPERTY),
                    )
                }
            }
    }
}
