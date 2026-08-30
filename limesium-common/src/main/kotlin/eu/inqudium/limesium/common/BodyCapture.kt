package eu.inqudium.limesium.common

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * How far the application consumed a body, as observed by a twin's tee. [UNREAD]: the body was never
 * selected/subscribed to - the bytes, if the client sent any, never reached the application.
 * [PARTIAL]: consumption started but the end of the stream was not observed - an early-exiting parser,
 * an exception or cancellation mid-read, or a read loop that never asked for the final EOF.
 * [COMPLETE]: the end of the stream was observed. The values are the `state` tag of the
 * `endpoint.request.body.read` counter and therefore a twin contract; the exact observation points are
 * documented on each twin's `BoundedBodyCapture` (deliberately separate implementations - ADR-0003).
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
 * replacement character and corrupt the logged prefix (finding 8 of an internal code analysis).
 * Decoding with `endOfInput = false` leaves an incomplete trailing sequence undecoded (underflow) instead
 * of reporting it as malformed; malformed bytes INSIDE the prefix are still replaced, as `String(bytes,
 * charset)` would. Shared by both endpoint-logging twins (ADR-0003 amendment).
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
