package ai.arthax.app.recording

import android.net.Uri
import android.os.Build
import ai.arthax.app.core.PhoneNumbers
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.local.store.PendingCallStore
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.CallWindow
import ai.arthax.app.domain.model.LogStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the recording the phone made for a given call, and copies it somewhere the app owns.
 *
 * The hard part is not finding a file, it is finding the *right* file at a moment when it is
 * *finished being written*. Five guards do that:
 *
 *  1. Time window — the file's timestamp must fall inside the call, and must not reach past
 *     the point where the next call had already begun. So neither an earlier personal call
 *     nor the following call's audio can be attributed to this lead.
 *  2. Ranking — among the files that survive, the best match wins, not the newest. Taking
 *     the newest is exactly how two leads called a minute apart had their recordings
 *     swapped: see [CallWindow.score].
 *  3. Size stability — a candidate is accepted only once its byte count is unchanged across
 *     two consecutive polls. OEM recorders finalise the container after hang-up, and a
 *     half-written file matters here: the server transcodes with FFmpeg and rejects
 *     anything it cannot decode.
 *  4. Not already claimed — a file already attached to another call is skipped.
 *  5. Length sanity — the audio has to be about as long as the call. A file that is not is
 *     still copied, but held for the rep rather than uploaded: see [RecordingSanity].
 */
@Singleton
class RecordingHarvester @Inject constructor(
    private val finder: RecordingFinder,
    private val storage: RecordingStorage,
    private val probe: RecordingDurationProbe,
    private val pendingCalls: PendingCallStore,
    private val settings: AppSettings,
    private val configStore: RemoteConfigStore,
    private val logger: EventLogger,
) {

    sealed interface Result {
        data class Captured(
            val file: File,
            val fileName: String,
            val mimeType: String,
            val sizeBytes: Long,
            val sourceUri: String,
            /** Measured, or estimated from the byte count, or null when neither was possible. */
            val audioSeconds: Double?,
            /** The recorder wrote the number into the file name: the strongest identity there is. */
            val namedForNumber: Boolean,
            val verdict: RecordingSanity.Verdict,
        ) : Result

        data object AlreadyCaptured : Result
        data object NoFolderConfigured : Result
        data object GrantLost : Result
        data class NotFound(val waitedSeconds: Int) : Result
        data class CopyFailed(val reason: String) : Result
    }

    suspend fun harvest(window: CallWindow): Result {
        val leadTag = window.leadName.ifBlank { window.leadId }

        val treeUriString = settings.recordingsTreeUri.first()
        if (treeUriString.isNullOrBlank()) {
            logger.error(
                LogStage.DETECT,
                "No recordings folder selected — cannot capture this call",
                leadId = window.leadId,
                leadName = window.leadName,
                detail = "Fix it in Settings: choose the folder your phone saves call recordings to.",
            )
            return Result.NoFolderConfigured
        }

        val treeUri = Uri.parse(treeUriString)
        if (!finder.hasValidGrant(treeUri)) {
            logger.error(
                LogStage.DETECT,
                "Lost access to the recordings folder",
                leadId = window.leadId,
                leadName = window.leadName,
                detail = "Re-select the folder in Settings. This happens if app data was cleared, " +
                    "access was revoked, or the storage holding it was unmounted.",
            )
            return Result.GrantLost
        }

        // A call that ended a while ago — a catch-up after the phone was off, or a call
        // whose lead was only added later — has a recorder that finished long since. One
        // look is enough; polling for a minute per call would turn a backlog of a day's
        // calls into an hour of waiting, and the stability check has nothing to catch.
        val settled = System.currentTimeMillis() - window.endedAt > SETTLED_AFTER_MILLIS
        val timeoutSeconds = if (settled) 0 else settings.snapshot.first().scanTimeoutSeconds

        logger.info(
            LogStage.DETECT,
            "Looking for the recording of the call with $leadTag",
            leadId = window.leadId,
            leadName = window.leadName,
            detail = if (settled) "The call ended a while ago, so the folder is checked once." else null,
        )

        // uri -> size seen on the previous poll, for the stability check.
        val previousSizes = mutableMapOf<String, Long>()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        var sawCandidateButUnstable = false
        var loggedFirstScan = false
        var lastScan: RecordingFinder.ScanResult? = null

        do {
            val scan = finder.findCandidatesSince(treeUri, window.recordingNotBefore)
            lastScan = scan

            // Ranked, not newest-first. Taking the newest acceptable file is what swapped
            // two leads' recordings when they were called a minute apart: the earlier call
            // was processed first, saw the later call's file sitting at the top of the list,
            // and took it. See CallWindow.score.
            val candidates = scan.candidates
                .filter { it.sizeBytes > 0 && window.accepts(it.lastModified) }
                .sortedBy { window.score(it.name, it.lastModified) }

            // Logged once per call. This one line answers the first question on every
            // missing-recording report: did we look somewhere that actually holds
            // recordings, and was anything in the right time range?
            if (!loggedFirstScan) {
                loggedFirstScan = true
                logger.info(
                    LogStage.DETECT,
                    "Scanned ${scan.foldersScanned} folder(s), ${scan.audioFilesSeen} audio file(s), " +
                        "${candidates.size} match this call",
                    leadId = window.leadId,
                    leadName = window.leadName,
                    detail = "Accepting files saved between ${formatTime(window.recordingNotBefore)} " +
                        "and ${formatTime(window.recordingNotAfter)}. Newest file in the folder: " +
                        (scan.newestSeenAt?.let(::formatTime) ?: "none"),
                )
            }

            for (candidate in candidates) {
                val key = candidate.documentUri.toString()

                if (pendingCalls.existsForSource(key)) {
                    // Already attached to another call. Not an error, but say so — a silent
                    // return here reads exactly like "found nothing" in the log later.
                    if (candidates.size == 1) {
                        logger.info(
                            LogStage.DETECT,
                            "Recording for $leadTag was already captured, nothing more to do",
                            leadId = window.leadId,
                            leadName = window.leadName,
                            detail = candidate.name,
                        )
                        return Result.AlreadyCaptured
                    }
                    continue
                }

                if (!settled) {
                    val previous = previousSizes[key]
                    if (previous == null || previous != candidate.sizeBytes) {
                        previousSizes[key] = candidate.sizeBytes
                        sawCandidateButUnstable = true
                        continue
                    }
                }

                // Two identical readings — the recorder has closed the file. (Or the call is
                // old enough that it must have.)
                return capture(window, candidate)
            }

            if (System.currentTimeMillis() >= deadline) break
            delay(POLL_INTERVAL_MILLIS)
        } while (true)

        val message = if (sawCandidateButUnstable) {
            "Found a recording but it never finished writing within ${timeoutSeconds}s"
        } else if (settled) {
            "No recording found for the earlier call with $leadTag"
        } else {
            "No recording appeared for the call with $leadTag within ${timeoutSeconds}s"
        }
        val scanSummary = lastScan?.let {
            "Scanned ${it.foldersScanned} folder(s) holding ${it.audioFilesSeen} audio file(s); " +
                "newest was ${it.newestSeenAt?.let(::formatTime) ?: "none"}. "
        }.orEmpty()

        logger.error(
            LogStage.DETECT,
            message,
            leadId = window.leadId,
            leadName = window.leadName,
            detail = scanSummary +
                "Check that call recording is switched on in the phone dialler, and that the " +
                "selected folder is where this phone actually saves recordings.",
        )
        return Result.NotFound(timeoutSeconds)
    }

    private suspend fun capture(window: CallWindow, candidate: RecordingFinder.Candidate): Result {
        val copy = storage.copyIn(candidate.documentUri, candidate.name)
            .getOrElse { error ->
                val reason = error.message ?: error::class.java.simpleName
                logger.error(
                    LogStage.DETECT,
                    "Could not copy the recording into the app",
                    leadId = window.leadId,
                    leadName = window.leadName,
                    detail = reason,
                )
                return Result.CopyFailed(reason)
            }

        // Says *why* this file was chosen, not just that it was. When two calls happen a
        // minute apart this line is the difference between "the right audio went to the
        // right lead" being something you can check and something you have to hope.
        val matchedByName = PhoneNumbers.looksLikeNumber(
            candidate.name,
            PhoneNumbers.matchKey(window.phone),
        )

        // Measured from the container when possible; a byte-count guess otherwise. The
        // verdict is made against the call log's talk time, with the ring allowance the
        // server configured for this manufacturer — a phone that records from dial-out
        // legitimately produces audio longer than the talk time by the ringing.
        val measured = probe.durationSeconds(copy)
        val audioSeconds = measured ?: RecordingSanity.estimateSeconds(copy.length(), candidate.mimeType)
        val config = configStore.current
        val verdict = RecordingSanity.check(
            audioSeconds = audioSeconds,
            estimated = measured == null,
            talkSeconds = window.durationSeconds,
            connected = window.durationSeconds > 0,
            ringAllowanceSec = config.ringAllowanceSecFor(Build.MANUFACTURER),
            slackSec = config.maxDurationSlackSec,
        )

        val length = audioSeconds?.let { String.format(Locale.US, "%.0fs of audio, ", it) }.orEmpty()
        val chosenBecause = if (matchedByName) {
            "the file is named for this number"
        } else {
            "closest file to the end of this call (${formatTime(candidate.lastModified)})"
        }

        when (verdict) {
            is RecordingSanity.Verdict.Upload -> logger.success(
                LogStage.DETECT,
                "Captured ${candidate.name} for ${window.leadName.ifBlank { window.leadId }}",
                leadId = window.leadId,
                leadName = window.leadName,
                detail = "${copy.length() / 1024} KB, $length$chosenBecause",
            )

            is RecordingSanity.Verdict.Review -> logger.warn(
                LogStage.DETECT,
                "Recording for ${window.leadName.ifBlank { window.leadId }} held for review — " +
                    "it does not fit the call",
                leadId = window.leadId,
                leadName = window.leadName,
                detail = "${verdict.reason}. ${copy.length() / 1024} KB, $chosenBecause. " +
                    "The call is logged without it; decide on the Activity screen.",
            )
        }

        return Result.Captured(
            file = copy,
            fileName = candidate.name,
            mimeType = candidate.mimeType,
            sizeBytes = copy.length(),
            sourceUri = candidate.documentUri.toString(),
            audioSeconds = audioSeconds,
            namedForNumber = matchedByName,
            verdict = verdict,
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** Past this the recorder has certainly closed the file; one scan is enough. */
        const val SETTLED_AFTER_MILLIS = 5L * 60 * 1000
    }
}
