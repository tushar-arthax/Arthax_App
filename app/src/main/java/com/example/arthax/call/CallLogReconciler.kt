package com.example.arthax.call

import com.example.arthax.core.PhoneNumbers
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.local.store.SeenCallStore
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.data.repository.LeadResolver
import com.example.arthax.domain.model.CallWindow
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingHarvester
import com.example.arthax.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 *  1. **Only leads, decided live.** Every number is checked against the CRM as the call
 *     happens — never against a remembered answer — so a lead added moments ago is matched
 *     and a lead deleted stops being matched at once. Anything that is not a lead is the
 *     rep's own business: no recording is touched, nothing is uploaded, and the number never
 *     appears in a log. Only an anonymous count is kept.
 *
 *  2. **Exactly once, and never none.** Every pass re-reads a trailing window of the call
 *     log rather than only what is past a watermark, because the log does not only grow
 *     forwards: rows are written when a call *ends* but stamped with when it *began*, so
 *     calls that overlap land out of order; and some OEMs record a repeat unanswered call by
 *     re-dating the row already there instead of inserting. A watermark that has moved past
 *     such a row can never look at it again, which is how a run of calls to one lead came
 *     out as two. Reading the window again costs one indexed query and makes a miss
 *     self-correcting. Nothing is handled twice: a row is identified by its id *and* its
 *     timestamp, and is recognised either in the pending queue or in the dismissed list.
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
    private val seenCalls: SeenCallStore,
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

    /** What one pass found, plus the calls it wants delivered straight away. */
    private data class Pass(val result: Result, val deliverNow: List<String>)

    /**
     * Detects, then delivers.
     *
     * The two halves are separated on purpose. Detection holds the lock, because two passes
     * over the same watermark would fight; delivery does not, because uploading a recording
     * can take a while and holding the lock through it would delay noticing the *next* call.
     */
    suspend fun reconcile(reason: String): Result {
        val pass = mutex.withLock { runPass(reason) }

        // The call that just ended goes first and goes immediately — that is the whole point
        // of delivering here rather than through the scheduler.
        //
        // Anything behind it is paced. A run of unanswered redials, or a backlog released
        // the moment the phone comes back online, would otherwise arrive at the server as
        // one burst; being pushed back on used to cost those calls permanently. Half a
        // second between them is imperceptible for a catch-up and keeps the burst from
        // forming at all.
        pass.deliverNow.forEachIndexed { index, pendingId ->
            if (index > 0) delay(DELIVERY_GAP_MILLIS)
            deliverImmediately(pendingId)
        }

        return pass.result
    }

    private suspend fun runPass(reason: String): Pass {
        val empty = Pass(Result(0, 0, 0, 0, 0), emptyList())

        // Before the permission check, so a signed-out app never warns about a permission it
        // has no use for yet. Observed on a fresh install: the standing periodic check fired
        // on the login screen and wrote "no call can reach the CRM", which reads like a fault.
        if (!tokenStore.isLoggedIn) return empty

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
            return empty
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

        // Standing safety net for the queue itself, run before anything can return early. A
        // call is only really delivered once the server has it, and a sync worker can be
        // stopped by the system in between. Work requests are unique per call and KEEP, so
        // re-arming costs nothing when one is already scheduled or backing off, and rescues
        // anything that was left stranded — which is most likely to be found on a run that
        // has no new calls at all.
        resumeStalledUploads()

        // Normally armed at sign-in. This is only a safety net for a session that predates
        // that, and unlike the old version it does NOT return early: arming to the newest
        // call that already existed means anything newer is still processed on this very
        // run, instead of being skipped.
        val watermark = settings.lastProcessedCallAt.first().takeIf { it > 0 } ?: armWatermark()

        // Sessions that predate the id watermark get it anchored to the rows the timestamp
        // one already covers — never to zero, which would sweep up the phone's whole call
        // history and post months of old calls as if they had all just happened.
        val idWatermark = settings.lastProcessedCallId.first().takeIf { it > 0 }
            ?: callLogReader.newestIdAtOrBefore(watermark).also {
                settings.setLastProcessedCallId(it)
            }

        // The floor is set once and never moves. Everything below it predates tracking on
        // this install and must never be posted, however wide the window below gets.
        val floor = settings.callTrackingFloor.first().takeIf { it > 0 }
            ?: watermark.also { settings.setCallTrackingFloor(it) }

        // A trailing window, not just "everything after the watermark".
        //
        // A watermark alone assumes the call log only ever grows forwards, and it does not:
        // a row can be written late, carry an earlier timestamp than one already processed,
        // or be re-dated in place. Once the watermark has moved past such a row, a
        // forward-only read can never see it again — which is how a run of calls to one lead
        // came out as two. Re-reading the recent past costs one indexed query and makes the
        // whole class of problem self-correcting: anything missed is picked up on the very
        // next pass, and the queue and the dismissed list stop it being handled twice.
        val windowFrom = maxOf(floor, watermark - LOOKBACK_MILLIS)

        val entries = callLogReader.entriesSince(sinceMillis = windowFrom, sinceId = idWatermark)
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
            return empty
        }

        var matched = 0
        var ignored = 0
        var deferred = 0
        var recordingsFound = 0
        val deliverNow = mutableListOf<String>()
        val dismissed = mutableListOf<Pair<Long, Long>>()

        // Advanced only past calls that were actually settled. A deferred call holds both
        // watermarks where they are, so the next run sees it again.
        var newWatermark = watermark
        var newIdWatermark = idWatermark
        var blocked = false

        fun advancePast(entry: CallLogReader.Entry) {
            if (blocked) return
            newWatermark = maxOf(newWatermark, entry.startedAt)
            newIdWatermark = maxOf(newIdWatermark, entry.id)
        }

        // Answers already given during *this* pass, so two calls to the same number a minute
        // apart cost one lookup rather than two. Scoped to the pass and thrown away with it:
        // nothing is remembered between runs, which is what keeps every answer live.
        val askedThisPass = mutableMapOf<String, LeadResolver.Resolution>()

        for ((index, entry) in entries.withIndex()) {
            // When the next call on this phone began. It bounds how far a recording for
            // *this* call can plausibly be stamped, which is what stops one call adopting
            // the audio of the one that followed it.
            val nextCallStartedAt = entries.getOrNull(index + 1)?.startedAt

            // Two ways a row can already be accounted for: it became CRM activity and is in
            // the queue, or it was checked and was not a lead. Both are needed now that the
            // same rows are read on every pass.
            if (syncRepository.existsForCall(entry.id, entry.startedAt) ||
                seenCalls.contains(entry.id, entry.startedAt)
            ) {
                advancePast(entry)
                continue
            }

            val numberKey = PhoneNumbers.matchKey(entry.number)
            val resolution = askedThisPass[numberKey]
                ?: leadResolver.resolve(entry.number).also {
                    // An "unavailable" is a moment in time, not an answer; asking again a
                    // second later may well succeed.
                    if (it !is LeadResolver.Resolution.Unavailable) askedThisPass[numberKey] = it
                }

            when (resolution) {
                is LeadResolver.Resolution.NotALead -> {
                    // Deliberately no number, no name, nothing identifying. The row id is
                    // remembered so re-reading the window does not ask the server about the
                    // same call again on every pass.
                    ignored++
                    dismissed += entry.id to entry.startedAt
                    advancePast(entry)
                }

                is LeadResolver.Resolution.Unavailable -> {
                    // Cannot say yet. Hold the watermark here so this call — and everything
                    // after it — is reconsidered once the phone is back online.
                    deferred++
                    blocked = true
                    workScheduler.enqueueReconcileWhenOnline()
                }

                is LeadResolver.Resolution.Lead -> {
                    val captured = maybeHarvestRecording(
                        entry = entry,
                        leadId = resolution.id,
                        leadName = resolution.name,
                        nextCallStartedAt = nextCallStartedAt,
                    )
                    if (captured != null) recordingsFound++

                    val pending = PendingCall(
                        callLogId = entry.id,
                        callLogDate = entry.startedAt,
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
                            (
                                if (resolution.fromCache) {
                                    "matched from the offline list (CRM unreachable)"
                                } else {
                                    "matched live against the CRM"
                                }
                                ) +
                            if (captured != null) ", recording attached" else "",
                    )

                    syncRepository.enqueue(pending)
                    deliverNow += pending.id

                    matched++
                    advancePast(entry)
                }
            }
        }

        if (dismissed.isNotEmpty()) seenCalls.rememberAll(dismissed)

        if (newWatermark > watermark) settings.setLastProcessedCallAt(newWatermark)
        if (newIdWatermark > idWatermark) settings.setLastProcessedCallId(newIdWatermark)

        logger.info(
            LogStage.CALL,
            "Checked ${entries.size} call(s) — $matched matched a lead, $ignored ignored" +
                if (deferred > 0) ", $deferred waiting for a network" else "",
            // Every row this pass looked at, by call-log id and time. No numbers and no
            // names, so a non-lead stays anonymous — but enough to settle, from the phone,
            // whether a call the rep can see in their dialler ever reached this app at all.
            detail = "Trigger: $reason. Window from ${formatTime(windowFrom)}, " +
                "row ids above $idWatermark. Rows seen: " +
                entries.joinToString(", ") { "#${it.id}@${formatTime(it.startedAt)}" },
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
                detail = "Each was checked against the CRM as it happened, and nothing about " +
                    "them is stored or uploaded. Reason: $reason",
            )
        }

        return Pass(
            Result(entries.size, matched, ignored, deferred, recordingsFound),
            deliverNow,
        )
    }

    /**
     * Sends a freshly queued call to the server straight away.
     *
     * This is the shortest path there is from hang-up to the CRM. The caller is already
     * awake — the watcher is a foreground service, exempt from the freezer — so going through
     * the scheduler first would only add its latency for nothing.
     *
     * WorkManager is still the retry net, and picks up anything this could not finish:
     * no signal, a server hiccup, a process killed halfway. A call that ran out of retries
     * is left alone, because its reason is already on the Activity screen.
     */
    private suspend fun deliverImmediately(pendingId: String) {
        val outcome = runCatching { syncRepository.sync(pendingId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

        when (outcome) {
            CallSyncRepository.Outcome.Done, is CallSyncRepository.Outcome.GaveUp -> Unit
            else -> workScheduler.enqueueSync(pendingId)
        }
    }

    /**
     * Re-arms sync work for calls that are queued but not yet delivered.
     *
     * Scoped to the signed-in rep, because the server attributes a call to whoever's token
     * uploads it and another rep's leftovers must not ride along on this one's session.
     */
    private fun resumeStalledUploads() {
        val outstanding = syncRepository.outstandingFor(tokenStore.session?.userId)
        if (outstanding.isEmpty()) return
        workScheduler.enqueueSyncAll(outstanding.map { it.id })
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
        // Both, together. Arming one and leaving the other at zero would make the very next
        // pass treat every row in the phone's history as new.
        settings.setLastProcessedCallId(callLogReader.newestId())

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
        nextCallStartedAt: Long?,
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
            phone = entry.number,
            startedAt = entry.startedAt,
            endedAt = entry.endedAt,
            nextCallStartedAt = nextCallStartedAt,
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

        /**
         * How far back each pass re-reads the call log.
         *
         * Six hours covers any plausible delay in a row appearing, being re-dated, or being
         * written out of order, while staying far inside the queue's own retention — so a
         * call delivered days ago cannot come round again and be posted twice.
         */
        const val LOOKBACK_MILLIS = 6L * 60 * 60 * 1000

        /**
         * Spacing between calls delivered in the same pass. Only ever applies to a catch-up,
         * because a single finished call is delivered on its own with no wait at all.
         */
        const val DELIVERY_GAP_MILLIS = 500L
    }
}
