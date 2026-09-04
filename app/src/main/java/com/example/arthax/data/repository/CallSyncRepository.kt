package com.example.arthax.data.repository

import com.example.arthax.data.local.store.PendingCall
import com.example.arthax.data.local.store.PendingCallStore
import com.example.arthax.data.local.store.SyncState
import com.example.arthax.data.remote.api.ApiResult
import com.example.arthax.data.remote.api.ArthaxApi
import com.example.arthax.data.remote.api.safeApiCall
import com.example.arthax.data.remote.dto.ApiTime
import com.example.arthax.data.remote.dto.CallCreateRequest
import com.example.arthax.domain.model.CallOutcome
import com.example.arthax.domain.model.CallStatus
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingStorage
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a finished call to the server.
 *
 * The backend needs two requests, in order: POST /api/calls/ creates the record and returns
 * its id, then POST /api/calls/{id}/upload-recording attaches the audio. Each step is
 * persisted before the next is attempted, so a process death or a dead zone resumes from
 * where it stopped rather than starting over or double-posting.
 */
@Singleton
class CallSyncRepository @Inject constructor(
    private val api: ArthaxApi,
    private val store: PendingCallStore,
    private val storage: RecordingStorage,
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

    fun existsForSource(uri: String): Boolean = store.existsForSource(uri)

    fun existsForCallLogId(callLogId: Long): Boolean = store.existsForCallLogId(callLogId)

    suspend fun enqueue(call: PendingCall) {
        store.upsert(call)
        logger.info(
            LogStage.SYNC,
            if (call.hasRecording) {
                "Queued call with ${call.leadName} and its recording"
            } else {
                "Queued call with ${call.leadName} (no recording)"
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

    suspend fun discard(id: String) {
        store.byId(id)?.localFilePath?.let(storage::delete)
        store.remove(id)
    }

    suspend fun pruneDelivered() = store.pruneDelivered(DELIVERED_RETENTION_MILLIS)

    /** Advances one queued call by exactly one step. */
    suspend fun sync(id: String): Outcome {
        val call = store.byId(id) ?: return Outcome.Done

        return when (call.state) {
            SyncState.PENDING_CALL_RECORD -> createCallRecord(call)
            SyncState.PENDING_UPLOAD -> uploadRecording(call)
            SyncState.DONE, SyncState.FAILED -> Outcome.Done
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
        )

        return when (val result = safeApiCall { api.createCall(request) }) {
            is ApiResult.Success -> {
                val serverId = result.data.id
                val next = if (call.hasRecording) SyncState.PENDING_UPLOAD else SyncState.DONE
                store.update(call.id) {
                    it.copy(serverCallId = serverId, state = next, lastError = null)
                }
                logger.success(
                    LogStage.SYNC,
                    "Call with ${call.leadName} logged to the CRM",
                    leadId = call.leadId,
                    leadName = call.leadName,
                    detail = "Call id $serverId",
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
            Outcome.GaveUp(reason)
        }
    }

    private suspend fun handlePermanent(call: PendingCall, reason: String): Outcome {
        store.update(call.id) { it.copy(state = SyncState.FAILED, lastError = reason) }
        logger.error(
            LogStage.SYNC,
            "$reason (${call.leadName})",
            leadId = call.leadId,
            leadName = call.leadName,
        )
        return Outcome.GaveUp(reason)
    }

    private companion object {
        const val FILE_FIELD = "file"
        const val MAX_ATTEMPTS = 8
        val DELIVERED_RETENTION_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
