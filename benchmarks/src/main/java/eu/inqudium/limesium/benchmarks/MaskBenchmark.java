package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.reactive.logging.HeaderLogProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

/**
 * Finding #2 of PERF_ANALYSIS-2026-08-29T22-31-30 (plan M2): {@code HeaderLogProperties.mask}
 * performs a {@code MessageDigest.getInstance} lookup per value and renders the 8 digest bytes
 * through eight {@code String.format("%02x")} calls (plus byte-boxing via {@code take(8)}).
 *
 * <p>Baseline: the production {@code mask}. Candidates: (a) same per-call digest lookup, hex via
 * {@link HexFormat} - isolates the rendering cost; (b) additionally a thread-local digest -
 * isolates the {@code getInstance} lookup. All three produce byte-identical output (asserted in
 * setup) - the fingerprint format is a twin contract.
 *
 * <p>Inputs rotate through a pre-generated pool so the JIT cannot fold the digest input.
 *
 * <p>Decision rule (fixed before the run): confirmed if the baseline is >= 2x the candidate time
 * or >= 500 B/op above it; retired regardless of ratio if the baseline is < 500 ns/op absolute.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class MaskBenchmark {

    private static final HexFormat HEX = HexFormat.of();
    private static final int POOL = 128;

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** Header-value lengths: short token, typical bearer token, fat header. */
    @Param({"8", "64", "512"})
    public int length;

    private String[] values;
    private int next;

    @Setup
    public void setup() {
        Random random = new Random(42);
        values = new String[POOL];
        for (int i = 0; i < POOL; i++) {
            StringBuilder sb = new StringBuilder(length);
            for (int c = 0; c < length; c++) {
                sb.append((char) ('!' + random.nextInt(94)));
            }
            values[i] = sb.toString();
        }
        // Output-equality gate across all three implementations.
        for (int i = 0; i < POOL; i++) {
            String expected = HeaderLogProperties.Companion.mask(values[i]);
            if (!expected.equals(maskHexFormat(values[i])) || !expected.equals(maskCachedDigest(values[i]))) {
                throw new IllegalStateException("candidate output differs for " + values[i]);
            }
        }
    }

    private String value() {
        return values[next++ & (POOL - 1)];
    }

    @Benchmark
    public String baseline() {
        return HeaderLogProperties.Companion.mask(value());
    }

    @Benchmark
    public String candidateHexFormat() {
        return maskHexFormat(value());
    }

    @Benchmark
    public String candidateCachedDigest() {
        return maskCachedDigest(value());
    }

    private static String maskHexFormat(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return value.length() + ":" + HEX.formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String maskCachedDigest(String value) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return value.length() + ":" + HEX.formatHex(hash, 0, 8);
    }
}
