package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import eu.inqudium.limesium.common.BodyLogMode
import eu.inqudium.limesium.common.CapturedLogger
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.EndpointLoggingMetrics
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.RequestLoggingProperties
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.web.filter.ServerHttpObservationFilter

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

    /** The auto-configuration's own logger - the wiring report - captured at TRACE for every test. */
    @JvmField
    @RegisterExtension
    val wiringLog = CapturedLogger(RequestLoggingAutoConfiguration::class.java.name, Level.TRACE)

    @Test
    fun `should report at DEBUG that it is enabled and what it wired`() {
        // What is tested: the wiring report on the auto-configuration's own logger - the line for the
        //   active switch, the filter bean with its properties (masking key redacted), the filter
        //   registration with its order, the completion listener, and the observation line for a
        //   context without Boot's server observation.
        // Success criteria: the DEBUG events contain the enabled line, the registration line naming
        //   HIGHEST_PRECEDENCE + 10, the listener line, the no-observation line, and the bean line with
        //   the bound logger name and a redacted masking key - the raw key nowhere.
        // Why it matters: an operator asking "is the module on, and is the filter really in the chain?"
        //   reads the answer from the host's log at DEBUG.
        // Given/When
        contextRunner.withPropertyValues("endpoint-logging.masking-key=k").run { context ->
            assertThat(context).hasNotFailed()

            // Then
            val messages = wiringLog.events.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
            assertThat(messages).contains(
                "Endpoint logging is enabled - the auto-configuration is active (endpoint-logging.enabled is not false)",
                "Endpoint logging registered the filter registration - the filter runs at order -2147483638 (HIGHEST_PRECEDENCE + 10) for every dispatcher type, mapped to /*",
                "Endpoint logging registered the exchange completion listener - the emission point, fired by the container at request destruction",
                "Endpoint logging found no server observation - Boot's observation auto-configuration is not active (no ObservationRegistry bean, or the observation module is absent): exchanges run outside any server observation; the exchange line still carries the trace context of an incoming traceparent",
            )
            assertThat(messages).anySatisfy { message ->
                assertThat(message)
                    .startsWith("Endpoint logging registered its RequestLoggingFilter bean with RequestLoggingProperties(")
                    .contains("loggerName=endpoint-http-exchange")
                    .contains("maskingKey=<redacted>")
                    .doesNotContain("maskingKey=k")
            }
        }
    }

    @Test
    fun `should report at DEBUG whether Boot's server observation wraps the filter and whether a bridge traces it`() {
        // What is tested: the observation line of the wiring report against Boot's REAL observation
        //   and tracing auto-configurations - with a Brave bridge, with the observation registry alone,
        //   and with a host that registered the observation filter itself behind this filter.
        // Success criteria: with tracing, the line names Boot's order (HIGHEST_PRECEDENCE + 1) against
        //   this filter's (+ 10), "inside", and traced handler lines; without a tracer, the measured-
        //   only line; with the host's registration at order 0, "outside" and the duration not part of
        //   the measurement.
        // Why it matters: whether the exchange runs inside the server span and whether handler lines
        //   carry a traceId has no property; this line is where an operator reads it - and the test
        //   breaks when a Boot upgrade moves or renames the observation filter the detection looks for.
        // Given: Boot's observation auto-configurations
        val observed = contextRunner.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration::class.java, WebMvcObservationAutoConfiguration::class.java))

        // When: with a tracing bridge
        observed
            .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration::class.java, MicrometerTracingAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(wiringLog.events.map { it.formattedMessage }).contains("Endpoint logging found Boot's server observation with Micrometer Tracing - the observation filter is registered at order -2147483647 against this filter's -2147483638, so every exchange runs inside the server observation: the handler lines carry the bridge's traceId and spanId, the exchange line the trace context of the incoming traceparent")
            }

        // And when: the observation registry alone
        wiringLog.clear()
        observed.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(wiringLog.events.map { it.formattedMessage }).contains("Endpoint logging found Boot's server observation but no Micrometer Tracing - the observation filter is registered at order -2147483647 against this filter's -2147483638, so every exchange runs inside the server observation: exchanges are measured, no server span is opened, and only the exchange line carries a trace context - that of the incoming traceparent")
        }

        // And when: a host registered the observation filter itself, ordered behind this filter
        wiringLog.clear()
        contextRunner.withUserConfiguration(HostObservationFilterConfiguration::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(wiringLog.events.map { it.formattedMessage }).contains("Endpoint logging found Boot's server observation but no Micrometer Tracing - the observation filter is registered at order 0 against this filter's -2147483638, so the exchange runs outside the server observation and its duration is not part of the measurement: exchanges are measured, no server span is opened, and only the exchange line carries a trace context - that of the incoming traceparent")
        }
    }

    @Test
    fun `should report at TRACE where every endpoint-logging value came from`() {
        // What is tested: the TRACE half of the wiring report - the origin of each bound
        //   endpoint-logging.* value, a shadowed value from a lower-precedence source, the redacted
        //   masking key, and the empty report when nothing is set.
        // Success criteria: with the logger name and the masking key inlined and a lower source
        //   setting the logger name too, the TRACE events name the runner's inlined source ("test")
        //   for the effective values, mark the lower value as shadowed, render the key redacted and
        //   never raw; with no property set, exactly the one "every key is at its default" line.
        // Why it matters: "which file set this, and why is my value not in effect" is answered from
        //   the host's log at TRACE instead of from the actuator's env endpoint in production.
        // Given/When: two sources, the inlined test properties above a host source
        contextRunner
            .withPropertyValues("endpoint-logging.logger-name=inbound", "endpoint-logging.masking-key=k")
            .withInitializer { it.environment.propertySources.addLast(MapPropertySource("host-defaults", mapOf("endpoint-logging.logger-name" to "base"))) }
            .run { context ->
                assertThat(context).hasNotFailed()

                // Then
                val traces = wiringLog.events.filter { it.level == Level.TRACE }.map { it.formattedMessage }
                assertThat(traces).anySatisfy { line ->
                    assertThat(line).startsWith("Endpoint logging property endpoint-logging.logger-name = inbound (origin: ").contains("from property source \"test\"")
                }
                assertThat(traces).anySatisfy { line ->
                    assertThat(line).startsWith("+- Endpoint logging property endpoint-logging.logger-name = base (origin: ").contains("host-defaults").contains(") is shadowed by ")
                }
                assertThat(traces).anySatisfy { line ->
                    assertThat(line).startsWith("Endpoint logging property endpoint-logging.masking-key = <redacted> (origin: ")
                }
                assertThat(traces).noneMatch { it.contains("masking-key = k") }
            }

        // And when: nothing set at all
        wiringLog.clear()
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val traces = wiringLog.events.filter { it.level == Level.TRACE }.map { it.formattedMessage }
            assertThat(traces).containsExactly("Endpoint logging properties: no endpoint-logging.* key is set in any property source - every key is at its default")
        }
    }

    @Test
    fun `should report nothing when disabled by property`() {
        // What is tested: the wiring report's negative - with the switch off the auto-configuration is
        //   never instantiated, so not even the "enabled" line appears.
        // Success criteria: no event at all on the auto-configuration's logger.
        // Why it matters: the absence of the report is the documented signal for "switched off"; a
        //   line logged from a static initializer or an unconditional bean would make it lie.
        // Given/When
        contextRunner.withPropertyValues("endpoint-logging.enabled=false").run { context ->
            // Then
            assertThat(context).hasNotFailed()
            assertThat(wiringLog.events).isEmpty()
        }
    }

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

/** A host that registers Boot's observation filter itself - at order 0, behind the module's filter. */
@Configuration(proxyBeanMethods = false)
internal class HostObservationFilterConfiguration {
    @Bean
    fun hostObservationFilter(): FilterRegistrationBean<ServerHttpObservationFilter> = FilterRegistrationBean(ServerHttpObservationFilter(ObservationRegistry.NOOP)).apply { order = 0 }
}
