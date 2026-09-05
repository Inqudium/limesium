package eu.inqudium.limesium.common

/**
 * Injectable monotonic time, used exclusively for measuring the duration of an HTTP exchange.
 *
 * Time is an injected dependency, not ambient state: the filter never calls [System.nanoTime] itself, so
 * tests drive durations deterministically (typically from an `AtomicLong`). The values are monotonic
 * nanoseconds with an arbitrary origin - only differences are meaningful, never wall-clock instants.
 * Timestamps on the emitted log lines come from the logging backend, which keeps the two time domains
 * separate by construction.
 */
fun interface NanoTimeSource {
    fun nanoTime(): Long

    companion object {
        /** The production default; the single place in this module that reads the system's monotonic clock. */
        @JvmField
        val SYSTEM: NanoTimeSource = NanoTimeSource { System.nanoTime() }
    }
}
