package eu.inqudium.limesium.reactive.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * The sizing hint the reactive decorators hand their captures: the request's declared length at the
 * claiming subscription, the response's at write time. The hint sizes the capture's one allocation; the
 * bytes and the count are proved by the tee tests in `RequestLoggingWebFilterBodyAndHeaderTest`.
 */
class CapturingDecoratorsSizingHintTest {
    private fun buffer(text: String): DataBuffer = DefaultDataBufferFactory.sharedInstance.wrap(text.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun `should hand the request's Content-Length to the capture at the claiming subscription and fold a malformed one to unknown`() {
        // What is tested: CapturingRequestDecorator.getBody - the hint arrives with the claim, from
        //   the request's headers; a value Spring cannot parse is unknown.
        // Success criteria: 5 for a declared 5; UNKNOWN_LENGTH for "many"; the body flows and is
        //   captured either way.
        // Why it matters: Content-Length is client-controlled; it may size an allocation but must
        //   never throw into the body publisher.
        // Given
        val declared = BoundedBodyCapture(64)
        val malformed = BoundedBodyCapture(64)

        // When
        CapturingRequestDecorator(MockServerHttpRequest.post("/things").header("Content-Length", "5").body("hello"), declared).body.blockLast()
        CapturingRequestDecorator(MockServerHttpRequest.post("/things").header("Content-Length", "many").body("hello"), malformed).body.blockLast()

        // Then
        assertThat(declared.expectedBytes).isEqualTo(5L)
        assertThat(declared.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(malformed.expectedBytes).isEqualTo(BoundedBodyCapture.UNKNOWN_LENGTH)
        assertThat(malformed.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `should hand the response's Content-Length to the capture at write time and fold a malformed one to unknown`() {
        // What is tested: CapturingResponseDecorator.writeWith - the hint is read from the response
        //   headers when the write starts, where EncoderHttpMessageWriter has just declared it.
        // Success criteria: 5 for a declared 5; UNKNOWN_LENGTH for "many"; the write succeeds and the
        //   body is captured either way.
        // Why it matters: a garbage value an application set must never fail its own write.
        // Given
        val declared = BoundedBodyCapture(64)
        val declaring = MockServerHttpResponse().apply { headers.contentLength = 5 }
        val malformed = BoundedBodyCapture(64)
        val garbled = MockServerHttpResponse().apply { headers.set("Content-Length", "many") }

        // When
        CapturingResponseDecorator(declaring, declared).writeWith(Mono.just(buffer("hello"))).block()
        CapturingResponseDecorator(garbled, malformed).writeWith(Mono.just(buffer("hello"))).block()

        // Then
        assertThat(declared.expectedBytes).isEqualTo(5L)
        assertThat(declared.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
        assertThat(malformed.expectedBytes).isEqualTo(BoundedBodyCapture.UNKNOWN_LENGTH)
        assertThat(malformed.loggedValue(StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `should take the hint only before the first buffered byte and never once frozen`() {
        // What is tested: expectBytes on the reactive capture - accepted while nothing is buffered,
        //   ignored after a write and after the freeze.
        // Success criteria: 5 after expectBytes(5); still 5 after a write and expectBytes(9); still 5
        //   after freeze and expectBytes(9).
        // Why it matters: the emission snapshot must not move after the freeze, and the hint sizes an
        //   allocation that has already happened after the first byte.
        // Given
        val capture = BoundedBodyCapture(16)

        // When/Then
        capture.expectBytes(5)
        assertThat(capture.expectedBytes).isEqualTo(5L)
        capture.capture("hello".toByteArray(), 0, 5)
        capture.expectBytes(9)
        assertThat(capture.expectedBytes).isEqualTo(5L)
        capture.freeze()
        capture.expectBytes(9)
        assertThat(capture.expectedBytes).isEqualTo(5L)
    }
}
