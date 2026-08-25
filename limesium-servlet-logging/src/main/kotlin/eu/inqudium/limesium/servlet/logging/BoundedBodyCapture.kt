package eu.inqudium.limesium.servlet.logging

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

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
 * synchronization instead was finding 4 of CODE_ANALYSIS-2026-08-21.md).
 *
 * With `maxBytes = 0` the capture runs in COUNT-ONLY mode: nothing is buffered, [totalBytes] still
 * counts every byte - the mode the body-size metrics use when body logging is off.
 *
 * Besides the bytes, the capture records HOW FAR the application consumed the body ([readState]): the
 * tee mirrors consumption, not transmission, so a body the application never read - or stopped reading
 * half-way - is invisible in the byte count alone. The request tee marks the start of consumption and
 * the end of the stream; the emitter turns the state into the `endpoint.request.body.read` counter.
 */
class BoundedBodyCapture(
    private val maxBytes: Int,
) {
    private val buffer = ByteArrayOutputStream()

    /**
     * How far the application consumed the body - see [BodyReadState]. Volatile for the same
     * writer-to-reader handoff as [totalBytes]; it is a separate fact (a zero-byte body can be read to
     * its end), so it has its own field rather than being derived from the count.
     */
    @Volatile
    var readState: BodyReadState = BodyReadState.UNREAD
        private set

    /**
     * Every byte that flowed, including those beyond the capture limit - the size metrics' source.
     * Volatile, and always the LAST write of a mutation: its write publishes the buffer state to the
     * destruction-time reader (see the class KDoc), and readers must read it FIRST.
     */
    @Volatile
    var totalBytes: Long = 0
        private set

    fun capture(b: Int) {
        if (buffer.size() < maxBytes) {
            buffer.write(b)
        }
        totalBytes += 1
    }

    fun capture(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val room = maxBytes - buffer.size()
        if (room > 0) {
            buffer.write(bytes, offset, minOf(length, room))
        }
        totalBytes += length
    }

    /** The application selected the body stream or reader: from now on the body counts as (at least) partially read. */
    fun markStarted() {
        if (readState == BodyReadState.UNREAD) {
            readState = BodyReadState.PARTIAL
        }
    }

    /** The application observed the end of the stream: the body was consumed completely. */
    fun markCompleted() {
        readState = BodyReadState.COMPLETE
    }

    /**
     * Discards everything captured so far. Called by [CapturingResponseWrapper] when the application
     * resets an UNCOMMITTED response (`reset()`/`resetBuffer()`): nothing written before the reset ever
     * reached the client, so dropping it keeps the logged body and the size metric aligned with what was
     * actually delivered (finding 3 of CODE_ANALYSIS-2026-08-21.md).
     */
    fun clear() {
        buffer.reset()
        totalBytes = 0
    }

    /**
     * The captured bytes decoded with [charset], suffixed with a truncation note when the body was larger
     * than the capture limit. Returns `null` for a body of zero bytes, so the log emission can omit the
     * key entirely instead of logging an empty string.
     */
    fun loggedValue(charset: Charset): String? {
        if (totalBytes == 0L) {
            return null
        }
        return if (totalBytes > buffer.size()) {
            "${decodeTruncated(buffer.toByteArray(), charset)}... [truncated, $totalBytes bytes total]"
        } else {
            buffer.toString(charset)
        }
    }
}

/**
 * How far the application consumed a body, as observed by the tee. [UNREAD]: the body API was never
 * selected - the bytes, if the client sent any, never reached the application. [PARTIAL]: consumption
 * started but the end of the stream was not observed - a parser that stopped early, an exception
 * mid-read, or simply a read loop that never asked for the final EOF. [COMPLETE]: the end of the
 * stream was observed. The values are the `state` tag of the `endpoint.request.body.read` counter and
 * therefore a twin contract.
 */
enum class BodyReadState(
    val tagValue: String,
) {
    UNREAD("unread"),
    PARTIAL("partial"),
    COMPLETE("complete"),
}

/**
 * Decodes a byte-bounded PREFIX of a text: the capture limit bounds bytes, not characters, so the cut can
 * fall inside a multi-byte sequence; decoded as a whole, that incomplete tail would render as a
 * replacement character and corrupt the logged prefix (finding 8 of CODE_ANALYSIS-2026-08-22T20-06-45.md).
 * Decoding with `endOfInput = false` leaves an incomplete trailing sequence undecoded (underflow) instead
 * of reporting it as malformed; malformed bytes INSIDE the prefix are still replaced, as `String(bytes,
 * charset)` would. Shared by both endpoint-logging twins.
 */
internal fun decodeTruncated(
    bytes: ByteArray,
    charset: Charset,
): String {
    val decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val chars = CharBuffer.allocate((bytes.size * decoder.maxCharsPerByte()).toInt() + 1)
    decoder.decode(ByteBuffer.wrap(bytes), chars, false)
    chars.flip()
    return chars.toString()
}
