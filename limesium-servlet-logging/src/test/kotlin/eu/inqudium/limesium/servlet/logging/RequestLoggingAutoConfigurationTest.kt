package eu.inqudium.limesium.servlet.logging

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Contract of [RequestLoggingAutoConfiguration]: present by default in a servlet web application,
 * removable by property, and every bean overridable by the host. Uses Boot's [WebApplicationContextRunner]
 * (a real context, no mocking); kept FLAT deliberately - see the Spring Boot test isolation caveat on
 * nested classes.
 */
class RequestLoggingAutoConfigurationTest {
    private val contextRunner =
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestLoggingAutoConfiguration::class.java))

    @Test
    fun `should register the filter its completion listener and the defaults in a servlet web application`() {
        // Given/When: the module's auto-configuration alone, in a servlet web context
        contextRunner.run { context ->
            // Then: the filter bean, its registration, the completion listener carrying the emission, and
            //   the two injectable defaults are present
            assertThat(context).hasSingleBean(RequestLoggingFilter::class.java)
            assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
            assertThat(context).hasSingleBean(ServletListenerRegistrationBean::class.java)
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            @Suppress("UNCHECKED_CAST")
            val registration = context.getBean(FilterRegistrationBean::class.java) as FilterRegistrationBean<RequestLoggingFilter>
            assertThat(registration.filter).isSameAs(context.getBean(RequestLoggingFilter::class.java))
        }
    }

    @Test
    fun `should back off entirely when disabled by property`() {
        // Given/When: the context with endpoint-logging.enabled=false
        contextRunner.withPropertyValues("endpoint-logging.enabled=false").run { context ->
            // Then: nothing of this module is in the context
            assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(ServletListenerRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingFilter::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingProperties::class.java)
        }
    }

    @Test
    fun `should bind the properties namespace`() {
        // Given/When: endpoint-logging.* keys bound into the properties class
        contextRunner
            .withPropertyValues(
                "endpoint-logging.max-body-bytes=128",
                "endpoint-logging.log-request-body=true",
                "endpoint-logging.log-request-start=true",
                "endpoint-logging.exclude-path-prefixes=/actuator/health,/internal",
                "endpoint-logging.request-headers.includes=*",
                "endpoint-logging.request-headers.excludes=Cookie",
                "endpoint-logging.request-headers.masked=Authorization",
                "endpoint-logging.slow-request-threshold=250ms",
            ).run { context ->
                // Then: the bound properties carry the configured values
                val properties = context.getBean(RequestLoggingProperties::class.java)
                assertThat(properties.maxBodyBytes).isEqualTo(128)
                assertThat(properties.logRequestBody).isTrue()
                assertThat(properties.logRequestStart).isTrue()
                assertThat(properties.excludePathPrefixes).containsExactly("/actuator/health", "/internal")
                assertThat(properties.requestHeaders.includes).containsExactly("*")
                assertThat(properties.requestHeaders.excludes).containsExactly("Cookie")
                assertThat(properties.requestHeaders.masked).containsExactly("Authorization")
                assertThat(properties.slowRequestThreshold.toMillis()).isEqualTo(250)
            }
    }

    @Test
    fun `should register the fail-open counters in a host-provided meter registry`() {
        contextRunner.withUserConfiguration(MeterRegistryHostConfig::class.java).run { context ->
            // Given/When: a host MeterRegistry; the filter bean created against it
            context.getBean(RequestLoggingFilter::class.java)

            // Then: all three fail-open stages are pre-registered there (emission, arrival, wiring)
            val registry = context.getBean(MeterRegistry::class.java)
            assertThat(registry.find(EndpointLoggingMetrics.FAIL_OPEN_METER).counters()).hasSize(3)
        }
    }

    @Test
    fun `should let a host-defined time source win over the default`() {
        // Given/When: a host-defined NanoTimeSource bean beside the auto-configuration
        contextRunner.withUserConfiguration(PinnedTimeSourceHostConfig::class.java).run { context ->
            // Then: the host bean is the only one, the auto-configured default backed off
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context.getBean(NanoTimeSource::class.java)).isSameAs(PINNED_TIME_SOURCE)
        }
    }

    @Test
    fun `should wire a host-defined filter into the registration instead of creating a second one`() {
        // Given/When: a host-defined RequestLoggingFilter bean beside the auto-configuration
        contextRunner.withUserConfiguration(OwnFilterHostConfig::class.java).run { context ->
            // Then: the auto-configured filter backs off, and registration plus completion listener wrap
            //   the HOST's filter - override changes the filter, never the wiring
            assertThat(context).hasSingleBean(RequestLoggingFilter::class.java)
            @Suppress("UNCHECKED_CAST")
            val registration = context.getBean(FilterRegistrationBean::class.java) as FilterRegistrationBean<RequestLoggingFilter>
            assertThat(registration.filter).isSameAs(context.getBean("hostRequestLoggingFilter"))
            assertThat(context).hasSingleBean(ServletListenerRegistrationBean::class.java)
        }
    }
}

// Host configurations live at file level: a @Configuration class local to a test method holds a hidden
// reference to the enclosing test instance, which Spring cannot instantiate as a bean.

private val PINNED_TIME_SOURCE = NanoTimeSource { 42L }

@Configuration(proxyBeanMethods = false)
private class MeterRegistryHostConfig {
    @Bean
    fun hostMeterRegistry(): MeterRegistry = SimpleMeterRegistry()
}

@Configuration(proxyBeanMethods = false)
private class PinnedTimeSourceHostConfig {
    @Bean
    fun hostNanoTimeSource(): NanoTimeSource = PINNED_TIME_SOURCE
}

@Configuration(proxyBeanMethods = false)
private class OwnFilterHostConfig {
    @Bean
    fun hostRequestLoggingFilter(properties: RequestLoggingProperties): RequestLoggingFilter = RequestLoggingFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.RANDOM_UUID, SimpleMeterRegistry())
}
