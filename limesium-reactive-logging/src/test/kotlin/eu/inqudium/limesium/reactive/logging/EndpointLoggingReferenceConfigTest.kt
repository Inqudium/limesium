package eu.inqudium.limesium.reactive.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource

/**
 * Lockstep between the TWO reference configurations and THIS module's [RequestLoggingProperties] - the
 * configuration-identity guarantee of the duplication. The repository-shared
 * `/docs/endpoint-logging-reference.yml` is bound against this module's properties class (the
 * cross-stack proof that the shared namespace is identical, key for key and default for default), and
 * this module's OWN `docs/endpoint-logging-reference.yml` is bound and key-compared as well: it must
 * document the identical keys plus exactly the one reactive-only `variant` key. Each file is loaded
 * exactly as Boot would load it (YamlPropertySourceLoader + Binder), so what the docs show is what an
 * application.yml would do; a property added, renamed or re-defaulted without both references
 * following - or a documented key that does not exist - fails the build.
 */
class EndpointLoggingReferenceConfigTest {
    // The shared reference reaches this module's test classpath through the declared test
    // resource in the POM; the module's own reference is read from the module directory (the
    // servlet twin reads the shared file the same way).
    private val sharedReferenceSources =
        YamlPropertySourceLoader()
            .load("shared-reference", ClassPathResource("endpoint-logging-reference.yml"))
    private val ownReferenceSources =
        YamlPropertySourceLoader()
            .load("own-reference", FileSystemResource("docs/endpoint-logging-reference.yml"))

    private fun documentedKeys(sources: List<PropertySource<*>>): Set<String> =
        sources
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { it.propertyNames.asList() }
            .filter { it.startsWith("endpoint-logging.") }
            .map { it.removePrefix("endpoint-logging.").replace(Regex("\\[\\d+]"), "") }
            .toSet()

    @Test
    fun `should bind the reference configuration to exactly the built-in defaults`() {
        // What is tested: that every VALUE in the reference YAML is the built-in default.
        // Success criteria: binding the file yields an object equal to RequestLoggingProperties() - the
        //   data-class equality covers every property at once.
        // Why it matters: the reference promises "copy it, and nothing changes"; a drifted default would
        //   silently break that promise for everyone who copies the block.
        // Given/When: both reference YAMLs, bound the way Boot binds an application.yml
        val boundFromSharedReference =
            Binder(ConfigurationPropertySources.from(sharedReferenceSources))
                .bind("endpoint-logging", RequestLoggingProperties::class.java)
                .get()
        val boundFromOwnReference =
            Binder(ConfigurationPropertySources.from(ownReferenceSources))
                .bind("endpoint-logging", RequestLoggingProperties::class.java)
                .get()

        // Then: each is indistinguishable from the untouched defaults
        assertThat(boundFromSharedReference).isEqualTo(RequestLoggingProperties())
        assertThat(boundFromOwnReference).isEqualTo(RequestLoggingProperties())
    }

    @Test
    fun `should document in the own reference exactly the shared keys plus the variant key`() {
        // What is tested: the parity rule of having TWO reference files - this module's own reference
        //   must be the shared namespace plus exactly the one reactive-only key.
        // Success criteria: key set of the own file == key set of the shared file + "variant".
        // Why it matters: a second reference file is a drift surface; this assertion turns the parity
        //   promise in both file headers into a build-breaking contract.
        // Given/When: the endpoint-logging.* keys of both files
        val sharedKeys = documentedKeys(sharedReferenceSources)
        val ownKeys = documentedKeys(ownReferenceSources)

        // Then: identical namespaces apart from the documented reactive-only addition
        assertThat(ownKeys).isEqualTo(sharedKeys + "variant")
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
                "response-headers.includes",
                "response-headers.excludes",
                "response-headers.masked",
                "log-request-body",
                "log-response-body",
                "measure-request-body-size",
                "measure-response-body-size",
                "max-body-bytes",
            )

        // When: the endpoint-logging.* keys are extracted from both loaded references
        // Then: nothing is documented that does not exist, and nothing existing is left undocumented -
        //   the shared file omits the reactive-only key by design, the own file carries it
        assertThat(documentedKeys(sharedReferenceSources)).isEqualTo(knownKeys)
        assertThat(documentedKeys(ownReferenceSources)).isEqualTo(knownKeys + "variant")
    }
}
