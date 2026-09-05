package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.MdcScope
import io.micrometer.context.ContextRegistry
import io.micrometer.context.ThreadLocalAccessor
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Bridges one `endpoint_*` MDC entry into Micrometer's context-propagation machinery: the filter writes
 * the exchange identity into the REACTOR CONTEXT (see the `contextWrite` in `RequestLoggingWebFilter`),
 * and with automatic context propagation active (`Hooks.enableAutomaticContextPropagation()` - Boot
 * calls it only for `spring.reactor.context-propagation=auto`, NOT by library presence; the default is
 * `limited`), this accessor restores the value into the thread-local MDC around every operator - so
 * application logs INSIDE handlers carry `endpoint_request_id`/`endpoint_method`/`endpoint_route`, the
 * parity feature the servlet twin gets from its chain-wide [MdcScope].
 *
 * The accessor's [key] IS the MDC key, which is also the Reactor context key the filter writes - one
 * name, three coordinated places.
 */
internal class MdcEntryThreadLocalAccessor(
    private val mdcKey: String,
) : ThreadLocalAccessor<String> {
    override fun key(): Any = mdcKey

    override fun getValue(): String? = MDC.get(mdcKey)

    override fun setValue(value: String) {
        MDC.put(mdcKey, value)
    }

    override fun setValue() {
        MDC.remove(mdcKey)
    }
}

/**
 * Registers the three `endpoint_*` accessors with the global [ContextRegistry], idempotently - the
 * registry is JVM-global, and a context refresh (or a second test context) must not register duplicates.
 * Invoked by the auto-configuration when `context-propagation` is on the classpath. Library presence
 * opts the ACCESSORS in (keeping the `endpoint-logging.*` configuration identical to the servlet
 * twin's), but restoration around every operator additionally needs Reactor's AUTOMATIC propagation
 * mode - see [warnUnlessAutomaticPropagation].
 */
internal object EndpointMdcContextPropagation {
    val KEYS = listOf(MdcKeys.REQUEST_ID, MdcKeys.REQUEST_METHOD, MdcKeys.ROUTE)

    /** Boot's property deciding whether thread-locals are restored around EVERY Reactor operator. */
    const val PROPAGATION_MODE_PROPERTY = "spring.reactor.context-propagation"

    private val internalLog = LoggerFactory.getLogger(EndpointMdcContextPropagation::class.java)

    fun registerAccessors(registry: ContextRegistry = ContextRegistry.getInstance()) {
        KEYS.forEach { key ->
            if (registry.threadLocalAccessors.none { it.key() == key }) {
                registry.registerThreadLocalAccessor(MdcEntryThreadLocalAccessor(key))
            }
        }
    }

    /**
     * Validates the handler-MDC prerequisite at startup: Spring Boot enables
     * `Hooks.enableAutomaticContextPropagation()` only for [PROPAGATION_MODE_PROPERTY]`=auto` - its
     * default `limited` restores thread-locals around `tap`/`handle` operators only, so a log statement
     * in an ordinary `map` would carry no `endpoint_*` identity although the accessors are
     * registered. Registering accessors while the mode is not `auto`
     * is therefore called out loudly and once, at startup - a host that enables the hook
     * programmatically instead can ignore the warning.
     */
    fun warnUnlessAutomaticPropagation(configuredMode: String?) {
        if (!"auto".equals(configuredMode, ignoreCase = true)) {
            internalLog.warn(
                "endpoint-logging registered the endpoint_* MDC accessors, but {}={} does not enable " +
                    "automatic context propagation - handler-side MDC will not be restored around " +
                    "ordinary Reactor operators. Set {}=auto (or call " +
                    "Hooks.enableAutomaticContextPropagation() yourself) to activate handler MDC parity.",
                PROPAGATION_MODE_PROPERTY,
                configuredMode ?: "<unset, default 'limited'>",
                PROPAGATION_MODE_PROPERTY,
            )
        }
    }
}
