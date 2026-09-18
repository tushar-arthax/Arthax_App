package com.example.arthax.data.local.store

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The counters behind one heartbeat. Timestamps are epoch millis, zero for "never". */
@JsonClass(generateAdapter = true)
data class SyncHealthState(
    @Json(name = "last_heartbeat_at") val lastHeartbeatAt: Long = 0L,
    @Json(name = "observer_last_tick_at") val observerLastTickAt: Long = 0L,
    @Json(name = "last_call_posted_at") val lastCallPostedAt: Long = 0L,
    @Json(name = "last_upload_at") val lastUploadAt: Long = 0L,
    /** When each call-log row was first seen by a pass; trimmed to the last day on write. */
    @Json(name = "calls_seen") val callsSeen: List<Long> = emptyList(),
    @Json(name = "calls_posted") val callsPosted: List<Long> = emptyList(),
    @Json(name = "errors") val errors: List<Long> = emptyList(),
    @Json(name = "last_error") val lastError: String? = null,
    /** Set by a 402 on upload; uploads wait until this passes. */
    @Json(name = "upload_blocked_until") val uploadBlockedUntil: Long = 0L,
) {
    fun callsSeen24h(now: Long): Int = callsSeen.count { it > now - DAY_MILLIS }
    fun callsPosted24h(now: Long): Int = callsPosted.count { it > now - DAY_MILLIS }
    fun errors24h(now: Long): Int = errors.count { it > now - DAY_MILLIS }
    fun isUploadBlocked(now: Long): Boolean = uploadBlockedUntil > now

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * What the device knows about its own health, for the hourly heartbeat.
 *
 * A phone that stops syncing is silent: the CRM just sees no calls, which looks exactly
 * like a rep who made none. These counters are what turn that silence into a row on the
 * fleet dashboard with a reason on it — the observer stopped ticking, uploads are stuck,
 * a permission was revoked.
 *
 * Every write is small and fire-and-forget from the caller's point of view; a failure to
 * persist is a logcat line, never something that can interrupt a call being delivered.
 */
@Singleton
class SyncHealthStore(
    private val file: File,
    moshi: Moshi,
) {

    @Inject
    constructor(@ApplicationContext context: Context, moshi: Moshi) :
        this(File(context.filesDir, FILE_NAME), moshi)

    private val adapter: JsonAdapter<SyncHealthState> = moshi.adapter(SyncHealthState::class.java)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SyncHealthState())
    val state: StateFlow<SyncHealthState> = _state.asStateFlow()

    val current: SyncHealthState get() = _state.value

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _state.value = runCatching {
                if (file.exists()) adapter.fromJson(file.readText()) else null
            }.onFailure {
                Log.e(TAG, "Sync health state unreadable, starting empty", it)
                runCatching { file.delete() }
            }.getOrNull() ?: SyncHealthState()
        }
    }

    suspend fun recordObserverTick(at: Long = System.currentTimeMillis()) =
        update { it.copy(observerLastTickAt = at) }

    suspend fun recordCallsSeen(count: Int, at: Long = System.currentTimeMillis()) {
        if (count <= 0) return
        update { it.copy(callsSeen = it.callsSeen + List(count) { at }) }
    }

    suspend fun recordCallPosted(at: Long = System.currentTimeMillis()) =
        update { it.copy(lastCallPostedAt = at, callsPosted = it.callsPosted + at) }

    suspend fun recordUpload(at: Long = System.currentTimeMillis()) =
        update { it.copy(lastUploadAt = at) }

    suspend fun recordError(message: String, at: Long = System.currentTimeMillis()) =
        update { it.copy(errors = it.errors + at, lastError = message.take(MAX_ERROR_LENGTH)) }

    suspend fun setUploadBlockedUntil(until: Long) = update { it.copy(uploadBlockedUntil = until) }

    suspend fun markHeartbeatSent(at: Long = System.currentTimeMillis()) =
        update { it.copy(lastHeartbeatAt = at) }

    private suspend fun update(transform: (SyncHealthState) -> SyncHealthState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val cutoff = now - RETENTION_MILLIS
            // Trimmed on every write so the file stays a few hundred bytes however busy the
            // phone is; only the last day is ever reported anyway.
            val next = transform(_state.value).let {
                it.copy(
                    callsSeen = it.callsSeen.filter { t -> t > cutoff }.takeLast(MAX_EVENTS),
                    callsPosted = it.callsPosted.filter { t -> t > cutoff }.takeLast(MAX_EVENTS),
                    errors = it.errors.filter { t -> t > cutoff }.takeLast(MAX_EVENTS),
                )
            }
            _state.value = next
            runCatching {
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(adapter.toJson(next))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.e(TAG, "Could not persist sync health", it) }
            Unit
        }
    }

    private companion object {
        const val TAG = "SyncHealthStore"
        const val FILE_NAME = "arthax_sync_health.json"
        const val RETENTION_MILLIS = 25L * 60 * 60 * 1000
        const val MAX_EVENTS = 2_000
        const val MAX_ERROR_LENGTH = 300
    }
}
