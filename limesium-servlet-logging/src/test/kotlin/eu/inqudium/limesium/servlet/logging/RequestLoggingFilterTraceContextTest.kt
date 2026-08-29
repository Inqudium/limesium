package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * The trace-context integration: the tracing
 * bridge's `traceId`/`spanId` MDC entries are captured at filter entry and restored around the emission -
 * which runs at request destruction, on a callback whose thread has LOST the bridge's MDC. The bridge is
 * simulated by plain `MDC.put`, exactly the state Boot's logging-correlation convention produces; the
 * thread hop is simulated by clearing the MDC before destruction fires.
 */
class RequestLoggingFilterTraceContextTest {
    private val ticker = AtomicLong(0)
    private val properties = RequestLoggingProperties(loggerName = "http-exchange-trace-test")
    private val filter =
        RequestLoggingFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            SimpleMeterRegistry(),
        )

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    private fun destroy(request: MockHttpServletRequest) = filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

    @Test
    fun `should carry the trace context into the event although destruction runs without the bridge MDC`() {
        // What is tested: the whole point of the capture - the emission happens at request destruction,
        //   where the tracing bridge's MDC is gone, yet the event must still be joinable with its trace.
        // Success criteria: with the bridge MDC present only DURING the filter pass, the emitted event
        //   carries traceId/spanId as MDC fields and inline in the message.
        // Why it matters: without the capture, every traced host loses the log-to-trace join for exactly
        //   the events that describe the exchange - the analogy's capturedMdc problem, inbound edition.
        // Given: the bridge's MDC, as Boot's logging-correlation convention writes it
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c")
        MDC.put("spanId", "b7ad6b7169203331")
        val request = MockHttpServletRequest("GET", "/api/things")

        // When: the filter pass runs under the bridge scope, the bridge scope closes (thread hop), and
        //   the container destroys the request
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()
        destroy(request)

        // Then: the event still carries the trace context - in the MDC for encoders, inline for plain text
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c")
            .containsEntry("spanId", "b7ad6b7169203331")
        assertThat(event.formattedMessage)
            .contains("traceId=0af7651916cd43dd8448eb211c80319c spanId=b7ad6b7169203331]")
    }

    @Test
    fun `should restore the destruction thread's MDC after the emission`() {
        // What is tested: the overlay is scoped - the destruction thread's own MDC state survives.
        // Success criteria: trace keys absent on the destruction thread before the emission are absent
        //   again afterwards (removed, not leaked).
        // Why it matters: destruction runs on a pooled container thread; a leaked trace id would attach
        //   THIS exchange's trace to whatever that thread logs next.
        // Given: an exchange captured under a bridge MDC
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c")
        val request = MockHttpServletRequest("GET", "/api/things")
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()

        // When: destruction emits on a thread without trace context
        destroy(request)

        // Then: the thread's MDC is clean again
        assertThat(MDC.get("traceId")).isNull()
        assertThat(MDC.get("spanId")).isNull()
    }

    @Test
    fun `should not adopt a stale trace context of the destruction thread when none was captured`() {
        // What is tested: the emission scope's OWNERSHIP of the trace keys (finding 5 of the
        //   internal analysis) - an id that was not captured at filter entry must be absent
        //   from the event even when the pooled destruction thread still carries one from elsewhere.
        // Success criteria: the event has neither trace key and no trace suffix; the thread's stale
        //   values are back in place after the emission (owned for the scope only, not cleared).
        // Why it matters: a stale id from another scope would join this exchange's event to a FOREIGN
        //   trace - the most misleading kind of correlation during an incident.
        // Given: an exchange captured without a bridge, destroyed on a thread with stale trace keys
        val request = MockHttpServletRequest("GET", "/api/things")
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.put("traceId", "stale-trace")
        MDC.put("spanId", "stale-span")

        // When: destruction emits on that thread
        destroy(request)

        // Then: nothing foreign on the event, the thread's own state restored
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).doesNotContain("traceId=")
        assertThat(MDC.get("traceId")).isEqualTo("stale-trace")
        assertThat(MDC.get("spanId")).isEqualTo("stale-span")
    }

    @Test
    fun `should suppress only the uncaptured half of the trace pair on the destruction thread`() {
        // What is tested: partial capture - only traceId was present at entry; a stale spanId on the
        //   destruction thread must not complete the pair with a foreign span.
        // Success criteria: the event carries the captured traceId, no spanId, and the suffix renders
        //   the span as "-".
        // Why it matters: an internally inconsistent trace/span pair is worse than a missing span.
        // Given: a bridge with a trace id only, and a stale span on the destruction thread
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c")
        val request = MockHttpServletRequest("GET", "/api/things")
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()
        MDC.put("spanId", "stale-span")

        // When
        destroy(request)

        // Then
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c")
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).contains("traceId=0af7651916cd43dd8448eb211c80319c spanId=-]")
        assertThat(MDC.get("spanId")).isEqualTo("stale-span")
    }

    @Test
    fun `should emit without trace decoration when no bridge is present`() {
        // Given: no tracing bridge - an empty MDC
        val request = MockHttpServletRequest("GET", "/api/things")

        // When: the exchange runs to destruction
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        destroy(request)

        // Then: the event has neither trace MDC entries nor a trace suffix - and no noise marker either
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]")
    }
}
