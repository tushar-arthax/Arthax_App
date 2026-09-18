package com.example.arthax.data.local.store

import android.content.Context
import com.example.arthax.call.CallWatermark
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A call the CRM did not recognise *yet*.
 *
 * Everything needed to deliver the call later is carried here, so that when the number does
 * turn up as a lead the call can be posted — and its recording hunted for — exactly as if
 * it had been matched live. The number is stored because it is the whole point: without
 * it there is nothing to re-ask about.
 */
@JsonClass(generateAdapter = true)
data class UnmatchedCall(
    @Json(name = "call_log_id") val callLogId: Long,
    @Json(name = "call_log_date") val callLogDate: Long,
    @Json(name = "phone") val phone: String,
    /** "outbound" or "inbound", from the call log. */
    @Json(name = "direction") val direction: String,
    @Json(name = "connected") val connected: Boolean,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    @Json(name = "started_at") val startedAt: Long,
    @Json(name = "ended_at") val endedAt: Long,
    /** When the following call began, so a late recording hunt cannot adopt its audio. */
    @Json(name = "next_call_started_at") val nextCallStartedAt: Long? = null,
    @Json(name = "first_seen_at") val firstSeenAt: Long = System.currentTimeMillis(),
    /** When the server was last actually asked about this number. Zero until it has been. */
    @Json(name = "last_checked_at") val lastCheckedAt: Long = 0L,
    /** How many times the server has said no so far. */
    @Json(name = "checks") val checks: Int = 0,
) {
    /**
     * Whether the server should be asked again now. Offline answers are free and happen on
     * every pass; a network round trip per row is rationed to once per [recheckMillis].
     */
    fun isDueForServerCheck(now: Long, recheckMillis: Long): Boolean =
        lastCheckedAt == 0L || now - lastCheckedAt >= recheckMillis

    /** Past retention the call is let go: a lead that old is not coming. */
    fun isExpired(now: Long, retentionMillis: Long): Boolean =
        now - firstSeenAt >= retentionMillis
}

/**
 * Calls waiting for a matching lead.
 *
 * The reconcile used to drop a call the moment the CRM said "not a lead", and that verdict
 * was wrong more often than it looked: the lead was added an hour after the call, the
 * number was on a colleague's lead the rep's search could not see, an import landed the
 * next morning. Every one of those calls — and its recording — was gone for good.
 *
 * Now a "no" parks the call here instead. Each reconcile pass re-asks about the rows that
 * are due, and the moment a number resolves the call is delivered through the same path as
 * a live one. Rows age out after the configured retention, and the store is bounded so a
 * rep whose personal calls are all non-leads cannot grow it without limit.
 */
@Singleton
class UnmatchedCallStore(
    file: File,
    moshi: Moshi,
) : JsonListStore<UnmatchedCall>(
    file = file,
    moshi = moshi,
    itemClass = UnmatchedCall::class.java,
    maxItems = MAX_ENTRIES,
) {

    @Inject
    constructor(@ApplicationContext context: Context, moshi: Moshi) :
        this(File(context.filesDir, "arthax_unmatched_calls.json"), moshi)

    val count: Int get() = items.value.size

    fun contains(callLogId: Long, callLogDate: Long): Boolean = items.value.any {
        CallWatermark.isSameCall(it.callLogId, it.callLogDate, callLogId, callLogDate)
    }

    /** Newest first, like the other stores; a duplicate row is ignored. */
    suspend fun remember(call: UnmatchedCall) {
        if (contains(call.callLogId, call.callLogDate)) return
        add(call)
    }

    /** Records that the server was asked and still said no. */
    suspend fun markChecked(callLogId: Long, callLogDate: Long, at: Long = System.currentTimeMillis()) =
        mutate { current ->
            current.map {
                if (CallWatermark.isSameCall(it.callLogId, it.callLogDate, callLogId, callLogDate)) {
                    it.copy(lastCheckedAt = at, checks = it.checks + 1)
                } else {
                    it
                }
            }
        }

    suspend fun remove(callLogId: Long, callLogDate: Long) = mutate { current ->
        current.filterNot { CallWatermark.isSameCall(it.callLogId, it.callLogDate, callLogId, callLogDate) }
    }

    /** Drops rows older than retention. Returns how many were let go, for the log. */
    suspend fun pruneExpired(retentionMillis: Long, now: Long = System.currentTimeMillis()): Int {
        val before = items.value.size
        mutate { current -> current.filterNot { it.isExpired(now, retentionMillis) } }
        return before - items.value.size
    }

    /** Oldest first, so a call that has waited longest is re-asked about first. */
    fun outstanding(): List<UnmatchedCall> = items.value.sortedBy { it.startedAt }

    private companion object {
        /**
         * Generous for a week of one rep's non-lead calls, and each row is under 300 bytes.
         * Past this the oldest rows fall off, which is the same thing retention would do.
         */
        const val MAX_ENTRIES = 500
    }
}
