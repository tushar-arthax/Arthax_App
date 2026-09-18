package ai.arthax.app.data.local.store

import android.content.Context
import ai.arthax.app.call.CallWatermark
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

    /**
     * The call is in the CRM but its recording was held back for the rep to look at: the
     * audio is much shorter or longer than the call, or the server refused the file. The
     * local copy is kept until the rep says "upload anyway" or "not this call".
     */
    NEEDS_REVIEW,
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
     * The phone's own call log row id. Half of the call's identity: reconciling the log
     * twice, or from two entry points at once, must not create two CRM records.
     */
    @Json(name = "call_log_id") val callLogId: Long,

    /**
     * When that row said the call began — the other half of the identity.
     *
     * The id alone is not enough. Xiaomi and some other builds fold a run of consecutive
     * unanswered calls to the same number into the row that is already there, bumping its
     * timestamp rather than inserting a new one. Treating the id as the whole identity meant
     * every call after the first in such a run was seen as "already handled" and dropped —
     * which is why a lead could trade seven unanswered calls with a rep and see three.
     *
     * Zero on rows written before this field existed; those match on the id alone, so an
     * upgrade cannot re-post calls that were already delivered.
     */
    @Json(name = "call_log_date") val callLogDate: Long = 0,

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

    /** How the lead was decided — sent as `match_source`. Null on rows from older builds. */
    @Json(name = "match_source") val matchSource: String? = null,

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

    /** Measured length of the copied audio, when it could be read. */
    @Json(name = "audio_seconds") val audioSeconds: Double? = null,

    /**
     * Why the recording is being held for a human. Set at capture when the audio does not
     * fit the call, or later when the server refuses the file; the row moves to
     * [SyncState.NEEDS_REVIEW] once the call itself has been posted.
     */
    @Json(name = "review_reason") val reviewReason: String? = null,

    @Json(name = "state") val state: SyncState = SyncState.PENDING_CALL_RECORD,
    @Json(name = "attempts") val attempts: Int = 0,
    @Json(name = "last_error") val lastError: String? = null,
    @Json(name = "recording_url") val recordingUrl: String? = null,
    @Json(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    val hasRecording: Boolean get() = !localFilePath.isNullOrBlank()

    val isOutstanding: Boolean get() = state == SyncState.PENDING_CALL_RECORD || state == SyncState.PENDING_UPLOAD

    /** Waiting on a person, not on the network. */
    val needsReview: Boolean get() = state == SyncState.NEEDS_REVIEW

    /** Held back at capture and not yet posted: the call will go up, the file will wait. */
    val recordingHeld: Boolean get() = reviewReason != null && state == SyncState.PENDING_CALL_RECORD
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

    /**
     * True if this call log entry has already been turned into a CRM record.
     *
     * Matched on the row id *and* its timestamp, so a row an OEM has re-dated to record a
     * fresh call counts as the new call it is. A row stored before the timestamp existed
     * carries zero and still matches on the id alone.
     */
    fun existsForCall(callLogId: Long, callLogDate: Long): Boolean = items.value.any {
        CallWatermark.isSameCall(it.callLogId, it.callLogDate, callLogId, callLogDate)
    }

    fun outstanding(): List<PendingCall> = items.value.filter { it.isOutstanding }

    fun needingReview(): List<PendingCall> = items.value.filter { it.needsReview }

    /** Outstanding calls belonging to this rep. Rows with no owner predate the field. */
    fun outstandingFor(userId: String?): List<PendingCall> =
        items.value.filter { it.isOutstanding && (it.ownerUserId == null || it.ownerUserId == userId) }

    fun byId(id: String): PendingCall? = items.value.firstOrNull { it.id == id }

    /**
     * Drops long-since-delivered rows; their local files are already gone.
     *
     * Returns the (row id, date) of every call let go, so the caller can hand them to the
     * dismissed list. The reconcile re-reads a trailing window of the call log that is now
     * wider than this retention, and a delivered call that had simply been forgotten would
     * be posted a second time.
     */
    suspend fun pruneDelivered(olderThanMillis: Long): List<Pair<Long, Long>> {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        val pruned = mutableListOf<Pair<Long, Long>>()
        mutate { current ->
            current.filterNot { row ->
                val gone = row.state == SyncState.DONE && row.updatedAt < cutoff
                if (gone) pruned += row.callLogId to row.callLogDate
                gone
            }
        }
        return pruned
    }

    private companion object {
        /**
         * Raised from 200 when the call-log window grew to three days: a busy rep's week
         * of calls must fit, or rows drop off the end before being remembered as delivered.
         */
        const val MAX_ENTRIES = 600
    }
}
