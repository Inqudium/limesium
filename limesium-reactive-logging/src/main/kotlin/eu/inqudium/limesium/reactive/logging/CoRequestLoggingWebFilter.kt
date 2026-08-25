package eu.inqudium.limesium.reactive.logging

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

/**
 * The coroutine-idiomatic variant of [RequestLoggingWebFilter] for Kotlin-coroutine WebFlux applications
 * (`suspend fun` handlers): IDENTICAL logging, configuration and metrics - both variants delegate to the
 * same [ExchangeLifecycle], so nothing about the output can differ - expressed as a [CoWebFilter], with
 * one coroutine-native addition:
 *
 * **Handler MDC via [MDCContext].** The chain runs inside
 * `withContext(MDCContext(identity))`; [CoWebFilter] publishes the active coroutine context to the
 * downstream handler invocation (via its context attribute), and `MDCContext` restores the installed
 * map into the thread-local MDC on EVERY coroutine resumption - so every log line inside a suspend
 * handler carries `endpoint_request_id`/`endpoint_method`/`endpoint_route`, whatever thread the
 * dispatcher resumed on. The installed map is an ADDITIVE overlay over the ambient MDC (see the
 * construction in [filter]). This is the coroutine-native equivalent of the Reactor variant's
 * context-propagation opt-in, and of the servlet twin's chain-wide MDC scope - with no dependency on
 * `io.micrometer:context-propagation`.
 *
 * The signal mapping mirrors the Reactor variant exactly: normal return = COMPLETE,
 * [CancellationException] = CANCEL (client disconnect; rethrown - structured concurrency must see it),
 * any other [Throwable] = ERROR (breadcrumb now, emission deferred to the response commit for the
 * rendered status; rethrown - note that kotlinx's stacktrace recovery may surface a COPY of the
 * exception across the coroutine-to-Reactor boundary, with the original as its cause; type, message and
 * the reachable original, which upstream error handling classifies on, are preserved).
 *
 * **Fail-open of the MDC hand-off.** The ambient snapshot is a host MDC-adapter call and is guarded like
 * every other logging-owned collaborator call: when it throws, the chain runs WITHOUT the handler MDC
 * (counted `stage=wiring`) instead of failing the request (finding 2 of
 * CODE_ANALYSIS-2026-08-22T23-32-09.md). Residual, deliberately not guarded: [MDCContext] installs and
 * restores the map inside kotlinx on every resumption; an adapter throwing THERE surfaces from
 * `withContext` indistinguishably from a handler failure and is treated as one. Such an adapter breaks
 * every `MDCContext` user in the host, not only this filter, so no second mechanism is built around it.
 *
 * Requires `kotlinx-coroutines-reactor` (for [CoWebFilter]) and `kotlinx-coroutines-slf4j` (for
 * [MDCContext]) - both optional dependencies of this module; their presence is what makes the
 * auto-configuration choose this variant over the Reactor one.
 */
class CoRequestLoggingWebFilter(
    properties: RequestLoggingProperties,
    nanoTime: NanoTimeSource,
    correlationIds: CorrelationIdGenerator,
    meterRegistry: MeterRegistry,
) : CoWebFilter(),
    EndpointLoggingFilter {
    private val lifecycle = ExchangeLifecycle(properties, nanoTime, correlationIds, meterRegistry)

    /** Symmetric to the servlet twin's registration order; early, so the correlation echo is set first. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        if (lifecycle.shouldNotFilter(exchange.request.path)) {
            return chain.filter(exchange)
        }
        val wiring = lifecycle.wireOrNull(exchange) ?: return chain.filter(exchange)
        val ex = wiring.exchange
        lifecycle.logRequestStartIfEnabled(ex)
        // ADDITIVE snapshot: MDCContext installs the supplied map as the coroutine's COMPLETE MDC on
        // every resumption - handing it only the three identity keys would delete every ambient entry
        // (trace ids, baggage, host keys) inside suspend handlers. So the ambient MDC of the current
        // thread is preserved and the module-owned keys overlay it, endpoint_* winning on collision -
        // the same overlay semantics as MdcScope (finding 2 of CODE_ANALYSIS-2026-08-22.md).
        val handlerMdc = handlerMdcOrNull(ex)
        try {
            if (handlerMdc == null) {
                chain.filter(wiring.mutatedExchange)
            } else {
                withContext(handlerMdc) {
                    chain.filter(wiring.mutatedExchange)
                }
            }
            lifecycle.onTerminal(exchange, ex, TerminalKind.COMPLETE)
        } catch (e: CancellationException) {
            // Client disconnect: mark, complete, and RETHROW - consuming a cancellation would break
            // structured concurrency.
            ex.cancelled = true
            lifecycle.onTerminal(exchange, ex, TerminalKind.CANCEL)
            throw e
        } catch (e: Throwable) {
            // Rethrown after the terminal handling - this filter adds visibility only; error semantics
            // belong to the upstream exception handler (which the deferred emission waits for). Across
            // the coroutine-to-Reactor bridge kotlinx may recover the stacktrace into a copy whose cause
            // is this original - see the class KDoc.
            ex.failure = e
            lifecycle.onTerminal(exchange, ex, TerminalKind.ERROR)
            throw e
        }
    }

    /**
     * The [MDCContext] carrying the ambient MDC plus the exchange identity - or null when the ambient
     * snapshot failed, in which case the chain runs without handler MDC (see the class KDoc). The
     * snapshot happens OUTSIDE the chain's try/catch: its failure is the filter's, never the handler's.
     */
    private fun handlerMdcOrNull(ex: Exchange): MDCContext? {
        val ambient =
            try {
                MDC.getCopyOfContextMap() ?: emptyMap()
            } catch (e: Exception) {
                reportQuietly {
                    lifecycle.metrics.wiringFailure()
                    internalLog.warn(
                        "Ambient MDC could not be read for {} {} (correlationId={}) - the handler runs without endpoint MDC: {}",
                        ex.method,
                        ex.path,
                        ex.correlationId,
                        e.toString(),
                    )
                }
                return null
            }
        return MDCContext(
            ambient +
                mapOf(
                    MdcKeys.REQUEST_ID to ex.correlationId,
                    MdcKeys.REQUEST_METHOD to ex.method,
                    MdcKeys.ROUTE to ex.path,
                ),
        )
    }

    companion object {
        // Named after the reference variant, like ExchangeLifecycle's logger, so the reference
        // configuration's logger levels keep applying to both variants.
        private val internalLog = LoggerFactory.getLogger(RequestLoggingWebFilter::class.java)
    }
}
