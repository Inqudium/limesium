package eu.inqudium.limesium.benchmarks;

import java.util.Random;
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
import org.springframework.http.server.PathContainer;

/**
 * Finding #1 of the servlet module's PERF_ANALYSIS-2026-08-29T22-49-04 (plan M2a):
 * {@code RequestLoggingFilter.shouldNotFilter} runs {@code PathContainer.parsePath(requestURI)}
 * on EVERY dispatch, before the early returns that make it unnecessary - with the shipped
 * defaults (no include patterns, no exclude prefixes) the parsed container is discarded unused.
 *
 * <p>{@code shouldNotFilter} itself is protected on a final class, so the unit under measurement
 * is the mechanism the finding names: the unconditional parse. Baseline: the parse as the filter
 * performs it. Candidate: the sketched short-circuit - with empty configuration the method
 * returns before parsing, so the candidate is the constant-false path (measured to show the
 * floor, expected ~1 ns).
 *
 * <p>URIs rotate through a pool per invocation so the JIT cannot specialize on one string.
 *
 * <p>Decision rule (fixed before the run): confirmed if the parse costs >= 150 ns/op or
 * >= 300 B/op at a typical segment count; retired otherwise.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class PathActivationBenchmark {

    private static final int POOL = 64;

    /** Path shapes: short REST path, typical nested resource, deep path (the 10x scaling point). */
    @Param({"3", "7", "12"})
    public int segments;

    private String[] uris;
    private int next;

    @Setup
    public void setup() {
        Random random = new Random(42);
        uris = new String[POOL];
        for (int i = 0; i < POOL; i++) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < segments; s++) {
                sb.append('/').append("seg").append(s).append('-').append(random.nextInt(1000));
            }
            uris[i] = sb.toString();
        }
    }

    private String uri() {
        return uris[next++ & (POOL - 1)];
    }

    @Benchmark
    public PathContainer baselineParsePath() {
        return PathContainer.parsePath(uri());
    }

    @Benchmark
    public boolean candidateShortCircuit() {
        // The sketched fix: with includePathPatterns and excludePathPrefixes both empty the
        // method answers without parsing. The uri() call stays so both methods share the
        // pool-rotation overhead.
        return uri() == null;
    }
}
