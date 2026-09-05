package eu.inqudium.limesium.reactive.logging

import eu.inqudium.limesium.common.CorrelationHeaderValue
import eu.inqudium.limesium.common.CorrelationIdGenerator
import eu.inqudium.limesium.common.HeaderValueMasker
import eu.inqudium.limesium.common.MdcKeys
import eu.inqudium.limesium.common.NanoTimeSource
import eu.inqudium.limesium.common.Traceparent
import eu.inqudium.limesium.common.failOpen
import eu.inqudium.limesium.common.reportQuietly
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.server.PathContainer
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** The three ways an exchange's chain can end; the lifecycle maps each to the right completion path. */
internal enum class TerminalKind { COMPLETE, ERROR, CANCEL }

/**
 * The shared choreography of both filter variants ([RequestLoggingWebFilter] and
 * [CoRequestLoggingWebFilter]): activation matching, fail-open wiring, the arrival line, the commit
 * callback with the deferred error emission, and the guarded terminal handling. The variants own only
 * their chain-invocation style (Reactor signals vs. suspend try/catch) - everything that decides WHAT is
 * logged and counted lives here, once.
 *
 * All documentation about the individual mechanisms (deferred commit, fail-open stages, gauge semantics)
 * lives on [RequestLoggingWebFilter], the reference variant.
 */
internal class ExchangeLifecycle(
    private val properties: RequestLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val correlationIds: CorrelationIdGenerator,
    meterRegistry: MeterRegistry,
    private val masker: HeaderValueMasker,
) {
    /** Shared with the variants for the arrival line and for tests; one instance per filter. */
    val metrics = EndpointLoggingMetrics.forRegistry(meterRegistry)
    val emitter = ExchangeLogEmitter(properties, nanoTime, metrics, masker)

    // Parsed ONCE at construction: an invalid pattern is a configuration error and fails the context
    // start with the parser's message, instead of failing per request.
    private val includePathPatterns: List<PathPattern> =
        properties.includePathPatterns.map { PathPatternParser.defaultInstance.parse(it) }

    /**
     * The filter is active for a request when it matches ANY include pattern (empty includes = every
     * endpoint) and NO exclude prefix - an exclude always wins; identical semantics with the servlet
     * twin's shouldNotFilter.
     *
     * [path] is the raw path WITHIN the application (`RequestPath.pathWithinApplication()` - the
     * variants strip a configured WebFlux base path first, mirroring the servlet twin's context-path
     * handling; finding 3 of the repo-wide code analysis of 2026-08-30), whose segments decode for
     * matching - so the include patterns see exactly what the router's handler mapping sees; the
     * exclude prefixes are compared against the decoded path rebuilt from those segments (path
     * parameters dropped, as in routing). Matching the already-decoded `uri.path`
     * re-parsed into a container decoded twice and accepted `/api%2Fthings` for the `/api/` double-star pattern where the
     * router does not (twin parity with the servlet module's percent-encoding fix).
     */
    fun shouldNotFilter(path: PathContainer): Boolean {
        if (includePathPatterns.isNotEmpty() && includePathPatterns.none { it.matches(path) }) {
            return true
        }
        if (properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val decodedPath =
            path.elements().joinToString("") { element ->
                if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
            }
        return properties.excludePathPrefixes.any { decodedPath.startsWith(it) }
    }

    /**
     * The fail-open wiring: an exception degrades the filter to a plain pass-through (the caller sees
     * null), counted `stage=wiring` - a logging component must never fail the request it describes.
     */
    fun wireOrNull(webExchange: ServerWebExchange): Wiring? =
        try {
            wireExchange(webExchange)
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.error(
                    "Request logging could not be wired for {} {} - continuing without logging: {}",
                    webExchange.request.method,
                    webExchange.request.uri.rawPath,
                    e.toString(),
                    e,
                )
            }
            null
        }

    fun logRequestStartIfEnabled(exchange: Exchange) {
        if (properties.logRequestStart) {
            emitter.logRequestStart(exchange)
        }
    }

    /**
     * Commit callback of the deferred error path: captures the FINAL status and emits. Registered from
     * [onTerminal] at the error signal, not at filter entry: Spring runs `beforeCommit` actions in
     * registration order, so this one must land BEHIND every action the chain registered (security or
     * session header writers, a status mutation) to observe their effects.
     *
     * RESIDUALS of this boundary (a WebFilter has
     * no hook that observes commit COMPLETION, only `beforeCommit`, and Spring's commit state is
     * private): an action registered after the terminal signal still runs after this one; an earlier
     * action that fails the commit prevents this one, and Spring's retry from `COMMIT_ACTION_FAILED`
     * (`isCommitted` false again) SKIPS every action - the exchange then stays open on the gauge even
     * when the outer handler's retry does reach the client, rather than logging a status it never saw.
     * Both are the documented never-commits semantics; the gauge is their liveness signal.
     *
     * FAIL-OPEN twice over: the callback BODY runs inside the response-commit chain, where an escaping
     * exception would disturb the commit itself; the REGISTRATION runs against a possibly host-provided
     * response facade whose `beforeCommit` may throw. A failed
     * registration leaves the exchange UNARMED and the error path completes at the terminal signal.
     */
    private fun registerCommitCallback(
        webExchange: ServerWebExchange,
        exchange: Exchange,
    ) {
        failOpen(
            onInterrupted = { e ->
                metrics.wiringFailure()
                internalLog.debug("Interrupted while registering the commit callback; the error path will not defer", e)
            },
            onFailure = { e ->
                metrics.wiringFailure()
                internalLog.error(
                    "Could not register the commit callback for {} {} - the error path will not defer: {}",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                    e,
                )
            },
        ) {
            webExchange.response.beforeCommit {
                Mono.fromRunnable {
                    failOpen(
                        onInterrupted = { e ->
                            metrics.emissionFailure()
                            internalLog.debug("Interrupted in the commit callback; the event is dropped", e)
                        },
                        onFailure = { e ->
                            metrics.emissionFailure()
                            internalLog.error(
                                "Exception in the commit callback for {} {}: {}",
                                exchange.method,
                                exchange.path,
                                e.toString(),
                                e,
                            )
                        },
                    ) {
                        exchange.committedStatus = webExchange.response.statusCode?.value()
                        if (exchange.state.get() == ExchangeState.AWAITING_COMMIT) {
                            complete(exchange)
                        }
                    }
                }
            }
            exchange.commitCallbackArmed = true
        }
    }

    /**
     * The guarded terminal handling, shared verbatim by both variants: template read, breadcrumb and
     * defer-or-complete on error, immediate completion otherwise. An exception in the bookkeeping is
     * confined (counted `stage=wiring`) and the exchange is STILL completed unless the deferral was
     * armed - a broken breadcrumb costs detail, never the event.
     */
    fun onTerminal(
        webExchange: ServerWebExchange,
        exchange: Exchange,
        kind: TerminalKind,
    ) {
        try {
            // The best-matching handler pattern is recorded by WebFlux during dispatch; PathPattern's
            // patternString is the low-cardinality template.
            exchange.pathTemplate =
                webExchange.attributes[RequestLoggingWebFilter.BEST_MATCHING_PATTERN_ATTRIBUTE]?.let {
                    (it as? PathPattern)?.patternString ?: it.toString()
                }
            if (kind == TerminalKind.ERROR) {
                // Immediate breadcrumb at the failure site, identical to the servlet twin: the full
                // ERROR event follows at commit (deferred) or right below (already committed).
                exchange.failure?.let {
                    internalLog.warn(
                        "Endpoint http exchange failed: {} {} - {} [{}={}]",
                        exchange.method,
                        exchange.path,
                        it.toString(),
                        MdcKeys.REQUEST_ID,
                        exchange.requestId,
                    )
                }
                if (!webExchange.response.isCommitted) {
                    // Defer to the commit callback for the RENDERED status - only if it could be armed;
                    // unarmed, the event completes right below with the then-readable status.
                    registerCommitCallback(webExchange, exchange)
                    if (exchange.commitCallbackArmed) {
                        exchange.state.compareAndSet(ExchangeState.OPEN, ExchangeState.AWAITING_COMMIT)
                        // Race with a commit starting concurrently: doCommit goes COMMITTING (isCommitted)
                        // BEFORE it snapshots the action list, so a registration that still saw
                        // isCommitted = false is in the snapshot; otherwise this side completes itself and
                        // the emitter's fallback to response.statusCode reads the rendered value. That
                        // status is final (the renderer sets it before it commits); a header an action
                        // still in flight adds afterwards is missed - residual, see registerCommitCallback.
                        if (webExchange.response.isCommitted) {
                            complete(exchange)
                        }
                        return
                    }
                }
            }
            complete(exchange)
        } catch (e: InterruptedException) {
            // Restore what the JVM cleared when it threw, so the interrupt still reaches its addressee.
            Thread.currentThread().interrupt()
            reportQuietly {
                metrics.wiringFailure()
                internalLog.debug("Interrupted in the terminal callback", e)
            }
            if (exchange.state.get() != ExchangeState.AWAITING_COMMIT) {
                complete(exchange)
            }
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn(
                    "Request logging failed for {} {} (requestId={}): {}",
                    exchange.method,
                    exchange.path,
                    exchange.requestId,
                    e.toString(),
                    e,
                )
            }
            if (exchange.state.get() != ExchangeState.AWAITING_COMMIT) {
                complete(exchange)
            }
        }
    }

    /** Exactly-once: closes the gauge and emits, whichever of the terminal/commit callbacks wins the transition. */
    fun complete(exchange: Exchange) {
        if (exchange.state.getAndSet(ExchangeState.COMPLETED) == ExchangeState.COMPLETED) {
            return
        }
        metrics.exchangeCompleted()
        emitter.logExchange(exchange)
    }

    private fun wireExchange(webExchange: ServerWebExchange): Wiring {
        val request = webExchange.request
        // The exchange identity, resolved per ADR-0002: a conformant traceparent's trace id IS the
        // request id (the caller's X-Correlation-Id is ignored on such exchanges - the distributed
        // identity outranks the private one); only a traceless exchange accepts the correlation header
        // or generates a fresh id, and only a traceless exchange gets the echo - a traced exchange
        // passes through observationally untouched. A header value outside the acceptance rule
        // (CorrelationHeaderValue: 1-128 visible-ASCII characters) counts as absent.
        val trace = Traceparent.parse(request.headers.getFirst(Traceparent.HEADER))
        val headerCorrelationId =
            if (trace == null) {
                CorrelationHeaderValue.accept(request.headers.getFirst(properties.correlationIdHeader))
            } else {
                null
            }
        val requestId = trace?.first ?: headerCorrelationId ?: correlationIds.nextCorrelationId()
        // Guarded inside the metrics: a throwing host counter must not turn the request into an
        // unlogged pass-through.
        metrics.requestId(
            when {
                trace != null -> EndpointLoggingMetrics.REQUEST_ID_SOURCE_TRACE
                headerCorrelationId != null -> EndpointLoggingMetrics.REQUEST_ID_SOURCE_HEADER
                else -> EndpointLoggingMetrics.REQUEST_ID_SOURCE_GENERATED
            },
        )
        if (trace == null) {
            webExchange.response.headers.set(properties.correlationIdHeader, requestId)
        }

        // A capture exists when the body is logged in ANY mode OR measured - `on-failure` needs the
        // bytes before the outcome is known and the emitter drops them on success; measure-only runs
        // the capture in count-only mode (limit 0: nothing buffered, every byte counted).
        val requestCapture =
            if (properties.logRequestBody.captures || properties.measureRequestBodySize) {
                BoundedBodyCapture(if (properties.logRequestBody.captures) properties.maxBodyBytes else 0)
            } else {
                null
            }
        val responseCapture =
            if (properties.logResponseBody.captures || properties.measureResponseBodySize) {
                BoundedBodyCapture(if (properties.logResponseBody.captures) properties.maxBodyBytes else 0)
            } else {
                null
            }
        val mutatedExchange =
            if (requestCapture == null && responseCapture == null) {
                webExchange
            } else {
                val builder = webExchange.mutate()
                requestCapture?.let { builder.request(CapturingRequestDecorator(request, it)) }
                responseCapture?.let { builder.response(CapturingResponseDecorator(webExchange.response, it)) }
                builder.build()
            }

        // RAW (still percent-encoded) path and query, as the client sent them - twin parity with the
        // servlet module's requestURI/queryString, and the log-injection guard: java.net.URI's decoded
        // getPath()/getQuery() turn `%0A`/`%0D` into real line breaks that would forge lines in every
        // plain-text sink (message, MDC endpoint_route, handler MDC, fields). The server rejects
        // unencoded control characters in
        // the request target at framing time, so the raw form carries none. Activation matching keeps
        // the decoded path (the same representation the WebFlux router decodes per segment).
        val exchange =
            Exchange(
                method = request.method.name(),
                path = request.uri.rawPath,
                query = if (properties.includeQueryString) request.uri.rawQuery else null,
                requestId = requestId,
                // Multi-value resolution, natively from the reactive HttpHeaders.
                requestHeaders =
                    properties.requestHeaders.select(request.headers.headerNames(), masker) { name ->
                        request.headers[name]?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    },
                requestCapture = requestCapture,
                responseCapture = responseCapture,
                requestCharset = request.headers.declaredCharsetOrUtf8(),
                response = webExchange.response,
                startNanos = nanoTime.nanoTime(),
                traceId = trace?.first,
                parentSpanId = trace?.second,
            )
        metrics.exchangeOpened()
        return Wiring(exchange, mutatedExchange)
    }

    /** The wired [exchange] plus the (possibly body-capturing) exchange the chain receives. */
    internal class Wiring(
        val exchange: Exchange,
        val mutatedExchange: ServerWebExchange,
    )

    companion object {
        // The breadcrumb and wiring failures go to the module's own logger, never onto the exchange
        // logger - the exchange log stream stays parseable. Named after the reference variant so the
        // reference configuration's logger levels keep applying.
        private val internalLog = LoggerFactory.getLogger(RequestLoggingWebFilter::class.java)
    }
}

/**
 * The charset the `Content-Type` declares, UTF-8 when there is none or the media type does not parse -
 * a malformed header is the peer's problem and must not cost the log line. One definition for the
 * request side (wiring) and the response side (emission).
 */
internal fun HttpHeaders.declaredCharsetOrUtf8(): Charset =
    try {
        contentType?.charset
    } catch (e: InvalidMediaTypeException) {
        null
    } ?: StandardCharsets.UTF_8
