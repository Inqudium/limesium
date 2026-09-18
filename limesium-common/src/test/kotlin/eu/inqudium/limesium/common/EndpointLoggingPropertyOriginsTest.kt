package eu.inqudium.limesium.common

import ch.qos.logback.classic.Level
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The TRACE half of the wiring report: every bound `endpoint-logging.*` value with its origin, shadowed
 * values included - against the [BoundConfigurationProperties] bean a real context fills while binding a
 * probe of the twins' properties class.
 */
class EndpointLoggingPropertyOriginsTest {
    @JvmField
    @RegisterExtension
    val log = CapturedLogger(LOGGER_NAME, Level.TRACE)

    private val contextRunner = ApplicationContextRunner().withUserConfiguration(ProbeConfiguration::class.java)

    @Test
    fun `should name the origin of every bound value, list shadowed values and redact the masking key`() {
        // What is tested: describe over a context with two sources - the runner's inlined properties
        //   setting the logger name and the masking key, a lower source setting the logger name too
        //   and an excluded prefix - with the bound map Boot's own binder recorded.
        // Success criteria: one line per effective value, sorted by name, each naming its source; the
        //   lower logger name reported as shadowed by the higher source's origin; the masking key
        //   rendered redacted; the raw key nowhere in the output.
        // Why it matters: "why is my application.yml value not in effect" is answered by the shadowed
        //   line; a leaked masking key would turn a TRACE report into a secret dump.
        // Given/When
        contextRunner
            .withPropertyValues("endpoint-logging.logger-name=outbound", "endpoint-logging.masking-key=k")
            .withInitializer { it.environment.propertySources.addLast(MapPropertySource("lower", mapOf("endpoint-logging.logger-name" to "base", "endpoint-logging.exclude-path-prefixes[0]" to "/actuator"))) }
            .run { context ->
                val lines = EndpointLoggingPropertyOrigins.describe(boundOf(context).all, context.environment)

                // Then
                assertThat(lines).containsExactly(
                    "Endpoint logging property endpoint-logging.exclude-path-prefixes[0] = /actuator (origin: \"endpoint-logging.exclude-path-prefixes[0]\" from property source \"lower\")",
                    "Endpoint logging property endpoint-logging.logger-name = outbound (origin: \"endpoint-logging.logger-name\" from property source \"test\")",
                    "Endpoint logging property endpoint-logging.logger-name = base (origin: \"endpoint-logging.logger-name\" from property source \"lower\") " +
                        "is shadowed by \"endpoint-logging.logger-name\" from property source \"test\"",
                    "Endpoint logging property endpoint-logging.masking-key = <redacted> (origin: \"endpoint-logging.masking-key\" from property source \"test\")",
                )
                assertThat(lines).noneMatch { it.contains("= k ") }
            }
    }

    @Test
    fun `should map an environment variable to its relaxed property name`() {
        // What is tested: a value set as ENDPOINT_LOGGING_MAX_BODY_BYTES in a system-environment source -
        //   the relaxed-binding path an operator uses in a container.
        // Success criteria: the line names the canonical key endpoint-logging.max-body-bytes and the value.
        // Why it matters: the report must find the value under the name the operator knows from the
        //   reference configuration, not under the environment variable's spelling.
        // Given/When
        contextRunner
            .withInitializer { it.environment.propertySources.addFirst(SystemEnvironmentPropertySource("container", mapOf("ENDPOINT_LOGGING_MAX_BODY_BYTES" to "1024"))) }
            .run { context ->
                val lines = EndpointLoggingPropertyOrigins.describe(boundOf(context).all, context.environment)

                // Then
                assertThat(lines).hasSize(1)
                assertThat(lines.single()).startsWith("Endpoint logging property endpoint-logging.max-body-bytes = 1024 (origin: ").contains("container")
            }
    }

    @Test
    fun `should report at TRACE only, and say so when no key is set anywhere`() {
        // What is tested: report - the level gate and the empty report.
        // Success criteria: with the logger at TRACE, exactly the one "every key is at its default"
        //   line for a context without any endpoint-logging.* key, and the "unavailable" line for a
        //   missing tracker; with the logger raised to DEBUG, nothing at all.
        // Why it matters: silence must mean "TRACE is off", never "nothing to report"; and a report
        //   computed at DEBUG would cost every host a property-source scan for nothing.
        // Given/When: the logger at TRACE
        val logger = LoggerFactory.getLogger(LOGGER_NAME)
        contextRunner.run { context ->
            EndpointLoggingPropertyOrigins.report(logger, context.environment, boundOf(context))
            EndpointLoggingPropertyOrigins.report(logger, context.environment, null)
        }

        // Then
        assertThat(log.events.map { it.formattedMessage }).containsExactly(
            "Endpoint logging properties: no endpoint-logging.* key is set in any property source - every key is at its default",
            "Endpoint logging property origins are unavailable - no BoundConfigurationProperties bean in this context",
        )

        // And when: the logger above TRACE
        log.logger.level = Level.DEBUG
        contextRunner.run { context -> EndpointLoggingPropertyOrigins.report(logger, context.environment, boundOf(context)) }

        // Then: nothing new
        assertThat(log.events).hasSize(2)
    }

    /** The tracker Boot filled while binding the probe - present in every context that enables configuration properties. */
    private fun boundOf(context: ConfigurableApplicationContext): BoundConfigurationProperties = requireNotNull(BoundConfigurationProperties.get(context))

    private companion object {
        const val LOGGER_NAME = "endpoint-logging-property-origins-test"
    }
}

/** A stand-in for the twins' properties class: the keys the tests set, bound under the shared prefix. */
@ConfigurationProperties("endpoint-logging")
internal data class ProbeProperties(
    val loggerName: String = "endpoint-http-exchange",
    val excludePathPrefixes: List<String> = emptyList(),
    val maxBodyBytes: Int = 16384,
    val maskingKey: String = "",
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProbeProperties::class)
internal class ProbeConfiguration
