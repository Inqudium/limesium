package eu.inqudium.limesium.servlet.logging

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ServletRequestListener
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * Registers the [RequestLoggingFilter] in a servlet (Tomcat) Spring Boot application - drop the module on
 * the classpath and every exchange is logged; `endpoint-logging.enabled=false` removes it again.
 *
 * Every bean backs off to a host-provided one: a host may pin [NanoTimeSource] or
 * [CorrelationIdGenerator] (tests do), or define its own [RequestLoggingFilter] bean to take over
 * registration entirely.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "endpoint-logging", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RequestLoggingProperties::class)
class RequestLoggingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingNanoTimeSource(): NanoTimeSource = NanoTimeSource.SYSTEM

    @Bean
    @ConditionalOnMissingBean
    fun requestLoggingCorrelationIdGenerator(): CorrelationIdGenerator = CorrelationIdGenerator.RANDOM_UUID

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
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): RequestLoggingFilter =
        RequestLoggingFilter(properties, nanoTime, correlationIds, meterRegistry.getIfAvailable { SimpleMeterRegistry() })

    /**
     * Runs very early (but not first) in the chain, so the correlation id is in the MDC before other
     * filters log; the offset leaves room for infrastructure that must precede logging (metrics,
     * request-context setup). Referencing the filter bean here keeps Boot from ALSO auto-registering the
     * bare `Filter` bean - a registration bean claims its filter.
     *
     * The `+ 10` is LOAD-BEARING for the trace capture: Boot registers the `ServerHttpObservationFilter`
     * at `Ordered.HIGHEST_PRECEDENCE + 1`, and the chain runs in ASCENDING order, so that filter wraps
     * this one and the tracing bridge's `traceId`/`spanId` are in the MDC when the filter captures them
     * at entry (see [TraceMdcKeys]). Moving this order before `+ 1` would not fail; it would silently
     * strip the trace join from every exchange event. Ordering and scope-around-the-chain are convention,
     * not contract - `RequestLoggingFilterTracingIntegrationTest` pins them against a real bridge (and
     * records where the constant comes from), so a Boot upgrade that changes either breaks the build.
     */
    @Bean
    fun requestLoggingFilterRegistration(filter: RequestLoggingFilter): FilterRegistrationBean<RequestLoggingFilter> =
        FilterRegistrationBean(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 10
        }

    /**
     * The emission point: the filter's completion listener, fired by the container at request destruction
     * - after the error dispatch and after async completion - so the logged status is the one the client
     * received. See the emission-point section of [RequestLoggingFilter].
     */
    @Bean
    fun requestLoggingExchangeCompletionListener(filter: RequestLoggingFilter): ServletListenerRegistrationBean<ServletRequestListener> =
        ServletListenerRegistrationBean(filter.exchangeCompletionListener())
}
