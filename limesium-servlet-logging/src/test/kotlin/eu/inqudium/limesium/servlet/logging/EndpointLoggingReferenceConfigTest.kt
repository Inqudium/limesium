package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyLogMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource

/**
 * Lockstep between the repository-shared `/docs/endpoint-logging-reference.yml` and [RequestLoggingProperties]: the reference
 * documents every property WITH ITS DEFAULT, so this test fails whenever a property is added, renamed or
 * re-defaulted without the reference following - and whenever the reference documents a key that does not
 * exist. The file is loaded exactly as Boot would load it (YamlPropertySourceLoader + Binder), so what the
 * docs show is what an application.yml would do.
 */
class EndpointLoggingReferenceConfigTest {
    // The shared reference reaches this module's test classpath through the declared test resource in
    // the POM (like the reactive twin) - no dependency on the working directory of the test run.
    private val referenceSources =
        YamlPropertySourceLoader().load("reference", ClassPathResource("endpoint-logging-reference.yml"))

    @Test
    fun `should bind the reference configuration to exactly the built-in defaults`() {
        // What is tested: that every VALUE in the reference YAML is the built-in default.
        // Success criteria: binding the file yields an object equal to RequestLoggingProperties() - the
        //   data-class equality covers every property at once.
        // Why it matters: the reference promises "copy it, and nothing changes"; a drifted default would
        //   silently break that promise for everyone who copies the block.
        // Given/When: the module's own reference YAML, bound the way Boot binds an application.yml
        val bound =
            Binder(ConfigurationPropertySources.from(referenceSources))
                .bind("endpoint-logging", RequestLoggingProperties::class.java)
                .get()

        // Then: it is indistinguishable from the untouched defaults
        assertThat(bound).isEqualTo(RequestLoggingProperties())
    }

    @Test
    fun `should document only keys that actually exist`() {
        // What is tested: that the reference contains no stale or misspelled keys - the Binder silently
        //   IGNORES unknown keys, so the equality test above cannot catch a typo on its own.
        // Success criteria: every endpoint-logging.* key in the YAML is one of the known property names.
        // Why it matters: a documented key that does not bind is worse than an undocumented one - readers
        //   copy it and believe it works.
        // Given: the kebab-case names of all properties
        val knownKeys =
            setOf(
                "enabled",
                "logger-name",
                "correlation-id-header",
                "include-query-string",
                "log-request-start",
                "include-path-patterns",
                "exclude-path-prefixes",
                "slow-request-threshold",
                "request-headers.includes",
                "request-headers.excludes",
                "request-headers.masked",
                "request-headers.unmasked",
                "response-headers.includes",
                "response-headers.excludes",
                "response-headers.masked",
                "response-headers.unmasked",
                "log-request-body",
                "log-response-body",
                "measure-request-body-size",
                "measure-response-body-size",
                "max-body-bytes",
                "masking-key",
            )

        // When: the endpoint-logging.* keys are extracted from the loaded reference
        val documentedKeys =
            referenceSources
                .filterIsInstance<EnumerablePropertySource<*>>()
                .flatMap { it.propertyNames.asList() }
                .filter { it.startsWith("endpoint-logging.") }
                .map { it.removePrefix("endpoint-logging.").replace(Regex("\\[\\d+]"), "") }
                .toSet()

        // Then: nothing is documented that does not exist, and nothing existing is left undocumented
        assertThat(documentedKeys).isEqualTo(knownKeys)
    }

    @Test
    fun `should bind the body modes by their kebab-case names and refuse the former booleans`() {
        // What is tested: the documented spellings `never` / `on-failure` / `always` bind (Boot's lenient
        //   enum conversion), and a leftover `true` from the boolean era fails the binding loudly.
        // Success criteria: the two modes bound; `true` raises a BindException.
        // Why it matters: a silently ignored `true` would switch body logging OFF for an operator who
        //   believed it on - the migration must be visible at startup.
        // Given
        fun bind(vararg pairs: Pair<String, String>) =
            Binder(ConfigurationPropertySources.from(listOf(MapPropertySource("test", pairs.toMap()))))
                .bind("endpoint-logging", RequestLoggingProperties::class.java)
                .get()

        // When
        val bound = bind("endpoint-logging.log-request-body" to "on-failure", "endpoint-logging.log-response-body" to "always")

        // Then
        assertThat(bound.logRequestBody).isEqualTo(BodyLogMode.ON_FAILURE)
        assertThat(bound.logResponseBody).isEqualTo(BodyLogMode.ALWAYS)
        assertThatThrownBy { bind("endpoint-logging.log-response-body" to "true") }.isInstanceOf(BindException::class.java)
    }
}
