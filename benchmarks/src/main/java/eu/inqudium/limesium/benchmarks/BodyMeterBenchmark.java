package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.common.BodyReadState;
import eu.inqudium.limesium.common.EndpointLoggingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The cost of ONE read-state sample on the metrics owner - the opt-in path every measured exchange
 * takes once for the read state and twice more, in the same shape, for the two body-size summaries.
 * First measured for finding #3 of PERF_ANALYSIS-2026-08-29T22-31-30 (BENCH_REPORT-2026-08-29T23-25-53:
 * refuted against fixed gates of 300 ns / 300 B, flat over 10 to 5 000 registry meters - the
 * {@code registrySize} axis of that run is settled and no longer swept). Re-measured for
 * BENCH_REPORT-2026-09-20T10-23-42 after legatium built the cache the first report declined
 * (legatium's BENCH_REPORT-2026-09-19T19-11-09) and then paid a lock-order defect for it
 * (its DEFECT_ANALYSIS-2026-09-19T20-24-59, finding 1). Three cases, rotating over {@code tagSets}
 * distinct URI templates so a single hot cache entry is not what gets measured:
 *
 * <ul>
 *   <li>{@link #registerPerCall}: the owner's {@code requestBodyRead} - the production path, which
 *       builds builder, two tags and a {@code Meter.Id} per call and lets Micrometer's deduplicating
 *       lookup find the existing counter;</li>
 *   <li>{@link #cached}: the same counter behind a benchmark-local cache in the shape legatium
 *       ended up with - a key record of the folded tag values, a plain {@code get} on the hit path,
 *       the miss resolved OUTSIDE the map and published with {@code putIfAbsent} (never
 *       {@code computeIfAbsent}: its mapping function runs under the map's bin lock, and Micrometer
 *       notifies removal listeners under its registry lock - the inversion of legatium's finding 1),
 *       plus the removal listener that keeps cache and registry in step;</li>
 *   <li>{@link #incrementOnly}: {@code Counter.increment} on a pre-resolved counter - the floor
 *       nothing above it can go below.</li>
 * </ul>
 *
 * <p>All three have a real side effect (the increment lands in the registry), so no Blackhole is
 * needed. {@code -prof gc} is the metric that matters: the per-call allocations the cache removes are
 * short-lived and the JIT hides much of their time. The cache is warmed for every tag set in setup,
 * so the measurement is the steady state a long-running host sees.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class BodyMeterBenchmark {

    private static final BodyReadState STATE = BodyReadState.COMPLETE;

    /** Distinct tag sets the calls rotate over; powers of two, so the rotation is a mask. */
    @Param({"1", "16"})
    public int tagSets;

    private SimpleMeterRegistry registry;
    private EndpointLoggingMetrics metrics;
    private String[] templates;
    private Counter[] resolved;
    private int next;

    /** The folded tag values of one read-state counter - two references, 24 B with compressed oops. */
    private record BodyMeterKey(String uriTemplate, String state) {}

    private final ConcurrentHashMap<BodyMeterKey, Counter> readStateCounters = new ConcurrentHashMap<>();

    @Setup
    public void setup() {
        registry = new SimpleMeterRegistry();
        metrics = EndpointLoggingMetrics.Companion.forRegistry(registry, EndpointLoggingMetrics.OUTCOME_CANCELLED);
        // The listener is part of the cache's shape (a host removing a meter must not leave the owner
        // recording into the detached instance); it costs nothing on the hit path but belongs to the
        // candidate, so it is installed as it would be in production.
        registry.config().onMeterRemoved(removed -> readStateCounters.values().removeIf(it -> it == removed));
        templates = new String[tagSets];
        resolved = new Counter[tagSets];
        for (int i = 0; i < tagSets; i++) {
            templates[i] = "/api/things/" + i + "/{id}";
            resolved[i] = counterFor(templates[i]);
            metrics.requestBodyRead(templates[i], STATE);
            cachedCounter(templates[i]);
        }
    }

    private int slot() {
        int i = next;
        next = (i + 1) & (tagSets - 1);
        return i;
    }

    /** The owner's builder chain, verbatim - the same {@code Meter.Id}, so every variant hits the identical counter. */
    private Counter counterFor(String template) {
        return Counter.builder(EndpointLoggingMetrics.REQUEST_BODY_READ_METER)
                .description("Exchanges by how far the application consumed the request body: unread, partial, or complete")
                .tag("uri", template == null ? EndpointLoggingMetrics.UNTEMPLATED_URI : template)
                .tag("state", STATE.getTagValue())
                .register(registry);
    }

    /** Variant B's resolution: the key is the hit path's only allocation; a miss registers outside the map. */
    private Counter cachedCounter(String template) {
        BodyMeterKey key = new BodyMeterKey(template == null ? EndpointLoggingMetrics.UNTEMPLATED_URI : template, STATE.getTagValue());
        Counter hit = readStateCounters.get(key);
        if (hit != null) {
            return hit;
        }
        Counter fresh = counterFor(key.uriTemplate());
        Counter raced = readStateCounters.putIfAbsent(key, fresh);
        return raced != null ? raced : fresh;
    }

    @Benchmark
    public void registerPerCall() {
        metrics.requestBodyRead(templates[slot()], STATE);
    }

    @Benchmark
    public void cached() {
        cachedCounter(templates[slot()]).increment();
    }

    @Benchmark
    public void incrementOnly() {
        resolved[slot()].increment();
    }
}
