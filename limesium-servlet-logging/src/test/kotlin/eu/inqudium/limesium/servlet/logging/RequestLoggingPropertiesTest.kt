package eu.inqudium.limesium.servlet.logging

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/** Construction-time invariants of [RequestLoggingProperties]. */
class RequestLoggingPropertiesTest {
    @Nested
    inner class `Slow request threshold` {
        @Test
        fun `should reject a positive threshold below one millisecond`() {
            // What is tested: the resolution floor -
            //   the comparison runs at full precision, but the LOGGED duration has millisecond resolution,
            //   so a sub-millisecond threshold would flag exchanges whose logged duration reads 0 ms.
            // Success criteria: construction fails with a message naming the millisecond floor.
            // Why it matters: a silently accepted 500us threshold escalates all traffic to WARN with
            //   endpoint_slow=true - false alerts and a log-volume surge from one innocent-looking value.
            // Given/When: a positive sub-millisecond threshold
            val thrown = catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ofNanos(999_999)) }

            // Then: rejected at construction
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("at least 1 millisecond")
        }

        @Test
        fun `should reject zero and negative thresholds`() {
            // Given/When/Then: neither zero nor a negative duration is a threshold
            assertThat(catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ZERO) })
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ofMillis(-1)) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should accept exactly one millisecond as the smallest threshold`() {
            // Given/When: the boundary value
            val properties = RequestLoggingProperties(slowRequestThreshold = Duration.ofMillis(1))

            // Then: accepted unchanged
            assertThat(properties.slowRequestThreshold).isEqualTo(Duration.ofMillis(1))
        }
    }

    @Nested
    inner class `Correlation id header` {
        @Test
        fun `should reject a correlation header name outside the HTTP field-name grammar`() {
            // What is tested: binding-time validation of the header NAME - the name is written onto every
            //   response, and a server
            //   adapter that validates field names rejects a non-token at runtime on every request.
            // Success criteria: whitespace, separators and a non-ASCII character fail construction
            //   with a message naming the property.
            // Why it matters: a runtime rejection degrades the filter to an unlogged pass-through for
            //   ALL traffic while the application stays healthy - an observability outage nobody sees.
            // Given/When/Then: each invalid name is rejected at construction
            listOf("X Correlation", "X:Correlation", "X-Correlation\u00e9", "X(Corr)", "X,Corr").forEach { name ->
                assertThat(catchThrowable { RequestLoggingProperties(correlationIdHeader = name) })
                    .describedAs("name %s", name)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("correlationIdHeader must be a valid HTTP field name")
            }
        }

        @Test
        fun `should accept every token character of a field name`() {
            // Given/When: the full RFC 9110 tchar set
            val properties = RequestLoggingProperties(correlationIdHeader = "X-Corr.Id_42!#$%&'*+^`|~")

            // Then
            assertThat(properties.correlationIdHeader).isEqualTo("X-Corr.Id_42!#$%&'*+^`|~")
        }
    }
}
