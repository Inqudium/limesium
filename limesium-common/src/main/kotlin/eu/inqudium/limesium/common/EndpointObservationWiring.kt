package eu.inqudium.limesium.common

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.util.ClassUtils

/**
 * The one line of the auto-configurations' wiring report that says whether Boot's SERVER OBSERVATION and
 * a tracing bridge sit around the module's filter - one rendering for both twins.
 *
 * The exchange identity does not depend on either (ADR-0002): the filter parses the incoming
 * `traceparent` itself, so a traced request joins its caller's trace with or without a bridge. What the
 * observation decides is everything else an operator sees next to the exchange line: whether the
 * exchange runs inside the server observation (the servlet twin's `+ 10` order is chosen for that, a
 * host's manual registration can undo it), whether a server span is opened at all, and whether the
 * handler lines - the lines the host writes - carry a `traceId`, which only a bridge puts into their
 * MDC. None of that has an `endpoint-logging.*` key; this line is where it becomes readable at startup.
 *
 * [describe] renders one of the cases from the beans of the context - the twins call it once every
 * singleton exists - and from the [Observation] the twin resolved: on the servlet stack Boot's
 * `ServerHttpObservationFilter` and its order against the module's filter, on the reactive stack the
 * `HttpWebHandlerAdapter`, which observes every request outside all `WebFilter`s. It reports the wiring
 * at context start, not the fate of a request: a host can still filter the observation with an
 * `ObservationPredicate`; the exchange line stays the per-request truth.
 *
 * The classes are named as strings, so the common module compiles without the optional observation and
 * tracing libraries; a class that is not on the classpath counts as "no such bean".
 */
internal object EndpointObservationWiring {
    /** Micrometer Tracing's facade; its bean is the sign that a tracing bridge is configured. */
    const val TRACER = "io.micrometer.tracing.Tracer"

    /** Micrometer's observation registry; its bean is what Boot's server observation is built on. */
    const val OBSERVATION_REGISTRY = "io.micrometer.observation.ObservationRegistry"

    /**
     * Boot's server observation as the twin found it: [placement] is the stack's own clause ("the
     * observation filter is registered at order … against this filter's …"), [wraps] whether the
     * observation encloses the module's filter, so that the exchange runs inside it.
     */
    data class Observation(
        val placement: String,
        val wraps: Boolean,
    )

    /**
     * One line for [observation] (null: none found) against the beans of [beanFactory]. [tracerClass]
     * is [TRACER]; the tests inject a class of their own classpath.
     */
    fun describe(
        beanFactory: ListableBeanFactory,
        observation: Observation?,
        tracerClass: String = TRACER,
    ): String {
        if (observation == null) {
            return "Endpoint logging found no server observation - Boot's observation auto-configuration is not active " +
                "(no ObservationRegistry bean, or the observation module is absent): exchanges run outside any server observation; " +
                "the exchange line still carries the trace context of an incoming traceparent"
        }
        val traced = hasBean(beanFactory, tracerClass)
        val found = if (traced) "Boot's server observation with Micrometer Tracing" else "Boot's server observation but no Micrometer Tracing"
        val extent =
            when {
                observation.wraps -> "so every exchange runs inside the server observation"
                traced -> "so the exchange runs outside the server observation and its duration is not part of the server span"
                else -> "so the exchange runs outside the server observation and its duration is not part of the measurement"
            }
        val consequence =
            if (traced) {
                "the handler lines carry the bridge's traceId and spanId, the exchange line the trace context of the incoming traceparent"
            } else {
                "exchanges are measured, no server span is opened, and only the exchange line carries a trace context - that of the incoming traceparent"
            }
        return "Endpoint logging found $found - ${observation.placement}, $extent: $consequence"
    }

    /** Whether the context holds a bean of [className] - matched by instance once the singletons exist. */
    fun hasBean(
        beanFactory: ListableBeanFactory,
        className: String,
    ): Boolean {
        val type = loadClass(beanFactory, className) ?: return false
        return beanFactory.getBeanNamesForType(type, true, true).isNotEmpty()
    }

    /** [className] through the factory's bean class loader, or null when the optional library is absent. */
    fun loadClass(
        beanFactory: ListableBeanFactory,
        className: String,
    ): Class<*>? {
        val classLoader = (beanFactory as? ConfigurableBeanFactory)?.beanClassLoader ?: EndpointObservationWiring::class.java.classLoader
        return if (ClassUtils.isPresent(className, classLoader)) ClassUtils.forName(className, classLoader) else null
    }
}
