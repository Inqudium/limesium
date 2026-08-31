package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.MdcScope
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.TraceMdcKeys
import eu.inqudium.limesium.common.failOpen
import eu.inqudium.limesium.common.reportQuietly
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Builds and emits the log events of an exchange - the arrival line and the completion event - with the
 * IDENTICAL message and field format of the limesium-reactive-logging emitter (fields locked by
 * `EndpointLogFieldTest`, message text by `TwinContractTest`, in both twins). The filter owns the servlet
 * lifecycle and hands over a populated [Exchange]; this class owns the exchange logger, the level/outcome
 * decision, the field assembly, and the fail-open discipline around all of it.
 *
 * ## Levels
 *
 * The level carries severity only, `endpoint_outcome` the semantic: ERROR when the chain threw, WARN for
 * a 5xx, a container timeout, or an exchange that reached
 * [RequestLoggingProperties.slowRequestThreshold], INFO otherwise. Severity and outcome are resolved
 * BEFORE the event is built, so an exchange whose level is disabled costs neither the key-value assembly
 * nor the header rendering.
 *
 * ## Fail-open
 *
 * A failure inside either emission is confined here: reported on this
 * class's own logger, counted on the [EndpointLoggingMetrics] fail-open counter, and an interrupt is
 * re-raised as a flag instead of being consumed on a request-serving thread. Requests are never affected.
 */
internal class ExchangeLogEmitter(
    private val properties: RequestLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val metrics: EndpointLoggingMetrics,
) {
    private val exchangeLog = LoggerFactory.getLogger(properties.loggerName)

    /**
     * The optional arrival line ([RequestLoggingProperties.logRequestStart]): what is known BEFORE the
     * chain - method, path, query, selected request headers - at INFO on the exchange logger, under the
     * exchange's MDC with the traceparent-derived trace overlay: the scope OWNS the trace keys here
     * exactly as at emission, so the arrival line carries the same `traceId`/`parentSpanId` pair as the
     * completion event and an ambient bridge `spanId` on the container thread cannot ride along - twin
     * parity with the reactive arrival line (finding 4 of the repo-wide code analysis of 2026-08-30;
     * before ADR-0002 the chain scope's bridge-captured keys happened to coincide). Deliberately WITHOUT
     * `endpoint_outcome`, status or duration: those exist only at completion, and their absence is what
     * keeps outcome-keyed dashboards blind to this extra line.
     */
    fun logRequestStart(exchange: Exchange) {
        // The guard covers the COMPLETE arrival operation including the level gate: isInfoEnabled is a
        // call into the host's logging backend and as fallible as the emission itself - outside the
        // guard it could fail the request this line merely announces.
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
     * The single emission point of the completion event, called exclusively at request destruction -
     * after the container's error dispatch and after async completion, so status, response headers and
     * captures are FINAL and race-free. The [Exchange.logged] guard backstops to exactly-once.
     *
     * The emission runs under the exchange's MDC (the destruction callback carries none of its own), so
     * the encoder emits the request id as an MDC field rather than as a structured key-value; the
     * message repeats method/path/status and the request id inline, so a plain-text appender that
     * drops key-values and MDC still shows the gist of the exchange.
     */
    fun logExchange(exchange: Exchange) {
        if (!exchange.logged.compareAndSet(false, true)) {
            return
        }
        // The fail-open guard covers EVERYTHING after the exactly-once CAS: the
        // pre-gate section reads host-provided beans (the time source) and the response object at
        // destruction time - an exception there used to escape into the container's listener invocation
        // and lose the event WITHOUT the emission counter seeing it, defeating that counter's purpose.
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
        val async = exchange.asyncStarted
        val disposition = exchange.asyncDisposition
        val elapsedNanos = nanoTime.nanoTime() - exchange.startNanos
        val durationMs = elapsedNanos / NANOS_PER_MS
        val status = exchange.response.status
        // Full-precision, overflow-free comparison (twin parity): a 1.5 ms threshold must not flag a 1 ms
        // exchange. The logged duration keeps millisecond resolution - hence the 1 ms floor in the properties.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        // Metrics BEFORE the level gate: a metric must not depend on how loud the logger is configured.
        // Guarded on their own: a host registry that rejects the body-size summary (meter-id conflict)
        // costs the sample, never the event (twin parity with the reactive module).
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
        // The SLF4J level carries the severity, endpoint_outcome the semantic - decoupled on purpose (see
        // EndpointLogField.OUTCOME): a 5xx without a chain exception is WARN (the application already
        // handled it), a thrown chain is ERROR, a container timeout is WARN; all of the first two carry
        // "failure". A slow but otherwise healthy exchange escalates INFO -> WARN without changing outcome.
        //
        // The async disposition is classified by WHICH CALLBACK occurred (Exchange.asyncDisposition),
        // never by throwable presence: onTimeout MAY carry a throwable (attached as cause, still a
        // timeout) and onError may carry none (still a failure) - inferring from the optional cause
        // misfiled both complements. The precedence (timeout
        // wins over a subsequent onError) is a property of the disposition value itself.
        val classification =
            when {
                exchange.failure != null -> {
                    Classification(Level.ERROR, EndpointLoggingMetrics.OUTCOME_FAILURE, exchange.failure)
                }

                disposition == AsyncDisposition.TIMED_OUT -> {
                    Classification(Level.WARN, EndpointLoggingMetrics.OUTCOME_TIMEOUT, exchange.asyncFailure)
                }

                disposition == AsyncDisposition.ERRORED -> {
                    Classification(Level.ERROR, EndpointLoggingMetrics.OUTCOME_FAILURE, exchange.asyncFailure)
                }

                status >= 500 -> {
                    Classification(Level.WARN, EndpointLoggingMetrics.OUTCOME_FAILURE, null)
                }

                else -> {
                    Classification(Level.INFO, EndpointLoggingMetrics.OUTCOME_SUCCESS, null)
                }
            }
        val outcome = classification.outcome
        val cause = classification.cause
        val level = if (slow && classification.level == Level.INFO) Level.WARN else classification.level
        if (!exchangeLog.isEnabledForLevel(level)) {
            return
        }
        // The emission scope overlays the trace context parsed from the exchange's traceparent header
        // (ADR-0002), so the encoder emits the SAME traceId/parentSpanId the exchange arrived with. The
        // ids ride the MDC only, not the key-values; the message suffix below is the
        // one extra, for plain-text appenders that drop the MDC. The scope OWNS the trace keys
        // (a bridge's spanId included): an id that was not parsed is removed for the emission, so a
        // stale id on the pooled destruction thread cannot join the event to a foreign trace.
        val mdcScope =
            MdcScope(exchange.requestId, exchange.method, exchange.path, exchange.traceId, exchange.parentSpanId, ownsTraceKeys = true)
        val traceSuffix =
            if (exchange.traceId != null || exchange.parentSpanId != null) {
                " ${TraceMdcKeys.TRACE_ID}=${exchange.traceId ?: "-"} ${TraceMdcKeys.PARENT_SPAN_ID}=${exchange.parentSpanId ?: "-"}"
            } else {
                ""
            }
        try {
            // Multi-value resolution, like the request side: a single-value getHeader would silently
            // truncate repeated headers (Set-Cookie being the classic).
            val responseHeaders =
                properties.responseHeaders.select(exchange.response.headerNames) { name ->
                    exchange.response
                        .getHeaders(name)
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
                }
            // Body fields only when body LOGGING is on - a capture may also exist in count-only mode for
            // the size metrics, and its empty buffer must not surface as a truncated-looking field.
            val requestBody =
                if (properties.logRequestBody) {
                    exchange.requestCapture?.loggedValue(exchange.requestWrapper?.bodyCharset ?: StandardCharsets.UTF_8)
                } else {
                    null
                }
            val responseBody =
                if (properties.logResponseBody) {
                    exchange.responseCapture?.loggedValue(exchange.responseWrapper?.bodyCharset() ?: StandardCharsets.UTF_8)
                } else {
                    null
                }
            // One immutable builder chain; optional fields are left off by the *IfPresent helpers. Both
            // halves of the path pair: the expanded path (high cardinality, per-call) and the
            // low-cardinality handler pattern for grouping - only when MVC recorded one. Query as its own
            // field (the path excludes it), only when the request carried one.
            exchangeLog
                .atLevel(level)
                .setMessage(
                    "Endpoint http exchange ${exchange.method} ${exchange.path} -> $status " +
                        "[${MdcKeys.REQUEST_ID}=${exchange.requestId}$traceSuffix]",
                ).addKeyValue(EndpointLogField.OUTCOME, outcome)
                .addKeyValue(EndpointLogField.DURATION_MS, durationMs)
                .addKeyValue(EndpointLogField.REQUEST_METHOD, exchange.method)
                .addKeyValue(EndpointLogField.RESPONSE_STATUS_CODE, status)
                .addKeyValue(EndpointLogField.URL_PATH, exchange.path)
                .addKeyValue(EndpointLogField.ASYNC, async)
                .setCauseIfPresent(cause)
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
        } finally {
            mdcScope.close()
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

    /** The level/outcome/cause triple one exchange classifies to - the `when` above yields it as one value. */
    private class Classification(
        val level: Level,
        val outcome: String,
        val cause: Throwable?,
    )

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        // Failures of the logging itself go to the module's own logger, never onto the exchange logger -
        // the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ExchangeLogEmitter::class.java)
    }
}
