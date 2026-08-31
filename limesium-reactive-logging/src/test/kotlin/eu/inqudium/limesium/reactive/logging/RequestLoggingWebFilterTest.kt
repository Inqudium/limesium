package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import org.springframework.web.util.pattern.PatternParseException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Core behavior of [RequestLoggingWebFilter]: the exchange line (IDENTICAL in format to the servlet
 * twin's), the level/outcome matrix with the reactive `cancelled` disposition, the commit-deferred
 * emission of the error path, correlation handling and exclusions. Deterministic: injected `AtomicLong`
 * time, pinned id generator, [MockServerWebExchange], and all reactive signals driven synchronously via
 * `block()`/`subscribe().dispose()`.
 */
class RequestLoggingWebFilterTest {
    private val ticker = AtomicLong(0)
    private val meterRegistry = SimpleMeterRegistry()
    private val properties =
        RequestLoggingProperties(
            loggerName = "http-exchange-reactive-core-test",
            slowRequestThreshold = Duration.ofMillis(200),
        )
    private val filter =
        RequestLoggingWebFilter(
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

    private fun keyValues(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs?.associate { it.key to it.value } ?: emptyMap()

    private fun okChain(): WebFilterChain =
        WebFilterChain { exchange ->
            exchange.response.statusCode = HttpStatus.OK
            Mono.empty()
        }

    @Nested
    inner class `The exchange line` {
        @Test
        fun `should log the identical line format of the servlet twin`() {
            // What is tested: the format contract - message and endpoint_* key-values must be
            //   indistinguishable from limesium-servlet-logging's output.
            // Success criteria: the exact message string and the full field family (minus endpoint_async,
            //   which the reactive twin deliberately never emits).
            // Why it matters: identical logging is this module's core requirement - dashboards must not
            //   care which stack produced an event.
            // Given: a GET answered 200 after 42 ms of measured work
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ticker.addAndGet(42_000_000)
                    ex.response.statusCode = HttpStatus.OK
                    Mono.empty()
                }

            // When: the filter's Mono completes - verified as a SIGNAL, not via block()
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

            // Then: one INFO line, format-identical to the servlet twin
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.INFO)
            assertThat(event.formattedMessage)
                .isEqualTo("Endpoint http exchange GET /api/things -> 200 [endpoint_request_id=generated-42]")
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "success")
                .containsEntry("endpoint_request_method", "GET")
                .containsEntry("endpoint_url_path", "/api/things")
                .containsEntry("endpoint_response_status_code", 200)
                .containsEntry("endpoint_duration_ms", 42L)
                .doesNotContainKey("endpoint_async")
                .doesNotContainKey("endpoint_slow")
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "generated-42")
        }

        @Test
        fun `should log query and handler pattern as their own fields`() {
            // Given: a query string and a recorded WebFlux handler pattern
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things/7?page=2"))
            val chain =
                WebFilterChain { ex ->
                    ex.attributes[RequestLoggingWebFilter.BEST_MATCHING_PATTERN_ATTRIBUTE] =
                        PathPatternParser.defaultInstance.parse("/api/things/{id}")
                    ex.response.statusCode = HttpStatus.OK
                    Mono.empty()
                }

            // When: the filter handles the exchange
            filter.filter(exchange, chain).block()

            // Then: the path pair and the query ride their own fields, like the servlet twin
            assertThat(keyValues(appender.list.single()))
                .containsEntry("endpoint_url_path", "/api/things/7")
                .containsEntry("endpoint_url_query", "page=2")
                .containsEntry("endpoint_url_template", "/api/things/{id}")
        }

        @Test
        fun `should log the raw request target so percent-encoded control characters cannot forge log lines`() {
            // What is tested: the log-injection guard for the raw request target -
            //   java.net.URI decodes getPath()/getQuery(), so `%0A` in the request target used to become a
            //   real line break in the message, the MDC route and the fields.
            // Success criteria: path and query appear percent-encoded as sent in the message, in the
            //   endpoint_url_path/endpoint_url_query fields and in the endpoint_route MDC entry; no sink
            //   contains a line break.
            // Why it matters: an unauthenticated client could otherwise forge complete exchange lines in
            //   every plain-text appender, and - via the Reactor context - in every handler log line.
            // Given: a request target with encoded CR/LF in path and query
            // (built from a URI: the String factory would re-encode the `%` to `%25`, hiding the case)
            val exchange =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/th%0Aings?x=%0D%0Ay")))
            val chain =
                WebFilterChain { ex ->
                    ex.response.statusCode = HttpStatus.OK
                    Mono.empty()
                }

            // When: the filter handles the exchange
            filter.filter(exchange, chain).block()

            // Then: every sink carries the raw form, none a control character
            val event = appender.list.single()
            assertThat(event.formattedMessage)
                .isEqualTo("Endpoint http exchange GET /api/th%0Aings -> 200 [endpoint_request_id=generated-42]")
            assertThat(keyValues(event))
                .containsEntry("endpoint_url_path", "/api/th%0Aings")
                .containsEntry("endpoint_url_query", "x=%0D%0Ay")
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.ROUTE, "/api/th%0Aings")
            assertThat(event.formattedMessage + keyValues(event).values.joinToString() + event.mdcPropertyMap.values.joinToString())
                .doesNotContain("\n", "\r")
        }

        @Test
        fun `should echo the correlation id and adopt one from the request header on a traceless exchange`() {
            // Given: a traceless request already carrying a correlation id
            val exchange =
                MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/things").header(properties.correlationIdHeader, "caller-id"),
                )

            // When: the filter handles the exchange
            filter.filter(exchange, okChain()).block()

            // Then: echoed on the response, carried in the event's MDC and inline
            assertThat(exchange.response.headers.getFirst(properties.correlationIdHeader)).isEqualTo("caller-id")
            val event = appender.list.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "caller-id")
            assertThat(event.formattedMessage).contains("[endpoint_request_id=caller-id]")
        }

        @Test
        fun `should use the traceparent trace id as the request id and suppress the echo`() {
            // What is tested: the identity decision of ADR-0002 - a conformant traceparent's trace id IS
            //   the request id, a caller-supplied X-Correlation-Id is ignored, and NO X-Correlation-Id
            //   response header is written.
            // Success criteria: endpoint_request_id equals the trace id in MDC and message; the response
            //   carries no correlation header although the request supplied one.
            // Why it matters: a request logger must be observationally neutral - on a traced exchange the
            //   wire already carries the identity, and echoing a second, private id would make enabling
            //   the logger visible in the communication.
            // Given: a traced request that ALSO carries a correlation header
            val exchange =
                MockServerWebExchange.from(
                    MockServerHttpRequest
                        .get("/api/things")
                        .header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                        .header(properties.correlationIdHeader, "caller-id"),
                )

            // When: the filter handles the exchange
            filter.filter(exchange, okChain()).block()

            // Then: the distributed identity outranks the private one, and the wire stays untouched
            assertThat(exchange.response.headers.getFirst(properties.correlationIdHeader)).isNull()
            val event = appender.list.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "0af7651916cd43dd8448eb211c80319c")
            assertThat(event.formattedMessage).contains("[endpoint_request_id=0af7651916cd43dd8448eb211c80319c ")
        }

        @Test
        fun `should fall back to the correlation contract when the traceparent is not conformant`() {
            // What is tested: an invalid traceparent counts as ABSENT (ADR-0002) - the traceless
            //   contract applies in full: the correlation header is accepted and echoed.
            // Success criteria: the event's request id is the caller's correlation id, the echo header
            //   is present, and no trace decoration is emitted.
            // Why it matters: half-trusting a malformed header would mint a request id from bytes the
            //   W3C validation rejected - the strict parser is the single gate for both the trace
            //   fields and the identity decision.
            // Given: a traceparent with an all-zero (forbidden) trace id, plus a correlation header
            val exchange =
                MockServerWebExchange.from(
                    MockServerHttpRequest
                        .get("/api/things")
                        .header("traceparent", "00-00000000000000000000000000000000-b7ad6b7169203331-01")
                        .header(properties.correlationIdHeader, "caller-id"),
                )

            // When: the filter handles the exchange
            filter.filter(exchange, okChain()).block()

            // Then
            assertThat(exchange.response.headers.getFirst(properties.correlationIdHeader)).isEqualTo("caller-id")
            val event = appender.list.single()
            assertThat(event.mdcPropertyMap).containsEntry(MdcKeys.REQUEST_ID, "caller-id")
            assertThat(event.mdcPropertyMap).doesNotContainKey("traceId")
        }

        @Test
        fun `should carry the traceparent-derived trace context into the event`() {
            // Given: an incoming W3C traceparent header
            val exchange =
                MockServerWebExchange.from(
                    MockServerHttpRequest
                        .get("/api/things")
                        .header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
                )

            // When: the filter handles the exchange
            filter.filter(exchange, okChain()).block()

            // Then: trace id (the server span's trace) and the caller's span id ride MDC and message -
            //   the latter as parentSpanId, never as the local spanId this module cannot know
            val event = appender.list.single()
            assertThat(event.mdcPropertyMap)
                .containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c")
                .containsEntry("parentSpanId", "b7ad6b7169203331")
                .doesNotContainKey("spanId")
            assertThat(event.formattedMessage)
                .contains(" traceId=0af7651916cd43dd8448eb211c80319c parentSpanId=b7ad6b7169203331")
        }
    }

    @Nested
    inner class `Levels and outcomes` {
        @Test
        fun `should escalate to WARN with outcome failure for a handled 5xx`() {
            // Given: a chain that answers 503 itself
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ex.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                    Mono.empty()
                }

            // When / Then: WARN, outcome failure - severity and semantic decoupled
            filter.filter(exchange, chain).block()
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event)).containsEntry("endpoint_outcome", "failure")
        }

        @Test
        fun `should escalate to WARN and flag a slow but successful exchange`() {
            // Given: a chain that consumes the configured threshold
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/slow"))
            val chain =
                WebFilterChain { ex ->
                    ticker.addAndGet(200_000_000)
                    ex.response.statusCode = HttpStatus.OK
                    Mono.empty()
                }

            // When / Then: WARN + endpoint_slow, outcome stays success
            filter.filter(exchange, chain).block()
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(keyValues(event))
                .containsEntry("endpoint_slow", true)
                .containsEntry("endpoint_outcome", "success")
        }

        @Test
        fun `should compare the slow threshold at full precision instead of truncated milliseconds`() {
            // What is tested: the threshold comparison - a 1.5 ms threshold used to truncate to
            //   1 ms and flag a 1 ms exchange.
            // Success criteria: 1.0 ms is NOT slow, 1.5 ms IS slow, under a 1.5 ms threshold.
            // Why it matters: truncating both sides inflated WARN logs for every threshold with
            //   sub-millisecond precision.
            // Given: a filter with a 1.5 ms threshold
            val precise =
                RequestLoggingWebFilter(
                    properties.copy(slowRequestThreshold = Duration.ofNanos(1_500_000)),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    meterRegistry,
                )

            fun slowFlagAfter(elapsedNanos: Long): Boolean {
                appender.list.clear()
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
                precise
                    .filter(
                        exchange,
                        WebFilterChain { ex ->
                            ticker.addAndGet(elapsedNanos)
                            ex.response.statusCode = HttpStatus.OK
                            Mono.empty()
                        },
                    ).block()
                return keyValues(appender.list.single()).containsKey("endpoint_slow")
            }

            // When/Then: just below is not slow, exactly at is slow
            assertThat(slowFlagAfter(1_000_000)).isFalse()
            assertThat(slowFlagAfter(1_500_000)).isTrue()
        }

        @Test
        fun `should defer the error emission until the response commits with the rendered status`() {
            // What is tested: the commit-deferred error path - the reactive analog of the servlet twin's
            //   request-destruction emission.
            // Success criteria: after the error signal NOTHING is logged (only the breadcrumb on the
            //   module logger); once the upstream handler renders and the response commits, exactly one
            //   ERROR event carries the FINAL 500 and the original cause; the error itself propagates
            //   unchanged.
            // Why it matters: emitting at the error signal would log the pre-rendering status - the exact
            //   wart the servlet twin eliminated; the deferral is what keeps the twins' semantics equal.
            // Given: a failing chain
            val boom = IllegalStateException("boom")
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the chain errors (response still uncommitted) - the error SIGNAL must pass the filter
            //   unchanged, and nothing may be logged before it does
            StepVerifier
                .create(filter.filter(exchange, WebFilterChain { Mono.error(boom) }))
                .expectErrorSatisfies { assertThat(it).isSameAs(boom) }
                .verify()

            // Then: no event yet
            assertThat(appender.list).isEmpty()

            // When: the upstream error handling renders and commits the response
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            exchange.response.setComplete().block()

            // Then: one ERROR event with the rendered status and the cause
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(event.throwableProxy?.message).isEqualTo("boom")
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "failure")
                .containsEntry("endpoint_response_status_code", 500)
        }

        @Test
        fun `should observe status and header mutations of later commit actions on the deferred error path`() {
            // What is tested: the commit-action ORDERING of the deferred error path - Spring runs
            //   beforeCommit actions in registration order,
            //   so the module's callback must be registered AFTER the chain ran, behind every action a
            //   downstream filter registered (security/session header writers, a status mutation).
            // Success criteria: a downstream filter registers an action that turns the rendered 500 into
            //   a 503 and adds a selected response header; the single ERROR event carries 503 and that
            //   header - exactly what the response applies - not the pre-action 500.
            // Why it matters: registered at filter entry, the callback ran FIRST and logged a status the
            //   client never received and a header set that was not yet complete.
            // Given: a filter selecting the late header, and a chain that registers the later action
            //   BEFORE erroring
            val selecting =
                RequestLoggingWebFilter(
                    properties.copy(responseHeaders = HeaderLogProperties(includes = listOf("X-Late"))),
                    NanoTimeSource { ticker.get() },
                    CorrelationIdGenerator { "generated-42" },
                    meterRegistry,
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    ex.response.beforeCommit {
                        Mono.fromRunnable {
                            ex.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
                            ex.response.headers.add("X-Late", "late")
                        }
                    }
                    Mono.error(IllegalStateException("boom"))
                }
            catchThrowable { selecting.filter(exchange, chain).block() }
            assertThat(appender.list).isEmpty()

            // When: the upstream error handling renders 500 and commits - the later action then mutates
            exchange.response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            exchange.response.setComplete().block()

            // Then: the event carries what the response applied, status and header alike
            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.ERROR)
            assertThat(keyValues(event)).containsEntry("endpoint_response_status_code", 503)
            assertThat(keyValues(event)["endpoint_response_headers"].toString()).contains("X-Late:\"late\"")
        }

        @Test
        fun `should log outcome cancelled with a dash status when the client disconnects`() {
            // What is tested: the reactive disposition the servlet twin does not have - a cancelled
            //   subscription (client disconnect), typically without a committed response.
            // Success criteria: one WARN event, outcome cancelled, message shows '-> -' and the status
            //   field is absent rather than invented.
            // Why it matters: without the flag a torn-down exchange would log as a success; without the
            //   dash it would log a status the client never saw.
            // Given: a chain that never completes
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))

            // When: the subscriber cancels (client disconnect) - the CANCEL signal, verified as such
            StepVerifier
                .create(filter.filter(exchange, WebFilterChain { Mono.never() }))
                .thenCancel()
                .verify()

            // Then: WARN, cancelled, no status
            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("-> - [")
            assertThat(keyValues(event))
                .containsEntry("endpoint_outcome", "cancelled")
                .doesNotContainKey("endpoint_response_status_code")
        }
    }

    @Nested
    inner class `Exclusions and start line` {
        @Test
        fun `should not log an excluded path at all`() {
            // Given: a filter with an exclusion and a request below it
            val excluding =
                RequestLoggingWebFilter(
                    properties.copy(excludePathPrefixes = listOf("/actuator/health")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health/liveness"))

            // When / Then: chain passes, nothing logged
            excluding.filter(exchange, okChain()).block()
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should match activation against the path within the application under a base path`() {
            // What is tested: finding 3 of the repo-wide code analysis of 2026-08-30 - activation must
            //   match what the WebFlux handler mapping matches, the path WITHIN the application, not
            //   the full request path that includes a configured base path.
            // Success criteria: under base path /app, /app/api/things is logged by include /api/** and
            //   /app/actuator/health is excluded by /actuator/health; matching against the full path
            //   would silently invert both.
            // Why it matters: on a non-root deployment, include patterns modeled after the routes
            //   otherwise match nothing - total, silent loss of exchange logging exactly where the
            //   operator configured it.
            // Given: a filter scoped to /api/** with /actuator/health excluded, under base path /app
            val scoped =
                RequestLoggingWebFilter(
                    properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/actuator/health")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val included =
                MockServerWebExchange.from(MockServerHttpRequest.get("/app/api/things").contextPath("/app"))
            val excluded =
                MockServerWebExchange.from(MockServerHttpRequest.get("/app/actuator/health").contextPath("/app"))

            // When: both exchanges run through the filter
            scoped.filter(included, okChain()).block()
            scoped.filter(excluded, okChain()).block()

            // Then: exactly the included exchange is logged, under its full (base-path-keeping) path
            val event = appender.list.single()
            assertThat(event.formattedMessage).contains("GET /app/api/things")
        }

        @Test
        fun `should match activation on the raw request path exactly as the WebFlux router does`() {
            // What is tested: twin parity with the servlet module's percent-encoding fix -
            //   activation must see the request target the way
            //   the router does: the raw RequestPath, segments decoded for matching, once.
            // Success criteria: `/%61pi/things` is included (router serves it under /api/**) and logs
            //   the raw path; `/api%2Fthings` is NOT included (the router sees one segment "api/things"
            //   and would not serve it) - the old decoded-then-reparsed path decoded it twice and
            //   accepted it; `/%61ctuator/health` is excluded.
            // Why it matters: activation and routing disagreeing means exchanges logged that were never
            //   served, or - in the other direction - served exchanges missing from the log.
            // Given: /api/** included, /actuator/health excluded
            val scoped =
                RequestLoggingWebFilter(
                    properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/actuator/health")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )

            fun exchangeFor(rawTarget: String) = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawTarget)))

            // When: the three encoded targets run
            scoped.filter(exchangeFor("/%61pi/things"), okChain()).block()
            scoped.filter(exchangeFor("/api%2Fthings"), okChain()).block()
            scoped.filter(exchangeFor("/%61ctuator/health"), okChain()).block()

            // Then: exactly the router-served one was logged, with the raw path
            assertThat(appender.list).hasSize(1)
            assertThat(keyValues(appender.list.single())).containsEntry("endpoint_url_path", "/%61pi/things")
        }

        @Test
        fun `should be active only for endpoints matching an include pattern`() {
            // Given: a filter restricted to /api/** - identical semantics to the servlet twin
            val including =
                RequestLoggingWebFilter(
                    properties.copy(includePathPatterns = listOf("/api/**")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )

            // When: one matching and one non-matching exchange
            val matching = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            including.filter(matching, okChain()).block()
            val other = MockServerWebExchange.from(MockServerHttpRequest.get("/static/logo.png"))
            including.filter(other, okChain()).block()

            // Then: only the matching one was logged and echoed
            assertThat(appender.list).hasSize(1)
            assertThat(keyValues(appender.list.single())).containsEntry("endpoint_url_path", "/api/things")
            assertThat(matching.response.headers.getFirst(properties.correlationIdHeader)).isEqualTo("generated-42")
            assertThat(other.response.headers.getFirst(properties.correlationIdHeader)).isNull()
        }

        @Test
        fun `should let an exclude win inside an included pattern`() {
            // Given: /api/** included, /api/internal excluded
            val filterUnderTest =
                RequestLoggingWebFilter(
                    properties.copy(includePathPatterns = listOf("/api/**"), excludePathPrefixes = listOf("/api/internal")),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )

            // When / Then: the exclude wins
            filterUnderTest
                .filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/internal/jobs")), okChain())
                .block()
            assertThat(appender.list).isEmpty()
        }

        @Test
        fun `should reject an invalid include pattern at construction time`() {
            // Given/When: a broken pattern at construction
            val thrown =
                catchThrowable {
                    RequestLoggingWebFilter(
                        properties.copy(includePathPatterns = listOf("/api/{unclosed")),
                        { ticker.get() },
                        { "generated-42" },
                        SimpleMeterRegistry(),
                    )
                }

            // Then: it fails fast with the PARSER's exception, and the diagnostic names the malformed
            //   pattern - a bare non-null check would pass for any unrelated constructor failure.
            assertThat(thrown).isInstanceOf(PatternParseException::class.java)
            assertThat((thrown as PatternParseException).toDetailedString()).contains("/api/{unclosed")
        }

        @Test
        fun `should announce the exchange before the chain when enabled`() {
            // Given: start-line logging and a chain that observes the log stream mid-flight
            val startLogging =
                RequestLoggingWebFilter(
                    properties.copy(logRequestStart = true),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            var eventsAtChainTime = listOf<String>()
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/things"))
            val chain =
                WebFilterChain { ex ->
                    eventsAtChainTime = appender.list.map { it.formattedMessage }
                    ex.response.statusCode = HttpStatus.OK
                    Mono.empty()
                }

            // When / Then: the arrival line was already visible while the chain ran; only the completion
            //   line carries the outcome
            startLogging.filter(exchange, chain).block()
            assertThat(eventsAtChainTime)
                .containsExactly("Endpoint http exchange started GET /api/things [endpoint_request_id=generated-42]")
            assertThat(appender.list).hasSize(2)
            assertThat(keyValues(appender.list.first())).doesNotContainKey("endpoint_outcome")
            // Twin parity: the arrival line carries the identity as MDC fields,
            // exactly like the servlet twin's chain-scoped arrival line.
            assertThat(appender.list.first().mdcPropertyMap)
                .containsEntry(MdcKeys.REQUEST_ID, "generated-42")
                .containsEntry(MdcKeys.ROUTE, "/api/things")
            assertThat(keyValues(appender.list.last())).containsEntry("endpoint_outcome", "success")
        }
    }
}
