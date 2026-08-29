package eu.inqudium.limesium.benchmarks;

import eu.inqudium.limesium.servlet.logging.BoundedBodyCapture;
import eu.inqudium.limesium.servlet.logging.CapturingResponseWrapper;
import java.io.PrintWriter;
import java.io.Writer;
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
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Finding #5 of the servlet module's PERF_ANALYSIS-2026-08-29T22-49-04 (plan M4 as a
 * microbenchmark): every character written through {@code CapturingResponseWrapper.getWriter}
 * is encoded a SECOND time by the capture encoder - past the capture cap and in count-only
 * (measure-only) mode, i.e. linear in body size, not in the cap.
 *
 * <p>The REAL wrapper is measured, over a delegate whose own writer is a null sink (a
 * {@link MockHttpServletResponse} subclass), so the delta between baseline and wrapped paths is
 * the capture pipeline and nothing else. One operation = writing ONE response body of
 * {@code bodyKb} in 8 KiB char chunks - the loop is the operation (one body), not amplification
 * of a smaller op. Chunks are ASCII (the encoder's fast path), so the measured overhead is a
 * LOWER bound for multi-byte content.
 *
 * <p>Decision rule (fixed before the run): confirmed if the wrapped path adds >= 20 us at the
 * 100 KB body (about 2% of an assumed 1 ms template render - the input plan's threshold moved
 * to this instrument) or scales super-linearly; retired if under 20 us at 100 KB.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class WriterCaptureBenchmark {

    private static final int CHUNK = 8192;

    /** Response body size; 10x steps make the linear-in-size claim visible. */
    @Param({"10", "100", "1000"})
    public int bodyKb;

    private char[] chunk;
    private int chunks;
    private PrintWriter plainWriter;
    private PrintWriter countOnlyWriter;
    private PrintWriter cappedWriter;

    /** Delegate whose writer swallows everything - the wrapper's tee cost is then the delta. */
    private static final class NullSinkResponse extends MockHttpServletResponse {
        private final PrintWriter sink = new PrintWriter(Writer.nullWriter());

        @Override
        public PrintWriter getWriter() {
            return sink;
        }
    }

    @Setup
    public void setup() {
        chunk = new char[CHUNK];
        for (int i = 0; i < CHUNK; i++) {
            chunk[i] = (char) ('a' + (i % 26));
        }
        chunks = Math.max(1, bodyKb * 1024 / CHUNK);

        NullSinkResponse plain = new NullSinkResponse();
        plain.setCharacterEncoding("UTF-8");
        plainWriter = plain.getWriter();

        NullSinkResponse countOnlyDelegate = new NullSinkResponse();
        countOnlyDelegate.setCharacterEncoding("UTF-8");
        // COUNT-ONLY mode: measure-response-body-size without log-response-body (maxBytes = 0).
        countOnlyWriter = new CapturingResponseWrapper(countOnlyDelegate, new BoundedBodyCapture(0)).getWriter();

        NullSinkResponse cappedDelegate = new NullSinkResponse();
        cappedDelegate.setCharacterEncoding("UTF-8");
        // The default cap: the buffer fills once, every later chunk still runs through the encoder.
        cappedWriter = new CapturingResponseWrapper(cappedDelegate, new BoundedBodyCapture(16384)).getWriter();
    }

    @Benchmark
    public void baselinePlainWriter() {
        for (int i = 0; i < chunks; i++) {
            plainWriter.write(chunk, 0, CHUNK);
        }
    }

    @Benchmark
    public void wrappedCountOnly() {
        for (int i = 0; i < chunks; i++) {
            countOnlyWriter.write(chunk, 0, CHUNK);
        }
    }

    @Benchmark
    public void wrappedCapped16k() {
        for (int i = 0; i < chunks; i++) {
            cappedWriter.write(chunk, 0, CHUNK);
        }
    }
}
