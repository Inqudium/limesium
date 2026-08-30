package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyReadState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Byte-bounded truncation of [BoundedBodyCapture] at a character boundary (twin parity with the reactive module). */
class BoundedBodyCaptureTest {
    private fun bytes(text: String) = text.toByteArray(StandardCharsets.UTF_8)

    @Nested
    inner class `Truncation at a character boundary` {
        @Test
        fun `should drop an incomplete trailing UTF-8 sequence instead of decoding a replacement character`() {
            // What is tested: byte-bounded truncation of multi-byte text (finding 8 of the
            //   internal analysis) - the cap counts bytes, so it can split a character.
            // Success criteria: with a 2-byte cap over "h\u00e9" (3 bytes: 68 c3 a9) the logged prefix is
            //   "h", not "h\uFFFD"; the byte count stays exact.
            // Why it matters: a replacement character in the logged prefix is corruption the reader
            //   cannot distinguish from corrupt input.
            // Given: a 2-byte capture over a 3-byte text
            val capture = BoundedBodyCapture(2)
            val body = bytes("h\u00e9")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("h... [truncated, 3 bytes total]")
        }

        @Test
        fun `should drop an incomplete trailing sequence of another variable-width charset`() {
            // Given: Shift_JIS "a\u3042" = 61 82 a0, capped at 2 bytes
            val shiftJis = Charset.forName("Shift_JIS")
            val capture = BoundedBodyCapture(2)
            val body = "a\u3042".toByteArray(shiftJis)
            capture.capture(body, 0, body.size)

            // When/Then: only the complete character survives
            assertThat(body).hasSize(3)
            assertThat(capture.loggedValue(shiftJis)).isEqualTo("a... [truncated, 3 bytes total]")
        }

        @Test
        fun `should keep a complete multi-byte character that ends exactly at the cap`() {
            // Given: "\u00e9" (2 bytes) capped at 2, followed by more
            val capture = BoundedBodyCapture(2)
            val body = bytes("\u00e9x")
            capture.capture(body, 0, body.size)

            // When/Then
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("\u00e9... [truncated, 3 bytes total]")
        }

        @Test
        fun `should still replace malformed bytes inside the prefix`() {
            // Given: a lone continuation byte in the middle, under truncation
            val capture = BoundedBodyCapture(3)
            val body = byteArrayOf(0x61, 0xa9.toByte(), 0x62, 0x63)
            capture.capture(body, 0, body.size)

            // When/Then: the malformed byte is replaced, the prefix is otherwise intact
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("a\uFFFDb... [truncated, 4 bytes total]")
        }
    }

    @Nested
    inner class `Read state` {
        @Test
        fun `should start unread and stay unread while bytes are only counted`() {
            // What is tested: the read state is a fact of its own, not derived from the byte count.
            // Success criteria: a fresh capture is UNREAD, and captured bytes alone do not move it.
            // Why it matters: the response side feeds the same class without ever marking - its state
            //   must not drift into PARTIAL just because bytes flowed.
            // Given: a fresh capture
            val capture = BoundedBodyCapture(8)

            // When: bytes are captured without any mark
            capture.capture(bytes("abc"), 0, 3)

            // Then: still unread
            assertThat(capture.readState).isEqualTo(BodyReadState.UNREAD)
        }

        @Test
        fun `should move to partial on start and to complete on completion, never backwards`() {
            // Given: a fresh capture
            val capture = BoundedBodyCapture(8)

            // When/Then: start -> partial
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.PARTIAL)

            // When/Then: completion -> complete
            capture.markCompleted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)

            // When/Then: a later start (a second accessor call) does not regress the state
            capture.markStarted()
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
        }

        @Test
        fun `should report a zero-byte body read to its end as complete`() {
            // What is tested: completeness is independent of the byte count - an empty body can be
            //   consumed completely.
            // Success criteria: COMPLETE with totalBytes 0 and no logged value.
            // Why it matters: "complete" and "absent" are different answers to different questions; the
            //   size sample collapses them, the read state must not.
            // Given: a capture marked started and completed without bytes
            val capture = BoundedBodyCapture(8)
            capture.markStarted()
            capture.markCompleted()

            // When/Then
            assertThat(capture.readState).isEqualTo(BodyReadState.COMPLETE)
            assertThat(capture.totalBytes).isEqualTo(0L)
            assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isNull()
        }
    }
}
