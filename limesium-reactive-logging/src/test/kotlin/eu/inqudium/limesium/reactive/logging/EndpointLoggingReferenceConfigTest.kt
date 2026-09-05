package eu.inqudium.limesium.reactive.logging

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
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource

/**
 * Lockstep between the reference configurations and THIS module's [RequestLoggingProperties] - the
 * configuration-identity guarantee of the duplication. The repository-shared
 * `/docs/endpoint-logging-reference.yml` is the ONE documented property semantics for both twins and is
 * bound against this module's properties class (the cross-stack proof that the shared namespace is
 * identical, key for key and default for default); this module's OWN `docs/endpoint-logging-reference.yml`
 * carries exactly the one reactive-only `variant` key and nothing else (a second full copy was a drift
 * surface - architecture review of 2026-09-05, finding 2). Each file is loaded exactly as Boot would load
 * it (YamlPropertySourceLoader + Binder), so what the docs show is what an application.yml would do; a
 * property added, renamed or re-defaulted without the shared reference following - or a documented key
 * that does not exist - fails the build.
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
    fun `should document in the own reference nothing but the variant key`() {
        // What is tested: the single-source rule of the two reference files - the shared file documents
        //   the whole namespace, this module's own file exactly the one reactive-only key.
        // Success criteria: key set of the own file == {"variant"}; the shared file does not document
        //   the variant key.
        // Why it matters: a second full copy of the shared block was a drift surface; this assertion
        //   turns the single-source promise in both file headers into a build-breaking contract.
        // Given/When: the endpoint-logging.* keys of both files
        val sharedKeys = documentedKeys(sharedReferenceSources)
        val ownKeys = documentedKeys(ownReferenceSources)

        // Then: the own file adds the one key the shared file leaves out
        assertThat(ownKeys).containsExactly("variant")
        assertThat(sharedKeys).doesNotContain("variant")
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

        // When: the endpoint-logging.* keys are extracted from both loaded references
        // Then: nothing is documented that does not exist, and nothing existing is left undocumented -
        //   the shared file carries the whole shared namespace, the own file the reactive-only key
        assertThat(documentedKeys(sharedReferenceSources)).isEqualTo(knownKeys)
        assertThat(documentedKeys(ownReferenceSources)).isEqualTo(setOf("variant"))
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
