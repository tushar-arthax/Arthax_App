package com.example.arthax.domain.model

/**
 * The slice of time a call occupied, used to decide which recording file belongs to it.
 *
 * Both bounds come from the phone's call log rather than from anything this app observed,
 * so they are correct even for a call it never saw happen — an incoming call, or one the
 * rep dialled from their contacts while Arthax was closed.
 */
data class CallWindow(
    val leadId: String,
    val leadName: String,
    /** When the call started, per the call log. */
    val startedAt: Long,
    /** When the line was released. Equal to [startedAt] for a call nobody answered. */
    val endedAt: Long,
) {
    val durationSeconds: Long get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0)

    /**
     * Earliest timestamp a recording for this call could carry.
     *
     * The grace window matters because OEM recorders disagree about when to stamp the file:
     * some use the moment the dialler opened, others the moment audio started.
     */
    val recordingNotBefore: Long get() = startedAt - PRE_CALL_GRACE_MILLIS

    /**
     * Latest plausible timestamp. Recorders close the container after hang-up, sometimes
     * seconds later, so this reaches past the end of the call.
     */
    val recordingNotAfter: Long get() = endedAt + POST_CALL_GRACE_MILLIS

    fun accepts(fileLastModified: Long): Boolean =
        fileLastModified in recordingNotBefore..recordingNotAfter

    companion object {
        const val PRE_CALL_GRACE_MILLIS = 30_000L
        const val POST_CALL_GRACE_MILLIS = 5L * 60 * 1000
    }
}
