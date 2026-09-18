package ai.arthax.app.data.repository

import ai.arthax.app.data.local.store.PendingCall
import ai.arthax.app.data.local.store.PendingCallStore
import ai.arthax.app.data.local.store.SeenCallStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.local.store.SyncState
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.data.remote.api.ArthaxApi
import ai.arthax.app.data.remote.api.safeApiCall
import ai.arthax.app.data.remote.dto.ApiTime
import ai.arthax.app.data.remote.dto.CallCreateRequest
import ai.arthax.app.domain.model.CallOutcome
import ai.arthax.app.domain.model.CallStatus
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.notification.AppNotifications
import ai.arthax.app.recording.RecordingStorage
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a finished call to the server.
 *
 * The backend needs two requests, in order: POST /api/calls/ creates the record and returns
 * its id, then POST /api/calls/{id}/upload-recording attaches the audio. Each step is
 * persisted before the next is attempted, so a process death or a dead zone resumes from
 * where it stopped rather than starting over or double-posting.
 *
 * A recording can be held back between the two steps. When the audio does not fit the
 * call, or the server refuses the file, the call stays in the CRM without it and the row
 * waits in [SyncState.NEEDS_REVIEW] for the rep to say "upload anyway" or "not this call".
 */
@Singleton
class CallSyncRepository @Inject constructor(
    private val api: ArthaxApi,
    private val store: PendingCallStore,
    private val seenCalls: SeenCallStore,
    private val health: SyncHealthStore,
    private val storage: RecordingStorage,
    private val notifications: AppNotifications,
    private val logger: EventLogger,
) {

    sealed interface Outcome {
        data object Done : Outcome

        /** Transient — the scheduler should back off and try again. */
        data class Retry(val reason: String) : Outcome

        /** Terminal — retrying will never help. */
        data class GaveUp(val reason: String) : Outcome
    }

    val queue: StateFlow<List<PendingCall>> = store.items

    suspend fun load() = store.load()

    fun outstanding(): List<PendingCall> = store.outstanding()

    /**
     * Outstanding calls that belong to the signed-in rep.
     *
     * The server derives the agent from whichever bearer token uploads a call, so syncing
     * a queue left behind by a previous rep would file their calls against the current one.
     */
    fun outstandingFor(userId: String?): List<PendingCall> = store.outstandingFor(userId)

    fun needingReview(): List<PendingCall> = store.needingReview()

    fun existsForSource(uri: String): Boolean = store.existsForSource(uri)

    fun existsForCall(callLogId: Long, callLogDate: Long): Boolean =
        store.existsForCall(callLogId, callLogDate)

    suspend fun enqueue(call: PendingCall) {
        store.upsert(call)
        logger.info(
            LogStage.SYNC,
            when {
                call.recordingHeld -> "Queued call with ${call.leadName} — its recording is held for review"
                call.hasRecording -> "Queued call with ${call.leadName} and its recording"
                else -> "Queued call with ${call.leadName} (no recording)"
            },
            leadId = call.leadId,
            leadName = call.leadName,
        )
    }

    /** Rep-initiated retry from the Activity screen. */
    suspend fun requeue(id: String) = store.update(id) {
        it.copy(
            state = if (it.serverCallId != null) SyncState.PENDING_UPLOAD else SyncState.PENDING_CALL_RECORD,
            attempts = 0,
            lastError = null,
        )
    }

    /**
     * "Upload anyway": the rep has listened, or trusts the match. The hold is lifted and
     * the row goes back on the normal path — the upload if the call is already in the CRM,
     * the call record first if it never got that far.
     */
    suspend fun uploadAnyway(id: String) {
        val call = store.byId(id) ?: return
        store.update(id) {
            it.copy(
                state = if (it.serverCallId != null) SyncState.PENDING_UPLOAD else SyncState.PENDING_CALL_RECORD,
                reviewReason = null,
                attempts = 0,
                lastError = null,
            )
        }
        logger.info(
            LogStage.SYNC,
            "Uploading the held recording for ${call.leadName} at your request",
            leadId = call.leadId,
            leadName = call.leadName,
        )
    }

    /**
     * "Not this call": the file is someone else's. It is deleted; the call itself stays —
     * already in the CRM, or still to be posted without audio.
     */
    suspend fun notThisCall(id: String) {
        val call = store.byId(id) ?: return
        call.localFilePath?.let(storage::delete)
        store.update(id) {
            it.copy(
                state = if (it.serverCallId != null) SyncState.DONE else SyncState.PENDING_CALL_RECORD,
                localFilePath = null,
                fileName = null,
                mimeType = null,
                sizeBytes = 0,
                sourceUri = null,
                audioSeconds = null,
                reviewReason = null,
                attempts = 0,
                lastError = null,
            )
        }
        logger.info(
            LogStage.SYNC,
            "Discarded the held recording for ${call.leadName} — the call is kept",
            leadId = call.leadId,
            leadName = call.leadName,
        )
    }

    suspend fun discard(id: String) {
        store.byId(id)?.localFilePath?.let(storage::delete)
        store.remove(id)
    }

    /**
     * Forgets delivered calls, but not what they were: each pruned row's identity goes to
     * the dismissed list so the widened call-log window cannot post it a second time.
     */
    suspend fun pruneDelivered() {
        val pruned = store.pruneDelivered(DELIVERED_RETENTION_MILLIS)
        if (pruned.isNotEmpty()) seenCalls.rememberAll(pruned)
    }

    /** Advances one queued call by exactly one step. */
    suspend fun sync(id: String): Outcome {
        val call = store.byId(id) ?: return Outcome.Done

        return when (call.state) {
            SyncState.PENDING_CALL_RECORD -> createCallRecord(call)
            SyncState.PENDING_UPLOAD -> uploadRecording(call)
            SyncState.DONE, SyncState.FAILED, SyncState.NEEDS_REVIEW -> Outcome.Done
        }
    }

    private suspend fun createCallRecord(call: PendingCall): Outcome {
        val status = if (call.connected) CallStatus.CONNECTED else CallStatus.NOT_ANSWERED
        val outcome = CallOutcome.fromConnected(call.connected)

        logger.info(
            LogStage.SYNC,
            "Logging call with ${call.leadName} to the CRM",
            leadId = call.leadId,
            leadName = call.leadName,
            detail = "${status.api}, ${call.durationSeconds}s, attempt ${call.attempts + 1}",
        )

        val request = CallCreateRequest(
            leadId = call.leadId,
            outcome = outcome.api,
            callStatus = status.api,
            // Taken from the system call log, not assumed — see CallCompleter.decideOutcome.
            direction = call.direction,
            durationSeconds = call.durationSeconds,
            createdAt = ApiTime.format(call.endedAt),
            externalId = call.externalId,
            matchSource = call.matchSource,
        )

        return when (val result = safeApiCall { api.createCall(request) }) {
            is ApiResult.Success -> {
                val serverId = result.data.id
                val next = when {
                    // The file was held at capture: the call is in, the audio waits.
                    call.reviewReason != null && call.hasRecording -> SyncState.NEEDS_REVIEW
                    call.hasRecording -> SyncState.PENDING_UPLOAD
                    else -> SyncState.DONE
                }
                store.update(call.id) {
                    it.copy(serverCallId = serverId, state = next, lastError = null)
                }
                health.recordCallPosted()
                logger.success(
                    LogStage.SYNC,
                    "Call with ${call.leadName} logged to the CRM",
                    leadId = call.leadId,
                    leadName = call.leadName,
                    detail = "Call id $serverId" +
                        if (next == SyncState.NEEDS_REVIEW) ". Its recording is waiting for your decision." else "",
                )
                // Keep going immediately when there is audio waiting; the caller re-enters.
                if (next == SyncState.PENDING_UPLOAD) uploadRecording(store.byId(call.id)!!) else Outcome.Done
            }

            is ApiResult.Failure -> handleFailure(call, result, "log the call")
        }
    }

    private suspend fun uploadRecording(call: PendingCall): Outcome {
        val callId = call.serverCallId
            ?: return handlePermanent(call, "No call id to attach the recording to")

        val path = call.localFilePath
            ?: return handlePermanent(call, "No recording file recorded for this call")

        val file = File(path)
        // The local copy is the only thing still uploadable; the OEM original may be gone.
        if (!file.exists() || file.length() == 0L) {
            return handlePermanent(call, "The saved recording file is missing from this device")
        }

        // A 402 earlier put uploads on hold. Nothing on the phone can end the hold, so no
        // request is made — the call record above is unaffected and the work simply backs
        // off until the hold has passed.
        val blockedUntil = health.current.uploadBlockedUntil
        if (blockedUntil > System.currentTimeMillis()) {
            return Outcome.Retry("Uploads are paused until ${formatTime(blockedUntil)}")
        }

        logger.info(
            LogStage.SYNC,
            "Uploading recording for ${call.leadName} (${file.length() / 1024} KB)",
            leadId = call.leadId,
            leadName = call.leadName,
            detail = "Call id $callId, attempt ${call.attempts + 1}",
        )

        val part = MultipartBody.Part.createFormData(
            FILE_FIELD,
            call.fileName ?: file.name,
            file.asRequestBody(call.mimeType?.toMediaTypeOrNull()),
        )

        return when (val result = safeApiCall { api.uploadRecording(callId, part) }) {
            is ApiResult.Success -> {
                store.update(call.id) {
                    it.copy(
                        state = SyncState.DONE,
                        recordingUrl = result.data.recordingUrl,
                        lastError = null,
                    )
                }
                // Only now is it safe to reclaim the space.
                storage.delete(path)
                health.recordUpload()
                logger.success(
                    LogStage.SYNC,
                    if (result.data.skipped) {
                        "Server already had a recording for ${call.leadName}"
                    } else {
                        "Recording uploaded for ${call.leadName}"
                    },
                    leadId = call.leadId,
                    leadName = call.leadName,
                    detail = result.data.message,
                )
                Outcome.Done
            }

            is ApiResult.Failure -> handleFailure(call, result, "upload the recording")
        }
    }

    private suspend fun handleFailure(
        call: PendingCall,
        failure: ApiResult.Failure,
        action: String,
    ): Outcome {
        // Out of credits. The call and the file are fine; the organisation is not. Uploads
        // pause for a while and nothing is counted against this call's retry budget, so a
        // long lapse cannot turn into a permanently failed call.
        if (failure is ApiResult.Failure.Blocked) {
            val pauseMillis = failure.retryAfterSeconds?.let { it * 1_000L } ?: UPLOAD_PAUSE_MILLIS
            val until = System.currentTimeMillis() + pauseMillis
            health.setUploadBlockedUntil(until)
            store.update(call.id) { it.copy(lastError = failure.message) }
            logger.warn(
                LogStage.SYNC,
                "Could not $action for ${call.leadName} — uploads paused until ${formatTime(until)}",
                leadId = call.leadId,
                leadName = call.leadName,
                detail = "The server answered 402: ${failure.detail ?: "the organisation has no credits"}. " +
                    "Calls are still logged; recordings are sent once credits are added.",
            )
            return Outcome.Retry(failure.message)
        }

        // The server understood and refused, for good. Not "failed" — a human decides.
        if (failure is ApiResult.Failure.Unprocessable) {
            return enterReview(call, "Server rejected it: ${failure.detail ?: failure.message}", action)
        }

        val exhausted = call.attempts + 1 >= MAX_ATTEMPTS

        return if (failure.retryable && !exhausted) {
            store.update(call.id) { it.copy(attempts = it.attempts + 1, lastError = failure.message) }
            logger.warn(
                LogStage.SYNC,
                "Could not $action for ${call.leadName}, will retry: ${failure.message}",
                leadId = call.leadId,
                leadName = call.leadName,
                detail = failure.detail,
            )
            Outcome.Retry(failure.message)
        } else {
            val reason = if (exhausted) {
                "${failure.message} (gave up after $MAX_ATTEMPTS attempts)"
            } else {
                failure.message
            }
            store.update(call.id) {
                it.copy(state = SyncState.FAILED, attempts = it.attempts + 1, lastError = reason)
            }
            logger.error(
                LogStage.SYNC,
                "Could not $action for ${call.leadName}: $reason",
                leadId = call.leadId,
                leadName = call.leadName,
                // The server's own words — usually the fastest route to the actual cause.
                detail = failure.detail,
            )
            // Raised here rather than in the worker, so it appears exactly once no matter
            // which path gave up - the immediate send from the watcher, or a later retry.
            notifications.notifyUploadFailed(call.leadName, reason)
            Outcome.GaveUp(reason)
        }
    }

    /**
     * Parks the row for the rep. The local file is kept — it is the only copy — and the
     * server's reason is shown verbatim, because "422" tells a rep nothing.
     */
    private suspend fun enterReview(call: PendingCall, reason: String, action: String): Outcome {
        store.update(call.id) {
            it.copy(state = SyncState.NEEDS_REVIEW, attempts = it.attempts + 1, reviewReason = reason, lastError = null)
        }
        logger.warn(
            LogStage.SYNC,
            "Could not $action for ${call.leadName} — held for review",
            leadId = call.leadId,
            leadName = call.leadName,
            detail = reason,
        )
        notifications.notifyNeedsReview(call.leadName, reason)
        return Outcome.GaveUp(reason)
    }

    private suspend fun handlePermanent(call: PendingCall, reason: String): Outcome {
        store.update(call.id) { it.copy(state = SyncState.FAILED, lastError = reason) }
        logger.error(
            LogStage.SYNC,
            "$reason (${call.leadName})",
            leadId = call.leadId,
            leadName = call.leadName,
        )
        notifications.notifyUploadFailed(call.leadName, reason)
        return Outcome.GaveUp(reason)
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val FILE_FIELD = "file"
        const val MAX_ATTEMPTS = 8
        val DELIVERED_RETENTION_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** How long a 402 keeps uploads on hold when the server does not say. */
        const val UPLOAD_PAUSE_MILLIS = 30L * 60 * 1000
    }
}
