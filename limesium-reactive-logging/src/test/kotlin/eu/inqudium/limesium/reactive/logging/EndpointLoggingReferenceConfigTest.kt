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
 * Lockstep between this module's own `docs/endpoint-logging-reference.yml` and [RequestLoggingVariantProperties],
 * and the single-source rule of the two reference files: the repository-shared
 * `/docs/endpoint-logging-reference.yml` documents the whole shared namespace (bound against the shared
 * `RequestLoggingProperties` by `limesium-common`'s test of this name), this module's own file exactly
 * the one reactive-only `variant` key and nothing else (a second full copy was a drift surface -
 * architecture review of 2026-09-05, finding 2). Each file is loaded exactly as Boot would load it.
 */
class EndpointLoggingReferenceConfigTest {
    // The shared reference reaches this module's test classpath through the declared test resource in
    // the POM; the module's own reference is read from the module directory.
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
    fun `should bind the own reference configuration to exactly the built-in variant default`() {
        // What is tested: that the VALUE of the variant key in this module's reference YAML is the
        //   built-in default.
        // Success criteria: binding the file yields an object equal to RequestLoggingVariantProperties().
        // Why it matters: the reference promises "copy it, and nothing changes"; a drifted default would
        //   silently select a variant for everyone who copies the block.
        // Given/When: the own reference YAML, bound the way Boot binds an application.yml
        val bound =
            Binder(ConfigurationPropertySources.from(ownReferenceSources))
                .bind("endpoint-logging", RequestLoggingVariantProperties::class.java)
                .get()

        // Then
        assertThat(bound).isEqualTo(RequestLoggingVariantProperties())
    }

    @Test
    fun `should document in the own reference nothing but the variant key`() {
        // What is tested: the single-source rule of the two reference files - the shared file documents
        //   the whole namespace, this module's own file exactly the one reactive-only key.
        // Success criteria: key set of the own file == {"variant"}; the shared file does not document
        //   the variant key.
        // Why it matters: a second full copy of the shared block was a drift surface; this assertion
        //   turns the single-source promise in both file headers into a build-breaking contract - and
        //   a `variant` key in the shared file would document a key the servlet twin cannot honour.
        // Given/When: the endpoint-logging.* keys of both files
        val sharedKeys = documentedKeys(sharedReferenceSources)
        val ownKeys = documentedKeys(ownReferenceSources)

        // Then: the own file adds the one key the shared file leaves out
        assertThat(ownKeys).containsExactly("variant")
        assertThat(sharedKeys).doesNotContain("variant")
    }
}
