package eu.inqudium.limesium.reactive.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * Contract of the [EndpointLogField] family: the wire names (a contract with the log index), the per-field
 * type guarantee, the drop-the-field-not-the-event semantics of the [addKeyValue] overload, and the
 * lockstep with the SERVLET twin's component template (the one index contract both stacks share).
 */
class EndpointLogFieldTest {
    // The ONE template for both stacks lives in the SERVLET twin and reaches this module's test classpath
    // through the declared test resource in the POM - the drift guard of the duplication: both enums
    // must stay in lockstep with the same index contract.
    private val template: String by lazy {
        val resource = ClassPathResource("elk/limesium-servlet-logging-fields.component-template.json")
        assertThat(resource.exists())
            .describedAs("the component template must be on the test classpath (declared test resource from the servlet twin's docs)")
            .isTrue()
        resource.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    private val properties: Map<String, Map<String, Any>> by lazy {
        JsonPath.read(template, "$.template.mappings.properties")
    }

    @Nested
    inner class `Wire names` {
        @Test
        fun `should be the literal strings the component template maps`() {
            // What is tested: every wire name, spelled out as a literal - independently of the enum, so a
            //   rename cannot pass by asserting a value against itself.
            // Success criteria: all thirteen names match exactly.
            // Why it matters: once the template is composed into a pipeline, changing a name is a breaking
            //   change for every dashboard and alert keying on it - the compiler cannot see that.
            // Given/When/Then: every wire name against its literal
            assertThat(EndpointLogField.OUTCOME.wireName).isEqualTo("endpoint_outcome")
            assertThat(EndpointLogField.DURATION_MS.wireName).isEqualTo("endpoint_duration_ms")
            assertThat(EndpointLogField.REQUEST_METHOD.wireName).isEqualTo("endpoint_request_method")
            assertThat(EndpointLogField.RESPONSE_STATUS_CODE.wireName).isEqualTo("endpoint_response_status_code")
            assertThat(EndpointLogField.URL_TEMPLATE.wireName).isEqualTo("endpoint_url_template")
            assertThat(EndpointLogField.URL_PATH.wireName).isEqualTo("endpoint_url_path")
            assertThat(EndpointLogField.URL_QUERY.wireName).isEqualTo("endpoint_url_query")
            assertThat(EndpointLogField.SLOW.wireName).isEqualTo("endpoint_slow")
            assertThat(EndpointLogField.ASYNC.wireName).isEqualTo("endpoint_async")
            assertThat(EndpointLogField.REQUEST_HEADERS.wireName).isEqualTo("endpoint_request_headers")
            assertThat(EndpointLogField.RESPONSE_HEADERS.wireName).isEqualTo("endpoint_response_headers")
            assertThat(EndpointLogField.REQUEST_BODY.wireName).isEqualTo("endpoint_request_body")
            assertThat(EndpointLogField.RESPONSE_BODY.wireName).isEqualTo("endpoint_response_body")
        }

        @Test
        fun `should prefix every wire name with endpoint and keep them unique`() {
            // What is tested: the naming contract of the whole family in one place.
            // Success criteria: every wire name starts with 'endpoint_', is lower snake_case, and no two
            //   fields collide.
            // Why it matters: the names are index-side contract - a stray prefix or a duplicate silently
            //   splits one logical field into two that no dashboard knows about.
            // Given/When: the wire names of every field of the family
            val wireNames = EndpointLogField.entries.map { it.wireName }

            // Then: prefixed, snake_case, unique
            assertThat(wireNames).allSatisfy { name ->
                assertThat(name).startsWith("endpoint_")
                assertThat(name).matches("[a-z0-9_]+")
            }
            assertThat(wireNames).doesNotHaveDuplicates()
        }
    }

    @Nested
    inner class `The component template` {
        @Test
        fun `should map exactly the fields this module emits`() {
            // What is tested: that the template and the enum describe the same field set.
            // Success criteria: set equality - it fails in BOTH directions, a field added to the enum
            //   without a mapping AND a mapping left behind for a removed field.
            // Why it matters: an unmapped field is not an error at index time - Elasticsearch maps it
            //   dynamically, and for a body or a header that means the value becomes SEARCHABLE, the one
            //   outcome the mapping guide's sensitivity rule forbids.
            // Given: the fields the module can emit
            val emitted = EndpointLogField.entries.map { it.wireName }

            // When / Then: the template maps those and no others
            assertThat(properties.keys).containsExactlyInAnyOrderElementsOf(emitted)
        }

        @Test
        fun `should keep every payload field out of the index`() {
            // What is tested: the mapping half of the sensitivity rule - headers and bodies must not be
            //   searchable, no matter how the rest of the template changes.
            // Success criteria: index false AND doc_values false asserted explicitly, not via the field
            //   set - a field silently re-typed to a searchable keyword would still pass the set check.
            // Why it matters: selection and masking in code are the real protection; the mapping is the
            //   second line, so a value that slips through cannot at least be searched for deliberately.
            // Given: the four fields carrying caller-controlled payload
            val sensitive =
                listOf(
                    EndpointLogField.REQUEST_HEADERS,
                    EndpointLogField.RESPONSE_HEADERS,
                    EndpointLogField.REQUEST_BODY,
                    EndpointLogField.RESPONSE_BODY,
                )

            // When / Then: none of them is indexed or given doc values
            sensitive.forEach { field ->
                assertThat(properties[field.wireName])
                    .describedAs("%s must not be searchable", field.wireName)
                    .containsEntry("index", false)
                    .containsEntry("doc_values", false)
            }
        }

        @Test
        fun `should keep the high-cardinality URL fields out of doc values but leave them searchable`() {
            // What is tested: the repetition-factor split of the path pair - the decision an unsuspecting
            //   edit is most likely to undo ("why is url_path not aggregatable? let me fix it").
            // Success criteria: path and query have doc_values off but stay indexed; the template half
            //   keeps its doc values as the aggregation counterpart.
            // Why it matters: the resolved path appears in about one line each - doc values on it grow an
            //   ordinal dictionary to the document count and buy only singleton buckets, while
            //   endpoint_url_template is the field that answers "which endpoint is slow".
            // Given / When / Then: path and query are filterable but not groupable
            listOf(EndpointLogField.URL_PATH, EndpointLogField.URL_QUERY).forEach { field ->
                assertThat(properties[field.wireName])
                    .describedAs("%s: repetition factor ~1, see the mapping guide", field.wireName)
                    .containsEntry("doc_values", false)
                    .doesNotContainEntry("index", false)
            }

            // And: the template half is the aggregation counterpart, so it keeps its doc values
            assertThat(properties[EndpointLogField.URL_TEMPLATE.wireName])
                .isEqualTo(mapOf("type" to "keyword"))
        }

        @Test
        fun `should map the numeric and boolean shapes the code guarantees`() {
            // Given / When / Then: the shape checked() enforces in code and the type the index expects
            //   must describe the same value - long duration, short status (three digits, a label never
            //   summed), boolean flags
            assertThat(properties[EndpointLogField.DURATION_MS.wireName]).containsEntry("type", "long")
            assertThat(properties[EndpointLogField.RESPONSE_STATUS_CODE.wireName]).containsEntry("type", "short")
            assertThat(properties[EndpointLogField.SLOW.wireName]).containsEntry("type", "boolean")
            assertThat(properties[EndpointLogField.ASYNC.wireName]).containsEntry("type", "boolean")
        }

        @Test
        fun `should be a component template, claiming no indices of its own`() {
            // Given / When: the top-level keys
            val root: Map<String, Any> = JsonPath.read(template, "$")

            // Then: no index_patterns - it composes into the host's template rather than competing with
            //   it on priority; which data streams carry these fields is the host pipeline's decision.
            assertThat(root).containsOnlyKeys("template", "_meta")
        }
    }

    @Nested
    inner class `Type guarantee` {
        @Test
        fun `should pass a correctly typed value through unchanged`() {
            // Given/When: values of the shape each field declares
            // Then: format returns the identical value - checked, never converted
            assertThat(EndpointLogField.OUTCOME.format("success")).isEqualTo("success")
            assertThat(EndpointLogField.DURATION_MS.format(42L)).isEqualTo(42L)
            assertThat(EndpointLogField.RESPONSE_STATUS_CODE.format(200)).isEqualTo(200)
            assertThat(EndpointLogField.ASYNC.format(false)).isEqualTo(false)
        }

        @Test
        fun `should reject a value of the wrong type naming the field`() {
            // Given/When: an Int where the mapping says long
            val thrown = catchThrowable { EndpointLogField.DURATION_MS.format(42) }

            // Then: the rejection names the field and both types
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("endpoint_duration_ms")
                .hasMessageContaining("Long")
                .hasMessageContaining("Int")
        }
    }

    @Nested
    inner class `Drop the field not the event` {
        private val logger = LoggerFactory.getLogger("endpoint-log-field-test") as Logger
        private lateinit var appender: ListAppender<ILoggingEvent>

        @BeforeEach
        fun setUp() {
            appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            logger.level = Level.INFO
        }

        @AfterEach
        fun tearDown() {
            logger.detachAppender(appender)
            appender.stop()
        }

        @Test
        fun `should drop a badly typed field but keep the event and its other fields`() {
            // What is tested: the fail-open contract of the addKeyValue(field, value) overload.
            // Success criteria: the event is logged, the well-typed field survives, the ill-typed field is
            //   absent - the statement never throws.
            // Why it matters: the exchange line is the observability of the request path; a type slip in
            //   one field must not take the whole statement (and the surrounding request) down with it.
            // Given/When: one well-typed and one ill-typed field on the same event
            logger
                .atInfo()
                .setMessage("exchange")
                .addKeyValue(EndpointLogField.OUTCOME, "success")
                .addKeyValue(EndpointLogField.DURATION_MS, "not-a-long")
                .log()

            // Then: the event survived with only the well-typed field
            val keyValues = appender.list.single().keyValuePairs.associate { it.key to it.value }
            assertThat(keyValues)
                .containsEntry("endpoint_outcome", "success")
                .doesNotContainKey("endpoint_duration_ms")
        }
    }
}
