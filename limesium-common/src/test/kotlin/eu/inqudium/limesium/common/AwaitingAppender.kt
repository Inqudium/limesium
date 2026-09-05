package eu.inqudium.limesium.common

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Shared by the twins through limesium-common's test-jar (architecture review of 2026-09-05, finding 3).

/**
 * Captures events from container threads and lets integration tests await their arrival event-driven: one
 * permit per event, a bounded [tryAcquire][Semaphore.tryAcquire] instead of any sleep. On timeout the
 * events captured so far are returned, so the caller's size assertion fails WITH the actual content.
 *
 * After the awaited count a short SETTLE window follows: a further permit arriving within it is included
 * in the returned list, so an exactly-once assertion (`awaitEvents(1).single()`) fails on a late duplicate
 * instead of returning before it lands (code analysis of 2026-09-05, finding 10). The window is a bounded
 * negative wait on the semaphore, not a synchronization sleep - it never delays a test whose events are
 * already complete by more than [SETTLE_MILLIS].
 */
class AwaitingAppender : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    private val arrivals = Semaphore(0)

    override fun append(event: ILoggingEvent) {
        // Logback captures the MDC LAZILY on the first getMDCPropertyMap() access, from the CALLING
        // thread - handing the event to the awaiting test thread without this call races that first
        // access, and a test-thread win captures the test thread's (empty) MDC instead of the emitting
        // thread's. prepareForDeferredProcessing() is logback's own answer for cross-thread event
        // inspection (AsyncAppender does the same) and pins the snapshot to the emitting thread here.
        event.prepareForDeferredProcessing()
        events.add(event)
        arrivals.release()
    }

    fun awaitEvents(count: Int): List<ILoggingEvent> {
        if (arrivals.tryAcquire(count, 5, TimeUnit.SECONDS)) {
            // Settle: a duplicate emitted by a second destruction, a backstop or a re-wired exchange
            // lands here and becomes visible to the caller's exactly-once assertion.
            arrivals.tryAcquire(1, SETTLE_MILLIS, TimeUnit.MILLISECONDS)
        }
        return events.toList()
    }

    private companion object {
        const val SETTLE_MILLIS = 100L
    }
}
