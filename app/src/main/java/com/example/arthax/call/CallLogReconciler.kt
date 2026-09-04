package com.example.arthax.call

import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.data.repository.LeadResolver
import com.example.arthax.domain.model.CallWindow
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingHarvester
import com.example.arthax.work.WorkScheduler
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the phone's call log into CRM activity.
 *
 * Everything funnels through here — a call placed from the app, one dialled straight from
 * the phone's dialler with Arthax closed, an incoming call, a missed call.
 *
 * Three rules govern it:
 *
 *  1. **Only leads.** Each number is resolved against the CRM. Anything that is not a lead
 *     is the rep's own business: no recording is touched, nothing is uploaded, and the
 *     number never appears in a log. Only an anonymous count is kept.
 *
 *  2. **Exactly once.** Progress is a watermark over call-log timestamps, and every queued
 *     call carries the call-log row id, so running this twice — or being interrupted — can
 *     never produce a duplicate in the CRM.
 *
 *  3. **Never discard on doubt.** A call that could not be classified because the phone was
 *     offline is kept and retried. Only a definite "not a lead" drops a call.
 */
@Singleton
class CallLogReconciler @Inject constructor(
    private val callLogReader: CallLogReader,
    private val leadResolver: LeadResolver,
    private val harvester: RecordingHarvester,
    private val syncRepository: CallSyncRepository,
    private val tokenStore: SecureTokenStore,
    private val settings: AppSettings,
    private val workScheduler: WorkScheduler,
    private val logger: EventLogger,
) {

    data class Result(
        val scanned: Int,
        val matched: Int,
        val ignored: Int,
        val deferred: Int,
        val recordingsFound: Int,
    )

    /** Serialised: the receiver, the service and the periodic worker can all land at once. */
    private val mutex = Mutex()

    suspend fun reconcile(reason: String): Result = mutex.withLock {
        val empty = Result(0, 0, 0, 0, 0)

        if (!callLogReader.hasPermission()) {
            // Written once, not on every run. Repeating it every fifteen minutes buried the
            // rest of the log and left a stale warning pinned at the top long after the
            // permission had been granted - which reads exactly like a live failure.
            if (!settings.callLogWarned.first()) {
                settings.setCallLogWarned(true)
                logger.warn(
                    LogStage.CALL,
                    "Cannot check for calls — call log permission is not granted",
                    detail = "Grant it in Settings > Calls being matched. Until then no call " +
                        "can reach the CRM.",
                )
            }
            return@withLock empty
        }

        // Closes the story in the log: if a warning was written earlier, say plainly that it
        // no longer applies rather than leaving the rep to wonder.
        if (settings.callLogWarned.first()) {
            settings.setCallLogWarned(false)
            logger.success(
                LogStage.CALL,
                "Call log permission granted — call tracking is now active",
            )
        }

        if (!tokenStore.isLoggedIn) return@withLock empty

        // Normally armed at sign-in. This is only a safety net for a session that predates
        // that, and unlike the old version it does NOT return early: arming to the newest
        // call that already existed means anything newer is still processed on this very
        // run, instead of being skipped.
        val watermark = settings.lastProcessedCallAt.first().takeIf { it > 0 } ?: armWatermark()

        val entries = callLogReader.entriesSince(watermark)
        if (entries.isEmpty()) {
            // Periodic runs are frequent and would flood the log; every other trigger is
            // something the rep did, and silence there is exactly what makes this
            // impossible to debug from the device.
            if (reason != WorkScheduler.REASON_PERIODIC) {
                logger.info(
                    LogStage.CALL,
                    "Checked for new calls — none since ${formatTime(watermark)}",
                    detail = "Trigger: $reason",
                )
            }
            return@withLock empty
        }

        var matched = 0
        var ignored = 0
        var deferred = 0
        var recordingsFound = 0

        // Advanced only past calls that were actually settled. A deferred call holds the
        // watermark where it is, so the next run sees it again.
        var newWatermark = watermark
        var blocked = false

        for (entry in entries) {
            if (syncRepository.existsForCallLogId(entry.id)) {
                if (!blocked) newWatermark = maxOf(newWatermark, entry.startedAt)
                continue
            }

            when (val resolution = leadResolver.resolve(entry.number)) {
                is LeadResolver.Resolution.NotALead -> {
                    // Deliberately no number, no name, nothing identifying.
                    ignored++
                    if (!blocked) newWatermark = maxOf(newWatermark, entry.startedAt)
                }

                is LeadResolver.Resolution.Unavailable -> {
                    // Cannot say yet. Hold the watermark here so this call — and everything
                    // after it — is reconsidered once the phone is back online.
                    deferred++
                    blocked = true
                    workScheduler.enqueueReconcileWhenOnline()
                }

                is LeadResolver.Resolution.Lead -> {
                    val captured = maybeHarvestRecording(entry, resolution.id, resolution.name)
                    if (captured != null) recordingsFound++

                    val pending = PendingCall(
                        callLogId = entry.id,
                        leadId = resolution.id,
                        leadName = resolution.name,
                        phone = entry.number,
                        connected = entry.connected,
                        direction = entry.direction.api,
                        durationSeconds = entry.durationSeconds,
                        ownerUserId = tokenStore.session?.userId,
                        dialedAt = entry.startedAt,
                        endedAt = entry.endedAt,
                        localFilePath = captured?.file?.absolutePath,
                        fileName = captured?.fileName,
                        mimeType = captured?.mimeType,
                        sizeBytes = captured?.sizeBytes ?: 0,
                        sourceUri = captured?.sourceUri,
                    )

                    logger.info(
                        LogStage.CALL,
                        buildString {
                            append(if (entry.direction.api == "inbound") "Incoming" else "Outgoing")
                            append(" call with ${resolution.name} — ")
                            append(
                                if (entry.connected) {
                                    "connected for ${entry.durationSeconds}s"
                                } else {
                                    "not answered"
                                },
                            )
                        },
                        leadId = resolution.id,
                        leadName = resolution.name,
                        detail = "direction=${entry.direction.api}, " +
                            "outcome=${if (entry.connected) "connected" else "not_picked"}, " +
                            "lead matched ${if (resolution.fromCache) "from cache" else "via server"}" +
                            if (captured != null) ", recording attached" else "",
                    )

                    syncRepository.enqueue(pending)
                    workScheduler.enqueueSync(pending.id)

                    matched++
                    if (!blocked) newWatermark = maxOf(newWatermark, entry.startedAt)
                }
            }
        }

        if (newWatermark > watermark) settings.setLastProcessedCallAt(newWatermark)

        logger.info(
            LogStage.CALL,
            "Checked ${entries.size} call(s) — $matched matched a lead, $ignored ignored" +
                if (deferred > 0) ", $deferred waiting for a network" else "",
            detail = "Trigger: $reason. Looking at calls after ${formatTime(watermark)}.",
        )

        if (deferred > 0) {
            logger.warn(
                LogStage.CALL,
                "$deferred call(s) could not be checked yet — they are kept, not dropped",
                detail = "They will be matched as soon as the phone is back online.",
            )
        }

        if (ignored > 0 && matched == 0) {
            // Useful for support without naming anyone: distinguishes "the app is not seeing
            // calls" from "the app saw calls that were not leads".
            logger.info(
                LogStage.CALL,
                "$ignored call(s) since the last check were not leads and were ignored",
                detail = "Nothing about them is stored or uploaded. Reason: $reason",
            )
        }

        Result(entries.size, matched, ignored, deferred, recordingsFound)
    }

    /**
     * Marks "process calls from here on".
     *
     * Anchored to the newest call the phone *already* had, never to the clock. Using the
     * clock loses any call that happened between signing in and this running — which,
     * because a finished call is exactly what triggers a reconcile, meant the first call
     * after sign-in was silently swallowed every single time.
     */
    suspend fun armWatermark(): Long {
        val watermark = CallWatermark.initial(
            newestExistingCallAt = callLogReader.newestEntryAt(),
            now = System.currentTimeMillis(),
        )
        settings.setLastProcessedCallAt(watermark)

        logger.info(
            LogStage.CALL,
            "Call tracking armed — every call from now on is matched against your leads",
            detail = "Incoming and outgoing, whether or not the app is open.",
        )
        return watermark
    }

    /**
     * Looks for the recording of a call that connected.
     *
     * Skipped for anything nobody answered — the phone records nothing, and scanning anyway
     * risks adopting an unrelated file. Also skipped for calls older than the search window:
     * by then the recorder either never produced a file or has pruned it, and the call is
     * still worth logging without audio.
     */
    private suspend fun maybeHarvestRecording(
        entry: CallLogReader.Entry,
        leadId: String,
        leadName: String,
    ): RecordingHarvester.Result.Captured? {
        if (!entry.connected) return null

        val age = System.currentTimeMillis() - entry.endedAt
        if (age > RECORDING_SEARCH_WINDOW_MILLIS) {
            logger.warn(
                LogStage.DETECT,
                "Logging an older call with $leadName without looking for a recording",
                leadId = leadId,
                leadName = leadName,
                detail = "The call ended ${age / 60_000} minute(s) ago, past the search window.",
            )
            return null
        }

        val window = CallWindow(
            leadId = leadId,
            leadName = leadName,
            startedAt = entry.startedAt,
            endedAt = entry.endedAt,
        )

        return harvester.harvest(window) as? RecordingHarvester.Result.Captured
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private companion object {
        /**
         * How recent a call must be for its recording to still be worth hunting for.
         * Comfortably covers a phone that was frozen, offline or switched off after a call.
         */
        const val RECORDING_SEARCH_WINDOW_MILLIS = 6L * 60 * 60 * 1000
    }
}
