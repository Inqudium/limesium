package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyReadState
import eu.inqudium.limesium.common.reportQuietly
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The module's meters (the `*_METER` constants below), all fed from the host's registry. Every meter
 * here observes what neither `http.server.requests` nor the log fields can show; rates, latencies and
 * status distributions are deliberately left to those.
 *
 * All fixed-tag meters are PRE-registered at construction: a `rate()` alert must see the zero before the
 * first occurrence, not a meter that springs into existence at the very moment it should already fire.
 *
 * ONE INSTANCE PER REGISTRY, enforced by [forRegistry]: Micrometer deduplicates meters by id, so a
 * second instance of this class against the same registry would share the counters (harmless -
 * increments merge) but NOT the gauge: the second gauge registration is silently ignored and that
 * instance's open-exchange movements become invisible. Every filter therefore obtains its metrics
 * through [forRegistry], and filters on one registry share one owner - the gauge then reports the
 * total open exchanges across them.
 *
 * FAIL-OPEN REGISTRATION: Micrometer rejects a registration whose id already exists with a different
 * meter type (a host or another library owning an `endpoint.*` name). Unguarded, that throw at
 * construction would abort the application context - a logging library must not - and at the lazy
 * body-size registration would suppress the exchange event. Every registration therefore falls back to a
 * private [SimpleMeterRegistry] for the conflicting meter, logged once per meter name: the module keeps
 * working and the affected meter is simply not exported (twin parity with the reactive module).
 */
internal class EndpointLoggingMetrics private constructor(
    private val meterRegistry: MeterRegistry,
) {
    private val fallbackRegistry = SimpleMeterRegistry()
    private val reportedConflicts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Registers through [register] against the host registry; on rejection the meter lands in the
     * private fallback registry instead, with one warning per meter name.
     */
    private fun <M : Meter> registerOrFallback(
        meterName: String,
        register: (MeterRegistry) -> M,
    ): M =
        try {
            register(meterRegistry)
        } catch (e: Exception) {
            if (reportedConflicts.add(meterName)) {
                internalLog.warn(
                    "Meter {} could not be registered in the host registry and is kept private (not exported): {}",
                    meterName,
                    e.toString(),
                )
            }
            register(fallbackRegistry)
        }

    // One counter per fail-open site. The metric exists because the failure it counts is the one state
    // logs cannot reliably show: when the emission breaks, the missing exchange line IS the symptom, and
    // the report about it is itself only a log line in the same possibly-broken pipeline. A counter
    // travels the independent metrics channel.
    private val failOpenCounters =
        listOf(STAGE_EMISSION, STAGE_ARRIVAL, STAGE_WIRING).associateWith { stage ->
            registerOrFallback(FAIL_OPEN_METER) { registry ->
                Counter
                    .builder(FAIL_OPEN_METER)
                    .tag("stage", stage)
                    .description(
                        "Logging failures swallowed by the fail-open path; each increment is a lost or " +
                            "degraded log emission that never disturbed its request",
                    ).register(registry)
            }
        }

    // Counts EMITTED exchange events - after the level gate, arrival lines excluded - so its sum is the
    // ground truth to reconcile against the log index: any difference is loss in the log pipeline
    // (appender overflow, broker loss, index rejection), isolated from application behavior.
    private val eventCounters =
        listOf(OUTCOME_SUCCESS, OUTCOME_FAILURE, OUTCOME_TIMEOUT).associateWith { outcome ->
            registerOrFallback(EVENTS_METER) { registry ->
                Counter
                    .builder(EVENTS_METER)
                    .tag("outcome", outcome)
                    .description(
                        "Structured exchange events actually emitted on the exchange logger; reconcile " +
                            "against the log index to detect log-pipeline loss",
                    ).register(registry)
            }
        }

    // The liveness check of the emission architecture itself: since the event is emitted at request
    // destruction, everything rests on the container firing requestDestroyed for every request the filter
    // saw. A destruction that never fires loses the event SILENTLY - nothing throws, so not even the
    // fail-open counter sees it. This gauge (up at filter entry, down at destruction) makes the
    // assumption measurable.
    private val openExchanges =
        AtomicLong(0).also { open ->
            registerOrFallback(OPEN_EXCHANGES_METER) { registry ->
                Gauge
                    .builder(OPEN_EXCHANGES_METER, open) { it.get().toDouble() }
                    .description(
                        "Exchanges between filter entry and request destruction; a growing baseline means " +
                            "requestDestroyed is not firing and exchange events are silently lost",
                    ).register(registry)
            }
        }

    // Watches the identity contract with the upstream: a rising `generated` share means callers (the
    // gateway, a sidecar) stopped propagating traceparent or the correlation header - a regression
    // neither logs nor other metrics surface reliably.
    private val requestIdSourceCounters =
        listOf(REQUEST_ID_SOURCE_TRACE, REQUEST_ID_SOURCE_HEADER, REQUEST_ID_SOURCE_GENERATED).associateWith { source ->
            registerOrFallback(CORRELATION_METER) { registry ->
                Counter
                    .builder(CORRELATION_METER)
                    .tag("source", source)
                    .description(
                        "Origin of the exchange's request id: the traceparent trace id, " +
                            "the correlation header, or generated (ADR-0002)",
                    ).register(registry)
            }
        }

    fun emissionFailure() = failOpenCounters.getValue(STAGE_EMISSION).increment()

    fun arrivalFailure() = failOpenCounters.getValue(STAGE_ARRIVAL).increment()

    fun wiringFailure() = failOpenCounters.getValue(STAGE_WIRING).increment()

    /**
     * Counts one EMITTED exchange event; [outcome] must be one of the [OUTCOME_SUCCESS] family. Guarded:
     * the event is already on the logger when this runs, so a failing host counter must neither be
     * reported as a lost emission nor disturb the caller.
     */
    fun eventEmitted(outcome: String) = updateQuietly(EVENTS_METER) { eventCounters.getValue(outcome).increment() }

    fun exchangeOpened() {
        openExchanges.incrementAndGet()
    }

    fun exchangeCompleted() {
        openExchanges.decrementAndGet()
    }

    /**
     * Counts the request-id origin; [source] must be one of the [REQUEST_ID_SOURCE_TRACE] family.
     * Guarded like [eventEmitted]: a throwing host counter must not degrade the exchange to an unlogged
     * pass-through.
     */
    fun requestId(source: String) =
        updateQuietly(CORRELATION_METER) {
            requestIdSourceCounters.getValue(source).increment()
        }

    /**
     * Isolates an OPERATIONAL counter update from the exchange it observes: registration succeeded, but
     * a host `Counter` may still throw on increment. The failure is counted `stage=wiring` (bookkeeping
     * lost, event unaffected) and warned; the fail-open counter itself is reported through [reportQuietly],
     * so a registry broken as a whole is silently dropped rather than escaping.
     */
    private inline fun updateQuietly(
        meterName: String,
        update: () -> Unit,
    ) {
        try {
            update()
        } catch (e: Exception) {
            reportQuietly {
                wiringFailure()
                internalLog.warn("Meter {} could not be updated - the exchange is logged without it: {}", meterName, e.toString())
            }
        }
    }

    fun requestBodySize(
        template: String?,
        bytes: Long,
    ) = recordBodySize(REQUEST_BODY_SIZE_METER, template, bytes)

    /**
     * Counts one exchange under how far the application consumed the request body, tagged by the
     * low-cardinality handler pattern - see [REQUEST_BODY_READ_METER]. Created per `uri`/`state` on
     * first use, like the body-size summaries (Micrometer deduplicates by id); recorded whenever a
     * request capture exists in measuring mode, INCLUDING bodyless requests the application never
     * touched - that is exactly the `unread` share the counter exists to show.
     */
    fun requestBodyRead(
        template: String?,
        state: BodyReadState,
    ) = registerOrFallback(REQUEST_BODY_READ_METER) { registry ->
        Counter
            .builder(REQUEST_BODY_READ_METER)
            .description("Exchanges by how far the application consumed the request body: unread, partial, or complete")
            .tag("uri", template ?: UNTEMPLATED_URI)
            .tag("state", state.tagValue)
            .register(registry)
    }.increment()

    fun responseBodySize(
        template: String?,
        bytes: Long,
    ) = recordBodySize(RESPONSE_BODY_SIZE_METER, template, bytes)

    /**
     * Bytes that ACTUALLY flowed, tagged by the low-cardinality handler pattern. A zero-byte body records
     * no sample - the distribution describes bodies that exist, and the sum stays exact either way. The
     * summaries are created per `uri` tag on first use; Micrometer's registry deduplicates by id.
     */
    private fun recordBodySize(
        meterName: String,
        template: String?,
        bytes: Long,
    ) {
        if (bytes == 0L) {
            return
        }
        registerOrFallback(meterName) { registry ->
            DistributionSummary
                .builder(meterName)
                .baseUnit("bytes")
                .description("Bytes of the body that actually flowed through the exchange")
                .tag("uri", template ?: UNTEMPLATED_URI)
                .register(registry)
        }.record(bytes.toDouble())
    }

    companion object {
        private val internalLog = LoggerFactory.getLogger(EndpointLoggingMetrics::class.java)

        // Both sides weak: the KEY must not pin a host registry that outlives its context, and the
        // VALUE is exactly what every filter already holds strongly - the owner lives as long as a
        // filter using it does. Residual (accepted): when every filter of a still-live registry has
        // been collected and a NEW one is wired against it afterwards, the fresh owner meets its own
        // pre-registered meter ids again and the ignored-gauge case resurfaces - a churn pattern
        // neither the auto-configuration nor per-test registries produce.
        private val perRegistry = WeakHashMap<MeterRegistry, WeakReference<EndpointLoggingMetrics>>()

        /**
         * The metrics owner for [registry] - created on first use, SHARED by every later caller with
         * the same registry. Sharing is what keeps the open-exchanges gauge truthful when several
         * filters run against one registry: a duplicate owner's gauge registration would be silently
         * ignored (see the class documentation), a shared owner makes the gauge the total across its
         * filters while the counters merge as before.
         */
        fun forRegistry(registry: MeterRegistry): EndpointLoggingMetrics =
            synchronized(perRegistry) {
                perRegistry[registry]?.get()
                    ?: EndpointLoggingMetrics(registry).also { perRegistry[registry] = WeakReference(it) }
            }

        /**
         * Meter counting logging failures the fail-open path swallowed, tagged `stage=emission` (the
         * exchange event was LOST), `stage=arrival` (the optional start line was lost) or `stage=wiring`
         * (wiring or bookkeeping around the chain failed; a pre-chain wiring failure degrades to an
         * unlogged pass-through, a post-chain one usually still emits the event). Requests are never
         * affected by what this counts - that is the fail-open contract; the counter makes its price
         * visible on a channel independent of the possibly-broken log pipeline.
         */
        const val FAIL_OPEN_METER = "endpoint.logging.failopen"

        /**
         * Meter counting the exchange events actually EMITTED (after the level gate; arrival lines are
         * not counted), tagged `outcome`. Its sum is the ground truth for reconciling metric-side event
         * counts against the log index: any difference is loss in the log pipeline itself.
         */
        const val EVENTS_METER = "endpoint.logging.events"

        /** Distribution of request body bytes that actually flowed, tagged `uri` (handler pattern). */
        const val REQUEST_BODY_SIZE_METER = "endpoint.request.body.size"

        /** Distribution of response body bytes that actually flowed, tagged `uri` (handler pattern). */
        const val RESPONSE_BODY_SIZE_METER = "endpoint.response.body.size"

        /**
         * Counter of exchanges by request-body consumption, tagged `uri` (handler pattern) and `state`
         * (`unread` | `partial` | `complete`, see [BodyReadState]). The body tee mirrors CONSUMPTION,
         * not transmission: the logged body and the size sample describe the bytes the application read,
         * so neither can tell a body the client sent but the application ignored from one that was never
         * sent. This counter is the one place that distinction is visible - an endpoint with a rising
         * `unread` or `partial` share is dropping payload it was handed. Opt-in with
         * `measure-request-body-size`, like the size summary.
         */
        const val REQUEST_BODY_READ_METER = "endpoint.request.body.read"

        /** The `uri` tag value for exchanges Spring MVC recorded no handler pattern for. */
        const val UNTEMPLATED_URI = "UNKNOWN"

        /**
         * Gauge of exchanges between filter entry and request destruction. Hovers near the active-request
         * count in health; a monotonically growing baseline means `requestDestroyed` is not firing and
         * exchange events are being lost SILENTLY - the one failure mode neither the fail-open counter
         * (nothing throws) nor the events counter (no baseline) can see.
         */
        const val OPEN_EXCHANGES_METER = "endpoint.logging.exchanges.open"

        /**
         * Counter of request-id origins, tagged `source=trace|header|generated` (ADR-0002). A rising
         * `generated` share means the upstream stopped propagating `traceparent` or the correlation
         * header. The meter name predates ADR-0002 and stays stable for existing dashboards.
         */
        const val CORRELATION_METER = "endpoint.logging.correlation.id"

        /** The closed outcome vocabulary - shared with the emitter, so counter keys and log field agree. */
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_TIMEOUT = "timeout"

        private const val STAGE_EMISSION = "emission"
        private const val STAGE_ARRIVAL = "arrival"
        private const val STAGE_WIRING = "wiring"

        /** The closed request-id source vocabulary of [CORRELATION_METER] - shared with the filter. */
        const val REQUEST_ID_SOURCE_TRACE = "trace"
        const val REQUEST_ID_SOURCE_HEADER = "header"
        const val REQUEST_ID_SOURCE_GENERATED = "generated"
    }
}
