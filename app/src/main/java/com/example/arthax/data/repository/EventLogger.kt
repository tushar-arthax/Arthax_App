package com.example.arthax.data.repository

import android.util.Log
import com.example.arthax.data.local.store.EventLogStore
import com.example.arthax.data.local.store.LogEntry
import com.example.arthax.di.ApplicationScope
import com.example.arthax.domain.model.LogLevel
import com.example.arthax.domain.model.LogStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single write path for the rep-visible activity trail.
 *
 * Every stage of the pipeline logs through here, which is what makes the Activity screen a
 * real diagnostic tool rather than decoration: when a rep says "the call did not upload",
 * the answer is on their screen, in order, with the reason and the server's own words.
 *
 * Writes are fire-and-forget on an app-scoped coroutine so logging can never block a
 * broadcast receiver or stall the recording scan.
 */
@Singleton
class EventLogger @Inject constructor(
    private val store: EventLogStore,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    val entries: StateFlow<List<LogEntry>> = store.items

    suspend fun load() = store.load()

    suspend fun clear() = store.clear()

    fun info(stage: LogStage, message: String, leadId: String? = null, leadName: String? = null, detail: String? = null) =
        write(LogLevel.INFO, stage, message, leadId, leadName, detail)

    fun success(stage: LogStage, message: String, leadId: String? = null, leadName: String? = null, detail: String? = null) =
        write(LogLevel.SUCCESS, stage, message, leadId, leadName, detail)

    fun warn(stage: LogStage, message: String, leadId: String? = null, leadName: String? = null, detail: String? = null) =
        write(LogLevel.WARN, stage, message, leadId, leadName, detail)

    fun error(stage: LogStage, message: String, leadId: String? = null, leadName: String? = null, detail: String? = null) =
        write(LogLevel.ERROR, stage, message, leadId, leadName, detail)

    private fun write(
        level: LogLevel,
        stage: LogStage,
        message: String,
        leadId: String?,
        leadName: String?,
        detail: String?,
    ) {
        // Mirrored to logcat so `adb logcat -s Arthax` gives the same trace during development.
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, "[$stage] $message ${detail.orEmpty()}")
            LogLevel.WARN -> Log.w(TAG, "[$stage] $message ${detail.orEmpty()}")
            else -> Log.i(TAG, "[$stage] $message")
        }

        appScope.launch {
            runCatching {
                store.add(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = level,
                        stage = stage,
                        message = message,
                        leadId = leadId,
                        leadName = leadName,
                        detail = detail,
                    ),
                )
            }
        }
    }

    companion object {
        const val TAG = "Arthax"
    }
}
