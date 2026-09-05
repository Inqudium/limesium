package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.EndpointLogField
import eu.inqudium.limesium.common.EndpointLoggingMetrics
import eu.inqudium.limesium.common.ExchangeLine
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.MdcScope
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.addKeyValue
import eu.inqudium.limesium.common.addKeyValueIfPresent
import eu.inqudium.limesium.common.failOpen
import eu.inqudium.limesium.common.setCauseIfPresent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Builds and emits the log events of an exchange - the arrival line and the completion event - with the
 * IDENTICAL message and field format of the limesium-reactive-logging emitter. The stack-neutral core
 * (message texts, header rendering, the arrival line, the body measurements) is the shared
 * [ExchangeLine]; what this class owns is what differs on this stack: the async disposition
 * (`timeout` where the reactive twin has `cancelled`), the always-present `endpoint_async` flag and
 * status, and the response reads against the servlet response. The filter owns the servlet lifecycle
 * and hands over a populated [Exchange].
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
    private val masker: HeaderValueMasker,
) {
    private val exchangeLog = LoggerFactory.getLogger(properties.loggerName)

    /**
     * The optional arrival line ([RequestLoggingProperties.logRequestStart]) - the shared
     * [ExchangeLine.logRequestStart]. On this stack it runs inside the filter's chain scope, so
     * structured encoders see the `endpoint_*` identity on it either way; the scope's ownership of the
     * trace keys keeps an ambient bridge `spanId` on the container thread off the line.
     */
    fun logRequestStart(exchange: Exchange) = ExchangeLine.logRequestStart(exchangeLog, internalLog, metrics, exchange)

    /**
     * The single emission point of the completion event, called exclusively at request destruction -
     * after the container's error dispatch and after async completion, so status, response headers and
     * captures are FINAL and race-free. [Exchange.tryClaimEmission] backstops to exactly-once.
     *
     * The emission runs under the exchange's MDC (the destruction callback carries none of its own), so
     * the encoder emits the request id as an MDC field rather than as a structured key-value; the
     * message repeats method/path/status and the request id inline, so a plain-text appender that
     * drops key-values and MDC still shows the gist of the exchange.
     */
    fun logExchange(exchange: Exchange) {
        if (!exchange.tryClaimEmission()) {
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
        val durationMs = elapsedNanos / ExchangeLine.NANOS_PER_MS
        val status = exchange.response.status
        // Full-precision, overflow-free comparison (twin parity): a 1.5 ms threshold must not flag a 1 ms
        // exchange. The logged duration keeps millisecond resolution - hence the 1 ms floor in the properties.
        val slow = Duration.ofNanos(elapsedNanos) >= properties.slowRequestThreshold
        ExchangeLine.recordBodySizesQuietly(
            internalLog,
            metrics,
            exchange,
            exchange.pathTemplate,
            exchange.requestCapture,
            exchange.responseCapture,
            properties.measureRequestBodySize,
            properties.measureResponseBodySize,
        )
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
        // ids ride the MDC only, not the key-values; the message suffix is the one extra, for plain-text
        // appenders that drop the MDC. The scope OWNS the trace keys (a bridge's spanId included): an id
        // that was not parsed is removed for the emission, so a stale id on the pooled destruction thread
        // cannot join the event to a foreign trace.
        val mdcScope =
            MdcScope(exchange.requestId, exchange.method, exchange.path, exchange.traceId, exchange.parentSpanId, ownsTraceKeys = true)
        try {
            // Multi-value resolution, like the request side: a single-value getHeader would silently
            // truncate repeated headers (Set-Cookie being the classic).
            val responseHeaders =
                properties.responseHeaders.select(exchange.response.headerNames, masker) { name ->
                    exchange.response
                        .getHeaders(name)
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
                }
            // Body fields only when the direction's mode admits THIS outcome: `on-failure` captured the
            // bytes (the outcome is unknown while they flow) and discards them here for a clean exchange (success outcome, no 4xx). A
            // capture may also exist in count-only mode for the size metrics, and its empty buffer must
            // not surface as a truncated-looking field.
            val failed = outcome != EndpointLoggingMetrics.OUTCOME_SUCCESS || status in 400..499
            val requestBody =
                if (properties.logRequestBody.logs(failed)) {
                    exchange.requestCapture?.loggedValue(exchange.requestWrapper?.bodyCharset ?: StandardCharsets.UTF_8)
                } else {
                    null
                }
            val responseBody =
                if (properties.logResponseBody.logs(failed)) {
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
                .setMessage(ExchangeLine.exchangeMessage(exchange, status.toString()))
                .addKeyValue(EndpointLogField.OUTCOME, outcome)
                .addKeyValue(EndpointLogField.DURATION_MS, durationMs)
                .addKeyValue(EndpointLogField.REQUEST_METHOD, exchange.method)
                .addKeyValue(EndpointLogField.RESPONSE_STATUS_CODE, status)
                .addKeyValue(EndpointLogField.URL_PATH, exchange.path)
                .addKeyValue(EndpointLogField.ASYNC, async)
                .setCauseIfPresent(cause)
                .addKeyValueIfPresent(EndpointLogField.SLOW, true.takeIf { slow })
                .addKeyValueIfPresent(EndpointLogField.URL_TEMPLATE, exchange.pathTemplate)
                .addKeyValueIfPresent(EndpointLogField.URL_QUERY, exchange.query)
                .addKeyValueIfPresent(EndpointLogField.REQUEST_HEADERS, ExchangeLine.renderHeaders(exchange.requestHeaders))
                .addKeyValueIfPresent(EndpointLogField.RESPONSE_HEADERS, ExchangeLine.renderHeaders(responseHeaders))
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

    /** The level/outcome/cause triple one exchange classifies to - the `when` above yields it as one value. */
    private class Classification(
        val level: Level,
        val outcome: String,
        val cause: Throwable?,
    )

    companion object {
        // Failures of the logging itself go to the module's own logger, never onto the exchange logger -
        // the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(ExchangeLogEmitter::class.java)
    }
}
