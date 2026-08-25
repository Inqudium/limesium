package eu.inqudium.limesium.servlet.logging

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Tees the request body into [capture] as the APPLICATION reads it - a passive copy, never a pre-read.
 *
 * A replaying up-front buffer was deliberately rejected; this wrapper instead observes the
 * live stream, which means: no extra memory beyond the capture limit, no interference with streaming or
 * async processing, and the log shows exactly the bytes the application actually consumed (an unread body
 * is logged as absent, which is the truthful answer).
 *
 * [getReader] is overridden alongside [getInputStream]: the wrapper base class would otherwise hand out
 * the ORIGINAL request's reader and silently bypass the tee. The reader preserves the SERVLET decoding
 * contract exactly - the declared request encoding, the spec default ISO-8859-1 when none is declared,
 * and `UnsupportedEncodingException` for an unsupported one; the UTF-8 fallback of [bodyCharset] applies
 * ONLY to how the log line renders the captured bytes, never to what the application reads (finding 1
 * of an internal code analysis). The LOG charset is bound LATE - when the application first selects
 * a body API - not at construction: the servlet contract lets downstream code call
 * `setCharacterEncoding` until the body is consumed, and a charset frozen at filter entry would decode
 * the captured bytes with an encoding the application never used (finding 2 of
 * an internal code analysis). The wrapper also reproduces the delegate's stream/reader either-or
 * contract itself, because the tee satisfies both APIs from ONE delegate stream and the delegate can
 * therefore no longer see which public API the application chose (finding 12 of
 * an internal code analysis).
 *
 * ASYNC boundary: the tee lives on THIS wrapper. Spring MVC's async support starts async with
 * `startAsync(currentRequest, currentResponse)` and keeps the wrappers; the Servlet-specified
 * zero-argument `startAsync()` initializes its context with the ORIGINAL request/response, so bytes a
 * raw async cycle reads/writes flow beside the tee and are logged as absent - a documented contract
 * boundary, pinned by integration test (finding 3 of an internal code analysis).
 */
class CapturingRequestWrapper(
    request: HttpServletRequest,
    private val capture: BoundedBodyCapture,
) : HttpServletRequestWrapper(request) {
    private var teeStream: ServletInputStream? = null
    private var teeReader: BufferedReader? = null
    private var streamSelected = false

    private var boundBodyCharset: Charset? = null

    /**
     * The charset the captured bytes are decoded with for the LOG LINE only - never for the reader. Bound
     * when the application first selects the stream or the reader (the moment the servlet contract
     * freezes the encoding); for a body that was never read, resolved from the encoding current at the
     * time of the query.
     */
    val bodyCharset: Charset
        get() = boundBodyCharset ?: charsetOrDefault(characterEncoding)

    override fun getInputStream(): ServletInputStream {
        check(teeReader == null) { "getReader() has already been called on this request" }
        streamSelected = true
        return teeOverDelegate()
    }

    override fun getReader(): BufferedReader {
        teeReader?.let { return it }
        check(!streamSelected) { "getInputStream() has already been called on this request" }
        return BufferedReader(InputStreamReader(teeOverDelegate(), readerCharset())).also { teeReader = it }
    }

    /**
     * The tee stream. Besides copying, it records the READ STATE on the capture: selecting the stream
     * (or the reader over it) marks consumption as started; observing the end of the stream - an EOF
     * return from either `read`, or `isFinished` answering true for the non-blocking read loop - marks
     * it complete. Both are observations of what the application did, never an extra read: the tee
     * does not probe for EOF itself, so a body the application stopped reading stays PARTIAL.
     */
    private fun teeOverDelegate(): ServletInputStream {
        teeStream?.let { return it }
        boundBodyCharset = charsetOrDefault(characterEncoding)
        val real = super.getInputStream()
        capture.markStarted()
        return object : ServletInputStream() {
            override fun read(): Int {
                val b = real.read()
                if (b != -1) {
                    capture.capture(b)
                } else {
                    capture.markCompleted()
                }
                return b
            }

            override fun read(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val n = real.read(bytes, offset, length)
                if (n > 0) {
                    capture.capture(bytes, offset, n)
                } else if (n == -1) {
                    capture.markCompleted()
                }
                return n
            }

            override fun isFinished(): Boolean {
                val finished = real.isFinished
                if (finished) {
                    capture.markCompleted()
                }
                return finished
            }

            override fun isReady(): Boolean = real.isReady

            override fun setReadListener(listener: ReadListener?) = real.setReadListener(listener)

            override fun close() = real.close()
        }.also { teeStream = it }
    }

    /**
     * The charset the APPLICATION's reader decodes with - the servlet contract, unchanged by this
     * wrapper: the effective request encoding (which already includes container/context defaults the
     * delegate applies), the spec default ISO-8859-1 when none is declared, and
     * [UnsupportedEncodingException] for a declared-but-unsupported one, exactly as `getReader()` on an
     * unwrapped request would throw it.
     */
    private fun readerCharset(): Charset {
        val name = characterEncoding ?: return StandardCharsets.ISO_8859_1
        try {
            return Charset.forName(name)
        } catch (e: Exception) {
            throw UnsupportedEncodingException(name).apply { initCause(e) }
        }
    }

    companion object {
        /**
         * UTF-8 fallback instead of the servlet spec's ISO-8859-1 default: this charset only affects how
         * the LOG LINE renders the captured bytes, and modern payloads without a declared encoding are
         * far more likely UTF-8. The bytes and characters handed to the application are untouched - the
         * reader uses [CapturingRequestWrapper.readerCharset], which preserves the servlet contract.
         */
        fun charsetOrDefault(name: String?): Charset =
            try {
                name?.let { Charset.forName(it) }
            } catch (e: IllegalArgumentException) {
                // IllegalCharsetNameException / UnsupportedCharsetException: an undeclared-or-broken
                // encoding only affects the log rendering, never the application's reader.
                null
            } ?: StandardCharsets.UTF_8
    }
}
