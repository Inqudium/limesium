package eu.inqudium.limesium.servlet.logging

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.ServletRequestListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.server.PathContainer
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Logs ONE structured line per HTTP exchange - method, path, status, duration, correlation id, optionally
 * selected headers and bounded bodies - and carries the exchange's identity in the MDC while the request
 * is being handled, so every application log line downstream is correlatable.
 *
 * ## MDC coverage
 *
 * The chain scope covers the initial dispatch thread; for MVC async controllers the per-request
 * [EndpointMdcCallableInterceptor] restores the identity on the `Callable`/`WebAsyncTask` WORKER thread
 * as well (finding 2 of CODE_ANALYSIS-2026-08-22.md), and the filter PARTICIPATES in the container's
 * ASYNC dispatch (see below), so the result/error rendering phase carries the identity too. Boundary:
 * `DeferredResult` producers and raw Servlet async workers run on APPLICATION-owned threads that neither
 * the container nor Spring routes through this module - propagating context there is the application's
 * responsibility (this section is the canonical statement of that boundary). The emission at request
 * destruction always restores the identity around the exchange event itself.
 *
 * ## The ASYNC dispatch: participation without restart
 *
 * `shouldNotFilterAsyncDispatch` is `false`: when the container re-dispatches a completed async cycle
 * (Spring MVC renders the `Callable`/`DeferredResult` result - or rethrows its failure - in THAT
 * dispatch), this filter runs again, but on the EXISTING exchange: no re-wiring, no second correlation
 * id, no second gauge increment. It only opens the chain-wide [MdcScope] around the dispatch and records
 * an exception propagating out of it as the exchange's failure, exactly like the initial dispatch. Without
 * this pass, an async handler failure reached the event only as a bare `status >= 500` (WARN, no cause)
 * while the synchronous equivalent logged ERROR with its cause, and every log line of the rendering
 * phase lacked the `endpoint_*` identity (finding 1 of CODE_ANALYSIS-2026-08-22T19-52-00.md). A handled
 * async exception (resolved by an `@ExceptionHandler` in the dispatch) never propagates and is
 * classified by its status - parity with the sync path.
 *
 * ## Async body-capture boundary
 *
 * A raw zero-argument `startAsync()` cycle reads/writes beside the tee wrappers and its bytes are logged
 * as absent - see [CapturingRequestWrapper] for the mechanism and the pinning test.
 *
 * This is the successor of the `LoggingFilter` family in `common-web`, redesigned around this repository's
 * principles rather than ported. This class owns the SERVLET side only - correlation resolution, the tee
 * wrappers, the [Exchange] handoff, the MDC chain scope, and the listeners; the collaborators own the
 * rest:
 *
 * - [ExchangeLogEmitter] builds and emits the arrival line and the completion event ([EndpointLogField]
 *   family, level/outcome decision, fail-open discipline).
 * - [EndpointLoggingMetrics] owns the module's meters (fail-open, emitted events, open exchanges,
 *   correlation source, body sizes).
 * - [Exchange] carries the per-exchange state from filter entry to emission; [AsyncOutcomeMarker] marks
 *   timeout/error on it during the async lifecycle.
 * - [MdcScope]/[MdcKeys] maintain the `endpoint_*` MDC identity.
 *
 * ## Emission point: request destruction
 *
 * The exchange event is emitted from [ServletRequestListener.requestDestroyed] (the listener comes from
 * [exchangeCompletionListener], registered by the auto-configuration) - the moment the request finally
 * goes out of scope: after the service, after the container's ERROR dispatch, and for an async exchange
 * after completion. Emitting earlier, in the filter's `finally`, reported the PRE-error-dispatch status: a
 * crashed exchange logged `-> 200` although the client received the 500 the container rendered afterwards.
 * Consequence: [EndpointLogField.DURATION_MS] measures until processing truly ended - request occupancy,
 * not bare chain time. The async lifecycle only MARKS the exchange (see [AsyncOutcomeMarker]); the
 * container orders destruction after async completion, and the exactly-once guard in the emitter
 * backstops container quirks.
 *
 * When the chain throws, a short WARN breadcrumb is additionally logged in the `finally` on the module's
 * OWN logger, so the failure is visible the moment it happens although the full ERROR event follows only
 * at request destruction. It is deliberately not on the exchange logger (one event per exchange is that
 * stream's contract, and level-keyed alerting must count the failure once) and deliberately WARN (the
 * ERROR belongs to the full event). The exception itself is rethrown UNCHANGED - this filter adds
 * visibility only, error semantics belong to the container.
 *
 * ## Fail-open, including the wiring
 *
 * The fail-open contract covers the WHOLE filter, not only the emission: a failure while wiring the
 * exchange (correlation resolution against a host-provided bean, header enumeration, capture
 * construction) degrades this filter to a plain pass-through - counted as `stage=wiring` on the fail-open
 * meter - and the request proceeds unlogged but undisturbed.
 */
class RequestLoggingFilter(
    private val properties: RequestLoggingProperties,
    private val nanoTime: NanoTimeSource,
    private val correlationIds: CorrelationIdGenerator,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    private val metrics = EndpointLoggingMetrics(meterRegistry)
    private val emitter = ExchangeLogEmitter(properties, nanoTime, metrics)

    // Parsed ONCE at construction: an invalid pattern is a configuration error and fails the context
    // start with the parser's message, instead of failing per request.
    private val includePathPatterns: List<PathPattern> =
        properties.includePathPatterns.map { PathPatternParser.defaultInstance.parse(it) }

    /**
     * The filter is active for a request when it matches ANY include pattern (empty includes = every
     * endpoint) and NO exclude prefix - an exclude always wins. Matching runs in-filter rather than via
     * the registration's `urlPatterns`, so the semantics are identical with the reactive twin.
     *
     * Both rules see the request target the way Spring MVC routes it: the raw `requestURI` is parsed
     * into a [PathContainer] whose segments DECODE for matching (the include patterns match it exactly
     * as `PathPattern`-based handler mapping does), and the exclude prefixes are compared against the
     * decoded path rebuilt from those segments. A byte-wise `startsWith` on the raw URI let a
     * percent-encoded variant (`/%61ctuator/health`) slip past an exclude while the container served it
     * under the excluded route (finding 1 of SECURITY_AUDIT-2026-08-23T13-26-06.md - the include side
     * was already consistent). Path parameters (`;x=1`) are dropped, as in routing.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val container = PathContainer.parsePath(request.requestURI)
        if (includePathPatterns.isNotEmpty() && includePathPatterns.none { it.matches(container) }) {
            return true
        }
        if (properties.excludePathPrefixes.isEmpty()) {
            return false
        }
        val decodedPath =
            container.elements().joinToString("") { element ->
                if (element is PathContainer.PathSegment) element.valueToMatch() else element.value()
            }
        return properties.excludePathPrefixes.any { decodedPath.startsWith(it) }
    }

    /** The ASYNC dispatch is filtered - on the existing exchange, never re-wired (see the class KDoc). */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    public override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isAsyncDispatch(request)) {
            filterAsyncDispatch(request, response, filterChain)
            return
        }
        // The WIRING is fail-open too, not only the emission: correlation resolution and the time source
        // are host-provided beans, and header enumeration touches container edges - an exception in any of
        // them must degrade this filter to a plain pass-through, never fail the request (assessment
        // finding 1: the documented fail-open contract used to start only at the chain call below).
        val exchange: Exchange? =
            try {
                wireExchange(request, response)
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.error(
                        "Request logging could not be wired for {} {} - continuing without logging: {}",
                        request.method,
                        request.requestURI,
                        e.toString(),
                        e,
                    )
                }
                null
            }
        if (exchange == null) {
            filterChain.doFilter(request, response)
            return
        }
        val effectiveRequest = exchange.requestWrapper ?: request
        val effectiveResponse = exchange.responseWrapper ?: response
        registerAsyncMdcPropagation(request, exchange)

        // The chain-wide MDC scope is logging-owned work and therefore fail-open too: a throwing MDC
        // adapter degrades the identity feature, never the request (finding 8 of
        // CODE_ANALYSIS-2026-08-22.md - construction used to run unguarded before the chain try).
        // MdcScope itself rolls back a partial install before rethrowing, so the pooled thread never
        // keeps half an identity.
        val mdcScope: MdcScope? =
            try {
                MdcScope(exchange.correlationId, exchange.method, exchange.path)
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.error(
                        "MDC scope could not be opened for {} {} - continuing without chain MDC: {}",
                        exchange.method,
                        exchange.path,
                        e.toString(),
                        e,
                    )
                }
                null
            }
        // The optional arrival line, before the chain and OUTSIDE the try below: a failure in it must be
        // confined (it is, see the emitter - including the level gate), never misattributed as a chain
        // failure.
        if (properties.logRequestStart) {
            emitter.logRequestStart(exchange)
        }
        try {
            filterChain.doFilter(effectiveRequest, effectiveResponse)
        } catch (e: Exception) {
            exchange.failure = e
            throw e
        } finally {
            try {
                // The best-matching handler pattern is recorded by Spring MVC during dispatch, so it is
                // readable here for the sync AND the async case (the mapping runs before the controller).
                exchange.pathTemplate = request.getAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
                // Known boundary: an async cycle that starts AND completes within the chain reads
                // isAsyncStarted=false here - such an exchange logs endpoint_async=false and gets no
                // outcome marker, so a timeout/error inside that window would go unmarked. The servlet
                // API offers no portable "was async ever started" signal; accepted and documented.
                if (request.isAsyncStarted) {
                    exchange.asyncStarted = true
                    request.asyncContext.addListener(AsyncOutcomeMarker(exchange))
                }
                // Immediate breadcrumb at the failure site: the full ERROR event follows only at request
                // destruction, after the container's error dispatch (which is what makes its status
                // final). Short on purpose - the exception's toString, no stack trace; the full event
                // carries the cause. See the class KDoc for why WARN and why the module's own logger.
                exchange.failure?.let {
                    internalLog.warn(
                        "Endpoint http exchange failed: {} {} - {} [{}={}]",
                        exchange.method,
                        exchange.path,
                        it.toString(),
                        MdcKeys.REQUEST_ID,
                        exchange.correlationId,
                    )
                }
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn(
                        "Request logging failed for {} {} (correlationId={}): {}",
                        exchange.method,
                        exchange.path,
                        exchange.correlationId,
                        e.toString(),
                        e,
                    )
                }
            } finally {
                // Restoration is guarded separately: a throwing MDC adapter here must neither fail the
                // request nor MASK an application exception already propagating out of the chain
                // (finding 8 of CODE_ANALYSIS-2026-08-21.md) - it costs the restoration, counted as stage=wiring.
                try {
                    mdcScope?.close()
                } catch (e: Exception) {
                    reportQuietly {
                        metrics.wiringFailure()
                        internalLog.warn(
                            "MDC restoration failed for {} {} - the pooled thread may carry stale endpoint keys: {}",
                            exchange.method,
                            exchange.path,
                            e.toString(),
                            e,
                        )
                    }
                }
            }
        }
    }

    /**
     * The second pass for the container's ASYNC dispatch: the exchange the initial dispatch attached is
     * reused as is; the pass contributes the MDC scope and the failure capture only. A request without
     * an attached exchange (the initial dispatch was excluded or its wiring failed open) passes through.
     * Fail-open like the initial pass: scope trouble costs the identity, never the dispatch.
     */
    private fun filterAsyncDispatch(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val exchange = request.getAttribute(EXCHANGE_ATTRIBUTE) as? Exchange
        if (exchange == null) {
            filterChain.doFilter(request, response)
            return
        }
        val mdcScope: MdcScope? =
            try {
                MdcScope(exchange.correlationId, exchange.method, exchange.path)
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.error(
                        "MDC scope could not be opened for the async dispatch of {} {} - continuing without chain MDC: {}",
                        exchange.method,
                        exchange.path,
                        e.toString(),
                        e,
                    )
                }
                null
            }
        try {
            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            // The async handler's failure, rethrown by Spring MVC in this dispatch and propagating to the
            // container's error handling - recorded like a sync chain failure, breadcrumb included. The
            // breadcrumb is a host-backend call and guarded like the initial dispatch's: a throwing
            // backend must not REPLACE the application exception on its way to the container (finding 3
            // of CODE_ANALYSIS-2026-08-22T23-19-06.md).
            exchange.failure = e
            try {
                internalLog.warn(
                    "Endpoint http exchange failed in the async dispatch: {} {} - {} [{}={}]",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                    MdcKeys.REQUEST_ID,
                    exchange.correlationId,
                )
            } catch (breadcrumb: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn(
                        "Request logging failed for {} {} (correlationId={}): {}",
                        exchange.method,
                        exchange.path,
                        exchange.correlationId,
                        breadcrumb.toString(),
                        breadcrumb,
                    )
                }
            }
            throw e
        } finally {
            try {
                mdcScope?.close()
            } catch (e: Exception) {
                reportQuietly {
                    metrics.wiringFailure()
                    internalLog.warn(
                        "MDC restoration failed after the async dispatch of {} {} - the pooled thread may carry stale endpoint keys: {}",
                        exchange.method,
                        exchange.path,
                        e.toString(),
                        e,
                    )
                }
            }
        }
    }

    /**
     * Registers the per-request [EndpointMdcCallableInterceptor], so `Callable`/`WebAsyncTask`
     * controllers see the `endpoint_*` identity on their MVC worker thread (finding 2 of
     * CODE_ANALYSIS-2026-08-22.md). `WebAsyncUtils` lives in spring-web, so this adds no MVC dependency;
     * in a non-MVC servlet application the registered interceptor is simply never consulted. Fail-open like
     * everything else the filter wires.
     */
    private fun registerAsyncMdcPropagation(
        request: HttpServletRequest,
        exchange: Exchange,
    ) {
        try {
            WebAsyncUtils
                .getAsyncManager(request)
                .registerCallableInterceptor(ASYNC_MDC_INTERCEPTOR_KEY, EndpointMdcCallableInterceptor(exchange, metrics))
        } catch (e: Exception) {
            reportQuietly {
                metrics.wiringFailure()
                internalLog.warn(
                    "Async MDC propagation could not be registered for {} {} - worker logs lose the identity: {}",
                    exchange.method,
                    exchange.path,
                    e.toString(),
                    e,
                )
            }
        }
    }

    /**
     * Everything that must exist before the chain runs: correlation resolution and echo, captures and
     * wrappers, the eagerly captured request-side coordinates, the destruction handoff and the gauge.
     * Called exclusively from the fail-open block in [doFilterInternal] - anything thrown here is
     * confined there and degrades the filter to a pass-through.
     */
    private fun wireExchange(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Exchange {
        val headerCorrelationId = request.getHeader(properties.correlationIdHeader)?.takeUnless { it.isBlank() }
        val correlationId = headerCorrelationId ?: correlationIds.nextCorrelationId()
        // Guarded inside the metrics: a throwing host counter must not turn the request into an
        // unlogged pass-through (finding 4 of CODE_ANALYSIS-2026-08-22T23-19-06.md).
        metrics.correlationId(fromHeader = headerCorrelationId != null)
        response.setHeader(properties.correlationIdHeader, correlationId)

        // A capture exists when the body is logged OR measured; measure-only runs the capture in
        // count-only mode (limit 0: nothing buffered, every byte counted).
        val requestCapture =
            if (properties.logRequestBody || properties.measureRequestBodySize) {
                BoundedBodyCapture(if (properties.logRequestBody) properties.maxBodyBytes else 0)
            } else {
                null
            }
        val responseCapture =
            if (properties.logResponseBody || properties.measureResponseBodySize) {
                BoundedBodyCapture(if (properties.logResponseBody) properties.maxBodyBytes else 0)
            } else {
                null
            }

        // Header values are resolved MULTI-VALUE (comma-joined): a single-value getHeader would silently
        // truncate repeated headers. The enumeration is null-tolerant - the servlet spec permits a
        // container to withhold header access entirely.
        val headerNames = request.headerNames?.toList() ?: emptyList()

        // Request-side coordinates are captured EAGERLY: for an async exchange the log line is written from
        // the completion callback, and the servlet contract only guarantees the request object until then -
        // reading eagerly keeps the emission independent of container recycling subtleties.
        val exchange =
            Exchange(
                method = request.method,
                path = request.requestURI,
                query = if (properties.includeQueryString) request.queryString else null,
                correlationId = correlationId,
                requestHeaders =
                    properties.requestHeaders.select(headerNames) { name ->
                        request.getHeaders(name)?.toList()?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    },
                requestCapture = requestCapture,
                requestWrapper = requestCapture?.let { CapturingRequestWrapper(request, it) },
                responseCapture = responseCapture,
                responseWrapper = responseCapture?.let { CapturingResponseWrapper(response, it) },
                response = response,
                startNanos = nanoTime.nanoTime(),
                // The trace context of THIS exchange's server span, captured while the tracing bridge's
                // scope is still open (Boot's observation filter runs before this one). The emission at
                // request destruction restores it, so the exchange event stays joinable with its trace.
                traceId = MDC.get(TraceMdcKeys.TRACE_ID),
                spanId = MDC.get(TraceMdcKeys.SPAN_ID),
            )
        // The handoff to the emission at request destruction: the ServletRequestListener finds the
        // exchange under this attribute once the request goes out of scope. The gauge goes up with the
        // handoff and down when destruction consumes it - the attribute removal there also guards the
        // gauge against a double decrement.
        request.setAttribute(EXCHANGE_ATTRIBUTE, exchange)
        metrics.exchangeOpened()
        return exchange
    }

    /**
     * The listener that emits the exchange event at request destruction; created here so it shares the
     * filter's emitter and metrics, registered by the auto-configuration. A request the filter never saw
     * (excluded path, non-REQUEST dispatch) carries no exchange attribute and is ignored.
     */
    fun exchangeCompletionListener(): ServletRequestListener = ExchangeCompletionListener()

    private inner class ExchangeCompletionListener : ServletRequestListener {
        override fun requestDestroyed(event: ServletRequestEvent) {
            val exchange = event.servletRequest.getAttribute(EXCHANGE_ATTRIBUTE) as? Exchange ?: return
            event.servletRequest.removeAttribute(EXCHANGE_ATTRIBUTE)
            metrics.exchangeCompleted()
            emitter.logExchange(exchange)
        }

        override fun requestInitialized(event: ServletRequestEvent) = Unit
    }

    companion object {
        /**
         * Request attribute under which Spring MVC records the best-matching handler pattern. Mirrors
         * `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (`HandlerMapping.class.getName() +
         * ".bestMatchingPattern"`), which would drag in spring-webmvc as a dependency - derived the same
         * way instead, so it matches the value MVC sets and stays null in a non-MVC servlet application.
         */
        const val BEST_MATCHING_PATTERN_ATTRIBUTE = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"

        /** Request attribute carrying the exchange from the filter to the emission at request destruction. */
        private val EXCHANGE_ATTRIBUTE: String = RequestLoggingFilter::class.java.name + ".exchange"

        /** Key under which the per-request async MDC interceptor is registered with the WebAsyncManager. */
        private val ASYNC_MDC_INTERCEPTOR_KEY: String = RequestLoggingFilter::class.java.name + ".asyncMdc"

        // The breadcrumb and wiring failures go to the module's own logger, never onto the exchange
        // logger - the exchange log stream stays parseable.
        private val internalLog = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
    }
}
