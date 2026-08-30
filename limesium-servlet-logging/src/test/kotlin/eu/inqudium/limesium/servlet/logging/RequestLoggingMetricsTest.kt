package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * The two observability meters beyond the fail-open counter: [EndpointLoggingMetrics.EVENTS_METER] (emitted
 * exchange events per outcome, the reconciliation ground truth against the log index) and the body-size
 * distributions [EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER] / [EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER]
 * (bytes that actually flowed, tagged by handler pattern, independent of body logging and of the log
 * level). All on a [SimpleMeterRegistry], failures-free and deterministic.
 */
class RequestLoggingMetricsTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        RequestLoggingProperties(
            loggerName = "http-exchange-metrics-test",
            measureRequestBodySize = true,
            measureResponseBodySize = true,
        )
    private val filter =
        RequestLoggingFilter(
            properties,
            NanoTimeSource { ticker.get() },
            CorrelationIdGenerator { "generated-42" },
            meterRegistry,
        )

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

    private fun handle(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
        chain: FilterChain = FilterChain { _, _ -> },
    ) {
        try {
            filter.doFilterInternal(request, response, chain)
        } finally {
            filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
        }
    }

    private fun eventCount(outcome: String): Double =
        meterRegistry
            .get(EndpointLoggingMetrics.EVENTS_METER)
            .tag("outcome", outcome)
            .counter()
            .count()

    @Nested
    inner class `The emitted-events counter` {
        @Test
        fun `should pre-register all outcomes at zero and count an emitted success event`() {
            // What is tested: pre-registration (alerts must see the zeros) and the happy-path increment.
            // Success criteria: before any request all three outcomes read 0; after one clean exchange
            //   success reads 1 and the others stay 0.
            // Why it matters: the counter is the metric-side half of the reconciliation against the log
            //   index - it must count exactly the events that were emitted, per outcome.
            // Given: a fresh registry - all outcomes already exist at zero
            assertThat(eventCount("success")).isEqualTo(0.0)
            assertThat(eventCount("failure")).isEqualTo(0.0)
            assertThat(eventCount("timeout")).isEqualTo(0.0)

            // When: a clean exchange runs
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse())

            // Then: exactly the success outcome counted
            assertThat(eventCount("success")).isEqualTo(1.0)
            assertThat(eventCount("failure")).isEqualTo(0.0)
        }

        @Test
        fun `should count a thrown chain as outcome failure`() {
            // Given: a failing chain
            val boom = IllegalStateException("boom")

            // When: the exchange runs (the exception propagates, the emission still happens at destroy)
            val thrown =
                catchThrowable {
                    handle(MockHttpServletRequest("POST", "/api/things"), MockHttpServletResponse(), FilterChain { _, _ -> throw boom })
                }

            // Then: rethrown unchanged and counted as failure
            assertThat(thrown).isSameAs(boom)
            assertThat(eventCount("failure")).isEqualTo(1.0)
            assertThat(eventCount("success")).isEqualTo(0.0)
        }

        @Test
        fun `should not count an event the level gate suppressed`() {
            // What is tested: the counter counts EMITTED events, not exchanges - that is what makes it
            //   comparable against the log index.
            // Success criteria: with the logger at ERROR, a successful exchange emits nothing and counts
            //   nothing.
            // Why it matters: if the counter kept counting suppressed events, every reconciliation
            //   against the index would report phantom pipeline loss on any host that gates at WARN.
            // Given: the exchange logger gated to ERROR
            logger.level = Level.ERROR

            // When: a clean exchange runs
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse())

            // Then: no event, no count
            assertThat(appender.list).isEmpty()
            assertThat(eventCount("success")).isEqualTo(0.0)
        }
    }

    @Nested
    inner class `The open exchanges gauge` {
        private fun openExchanges(): Double = meterRegistry.get(EndpointLoggingMetrics.OPEN_EXCHANGES_METER).gauge().value()

        @Test
        fun `should rise while the exchange is handled and return to zero at destruction`() {
            // What is tested: the up-down lifecycle of the gauge around one exchange, and its guard
            //   against double decrements when destruction fires twice.
            // Success criteria: 1 while the chain runs, 0 after destruction, still 0 (not -1) after a
            //   second destruction of the same request.
            // Why it matters: the gauge is a liveness check - a value that could drift NEGATIVE on
            //   container quirks would destroy exactly the baseline the leak detection reads.
            // Given: a chain that observes the gauge mid-flight
            var openDuringChain = -1.0
            val request = MockHttpServletRequest("GET", "/api/things")
            val chain = FilterChain { _, _ -> openDuringChain = openExchanges() }

            // When: the exchange runs to destruction, and destruction fires a second time
            handle(request, MockHttpServletResponse(), chain)
            filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: up during, zero after, never negative
            assertThat(openDuringChain).isEqualTo(1.0)
            assertThat(openExchanges()).isEqualTo(0.0)
        }

        @Test
        fun `should keep counting an exchange whose destruction never fires`() {
            // What is tested: the failure mode the gauge exists for - the container never fires
            //   requestDestroyed, so the event is silently lost.
            // Success criteria: after a filter pass WITHOUT destruction the gauge stays at 1 and no
            //   event was emitted.
            // Why it matters: nothing throws in this scenario, so the fail-open counter stays silent and
            //   the events counter has no baseline - this gauge's drifting baseline is the ONLY signal.
            // Given/When: only the filter pass runs, destruction never fires
            filter.doFilterInternal(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the exchange hangs open, unlogged
            assertThat(openExchanges()).isEqualTo(1.0)
            assertThat(appender.list).isEmpty()
        }
    }

    @Nested
    inner class `The correlation id source counter` {
        private fun sourceCount(source: String): Double =
            meterRegistry
                .get(EndpointLoggingMetrics.CORRELATION_METER)
                .tag("source", source)
                .counter()
                .count()

        @Test
        fun `should count the request id source as trace, header or generated`() {
            // What is tested: the upstream-propagation watch - which side of the identity contract
            //   (ADR-0002) each request lands on.
            // Success criteria: all three sources are pre-registered at zero; one request with a
            //   conformant traceparent, one with only the correlation header and one with neither
            //   count 1 each on their side.
            // Why it matters: a rising generated share is the earliest signal that a gateway or sidecar
            //   stopped propagating traceparent or correlation ids - invisible in logs, where every
            //   event simply carries SOME id.
            // Given: a fresh registry - all three sources already exist at zero
            assertThat(sourceCount("trace")).isEqualTo(0.0)
            assertThat(sourceCount("header")).isEqualTo(0.0)
            assertThat(sourceCount("generated")).isEqualTo(0.0)

            // When: one traced request, one with the correlation header only, one with neither
            val withTraceparent = MockHttpServletRequest("GET", "/api/things")
            withTraceparent.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
            handle(withTraceparent, MockHttpServletResponse())
            val withHeader = MockHttpServletRequest("GET", "/api/things")
            withHeader.addHeader(properties.correlationIdHeader, "caller-id")
            handle(withHeader, MockHttpServletResponse())
            handle(MockHttpServletRequest("GET", "/api/things"), MockHttpServletResponse())

            // Then: one count on each side
            assertThat(sourceCount("trace")).isEqualTo(1.0)
            assertThat(sourceCount("header")).isEqualTo(1.0)
            assertThat(sourceCount("generated")).isEqualTo(1.0)
        }
    }

    @Nested
    inner class `The body size distributions` {
        private fun summaryFor(
            meter: String,
            uri: String,
        ) = meterRegistry.get(meter).tag("uri", uri).summary()

        @Test
        fun `should record both body sizes with the handler pattern as uri tag`() {
            // Given: a chain that reads the 5-byte request body, writes a 6-byte response and carries a
            //   recorded handler pattern
            val request = MockHttpServletRequest("POST", "/api/things/7")
            request.setContent("hello".toByteArray(StandardCharsets.UTF_8))
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { req, res ->
                    (req as HttpServletRequest).inputStream.readAllBytes()
                    req.setAttribute(RequestLoggingFilter.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/things/{id}")
                    (res as HttpServletResponse).outputStream.write("bytes!".toByteArray())
                }

            // When: the exchange runs
            handle(request, response, chain)

            // Then: one sample per direction under the template tag, with the exact byte counts
            val requestSummary = summaryFor(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, "/api/things/{id}")
            assertThat(requestSummary.count()).isEqualTo(1)
            assertThat(requestSummary.totalAmount()).isEqualTo(5.0)
            val responseSummary = summaryFor(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER, "/api/things/{id}")
            assertThat(responseSummary.count()).isEqualTo(1)
            assertThat(responseSummary.totalAmount()).isEqualTo(6.0)
        }

        @Test
        fun `should record no sample for a body that never flowed and tag untemplated exchanges as UNKNOWN`() {
            // What is tested: the zero-sample rule and the template fallback in one exchange.
            // Success criteria: a GET without body and without handler pattern records nothing on the
            //   request side, and its response sample lands under the UNKNOWN uri tag.
            // Why it matters: zero-byte samples would drag every average and percentile toward zero;
            //   and without the fallback, untemplated exchanges would silently record nothing at all.
            // Given: a bodyless GET whose chain writes a response, no handler pattern recorded
            val response = MockHttpServletResponse()
            val chain = FilterChain { _, res -> (res as HttpServletResponse).outputStream.write("data".toByteArray()) }

            // When: the exchange runs
            handle(MockHttpServletRequest("GET", "/api/plain"), response, chain)

            // Then: no request-side meter exists at all, the response sample sits under UNKNOWN
            assertThat(meterRegistry.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summaries()).isEmpty()
            val responseSummary = summaryFor(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER, EndpointLoggingMetrics.UNTEMPLATED_URI)
            assertThat(responseSummary.totalAmount()).isEqualTo(4.0)
        }

        @Test
        fun `should measure the full body size beyond the logging capture limit`() {
            // What is tested: measuring and logging together - the tee counts past the capture cap, so
            //   the metric must show the REAL size while the logged field is truncated.
            // Success criteria: a 16-byte body against an 8-byte cap records 16 in the summary.
            // Why it matters: a size metric silently clipped to the log capture limit would understate
            //   exactly the large payloads it exists to find.
            // Given: a filter that both logs (cap 8) and measures the request body
            val loggingAndMeasuring =
                RequestLoggingFilter(
                    properties.copy(logRequestBody = true, maxBodyBytes = 8),
                    { ticker.get() },
                    { "generated-42" },
                    meterRegistry,
                )
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8))
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).inputStream.readAllBytes() }

            // When: the exchange runs
            loggingAndMeasuring.doFilterInternal(request, MockHttpServletResponse(), chain)
            loggingAndMeasuring.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the metric carries the full 16 bytes although the logged field was capped at 8
            val summary = summaryFor(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, EndpointLoggingMetrics.UNTEMPLATED_URI)
            assertThat(summary.totalAmount()).isEqualTo(16.0)
        }

        @Test
        fun `should record sizes even when the level gate suppresses the event`() {
            // What is tested: metrics are independent of logging volume.
            // Success criteria: with the logger at ERROR a successful exchange emits no event but still
            //   records its response size.
            // Why it matters: hosts routinely gate the exchange stream at WARN in production - the size
            //   signal must not vanish with it.
            // Given: the exchange logger gated to ERROR
            logger.level = Level.ERROR
            val response = MockHttpServletResponse()
            val chain = FilterChain { _, res -> (res as HttpServletResponse).outputStream.write("data".toByteArray()) }

            // When: the exchange runs
            handle(MockHttpServletRequest("GET", "/api/plain"), response, chain)

            // Then: no event, but a recorded sample
            assertThat(appender.list).isEmpty()
            val responseSummary = summaryFor(EndpointLoggingMetrics.RESPONSE_BODY_SIZE_METER, EndpointLoggingMetrics.UNTEMPLATED_URI)
            assertThat(responseSummary.totalAmount()).isEqualTo(4.0)
        }

        @Test
        fun `should not surface a measure-only capture as a logged body field`() {
            // What is tested: the count-only capture (limit 0) exists for the metric alone - it must not
            //   leak into the event as an empty, truncated-looking endpoint_request_body.
            // Success criteria: body fields are absent although bytes were measured.
            // Why it matters: this is the coupling bug the measure flags were deliberately decoupled to
            //   avoid - metrics on, logging off must not change the event shape.
            // Given: measure-only configuration (the class default here), a body that flows
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("hello".toByteArray(StandardCharsets.UTF_8))
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).inputStream.readAllBytes() }

            // When: the exchange runs
            handle(request, MockHttpServletResponse(), chain)

            // Then: measured, but no body keys on the event
            val keyValues =
                appender.list
                    .single()
                    .keyValuePairs
                    ?.associate { it.key to it.value } ?: emptyMap()
            assertThat(keyValues)
                .doesNotContainKey("endpoint_request_body")
                .doesNotContainKey("endpoint_response_body")
            assertThat(summaryFor(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER, EndpointLoggingMetrics.UNTEMPLATED_URI).totalAmount())
                .isEqualTo(5.0)
        }
    }

    @Nested
    inner class `The request body read counter` {
        private fun readCount(
            state: String,
            uri: String = EndpointLoggingMetrics.UNTEMPLATED_URI,
        ): Double =
            meterRegistry
                .find(EndpointLoggingMetrics.REQUEST_BODY_READ_METER)
                .tag("uri", uri)
                .tag("state", state)
                .counter()
                ?.count() ?: 0.0

        private fun postWithBody(body: String = "hello"): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/things").apply { setContent(body.toByteArray(StandardCharsets.UTF_8)) }

        @Test
        fun `should count a body read to its end as complete under the handler pattern`() {
            // Given: a chain that drains the stream (readAllBytes observes the EOF) and records a pattern
            val chain =
                FilterChain { req, _ ->
                    (req as HttpServletRequest).inputStream.readAllBytes()
                    req.setAttribute(RequestLoggingFilter.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/things")
                }

            // When: the exchange runs
            handle(postWithBody(), MockHttpServletResponse(), chain)

            // Then: one complete under the template, nothing else
            assertThat(readCount("complete", "/api/things")).isEqualTo(1.0)
            assertThat(readCount("partial", "/api/things")).isEqualTo(0.0)
            assertThat(readCount("unread", "/api/things")).isEqualTo(0.0)
        }

        @Test
        fun `should count a body the application stopped reading as partial`() {
            // What is tested: the tee observes consumption only - it never probes for EOF itself.
            // Success criteria: reading 2 of 5 bytes and returning counts as partial, and the size sample
            //   shows the 2 bytes that flowed.
            // Why it matters: a parser that bails out early is the case the counter exists to expose;
            //   a tee that drained the rest to "find out" would both lie and change the application's
            //   I/O.
            // Given: a chain reading only two bytes
            val chain =
                FilterChain { req, _ ->
                    val stream = (req as HttpServletRequest).inputStream
                    stream.read()
                    stream.read()
                }

            // When: the exchange runs
            handle(postWithBody(), MockHttpServletResponse(), chain)

            // Then: partial, with exactly the consumed bytes in the size sample
            assertThat(readCount("partial")).isEqualTo(1.0)
            assertThat(readCount("complete")).isEqualTo(0.0)
            val summary =
                meterRegistry
                    .get(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER)
                    .tag("uri", EndpointLoggingMetrics.UNTEMPLATED_URI)
                    .summary()
            assertThat(summary.totalAmount()).isEqualTo(2.0)
        }

        @Test
        fun `should count a body the application never touched as unread even though the client sent one`() {
            // What is tested: the one distinction neither the logged body nor the size sample can make -
            //   a body that was SENT but never READ.
            // Success criteria: the read counter shows unread while the size summary has no sample.
            // Why it matters: an endpoint silently ignoring its payload looks identical to a bodyless
            //   request in every other signal of this module.
            // Given: a chain that ignores the request entirely
            handle(postWithBody(), MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: unread counted, no size sample at all
            assertThat(readCount("unread")).isEqualTo(1.0)
            assertThat(meterRegistry.find(EndpointLoggingMetrics.REQUEST_BODY_SIZE_METER).summaries()).isEmpty()
        }

        @Test
        fun `should observe completion through the reader as well as through the stream`() {
            // Given: a chain consuming the body through getReader
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).reader.readText() }

            // When: the exchange runs
            handle(postWithBody("h\u00e9llo"), MockHttpServletResponse(), chain)

            // Then: complete - the reader sits on the same tee stream and its EOF is the stream's EOF
            assertThat(readCount("complete")).isEqualTo(1.0)
        }

        @Test
        fun `should record nothing when request body measuring is off`() {
            // Given: a filter without request measuring, a chain that reads
            val notMeasuring =
                RequestLoggingFilter(properties.copy(measureRequestBodySize = false), { ticker.get() }, { "generated-42" }, meterRegistry)
            val request = postWithBody()
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).inputStream.readAllBytes() }

            // When: the exchange runs
            notMeasuring.doFilterInternal(request, MockHttpServletResponse(), chain)
            notMeasuring.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))

            // Then: the counter does not exist - the opt-in is the measuring flag
            assertThat(meterRegistry.find(EndpointLoggingMetrics.REQUEST_BODY_READ_METER).counters()).isEmpty()
        }
    }
}
