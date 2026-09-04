package com.example.arthax.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.arthax.domain.model.CallDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the phone's own call log.
 *
 * This is the app's source of truth for what happened on the line, for two reasons.
 *
 * First, completeness. A call can reach the phone without this app being involved at all —
 * an incoming call from a lead, or the rep dialling a lead straight from their contacts
 * while Arthax is closed. Telephony broadcasts alone cannot describe those; the call log
 * records every one of them.
 *
 * Second, accuracy. Android fires OFFHOOK when an outbound call starts *dialling*, not when
 * anyone answers, and exposes no "answered" signal. Only the log knows: DURATION is real
 * talk time and is 0 when the call was never picked up, and TYPE separates incoming from
 * outgoing and missed/rejected from answered.
 */
@Singleton
class CallLogReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Entry(
        val id: Long,
        val number: String,
        val type: Int,
        /** When the call began, per the platform. */
        val startedAt: Long,
        /** Talk time in seconds. Zero for anything nobody answered. */
        val durationSeconds: Int,
    ) {
        /** Best estimate of when the line was released — where a recording would be closed. */
        val endedAt: Long get() = startedAt + durationSeconds * 1_000L

        val direction: CallDirection
            get() = when (type) {
                CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTBOUND
                else -> CallDirection.INBOUND
            }

        /**
         * Whether anyone actually spoke. Missed and rejected calls report zero duration, but
         * both are checked explicitly rather than relying on that alone.
         */
        val connected: Boolean
            get() = durationSeconds > 0 &&
                type != CallLog.Calls.MISSED_TYPE &&
                type != CallLog.Calls.REJECTED_TYPE
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Calls that started after [sinceMillis], oldest first.
     *
     * Ordered ascending so the caller can walk forward and move its watermark as it goes;
     * if it is interrupted halfway the unprocessed tail is simply picked up next time.
     */
    fun entriesSince(sinceMillis: Long, limit: Int = DEFAULT_LIMIT): List<Entry> {
        if (!hasPermission()) return emptyList()

        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                ),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(sinceMillis.toString()),
                // Sort order only. A "LIMIT n" suffix here is rejected by the call log
                // provider with "Invalid token LIMIT" - it validates the clause rather than
                // passing it to SQLite. That threw on every read, and because the result was
                // swallowed it looked exactly like "no new calls": no call was ever detected.
                // The cap is applied while walking the cursor instead.
                "${CallLog.Calls.DATE} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            Entry(
                                id = cursor.getLong(idIdx),
                                number = cursor.getString(numberIdx).orEmpty(),
                                type = cursor.getInt(typeIdx),
                                startedAt = cursor.getLong(dateIdx),
                                durationSeconds = cursor.getInt(durationIdx).coerceAtLeast(0),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            // Never swallow this. A silent empty list here is indistinguishable from "no new
            // calls", which is exactly how a detection failure hides.
            Log.e(TAG, "Reading the call log failed", it)
        }.getOrDefault(emptyList())
    }

    /** Timestamp of the newest entry, used to set the watermark on a fresh install. */
    fun newestEntryAt(): Long = runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE),
            null,
            null,
            // Same reason as above: no LIMIT clause. Only the first row is read.
            "${CallLog.Calls.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.onFailure { Log.e(TAG, "Reading the newest call log entry failed", it) }.getOrDefault(0L)

    private companion object {
        const val TAG = "ArthaxCallLog"

        /** Generous, but bounded so a first run after a long gap cannot stall. */
        const val DEFAULT_LIMIT = 200
    }
}
