package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyReadState
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The request tee's transparency towards the application: the container stream's `mark`/`reset`
 * contract is forwarded as it is - present on a rewindable stream a filter ahead handed down, absent
 * on a container stream - and a rewind moves the capture with the stream.
 */
class CapturingRequestWrapperMarkResetTest {
    /** A request whose body stream is [stream] - a filter ahead of the logging may hand down any stream. */
    private fun requestWith(stream: ServletInputStream) =
        object : HttpServletRequestWrapper(MockHttpServletRequest()) {
            override fun getInputStream(): ServletInputStream = stream
        }

    /** A rewindable stream over a byte array, as a caching wrapper hands down. */
    private fun rewindable(text: String): ServletInputStream =
        object : ServletInputStream() {
            private val bytes = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int = bytes.read()

            override fun markSupported(): Boolean = true

            override fun mark(readlimit: Int) = bytes.mark(readlimit)

            override fun reset() = bytes.reset()

            override fun isFinished(): Boolean = bytes.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) = Unit
        }

    /** A container-like stream: no mark support, reset refused. */
    private fun containerLike(text: String): ServletInputStream =
        object : ServletInputStream() {
            private val bytes = ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int = bytes.read()

            override fun markSupported(): Boolean = false

            override fun reset(): Unit = throw IOException("mark/reset not supported")

            override fun isFinished(): Boolean = bytes.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) = Unit
        }

    @Test
    fun `should keep a rewindable stream rewindable and count the replayed bytes once`() {
        // What is tested: the tee over a mark-capable stream - markSupported stays true, and a peek
        //   (mark(1), read(), reset()) followed by the full read is captured exactly once.
        // Success criteria: markSupported is true through the wrapper; after the peek and the full
        //   read the total is the body's 5 bytes, the logged text is the body without a duplicated
        //   first byte, and the state is COMPLETE.
        // Why it matters: a bare ServletInputStream subclass answered false and threw on reset, so the
        //   logging's presence changed what the application saw on its stream - and forwarding the
        //   reset without rewinding the capture would have counted and logged the peeked byte twice.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = CapturingRequestWrapper(requestWith(rewindable("hello")), capture).inputStream

        // When: the peek-and-rewind, then the full read
        assertThat(body.markSupported()).isTrue()
        body.mark(1)
        assertThat(body.read()).isEqualTo('h'.code)
        body.reset()
        val text = body.readAllBytes().toString(StandardCharsets.UTF_8)

        // Then
        assertThat(text).isEqualTo("hello")
        assertThat(capture.totalBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
    }

    @Test
    fun `should rewind the read state with the bytes when a fully read body is reset`() {
        // What is tested: the read state is part of the mark - a body read to its EOF, then reset to
        //   a mark after the first byte, is no longer complete until the replay reaches the EOF again.
        // Success criteria: COMPLETE after the first full read, PARTIAL with a total of 1 after the
        //   reset, COMPLETE with a total of 5 after the replay.
        // Why it matters: a rewind that left COMPLETE in place would count a body the application
        //   then abandoned as consumed.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = CapturingRequestWrapper(requestWith(rewindable("hello")), capture).inputStream
        body.read()
        body.mark(16)

        // When/Then
        body.readAllBytes()
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        body.reset()
        assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)
        assertThat(capture.totalBytes).isEqualTo(1L)
        body.readAllBytes()
        assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        assertThat(capture.totalBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `should report a container stream as not rewindable and leave the capture alone when a reset is refused`() {
        // What is tested: the tee over a stream without mark support - the answer is forwarded, a
        //   mark is the no-op the contract allows, and the IOException of a refused reset reaches
        //   the caller unchanged without the capture rewinding.
        // Success criteria: markSupported is false; mark does not throw; reset throws the stream's
        //   IOException; the total stays at the 1 byte read.
        // Why it matters: the wrapper must neither invent a capability the container lacks nor
        //   rewind the capture for a rewind that did not happen.
        // Given
        val capture = BoundedBodyCapture(16)
        val body = CapturingRequestWrapper(requestWith(containerLike("hello")), capture).inputStream

        // When
        assertThat(body.markSupported()).isFalse()
        body.mark(1)
        assertThat(body.read()).isEqualTo('h'.code)
        val thrown = catchThrowable { body.reset() }

        // Then
        assertThat(thrown).isInstanceOf(IOException::class.java).hasMessageContaining("not supported")
        assertThat(capture.totalBytes).isEqualTo(1L)
    }
}
