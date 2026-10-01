package ai.arthax.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.remote.api.ApiResult
import ai.arthax.app.call.CallLogReader
import ai.arthax.app.call.CallLogReconciler
import ai.arthax.app.data.local.store.LeadLookupCache
import ai.arthax.app.data.local.store.RemoteConfigStore
import ai.arthax.app.data.local.store.SyncHealthStore
import ai.arthax.app.data.local.store.UnmatchedCallStore
import ai.arthax.app.data.repository.AuthRepository
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.LogStage
import ai.arthax.app.push.PushRegistration
import ai.arthax.app.recording.RecordingFinder
import ai.arthax.app.recording.RecordingStorage
import ai.arthax.app.work.WorkScheduler
import ai.arthax.app.ui.common.AppPermissions
import ai.arthax.app.ui.common.DeviceSetup
import ai.arthax.app.ui.common.RecordingFolderHints
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val authRepository: AuthRepository,
    private val finder: RecordingFinder,
    private val storage: RecordingStorage,
    private val lookupCache: LeadLookupCache,
    private val unmatched: UnmatchedCallStore,
    private val configStore: RemoteConfigStore,
    private val health: SyncHealthStore,
    private val reconciler: CallLogReconciler,
    private val callLogReader: CallLogReader,
    private val workScheduler: WorkScheduler,
    private val push: PushRegistration,
    private val logger: EventLogger,
) : ViewModel() {

    data class UiState(
        val repName: String = "",
        val repPhone: String = "",
        val repEmail: String = "",
        val repRole: String = "",
        val callMode: CallMode = CallMode.DIRECT,
        val folderDisplayName: String? = null,
        val folderError: String? = null,
        val scanTimeoutSeconds: Int = AppSettings.DEFAULT_SCAN_TIMEOUT_SECONDS,
        val permissionsOk: Boolean = true,
        val batteryOptimised: Boolean = false,
        val needsAutostart: Boolean = false,
        val manufacturer: String = "",
        val heldBytes: Long = 0,
        val knownLeadNumbers: Int = 0,
        val cachedLookups: Int = 0,
        val callLogPermission: Boolean = false,
        val isCheckingCalls: Boolean = false,
        val lastCheckSummary: String? = null,
        val loggedOut: Boolean = false,
        val isLoggingOut: Boolean = false,
        val consent: AppSettings.Consent = AppSettings.Consent.UNDECIDED,
        /** Calls parked as "not a lead yet". A count only; the numbers stay on the device. */
        val waitingForLead: Int = 0,
        /** Set while a 402 has uploads on hold. */
        val uploadsPausedUntil: Long? = null,
        val folderHint: String = "",
        val folderPickerStart: Uri? = null,
        val configVersion: Int = 0,
        val lookbackHours: Int = 0,
        val notifyNewLeads: Boolean = true,
        val notifyReminders: Boolean = true,
        val notifyGeneral: Boolean = true,
        val pushState: PushRegistration.State = PushRegistration.State.PENDING,
        /** Which look the app wears. Dark is the default — see [AppSettings.ThemeMode]. */
        val themeMode: AppSettings.ThemeMode = AppSettings.ThemeMode.DARK,

        /** True while `GET /users/me` or the token re-send is in flight. */
        val isCheckingRemote: Boolean = false,
        /** Outcome of the last remote-calling check, shown in place under the section. */
        val remoteMessage: String? = null,
        val remoteMessageIsError: Boolean = false,
    ) {
        val callTrackingOn: Boolean get() = consent == AppSettings.Consent.ACCEPTED
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = settings.snapshot.first()
            val (name, error) = describeFolder(snapshot.recordingsTreeUri)
            val notify = settings.notificationPreferences.first()
            val theme = settings.themeMode.first()
            val pushState = runCatching { push.state(authRepository.isLoggedIn) }
                .getOrDefault(PushRegistration.State.NO_GOOGLE_SERVICES)

            _state.update {
                it.copy(
                    repName = authRepository.session?.fullName.orEmpty(),
                    repPhone = authRepository.session?.phone.orEmpty(),
                    repEmail = authRepository.session?.email.orEmpty(),
                    repRole = authRepository.session?.role.orEmpty(),
                    callMode = snapshot.callMode,
                    folderDisplayName = name,
                    folderError = error,
                    scanTimeoutSeconds = snapshot.scanTimeoutSeconds,
                    permissionsOk = AppPermissions.missingRequired(context).isEmpty(),
                    batteryOptimised = DeviceSetup.isBatteryOptimised(context),
                    needsAutostart = DeviceSetup.needsAutostartGrant(),
                    manufacturer = DeviceSetup.manufacturerLabel(),
                    heldBytes = storage.totalBytesHeld(),
                    knownLeadNumbers = lookupCache.leadCount,
                    cachedLookups = lookupCache.size,
                    callLogPermission = callLogReader.hasPermission(),
                    consent = snapshot.consent,
                    waitingForLead = unmatched.count,
                    uploadsPausedUntil = health.current.uploadBlockedUntil
                        .takeIf { it > System.currentTimeMillis() },
                    folderHint = RecordingFolderHints.humanHint(configStore.current),
                    folderPickerStart = RecordingFolderHints.initialTreeUri(configStore.current),
                    configVersion = configStore.current.version,
                    lookbackHours = configStore.current.lookbackHours,
                    notifyNewLeads = notify.newLeads,
                    notifyReminders = notify.reminders,
                    notifyGeneral = notify.general,
                    themeMode = theme,
                    pushState = pushState,
                )
            }
        }
    }

    fun setNotifyNewLeads(on: Boolean) {
        viewModelScope.launch {
            settings.setNotifyNewLeads(on)
            _state.update { it.copy(notifyNewLeads = on) }
        }
    }

    fun setNotifyReminders(on: Boolean) {
        viewModelScope.launch {
            settings.setNotifyReminders(on)
            _state.update { it.copy(notifyReminders = on) }
        }
    }

    fun setNotifyGeneral(on: Boolean) {
        viewModelScope.launch {
            settings.setNotifyGeneral(on)
            _state.update { it.copy(notifyGeneral = on) }
        }
    }

    /**
     * Re-reads the profile from `GET /users/me`.
     *
     * Two things at once, which is why it earns a button of its own: it refreshes the name
     * and role shown above, and it proves end to end that this phone's token is still
     * accepted — the single most useful thing to know when a rep says the CRM cannot reach
     * them.
     */
    fun checkRemoteCalling() {
        if (_state.value.isCheckingRemote) return
        _state.update { it.copy(isCheckingRemote = true, remoteMessage = null) }

        viewModelScope.launch {
            when (val result = authRepository.refreshProfile()) {
                is ApiResult.Success -> {
                    refresh()
                    _state.update {
                        it.copy(
                            isCheckingRemote = false,
                            remoteMessage = "Signed in and reachable. Your profile is up to date.",
                            remoteMessageIsError = false,
                        )
                    }
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(
                        isCheckingRemote = false,
                        remoteMessage = result.message,
                        remoteMessageIsError = true,
                    )
                }
            }
        }
    }

    /**
     * Sends this phone's push token to the server again.
     *
     * The repair for a phone the CRM cannot ring. Deliberately unconditional: the state
     * saying "registered" is the app's own record of what it believes it sent, and that
     * belief is exactly what is in doubt when a rep reaches for this.
     */
    fun reregisterForRemoteCalls() {
        if (_state.value.isCheckingRemote) return
        _state.update { it.copy(isCheckingRemote = true, remoteMessage = null) }

        viewModelScope.launch {
            val registered = runCatching { push.reregister() }.getOrDefault(false)
            refresh()
            _state.update {
                it.copy(
                    isCheckingRemote = false,
                    remoteMessage = if (registered) {
                        "This phone is registered for calls from the CRM."
                    } else {
                        "Could not register this phone. Check you are signed in and online."
                    },
                    remoteMessageIsError = !registered,
                )
            }
        }
    }

    fun dismissRemoteMessage() = _state.update { it.copy(remoteMessage = null) }

    /**
     * Light, dark, or whatever the phone is set to.
     *
     * Nothing here re-reads the theme: MainActivity collects the same preference, so writing
     * it is all that is needed and the whole tree repaints on the next frame.
     */
    fun setThemeMode(mode: AppSettings.ThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
            _state.update { it.copy(themeMode = mode) }
        }
    }

    /**
     * The rep declined call tracking at setup and wants it after all. Sending them back
     * through onboarding is deliberate: the disclosure has to be read and accepted again,
     * and the folder still has to be chosen.
     */
    fun turnOnCallTracking() {
        viewModelScope.launch {
            settings.setConsent(AppSettings.Consent.UNDECIDED)
            settings.setOnboardingComplete(false)
            logger.info(LogStage.SETUP, "Call tracking setup reopened from Settings")
        }
    }

    /** Stops watching calls. Queued calls are left alone; nothing new is read or uploaded. */
    fun turnOffCallTracking() {
        viewModelScope.launch {
            settings.setConsent(AppSettings.Consent.DECLINED)
            logger.warn(
                LogStage.SETUP,
                "Call tracking switched off — no calls are matched or uploaded",
                detail = "Turn it back on in Settings > Calls being matched.",
            )
            refresh()
        }
    }

    /**
     * Runs the call check by hand and reports what it found.
     *
     * The single most useful thing when a rep says "my calls are not showing up": it turns
     * an invisible background job into an answer on screen, right now.
     */
    fun checkCallsNow() {
        if (_state.value.isCheckingCalls) return
        _state.update { it.copy(isCheckingCalls = true, lastCheckSummary = null) }

        viewModelScope.launch {
            val summary = if (!_state.value.callTrackingOn) {
                "Call tracking is off — accept the disclosure to turn it on."
            } else if (!callLogReader.hasPermission()) {
                "Call log permission is not granted — calls cannot be detected."
            } else {
                val result = reconciler.reconcile("checked by hand")
                when {
                    result.matched + result.recovered > 0 ->
                        "Found ${result.matched + result.recovered} call(s) with your leads."
                    result.deferred > 0 ->
                        "${result.deferred} call(s) are waiting for a network connection."
                    result.parked > 0 ->
                        "Checked ${result.scanned} recent call(s) — ${result.parked} kept in case a lead is added."
                    result.ignored > 0 ->
                        "Checked ${result.scanned} recent call(s) — none were leads."
                    else -> "No new calls since the last check."
                }
            }

            _state.update { it.copy(isCheckingCalls = false, lastCheckSummary = summary) }
            refresh()
        }
    }

    /**
     * Forgets every cached answer, so the next call re-asks the server.
     *
     * Useful right after a bulk lead import, when a number the app previously confirmed was
     * not a lead has just become one.
     */
    fun clearLookupCache() {
        viewModelScope.launch {
            lookupCache.clear()
            logger.info(LogStage.SETUP, "Cleared cached lead lookups")
            refresh()
        }
    }

    fun setCallMode(mode: CallMode) {
        viewModelScope.launch {
            settings.setCallMode(mode)
            _state.update { it.copy(callMode = mode) }
            logger.info(
                LogStage.SETUP,
                if (mode == CallMode.DIRECT) {
                    "Call mode set to direct dial"
                } else {
                    "Call mode set to open the phone dialler"
                },
            )
        }
    }

    fun setScanTimeout(seconds: Int) {
        viewModelScope.launch {
            settings.setScanTimeoutSeconds(seconds)
            _state.update { it.copy(scanTimeoutSeconds = seconds) }
        }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return

        viewModelScope.launch {
            // Release the previous grant so the app does not sit on access it no longer
            // uses — the rep gave us one folder, not a growing collection of them.
            val previous = settings.recordingsTreeUri.first()
            if (!previous.isNullOrBlank() && previous != uri.toString()) {
                runCatching { finder.releaseGrant(Uri.parse(previous)) }
            }

            runCatching {
                finder.takePersistableGrant(uri)
                require(finder.hasValidGrant(uri)) { "The folder could not be opened for reading" }
                settings.setRecordingsTreeUri(uri.toString())
            }.onSuccess {
                val (name, error) = describeFolder(uri.toString())
                _state.update { it.copy(folderDisplayName = name, folderError = error) }
                logger.success(LogStage.SETUP, "Recordings folder changed to ${name ?: uri}")
            }.onFailure { t ->
                // A cancelled screen is not a broken folder; reporting it as one would leave
                // "Job was cancelled" sitting where a real reason belongs.
                if (t is CancellationException) throw t
                val message = t.message ?: "Could not use that folder"
                _state.update { it.copy(folderError = message) }
                logger.error(LogStage.SETUP, "Could not use the selected folder", detail = message)
            }
        }
    }

    fun logout() {
        if (_state.value.isLoggingOut) return
        _state.update { it.copy(isLoggingOut = true) }

        viewModelScope.launch {
            authRepository.logout()
            _state.update { it.copy(isLoggingOut = false, loggedOut = true) }
        }
    }

    private fun describeFolder(uriString: String?): Pair<String?, String?> {
        if (uriString.isNullOrBlank()) return null to "No folder selected"
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return null to "Saved folder is unreadable, please pick it again"

        if (!finder.hasValidGrant(uri)) {
            return null to "Access to this folder was lost, please pick it again"
        }

        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        return (name ?: uri.lastPathSegment) to null
    }
}
