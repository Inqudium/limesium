package eu.inqudium.limesium.servlet.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
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
 * Header capture and the tee-based body capture of [RequestLoggingFilter]: bodies are logged exactly as
 * they flowed through the exchange - what the application read, what it wrote - bounded by
 * [RequestLoggingProperties.maxBodyBytes], and the client-visible response is never altered by capturing.
 */
class RequestLoggingFilterBodyAndHeaderTest {
    private val ticker = AtomicLong(0)
    private val properties =
        RequestLoggingProperties(
            loggerName = "http-exchange-body-test",
            requestHeaders = HeaderLogProperties(includes = listOf("Accept"), masked = listOf("X-Api-Key")),
            responseHeaders = HeaderLogProperties(includes = listOf("Content-Type")),
            logRequestBody = true,
            logResponseBody = true,
            maxBodyBytes = 8,
        )
    private val filter = RequestLoggingFilter(properties, { ticker.get() }, { "generated-42" }, SimpleMeterRegistry())

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

    private fun keyValues(): Map<String, Any?> =
        appender.list
            .single()
            .keyValuePairs
            ?.associate { it.key to it.value } ?: emptyMap()

    /** Filter pass plus the request destruction the container would fire - the emission point. */
    private fun handle(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
        chain: FilterChain,
    ) {
        try {
            filter.doFilterInternal(request, response, chain)
        } finally {
            filter.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
        }
    }

    @Nested
    inner class `Request body capture` {
        @Test
        fun `should log the request body the application actually read`() {
            // Given: a request body and a chain that consumes it via the input stream
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("hello".toByteArray(StandardCharsets.UTF_8))
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).inputStream.readAllBytes() }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: the logged body is exactly what was read
            assertThat(keyValues()).containsEntry("endpoint_request_body", "hello")
        }

        @Test
        fun `should log the request body when the application reads via the reader`() {
            // Given: a chain that consumes the body through the CHARACTER API - the wrapper must route the
            //   reader over its own tee stream, or the capture would be silently bypassed
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("hallo".toByteArray(StandardCharsets.UTF_8))
            request.characterEncoding = "UTF-8"
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).reader.readText() }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: the logged body is what the reader delivered
            assertThat(keyValues()).containsEntry("endpoint_request_body", "hallo")
        }

        @Test
        fun `should hand the application container-default decoded text when no encoding is declared`() {
            // What is tested: the reader's charset transparency (review finding 1, internal
            //   analysis) - with no declared request encoding, the wrapper's reader must decode with the
            //   servlet default ISO-8859-1 like an unwrapped request, NOT with the log-side UTF-8
            //   fallback.
            // Success criteria: the application reads the exact ISO-8859-1 text; the fixture's non-ASCII
            //   byte is one whose UTF-8 and ISO-8859-1 decodings differ, so the old behavior would have
            //   delivered a replacement character instead.
            // Why it matters: a logging feature that changes the characters the application receives can
            //   corrupt validation, persistence and responses - the one thing a passive tee must never do.
            // Given: an ISO-8859-1 body carrying a non-ASCII character, no declared encoding
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("hällo".toByteArray(StandardCharsets.ISO_8859_1))
            var applicationRead = ""
            val chain = FilterChain { req, _ -> applicationRead = (req as HttpServletRequest).reader.readText() }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: the application saw the container-default decoding, unchanged by the wrapper
            assertThat(applicationRead).isEqualTo("hällo")
        }

        @Test
        fun `should log the body with the encoding the chain set before reading, not the one at filter entry`() {
            // What is tested: the late binding of the LOG charset (finding 2 of the internal
            //   analysis) - the servlet contract allows setCharacterEncoding until the body is consumed,
            //   so downstream code may change the encoding after the filter constructed its wrapper.
            // Success criteria: with ISO-8859-1 declared at entry and the chain switching to UTF-8
            //   before reading a UTF-8 body, BOTH the application-visible text and the logged body read
            //   "h\u00e4llo"; a charset frozen at entry would have logged the two-byte sequence as "h\u00c3\u00a4llo".
            // Why it matters: the logged body is forensic evidence; decoding it with an encoding the
            //   application never used makes it wrong on a fully valid servlet flow.
            // Given: a UTF-8 body under an ISO-8859-1 declaration
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("h\u00e4llo".toByteArray(StandardCharsets.UTF_8))
            request.characterEncoding = "ISO-8859-1"
            var applicationRead = ""
            val chain =
                FilterChain { req, _ ->
                    req.characterEncoding = "UTF-8"
                    applicationRead = (req as HttpServletRequest).reader.readText()
                }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: application and log agree on the encoding that was actually in force
            assertThat(applicationRead).isEqualTo("h\u00e4llo")
            assertThat(keyValues()).containsEntry("endpoint_request_body", "h\u00e4llo")
        }

        @Test
        fun `should enforce the stream-reader either-or contract like an unwrapped request`() {
            // What is tested: the servlet exclusivity contract the wrapper must reproduce itself
            //   (finding 12 of an internal code analysis) - the tee serves both public APIs from ONE delegate stream, so
            //   the delegate can no longer see which API the application chose.
            // Success criteria: reader-after-stream and stream-after-reader both throw
            //   IllegalStateException; the same accessor repeated stays legal (cached object).
            // Why it matters: erroneous downstream code consuming both APIs would fail on an unwrapped
            //   request - enabling body logging must not silently legalize it.
            // Given/When/Then: stream first, then reader
            val streamFirst =
                CapturingRequestWrapper(
                    MockHttpServletRequest("POST", "/api/things").apply { setContent("x".toByteArray()) },
                    BoundedBodyCapture(8),
                )
            streamFirst.inputStream
            streamFirst.inputStream
            assertThat(
                org.assertj.core.api.Assertions
                    .catchThrowable { streamFirst.reader },
            ).isInstanceOf(IllegalStateException::class.java)

            // Given/When/Then: reader first, then stream
            val readerFirst =
                CapturingRequestWrapper(
                    MockHttpServletRequest("POST", "/api/things").apply { setContent("x".toByteArray()) },
                    BoundedBodyCapture(8),
                )
            readerFirst.reader
            readerFirst.reader
            assertThat(
                org.assertj.core.api.Assertions
                    .catchThrowable { readerFirst.inputStream },
            ).isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `should omit the request body key when the application read nothing`() {
            // What is tested: the tee's truthfulness - a body that was never consumed by the application.
            // Success criteria: no requestBody key at all, rather than an empty or fabricated value.
            // Why it matters: a pre-buffering design would log bytes the
            //   application never touched; the tee design makes "logged" mean "actually flowed".
            // Given: a request body that the chain ignores
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("ignored".toByteArray(StandardCharsets.UTF_8))

            // When: the filter handles the exchange with a chain that never reads
            handle(request, MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the line has no request body key
            assertThat(keyValues()).doesNotContainKey("endpoint_request_body")
        }

        @Test
        fun `should truncate the logged request body at the configured limit and say so`() {
            // Given: a 16-byte body against an 8-byte capture limit
            val request = MockHttpServletRequest("POST", "/api/things")
            request.setContent("0123456789ABCDEF".toByteArray(StandardCharsets.UTF_8))
            val chain = FilterChain { req, _ -> (req as HttpServletRequest).inputStream.readAllBytes() }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: the log shows the captured prefix plus an explicit truncation note
            assertThat(keyValues()).containsEntry("endpoint_request_body", "01234567... [truncated, 16 bytes total]")
        }
    }

    @Nested
    inner class `Response body capture` {
        @Test
        fun `should log the response body written through the output stream and leave the client response intact`() {
            // Given: a chain that writes bytes through the stream API
            val response = MockHttpServletResponse()
            val chain = FilterChain { _, res -> (res as HttpServletResponse).outputStream.write("bytes!".toByteArray()) }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: the body is logged AND fully delivered to the client (tee, not cache)
            assertThat(keyValues()).containsEntry("endpoint_response_body", "bytes!")
            assertThat(response.contentAsString).isEqualTo("bytes!")
        }

        @Test
        fun `should log the response body written through the writer and leave the client response intact`() {
            // Given: a chain that writes through the CHARACTER API
            val response = MockHttpServletResponse()
            response.characterEncoding = "UTF-8"
            val chain = FilterChain { _, res -> (res as HttpServletResponse).writer.write("text!") }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: the body is logged AND fully delivered to the client
            assertThat(keyValues()).containsEntry("endpoint_response_body", "text!")
            assertThat(response.contentAsString).isEqualTo("text!")
        }

        @Test
        fun `should encode a surrogate pair split across writer calls exactly like the client bytes`() {
            // What is tested: the writer tee's byte fidelity (review finding 6, internal
            //   analysis) - the capture runs through ONE stateful encoder with the writer's lifecycle,
            //   so a surrogate pair whose halves arrive in separate write calls is encoded as one
            //   character; the old chunk-local String.toByteArray produced replacement bytes for each
            //   half.
            // Success criteria: the logged body and the client body are the identical two-char (4-byte
            //   UTF-8) sequence although the halves crossed a write boundary.
            // Why it matters: replacement bytes in the capture make the logged text differ from the
            //   client response and the body-size metric count the wrong bytes - silently, only for
            //   non-BMP characters.
            // Given: a chain that writes the two halves of one supplementary character separately
            val response = MockHttpServletResponse()
            response.characterEncoding = "UTF-8"
            val chain =
                FilterChain { _, res ->
                    val writer = (res as HttpServletResponse).writer
                    writer.write("\uD83D")
                    writer.write("\uDE00")
                    writer.flush()
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: capture and client agree on the joined character
            assertThat(keyValues()).containsEntry("endpoint_response_body", "😀")
            assertThat(response.contentAsString).isEqualTo("😀")
        }

        @Test
        fun `should surface the delegate writer's suppressed error state through checkError`() {
            // What is tested: the PrintWriter error contract (review finding 7, internal
            //   analysis) - the container's writer IS a PrintWriter that swallows IOExceptions into an
            //   internal flag; the wrapper's outer PrintWriter used to consult only its own healthy tee
            //   and answered false after the real writer had failed.
            // Success criteria: after a write against a delegate whose underlying writer throws,
            //   checkError() on the wrapper's writer returns true.
            // Why it matters: checkError() is the ONLY failure signal the servlet writer API offers;
            //   application code polling it lost the client-disconnect signal the moment body capture
            //   was enabled.
            // Given: a response whose container writer fails on every write and flush
            val failingDelegate =
                object : MockHttpServletResponse() {
                    override fun getWriter(): java.io.PrintWriter =
                        java.io.PrintWriter(
                            object : java.io.Writer() {
                                override fun write(
                                    cbuf: CharArray,
                                    off: Int,
                                    len: Int,
                                ): Unit = throw java.io.IOException("client gone")

                                override fun flush(): Unit = throw java.io.IOException("client gone")

                                override fun close() = Unit
                            },
                            false,
                        )
                }
            val wrapper = CapturingResponseWrapper(failingDelegate, BoundedBodyCapture(8))

            // When: the application writes and polls the error state
            val writer = wrapper.writer
            writer.write("x")
            writer.flush()

            // Then: the suppressed delegate failure is visible
            assertThat(writer.checkError()).isTrue()
        }
    }

    @Nested
    inner class `Response reset handling` {
        @Test
        fun `should log only the content that survived a resetBuffer`() {
            // What is tested: the tee's alignment with container buffer semantics (finding 3 of an internal code analysis) -
            //   a reset of an uncommitted response discards everything written so far.
            // Success criteria: after write-reset-write, the logged body and the client body BOTH show
            //   only the post-reset content.
            // Why it matters: error handlers rewrite partially written responses; a tee that kept the
            //   discarded bytes would log content the client never received - on exactly the exchanges
            //   one investigates most.
            // Given: a chain that writes, discards, and rewrites
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { _, res ->
                    res as HttpServletResponse
                    res.outputStream.write("discarded".toByteArray())
                    res.resetBuffer()
                    res.outputStream.write("final".toByteArray())
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: log and client agree on the surviving content
            assertThat(keyValues()).containsEntry("endpoint_response_body", "final")
            assertThat(response.contentAsString).isEqualTo("final")
        }

        @Test
        fun `should hand out a fresh writer over the delegate after a full reset`() {
            // What is tested: the accessor-state half of reset() (review finding 1,
            //   internal analysis) - Servlet 6.1 clears the writer/stream selection on
            //   reset(), so the accessor returned afterwards must be a NEW tee over the delegate's new
            //   writer, not the cached one over a stale delegate object.
            // Success criteria: the post-reset writer is a different instance, both the client body and
            //   the logged body show only the post-reset content, and the capture encodes with the
            //   charset set AFTER the reset (reset also clears the encoding the first writer pinned).
            // Why it matters: exception handlers reset and rewrite responses; a stale cached writer would
            //   encode with the old charset and, on a real container, write through an object whose
            //   behavior after reset is undefined - client-visible corruption only when logging is on.
            // Given: a chain that writes via a writer, resets, switches charset and writes again
            val response = MockHttpServletResponse()
            var sameWriter: Boolean? = null
            val chain =
                FilterChain { _, res ->
                    res as HttpServletResponse
                    res.characterEncoding = "UTF-8"
                    val first = res.writer
                    first.write("discarded")
                    res.reset()
                    res.characterEncoding = "ISO-8859-1"
                    val second = res.writer
                    sameWriter = first === second
                    second.write("h\u00e4llo")
                    second.flush()
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: a fresh writer, and log and client agree on the post-reset content in the new charset
            assertThat(sameWriter).isFalse()
            assertThat(response.contentAsByteArray).isEqualTo("h\u00e4llo".toByteArray(StandardCharsets.ISO_8859_1))
            assertThat(keyValues()).containsEntry("endpoint_response_body", "h\u00e4llo")
        }

        @Test
        fun `should allow switching from writer to stream and back across a full reset`() {
            // What is tested: the mode switch Servlet 6.1 explicitly permits ("getWriter, reset and then
            //   getOutputStream") - the wrapper must not bypass the delegate's either-or check through a
            //   cached opposite accessor, and the tee must follow the mode actually in use.
            // Success criteria: no IllegalStateException, and both the client body and the logged body
            //   show exactly the bytes written through the post-reset accessor.
            // Why it matters: valid servlet code must behave identically with and without the logging
            //   filter installed.
            // Given: writer -> reset -> stream -> reset -> writer
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { _, res ->
                    res as HttpServletResponse
                    res.writer.write("via-writer")
                    res.reset()
                    res.outputStream.write("via-stream".toByteArray())
                    res.reset()
                    res.writer.write("final")
                    res.writer.flush()
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: only the last mode's content survives, in log and on the client
            assertThat(response.contentAsString).isEqualTo("final")
            assertThat(keyValues()).containsEntry("endpoint_response_body", "final")
        }

        @Test
        fun `should discard the capture when sendError replaces the buffered response`() {
            // What is tested: the buffer-replacing operations beyond reset()/resetBuffer() - sendError
            //   clears the delegate's buffer per the servlet spec WITHOUT calling the reset overrides,
            //   so the capture must follow the buffer through the wrapper's own sendError override.
            // Success criteria: after write-then-sendError, the event carries NO response-body field -
            //   the pre-error bytes never reached the client (the rendered error page bypasses the tee
            //   through the container's ERROR dispatch, the documented boundary).
            // Why it matters: a stale "discarded" body on exactly the failure responses operators
            //   investigate is worse than an absent one - it asserts content the client never received.
            // Given: a chain that writes and then replaces the response via sendError
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { _, res ->
                    res as HttpServletResponse
                    res.outputStream.write("discarded".toByteArray())
                    res.sendError(500)
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: WARN for the 5xx, and no stale body field
            assertThat(appender.list.single().level).isEqualTo(Level.WARN)
            assertThat(keyValues()).doesNotContainKey("endpoint_response_body")
        }

        @Test
        fun `should discard the capture when a redirect clears the buffered response`() {
            // What is tested: the redirect half of finding 4 - sendRedirect also clears the buffer per
            //   the servlet spec, again without traversing the reset overrides.
            // Success criteria: after write-then-sendRedirect, no response-body field is logged.
            // Why it matters: same stale-body defect as sendError, on the redirect path.
            // Given: a chain that writes and then redirects
            val response = MockHttpServletResponse()
            val chain =
                FilterChain { _, res ->
                    res as HttpServletResponse
                    res.outputStream.write("discarded".toByteArray())
                    res.sendRedirect("/elsewhere")
                }

            // When: the filter handles the exchange
            handle(MockHttpServletRequest("GET", "/api/things"), response, chain)

            // Then: the redirect took effect and no stale body field is logged
            assertThat(response.redirectedUrl).isEqualTo("/elsewhere")
            assertThat(keyValues()).doesNotContainKey("endpoint_response_body")
        }
    }

    @Nested
    inner class `Header masking and selection` {
        private val maskingFilter =
            RequestLoggingFilter(
                properties.copy(
                    requestHeaders =
                        HeaderLogProperties(
                            includes = listOf("Accept", "X-Api-Key"),
                            masked = listOf("x-api-key"),
                        ),
                ),
                { ticker.get() },
                { "generated-42" },
                SimpleMeterRegistry(),
            )

        private fun handleWith(
            filterUnderTest: RequestLoggingFilter,
            request: MockHttpServletRequest,
        ) {
            filterUnderTest.doFilterInternal(request, MockHttpServletResponse(), FilterChain { _, _ -> })
            filterUnderTest.exchangeCompletionListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
        }

        @Test
        fun `should replace a masked header value by a stable fingerprint that never shows the value`() {
            // What is tested: the masking contract - non-reversible but STABLE, and case-insensitive on
            //   the header name (configured lowercase, sent canonical).
            // Success criteria: the raw value appears nowhere in the event; the rendered fingerprint has
            //   the length:hex shape; the unmasked header stays verbatim.
            // Why it matters: masked headers exist to keep a secret out of the log WITHOUT losing the
            //   ability to correlate requests that carried the same token.
            // Given: a request with a secret header and a plain header
            val request = MockHttpServletRequest("GET", "/api/things")
            request.addHeader("X-Api-Key", "super-secret-token")
            request.addHeader("Accept", "text/plain")

            // When: the masking filter handles the exchange
            handleWith(maskingFilter, request)

            // Then: the fingerprint replaces the secret, the plain header is verbatim
            val headers = keyValues()["endpoint_request_headers"].toString()
            assertThat(headers).doesNotContain("super-secret-token")
            assertThat(headers).contains("Accept:\"text/plain\"")
            assertThat(headers).contains("X-Api-Key:\"${HeaderLogProperties.mask("super-secret-token")}\"")
            assertThat(headers).contains("X-Api-Key:\"18:")
        }

        @Test
        fun `should render equal-length values that collided under String hashCode as distinct fingerprints`() {
            // What is tested: the collision model of the fingerprint after its widening (finding 3 of the
            //   reactive twin's internal analysis, applied in lockstep) - length plus 32-bit
            //   String.hashCode was NOT injective; length plus a 64-bit SHA-256 prefix separates the
            //   well-known colliding pair.
            // Success criteria: "Aa" and "BB" (equal hashCode, both length 2) mask to DIFFERENT strings
            //   of the new 16-hex format.
            // Why it matters: a collision made two distinct secrets one logged identity - a wrong
            //   correlation conclusion that the widened fingerprint makes negligible.
            // Given: two distinct values with equal length and equal hashCode
            assertThat("Aa".hashCode()).isEqualTo("BB".hashCode())

            // When/Then: the fingerprints differ and carry the widened format
            assertThat(HeaderLogProperties.mask("Aa")).isNotEqualTo(HeaderLogProperties.mask("BB"))
            assertThat(HeaderLogProperties.mask("Aa")).matches("2:[0-9a-f]{16}")
        }

        @Test
        fun `should render equal masked values as equal fingerprints and different values as different ones`() {
            // What is tested: the stability half of the masking contract, across two exchanges.
            // Success criteria: same secret -> same rendered headers field; different secret -> different.
            // Why it matters: correlation by fingerprint is the entire reason the value is hashed instead
            //   of simply dropped.
            // Given/When: three exchanges - two with the same secret, one with a different one
            fun headersFor(secret: String): String {
                appender.list.clear()
                val request = MockHttpServletRequest("GET", "/api/things")
                request.addHeader("X-Api-Key", secret)
                handleWith(maskingFilter, request)
                return keyValues()["endpoint_request_headers"].toString()
            }
            val first = headersFor("token-alpha")
            val second = headersFor("token-alpha")
            val other = headersFor("token-beta")

            // Then: equal values collide, different values do not
            assertThat(first).isEqualTo(second)
            assertThat(first).isNotEqualTo(other)
        }

        @Test
        fun `should include every header via the wildcard except the excluded ones`() {
            // Given: a wildcard include with one exclusion
            val wildcardFilter =
                RequestLoggingFilter(
                    properties.copy(
                        requestHeaders =
                            HeaderLogProperties(
                                includes = listOf("*"),
                                excludes = listOf("cookie"),
                            ),
                    ),
                    { ticker.get() },
                    { "generated-42" },
                    SimpleMeterRegistry(),
                )
            val request = MockHttpServletRequest("GET", "/api/things")
            request.addHeader("Accept", "text/plain")
            request.addHeader("User-Agent", "it-agent")
            request.addHeader("Cookie", "session=opaque")

            // When: the filter handles the exchange
            handleWith(wildcardFilter, request)

            // Then: everything is logged except the exclusion (matched case-insensitively)
            val headers = keyValues()["endpoint_request_headers"].toString()
            assertThat(headers).contains("Accept:\"text/plain\"")
            assertThat(headers).contains("User-Agent:\"it-agent\"")
            assertThat(headers).doesNotContain("session=opaque")
            assertThat(headers).doesNotContain("Cookie")
        }
    }

    @Nested
    inner class `Header capture` {
        @Test
        fun `should log the configured request and response headers`() {
            // Given: a request carrying a selected header and a chain setting a selected response header
            val request = MockHttpServletRequest("GET", "/api/things")
            request.addHeader("Accept", "application/json")
            request.addHeader("Authorization", "Bearer opaque")
            val chain = FilterChain { _, res -> (res as HttpServletResponse).setHeader("Content-Type", "application/json") }

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), chain)

            // Then: exactly the selected headers appear, rendered into one field per direction,
            //   unselected ones do not
            assertThat(keyValues())
                .containsEntry("endpoint_request_headers", "[Accept:\"application/json\"]")
                .containsEntry("endpoint_response_headers", "[Content-Type:\"application/json\"]")
            assertThat(keyValues()["endpoint_request_headers"].toString()).doesNotContain("Authorization")
        }

        @Test
        fun `should log every value of a repeated header, comma-joined`() {
            // What is tested: multi-value header resolution (finding 7 of an internal code analysis) - a single-value
            //   getHeader would silently truncate repeated headers.
            // Success criteria: both Accept values appear in the rendered field, comma-joined.
            // Why it matters: repeated headers (Accept, Set-Cookie) are exactly the ones whose LOSS is
            //   invisible - the field still shows a plausible single value.
            // Given: a request repeating the selected header
            val request = MockHttpServletRequest("GET", "/api/things")
            request.addHeader("Accept", "text/plain")
            request.addHeader("Accept", "text/html")

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the field carries all values
            assertThat(keyValues()).containsEntry("endpoint_request_headers", "[Accept:\"text/plain, text/html\"]")
        }

        @Test
        fun `should omit a configured header that the exchange does not carry`() {
            // Given: a request without the selected Accept header
            val request = MockHttpServletRequest("GET", "/api/things")

            // When: the filter handles the exchange
            handle(request, MockHttpServletResponse(), FilterChain { _, _ -> })

            // Then: the keys are absent instead of logged as null or empty
            assertThat(keyValues())
                .doesNotContainKey("endpoint_request_headers")
                .doesNotContainKey("endpoint_response_headers")
        }
    }
}
