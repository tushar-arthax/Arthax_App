package ai.arthax.app.domain.model

/** How the CALL button places the call. Rep-selectable in Settings. */
enum class CallMode {
    /** ACTION_CALL — one tap, dials immediately. Requires CALL_PHONE. */
    DIRECT,

    /** ACTION_DIAL — opens the dialler pre-filled so the rep picks the SIM. */
    DIALER,
}
