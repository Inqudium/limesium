package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.NanoTimeSource
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import kotlin.reflect.KClass

/**
 * The structured log fields of an INBOUND HTTP exchange: their wire names, and the one rendering each name
 * is allowed to carry.
 *
 * These names are a CONTRACT with the log index, not local identifiers: renaming a constant below is free,
 * changing a [wireName] breaks every dashboard, saved search and alert rule keying on it. The mapping is
 * shipped as the repository-shared `/docs/elk/limesium-servlet-logging-fields.component-template.json` - the DEFINITION of the
 * family (no upstream data-stream mapping exists yet), composed into the log pipeline by whoever wires the
 * module in; `EndpointLogFieldTest` keeps enum and template in lockstep, build-breaking in both directions.
 *
 * **Why an enum rather than string literals.** A literal repeated across call sites is a typo away from a
 * second, near-identical field no dashboard knows about; and beyond the name, a field owns its wire SHAPE,
 * so two call sites cannot disagree about the JSON type a field carries. [format] converts nothing - every
 * field goes on the wire exactly as supplied - it only GUARANTEES the type.
 *
 * ELK MAPPING. Each field carries an `ELK:` line stating the intended mapping and the access pattern that
 * earns it:
 *
 *   aggregate / sort  -> keyword or numeric   index true    doc_values ON
 *   filter exactly    -> keyword              index true    doc_values off
 *   display only      -> text or keyword      index FALSE   doc_values off
 *   compute           -> long or double       index true    doc_values ON
 *
 * Headers and bodies are display-only (`index: false`): they are the widest data-leak surface of the
 * family, and a value that reaches the log should at least not be searchable for deliberately.
 */
internal enum class EndpointLogField(
    val wireName: String,
    /** The exact JVM type a value of this field must have on the wire - asserted, never converted. */
    private val type: KClass<out Any>,
) {
    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. Three values (`success`, `failure`,
     * `timeout`), and the field a dashboard splits by - deliberately NOT the log level: a 5xx without a
     * chain exception logs at WARN while a thrown chain logs at ERROR, yet both carry `failure`. Panels
     * key off this field, the level only carries severity.
     */
    OUTCOME("endpoint_outcome", String::class),

    /**
     * ELK: `long`, index true, doc_values ON - compute (percentiles). Milliseconds, with the unit in the
     * name; measured from the injected monotonic [NanoTimeSource], so it is a duration, never a timestamp.
     */
    DURATION_MS("endpoint_duration_ms", Long::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. Normally a handful of well-known HTTP
     * methods, but NOT a closed vocabulary: RFC 9110 keeps the method token extensible, and the field
     * carries whatever token the request line named.
     */
    REQUEST_METHOD("endpoint_request_method", String::class),

    /**
     * ELK: **`short`**, index true, doc_values ON - aggregate, NOT compute: a numeric LABEL one groups by
     * and never averages. `short` is safe because HTTP status codes are three digits.
     *
     * For `endpoint_outcome=timeout` (and async errors) the value is whatever the response object held at
     * request destruction - often a stale 200 the client never received as a success. The OUTCOME field
     * is the authoritative disposition; this field reports the container's last word on the wire status.
     */
    RESPONSE_STATUS_CODE("endpoint_response_status_code", Int::class),

    /**
     * ELK: `keyword`, index true, doc_values ON - aggregate. The low-cardinality handler pattern
     * (`/api/things/{id}`) Spring MVC recorded for the request, when there is one - the aggregation half
     * of the pair.
     */
    URL_TEMPLATE("endpoint_url_template", String::class),

    /**
     * ELK: `keyword`, index true, **doc_values OFF** - filter exactly. The expanded request path, ids and
     * all: useful for finding one call, useless to group by (singleton buckets), expensive to keep an
     * ordinal dictionary for.
     */
    URL_PATH("endpoint_url_path", String::class),

    /**
     * ELK: `keyword`, index true, doc_values OFF - filter exactly, as [URL_PATH]. Its own field rather
     * than part of the path so grouping by path is not defeated by varying query strings.
     */
    URL_QUERY("endpoint_url_query", String::class),

    /**
     * ELK: `boolean`, index true, doc_values ON - aggregate. True when the exchange reached the
     * configured slow-request threshold; present only then, so absence means fast.
     */
    SLOW("endpoint_slow", Boolean::class),

    /**
     * ELK: `boolean`, index true, doc_values ON - aggregate. True when the exchange completed
     * asynchronously (suspend controller, DeferredResult); splits latency panels by processing mode.
     */
    ASYNC("endpoint_async", Boolean::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. Read in the hit, never searched,
     * never grouped. Only the configured header selection reaches this field at all, and names listed as
     * masked carry a stable `length:hash` fingerprint instead of the value.
     */
    REQUEST_HEADERS("endpoint_request_headers", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_HEADERS]. */
    RESPONSE_HEADERS("endpoint_response_headers", String::class),

    /**
     * ELK: `keyword`, **index FALSE**, doc_values off - display only. The largest field of the family and
     * the widest data-leak surface, which is why it exists only when body capture is enabled and carries
     * at most the configured capture limit.
     */
    REQUEST_BODY("endpoint_request_body", String::class),

    /** ELK: `keyword`, index false, doc_values off - display only, as [REQUEST_BODY]. */
    RESPONSE_BODY("endpoint_response_body", String::class),

    ;

    /** Where a rejected value is reported, since [addKeyValue] swallows the rejection rather than propagating it. */
    val log = LoggerFactory.getLogger(EndpointLogField::class.java)

    /**
     * The exact shape this field puts on the wire: [value] itself with its type asserted - see the class
     * comment. No conversion, by design: a value of the wrong type is rejected, never coerced.
     */
    fun format(value: Any?): Any =
        if (value != null && type.isInstance(value)) {
            value
        } else {
            throw IllegalArgumentException(
                "Structured log field $wireName expects ${type.simpleName}, got ${value?.let { it::class.simpleName } ?: "null"}",
            )
        }
}

/**
 * Adds a field to a log event under its wire name, rendered by the field itself. The overload exists so a
 * call site names the field rather than a string, and cannot reach the event with an unrendered value by
 * spelling the key by hand.
 *
 * A rejected value costs THIS FIELD and a warning naming it, never the log call it was part of: the
 * exchange line is the observability of the request path, and letting a type slip take the whole statement
 * down would remove it exactly when it is needed.
 */
internal fun LoggingEventBuilder.addKeyValue(
    field: EndpointLogField,
    value: Any?,
): LoggingEventBuilder =
    try {
        addKeyValue(field.wireName, field.format(value))
    } catch (e: IllegalArgumentException) {
        field.log.warn(e.toString())
        this
    }

/** As [addKeyValue], but leaves the field off the event when [value] is null - for optional fields in a single builder chain. */
internal fun LoggingEventBuilder.addKeyValueIfPresent(
    field: EndpointLogField,
    value: Any?,
): LoggingEventBuilder = if (value == null) this else addKeyValue(field, value)

/** Attaches [cause] when there is one; otherwise returns the builder unchanged, so the chain stays a single expression. */
internal fun LoggingEventBuilder.setCauseIfPresent(cause: Throwable?): LoggingEventBuilder = if (cause == null) this else setCause(cause)
