package eu.inqudium.limesium.common

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
