package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.common.HeaderLogProperties;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import kotlin.Pair;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Twin-inheritance spot check for findings #2 and #3 of the servlet module's
 * PERF_ANALYSIS-2026-08-29T22-49-04: the SERVLET copies of {@code HeaderLogProperties.select}
 * and {@code mask} at one operating point each. BENCH_REPORT-2026-08-29T23-25-53 measured the
 * REACTIVE copies in full (three configurations / lengths plus candidates); the twin classes are
 * source-identical by contract, so those results are inherited - this class only pins that the
 * servlet compilation behaves the same. Expected (from the reactive run): select at the 8-header
 * config ~307 ns / 1496 B/op, mask at length 64 ~505 ns / 3456 B/op; inheritance holds if the
 * servlet numbers land within +/-15 %.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class ServletTwinSpotCheckBenchmark {

    private static final int POOL = 128;
    private static final int MASK_LENGTH = 64;

    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private List<String> availableNames;
    private HeaderLogProperties selectProps;
    private String[] maskValues;
    private int next;

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
        // The reactive run's EXPLICIT_8_EXCL_4 configuration, verbatim.
        selectProps = new HeaderLogProperties(
                availableNames,
                List.of("Authorization", "Host", "Accept-Encoding", "User-Agent"),
                List.of());

        Random random = new Random(42);
        maskValues = new String[POOL];
        for (int i = 0; i < POOL; i++) {
            StringBuilder sb = new StringBuilder(MASK_LENGTH);
            for (int c = 0; c < MASK_LENGTH; c++) {
                sb.append((char) ('!' + random.nextInt(94)));
            }
            maskValues[i] = sb.toString();
        }
    }

    @Benchmark
    public List<Pair<String, String>> servletSelectExplicit8Excl4() {
        return selectProps.select(availableNames, headers::get);
    }

    @Benchmark
    public String servletMaskLength64() {
        return HeaderLogProperties.Companion.mask(maskValues[next++ & (POOL - 1)]);
    }
}
