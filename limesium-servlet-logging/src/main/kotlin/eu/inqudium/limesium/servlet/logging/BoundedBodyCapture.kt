package eu.inqudium.limesium.servlet.logging

import eu.inqudium.limesium.common.BodyReadState
import eu.inqudium.limesium.common.BoundedByteBuffer
import eu.inqudium.limesium.common.MeasuredBody
import java.nio.charset.Charset

/**
 * A bounded tee target: the capturing wrappers copy every body byte that actually flows through the
 * exchange into this buffer, up to [maxBytes]; beyond the cap bytes are only counted.
 *
 * The capture is a passive copy of the live stream - it never buffers, replays, or withholds bytes - so
 * unlike a replaying body cache there is no `IN_PROGRESS`/`COMPLETE` lifecycle to manage and nothing to
 * mark complete: at the moment the exchange line is written, whatever has flowed is what gets logged.
 *
 * Single-writer, single-late-reader concurrency model: the container serializes body I/O (one writer at
 * a time), and the emission reads once, at request destruction. Visibility across that handoff is
 * established by THIS class, not borrowed from container internals: [totalBytes] is `@Volatile` and is
 * written LAST in every mutation, so the reader's initial [totalBytes] read publishes all preceding
 * buffer writes (a piggybacked happens-before edge; relying on the async state machine's incidental
 * synchronization instead proved unsafe).
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off. The bytes live in the
 * shared [BoundedByteBuffer], sized once by the length the wrappers learned from `Content-Length`
 * ([expectBytes]); this class adds the count, the read state and the servlet stack's concurrency model.
 *
 * Besides the bytes, the capture records HOW FAR the application consumed the body ([readState]): the
 * tee mirrors consumption, not transmission, so a body the application never read - or stopped reading
 * half-way - is invisible in the byte count alone. The request tee marks the start of consumption and
 * the end of the stream; the emitter turns the state into the `endpoint.request.body.read` counter.
 *
 * The request capture also mirrors the application's READ POSITION, so it follows a `mark`/`reset` of
 * the tee stream: [mark] remembers the count, the buffered length and the read state, [reset] restores
 * them, and the bytes the application then reads again are neither counted nor buffered twice.
 */
internal class BoundedBodyCapture(
    maxBytes: Int,
) : MeasuredBody {
    private val buffer = BoundedByteBuffer(maxBytes)

    /**
     * How far the application consumed the body - see [BodyReadState]. Volatile for the same
     * writer-to-reader handoff as [totalBytes]; it is a separate fact (a zero-byte body can be read to
     * its end), so it has its own field rather than being derived from the count.
     */
    @Volatile
    override var readState: BodyReadState = BodyReadState.UNREAD
        private set

    /**
     * Every byte that flowed, including those beyond the capture limit - the size metrics' source.
     * Volatile, and always the LAST write of a mutation: its write publishes the buffer state to the
     * destruction-time reader (see the class KDoc), and readers must read it FIRST.
     */
    @Volatile
    override var totalBytes: Long = 0
        private set

    /**
     * The body length the client or the application declared, as the buffer's SIZING hint
     * ([BoundedByteBuffer.expect]): ignored once a byte is buffered. A wrong hint costs allocation, never
     * bytes - the cap and the count are unaffected.
     */
    fun expectBytes(length: Long) = buffer.expect(length)

    /** The declared length the buffer is sized by, [UNKNOWN_LENGTH] without one - exposed for the tests. */
    internal val expectedBytes: Long
        get() = buffer.expectedBytes

    fun capture(b: Int) {
        buffer.write(b)
        totalBytes += 1
    }

    fun capture(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        buffer.write(bytes, offset, length)
        totalBytes += length
    }

    // The read position `reset` rewinds to - the start of the stream until `mark` moves it.
    private var markedTotal: Long = 0
    private var markedBuffered = 0
    private var markedState: BodyReadState = BodyReadState.UNREAD

    /** The application selected the body stream or reader: from now on the body counts as (at least) partially read. */
    fun markStarted() {
        if (readState == BodyReadState.UNREAD) {
            readState = BodyReadState.PARTIAL
        }
        // The default mark is the start of the stream in the state the selection left behind: a reset
        // without a mark rewinds a mark-capable stream to its beginning.
        markedState = readState
    }

    /** Remembers the read position for [reset] - the tee's `mark`, taken when the container stream took its own. */
    fun mark() {
        markedTotal = totalBytes
        markedBuffered = buffer.size
        markedState = readState
    }

    /**
     * Rewinds the count, the buffer and the read state to the last [mark] (or to the start of the
     * stream): the container stream rewound, so the bytes the application reads next are a REPLAY and
     * must not count twice. [totalBytes] is written LAST, like every mutation.
     */
    fun reset() {
        buffer.truncate(markedBuffered)
        readState = markedState
        totalBytes = markedTotal
    }

    /** The application observed the end of the stream: the body was consumed completely. */
    fun markCompleted() {
        readState = BodyReadState.COMPLETE
    }

    /**
     * Discards everything captured so far. Called by [CapturingResponseWrapper] when the application
     * resets an UNCOMMITTED response (`reset()`/`resetBuffer()`): nothing written before the reset ever
     * left the container's buffer, so dropping it keeps the logged body and the size metric aligned with
     * what actually went out through the write path. A mark taken before the clear pointed into the
     * discarded bytes and is re-anchored at the start. [totalBytes] is written LAST, like every
     * mutation.
     */
    fun clear() {
        buffer.truncate(0)
        markedTotal = 0
        markedBuffered = 0
        totalBytes = 0
    }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string. Reads [totalBytes] FIRST (the handoff model).
     */
    fun loggedValue(charset: Charset): String? = buffer.render(charset, totalBytes)

    companion object {
        /** No trustworthy declared length: the buffer is sized by what flows. */
        const val UNKNOWN_LENGTH = BoundedByteBuffer.UNKNOWN_LENGTH
    }
}
