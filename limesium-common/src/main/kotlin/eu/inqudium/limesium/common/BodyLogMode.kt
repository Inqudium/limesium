package eu.inqudium.limesium.common

/**
 * When a captured body reaches the exchange line - per direction, the switch that decides log VOLUME.
 * The normative contract of the `log-request-body` / `log-response-body` keys (the reference YAML names
 * them and points here); the decision and its derivation are ADR-0006.
 *
 * [ON_FAILURE] captures exactly like [ALWAYS] (bounded by `max-body-bytes`) - the request body flows
 * BEFORE the outcome is known - and writes the body only for a FAILED exchange: outcome not `success`,
 * that is `rejected` (a 4xx - the application answered, the client's request was wrong, and that is
 * exactly the case a body explains; ADR-0007), `failure`, `timeout`, on the reactive twin `cancelled`.
 * A slow but healthy exchange stays `success` and logs no body.
 */
enum class BodyLogMode {
    /** Nothing is captured for logging; a size meter may still install a count-only capture. */
    NEVER,

    /** Captured on every exchange, logged only when the exchange failed: outcome not `success`. */
    ON_FAILURE,

    /** Captured and logged on every exchange. */
    ALWAYS,
    ;

    /** Whether a bounded capture must be installed: the bytes are needed unless the mode is [NEVER]. */
    val captures: Boolean
        get() = this != NEVER

    /**
     * Whether the captured body is written to the line of an exchange that [failed] (outcome not
     * `success`) or did not.
     */
    fun logs(failed: Boolean): Boolean =
        when (this) {
            NEVER -> false
            ON_FAILURE -> failed
            ALWAYS -> true
        }
}
