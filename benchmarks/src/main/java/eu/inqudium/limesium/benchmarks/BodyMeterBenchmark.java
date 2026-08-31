package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.common.BodyReadState;
import eu.inqudium.limesium.reactive.logging.EndpointLoggingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Finding #3 of PERF_ANALYSIS-2026-08-29T22-31-30 (plan M1): the body meters are re-resolved
 * through {@code Counter.builder(...).tag(...).register(registry)} on EVERY emission -
 * builder, {@code Meter.Id} and {@code Tags} construction plus the registry lookup - although the
 * meter never changes after first use.
 *
 * <p>Baseline: the production path, {@code EndpointLoggingMetrics.requestBodyRead} against the
 * REAL (internal, bytecode-public) class in steady state - the meter already exists, so the
 * measured cost is exactly the per-emission re-resolution the finding claims. Candidate: the
 * increment through a counter reference resolved once. The registry is pre-populated with
 * {@code registrySize} unrelated meters, because the lookup cost depends on registry size.
 *
 * <p>Both operations have a real side effect (the increment), so no Blackhole is needed.
 *
 * <p>Decision rule (fixed before the run): confirmed if the baseline is >= 300 ns/op or
 * >= 300 B/op above the cached-reference candidate; retired otherwise.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class BodyMeterBenchmark {

    private static final String TEMPLATE = "/api/things/{id}";

    /** Total meters in the host registry - the lookup's haystack. */
    @Param({"10", "500", "5000"})
    public int registrySize;

    private EndpointLoggingMetrics metrics;
    private Counter cachedCounter;

    @Setup
    public void setup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (int i = 0; i < registrySize; i++) {
            registry.counter("host.dummy.meter", "idx", Integer.toString(i));
        }
        metrics = EndpointLoggingMetrics.Companion.forRegistry(registry);
        // Warm the meter the baseline re-resolves, then resolve the candidate's reference ONCE -
        // the same Meter.Id, so both increment the identical counter.
        metrics.requestBodyRead(TEMPLATE, BodyReadState.COMPLETE);
        cachedCounter = Counter.builder(EndpointLoggingMetrics.REQUEST_BODY_READ_METER)
                .description("Exchanges by how far the application consumed the request body: unread, partial, or complete")
                .tag("uri", TEMPLATE)
                .tag("state", BodyReadState.COMPLETE.getTagValue())
                .register(registry);
    }

    @Benchmark
    public void baseline() {
        metrics.requestBodyRead(TEMPLATE, BodyReadState.COMPLETE);
    }

    @Benchmark
    public void candidateCachedCounter() {
        cachedCounter.increment();
    }
}
