package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.MaskingKey
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
            //   the logged duration has millisecond resolution, so a sub-millisecond threshold would
            //   flag exchanges whose logged duration reads 0 ms.
            // Success criteria: construction fails with a message naming the floor.
            // Why it matters: a silently accepted 500us threshold escalates all traffic to WARN.
            // Given/When
            val thrown = catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ofNanos(999_999)) }

            // Then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("at least 1 millisecond")
        }

        @Test
        fun `should reject zero and negative thresholds`() {
            // What is tested: the slow-threshold guard at binding time.
            // Success criteria: Duration.ZERO and a negative duration both throw
            //   IllegalArgumentException.
            // Why it matters: a zero threshold would flag every exchange as slow, a negative one
            //   none; both are configuration errors better caught at start than read off a dashboard.
            // Given/When/Then: neither zero nor a negative duration is a threshold
            assertThat(catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ZERO) })
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(catchThrowable { RequestLoggingProperties(slowRequestThreshold = Duration.ofMillis(-1)) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `should accept exactly one millisecond as the smallest threshold`() {
            // What is tested: the boundary of the threshold guard.
            // Success criteria: one millisecond is accepted unchanged.
            // Why it matters: the logged duration has millisecond resolution, so 1 ms is the
            //   smallest threshold that can ever match a logged value.
            // Given/When
            val properties = RequestLoggingProperties(slowRequestThreshold = Duration.ofMillis(1))

            // Then
            assertThat(properties.slowRequestThreshold).isEqualTo(Duration.ofMillis(1))
        }
    }

    @Nested
    inner class `Masking key` {
        @Test
        fun `should reject a blank masking key but accept an empty one`() {
            // What is tested: the binding-time rule - empty means unkeyed, blank is a misconfiguration.
            // Success criteria: whitespace fails construction naming the property; the empty default binds.
            // Why it matters: a whitespace key would silently key the fingerprint with a worthless secret.
            // Given/When/Then
            assertThat(catchThrowable { RequestLoggingProperties(maskingKey = MaskingKey("  ")) })
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("maskingKey")
            assertThat(RequestLoggingProperties().maskingKey).isEqualTo(MaskingKey.NONE)
        }

        @Test
        fun `should redact the masking key in toString`() {
            // What is tested: the key is a secret - a properties dump (a startup log, a debug endpoint)
            //   must not print it.
            // Success criteria: toString carries the redaction marker, never the key.
            // Why it matters: data-class toString would otherwise leak the secret into every context
            //   that prints the bean.
            // Given/When/Then
            assertThat(RequestLoggingProperties(maskingKey = MaskingKey("pepper")).toString()).contains("maskingKey=<redacted>").doesNotContain("pepper")
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
            // What is tested: the correlation-header validation against the full RFC 9110 tchar
            //   set.
            // Success criteria: a name using every token character is accepted unchanged.
            // Why it matters: the validation must reject non-tokens without rejecting legal but
            //   unusual names; an over-strict pattern would fail a host's existing header name at
            //   start.
            // Given/When: the full RFC 9110 tchar set
            val properties = RequestLoggingProperties(correlationIdHeader = "X-Corr.Id_42!#$%&'*+^`|~")

            // Then
            assertThat(properties.correlationIdHeader).isEqualTo("X-Corr.Id_42!#$%&'*+^`|~")
        }
    }
}
