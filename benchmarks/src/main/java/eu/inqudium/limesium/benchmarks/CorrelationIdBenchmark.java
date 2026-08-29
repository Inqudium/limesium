package eu.inqudium.limesium.benchmarks;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Finding #4 of PERF_ANALYSIS-2026-08-29T22-31-30: {@code UUID.randomUUID()} draws from the
 * process-global {@code SecureRandom} for every request without a correlation header - a
 * potential serialization point across event-loop threads.
 *
 * <p>CONTENTION PROBE, not the finding's prescribed verification: the input report's plan M4
 * (JFR lock profiling on a loaded Netty instance) remains the instrument that can settle
 * event-loop stall behaviour. This benchmark establishes (a) the uncontended per-call cost class
 * and (b) how throughput scales when 16 threads draw concurrently - run once with {@code -t 1}
 * and once with {@code -t 16}; JMH prints per-thread and aggregate throughput.
 *
 * <p>Baseline: {@code UUID.randomUUID().toString()} (the module's shipped generator). Candidate:
 * a {@link ThreadLocalRandom}-based v4-format id - the injectable-generator escape hatch the
 * report sketches for contended hosts (NOT cryptographically random; that trade-off is the host's
 * documented decision, and correlation ids carry no security function).
 *
 * <p>Decision rule (fixed before the run): contention confirmed if per-thread throughput at 16
 * threads drops by more than 5x versus 1 thread for the baseline while the candidate scales;
 * the uncontended cost verifies or refutes the finding's cost class either way.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
public class CorrelationIdBenchmark {

    @Benchmark
    public String baselineRandomUuid() {
        return UUID.randomUUID().toString();
    }

    @Benchmark
    public String candidateThreadLocalRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (random.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }
}
