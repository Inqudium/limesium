package eu.inqudium.limesium.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory

// Shared by the twins through limesium-common's test-jar (code-style audit of 2026-09-05, pattern S2:
// the per-class Logback fixture had been copied into 24 test classes).

/**
 * Captures what one Logback logger emits during a test: registered with `@RegisterExtension`, the
 * extension attaches an [AwaitingAppender] to the logger named [loggerName] before each test (at
 * [level], so a host `logback-test.xml` cannot gate the line away) and detaches it afterwards. The
 * appender is event-driven ([awaitEvents]) for integration tests and synchronous for unit tests, so one
 * fixture serves both. [logger] stays reachable for tests that move the level mid-test.
 */
class CapturedLogger(
    private val loggerName: String,
    private val level: Level = Level.INFO,
) : BeforeEachCallback,
    AfterEachCallback {
    private val appender = AwaitingAppender()

    /** The Logback logger under capture - for level changes inside a test. */
    lateinit var logger: Logger
        private set

    /** Every event captured so far, in emission order. */
    val events: List<ILoggingEvent>
        get() = appender.events

    /** See [AwaitingAppender.awaitEvents]. */
    fun awaitEvents(count: Int): List<ILoggingEvent> = appender.awaitEvents(count)

    /** Forgets the events captured so far - for a test that sets up and then observes a second exchange. */
    fun clear() {
        appender.events.clear()
    }

    override fun beforeEach(context: ExtensionContext) {
        logger = LoggerFactory.getLogger(loggerName) as Logger
        appender.start()
        logger.addAppender(appender)
        logger.level = level
    }

    override fun afterEach(context: ExtensionContext) {
        logger.detachAppender(appender)
        appender.stop()
    }
}

/** The structured key-values of an event as a map - what every exchange-line assertion reads. */
fun ILoggingEvent.keyValues(): Map<String, Any?> = keyValuePairs?.associate { it.key to it.value } ?: emptyMap()
