package com.example.arthax.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.arthax.domain.model.CallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "arthax_settings")

/** Device-local configuration. Nothing secret lives here — see [SecureTokenStore] for that. */
class AppSettings(private val context: Context) {

    data class Snapshot(
        val recordingsTreeUri: String?,
        val callMode: CallMode,
        val onboardingComplete: Boolean,
        val scanTimeoutSeconds: Int,
        val batteryPromptShown: Boolean,
        val autostartPromptShown: Boolean,
    ) {
        val hasRecordingsFolder: Boolean get() = !recordingsTreeUri.isNullOrBlank()
    }

    val snapshot: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            recordingsTreeUri = p[KEY_TREE_URI],
            callMode = p[KEY_CALL_MODE]?.let { raw ->
                runCatching { CallMode.valueOf(raw) }.getOrDefault(CallMode.DIRECT)
            } ?: CallMode.DIRECT,
            onboardingComplete = p[KEY_ONBOARDING_DONE] ?: false,
            scanTimeoutSeconds = p[KEY_SCAN_TIMEOUT] ?: DEFAULT_SCAN_TIMEOUT_SECONDS,
            batteryPromptShown = p[KEY_BATTERY_PROMPT] ?: false,
            autostartPromptShown = p[KEY_AUTOSTART_PROMPT] ?: false,
        )
    }

    val recordingsTreeUri: Flow<String?> = context.dataStore.data.map { it[KEY_TREE_URI] }

    /**
     * Call-log timestamp up to which calls have already been turned into CRM activity.
     *
     * Zero means "never run": the first reconcile sets it to now rather than sweeping up the
     * phone's entire call history and posting months of old calls to the CRM.
     */
    val lastProcessedCallAt: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_CALL_AT] ?: 0L }

    suspend fun setLastProcessedCallAt(millis: Long) = context.dataStore.edit { p ->
        p[KEY_LAST_CALL_AT] = millis
    }

    /**
     * Whether the missing-call-log-permission warning has already been written.
     *
     * Without this the reconcile would repeat it on every run, including the fifteen-minute
     * one - burying the rest of the log and, worse, leaving a stale warning sitting at the
     * top long after the permission was actually granted.
     */
    val callLogWarned: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALL_LOG_WARNED] ?: false }

    suspend fun setCallLogWarned(warned: Boolean) = context.dataStore.edit { p ->
        p[KEY_CALL_LOG_WARNED] = warned
    }

    /** Set once the permission has actually been asked for, so a silent denial is detectable. */
    val callLogRequested: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALL_LOG_REQUESTED] ?: false }

    suspend fun setCallLogRequested(requested: Boolean) = context.dataStore.edit { p ->
        p[KEY_CALL_LOG_REQUESTED] = requested
    }

    /** Cleared on sign-out so the next rep does not inherit this one's position. */
    suspend fun resetCallWatermark() = context.dataStore.edit { p -> p.remove(KEY_LAST_CALL_AT) }

    suspend fun setRecordingsTreeUri(uri: String?) = context.dataStore.edit { p ->
        if (uri == null) p.remove(KEY_TREE_URI) else p[KEY_TREE_URI] = uri
    }

    suspend fun setCallMode(mode: CallMode) = context.dataStore.edit { p ->
        p[KEY_CALL_MODE] = mode.name
    }

    suspend fun setOnboardingComplete(complete: Boolean) = context.dataStore.edit { p ->
        p[KEY_ONBOARDING_DONE] = complete
    }

    suspend fun setScanTimeoutSeconds(seconds: Int) = context.dataStore.edit { p ->
        p[KEY_SCAN_TIMEOUT] = seconds.coerceIn(MIN_SCAN_TIMEOUT_SECONDS, MAX_SCAN_TIMEOUT_SECONDS)
    }

    suspend fun setBatteryPromptShown(shown: Boolean) = context.dataStore.edit { p ->
        p[KEY_BATTERY_PROMPT] = shown
    }

    suspend fun setAutostartPromptShown(shown: Boolean) = context.dataStore.edit { p ->
        p[KEY_AUTOSTART_PROMPT] = shown
    }

    companion object {
        private val KEY_TREE_URI = stringPreferencesKey("recordings_tree_uri")
        private val KEY_CALL_MODE = stringPreferencesKey("call_mode")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        private val KEY_SCAN_TIMEOUT = intPreferencesKey("scan_timeout_seconds")
        private val KEY_BATTERY_PROMPT = booleanPreferencesKey("battery_prompt_shown")
        private val KEY_AUTOSTART_PROMPT = booleanPreferencesKey("autostart_prompt_shown")
        private val KEY_LAST_CALL_AT = longPreferencesKey("last_processed_call_at")
        private val KEY_CALL_LOG_WARNED = booleanPreferencesKey("call_log_warned")
        private val KEY_CALL_LOG_REQUESTED = booleanPreferencesKey("call_log_requested")

        /**
         * How long the harvester keeps looking for the recording after hang-up.
         * Samsung and Xiaomi both finalise the container lazily; 60s covers the
         * slow cases without pinning an expedited worker for long.
         */
        const val DEFAULT_SCAN_TIMEOUT_SECONDS = 60
        const val MIN_SCAN_TIMEOUT_SECONDS = 15
        const val MAX_SCAN_TIMEOUT_SECONDS = 180
    }
}
