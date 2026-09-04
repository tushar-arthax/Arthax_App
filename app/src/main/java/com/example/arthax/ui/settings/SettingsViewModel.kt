package com.example.arthax.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.call.CallLogReader
import com.example.arthax.call.CallLogReconciler
import com.example.arthax.data.local.store.LeadLookupCache
import com.example.arthax.data.repository.AuthRepository
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.CallMode
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingFinder
import com.example.arthax.recording.RecordingStorage
import com.example.arthax.work.WorkScheduler
import com.example.arthax.ui.common.AppPermissions
import com.example.arthax.ui.common.DeviceSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    private val reconciler: CallLogReconciler,
    private val callLogReader: CallLogReader,
    private val workScheduler: WorkScheduler,
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = settings.snapshot.first()
            val (name, error) = describeFolder(snapshot.recordingsTreeUri)

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
                )
            }
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
            val summary = if (!callLogReader.hasPermission()) {
                "Call log permission is not granted — calls cannot be detected."
            } else {
                val result = reconciler.reconcile("checked by hand")
                when {
                    result.matched > 0 ->
                        "Found ${result.matched} call(s) with your leads."
                    result.deferred > 0 ->
                        "${result.deferred} call(s) are waiting for a network connection."
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
