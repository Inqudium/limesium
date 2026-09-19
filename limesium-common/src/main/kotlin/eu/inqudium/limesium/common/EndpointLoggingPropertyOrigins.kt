package eu.inqudium.limesium.common

import org.slf4j.Logger
import org.springframework.boot.context.properties.BoundConfigurationProperties
import org.springframework.boot.context.properties.source.ConfigurationProperty
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.Environment

/**
 * Where each `endpoint-logging.*` value came from - the TRACE half of the auto-configurations' wiring
 * report, one implementation for both twins (ported from the outbound sibling legatium).
 *
 * Boot tracks an [org.springframework.boot.origin.Origin] for every property value it binds (the file
 * and line of a YAML entry, the name of an environment variable, the property source of a command-line
 * argument) and records every leaf it bound into a `@ConfigurationProperties` bean in the
 * [BoundConfigurationProperties] bean. [describe] renders the entries under the `endpoint-logging`
 * prefix as one line each, then asks every property source of the environment - in precedence order -
 * for the same name: a value a later source also holds is SHADOWED, which is the usual answer to "why
 * is my application.yml value not in effect" (a profile file or an environment variable won). Keys no
 * source sets are the class defaults and are not listed; the bean line of the DEBUG report shows them.
 *
 * The masking key is a secret and rendered redacted, whatever its source.
 */
internal object EndpointLoggingPropertyOrigins {
    /** The prefix both twins' properties bind under, as Boot's property name. */
    val PREFIX: ConfigurationPropertyName = ConfigurationPropertyName.of("endpoint-logging")

    private val MASKING_KEY = ConfigurationPropertyName.of("endpoint-logging.masking-key")

    /**
     * Logs [describe] line by line at TRACE on [log] - nothing is computed unless TRACE is enabled - or
     * one line saying - first in plain words, then in Spring terms - that the origins are unavailable when
     * the context has no [BoundConfigurationProperties] bean ([bound] null: a host that bound the
     * properties without `@EnableConfigurationProperties`).
     */
    fun report(
        log: Logger,
        environment: Environment,
        bound: BoundConfigurationProperties?,
    ) {
        if (!log.isTraceEnabled) {
            return
        }
        if (bound == null) {
            log.trace(
                "Endpoint logging cannot tell where its endpoint-logging.* values came from (which file, environment " +
                    "variable or command-line argument set them); the values above are in effect nonetheless. " +
                    "Endpoint logging property origins are unavailable - no BoundConfigurationProperties bean in this context",
            )
            return
        }
        describe(bound.all, environment).forEach(log::trace)
    }

    /**
     * One line per `endpoint-logging.*` value in [bound] (the effective value and its origin), followed by
     * one line per value of the same name a lower-precedence source of [environment] also holds - indented
     * with `+- ` under the effective value, so the shadowed values read as a tree; or a single line saying
     * that no key is set anywhere. Sorted by name, so a report reads like the reference configuration.
     */
    fun describe(
        bound: Map<ConfigurationPropertyName, ConfigurationProperty>,
        environment: Environment,
    ): List<String> {
        val ours = bound.values.filter { PREFIX.isAncestorOf(it.name) }.sortedBy { it.name }
        if (ours.isEmpty()) {
            return listOf("Endpoint logging properties: no endpoint-logging.* key is set in any property source - every key is at its default")
        }
        val sources = ConfigurationPropertySources.get(environment).toList()
        return buildList {
            for (property in ours) {
                val effectiveOrigin = property.origin?.toString() ?: "unknown origin"
                add("Endpoint logging property ${property.name} = ${render(property)} (origin: $effectiveOrigin)")
                // The first source holding the name is the one the binder took; every later one is shadowed.
                sources
                    .mapNotNull { it.getConfigurationProperty(property.name) }
                    .drop(1)
                    .forEach { shadowed ->
                        add(
                            "+- Endpoint logging property ${shadowed.name} = ${render(shadowed)} " +
                                "(origin: ${shadowed.origin?.toString() ?: "unknown origin"}) is shadowed by $effectiveOrigin",
                        )
                    }
            }
        }
    }

    private fun render(property: ConfigurationProperty): String = if (property.name == MASKING_KEY) "<redacted>" else property.value.toString()
}
