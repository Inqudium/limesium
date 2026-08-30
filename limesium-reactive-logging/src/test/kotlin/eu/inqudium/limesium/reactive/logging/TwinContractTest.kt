package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.TraceMdcKeys
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Literal pins of the contracts DUPLICATED across the endpoint-logging twins that the cross-module
 * lockstep tests do not cover: meter names, MDC key values, the masking fingerprint, and the message
 * text of the arrival and exchange lines. Both twins
 * carry this test with the identical literals - a drift in either module breaks that module's build.
 */
class TwinContractTest {
    @Test
    fun `should pin the meter names to the literal twin contract`() {
        // What is tested: the duplicated meter-name constants, spelled out as literals (review        //   finding 7) - the cross-module lockstep tests cover configuration and field names, but not
        //   these.
        // Success criteria: every meter name matches the literal both twins ship.
        // Why it matters: a renamed meter in ONE twin would split every dashboard by stack - silently.
        // Given/When/Then: the literal meter names, pinned
        assertThat(EndpointLoggingMetrics.FAIL_OPEN_METER).isEqualTo("endpoint.logging.failopen")
        assertThat(EndpointLoggingMetrics.EVENTS_METER).isEqualTo("endpoint.logging.events")
        assertThat(EndpointLoggingMetrics.OPEN_EXCHANGES_METER).isEqualTo("endpoint.logging.exchanges.open")
        assertThat(EndpointLoggingMetrics.CORRELATION_METER).isEqualTo("endpoint.logging.correlation.id")
        assertThat(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).isEqualTo("endpoint.request.body.size")
        assertThat(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER).isEqualTo("endpoint.response.body.size")
        assertThat(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).isEqualTo("endpoint.request.body.read")
    }

    @Test
    fun `should pin the request body read states to the literal twin contract`() {
        // What is tested: the `state` tag vocabulary of endpoint.request.body.read, which both twins
        //   derive from their own BodyReadState enum.
        // Success criteria: the three tag values match the literals both twins ship.
        // Why it matters: a dashboard splitting by `state` must not see `complete` from one stack and
        //   `completed` from the other.
        // Given/When/Then: the literal tag values, pinned
        assertThat(BodyReadState.UNREAD.tagValue).isEqualTo("unread")
        assertThat(BodyReadState.PARTIAL.tagValue).isEqualTo("partial")
        assertThat(BodyReadState.COMPLETE.tagValue).isEqualTo("complete")
        assertThat(BodyReadState.entries).hasSize(3)
    }

    @Test
    fun `should pin the MDC keys to the literal twin contract`() {
        // Given/When/Then: the literal MDC keys, pinned
        assertThat(MdcKeys.REQUEST_ID).isEqualTo("endpoint_request_id")
        assertThat(MdcKeys.REQUEST_METHOD).isEqualTo("endpoint_method")
        assertThat(MdcKeys.ROUTE).isEqualTo("endpoint_route")
        assertThat(TraceMdcKeys.TRACE_ID).isEqualTo("traceId")
        // Stack-inherent difference: the servlet twin reads the bridge's local `spanId`; this stack only
        //   knows the CALLER's span from traceparent and publishes it as `parentSpanId`.
        assertThat(TraceMdcKeys.PARENT_SPAN_ID).isEqualTo("parentSpanId")
    }

    @Test
    fun `should pin the masking fingerprint format to the literal twin contract`() {
        // The expected value is hardcoded, not derived: the first 64 bits of SHA-256 over the UTF-8
        //   bytes are stable across JVMs - and a format change in one twin breaks that module's literal here, forcing coordinated change.
        // Given/When/Then: one fixed input against its literal fingerprint
        assertThat(HeaderLogProperties.mask("secret-token")).isEqualTo("12:930bbdc51b6aed5c")
    }

    @Test
    fun `should pin the shared outcome vocabulary plus this stack's own disposition`() {
        // Given/When/Then: the literal outcome vocabulary, pinned
        assertThat(EndpointLoggingMetrics.OUTCOME_SUCCESS).isEqualTo("success")
        assertThat(EndpointLoggingMetrics.OUTCOME_FAILURE).isEqualTo("failure")
        assertThat(EndpointLoggingMetrics.OUTCOME_CANCELLED).isEqualTo("cancelled")
    }

    @Test
    fun `should pin the exchange and arrival message format to the literal twin contract`() {
        // What is tested: the MESSAGE half of the twin contract - the field names are locked by
        //   EndpointLogFieldTest, the message text was asserted by KDoc only (finding 4 of
        //   an internal comment audit).
        // Success criteria: a pinned exchange renders the literal messages both twins ship.
        // Why it matters: plain-text appenders and the README's parity promise key on this text; a
        //   divergence in one twin would otherwise ship silently.
        // Given: a pinned filter logging the arrival line, a logger with a list appender
        val properties = RequestLoggingProperties(loggerName = "http-exchange-twin-message-test", logRequestStart = true)
        val filter = RequestLoggingWebFilter(properties, { 0L }, { "generated-42" }, SimpleMeterRegistry())
        val logger = LoggerFactory.getLogger(properties.loggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.INFO
        try {
            // When: one successful exchange
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            filter
                .filter(
                    exchange,
                    WebFilterChain { ex ->
                        ex.response.statusCode = HttpStatus.OK
                        Mono.empty()
                    },
                ).block()

            // Then: the literal messages, identical in both twins
            assertThat(appender.list.map { it.formattedMessage })
                .containsExactly(
                    "Endpoint http exchange started GET /api/things [endpoint_request_id=generated-42]",
                    "Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]",
                )
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
