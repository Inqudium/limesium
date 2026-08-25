package eu.inqudium.limesium.reactive.logging

import io.micrometer.context.ContextRegistry

/**
 * Test guard for the JVM-global [ContextRegistry]: [snapshot] records which of the module-owned
 * `endpoint_*` accessors a HOST had already registered, [restore] removes only the ones a test added -
 * a pre-existing host accessor is preserved, never deleted blindly. Without this, accessors registered
 * by one test context leak into every later test in the JVM (finding 10 of the internal
 * analysis).
 */
internal class EndpointAccessorRegistryGuard(
    private val registry: ContextRegistry = ContextRegistry.getInstance(),
) {
    private var preExisting: Set<String> = emptySet()

    fun snapshot() {
        preExisting = registeredKeys()
    }

    fun restore() {
        (registeredKeys() - preExisting).forEach { registry.removeThreadLocalAccessor(it) }
    }

    private fun registeredKeys(): Set<String> =
        registry.threadLocalAccessors
            .map { it.key() }
            .filter { it in EndpointMdcContextPropagation.KEYS }
            .map { it as String }
            .toSet()
}
