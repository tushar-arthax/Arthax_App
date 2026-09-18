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

/** One call-log row that was checked against the CRM and turned out not to be a lead. */
@JsonClass(generateAdapter = true)
data class SeenCall(
    @Json(name = "call_log_id") val callLogId: Long,
    @Json(name = "call_log_date") val callLogDate: Long,
    @Json(name = "seen_at") val seenAt: Long = System.currentTimeMillis(),
)

/**
 * Call-log rows already examined and dismissed.
 *
 * The reconcile no longer trusts a single high-water mark to mean "everything before this is
 * handled". It re-reads a trailing window of the call log on every pass, because a row can
 * turn up late, out of order, or re-dated by the phone, and a watermark that has already
 * moved past it would never look again. Rows that became CRM activity are recognised by the
 * pending queue; this remembers the other kind, so re-reading the window does not mean
 * asking the server about the same non-lead numbers over and over.
 *
 * It records *rows*, not numbers, and that distinction is deliberate. "This particular call
 * was not to a lead" stays true forever, so it can be remembered safely. "This number is not
 * a lead" does not — the number may be added to the CRM a minute later — which is why lead
 * matching itself is always live.
 */
@Singleton
class SeenCallStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) : JsonListStore<SeenCall>(
    file = File(context.filesDir, "arthax_seen_calls.json"),
    moshi = moshi,
    itemClass = SeenCall::class.java,
    maxItems = MAX_ENTRIES,
) {

    fun contains(callLogId: Long, callLogDate: Long): Boolean = items.value.any {
        CallWatermark.isSameCall(it.callLogId, it.callLogDate, callLogId, callLogDate)
    }

    /** Newest first, and bounded, so the file cannot grow without limit. */
    suspend fun remember(callLogId: Long, callLogDate: Long) {
        if (contains(callLogId, callLogDate)) return
        add(SeenCall(callLogId, callLogDate))
    }

    suspend fun rememberAll(rows: List<Pair<Long, Long>>) {
        val fresh = rows.filterNot { (id, date) -> contains(id, date) }
        if (fresh.isEmpty()) return
        mutate { current ->
            (fresh.map { (id, date) -> SeenCall(id, date) } + current).take(MAX_ENTRIES)
        }
    }

    private companion object {
        /**
         * Comfortably more rows than the look-back window can hold, so a dismissed call is
         * never forgotten while it is still being re-read. Each row is a few dozen bytes.
         */
        const val MAX_ENTRIES = 2_000
    }
}
