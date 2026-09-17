package eu.inqudium.limesium.servlet.logging

import jakarta.servlet.http.HttpServletResponseWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets

/**
 * The sizing hint the servlet wrappers hand their captures: the request's declared length at stream
 * selection, the application's declared length through the response setters. The hint sizes the
 * capture's one allocation; the bytes and the count are proved by the wrapper tests in
 * `RequestLoggingFilterBodyAndHeaderTest`.
 */
class CapturingWrapperSizingHintTest {
    @Test
    fun `should hand the request's Content-Length to the capture when the stream is selected`() {
        // What is tested: CapturingRequestWrapper.getInputStream against a request that declares
        //   its body length - the hint arrives with the tee, before the first byte is read.
        // Success criteria: the hint reads 5 after getInputStream and the body still reads "hello";
        //   a request without a body declares -1, which the buffer treats as unknown.
        // Why it matters: the hint sizes the capture's one allocation to the declared body; the
        //   tee must stay a passive copy that reads exactly what the application reads.
        // Given
        val capture = BoundedBodyCapture(64)
        val request = MockHttpServletRequest().apply { setContent("hello".toByteArray()) }

        // When
        val body = CapturingRequestWrapper(request, capture).inputStream.readAllBytes().toString(StandardCharsets.UTF_8)

        // Then
        assertThat(body).isEqualTo("hello")
        assertThat(capture.expectedBytes).isEqualTo(5L)
        assertThat(capture.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")

        // And: no body, no declaration
        val bodiless = BoundedBodyCapture(64)
        CapturingRequestWrapper(MockHttpServletRequest(), bodiless).inputStream
        assertThat(bodiless.expectedBytes).isEqualTo(BoundedBodyCapture.UNKNOWN_LENGTH)
    }

    @Test
    fun `should hand the application's declared length to the capture through every response setter`() {
        // What is tested: setContentLength, setContentLengthLong, setHeader and addHeader with
        //   Content-Length - the four ways an application or Spring declares the response length.
        // Success criteria: each declares the value the capture reports; a header the parser refuses
        //   is unknown; the hint is fixed once a byte is buffered.
        // Why it matters: a container keeps Content-Length as a field rather than a header, so the
        //   wrapper must observe the declaration where it is made - and a garbage value must never
        //   throw into the application's write path.
        // Given/When/Then: the two setters
        val viaInt = BoundedBodyCapture(64)
        CapturingResponseWrapper(MockHttpServletResponse(), viaInt).setContentLength(7)
        assertThat(viaInt.expectedBytes).isEqualTo(7L)
        val viaLong = BoundedBodyCapture(64)
        CapturingResponseWrapper(MockHttpServletResponse(), viaLong).setContentLengthLong(9)
        assertThat(viaLong.expectedBytes).isEqualTo(9L)

        // And: the two header methods, case-insensitively
        val viaSet = BoundedBodyCapture(64)
        CapturingResponseWrapper(MockHttpServletResponse(), viaSet).setHeader("content-length", "11")
        assertThat(viaSet.expectedBytes).isEqualTo(11L)
        val viaAdd = BoundedBodyCapture(64)
        CapturingResponseWrapper(MockHttpServletResponse(), viaAdd).addHeader("Content-Length", "13")
        assertThat(viaAdd.expectedBytes).isEqualTo(13L)

        // And: a value the parser refuses (over a delegate that tolerates it, unlike the mock)
        val malformed = BoundedBodyCapture(64)
        val tolerant =
            object : HttpServletResponseWrapper(MockHttpServletResponse()) {
                override fun setHeader(
                    name: String?,
                    value: String?,
                ) = Unit
            }
        CapturingResponseWrapper(tolerant, malformed).setHeader("Content-Length", "many")
        assertThat(malformed.expectedBytes).isEqualTo(BoundedBodyCapture.UNKNOWN_LENGTH)

        // And: fixed once a byte is buffered
        val fixed = BoundedBodyCapture(64)
        val wrapper = CapturingResponseWrapper(MockHttpServletResponse(), fixed)
        wrapper.setContentLength(3)
        wrapper.outputStream.write("abc".toByteArray())
        wrapper.setContentLengthLong(30)
        assertThat(fixed.expectedBytes).isEqualTo(3L)
        assertThat(fixed.loggedValue(StandardCharsets.UTF_8)).isEqualTo("abc")
    }
}
