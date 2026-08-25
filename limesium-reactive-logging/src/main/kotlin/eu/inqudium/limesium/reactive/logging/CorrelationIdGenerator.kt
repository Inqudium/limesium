package eu.inqudium.limesium.reactive.logging

import java.util.UUID

/**
 * Supplies the correlation id for a request that did not carry one in its
 * [correlation header][RequestLoggingProperties.correlationIdHeader].
 *
 * Injectable for the same reason as [NanoTimeSource]: randomness is ambient state, and tests must be able
 * to pin the generated id to a known value without any mocking library.
 */
fun interface CorrelationIdGenerator {
    fun nextCorrelationId(): String

    companion object {
        /** The production default: a random type-4 UUID per request. */
        val RANDOM_UUID: CorrelationIdGenerator = CorrelationIdGenerator { UUID.randomUUID().toString() }
    }
}
