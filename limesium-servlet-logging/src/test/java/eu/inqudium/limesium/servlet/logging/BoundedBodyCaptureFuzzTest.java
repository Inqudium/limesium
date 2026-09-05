package eu.inqudium.limesium.servlet.logging;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Fuzzes the bounded tee target both twins rest on (the servlet variant; the
 * reactive one is its field-identical copy): arbitrary interleavings of
 * single-byte and array captures, resets, and read-state marks, decoded with
 * different charsets.
 *
 * Invariants under test: no capture sequence may throw; the total byte count
 * is exact; loggedValue() is null exactly for a zero-byte body, never throws
 * for any byte content or charset, and announces truncation whenever more
 * bytes flowed than the capture limit holds.
 *
 * Runs as a regression test (checked-in inputs plus the empty input) in every
 * build; the scheduled Fuzz workflow explores for real (JAZZER_FUZZ=1).
 */
class BoundedBodyCaptureFuzzTest {
    private static final Charset[] CHARSETS = {
        StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16, StandardCharsets.US_ASCII,
    };

    @FuzzTest(maxDuration = "10m")
    void capture_upholds_its_contract(FuzzedDataProvider data) {
        // What is tested: BoundedBodyCapture under a fuzzed sequence of single-byte and ranged
        //   captures, start and completion marks and clears, against a fuzzed cap.
        // Success criteria: totalBytes equals the bytes fed since the last clear; the logged value
        //   is null exactly for zero bytes; a body beyond the cap carries the truncation note - for
        //   any charset and any input Jazzer generates.
        // Why it matters: the capture bounds memory on every request; a counting drift or a missing
        //   note would misreport payload sizes and truncation to the operator without another
        //   symptom.
        // Given: a capture with a fuzzed cap
        int maxBytes = data.consumeInt(0, 1 << 16);
        BoundedBodyCapture capture = new BoundedBodyCapture(maxBytes);
        long expectedTotal = 0;

        // When: a fuzzed sequence of captures, marks and clears runs against it
        int ops = data.consumeInt(0, 64);
        for (int i = 0; i < ops && data.remainingBytes() > 0; i++) {
            switch (data.consumeInt(0, 4)) {
                case 0 -> {
                    capture.capture(data.consumeByte());
                    expectedTotal += 1;
                }
                case 1 -> {
                    byte[] bytes = data.consumeBytes(data.consumeInt(0, 4096));
                    // The wrapper contract guarantees a valid range; fuzz within it.
                    int offset = bytes.length == 0 ? 0 : data.consumeInt(0, bytes.length - 1);
                    int length = data.consumeInt(0, bytes.length - offset);
                    capture.capture(bytes, offset, length);
                    expectedTotal += length;
                }
                case 2 -> capture.markStarted();
                case 3 -> capture.markCompleted();
                case 4 -> {
                    capture.clear();
                    expectedTotal = 0;
                }
            }
        }

        // Then: the count, the null contract and the truncation note hold
        if (capture.getTotalBytes() != expectedTotal) {
            throw new IllegalStateException(
                    "totalBytes drifted: expected " + expectedTotal + ", got " + capture.getTotalBytes());
        }
        Charset charset = data.pickValue(CHARSETS);
        String logged = capture.loggedValue(charset);
        if ((logged == null) != (expectedTotal == 0)) {
            throw new IllegalStateException(
                    "null contract violated: totalBytes=" + expectedTotal + ", logged=" + logged);
        }
        if (logged != null && expectedTotal > maxBytes && !logged.contains("[truncated, ")) {
            throw new IllegalStateException(
                    "missing truncation note: totalBytes=" + expectedTotal + ", maxBytes=" + maxBytes);
        }
    }
}
