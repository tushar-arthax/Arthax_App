package ai.arthax.app.domain.model

import ai.arthax.app.core.PhoneNumbers

/**
 * The slice of time a call occupied, used to decide which recording file belongs to it.
 *
 * Both bounds come from the phone's call log rather than from anything this app observed,
 * so they are correct even for a call it never saw happen — an incoming call, or one the
 * rep dialled from their contacts while Arthax was closed.
 *
 * A time range alone is not enough to identify a recording, and assuming it was produced the
 * worst bug this app has had: two leads called a minute apart had their recordings swapped,
 * because both files fell inside both calls' ranges and each call simply took the newest
 * file it could see. [score] is the answer — it ranks the files a call could plausibly own
 * so the best one wins, rather than the most recent one.
 */
data class CallWindow(
    val leadId: String,
    val leadName: String,
    /** The number that was on the line. Recorders usually put it in the file name. */
    val phone: String,
    /** When the call started, per the call log. */
    val startedAt: Long,
    /** When the line was released. Equal to [startedAt] for a call nobody answered. */
    val endedAt: Long,
    /**
     * When the next call on this phone began, if there was one.
     *
     * Not a hard boundary — a recorder can finalise one file a second or two after the next
     * call has already started — but a file stamped after the next call began almost always
     * belongs to that next call, and [score] weighs it accordingly.
     */
    val nextCallStartedAt: Long? = null,
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
     * Latest plausible timestamp.
     *
     * Recorders close the container after hang-up, sometimes seconds later, so this reaches
     * past the end of the call — but only just. It used to reach five minutes past, which
     * swallowed whole subsequent calls whole.
     *
     * The next call caps it. A file still being closed as the next call starts ringing can
     * belong to this call, so there is a few seconds of allowance; a file stamped well after
     * that cannot, and must not be reachable at all — otherwise a call whose own recording
     * failed would quietly adopt the following call's audio.
     */
    val recordingNotAfter: Long
        get() {
            val generous = endedAt + POST_CALL_GRACE_MILLIS
            val next = nextCallStartedAt ?: return generous
            return minOf(generous, next + NEXT_CALL_FINALISE_ALLOWANCE_MILLIS)
                .coerceAtLeast(endedAt)
        }

    fun accepts(fileLastModified: Long): Boolean =
        fileLastModified in recordingNotBefore..recordingNotAfter

    /**
     * How well a file fits this call. Lower is better; the caller takes the lowest.
     *
     * Three things decide it, in order of how much they can be trusted:
     *
     *  1. **The number in the file name.** When the recorder wrote it there, it is as good
     *     as a label, and no amount of timing coincidence should override it.
     *  2. **Whether the file was stamped after the next call had already begun.** If it was,
     *     it is far more likely to be that call's recording than this one's.
     *  3. **How close the file is to the moment this call ended**, which is when the
     *     recorder closes the container. Not "how recent the file is" — that was the bug.
     */
    fun score(fileName: String?, fileLastModified: Long): Long {
        val key = PhoneNumbers.matchKey(phone)
        val named = PhoneNumbers.looksLikeNumber(fileName, key)

        val afterNextCallBegan = nextCallStartedAt != null && fileLastModified >= nextCallStartedAt
        val distance = kotlin.math.abs(fileLastModified - endedAt)

        return when {
            // Named for this call. Kept apart from everything else so that even a file
            // stamped oddly still beats an unnamed file that happens to sit closer in time.
            named -> distance

            afterNextCallBegan -> BELONGS_TO_A_LATER_CALL + distance

            else -> UNNAMED + distance
        }
    }

    companion object {
        const val PRE_CALL_GRACE_MILLIS = 30_000L

        /**
         * Ninety seconds. Long enough for any recorder to finish closing a file, short
         * enough that it cannot reach across a following call — which five minutes did.
         */
        const val POST_CALL_GRACE_MILLIS = 90_000L

        /**
         * How long after the next call has begun a file may still be this call's.
         *
         * Covers a recorder that is a beat late closing the previous container. Anything
         * later belongs to the call that was already ringing when it was written.
         */
        const val NEXT_CALL_FINALISE_ALLOWANCE_MILLIS = 10_000L

        /**
         * Rank offsets, spaced far enough apart that no difference in timing can promote a
         * file from a worse band into a better one. A call cannot last longer than a day.
         */
        private const val UNNAMED = 1_000_000_000L
        private const val BELONGS_TO_A_LATER_CALL = 2_000_000_000L
    }
}
