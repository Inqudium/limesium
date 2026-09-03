package eu.inqudium.limesium.common

/**
 * When a captured body reaches the exchange line - per direction, the switch that decides log VOLUME
 * (ADR-0006).
 *
 * `always` means every body of every exchange. What an operator nearly always wants is "bodies only
 * when something went wrong": that cuts the volume by orders of magnitude and hits exactly the lines a
 * body is wanted for. The outcome is final before the line is written, so the emitter simply decides
 * then. The request body flows BEFORE the outcome is known, so [ON_FAILURE] captures it exactly like
 * [ALWAYS] does (bounded by `max-body-bytes`) and discards it for a success: the capture is paid, the
 * output is saved - and the output is what burdens the log pipeline.
 *
 * "Failed" is wider than the outcome vocabulary of the exchange line by one status class: `failure`,
 * `timeout`, on the reactive twin `cancelled` - and a 4xx response, which keeps its `success`
 * outcome (the application answered; the client's request was wrong) but is exactly the case a body explains. A 5xx is a
 * `failure` and logs as well; a slow but healthy exchange stays `success` and logs no body.
 */
enum class BodyLogMode {
    /** Nothing is captured for logging; a size meter may still install a count-only capture. */
    NEVER,

    /** Captured on every exchange, logged only when the exchange failed: outcome not `success`, or a 4xx status. */
    ON_FAILURE,

    /** Captured and logged on every exchange. */
    ALWAYS,
    ;

    /** Whether a bounded capture must be installed: the bytes are needed unless the mode is [NEVER]. */
    val captures: Boolean
        get() = this != NEVER

    /** Whether the captured body is written to the line of an exchange that [failed] (outcome not `success`, or a 4xx status) or did not. */
    fun logs(failed: Boolean): Boolean =
        when (this) {
            NEVER -> false
            ON_FAILURE -> failed
            ALWAYS -> true
        }
}
