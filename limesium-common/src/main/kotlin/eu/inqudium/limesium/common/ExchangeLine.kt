package eu.inqudium.limesium.common

import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * What the shared emission core needs to know about one exchange - the request-side coordinates both
 * twins capture EAGERLY at wiring time. Implemented by each twin's `Exchange` (two real
 * implementations: the servlet one carries the async disposition and the servlet response, the
 * reactive one the lifecycle state and the reactive response).
 */
internal interface LoggedExchange {
    val method: String
    val path: String
    val query: String?

    /** The exchange identity (`endpoint_request_id`, ADR-0002). */
    val requestId: String
    val requestHeaders: List<Pair<String, String>>

    /** Trace context parsed from the incoming W3C `traceparent` header; null without the header. */
    val traceId: String?
    val parentSpanId: String?
}

/**
 * The measured side of a body capture, as the emission reads it - implemented by both twins'
 * `BoundedBodyCapture` (deliberately separate implementations with different concurrency designs,
 * ADR-0003; the two properties are their shared reading surface).
 */
internal interface MeasuredBody {
    /** Every byte that flowed, including those beyond the capture limit. */
    val totalBytes: Long

    /** How far the application consumed the body. */
    val readState: BodyReadState
}

/**
 * The stack-neutral core of the exchange line, shared by both twins' emitters (ADR-0003 amendment of
 * 2026-09-05): the message texts, the header rendering, the optional arrival line and the opt-in body
 * measurements. What stays on the emitters is exactly what differs per stack - the level/outcome
 * classification (async disposition vs. cancellation, an always-present vs. a nullable status), the
 * exactly-once guard shape, and the response-side header/body reads against the stack's response type.
 * Message and field format are locked in both twins by `EndpointLogFieldTest` (this module) and the
 * twins' `TwinContractTest`s.
 */
internal object ExchangeLine {
    const val NANOS_PER_MS = 1_000_000L

    /** Renders selected headers as `[name:"value", ...]`, or null when nothing was selected or present. */
    fun renderHeaders(headers: List<Pair<String, String>>): String? {
        if (headers.isEmpty()) {
            return null
        }
        return headers.joinToString(separator = ", ", prefix = "[", postfix = "]") { (name, value) -> "$name:\"$value\"" }
    }

    /**
     * The trace part of the inline identity for plain-text appenders that drop the MDC - empty for a
     * traceless exchange, so the message shows no placeholder ids.
     */
    fun traceSuffix(exchange: LoggedExchange): String =
        if (exchange.traceId != null || exchange.parentSpanId != null) {
            " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} " +
                "${TraceMdcKeys.PARENT_SPAN_ID}=${exchange.parentSpanId ?: "-"}"
        } else {
            ""
        }

    /** The arrival line's message: what is known before the chain. */
    fun arrivalMessage(exchange: LoggedExchange): String = "Endpoint http exchange started ${exchange.method} ${exchange.path} [${MdcKeys.REQUEST_ID}=${exchange.requestId}]"

    /** The exchange line's message; [statusText] is the rendered status, `-` when there is none. */
    fun exchangeMessage(
        exchange: LoggedExchange,
        statusText: String,
    ): String =
        "Endpoint http exchange ${exchange.method} ${exchange.path} -> $statusText " +
            "[${MdcKeys.REQUEST_ID}=${exchange.requestId}${traceSuffix(exchange)}]"

    /**
     * The optional arrival line (`log-request-start`): what is known BEFORE the chain - method, path,
     * query, selected request headers - at INFO on [exchangeLog], under the exchange's MDC with the
     * traceparent-derived trace overlay. The scope OWNS the trace keys exactly as at emission, so the
     * line carries the same `traceId`/`parentSpanId` pair as the completion event and an ambient bridge
     * `spanId` on the emitting thread cannot ride along. Deliberately WITHOUT `endpoint_outcome`, status
     * or duration: those exist only at completion, and their absence is what keeps outcome-keyed
     * dashboards blind to this extra line.
     *
     * The whole operation is inside the fail-open guard - the level gate and the MDC adapter are host
     * calls and fail like the emission itself; `use` records a close-time failure as suppressed instead
     * of masking the original one. A loss is counted `stage=arrival` and reported on [internalLog].
     */
    fun logRequestStart(
        exchangeLog: Logger,
        internalLog: Logger,
        metrics: EndpointLoggingMetrics,
        exchange: LoggedExchange,
    ) {
        failOpen(
            onInterrupted = { e ->
                metrics.arrivalFailure()
                internalLog.debug("Interrupted while logging a request start; the line is dropped", e)
            },
            onFailure = { e ->
                metrics.arrivalFailure()
                internalLog.error(
                    "Exception while logging request start {} {}: {}",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                    e,
                )
            },
        ) {
            if (!exchangeLog.isInfoEnabled) {
                return
            }
            MdcScope(
                exchange.requestId,
                exchange.method,
                exchange.path,
                exchange.traceId,
                exchange.parentSpanId,
                ownsTraceKeys = true,
            ).use {
                exchangeLog
                    .atInfo()
                    .setMessage(arrivalMessage(exchange))
                    .addKeyValue(EndpointLogField.REQUEST_METHOD, exchange.method)
                    .addKeyValue(EndpointLogField.URL_PATH, exchange.path)
                    .addKeyValueIfPresent(EndpointLogField.URL_QUERY, exchange.query)
                    .addKeyValueIfPresent(EndpointLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
                    .log()
            }
        }
    }

    /**
     * The opt-in body measurements: the size samples, and - for the request side - the read-state
     * counter, which is what tells an unread body from an absent one (the size sample cannot: both are
     * zero bytes and record nothing). Recorded BEFORE the level gate - a metric must not depend on how
     * loud the logger is configured - and guarded on their own: a host registry that rejects the
     * body-size summary (meter-id conflict) costs the sample, never the event; the loss is counted
     * `stage=wiring` and reported on [internalLog].
     */
    fun recordBodySizesQuietly(
        internalLog: Logger,
        metrics: EndpointLoggingMetrics,
        exchange: LoggedExchange,
        template: String?,
        requestCapture: MeasuredBody?,
        responseCapture: MeasuredBody?,
        measureRequest: Boolean,
        measureResponse: Boolean,
    ) {
        try {
            if (measureRequest) {
                requestCapture?.let {
                    metrics.requestBodySize(template, it.totalBytes)
                    metrics.requestBodyRead(template, it.readState)
                }
            }
            if (measureResponse) {
                responseCapture?.let { metrics.responseBodySize(template, it.totalBytes) }
            }
        } catch (e: Exception) {
            reportFailOpen(
                metrics::wiringFailure,
                internalLog,
                Level.WARN,
                null,
                "Body size could not be recorded for {} {} - the event follows without it: {}",
                exchange.method,
                exchange.path,
                e.toString(),
            )
        }
    }
}
