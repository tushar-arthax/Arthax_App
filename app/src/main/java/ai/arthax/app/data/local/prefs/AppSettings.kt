package ai.arthax.app.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.arthax.app.call.ClickToCallIntent
import ai.arthax.app.domain.model.CallMode
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
        val consent: Consent,
    ) {
        val hasRecordingsFolder: Boolean get() = !recordingsTreeUri.isNullOrBlank()

        /** Call tracking only ever runs once the rep has read the disclosure and accepted it. */
        val callTrackingAllowed: Boolean get() = consent == Consent.ACCEPTED
    }

    /**
     * The rep's answer to the prominent disclosure shown before any call permission is
     * requested. Play's policy asks for it, and it is the right thing regardless: an app
     * that watches every call should say so in its own words and take no for an answer.
     *
     * DECLINED leaves the app usable for viewing and dialling leads; nothing is read from
     * the call log and nothing is uploaded until the rep changes their mind in Settings.
     */
    enum class Consent { UNDECIDED, ACCEPTED, DECLINED }

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
            consent = p[KEY_CONSENT]?.let { raw ->
                runCatching { Consent.valueOf(raw) }.getOrDefault(Consent.UNDECIDED)
            } ?: Consent.UNDECIDED,
        )
    }

    val consent: Flow<Consent> = snapshot.map { it.consent }

    suspend fun setConsent(consent: Consent) = context.dataStore.edit { p ->
        p[KEY_CONSENT] = consent.name
    }

    /**
     * The lead the rep last tapped CALL on, or null when there is none outstanding.
     *
     * Persisted rather than held in memory because the process is routinely killed between
     * the tap and the call log row appearing — that is the whole reason the watcher exists.
     */
    val clickToCall: Flow<ClickToCallIntent?> = context.dataStore.data.map { p ->
        val leadId = p[KEY_C2C_LEAD_ID] ?: return@map null
        val at = p[KEY_C2C_AT] ?: return@map null
        ClickToCallIntent(
            leadId = leadId,
            leadName = p[KEY_C2C_LEAD_NAME].orEmpty(),
            phone = p[KEY_C2C_PHONE].orEmpty(),
            at = at,
        )
    }

    suspend fun setClickToCall(intent: ClickToCallIntent?) = context.dataStore.edit { p ->
        if (intent == null) {
            p.remove(KEY_C2C_LEAD_ID)
            p.remove(KEY_C2C_LEAD_NAME)
            p.remove(KEY_C2C_PHONE)
            p.remove(KEY_C2C_AT)
        } else {
            p[KEY_C2C_LEAD_ID] = intent.leadId
            p[KEY_C2C_LEAD_NAME] = intent.leadName
            p[KEY_C2C_PHONE] = intent.phone
            p[KEY_C2C_AT] = intent.at
        }
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
     * Highest call-log row id already turned into CRM activity.
     *
     * The companion to [lastProcessedCallAt], and not redundant. Call log rows are written
     * when a call ends but stamped with when it began, so the two orders disagree the moment
     * calls overlap — and a call that began before the previous one ended sits below the
     * timestamp watermark and would never be looked at again. Row ids only go up.
     *
     * Zero means "not armed yet"; the reconciler anchors it rather than sweeping the phone's
     * whole call history.
     */
    val lastProcessedCallId: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_CALL_ID] ?: 0L }

    suspend fun setLastProcessedCallId(id: Long) = context.dataStore.edit { p ->
        p[KEY_LAST_CALL_ID] = id
    }

    /**
     * The oldest call this install may ever look at.
     *
     * The reconcile re-reads a trailing window of the call log rather than trusting the
     * watermark alone, because rows can arrive late or out of order and a watermark that has
     * moved past them would never look again. This is the hard floor under that window: it
     * is set once, and it is what stops the widened read from sweeping up the phone's call
     * history and posting weeks of old calls as though they had all just happened.
     */
    val callTrackingFloor: Flow<Long> = context.dataStore.data.map { it[KEY_TRACKING_FLOOR] ?: 0L }

    suspend fun setCallTrackingFloor(millis: Long) = context.dataStore.edit { p ->
        p[KEY_TRACKING_FLOOR] = millis
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
    suspend fun resetCallWatermark() = context.dataStore.edit { p ->
        p.remove(KEY_LAST_CALL_AT)
        p.remove(KEY_LAST_CALL_ID)
        p.remove(KEY_TRACKING_FLOOR)
    }

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
        private val KEY_LAST_CALL_ID = longPreferencesKey("last_processed_call_id")
        private val KEY_TRACKING_FLOOR = longPreferencesKey("call_tracking_floor")
        private val KEY_CALL_LOG_WARNED = booleanPreferencesKey("call_log_warned")
        private val KEY_CALL_LOG_REQUESTED = booleanPreferencesKey("call_log_requested")
        private val KEY_CONSENT = stringPreferencesKey("call_tracking_consent")
        private val KEY_C2C_LEAD_ID = stringPreferencesKey("click_to_call_lead_id")
        private val KEY_C2C_LEAD_NAME = stringPreferencesKey("click_to_call_lead_name")
        private val KEY_C2C_PHONE = stringPreferencesKey("click_to_call_phone")
        private val KEY_C2C_AT = longPreferencesKey("click_to_call_at")

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
