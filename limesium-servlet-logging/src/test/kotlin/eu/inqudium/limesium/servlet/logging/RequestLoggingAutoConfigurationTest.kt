package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyLogMode
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
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
    fun `should let a host HeaderValueMasker back the default masker off`() {
        // What is tested: the masker is a @ConditionalOnMissingBean bean like the time source and the
        //   id generator - a host pins its own rendering (a keyed HMAC, a fixed `***`) once.
        // Success criteria: with a host bean the context holds exactly one HeaderValueMasker, the
        //   host's, and the module's default backed off.
        // Why it matters: the properties decide which values are masked; the bean is the host's only
        //   handle on HOW - it must win over the default in the shipped configuration.
        // Given/When: a host masker beside the auto-configuration
        contextRunner.withUserConfiguration(MaskerHostConfig::class.java).run { context ->
            // Then
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("x")).isEqualTo("***")
        }
    }

    @Test
    fun `should register the filter its completion listener and the defaults in a servlet web application`() {
        // What is tested: the auto-configuration alone in a servlet web context.
        // Success criteria: one filter, one filter registration wrapping it, one listener
        //   registration carrying the emission, and the three injectable defaults.
        // Why it matters: the emission lives in the completion listener, not the filter; a missing
        //   listener registration would wire a filter that never logs.
        // Given/When: the module's auto-configuration alone, in a servlet web context
        contextRunner.run { context ->
            // Then: the filter bean, its registration, the completion listener carrying the emission, and
            //   the two injectable defaults are present
            assertThat(context).hasSingleBean(RequestLoggingFilter::class.java)
            assertThat(context).hasSingleBean(FilterRegistrationBean::class.java)
            assertThat(context).hasSingleBean(ServletListenerRegistrationBean::class.java)
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context).hasSingleBean(CorrelationIdGenerator::class.java)
            assertThat(context).hasSingleBean(HeaderValueMasker::class.java)
            @Suppress("UNCHECKED_CAST")
            val registration = context.getBean(FilterRegistrationBean::class.java) as FilterRegistrationBean<RequestLoggingFilter>
            assertThat(registration.filter).isSameAs(context.getBean(RequestLoggingFilter::class.java))
        }
    }

    @Test
    fun `should key the default masker from the masking-key property`() {
        // What is tested: the property path to a guess-proof fingerprint - no host bean needed.
        // Success criteria: with masking-key set, the masker bean renders the keyed fingerprint, not the
        //   unkeyed default.
        // Why it matters: keying is the documented answer to "masked is not a security boundary for
        //   guessable values"; it must be reachable from application.yml alone.
        // Given/When
        contextRunner.withPropertyValues("endpoint-logging.masking-key=k").run { context ->
            // Then
            assertThat(context.getBean(HeaderValueMasker::class.java).mask("secret-token")).isEqualTo("12:18da04f7cd594ea3")
        }
    }

    @Test
    fun `should back off entirely when disabled by property`() {
        // What is tested: endpoint-logging.enabled=false on the servlet stack.
        // Success criteria: no registrations, no filter, no defaults, no properties bean.
        // Why it matters: the master switch must remove the servlet registrations too, or a
        //   disabled module would still sit in the container's filter chain.
        // Given/When: the context with endpoint-logging.enabled=false
        contextRunner.withPropertyValues("endpoint-logging.enabled=false").run { context ->
            // Then: nothing of this module is in the context
            assertThat(context).doesNotHaveBean(FilterRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(ServletListenerRegistrationBean::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingFilter::class.java)
            assertThat(context).doesNotHaveBean(NanoTimeSource::class.java)
            assertThat(context).doesNotHaveBean(HeaderValueMasker::class.java)
            assertThat(context).doesNotHaveBean(RequestLoggingProperties::class.java)
        }
    }

    @Test
    fun `should bind the properties namespace`() {
        // What is tested: eight endpoint-logging.* keys bound into the properties class, including
        //   the nested header section and a duration.
        // Success criteria: every configured value comes back typed - the enum, the list, the
        //   duration in milliseconds.
        // Why it matters: the namespace is the operator's contract; a key that silently failed to
        //   bind would leave a default in force the operator believes changed.
        // Given/When: endpoint-logging.* keys bound into the properties class
        contextRunner
            .withPropertyValues(
                "endpoint-logging.max-body-bytes=128",
                "endpoint-logging.log-request-body=always",
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
                assertThat(properties.logRequestBody).isEqualTo(BodyLogMode.ALWAYS)
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
        // What is tested: the filter bean created against a host MeterRegistry.
        // Success criteria: all three fail-open stages are pre-registered there.
        // Why it matters: a rate() alert must see the zero before the first occurrence, and in the
        //   HOST registry, where it is exported.
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
        // What is tested: a host-defined NanoTimeSource bean beside the auto-configuration.
        // Success criteria: the host bean is the only one; the default backed off.
        // Why it matters: the time source is an injectable seam for tests and hosts; two beans
        //   would make the injection ambiguous at start.
        // Given/When: a host-defined NanoTimeSource bean beside the auto-configuration
        contextRunner.withUserConfiguration(PinnedTimeSourceHostConfig::class.java).run { context ->
            // Then: the host bean is the only one, the auto-configured default backed off
            assertThat(context).hasSingleBean(NanoTimeSource::class.java)
            assertThat(context.getBean(NanoTimeSource::class.java)).isSameAs(PINNED_TIME_SOURCE)
        }
    }

    @Test
    fun `should wire a host-defined filter into the registration instead of creating a second one`() {
        // What is tested: a host-defined RequestLoggingFilter bean beside the auto-configuration.
        // Success criteria: one filter - the host's - wrapped by the registration, with the
        //   completion listener still present.
        // Why it matters: overriding the filter must change the filter and keep the wiring; a
        //   second filter would log every exchange twice.
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
    fun hostRequestLoggingFilter(properties: RequestLoggingProperties): RequestLoggingFilter = RequestLoggingFilter(properties, NanoTimeSource.SYSTEM, CorrelationIdGenerator.DEFAULT, SimpleMeterRegistry())
}

@Configuration(proxyBeanMethods = false)
private class MaskerHostConfig {
    @Bean
    fun hostMasker(): HeaderValueMasker = HeaderValueMasker { "***" }
}
