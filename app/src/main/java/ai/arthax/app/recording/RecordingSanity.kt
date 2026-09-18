package ai.arthax.app.recording

/**
 * Whether a captured recording plausibly *is* the call it was captured for.
 *
 * Time and file name pick the file; this is the last check before it is uploaded under a
 * lead's name. The call log knows how long the two people talked, and audio that is far
 * shorter or far longer than that is usually not that call at all — the recorder started
 * late, or the file belongs to a call that overlapped this one. Uploading it anyway used to
 * be the policy ("Recording looks shorter than the call — uploading it anyway"), and the
 * result was the wrong conversation sitting on the wrong lead with nobody the wiser.
 *
 * Now such a file is held for the rep to look at. Pure so the band is unit-tested exactly.
 */
object RecordingSanity {

    sealed interface Verdict {
        data object Upload : Verdict

        /** Held back. [reason] is what the rep sees on the review row. */
        data class Review(val reason: String) : Verdict
    }

    /**
     * @param audioSeconds measured length of the file, or null when it could not be read.
     * @param estimated true when [audioSeconds] is a bytes-per-second guess rather than a
     *   measurement, in which case it is reported as such.
     * @param talkSeconds real talk time from the call log; zero for an unanswered call.
     * @param ringAllowanceSec how much ringing a record-from-dial phone may prepend.
     * @param slackSec tolerance on top of that for a recorder that trails the hang-up.
     */
    fun check(
        audioSeconds: Double?,
        estimated: Boolean,
        talkSeconds: Long,
        connected: Boolean,
        ringAllowanceSec: Int,
        slackSec: Int,
    ): Verdict {
        // Nothing to judge by. The time-and-name match already chose this file; refusing
        // it on the strength of a probe failure would lose real recordings on phones whose
        // container the retriever cannot open.
        if (audioSeconds == null) return Verdict.Upload

        val audio = audioSeconds.format()
        val how = if (estimated) " (estimated from file size)" else ""

        if (!connected) {
            // The phone records nothing for a call nobody answered; more than a few seconds
            // of audio is some other call's.
            return if (audioSeconds > UNANSWERED_MAX_SECONDS) {
                Verdict.Review("The call was not answered but the recording is ${audio}s long$how")
            } else {
                Verdict.Upload
            }
        }

        val shortest = talkSeconds * MIN_RATIO
        if (audioSeconds < shortest) {
            return Verdict.Review(
                "Recording is ${audio}s but the call lasted ${talkSeconds}s$how — " +
                    "it may have started late or belong to another call",
            )
        }

        val longest = talkSeconds + ringAllowanceSec + slackSec
        if (audioSeconds > longest) {
            return Verdict.Review(
                "Recording is ${audio}s but the call lasted ${talkSeconds}s$how — " +
                    "it may include another call",
            )
        }

        return Verdict.Upload
    }

    /**
     * A last-resort length from the byte count, for a file the retriever could not read.
     * Deliberately coarse: it only exists so a wildly wrong file is still caught.
     */
    fun estimateSeconds(sizeBytes: Long, mimeType: String?): Double? {
        if (sizeBytes <= 0) return null
        val bytesPerSecond = when {
            mimeType == null -> DEFAULT_BYTES_PER_SECOND
            mimeType.contains("amr") || mimeType.contains("3gpp") -> AMR_BYTES_PER_SECOND
            mimeType.contains("wav") -> WAV_BYTES_PER_SECOND
            else -> DEFAULT_BYTES_PER_SECOND
        }
        return sizeBytes.toDouble() / bytesPerSecond
    }

    private fun Double.format(): String = String.format(java.util.Locale.US, "%.0f", this)

    /** Audio shorter than this fraction of the talk time did not capture the call. */
    const val MIN_RATIO = 0.8

    /** An unanswered call may carry a recorder's brief false start, nothing more. */
    const val UNANSWERED_MAX_SECONDS = 5.0

    /** AAC/M4A at the ~64 kbps OEM diallers use. */
    private const val DEFAULT_BYTES_PER_SECOND = 8_000.0
    private const val AMR_BYTES_PER_SECOND = 1_600.0
    private const val WAV_BYTES_PER_SECOND = 32_000.0
}
