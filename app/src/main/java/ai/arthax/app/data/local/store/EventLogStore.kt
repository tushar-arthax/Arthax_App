package ai.arthax.app.data.local.store

import android.content.Context
import ai.arthax.app.domain.model.LogLevel
import ai.arthax.app.domain.model.LogStage
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One line in the on-device activity log. */
@JsonClass(generateAdapter = true)
data class LogEntry(
    @Json(name = "timestamp") val timestamp: Long,
    @Json(name = "level") val level: LogLevel,
    @Json(name = "stage") val stage: LogStage,
    @Json(name = "message") val message: String,
    @Json(name = "lead_id") val leadId: String? = null,
    @Json(name = "lead_name") val leadName: String? = null,
    @Json(name = "detail") val detail: String? = null,
) {
    /** Stable enough to key a list on; two entries in the same millisecond are vanishingly rare. */
    val id: String get() = "$timestamp-${message.hashCode()}"
}

/**
 * The rep-visible activity log, newest first.
 *
 * Deliberately local and temporary: it exists so a rep in the field, or support reading
 * over their shoulder, can see exactly what the app did and why an upload failed. Once
 * the pipeline is proven this can be dropped or shipped to the server instead.
 */
@Singleton
class EventLogStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) : JsonListStore<LogEntry>(
    file = File(context.filesDir, "arthax_activity_log.json"),
    moshi = moshi,
    itemClass = LogEntry::class.java,
    maxItems = MAX_ENTRIES,
) {
    private companion object {
        /** Bounded so a long-lived install cannot grow the file without limit. */
        const val MAX_ENTRIES = 500
    }
}
