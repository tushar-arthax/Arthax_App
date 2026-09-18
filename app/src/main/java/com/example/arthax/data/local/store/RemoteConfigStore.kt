package com.example.arthax.data.local.store

import android.content.Context
import android.util.Log
import com.example.arthax.core.MobileConfig
import com.example.arthax.data.remote.dto.MobileConfigResponseDto
import com.squareup.moshi.JsonAdapter
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

/**
 * The last server config this device applied, kept as the raw response so the exact
 * document the server sent can be read off the device while debugging.
 *
 * [current] is synchronous and always answers: [MobileConfig.DEFAULTS] until the file has
 * been loaded or when there has never been a config. Nothing waits on the network to know
 * how far back to read the call log.
 */
@Singleton
class RemoteConfigStore(
    private val file: File,
    moshi: Moshi,
) {

    @Inject
    constructor(@ApplicationContext context: Context, moshi: Moshi) :
        this(File(context.filesDir, FILE_NAME), moshi)

    private val adapter: JsonAdapter<MobileConfigResponseDto> =
        moshi.adapter(MobileConfigResponseDto::class.java)
    private val mutex = Mutex()

    private val _config = MutableStateFlow(MobileConfig.DEFAULTS)
    val config: StateFlow<MobileConfig> = _config.asStateFlow()

    val current: MobileConfig get() = _config.value

    /** When the server was last asked, successfully. Zero when never. */
    @Volatile
    var lastFetchedAt: Long = 0L
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = runCatching {
                if (file.exists()) adapter.fromJson(file.readText()) else null
            }.onFailure {
                // A corrupt cache must not brick startup: defaults apply and the next
                // heartbeat fetches a fresh copy.
                Log.e(TAG, "Config cache unreadable, using defaults", it)
                runCatching { file.delete() }
            }.getOrNull()

            _config.value = loaded?.let(MobileConfig::from) ?: MobileConfig.DEFAULTS
            lastFetchedAt = if (loaded != null) file.lastModified() else 0L
        }
    }

    /** Applies a freshly fetched document and persists it for the next process. */
    suspend fun save(response: MobileConfigResponseDto) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _config.value = MobileConfig.from(response)
            runCatching {
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(adapter.toJson(response))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.e(TAG, "Could not persist the config cache", it) }
            lastFetchedAt = System.currentTimeMillis()
        }
    }

    /** A 304: the server confirmed what is cached is still current. */
    fun markFresh() {
        lastFetchedAt = System.currentTimeMillis()
    }

    private companion object {
        const val TAG = "RemoteConfigStore"
        const val FILE_NAME = "arthax_mobile_config.json"
    }
}
