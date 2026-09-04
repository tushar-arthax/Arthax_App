package com.example.arthax.recording

import android.net.Uri
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.store.PendingCallStore
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.CallWindow
import com.example.arthax.domain.model.LogStage
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
 * *finished being written*. Three guards do that:
 *
 *  1. Time window — the file's timestamp must fall inside the call, so a recording of an
 *     earlier personal call can never be attributed to a lead.
 *  2. Size stability — a candidate is accepted only once its byte count is unchanged across
 *     two consecutive polls. OEM recorders finalise the container after hang-up, and a
 *     half-written file matters here: the server transcodes with FFmpeg and rejects
 *     anything it cannot decode.
 *  3. Not already claimed — a file already attached to another call is skipped.
 */
@Singleton
class RecordingHarvester @Inject constructor(
    private val finder: RecordingFinder,
    private val storage: RecordingStorage,
    private val pendingCalls: PendingCallStore,
    private val settings: AppSettings,
    private val logger: EventLogger,
) {

    sealed interface Result {
        data class Captured(
            val file: File,
            val fileName: String,
            val mimeType: String,
            val sizeBytes: Long,
            val sourceUri: String,
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

        val timeoutSeconds = settings.snapshot.first().scanTimeoutSeconds
        logger.info(
            LogStage.DETECT,
            "Looking for the recording of the call with $leadTag",
            leadId = window.leadId,
            leadName = window.leadName,
        )

        // uri -> size seen on the previous poll, for the stability check.
        val previousSizes = mutableMapOf<String, Long>()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        var sawCandidateButUnstable = false
        var loggedFirstScan = false
        var lastScan: RecordingFinder.ScanResult? = null

        while (System.currentTimeMillis() < deadline) {
            val scan = finder.findCandidatesSince(treeUri, window.recordingNotBefore)
            lastScan = scan

            val candidates = scan.candidates
                .filter { it.sizeBytes > 0 && window.accepts(it.lastModified) }

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

                val previous = previousSizes[key]
                if (previous == null || previous != candidate.sizeBytes) {
                    previousSizes[key] = candidate.sizeBytes
                    sawCandidateButUnstable = true
                    continue
                }

                // Two identical readings — the recorder has closed the file.
                return capture(window, candidate)
            }

            delay(POLL_INTERVAL_MILLIS)
        }

        val message = if (sawCandidateButUnstable) {
            "Found a recording but it never finished writing within ${timeoutSeconds}s"
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

        // Worth surfacing: a recording far shorter than the call usually means the dialler
        // only started recording partway through, and the rep should know.
        if (window.durationSeconds > SUSPICIOUS_DURATION_THRESHOLD_SECONDS &&
            copy.length() < MIN_PLAUSIBLE_BYTES_PER_SECOND * window.durationSeconds
        ) {
            logger.warn(
                LogStage.DETECT,
                "Recording looks shorter than the call — uploading it anyway",
                leadId = window.leadId,
                leadName = window.leadName,
                detail = "${copy.length()} bytes for a ${window.durationSeconds}s call.",
            )
        }

        logger.success(
            LogStage.DETECT,
            "Captured ${candidate.name} for ${window.leadName.ifBlank { window.leadId }}",
            leadId = window.leadId,
            leadName = window.leadName,
            detail = "${copy.length() / 1024} KB",
        )

        return Result.Captured(
            file = copy,
            fileName = candidate.name,
            mimeType = candidate.mimeType,
            sizeBytes = copy.length(),
            sourceUri = candidate.documentUri.toString(),
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** Below this, an odd byte count tells us nothing useful. */
        const val SUSPICIOUS_DURATION_THRESHOLD_SECONDS = 20L

        /** ~4 kbps floor; even AMR-NB clears this comfortably. */
        const val MIN_PLAUSIBLE_BYTES_PER_SECOND = 500L
    }
}
