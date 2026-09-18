package com.example.arthax.data.local.store

import android.content.Context
import android.util.Log
import com.example.arthax.core.PhoneNumbers
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One number that was a lead the last time the CRM was asked. */
@JsonClass(generateAdapter = true)
data class CachedLookup(
    @Json(name = "k") val key: String,
    /**
     * Nullable only because older files on existing installs recorded confirmed non-leads
     * this way. Those rows are dropped on load; nothing writes a null id any more.
     */
    @Json(name = "i") val leadId: String? = null,
    @Json(name = "n") val leadName: String? = null,
    @Json(name = "t") val resolvedAt: Long = System.currentTimeMillis(),
) {
    val isLead: Boolean get() = leadId != null
}

@JsonClass(generateAdapter = true)
internal data class LookupCacheFile(
    @Json(name = "entries") val entries: List<CachedLookup> = emptyList(),
)

/**
 * The last known answer to "does this number belong to a lead?", kept for when the CRM
 * cannot be reached.
 *
 * It is **not** consulted while there is a connection. Every call is matched against the
 * live CRM, so a lead added a minute ago is found on the very next call and a lead deleted
 * in the CRM stops being matched immediately. That is the whole point: a remembered answer
 * is a wrong answer waiting to happen, and the two ways it went wrong in the field were
 * both silent — a number called before it was added stayed invisible for hours afterwards,
 * and a deleted lead would have kept collecting calls.
 *
 * What is left is a small, bounded fallback for the one case where a stale answer beats no
 * answer at all: the phone is offline when a call ends. A recording is perishable — OEM
 * recorders prune their own folders — so capturing it against the last known lead is worth
 * far more than waiting for a network that may not come back before the file is gone.
 *
 * Non-leads are never remembered. There is nothing to preserve: with no connection the call
 * is held and re-checked rather than being dropped on a guess.
 */
@Singleton
class LeadLookupCache @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) {

    enum class Verdict { LEAD, UNKNOWN }

    data class Hit(val verdict: Verdict, val leadId: String? = null, val leadName: String? = null)

    private val file = File(context.filesDir, "arthax_lead_lookup_cache.json")
    private val adapter: JsonAdapter<LookupCacheFile> = moshi.adapter(LookupCacheFile::class.java)
    private val mutex = Mutex()

    /**
     * Access-ordered so eviction drops the number that has gone longest without being
     * involved in a call, which is exactly the one least likely to come up again.
     */
    private val entries = object : LinkedHashMap<String, CachedLookup>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLookup>) =
            size > MAX_ENTRIES
    }

    val size: Int get() = synchronized(entries) { entries.size }

    val leadCount: Int get() = size

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = runCatching {
                if (file.exists()) adapter.fromJson(file.readText())?.entries else null
            }.onFailure {
                Log.e(TAG, "Lookup cache unreadable, starting empty", it)
                runCatching { file.delete() }
            }.getOrNull().orEmpty()

            synchronized(entries) {
                entries.clear()
                // Leads only. A file written by an older build may still hold remembered
                // non-leads, and those must not come back to life.
                loaded.filter { it.isLead }.forEach { entries[it.key] = it }
            }
        }
    }

    /**
     * The last known answer for this number, without touching the network.
     *
     * [Verdict.UNKNOWN] covers "never seen" and "seen too long ago to trust" alike, so the
     * caller treats a stale answer exactly like no answer.
     */
    fun peek(rawNumber: String?): Hit {
        val key = PhoneNumbers.matchKey(rawNumber)
        if (key.isEmpty()) return Hit(Verdict.UNKNOWN)

        val entry = synchronized(entries) { entries[key] } ?: return Hit(Verdict.UNKNOWN)
        if (!entry.isLead) return Hit(Verdict.UNKNOWN)
        if (System.currentTimeMillis() - entry.resolvedAt > FALLBACK_TTL_MILLIS) {
            return Hit(Verdict.UNKNOWN)
        }

        return Hit(Verdict.LEAD, entry.leadId, entry.leadName)
    }

    suspend fun rememberLead(rawNumber: String?, leadId: String, leadName: String) {
        val key = PhoneNumbers.matchKey(rawNumber)
        if (key.isEmpty()) return
        mutex.withLock {
            synchronized(entries) { entries[key] = CachedLookup(key, leadId, leadName) }
        }
        persist()
    }

    /**
     * Drops whatever was remembered for this number.
     *
     * Called the moment the CRM says a number is not a lead, so a lead that has been deleted
     * or reassigned cannot go on being matched from the offline fallback.
     */
    suspend fun forget(rawNumber: String?) {
        val key = PhoneNumbers.matchKey(rawNumber)
        if (key.isEmpty()) return

        val removed = mutex.withLock {
            synchronized(entries) { entries.remove(key) != null }
        }
        if (removed) persist()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(entries) { entries.clear() }
            runCatching { file.delete() }
            Unit
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val snapshot = synchronized(entries) { entries.values.toList() }
        runCatching {
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(adapter.toJson(LookupCacheFile(snapshot)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e(TAG, "Could not persist the lookup cache", it) }
        Unit
    }

    private companion object {
        const val TAG = "LeadLookupCache"

        /** ~1000 numbers is far more than a rep's real working set, at roughly 80 KB. */
        const val MAX_ENTRIES = 1_000

        /**
         * How long an offline fallback stays usable. A week: long enough to cover any
         * realistic stretch without signal, and it is only ever reached when the CRM itself
         * could not be asked.
         */
        val FALLBACK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
