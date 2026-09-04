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

/** One remembered answer to "does this number belong to a lead?". */
@JsonClass(generateAdapter = true)
data class CachedLookup(
    @Json(name = "k") val key: String,
    /** Null means a confirmed non-lead — a negative result worth remembering. */
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
 * A small, bounded memory of which numbers are leads.
 *
 * This deliberately does **not** hold the lead book. An org with a million leads cannot have
 * its directory shipped to a phone, and trying would be slow to sync, heavy on storage and
 * stale the moment it finished. Instead the server answers the question one number at a
 * time, and this remembers the answers so the same number is never asked about twice.
 *
 * The working set is what matters: a rep calls the same few hundred people, so a cache of a
 * thousand entries covers essentially every call after the first, no matter how large the
 * CRM is behind it.
 *
 * Negative results are cached too, and matter more than the positive ones — without them
 * every call to the rep's spouse, bank or courier would hit the API again. They expire
 * sooner, because a number that is not a lead today can be imported as one tomorrow.
 */
@Singleton
class LeadLookupCache @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
) {

    enum class Verdict { LEAD, NOT_A_LEAD, UNKNOWN }

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

    val leadCount: Int get() = synchronized(entries) { entries.values.count { it.isLead } }

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
                loaded.forEach { entries[it.key] = it }
            }
        }
    }

    /**
     * What we already know about this number, without touching the network.
     *
     * [Verdict.UNKNOWN] covers both "never seen" and "seen too long ago to trust", so the
     * caller treats a stale answer exactly like no answer.
     */
    fun peek(rawNumber: String?): Hit {
        val key = PhoneNumbers.matchKey(rawNumber)
        if (key.isEmpty()) return Hit(Verdict.UNKNOWN)

        val entry = synchronized(entries) { entries[key] } ?: return Hit(Verdict.UNKNOWN)

        val age = System.currentTimeMillis() - entry.resolvedAt
        val ttl = if (entry.isLead) POSITIVE_TTL_MILLIS else NEGATIVE_TTL_MILLIS
        if (age > ttl) return Hit(Verdict.UNKNOWN)

        return if (entry.isLead) {
            Hit(Verdict.LEAD, entry.leadId, entry.leadName)
        } else {
            Hit(Verdict.NOT_A_LEAD)
        }
    }

    suspend fun rememberLead(rawNumber: String?, leadId: String, leadName: String) =
        put(rawNumber, CachedLookup(PhoneNumbers.matchKey(rawNumber), leadId, leadName))

    suspend fun rememberNotALead(rawNumber: String?) =
        put(rawNumber, CachedLookup(PhoneNumbers.matchKey(rawNumber)))

    /**
     * Seeds the cache from leads already on screen.
     *
     * Free accuracy: the rep scrolls their list, so by the time they call anyone the answer
     * is usually already here and the call needs no network at all.
     */
    suspend fun seed(leads: List<Pair<String, Pair<String, String>>>) {
        if (leads.isEmpty()) return
        mutex.withLock {
            synchronized(entries) {
                leads.forEach { (number, lead) ->
                    val key = PhoneNumbers.matchKey(number)
                    if (key.isNotEmpty()) {
                        entries[key] = CachedLookup(key, lead.first, lead.second)
                    }
                }
            }
        }
        persist()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(entries) { entries.clear() }
            runCatching { file.delete() }
            Unit
        }
    }

    private suspend fun put(rawNumber: String?, entry: CachedLookup) {
        val key = entry.key
        if (key.isEmpty()) return
        mutex.withLock { synchronized(entries) { entries[key] = entry } }
        persist()
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

        /** A lead stays a lead; re-checked weekly in case it was reassigned or deleted. */
        val POSITIVE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Shorter, because today's stranger can be tomorrow's imported lead. */
        val NEGATIVE_TTL_MILLIS = 12L * 60 * 60 * 1000
    }
}
