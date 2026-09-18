package ai.arthax.app.data.local.store

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A list of records persisted as a JSON file in app-private storage.
 *
 * This replaces what used to be a Room database. For two small collections — an activity
 * log and a short upload queue — a table, DAOs, type converters, a schema version and an
 * annotation processor were far more machinery than the job needs. A JSON file has no
 * migrations to get wrong and can be read straight off the device while debugging.
 *
 * Writes go through a Mutex and land via a temp file plus rename, so a process death
 * mid-write leaves the previous good version rather than a truncated file.
 *
 * It is not a database: the whole list is held in memory and rewritten on every change.
 * That is fine at these sizes (hundreds of rows) and is the reason both stores stay bounded.
 */
abstract class JsonListStore<T : Any>(
    private val file: File,
    moshi: Moshi,
    itemClass: Class<T>,
    private val maxItems: Int,
) {

    private val adapter: JsonAdapter<List<T>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, itemClass))

    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    /** Loads from disk into memory. Call once at startup before anything reads [items]. */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = readFromDisk()
        }
    }

    /** Newest-first ordering is applied by subclasses that care; this is raw order. */
    suspend fun add(item: T) = mutate { current -> (listOf(item) + current).take(maxItems) }

    suspend fun replaceAll(items: List<T>) = mutate { items.take(maxItems) }

    suspend fun clear() = mutate { emptyList() }

    /**
     * Read-modify-write under the lock. The whole point of routing every change through
     * here is that two coroutines updating the queue concurrently cannot lose an entry.
     */
    protected suspend fun mutate(block: (List<T>) -> List<T>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = block(_items.value)
            _items.value = updated
            writeToDisk(updated)
        }
    }

    private fun readFromDisk(): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching { adapter.fromJson(file.readText()).orEmpty() }
            .onFailure {
                // A corrupt file must not brick the app on every launch. Move it aside so
                // it can still be inspected, and carry on from empty.
                Log.e(TAG, "Corrupt store at ${file.name}, quarantining", it)
                runCatching { file.renameTo(File(file.absolutePath + ".corrupt")) }
            }
            .getOrDefault(emptyList())
    }

    private fun writeToDisk(items: List<T>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(adapter.toJson(items))
            if (!tmp.renameTo(file)) {
                // renameTo can fail if the target exists on some filesystems.
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e(TAG, "Could not persist ${file.name}", it) }
    }

    private companion object {
        const val TAG = "JsonListStore"
    }
}
