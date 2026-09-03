package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.common.HeaderLogProperties;
import eu.inqudium.limesium.common.HeaderValueMasker;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import kotlin.Pair;
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
 * Finding #1 of PERF_ANALYSIS-2026-08-29T22-31-30 (plan M3), CONFIRMED AND ADOPTED: at the time
 * of the recorded runs {@code HeaderLogProperties.select} rebuilt the lowercased exclude/mask sets
 * and intermediate collections on every call; production has since derived them once at
 * construction (the "Derived ONCE" block in {@code RequestLoggingProperties}), so the baseline
 * below now measures the ADOPTED implementation, not the finding.
 *
 * <p>Baseline: the production {@code select} as currently shipped (precomputation included).
 * Candidate: {@link PrecomputedHeaderSelect}, the original standalone sketch the adoption was
 * measured against (identical output). The pre-adoption evidence lives in
 * {@code results/header-select.*}; a re-run today is a REGRESSION GUARD - baseline and candidate
 * are expected to be on par, a baseline drifting back above the candidate reopens the finding.
 * Masking is left EMPTY in all configurations to keep finding #2's cost out of this measurement.
 *
 * <p>Decision rule of the historical confirmation run (fixed before that run): confirmed if the
 * baseline is >= 200 ns/op or >= 500 B/op above the candidate at a realistic configuration;
 * retired otherwise.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class HeaderSelectBenchmark {

    /** Realistic operating points from the assumed load profile, plus the wildcard include. */
    @Param({"EXPLICIT_2", "EXPLICIT_8_EXCL_4", "WILDCARD_EXCL_4"})
    public String config;

    // Case-insensitive lookup, like the header multimap of a real request.
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<String> availableNames;
    private HeaderLogProperties baselineProps;
    private PrecomputedHeaderSelect candidate;

    @Setup
    public void setup() {
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "curl/8.5.0");
        headers.put("X-Correlation-Id", "3c9adf3d-6a95-4f2b-9d43-b9351a4d8c11");
        headers.put("Authorization", "Bearer 5f1c-benchmark-token-value");
        headers.put("Accept-Encoding", "gzip");
        headers.put("X-Tenant", "tenant-42");
        headers.put("Host", "api.example.test");
        availableNames = List.copyOf(headers.keySet());

        List<String> includes;
        List<String> excludes;
        switch (config) {
            case "EXPLICIT_2" -> {
                includes = List.of("Content-Type", "X-Tenant");
                excludes = List.of();
            }
            case "EXPLICIT_8_EXCL_4" -> {
                includes = availableNames;
                excludes = List.of("Authorization", "Host", "Accept-Encoding", "User-Agent");
            }
            case "WILDCARD_EXCL_4" -> {
                includes = List.of(HeaderLogProperties.WILDCARD);
                excludes = List.of("Authorization", "Host", "Accept-Encoding", "User-Agent");
            }
            default -> throw new IllegalStateException(config);
        }
        baselineProps = new HeaderLogProperties(includes, excludes, List.of());
        candidate = new PrecomputedHeaderSelect(includes, excludes, List.of());

        // Output-equality gate: a candidate with different semantics measures nothing.
        List<Pair<String, String>> base = baseline();
        List<Pair<String, String>> cand = candidateSelect();
        if (!base.equals(cand)) {
            throw new IllegalStateException("candidate output differs: " + base + " vs " + cand);
        }
    }

    @Benchmark
    public List<Pair<String, String>> baseline() {
        return baselineProps.select(availableNames, HeaderValueMasker.Companion.getDEFAULT(), headers::get);
    }

    @Benchmark
    public List<Pair<String, String>> candidateSelect() {
        return candidate.select(availableNames, headers::get);
    }
}
