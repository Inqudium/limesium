package eu.inqudium.limesium.reactive.logging

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType

/**
 * The WebFlux twin of `limesium-servlet-logging`'s `RequestLoggingFilter`: ONE structured `endpoint_*`
 * line per HTTP exchange, identical message and field format, identical `endpoint-logging.*`
 * configuration. This is the REFERENCE variant (Reactor signals); [CoRequestLoggingWebFilter] is the
 * coroutine-idiomatic variant sharing the identical [ExchangeLifecycle]. Stack-inherent differences to
 * the servlet twin, all deliberate:
 *
 * - **Disposition vocabulary:** `cancelled` (client disconnect, the reactive reality) where the servlet
 *   twin has `timeout`; no `endpoint_async` field - everything is asynchronous here.
 * - **No chain-wide THREAD-LOCAL MDC:** handlers hop event-loop threads; the exchange identity rides the
 *   emission's [MdcScope] (and the message inline). It is additionally written to the REACTOR CONTEXT
 *   under the same keys; restoring it into handler-side MDC needs the context-propagation accessors AND
 *   Reactor's automatic propagation mode - the prerequisite, its default and the startup warning are
 *   documented on [EndpointMdcContextPropagation]. (Coroutine applications get the parity natively via
 *   [CoRequestLoggingWebFilter]'s `MDCContext`, with no propagation-mode prerequisite.)
 * - **Trace context from `traceparent`:** the event-loop thread carries no bridge MDC at filter time, so
 *   the incoming W3C header is parsed instead - the trace id is the server span's trace id (`traceId`),
 *   the parent-id is the CALLER's span and is published as `parentSpanId`, never as the local `spanId`
 *   (see [Traceparent], [TraceMdcKeys]).
 *
 * ## Emission point: terminal signal, commit-deferred on error
 *
 * The event is emitted at the chain's terminal signal (`doFinally`). The ERROR signal passes this filter
 * BEFORE the upstream exception handler renders the 500 - emitting there would log the pre-rendering
 * status, the exact wart the servlet twin eliminated with its request-destruction emission. So on an
 * error with an UNCOMMITTED response the emission is deferred to the response's commit callback, which
 * sees the rendered status; an error on a committed response and every other terminal signal emit
 * immediately. The callback is registered AT THE ERROR SIGNAL, behind every `beforeCommit` action the
 * chain registered, so it observes their status/header effects (Spring runs the actions in registration
 * order - see [ExchangeLifecycle]). A commit that never happens (connection died during rendering, or a
 * commit action failing before this one) leaves the exchange open on the gauge - the liveness signal -
 * rather than logging a wrong status. Gauge-close and emission are guarded exactly-once
 * ([Exchange.state]) against the terminal/commit race.
 *
 * ## Fail-open, including the wiring and every callback
 *
 * Identical contract to the servlet twin: a wiring failure degrades the filter to a plain pass-through
 * (`stage=wiring`); the terminal and commit callbacks confine their own failures (`stage=wiring` /
 * `stage=emission`) - see [ExchangeLifecycle]; emission failures are confined in the emitter. Requests
 * are never affected.
 */
class RequestLoggingWebFilter(
    properties: RequestLoggingProperties,
    nanoTime: NanoTimeSource,
    correlationIds: CorrelationIdGenerator,
    meterRegistry: MeterRegistry,
) : EndpointLoggingFilter {
    private val lifecycle = ExchangeLifecycle(properties, nanoTime, correlationIds, meterRegistry)

    /** Symmetric to the servlet twin's registration order; early, so the correlation echo is set first. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (lifecycle.shouldNotFilter(exchange.request.path)) {
            return chain.filter(exchange)
        }
        val wiring = lifecycle.wireOrNull(exchange) ?: return chain.filter(exchange)
        val ex = wiring.exchange
        lifecycle.logRequestStartIfEnabled(ex)
        // Mono.defer: a downstream filter that THROWS while assembling its publisher (instead of
        // returning Mono.error) must become THIS pipeline's error signal - invoked bare, the exception
        // would propagate synchronously past doOnError/doFinally, lose the exchange event and leak the
        // open-exchange gauge (finding 4 of an internal code analysis).
        return Mono
            .defer { chain.filter(wiring.mutatedExchange) }
            .doOnError { ex.failure = it }
            .doOnCancel { ex.cancelled = true }
            .doFinally { signal ->
                lifecycle.onTerminal(
                    exchange,
                    ex,
                    when (signal) {
                        SignalType.ON_ERROR -> TerminalKind.ERROR
                        SignalType.CANCEL -> TerminalKind.CANCEL
                        else -> TerminalKind.COMPLETE
                    },
                )
            }
            // The exchange identity in the REACTOR CONTEXT, under the same names as the MDC keys: three
            // cheap immutable puts that only the EndpointMdcContextPropagation accessors read - and only
            // under automatic propagation. No endpoint-logging.* key exists for this, which keeps the
            // namespace identical across the twins.
            .contextWrite { ctx ->
                ctx
                    .put(MdcKeys.REQUEST_ID, ex.correlationId)
                    .put(MdcKeys.REQUEST_METHOD, ex.method)
                    .put(MdcKeys.ROUTE, ex.path)
            }
    }

    companion object {
        /**
         * Request attribute under which WebFlux records the best-matching handler pattern. Mirrors
         * `org.springframework.web.reactive.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` without
         * depending on spring-webflux - derived the same way, so it matches the value WebFlux sets and
         * stays null in a non-WebFlux reactive application.
         */
        const val BEST_MATCHING_PATTERN_ATTRIBUTE = "org.springframework.web.reactive.HandlerMapping.bestMatchingPattern"
    }
}
