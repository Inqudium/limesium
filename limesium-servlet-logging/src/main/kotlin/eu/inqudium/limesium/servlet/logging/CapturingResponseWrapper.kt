package eu.inqudium.limesium.servlet.logging

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Writer
import java.nio.charset.Charset

/**
 * Tees the response body into [capture] as the application writes it - the write-side twin of
 * [CapturingRequestWrapper].
 *
 * Every byte is forwarded to the real response FIRST and copied second, so commit semantics, streaming
 * and content length are exactly those of an unwrapped response. In particular there is no equivalent of
 * `ContentCachingResponseWrapper.copyBodyToResponse()`: nothing is withheld, so nothing has to be copied
 * back - which is also what makes this wrapper safe for async completion, where a held-back body could no
 * longer be written once the container has committed the response.
 *
 * The stream/writer either-or contract of the servlet API is enforced by the wrapped response itself (the
 * `super` calls hit it); this wrapper only mirrors whichever of the two the application chose.
 *
 * ## Buffer-replacing operations
 *
 * The capture is discarded together with the delegate's buffer for EVERY servlet operation that clears
 * it: [reset]/[resetBuffer], [sendError], and the buffer-clearing [sendRedirect] variants - `sendError`
 * and redirects clear the buffer per the servlet spec WITHOUT calling the reset overrides, so relying on
 * those alone logged discarded pre-error bytes as if they had flowed (finding 4 of
 * an internal code analysis). The committed-response rule is documented on [reset].
 *
 * ## Boundary - container error rendering
 *
 * The final body of an error dispatch (Boot's error page after `sendError` or an unhandled exception) is
 * written through the ORIGINAL response: `OncePerRequestFilter` skips the ERROR dispatch, so those bytes
 * bypass this tee and `endpoint_response_body` stays absent for container-rendered error responses -
 * a documented capture boundary, pinned by integration test (finding 4 of an internal code analysis).
 *
 * ## Writer fidelity
 *
 * The character API captures through one stateful encoder with the writer's lifecycle, and
 * `checkError()` on the returned writer reflects the DELEGATE `PrintWriter`'s suppressed error state -
 * both mechanisms and their failure modes are documented at [getWriter]. Residual: a chunk the delegate
 * `PrintWriter` swallowed an `IOException` for (client disconnect mid-write) is still counted as
 * flowed - `PrintWriter` suppresses the failure before any tee can see it; `checkError()` is the signal
 * the servlet API offers, and it is preserved.
 */
class CapturingResponseWrapper(
    response: HttpServletResponse,
    private val capture: BoundedBodyCapture,
) : HttpServletResponseWrapper(response) {
    private var teeStream: ServletOutputStream? = null
    private var teeWriter: PrintWriter? = null

    /** The charset used to encode captured writer output and to decode the capture for logging. */
    fun bodyCharset(): Charset = CapturingRequestWrapper.charsetOrDefault(characterEncoding)

    override fun getOutputStream(): ServletOutputStream {
        teeStream?.let { return it }
        val real = super.getOutputStream()
        return object : ServletOutputStream() {
            override fun write(b: Int) {
                real.write(b)
                capture.capture(b)
            }

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                real.write(bytes, offset, length)
                capture.capture(bytes, offset, length)
            }

            override fun isReady(): Boolean = real.isReady

            override fun setWriteListener(listener: WriteListener?) = real.setWriteListener(listener)

            override fun flush() = real.flush()

            override fun close() = real.close()
        }.also { teeStream = it }
    }

    /**
     * A reset of an UNCOMMITTED response discards everything buffered - nothing written so far ever
     * reached the client - so the capture is discarded with it; otherwise the logged body and the size
     * metric would report bytes the client never saw (e.g. a partially written response an
     * `@ExceptionHandler` throws away and rewrites). `super` runs FIRST: on a committed response it
     * throws `IllegalStateException` per the servlet spec, and the capture must then stay intact.
     *
     * Unlike [resetBuffer], a full reset also clears the delegate's writer-or-stream selection (Servlet
     * 6.1: "It is legal, for instance, to call getWriter, reset and then getOutputStream"; the previously
     * returned object is stale). The cached tee accessors are dropped in lockstep, so the next accessor
     * call goes through the delegate again - its either-or check, its freshly resolved charset, a fresh
     * capture encoder - instead of handing back a stale tee over a stale delegate object (finding 1 of
     * an internal code analysis).
     */
    override fun reset() {
        super.reset()
        capture.clear()
        teeStream = null
        teeWriter = null
    }

    override fun resetBuffer() {
        super.resetBuffer()
        capture.clear()
    }

    // sendError and the buffer-clearing redirects reset the DELEGATE's buffer per the servlet spec
    // without traversing reset()/resetBuffer(); the capture must follow the buffer (finding 4 of
    // an internal code analysis). Every variant is overridden because HttpServletResponseWrapper
    // delegates each one directly.

    override fun sendError(sc: Int) {
        super.sendError(sc)
        capture.clear()
    }

    override fun sendError(
        sc: Int,
        msg: String?,
    ) {
        super.sendError(sc, msg)
        capture.clear()
    }

    override fun sendRedirect(location: String) {
        super.sendRedirect(location)
        capture.clear()
    }

    override fun sendRedirect(
        location: String,
        sc: Int,
    ) {
        super.sendRedirect(location, sc)
        capture.clear()
    }

    override fun sendRedirect(
        location: String,
        clearBuffer: Boolean,
    ) {
        super.sendRedirect(location, clearBuffer)
        if (clearBuffer) {
            capture.clear()
        }
    }

    override fun sendRedirect(
        location: String,
        sc: Int,
        clearBuffer: Boolean,
    ) {
        super.sendRedirect(location, sc, clearBuffer)
        if (clearBuffer) {
            capture.clear()
        }
    }

    override fun getWriter(): PrintWriter {
        teeWriter?.let { return it }
        val real = super.getWriter()
        // The charset is resolved ONCE, at first writer access: from here on the servlet spec pins the
        // response encoding anyway, and the capture must encode exactly like the container does.
        val charset = bodyCharset()
        // ONE stateful encoder with the writer's lifecycle: a surrogate half pending at the end of a
        // write chunk stays in the encoder until its partner arrives (or close finalizes it) - the
        // chunk-local String.toByteArray conversion it replaces emitted replacement bytes for every
        // split sequence (finding 6 of an internal code analysis).
        val captureEncoder =
            OutputStreamWriter(
                object : OutputStream() {
                    override fun write(b: Int) = capture.capture(b)

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = capture.capture(bytes, offset, length)
                },
                charset,
            )
        val tee =
            object : Writer() {
                override fun write(
                    cbuf: CharArray,
                    off: Int,
                    len: Int,
                ) {
                    real.write(cbuf, off, len)
                    captureEncoder.write(cbuf, off, len)
                    // Completed characters land in the capture immediately (the emission at request
                    // destruction must not wait for a writer close the application may never call);
                    // only a pending surrogate half stays in the encoder.
                    captureEncoder.flush()
                }

                override fun flush() = real.flush()

                override fun close() {
                    captureEncoder.close()
                    real.close()
                }
            }
        // checkError() must also reflect the DELEGATE PrintWriter's suppressed-error state: the servlet
        // container hands out a PrintWriter that swallows IOExceptions into an internal flag, and an
        // outer PrintWriter over the tee would otherwise answer false after the real writer failed
        // (finding 7 of an internal code analysis). Note real.checkError() flushes, exactly as it would unwrapped.
        return object : PrintWriter(tee, false) {
            override fun checkError(): Boolean {
                val outer = super.checkError()
                return real.checkError() || outer
            }
        }.also { teeWriter = it }
    }
}
