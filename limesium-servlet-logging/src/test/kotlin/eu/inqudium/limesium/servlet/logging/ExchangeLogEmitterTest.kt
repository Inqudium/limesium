package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CapturedLogger
import eu.inqudium.limesium.common.EndpointLoggingMetrics
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.keyValues
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.AsyncEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
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
            loggerName = "endpoint-http-exchange-emitter-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val metrics = EndpointLoggingMetrics.forRegistry(meterRegistry, EndpointLoggingMetrics.OUTCOME_TIMEOUT)
    private val emitter = ExchangeLogEmitter(properties, NanoTimeSource { ticker.get() }, metrics, HeaderValueMasker.DEFAULT)

    @JvmField
    @RegisterExtension
    val exchangeLog = CapturedLogger(properties.loggerName)

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
            // What is tested: the emitter's classification of a completed exchange without failure
            //   - level, outcome, status field and the events counter.
            // Success criteria: one INFO event with outcome success and status 200; the success
            //   counter reads 1.
            // Why it matters: the events counter is the reconciliation ground truth against the log
            //   index; it must count exactly the events that were emitted.
            // Given/When: a clean exchange
            emitter.logExchange(exchange(200))

            // Then: INFO, success, counted
            val event = exchangeLog.events.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(event.keyValues())
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_response_status_code", 200)
            assertThat(emitted("success")).isEqualTo(1.0)
        }

        @Test
        fun `should classify a handled 5xx as WARN failure without a cause`() {
            // What is tested: an exchange the application answered with 503 and no exception.
            // Success criteria: WARN, outcome failure, no throwable attached.
            // Why it matters: the handler decided the status; ERROR with a stack trace would be
            //   noise, while the outcome tag still counts the failure.
            // Given/When: the application rendered a 503 itself
            emitter.logExchange(exchange(503))

            // Then: WARN (already handled), outcome failure, no cause
            val event = exchangeLog.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.throwableProxy).isNull()
            assertThat(event.keyValues()).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should classify a thrown chain as ERROR failure carrying the cause`() {
            // What is tested: an exchange whose chain failed with an exception recorded on it.
            // Success criteria: ERROR, outcome failure, the exception attached as the event's
            //   cause.
            // Why it matters: the cause on the event is what a structured encoder renders as the
            //   stack trace; without it the failure line names no reason.
            // Given: a chain failure marked on the exchange
            val ex = exchange(200).apply { failure = IllegalStateException("boom") }

            // When
            emitter.logExchange(ex)

            // Then
            val event = exchangeLog.events.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("boom")
            assertThat(event.keyValues()).containsEntry("endpoint_outcome", "failure")
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
            val event = exchangeLog.events.single()
            assertThat(ex.asyncDisposition).isEqualTo(AsyncDisposition.TIMED_OUT)
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.throwableProxy?.message).isEqualTo("abort after timeout")
            assertThat(event.keyValues()).containsEntry("endpoint_outcome", "timeout")
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
            assertThat(exchangeLog.events.single().keyValues()).containsEntry("endpoint_outcome", "timeout")
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
            val event = exchangeLog.events.single()
            assertThat(ex.asyncDisposition).isEqualTo(AsyncDisposition.ERRORED)
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy).isNull()
            assertThat(event.keyValues()).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should escalate a slow success to WARN without changing the outcome`() {
            // What is tested: exactly the configured threshold elapsed on a clean exchange.
            // Success criteria: WARN, endpoint_slow true, outcome success, duration 200 ms.
            // Why it matters: slowness raises severity, never the outcome; the boundary must be
            //   inclusive so a 200 ms threshold flags a 200 ms exchange.
            // Given: exactly the threshold elapsed
            val ex = exchange(200)
            ticker.addAndGet(200_000_000)

            // When
            emitter.logExchange(ex)

            // Then
            val event = exchangeLog.events.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.keyValues())
                .containsEntry("endpoint_slow", true)
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_duration_ms", 200L)
        }
    }

    @Nested
    inner class `Gates and guards` {
        @Test
        fun `should emit exactly once however often completion is signalled`() {
            // What is tested: two completion signals for one exchange.
            // Success criteria: one event, one count.
            // Why it matters: the containers signal destruction on differing schedules (once-late,
            //   per-dispatch); the exactly-once guard is what keeps the line count truthful.
            // Given/When: two completion signals for one exchange
            val ex = exchange(200)
            emitter.logExchange(ex)
            emitter.logExchange(ex)

            // Then: one event, one count
            assertThat(exchangeLog.events).hasSize(1)
            assertThat(emitted("success")).isEqualTo(1.0)
        }

        @Test
        fun `should skip the event and the emitted counter when the level is disabled`() {
            // What is tested: the level gate with INFO disabled on the exchange logger.
            // Success criteria: nothing logged and the success counter stays at 0.
            // Why it matters: the counter counts EMITTED events, so a gated exchange must not be
            //   counted, or the reconciliation against the index would show phantom loss.
            // Given: INFO disabled on the exchange logger
            exchangeLog.logger.level = Level.WARN

            // When: a clean exchange completes
            emitter.logExchange(exchange(200))

            // Then: nothing logged, nothing counted as emitted (the counter is the reconciliation ground truth)
            assertThat(exchangeLog.events).isEmpty()
            assertThat(emitted("success")).isEqualTo(0.0)
        }
    }
}
