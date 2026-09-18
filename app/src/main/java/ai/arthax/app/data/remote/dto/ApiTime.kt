package ai.arthax.app.data.remote.dto

import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parses and formats the API's timestamps.
 *
 * The server sends three different shapes for the same concept:
 *   2026-06-13T19:14:06.037203   (no zone, microseconds)
 *   2026-08-25T19:30:00          (no zone, no fraction)
 *   2026-09-02T16:37:14.308Z     (UTC, as the docs claim)
 *
 * The zone-less ones are the common case, and they are **not UTC** — measured against a
 * call created at 16:47 UTC, the server returned 22:17:23, exactly +05:30. So a naive
 * timestamp is Asia/Kolkata wall-clock time. Reading them as UTC would put every
 * "last called 6h ago" out by five and a half hours.
 *
 * Anything we *send* carries an explicit UTC offset, so the server never has to guess.
 */
object ApiTime {

    /**
     * Timezone the backend writes zone-less timestamps in. If the API is ever fixed to
     * emit proper UTC, or the server moves region, this constant is the only thing
     * that changes.
     */
    val SERVER_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val OUTGOING: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Epoch millis, or null for null/blank/unparseable input. Never throws. */
    fun parseOrNull(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // Explicit offset present ("...Z" or "+05:30") — trust it.
        runCatching { return Instant.parse(value).toEpochMilli() }

        // Zone-less: interpret as server wall-clock time.
        runCatching {
            return LocalDateTime.parse(value).atZone(SERVER_ZONE).toInstant().toEpochMilli()
        }

        Log.w(TAG, "Unparseable timestamp from API: $value")
        return null
    }

    /** Formats for sending. Always explicit UTC so the server cannot misread it. */
    fun format(epochMillis: Long): String = OUTGOING.format(Instant.ofEpochMilli(epochMillis))

    private const val TAG = "ApiTime"
}
