package com.example.arthax.call

import com.example.arthax.core.PhoneNumbers
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.local.prefs.SecureTokenStore
import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.local.store.RemoteConfigStore
import com.example.arthax.data.local.store.SeenCallStore
import com.example.arthax.data.local.store.SyncHealthStore
import com.example.arthax.data.local.store.UnmatchedCall
import com.example.arthax.data.local.store.UnmatchedCallStore
import com.example.arthax.data.repository.CallSyncRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.data.repository.LeadResolver
import com.example.arthax.domain.model.CallWindow
import com.example.arthax.domain.model.LogStage
import com.example.arthax.domain.model.MatchSource
import com.example.arthax.notification.AppNotifications
import com.example.arthax.recording.RecordingHarvester
import com.example.arthax.recording.RecordingSanity
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
 *     rep's own business: no recording is touched and nothing is uploaded.
 *
 *  2. **Exactly once, and never none.** Every pass re-reads a trailing window of the call
 *     log rather than only what is past a watermark, because the log does not only grow
 *     forwards: rows are written when a call *ends* but stamped with when it *began*, so
 *     calls that overlap land out of order; and some OEMs record a repeat unanswered call by
 *     re-dating the row already there instead of inserting. A watermark that has moved past
 *     such a row can never look at it again, which is how a run of calls to one lead came
 *     out as two. Reading the window again costs one indexed query and makes a miss
 *     self-correcting. Nothing is handled twice: a row is identified by its id *and* its
 *     timestamp, and is recognised in the pending queue, the unmatched list or the
 *     dismissed list.
 *
 *  3. **Never discard.** A call that could not be classified because the phone was offline
 *     is held and retried. A call the CRM did not recognise is *parked*, not dropped: the
 *     number may be a lead by the afternoon — added late, imported overnight, or on a
 *     colleague's list — and every pass asks again until it is, or until retention runs
 *     out. When it resolves, the call is delivered exactly as a live one would have been.
 */
@Singleton
class CallLogReconciler @Inject constructor(
    private val callLogReader: CallLogReader,
    private val leadResolver: LeadResolver,
    private val harvester: RecordingHarvester,
    private val syncRepository: CallSyncRepository,
    private val seenCalls: SeenCallStore,
    private val unmatched: UnmatchedCallStore,
    private val configStore: RemoteConfigStore,
    private val health: SyncHealthStore,
    private val tokenStore: SecureTokenStore,
    private val settings: AppSettings,
    private val workScheduler: WorkScheduler,
    private val notifications: AppNotifications,
    private val logger: EventLogger,
) {

    data class Result(
        val scanned: Int,
        val matched: Int,
        val ignored: Int,
        val deferred: Int,
        val recordingsFound: Int,
        /** Calls the CRM did not recognise this pass, now waiting for a lead. */
        val parked: Int = 0,
        /** Calls that had been waiting and were delivered this pass. */
        val recovered: Int = 0,
    )

    /** What the watcher shows in its notification. */
    data class Summary(val waitingForLead: Int, val needingReview: Int)

    /** Serialised: the receiver, the service and the periodic worker can all land at once. */
    private val mutex = Mutex()

    /** What one pass found, plus the calls it wants delivered straight away. */
    private data class Pass(val result: Result, val deliverNow: List<String>)

    /**
     * One call, as either the call log or the unmatched list describes it. The two are
     * delivered through the same path on purpose: a call that waited a day for its lead
     * must be posted, and have its recording hunted for, exactly like one matched live.
     */
    private data class Detected(
        val callLogId: Long,
        val callLogDate: Long,
        val number: String,
        val direction: String,
        val connected: Boolean,
        val durationSeconds: Int,
        val startedAt: Long,
        val endedAt: Long,
        val nextCallStartedAt: Long?,
    )

    fun summary(): Summary = Summary(
        waitingForLead = unmatched.count,
        needingReview = syncRepository.needingReview().size,
    )

    /**
     * Detects, then delivers.
     *
     * The two halves are separated on purpose. Detection holds the lock, because two passes
     * over the same watermark would fight; delivery does not, because uploading a recording
     * can take a while and holding the lock through it would delay noticing the *next* call.
     */
    suspend fun reconcile(reason: String): Result {
        val pass = mutex.withLock { runPass(reason) }
        deliver(pass.deliverNow)
        return pass.result
    }

    /**
     * Only the second look at calls still waiting for a lead — no call-log read.
     *
     * Run when the leads list comes back from the server, because that is the moment a
     * rep most expects a newly added lead to pick up the call they made to it earlier.
     */
    suspend fun retryUnmatched(reason: String): Int {
        if (!tokenStore.isLoggedIn || unmatched.count == 0) return 0
        if (!settings.snapshot.first().callTrackingAllowed) return 0

        val deliverNow = mutableListOf<String>()
        val recovered = mutex.withLock { retryUnmatchedLocked(reason, deliverNow) }
        deliver(deliverNow)
        return recovered
    }

    private suspend fun deliver(pendingIds: List<String>) {
        // The call that just ended goes first and goes immediately — that is the whole point
        // of delivering here rather than through the scheduler.
        //
        // Anything behind it is paced. A run of unanswered redials, or a backlog released
        // the moment the phone comes back online, would otherwise arrive at the server as
        // one burst; being pushed back on used to cost those calls permanently. Half a
        // second between them is imperceptible for a catch-up and keeps the burst from
        // forming at all.
        pendingIds.forEachIndexed { index, pendingId ->
            if (index > 0) delay(DELIVERY_GAP_MILLIS)
            deliverImmediately(pendingId)
        }
    }

    private suspend fun runPass(reason: String): Pass {
        val empty = Pass(Result(0, 0, 0, 0, 0), emptyList())

        // Before the permission check, so a signed-out app never warns about a permission it
        // has no use for yet. Observed on a fresh install: the standing periodic check fired
        // on the login screen and wrote "no call can reach the CRM", which reads like a fault.
        if (!tokenStore.isLoggedIn) return empty

        // The rep has not accepted — or has declined — the disclosure. Nothing is read from
        // the call log until they do; the app is a lead list until then, and says so in
        // Settings rather than here on every pass.
        if (!settings.snapshot.first().callTrackingAllowed) return empty

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

        // For the heartbeat: the watcher is alive and looking. An hour without this and the
        // dashboard marks the phone critical, which is the right call.
        health.recordObserverTick()

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

        val deliverNow = mutableListOf<String>()

        // Calls already waiting for a lead get their second look before anything new is
        // read, so a lead added since the last pass picks up its earlier call first.
        val recovered = retryUnmatchedLocked(reason, deliverNow)

        // A trailing window, not just "everything after the watermark".
        //
        // A watermark alone assumes the call log only ever grows forwards, and it does not:
        // a row can be written late, carry an earlier timestamp than one already processed,
        // or be re-dated in place. Once the watermark has moved past such a row, a
        // forward-only read can never see it again — which is how a run of calls to one lead
        // came out as two. Re-reading the recent past costs one indexed query and makes the
        // whole class of problem self-correcting: anything missed is picked up on the very
        // next pass, and the queue, the unmatched list and the dismissed list stop it being
        // handled twice.
        //
        // How far back is the server's call (72 hours by default): wide enough that a
        // watcher the OS killed on Friday afternoon does not lose the shift, narrow enough
        // to stay inside what the queue and the dismissed list remember.
        val lookbackMillis = configStore.current.lookbackMillis
        val windowFrom = CallWatermark.windowStart(floor, watermark, lookbackMillis)

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
            return Pass(Result(0, 0, 0, 0, 0, recovered = recovered), deliverNow)
        }

        var matched = 0
        var ignored = 0
        var deferred = 0
        var parked = 0
        var recordingsFound = 0
        var newRows = 0
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

        // The lead the rep last tapped CALL on, if that tap is still recent enough to
        // explain a call. Consumed by the first outgoing row it fits.
        var clickToCall = settings.clickToCall.first()
            ?.takeUnless { it.isStale(System.currentTimeMillis()) }

        for ((index, entry) in entries.withIndex()) {
            // When the next call on this phone began. It bounds how far a recording for
            // *this* call can plausibly be stamped, which is what stops one call adopting
            // the audio of the one that followed it.
            val nextCallStartedAt = entries.getOrNull(index + 1)?.startedAt

            // Three ways a row can already be accounted for: it became CRM activity and is
            // in the queue, it is parked waiting for a lead, or it could never be a lead.
            // All are needed now that the same rows are read on every pass.
            if (syncRepository.existsForCall(entry.id, entry.startedAt) ||
                unmatched.contains(entry.id, entry.startedAt) ||
                seenCalls.contains(entry.id, entry.startedAt)
            ) {
                advancePast(entry)
                continue
            }

            newRows++
            val numberKey = PhoneNumbers.matchKey(entry.number)

            // Withheld, unknown or malformed. There is no number to ask about, now or ever,
            // so this is the one kind of row that is dismissed for good — and, deliberately,
            // nothing identifying is written down about it.
            if (numberKey.isEmpty()) {
                ignored++
                dismissed += entry.id to entry.startedAt
                advancePast(entry)
                continue
            }

            val tapped = clickToCall?.takeIf { it.matches(entry.direction.api, entry.number, entry.startedAt) }
            val resolution = if (tapped != null) {
                // The rep chose this lead moments ago. That beats a fresh lookup when two
                // leads share the number, and tells the CRM the call came from the app.
                clickToCall = null
                settings.setClickToCall(null)
                LeadResolver.Resolution.Lead(tapped.leadId, tapped.leadName, MatchSource.CLICK_TO_CALL)
            } else {
                askedThisPass[numberKey]
                    ?: leadResolver.resolve(entry.number).also {
                        // An "unavailable" is a moment in time, not an answer; asking again a
                        // second later may well succeed.
                        if (it !is LeadResolver.Resolution.Unavailable) askedThisPass[numberKey] = it
                    }
            }

            when (resolution) {
                is LeadResolver.Resolution.NotALead -> {
                    // Not a lead *today*. Parked with everything needed to deliver it later,
                    // and asked about again on every pass until it is one or retention runs
                    // out. The row id is remembered so re-reading the window does not ask
                    // the server about the same call again on every pass.
                    parked++
                    unmatched.remember(
                        UnmatchedCall(
                            callLogId = entry.id,
                            callLogDate = entry.startedAt,
                            phone = entry.number,
                            direction = entry.direction.api,
                            connected = entry.connected,
                            durationSeconds = entry.durationSeconds,
                            startedAt = entry.startedAt,
                            endedAt = entry.endedAt,
                            nextCallStartedAt = nextCallStartedAt,
                            lastCheckedAt = System.currentTimeMillis(),
                            checks = 1,
                        ),
                    )
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
                    val pending = queueCall(entry.toDetected(nextCallStartedAt), resolution)
                    if (pending.hasRecording) recordingsFound++
                    deliverNow += pending.id
                    matched++
                    advancePast(entry)
                }
            }
        }

        if (dismissed.isNotEmpty()) seenCalls.rememberAll(dismissed)
        health.recordCallsSeen(newRows)

        if (newWatermark > watermark) settings.setLastProcessedCallAt(newWatermark)
        if (newIdWatermark > idWatermark) settings.setLastProcessedCallId(newIdWatermark)

        logger.info(
            LogStage.CALL,
            "Checked ${entries.size} call(s) — $matched matched a lead" +
                (if (parked > 0) ", $parked waiting for a matching lead" else "") +
                (if (ignored > 0) ", $ignored ignored" else "") +
                (if (deferred > 0) ", $deferred waiting for a network" else ""),
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

        if (parked > 0) {
            // Useful for support without naming anyone: distinguishes "the app is not seeing
            // calls" from "the app saw calls the CRM does not know".
            logger.info(
                LogStage.CALL,
                "$parked call(s) were not to a known lead — kept in case the lead is added",
                detail = "Each is re-checked against the CRM on every pass for " +
                    "${configStore.current.unmatchedRetentionDays} day(s). Nothing is uploaded " +
                    "until a lead matches. Reason: $reason",
            )
        }

        return Pass(
            Result(entries.size, matched, ignored, deferred, recordingsFound, parked, recovered),
            deliverNow,
        )
    }

    /**
     * The second look at calls parked as "not a lead". Caller holds the lock.
     *
     * Two kinds of look, priced differently. The offline list is consulted for every row on
     * every pass, because it is free and a lead just matched live for another call may well
     * explain a parked one. The server is asked at most once per [MobileConfig.byPhoneRecheckHours]
     * per row, so a rep with a hundred personal calls does not cost a hundred requests every
     * minute. The first "unavailable" ends the server round for this pass — the phone is
     * offline, and asking nine more times will not change that.
     *
     * Returns how many were delivered.
     */
    private suspend fun retryUnmatchedLocked(reason: String, deliverNow: MutableList<String>): Int {
        val config = configStore.current
        val now = System.currentTimeMillis()

        val expired = unmatched.pruneExpired(config.unmatchedRetentionMillis, now)
        if (expired > 0) {
            logger.info(
                LogStage.CALL,
                "$expired call(s) waited ${config.unmatchedRetentionDays} day(s) for a lead and were let go",
                detail = "No lead with their number was added in that time.",
            )
        }

        val rows = unmatched.outstanding()
        if (rows.isEmpty()) return 0

        var recovered = 0
        var askedServer = 0
        var offline = false
        val askedThisPass = mutableMapOf<String, LeadResolver.Resolution>()

        for (row in rows) {
            val key = PhoneNumbers.matchKey(row.phone)

            var lead: LeadResolver.Resolution.Lead? = leadResolver.resolveFromCache(row.phone)

            if (lead == null && !offline && row.isDueForServerCheck(now, config.byPhoneRecheckMillis)) {
                val resolution = askedThisPass[key] ?: run {
                    askedServer++
                    leadResolver.resolve(row.phone).also {
                        if (it !is LeadResolver.Resolution.Unavailable) askedThisPass[key] = it
                    }
                }
                when (resolution) {
                    is LeadResolver.Resolution.Lead -> lead = resolution
                    is LeadResolver.Resolution.NotALead -> unmatched.markChecked(row.callLogId, row.callLogDate, now)
                    is LeadResolver.Resolution.Unavailable -> offline = true
                }
            }

            if (lead == null) continue

            unmatched.remove(row.callLogId, row.callLogDate)
            val pending = queueCall(row.toDetected(), lead)
            deliverNow += pending.id
            recovered++
        }

        if (recovered > 0 || askedServer > 0) {
            logger.info(
                LogStage.CALL,
                if (recovered > 0) {
                    "$recovered earlier call(s) now match a lead and are being sent"
                } else {
                    "${rows.size} call(s) still waiting for a matching lead"
                },
                detail = "Re-checked $askedServer number(s) against the CRM" +
                    (if (offline) " until the connection dropped" else "") +
                    ". Trigger: $reason",
            )
        }

        return recovered
    }

    /**
     * Turns a matched call into a queue row: hunts for its recording, records it, logs it.
     *
     * The same for a call matched live and one that waited a day for its lead — which is
     * the point. The only difference is time: for an old call the recorder has certainly
     * finished, and the file may already have been pruned by the phone.
     */
    private suspend fun queueCall(call: Detected, lead: LeadResolver.Resolution.Lead): PendingCall {
        val captured = maybeHarvestRecording(call, lead.id, lead.name)

        val verdict = captured?.verdict
        val reviewReason = (verdict as? RecordingSanity.Verdict.Review)?.reason

        val pending = PendingCall(
            callLogId = call.callLogId,
            callLogDate = call.callLogDate,
            leadId = lead.id,
            leadName = lead.name,
            phone = call.number,
            connected = call.connected,
            direction = call.direction,
            durationSeconds = call.durationSeconds,
            matchSource = lead.source.api,
            ownerUserId = tokenStore.session?.userId,
            dialedAt = call.startedAt,
            endedAt = call.endedAt,
            localFilePath = captured?.file?.absolutePath,
            fileName = captured?.fileName,
            mimeType = captured?.mimeType,
            sizeBytes = captured?.sizeBytes ?: 0,
            sourceUri = captured?.sourceUri,
            audioSeconds = captured?.audioSeconds,
            reviewReason = reviewReason,
        )

        logger.info(
            LogStage.CALL,
            buildString {
                append(if (call.direction == "inbound") "Incoming" else "Outgoing")
                append(" call with ${lead.name} — ")
                append(
                    if (call.connected) {
                        "connected for ${call.durationSeconds}s"
                    } else {
                        "not answered"
                    },
                )
            },
            leadId = lead.id,
            leadName = lead.name,
            detail = "direction=${call.direction}, " +
                "outcome=${if (call.connected) "connected" else "not_picked"}, " +
                when (lead.source) {
                    MatchSource.CLICK_TO_CALL -> "the lead you tapped CALL on"
                    MatchSource.LEAD_CACHE -> "matched from the offline list (CRM unreachable)"
                    MatchSource.BY_PHONE -> "matched live against the CRM"
                } +
                (if (lead.isJunk) ", lead is marked junk" else "") +
                when {
                    reviewReason != null -> ", recording held for review"
                    captured != null -> ", recording attached"
                    else -> ""
                },
        )

        syncRepository.enqueue(pending)

        if (reviewReason != null) notifications.notifyNeedsReview(lead.name, reviewReason)

        return pending
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
     * risks adopting an unrelated file. Also skipped for calls older than the look-back
     * window: by then the recorder has long since pruned its folder, and the call is still
     * worth logging without audio. Inside the window the folder is checked once, without
     * the usual wait, because the recorder finished long ago.
     */
    private suspend fun maybeHarvestRecording(
        call: Detected,
        leadId: String,
        leadName: String,
    ): RecordingHarvester.Result.Captured? {
        if (!call.connected) return null

        val age = System.currentTimeMillis() - call.endedAt
        if (age > configStore.current.lookbackMillis) {
            logger.warn(
                LogStage.DETECT,
                "Logging an older call with $leadName without looking for a recording",
                leadId = leadId,
                leadName = leadName,
                detail = "The call ended ${age / 3_600_000} hour(s) ago, past the search window; " +
                    "the phone has most likely pruned the file by now.",
            )
            return null
        }

        val window = CallWindow(
            leadId = leadId,
            leadName = leadName,
            phone = call.number,
            startedAt = call.startedAt,
            endedAt = call.endedAt,
            nextCallStartedAt = call.nextCallStartedAt,
        )

        return harvester.harvest(window) as? RecordingHarvester.Result.Captured
    }

    private fun CallLogReader.Entry.toDetected(nextCallStartedAt: Long?) = Detected(
        callLogId = id,
        callLogDate = startedAt,
        number = number,
        direction = direction.api,
        connected = connected,
        durationSeconds = durationSeconds,
        startedAt = startedAt,
        endedAt = endedAt,
        nextCallStartedAt = nextCallStartedAt,
    )

    private fun UnmatchedCall.toDetected() = Detected(
        callLogId = callLogId,
        callLogDate = callLogDate,
        number = phone,
        direction = direction,
        connected = connected,
        durationSeconds = durationSeconds,
        startedAt = startedAt,
        endedAt = endedAt,
        nextCallStartedAt = nextCallStartedAt,
    )

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private companion object {
        /**
         * Spacing between calls delivered in the same pass. Only ever applies to a catch-up,
         * because a single finished call is delivered on its own with no wait at all.
         */
        const val DELIVERY_GAP_MILLIS = 500L
    }
}
