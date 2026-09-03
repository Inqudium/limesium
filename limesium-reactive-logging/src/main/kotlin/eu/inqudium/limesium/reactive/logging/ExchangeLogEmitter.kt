package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.MdcScope
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.TraceMdcKeys
import eu.inqudium.limesium.common.failOpen
import eu.inqudium.limesium.common.reportQuietly
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Duration

/**
 * Builds and emits the log events of an exchange - the arrival line and the completion event - with the
 * IDENTICAL message and field format of the limesium-servlet-logging emitter (fields locked by
 * `EndpointLogFieldTest`, message text by `TwinContractTest`, in both twins); only the disposition
 * vocabulary differs where the stacks differ (`cancelled` where the servlet twin has `timeout`, no
 * `endpoint_async` - everything is asynchronous here, the flag would carry no information).
 *
 * ## Levels
 *
 * The level carries severity only, `endpoint_outcome` the semantic: ERROR when the chain errored, WARN
 * for a 5xx, a cancellation, or an exchange that reached
 * [RequestLoggingProperties.slowRequestThreshold], INFO otherwise. Severity and outcome are resolved
 * BEFORE the event is built, so a disabled level costs no assembly.
 *
 * ## Fail-open
 *
 * The guard covers everything after the exactly-once CAS: a failure inside the emission is reported on
 * this class's own logger, counted on the [EndpointLoggingMetrics] fail-open counter, and an interrupt
 * is re-raised as a flag. Requests are never affected.
 */
internal class ExchangeLogEmitter(
    private val properties: RequestLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val metrics: EndpointLoggingMetrics,
    private val masker: HeaderValueMasker,
) {
    private val exchangeLog = LoggerFactory.getLogger(properties.loggerName)

    /**
     * The optional arrival line ([RequestLoggingProperties.logRequestStart]): what is known BEFORE the
     * chain, identical in format to the servlet twin - INCLUDING the MDC: the servlet twin's arrival
     * line runs inside its chain scope, so structured encoders see the `endpoint_*` identity on it;
     * this emission opens the same [MdcScope] (with the traceparent-derived trace overlay) around the
     * single log statement for output parity.
     */
    fun logRequestStart(exchange: Exchange) {
        // The whole arrival operation is inside the guard - the level gate and the MDC adapter are host
        // calls and fail like the emission itself; `use`
        // records a close-time failure as suppressed instead of masking the original one.
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
            MdcScope(exchange.requestId, exchange.method, exchange.path, exchange.traceId, exchange.parentSpanId).use {
                exchangeLog
                    .atInfo()
                    .setMessage(
                        "Endpoint http exchange started ${exchange.method} ${exchange.path} " +
                            "[${MdcKeys.REQUEST_ID}=${exchange.requestId}]",
                    ).addKeyValue(EndpointLogField.REQUEST_METHOD, exchange.method)
                    .addKeyValue(EndpointLogField.URL_PATH, exchange.path)
                    .addKeyValueIfPresent(EndpointLogField.URL_QUERY, exchange.query)
                    .addKeyValueIfPresent(EndpointLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
                    .log()
            }
        }
    }

    /**
     * The single emission point, called exactly once per exchange from `ExchangeLifecycle.complete` -
     * the one place that wins the [Exchange.state] transition to `COMPLETED` (terminal signal, or the
     * commit callback for the deferred error case - see the filter).
     */
    fun logExchange(exchange: Exchange) {
        failOpen(
            onInterrupted = { e ->
                metrics.emissionFailure()
                internalLog.debug("Interrupted while logging an exchange; the event is dropped", e)
            },
            onFailure = { e ->
                metrics.emissionFailure()
                internalLog.error(
                    "Exception while logging exchange {} {}: {}",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                    e,
                )
            },
        ) {
            emitExchange(exchange)
        }
    }

    private fun emitExchange(exchange: Exchange) {
        // Freeze FIRST: from here on a late body chunk (an onNext still in flight after a cancellation)
        // can no longer move the captures - body text and size sample are one consistent snapshot.
        exchange.requestCapture?.freeze()
        exchange.responseCapture?.freeze()
        val elapsedNanos = nanoTime.nanoTime() - exchange.startNanos
        val durationMs = elapsedNanos / NANOS_PER_MS
        val failure = exchange.failure
        val cancelled = exchange.cancelled
        // The commit-time status is authoritative (the error path defers emission until the upstream
        // handler rendered); a cancelled exchange may never have committed - status then stays null, the
        // message shows "-" and the status field is omitted rather than invented.
        val status: Int? = exchange.committedStatus ?: exchange.response.statusCode?.value()
        // Compared at full precision and overflow-free (Duration comparison, no toMillis/toNanos
        // truncation): a 1.5 ms threshold must not flag a 1 ms exchange. The logged duration field keeps
        // its millisecond resolution, which is why the properties reject thresholds below 1 ms.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        // Guarded on their own: a host registry that rejects the body-size summary (meter-id conflict)
        // costs the sample, never the event.
        try {
            recordBodySizes(exchange)
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn(
                    "Body size could not be recorded for {} {} - the event follows without it: {}",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                )
            }
        }
        // Severity and semantic decoupled, exactly like the servlet twin - with `cancelled` where the
        // servlet stack has `timeout`: an error signal is ERROR, a 5xx without one is WARN (the
        // application already handled it), a client disconnect is WARN; slow escalates INFO -> WARN
        // without changing the outcome.
        val (baseLevel, outcome) =
            when {
                failure != null -> Level.ERROR to EndpointLoggingMetrics.OUTCOME_FAILURE
                cancelled -> Level.WARN to EndpointLoggingMetrics.OUTCOME_CANCELLED
                (status ?: 0) >= 500 -> Level.WARN to EndpointLoggingMetrics.OUTCOME_FAILURE
                else -> Level.INFO to EndpointLoggingMetrics.OUTCOME_SUCCESS
            }
        val level = if (slow && baseLevel == Level.INFO) Level.WARN else baseLevel
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope carries the exchange identity and the traceparent-derived trace context into
        // the MDC, so a structured encoder emits them as fields; the message repeats the gist inline for
        // plain-text appenders - identical to the servlet twin. `use` restores the scope and records a
        // close-time failure as suppressed instead of masking an emission failure (both land in
        // logExchange's guard either way).
        val traceSuffix =
            if (exchange.traceId != null || exchange.parentSpanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} " +
                    "${TraceMdcKeys.PARENT_SPAN_ID}=${exchange.parentSpanId ?: "-"}"
            } else {
                ""
            }
        MdcScope(exchange.requestId, exchange.method, exchange.path, exchange.traceId, exchange.parentSpanId).use {
            // Multi-value resolution, natively from the reactive HttpHeaders.
            val responseHeaders =
                properties.responseHeaders.select(exchange.response.headers.headerNames(), masker) { name ->
                    exchange.response.headers[name]
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
                }
            // Body fields only when body LOGGING is on - a capture may also exist in count-only mode for
            // the size metrics, and its empty buffer must not surface as a truncated-looking field.
            val requestBody =
                if (properties.logRequestBody) exchange.requestCapture?.loggedValue(exchange.requestCharset) else null
            val responseBody =
                if (properties.logResponseBody) {
                    exchange.responseCapture?.loggedValue(exchange.response.headers.declaredCharsetOrUtf8())
                } else {
                    null
                }
            // One immutable builder chain; optional fields are left off by the *IfPresent helpers. Both
            // halves of the path pair, query as its own field - identical to the servlet twin.
            exchangeLog
                .atLevel(level)
                .setMessage(
                    "Endpoint http exchange ${exchange.method} ${exchange.path} -> ${status ?: "-"} " +
                        "[${MdcKeys.REQUEST_ID}=${exchange.requestId}$traceSuffix]",
                ).addKeyValue(EndpointLogField.OUTCOME, outcome)
                .addKeyValue(EndpointLogField.DURATION_MS, durationMs)
                .addKeyValue(EndpointLogField.REQUEST_METHOD, exchange.method)
                .addKeyValue(EndpointLogField.URL_PATH, exchange.path)
                .addKeyValueIfPresent(EndpointLogField.RESPONSE_STATUS_CODE, status)
                .setCauseIfPresent(failure)
                .addKeyValueIfPresent(EndpointLogField.SLOW, true.takeIf { slow })
                .addKeyValueIfPresent(EndpointLogField.URL_TEMPLATE, exchange.pathTemplate)
                .addKeyValueIfPresent(EndpointLogField.URL_QUERY, exchange.query)
                .addKeyValueIfPresent(EndpointLogField.REQUEST_HEADERS, renderHeaders(exchange.requestHeaders))
                .addKeyValueIfPresent(EndpointLogField.RESPONSE_HEADERS, renderHeaders(responseHeaders))
                .addKeyValueIfPresent(EndpointLogField.REQUEST_BODY, requestBody)
                .addKeyValueIfPresent(EndpointLogField.RESPONSE_BODY, responseBody)
                .log()
            // Guarded inside the metrics: a throwing host counter after a successful log() must not be
            // reported as a lost emission.
            metrics.eventEmitted(outcome)
        }
    }

    /**
     * The opt-in body measurements: the size samples, and - for the request side - the read-state
     * counter, which is what tells an unread body from an absent one (the size sample cannot: both are
     * zero bytes and record nothing).
     */
    private fun recordBodySizes(exchange: Exchange) {
        if (properties.measureRequestBodySize) {
            exchange.requestCapture?.let {
                metrics.requestBodySize(exchange.pathTemplate, it.totalBytes)
                metrics.requestBodyRead(exchange.pathTemplate, it.readState)
            }
        }
        if (properties.measureResponseBodySize) {
            exchange.responseCapture?.let { metrics.responseBodySize(exchange.pathTemplate, it.totalBytes) }
        }
    }

    /** Renders selected headers as `[name:"value", ...]`, or null when nothing was selected or present. */
    private fun renderHeaders(headers: List<Pair<String, String>>): String? {
        if (headers.isEmpty()) {
            return null
        }
        return headers.joinToString(separator = ", ", prefix = "[", postfix = "]") { (name, value) -> "$name:\"$value\"" }
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        // Failures of the logging itself go to the module's own logger, never onto the exchange logger -
        // the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ExchangeLogEmitter::class.java)
    }
}
