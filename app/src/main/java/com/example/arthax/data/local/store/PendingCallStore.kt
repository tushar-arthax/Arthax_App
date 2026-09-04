package com.example.arthax.data.local.store

import android.content.Context
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a call is in the two-step handoff to the server.
 *
 * The backend needs the call record created first (POST /api/calls/) because the recording
 * upload is addressed by the call id it returns. So a call is never "uploaded" in one shot;
 * it walks these states, and each one is durable so a process death resumes rather than restarts.
 */
enum class SyncState {
    /** Nothing sent yet. Needs POST /api/calls/. */
    PENDING_CALL_RECORD,

    /** Call record exists on the server; the audio file still needs attaching. */
    PENDING_UPLOAD,

    /** Fully synced. */
    DONE,

    /** Permanently rejected — a human needs to look. The local file is kept. */
    FAILED,
}

/**
 * One call awaiting delivery to the server.
 *
 * [localFilePath] is a copy made into app-private storage before this row is written. The
 * SAF grant can be revoked and OEM recorders prune their own folders, so the copy — not
 * the original — is what actually guarantees a recording is never lost.
 */
@JsonClass(generateAdapter = true)
data class PendingCall(
    @Json(name = "id") val id: String = UUID.randomUUID().toString(),

    /**
     * The phone's own call log row id. This is the call's real identity: reconciling the
     * log twice, or from two entry points at once, must not create two CRM records.
     */
    @Json(name = "call_log_id") val callLogId: Long,

    @Json(name = "lead_id") val leadId: String,
    @Json(name = "lead_name") val leadName: String,
    @Json(name = "phone") val phone: String,
    @Json(name = "connected") val connected: Boolean,

    /**
     * "outbound" or "inbound", taken from the system call log rather than assumed. The rep
     * always dials out, but an incoming call answered while our session was open would
     * otherwise be filed against the wrong lead in the wrong direction.
     */
    @Json(name = "direction") val direction: String,

    /** Real talk seconds from the call log — ringing excluded. */
    @Json(name = "duration_seconds") val durationSeconds: Int,

    /**
     * Which rep captured this call. Queued calls survive a sign-out, and the server derives
     * the agent from whichever bearer token uploads them — so without this, a call made by
     * one rep could be filed against the next person to sign in on the same handset.
     */
    @Json(name = "owner_user_id") val ownerUserId: String? = null,
    @Json(name = "dialed_at") val dialedAt: Long,
    @Json(name = "ended_at") val endedAt: Long,

    /**
     * Sent to the server as `external_id` and reused on every retry, so a response lost to
     * a dropped connection cannot produce a duplicate call in the CRM.
     */
    @Json(name = "external_id") val externalId: String = "android-${UUID.randomUUID()}",

    /** Set once POST /api/calls/ succeeds; the address for the recording upload. */
    @Json(name = "server_call_id") val serverCallId: String? = null,

    @Json(name = "local_file_path") val localFilePath: String? = null,
    @Json(name = "file_name") val fileName: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "size_bytes") val sizeBytes: Long = 0,

    /** Dedupe key: the SAF document URI the recording was copied from. */
    @Json(name = "source_uri") val sourceUri: String? = null,

    @Json(name = "state") val state: SyncState = SyncState.PENDING_CALL_RECORD,
    @Json(name = "attempts") val attempts: Int = 0,
    @Json(name = "last_error") val lastError: String? = null,
    @Json(name = "recording_url") val recordingUrl: String? = null,
    @Json(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val hasRecording: Boolean get() = !localFilePath.isNullOrBlank()

    val isOutstanding: Boolean get() = state == SyncState.PENDING_CALL_RECORD || state == SyncState.PENDING_UPLOAD
}

@Singleton
class PendingCallStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) : JsonListStore<PendingCall>(
    file = File(context.filesDir, "arthax_pending_calls.json"),
    moshi = moshi,
    itemClass = PendingCall::class.java,
    maxItems = MAX_ENTRIES,
) {

    suspend fun upsert(call: PendingCall) = mutate { current ->
        val without = current.filterNot { it.id == call.id }
        (listOf(call.copy(updatedAt = System.currentTimeMillis())) + without).take(MAX_ENTRIES)
    }

    suspend fun update(id: String, transform: (PendingCall) -> PendingCall) = mutate { current ->
        current.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it }
    }

    suspend fun remove(id: String) = mutate { current -> current.filterNot { it.id == id } }

    /** True if this recording was already captured — the guard against double upload. */
    fun existsForSource(uri: String): Boolean = items.value.any { it.sourceUri == uri }

    /** True if this call log entry has already been turned into a CRM record. */
    fun existsForCallLogId(callLogId: Long): Boolean = items.value.any { it.callLogId == callLogId }

    fun outstanding(): List<PendingCall> = items.value.filter { it.isOutstanding }

    /** Outstanding calls belonging to this rep. Rows with no owner predate the field. */
    fun outstandingFor(userId: String?): List<PendingCall> =
        items.value.filter { it.isOutstanding && (it.ownerUserId == null || it.ownerUserId == userId) }

    fun byId(id: String): PendingCall? = items.value.firstOrNull { it.id == id }

    /** Drops long-since-delivered rows; their local files are already gone. */
    suspend fun pruneDelivered(olderThanMillis: Long) = mutate { current ->
        val cutoff = System.currentTimeMillis() - olderThanMillis
        current.filterNot { it.state == SyncState.DONE && it.updatedAt < cutoff }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
