package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
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
 * The trace-context contract of ADR-0002: the trace context is parsed from the incoming W3C
 * `traceparent` header (never from a tracing bridge's MDC), the trace id doubles as the request id,
 * only a traceless exchange accepts or generates a correlation id and gets the `X-Correlation-Id`
 * echo - a traced exchange passes through observationally untouched. The emission at request
 * destruction restores the parsed context on a thread of its own and suppresses stale bridge keys.
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

    private fun tracedRequest(): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/things").apply {
            addHeader("traceparent", "00-$TRACE_ID-$PARENT_SPAN_ID-01")
        }

    @Test
    fun `should carry the traceparent context into the event although destruction runs on its own thread`() {
        // What is tested: the trace context is parsed from the traceparent header at filter entry and
        //   restored around the emission - which runs at request destruction, on a callback thread that
        //   carries no MDC of its own.
        // Success criteria: the emitted event carries traceId/parentSpanId as MDC fields and inline in
        //   the message; the parent-id is published under parentSpanId, never as the local spanId.
        // Why it matters: without the header parse, a traced host loses the log-to-trace join for
        //   exactly the events that describe the exchange; publishing the caller's span as spanId would
        //   masquerade it as the local span (ADR-0002, twin parity).
        // Given: a request carrying a conformant traceparent
        val request = tracedRequest()

        // When: the filter pass runs and the container destroys the request on a clean thread
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()
        destroy(request)

        // Then: the event carries the trace context - in the MDC for encoders, inline for plain text
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", TRACE_ID)
            .containsEntry("parentSpanId", PARENT_SPAN_ID)
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage)
            .contains("traceId=$TRACE_ID parentSpanId=$PARENT_SPAN_ID]")
    }

    @Test
    fun `should use the traceparent trace id as the request id and suppress the echo`() {
        // What is tested: the identity decision of ADR-0002 - a conformant traceparent's trace id IS
        //   the request id, a caller-supplied X-Correlation-Id is ignored, and NO X-Correlation-Id
        //   response header is written.
        // Success criteria: endpoint_request_id equals the trace id in MDC and message; the response
        //   carries no correlation header although the request supplied one.
        // Why it matters: a request logger must be observationally neutral - on a traced exchange the
        //   wire already carries the identity, and echoing a second, private id would make enabling the
        //   logger visible in the communication.
        // Given: a traced request that ALSO carries a correlation header
        val request = tracedRequest().apply { addHeader(properties.correlationIdHeader, "caller-corr-1") }
        val response = MockHttpServletResponse()

        // When
        filter.doFilterInternal(request, response, FilterChain { _, _ -> })
        destroy(request)

        // Then: the distributed identity outranks the private one, and the wire stays untouched
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, TRACE_ID)
        assertThat(event.formattedMessage).contains("[endpoint_request_id=$TRACE_ID ")
        assertThat(response.getHeader(properties.correlationIdHeader)).isNull()
    }

    @Test
    fun `should fall back to the correlation contract when the traceparent is not conformant`() {
        // What is tested: an invalid traceparent counts as ABSENT (ADR-0002) - the traceless contract
        //   applies in full: the correlation header is accepted and echoed.
        // Success criteria: the event's request id is the caller's correlation id, the echo header is
        //   present, and no trace decoration is emitted.
        // Why it matters: half-trusting a malformed header would mint a request id from bytes the W3C
        //   validation rejected - the strict parser is the single gate for both the trace fields and
        //   the identity decision.
        // Given: a traceparent with an all-zero (forbidden) trace id, plus a correlation header
        val request =
            MockHttpServletRequest("GET", "/api/things").apply {
                addHeader("traceparent", "00-00000000000000000000000000000000-b7ad6b7169203331-01")
                addHeader(properties.correlationIdHeader, "caller-corr-1")
            }
        val response = MockHttpServletResponse()

        // When
        filter.doFilterInternal(request, response, FilterChain { _, _ -> })
        destroy(request)

        // Then
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "caller-corr-1")
        assertThat(event.mdcPropertyMap).doesNotContainKey("traceId")
        assertThat(response.getHeader(properties.correlationIdHeader)).isEqualTo("caller-corr-1")
    }

    @Test
    fun `should restore the destruction thread's MDC after the emission`() {
        // What is tested: the overlay is scoped - the destruction thread's own MDC state survives.
        // Success criteria: trace keys absent on the destruction thread before the emission are absent
        //   again afterwards (removed, not leaked).
        // Why it matters: destruction runs on a pooled container thread; a leaked trace id would attach
        //   THIS exchange's trace to whatever that thread logs next.
        // Given: a traced exchange
        val request = tracedRequest()
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()

        // When: destruction emits on a thread without trace context
        destroy(request)

        // Then: the thread's MDC is clean again
        assertThat(MDC.get("traceId")).isNull()
        assertThat(MDC.get("parentSpanId")).isNull()
    }

    @Test
    fun `should not adopt a stale trace context of the destruction thread when none was parsed`() {
        // What is tested: the emission scope's OWNERSHIP of the trace keys (finding 5 of the
        //   internal analysis) - an id that was not parsed from the request must be absent
        //   from the event even when the pooled destruction thread still carries one from elsewhere.
        // Success criteria: the event has no trace key (a bridge's spanId included) and no trace
        //   suffix; the thread's stale values are back in place after the emission (owned for the
        //   scope only, not cleared).
        // Why it matters: a stale id from another scope would join this exchange's event to a FOREIGN
        //   trace - the most misleading kind of correlation during an incident.
        // Given: a traceless exchange, destroyed on a thread with stale trace keys
        val request = MockHttpServletRequest("GET", "/api/things")
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.put("traceId", "stale-trace")
        MDC.put("parentSpanId", "stale-parent")
        MDC.put("spanId", "stale-span")

        // When: destruction emits on that thread
        destroy(request)

        // Then: nothing foreign on the event, the thread's own state restored
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("parentSpanId")
            .doesNotContainKey("spanId")
        assertThat(event.formattedMessage).doesNotContain("traceId=")
        assertThat(MDC.get("traceId")).isEqualTo("stale-trace")
        assertThat(MDC.get("parentSpanId")).isEqualTo("stale-parent")
        assertThat(MDC.get("spanId")).isEqualTo("stale-span")
    }

    @Test
    fun `should suppress a stale bridge spanId beside the parsed trace pair`() {
        // What is tested: the bridge's local-span key is suppressed during the emission even when the
        //   exchange HAS a parsed trace pair - the module never publishes under spanId (ADR-0002).
        // Success criteria: the event carries the parsed traceId/parentSpanId and no spanId; the
        //   thread's stale bridge value is back in place afterwards.
        // Why it matters: a stale local-span id beside the caller's context would join the event to a
        //   foreign span and read as if this module had measured it.
        // Given: a traced exchange, destroyed on a thread with a stale bridge spanId
        val request = tracedRequest()
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        MDC.clear()
        MDC.put("spanId", "stale-span")

        // When
        destroy(request)

        // Then
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .containsEntry("traceId", TRACE_ID)
            .containsEntry("parentSpanId", PARENT_SPAN_ID)
            .doesNotContainKey("spanId")
        assertThat(MDC.get("spanId")).isEqualTo("stale-span")
    }

    @Test
    fun `should emit without trace decoration when no traceparent is present`() {
        // Given: no traceparent header
        val request = MockHttpServletRequest("GET", "/api/things")

        // When: the exchange runs to destruction
        filter.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
        destroy(request)

        // Then: the event has neither trace MDC entries nor a trace suffix - and no noise marker either
        val event = appender.list.single()
        assertThat(event.mdcPropertyMap)
            .doesNotContainKey("traceId")
            .doesNotContainKey("parentSpanId")
        assertThat(event.formattedMessage).isEqualTo("Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]")
    }

    companion object {
        private const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
        private const val PARENT_SPAN_ID = "b7ad6b7169203331"
    }
}
