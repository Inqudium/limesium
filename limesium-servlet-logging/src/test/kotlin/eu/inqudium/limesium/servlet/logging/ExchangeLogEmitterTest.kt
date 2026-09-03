package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.AsyncEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The level/outcome matrix of [ExchangeLogEmitter], driven through the emitter's OWN seam with a
 * hand-built [Exchange] - no filter, no destruction listener, no wiring. The filter-level tests keep
 * proving the end-to-end handshake; this class pins the pure classification where it lives.
 */
class ExchangeLogEmitterTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        RequestLoggingProperties(
            loggerName = "http-exchange-emitter-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val metrics = EndpointLoggingMetrics.forRegistry(meterRegistry)
    private val emitter = ExchangeLogEmitter(properties, NanoTimeSource { ticker.get() }, metrics, HeaderValueMasker.DEFAULT)

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun exchange(status: Int = 200): Exchange =
        Exchange(
            method = "GET",
            path = "/api/things",
            query = null,
            requestId = "corr-1",
            requestHeaders = emptyList(),
            requestCapture = null,
            requestWrapper = null,
            responseCapture = null,
            responseWrapper = null,
            response = MockHttpServletResponse().apply { this.status = status },
            startNanos = ticker.get(),
        )

    /** A real (mock) async context: the servlet `AsyncEvent` constructor dereferences it. */
    private fun asyncContext(): MockAsyncContext = MockAsyncContext(MockHttpServletRequest(), MockHttpServletResponse())

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    private fun emitted(outcome: String): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.EVENTS_METER)
            .tag("outcome", outcome)
            .counter()
            .count()

    @Nested
    inner class `Level and outcome matrix` {
        @Test
        fun `should classify a clean 200 as INFO success`() {
            // Given/When: a clean exchange
            emitter.logExchange(exchange(200))

            // Then: INFO, success, counted
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_response_status_code", 200)
            assertThat(emitted("success")).isEqualTo(1.0)
        }

        @Test
        fun `should classify a handled 5xx as WARN failure without a cause`() {
            // Given/When: the application rendered a 503 itself
            emitter.logExchange(exchange(503))

            // Then: WARN (already handled), outcome failure, no cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.throwableProxy).isNull()
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should classify a thrown chain as ERROR failure carrying the cause`() {
            // Given: a chain failure marked on the exchange
            val ex = exchange(200).apply { failure = IllegalStateException("boom") }

            // When
            emitter.logExchange(ex)

            // Then
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("boom")
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should classify an async timeout as WARN timeout and let it win over a later onError`() {
            // What is tested: the disposition's built-in precedence - TIMED_OUT is the callback that ended
            //   the exchange; the container's subsequent onError must not reclassify it.
            // Success criteria: disposition TIMED_OUT, WARN, outcome timeout, the later throwable as cause.
            // Why it matters: the precedence used to live in the emitter's when-order; it is now a
            //   property of the value and must hold regardless of evaluation order.
            // Given: onTimeout, then onError
            val ex = exchange(200)
            val marker = AsyncOutcomeMarker(ex) {}
            marker.onTimeout(AsyncEvent(asyncContext()))
            marker.onError(AsyncEvent(asyncContext(), IllegalStateException("abort after timeout")))

            // When
            emitter.logExchange(ex)

            // Then
            val event = appender.list.single()
            assertThat(ex.asyncDisposition).isEqualTo(AsyncDisposition.TIMED_OUT)
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.throwableProxy?.message).isEqualTo("abort after timeout")
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "timeout")
            assertThat(emitted("timeout")).isEqualTo(1.0)
        }

        @Test
        fun `should keep the timeout precedence when onTimeout and onError race on two threads`() {
            // What is tested: the ATOMICITY of the disposition precedence - the sequential test
            //   above proves the ORDER rule, this one
            //   the rule under real contention: the container does not confine the two callbacks to one
            //   thread, and a volatile check-then-set in onError could read NONE, lose the race to
            //   onTimeout and then overwrite TIMED_OUT with ERRORED.
            // Success criteria: across many exchanges, each with both callbacks released from a shared
            //   start latch onto two worker threads, EVERY exchange ends TIMED_OUT; a worker failure
            //   propagates via Future.get; the emitted event of the last exchange says timeout.
            // Why it matters: a CAS makes the invariant hold deterministically; the old check-then-set
            //   held only by luck, which a fixed-order test cannot distinguish from correctness.
            // Given: two workers and a batch of exchanges
            val workers = Executors.newFixedThreadPool(2)
            val exchanges = List(500) { exchange(200) }
            try {
                exchanges.forEach { ex ->
                    val marker = AsyncOutcomeMarker(ex) {}
                    val start = CountDownLatch(1)
                    // When: both callbacks are released simultaneously
                    val timeout =
                        workers.submit {
                            start.await()
                            marker.onTimeout(AsyncEvent(asyncContext()))
                        }
                    val error =
                        workers.submit {
                            start.await()
                            marker.onError(AsyncEvent(asyncContext(), IllegalStateException("abort")))
                        }
                    start.countDown()
                    timeout.get(10, TimeUnit.SECONDS)
                    error.get(10, TimeUnit.SECONDS)
                }
            } finally {
                workers.shutdownNow()
            }

            // Then: the timeout won every race, and the classification follows it
            assertThat(exchanges).allSatisfy({ assertThat(it.asyncDisposition).isEqualTo(AsyncDisposition.TIMED_OUT) })
            emitter.logExchange(exchanges.last())
            assertThat(keyValues(appender.list.single())).containsEntry("endpoint_outcome", "timeout")
        }

        @Test
        fun `should classify an async onError without a throwable as ERROR failure`() {
            // What is tested: callback-true classification - the servlet API permits onError WITHOUT a
            //   throwable; the disposition, not the throwable, carries the outcome.
            // Success criteria: the event is an ERROR-level `failure` with no attached throwable.
            // Why it matters: keying the classification on the throwable's presence would report such an
            //   async failure as a success - the container signaled ERROR, and the event must say so even
            //   without a cause to attach.
            // Given: onError with no throwable
            val ex = exchange(200)
            AsyncOutcomeMarker(ex) {}.onError(AsyncEvent(asyncContext()))

            // When
            emitter.logExchange(ex)

            // Then
            val event = appender.list.single()
            assertThat(ex.asyncDisposition).isEqualTo(AsyncDisposition.ERRORED)
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy).isNull()
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should escalate a slow success to WARN without changing the outcome`() {
            // Given: exactly the threshold elapsed
            val ex = exchange(200)
            ticker.addAndGet(200_000_000)

            // When
            emitter.logExchange(ex)

            // Then
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event))
                .containsEntry("endpoint_slow", true)
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_duration_ms", 200L)
        }
    }

    @Nested
    inner class `Gates and guards` {
        @Test
        fun `should emit exactly once however often completion is signalled`() {
            // Given/When: two completion signals for one exchange
            val ex = exchange(200)
            emitter.logExchange(ex)
            emitter.logExchange(ex)

            // Then: one event, one count
            assertThat(appender.list).hasSize(1)
            assertThat(emitted("success")).isEqualTo(1.0)
        }

        @Test
        fun `should skip the event and the emitted counter when the level is disabled`() {
            // Given: INFO disabled on the exchange logger
            logger.level = Level.WARN

            // When: a clean exchange completes
            emitter.logExchange(exchange(200))

            // Then: nothing logged, nothing counted as emitted (the counter is the reconciliation ground truth)
            assertThat(appender.list).isEmpty()
            assertThat(emitted("success")).isEqualTo(0.0)
        }
    }
}
