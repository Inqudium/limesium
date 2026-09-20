package eu.inqudium.limesium.common

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.noop.NoopMeter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The module's meters (the `*_METER` constants below), all fed from the host's registry - ONE class for
 * both endpoint-logging twins (ADR-0003 amendment of 2026-09-05), parameterized by the one thing that
 * differs per stack: the fourth value of the outcome vocabulary ([OUTCOME_TIMEOUT] on the servlet twin,
 * [OUTCOME_CANCELLED] on the reactive twin). Every meter here observes what neither
 * `http.server.requests` nor the log fields can show; rates, latencies and status distributions are
 * deliberately left to those.
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
 * working and the affected meter is simply not exported.
 *
 * The DYNAMIC body meters (the two size summaries and the read-state counter, tagged per handler
 * pattern) are resolved ONCE per tag set and kept in a cache that mirrors the registry's entries under
 * those three names - one entry per meter the registry HOLDS, so the cache adds no cardinality and needs
 * no size policy of its own; the framework's folding of the path to the handler pattern (`UNKNOWN`
 * without one) bounds both alike. A meter the registry did NOT keep - a denying `MeterFilter` (Boot's
 * `management.metrics.enable.*`, a tag cap) or a closed registry answers with a detached no-op instance
 * - is used for its exchange but never cached: nothing would ever release it, and the cache would grow
 * per tag set exactly where the operator bounded the registry. Without the cache every measured exchange
 * rebuilt the builder, the tags and the `Meter.Id` three times only to hit Micrometer's deduplicating
 * lookup (measured in `benchmarks/`: `BodyMeterBenchmark`, `docs/assessment/BENCH_REPORT-2026-09-20T10-23-42.md`;
 * the shape is legatium's `ClientLoggingMetrics` cache with the `host` and `name` dimensions dropped).
 * The one way cache and registry could drift apart - a host removing one of the dynamic meters - is
 * closed by a removal listener that drops the entry, so the next exchange registers anew instead of
 * recording into a detached instance. The listener covers the dynamic meters ONLY: the fixed meters are
 * registered once at construction, and a host that removes one of them (a `clear()` on a test registry)
 * has decided against it - the owner keeps counting into the detached instance rather than
 * re-registering behind the host's back.
 *
 * LOCK ORDER: Micrometer notifies removal listeners while holding its registry-wide meter-map lock, and
 * registering a new id takes that same lock. The cache is therefore never written from inside a
 * `ConcurrentHashMap.computeIfAbsent` - its mapping function runs under the map's bin lock, and a
 * registration in there would wait for the registry lock while the listener, holding it, waits for the
 * bin lock to drop the removed entry (legatium's defect analysis of 2026-09-19, night, finding 1). A
 * miss resolves the meter OUTSIDE the map and publishes it with `putIfAbsent` ([cacheBodyMeter]); a
 * lost race registers the same id twice, which Micrometer deduplicates to one instance anyway.
 *
 * ACCEPTED RESIDUE of that order: between the registration returning and the `putIfAbsent` lies a window
 * of microseconds in which a host removal of that very meter runs the listener against a cache that
 * holds no entry yet; the detached instance is then published and takes every later sample of its tag
 * set, unseen by any exporter, until the owner is recreated. It needs a host that removes meters at
 * runtime (a `clear()`, a pruner) AND the removal inside that window, and it costs the samples of one
 * tag set - never an event, never a request. Closing it would take a registry-wide lookup after every
 * first-time registration; the trade against the deadlock the order removed is deliberate, and the
 * residue is documented rather than paid for.
 */
internal class EndpointLoggingMetrics private constructor(
    private val meterRegistry: MeterRegistry,
    /** The stack's own fourth outcome ([OUTCOME_TIMEOUT] or [OUTCOME_CANCELLED]), pre-registered beside success, rejected and failure. */
    stackOutcome: String,
) {
    private val fallbackRegistry = SimpleMeterRegistry()
    private val reportedConflicts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val reportedUpdateFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The tag set of one dynamic body meter, already folded the way the tags are (the `UNKNOWN` fallback
     * of the handler pattern), so every raw input that yields the same meter id shares one entry.
     */
    private data class BodyMeterKey(
        val meterName: String,
        val uriTemplate: String,
        val state: String? = null,
    )

    /** [REQUEST_BODY_SIZE_METER] and [RESPONSE_BODY_SIZE_METER], resolved once per tag set (class KDoc). */
    private val bodySizeSummaries = ConcurrentHashMap<BodyMeterKey, DistributionSummary>()

    /** [REQUEST_BODY_READ_METER], resolved once per tag set and state (class KDoc). */
    private val readStateCounters = ConcurrentHashMap<BodyMeterKey, Counter>()

    init {
        // A host that removes one of the cached meters (`MeterRegistry.remove`) gets it registered anew
        // on the next exchange; without this the owner would keep recording into the detached instance.
        // Runs UNDER the registry's meter-map lock and takes the maps' bin locks - the lock order the
        // class KDoc fixes; the registry also holds this lambda, and with it the owner, as long as it lives.
        meterRegistry.config().onMeterRemoved { removed ->
            bodySizeSummaries.values.removeIf { it === removed }
            readStateCounters.values.removeIf { it === removed }
        }
    }

    /**
     * The miss path of the body-meter caches: resolves the meter through [resolve] OUTSIDE [cache] and
     * publishes it with `putIfAbsent`, never `computeIfAbsent` (the lock order of the class KDoc); a
     * racing resolver's instance is the same registry-deduplicated meter, so either one serves. A meter
     * the host registry did not keep ([NoopMeter]: a denying filter or a closed registry) is returned
     * for this exchange but NOT cached - the removal listener could never release it, and one entry per
     * denied tag set would grow the cache exactly where the operator bounded the registry. The window
     * between [resolve] returning and the `putIfAbsent` is the accepted residue of the class KDoc.
     */
    private fun <M : Meter> cacheBodyMeter(
        cache: ConcurrentHashMap<BodyMeterKey, M>,
        key: BodyMeterKey,
        resolve: () -> M,
    ): M {
        val meter = resolve()
        if (meter is NoopMeter) {
            return meter
        }
        return cache.putIfAbsent(key, meter) ?: meter
    }

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
        listOf(OUTCOME_SUCCESS, OUTCOME_REJECTED, OUTCOME_FAILURE, stackOutcome).associateWith { outcome ->
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

    // The liveness check of the emission architecture itself - see [OPEN_EXCHANGES_METER]: everything
    // rests on the completion signal reaching the filter (request destruction on the servlet stack, the
    // terminal signal or the commit on the reactive one). A completion that never arrives loses the
    // event SILENTLY - nothing throws, so not even the fail-open counter sees it. This gauge (up at
    // filter entry, down at the exactly-once completion) makes the assumption measurable.
    private val openExchanges =
        AtomicLong(0).also { open ->
            registerOrFallback(OPEN_EXCHANGES_METER) { registry ->
                Gauge
                    .builder(OPEN_EXCHANGES_METER, open) { it.get().toDouble() }
                    .description(
                        "Exchanges between filter entry and the exactly-once completion; a growing baseline means " +
                            "completions are not reaching the filter and exchange events are silently lost",
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
                            "the correlation header, or generated",
                    ).register(registry)
            }
        }

    fun emissionFailure() = failOpenCounters.getValue(STAGE_EMISSION).increment()

    fun arrivalFailure() = failOpenCounters.getValue(STAGE_ARRIVAL).increment()

    fun wiringFailure() = failOpenCounters.getValue(STAGE_WIRING).increment()

    /**
     * Counts one EMITTED exchange event; [outcome] must be one of the [OUTCOME_SUCCESS] family of THIS
     * stack. Guarded: the event is already on the logger when this runs, so a failing host counter must
     * neither be reported as a lost emission nor disturb the caller.
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
     * Isolates an OPERATIONAL meter update from the exchange it observes: registration succeeded, but a
     * host `Counter` or `DistributionSummary` may still throw on update. The failure is counted
     * `stage=wiring` on EVERY call (bookkeeping lost, event unaffected - the count is the measure of the
     * loss) but warned ONCE per meter name, like a registration conflict: a permanently broken host
     * meter is hit up to five times per measured exchange (the two fixed counters and the three body
     * meters), and a warning per hit would drown the module's curated one-time warnings under load
     * (legatium's defect analyses of 2026-09-16, finding 5, and 2026-09-19). The fail-open counter itself
     * is reported through [reportQuietly], so a registry broken as a whole is silently dropped rather
     * than escaping.
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
                if (reportedUpdateFailures.add(meterName)) {
                    internalLog.warn(
                        "Meter {} could not be updated - the exchange is logged without it; further failures of this meter are counted, not logged: {}",
                        meterName,
                        e.toString(),
                    )
                }
            }
        }
    }

    fun requestBodySize(
        template: String?,
        bytes: Long,
    ) = recordBodySize(REQUEST_BODY_SIZE_METER, template, bytes)

    /**
     * Counts one exchange under how far the application consumed the request body, tagged by the
     * low-cardinality handler pattern - see [REQUEST_BODY_READ_METER]. Resolved per `uri`/`state` on
     * first use and cached, like the body-size summaries (class KDoc); recorded whenever a request
     * capture exists in measuring mode, INCLUDING bodyless requests the application never touched -
     * that is exactly the `unread` share the counter exists to show. Guarded like the fixed counters
     * ([updateQuietly]): a host counter that throws on increment is counted per hit and warned once.
     */
    fun requestBodyRead(
        template: String?,
        state: BodyReadState,
    ) = updateQuietly(REQUEST_BODY_READ_METER) {
        val key = BodyMeterKey(REQUEST_BODY_READ_METER, template ?: UNTEMPLATED_URI, state.tagValue)
        // The plain get first: on the hit path - every exchange but the first per tag set - the key is
        // then the only allocation; the resolver's lambdas are built on a miss alone.
        val counter =
            readStateCounters[key] ?: cacheBodyMeter(readStateCounters, key) {
                registerOrFallback(REQUEST_BODY_READ_METER) { registry ->
                    Counter
                        .builder(REQUEST_BODY_READ_METER)
                        .description("Exchanges by how far the application consumed the request body: unread, partial, or complete")
                        .tag("uri", key.uriTemplate)
                        .tag("state", state.tagValue)
                        .register(registry)
                }
            }
        counter.increment()
    }

    fun responseBodySize(
        template: String?,
        bytes: Long,
    ) = recordBodySize(RESPONSE_BODY_SIZE_METER, template, bytes)

    /**
     * Bytes that ACTUALLY flowed, tagged by the low-cardinality handler pattern. A zero-byte body records
     * no sample - the distribution describes bodies that exist, and the sum stays exact either way. The
     * summaries are resolved per `uri` tag on first use and cached (class KDoc). Guarded like the fixed
     * counters ([updateQuietly]): a host summary that throws on record is counted per hit and warned once.
     */
    private fun recordBodySize(
        meterName: String,
        template: String?,
        bytes: Long,
    ) {
        if (bytes == 0L) {
            return
        }
        updateQuietly(meterName) {
            val key = BodyMeterKey(meterName, template ?: UNTEMPLATED_URI)
            // The plain get first, as in requestBodyRead: the key is the hit path's only allocation.
            val summary =
                bodySizeSummaries[key] ?: cacheBodyMeter(bodySizeSummaries, key) {
                    registerOrFallback(meterName) { registry ->
                        DistributionSummary
                            .builder(meterName)
                            .baseUnit("bytes")
                            .description("Bytes of the body that actually flowed through the exchange")
                            .tag("uri", key.uriTemplate)
                            .register(registry)
                    }
                }
            summary.record(bytes.toDouble())
        }
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
         * filters while the counters merge as before. One STACK per registry: the owner keeps the
         * [stackOutcome] of its first caller (the auto-configurations activate exactly one twin per
         * application, so the case never arises there; a hand-wired mix would count the other twin's
         * fourth outcome as a lost bookkeeping update, never as a lost event).
         */
        fun forRegistry(
            registry: MeterRegistry,
            stackOutcome: String,
        ): EndpointLoggingMetrics =
            synchronized(perRegistry) {
                perRegistry[registry]?.get()
                    ?: EndpointLoggingMetrics(registry, stackOutcome).also { perRegistry[registry] = WeakReference(it) }
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
         * `unread` or `partial` share is dropping payload it was handed. Bodies the framework parses
         * itself (form and multipart requests read through the parameter/form-data API) never pass the
         * tee and always count as `unread` - read the share per `uri` with that in mind. Opt-in with
         * `measure-request-body-size`, like the size summary.
         */
        const val REQUEST_BODY_READ_METER = "endpoint.request.body.read"

        /** The `uri` tag value for exchanges the framework recorded no handler pattern for. */
        const val UNTEMPLATED_URI = "UNKNOWN"

        /**
         * Gauge of exchanges between filter entry and the exactly-once completion (up at wiring, down at
         * request destruction resp. the terminal signal or commit). Hovers near the active-request count
         * in health; a monotonically growing baseline means completions never reach the filter and
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

        /**
         * The closed outcome vocabulary - shared with the emitters, so counter keys and log field agree.
         * The value names WHO is responsible for the disposition (ADR-0007): nobody for a success, the
         * caller for a rejected 4xx, the application for a failure, the clock or the caller's disconnect
         * for the stack's own fourth value.
         */
        const val OUTCOME_SUCCESS = "success"

        /** A 4xx: the application answered that the caller's request was refused ([StatusClassification]). */
        const val OUTCOME_REJECTED = "rejected"
        const val OUTCOME_FAILURE = "failure"

        /** The servlet twin's fourth outcome: the container's async timeout. */
        const val OUTCOME_TIMEOUT = "timeout"

        /** The reactive twin's fourth outcome: a client disconnect, the reactive reality. */
        const val OUTCOME_CANCELLED = "cancelled"

        private const val STAGE_EMISSION = "emission"
        private const val STAGE_ARRIVAL = "arrival"
        private const val STAGE_WIRING = "wiring"

        /** The closed request-id source vocabulary of [CORRELATION_METER] - shared with the filters. */
        const val REQUEST_ID_SOURCE_TRACE = "trace"
        const val REQUEST_ID_SOURCE_HEADER = "header"
        const val REQUEST_ID_SOURCE_GENERATED = "generated"
    }
}
