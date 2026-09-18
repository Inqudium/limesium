package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.EndpointLoggingPropertyOrigins
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ServletRequestListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.env.Environment

/**
 * Registers the [RequestLoggingFilter] in a servlet (Tomcat) Spring Boot application - drop the module on
 * the classpath and every exchange is logged; `endpoint-logging.enabled=false` removes it again.
 *
 * Every bean backs off to a host-provided one: a host may pin [NanoTimeSource] or
 * [CorrelationIdGenerator] (tests do), replace the [HeaderValueMasker] (a keyed fingerprint for a
 * compliance regime), or define its own [RequestLoggingFilter] bean to take over registration entirely.
 *
 * ## Observing the wiring
 *
 * At DEBUG on this class's logger the auto-configuration reports what it did, so a host can tell from
 * its own log whether the module is switched on and whether the filter was actually wired: one line
 * when the configuration is active (the switch is on), one when the filter bean is registered (with the
 * bound properties, the masking key redacted), one for the filter registration with its order, and one
 * for the completion listener - the emission point. With `endpoint-logging.enabled=false` none of them
 * appears - Boot's condition evaluation report (DEBUG on `org.springframework.boot.autoconfigure`)
 * then names the property as the reason.
 *
 * At TRACE the bean line is followed by the ORIGIN of every `endpoint-logging.*` value Boot bound - the
 * file and line, the environment variable, the property source - and by every value of the same name
 * a lower-precedence source also holds, marked as shadowed ([EndpointLoggingPropertyOrigins]).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "endpoint-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class RequestLoggingAutoConfiguration {
    init {
        log.debug("Endpoint logging is enabled - the auto-configuration is active (endpoint-logging.enabled is not false)")
    }

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingNanoTimeSource(): NanoTimeSource = NanoTimeSource.SYSTEM

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator.DEFAULT

    /** How masked header values render - a host pins a keyed or fixed masker; both twins take the same bean. */
    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingHeaderValueMasker(properties: RequestLoggingProperties): HeaderValueMasker = HeaderValueMasker.forKey(properties.maskingKey.value)

    /**
     * The filter as its own bean, so a host can replace it while keeping the registration wiring below.
     *
     * The meter registry arrives as an [ObjectProvider] and is CONSUMED, never exported: a logging
     * library must not define the host's `MeterRegistry`. A host without one - no actuator - gets a
     * private [SimpleMeterRegistry]: the fail-open counters then count unexported, and the module works
     * unchanged.
     */
    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingFilter(
        properties: RequestLoggingProperties,
        nanoTime: NanoTimeSource,
        correlationIds: CorrelationIdGenerator,
        masker: HeaderValueMasker,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
        boundProperties: ObjectProvider<BoundConfigurationProperties>,
    ): RequestLoggingFilter {
        log.debug("Endpoint logging registered its RequestLoggingFilter bean with {}", properties)
        EndpointLoggingPropertyOrigins.report(log, environment, boundProperties.ifAvailable)
        return RequestLoggingFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { SimpleMeterRegistry() }, masker)
    }

    /**
     * Runs very early (but not first) in the chain, so the request id is in the MDC before other
     * filters log; the offset leaves room for infrastructure that must precede logging (metrics,
     * request-context setup). Referencing the filter bean here keeps Boot from ALSO auto-registering the
     * bare `Filter` bean - a registration bean claims its filter.
     *
     * Trace identity does NOT depend on this order: the filter parses the incoming `traceparent` header
     * itself (ADR-0002).
     * `RequestLoggingFilterTomcatTracingIntegrationTest` pins that contract beside a live bridge, so a
     * Boot upgrade that lets the bridge displace the parsed context breaks the build.
     */
    @Bean
    fun requestLoggingFilterRegistration(filter: RequestLoggingFilter): FilterRegistrationBean<RequestLoggingFilter> =
        FilterRegistrationBean(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 10
            log.debug("Endpoint logging registered the filter registration - the filter runs at order {} (HIGHEST_PRECEDENCE + 10) for every dispatcher type, mapped to /*", order)
        }

    /**
     * The emission point: the filter's completion listener, fired by the container at request destruction
     * - after the error dispatch and after async completion - so the logged status is the response's
     * FINAL one, not a pre-rendering value. See the emission-point section of [RequestLoggingFilter].
     */
    @Bean
    fun requestLoggingExchangeCompletionListener(filter: RequestLoggingFilter): ServletListenerRegistrationBean<ServletRequestListener> {
        log.debug("Endpoint logging registered the exchange completion listener - the emission point, fired by the container at request destruction")
        return ServletListenerRegistrationBean(filter.exchangeCompletionListener())
    }

    companion object {
        /** The wiring report of the class KDoc, at DEBUG and TRACE; the exchange lines have their own logger. */
        private val log = LoggerFactory.getLogger(RequestLoggingAutoConfiguration::class.java)
    }
}
