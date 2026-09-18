package com.example.arthax.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.data.repository.EventLogger
import com.example.arthax.domain.model.LogStage
import com.example.arthax.recording.RecordingFinder
import com.example.arthax.ui.common.AppPermissions
import com.example.arthax.ui.common.DeviceSetup
import com.example.arthax.work.WorkScheduler
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
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val finder: RecordingFinder,
    private val workScheduler: WorkScheduler,
    private val logger: EventLogger,
) : ViewModel() {

    data class UiState(
        val missingRequiredPermissions: List<String> = emptyList(),
        val missingOptionalPermissions: List<String> = emptyList(),
        val folderUri: String? = null,
        val folderDisplayName: String? = null,
        val folderError: String? = null,
        val batteryOptimised: Boolean = false,
        val needsAutostart: Boolean = false,
        val autostartAcknowledged: Boolean = false,
        val manufacturer: String = "",
        val folderHint: String = "",
        val completed: Boolean = false,
        val callLogBlocked: Boolean = false,
    ) {
        val permissionsDone: Boolean get() = missingRequiredPermissions.isEmpty()
        val folderDone: Boolean get() = !folderUri.isNullOrBlank() && folderError == null

        /**
         * Battery and autostart are advisory, not blocking — some devices simply do not
         * expose them, and refusing to let the rep finish setup over a switch we cannot
         * verify would be worse than proceeding with a warning.
         */
        val canFinish: Boolean get() = permissionsDone && folderDone
    }

    private val _state = MutableStateFlow(
        UiState(
            manufacturer = DeviceSetup.manufacturerLabel(),
            needsAutostart = DeviceSetup.needsAutostartGrant(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-read every OS-owned bit of state. Called on every resume, since all of it can change outside the app. */
    fun refresh() {
        viewModelScope.launch {
            val stored = settings.recordingsTreeUri.first()
            val (displayName, folderError) = describeFolder(stored)

            _state.update {
                it.copy(
                    missingRequiredPermissions = AppPermissions.missingRequired(context),
                    callLogBlocked = AppPermissions.looksUngrantable(
                        context,
                        android.Manifest.permission.READ_CALL_LOG,
                        alreadyRequested = settings.callLogRequested.first(),
                    ),
                    missingOptionalPermissions = AppPermissions.missing(context) -
                        AppPermissions.missingRequired(context).toSet(),
                    folderUri = stored,
                    folderDisplayName = displayName,
                    folderError = folderError,
                    batteryOptimised = DeviceSetup.isBatteryOptimised(context),
                    manufacturer = DeviceSetup.manufacturerLabel(),
                    needsAutostart = DeviceSetup.needsAutostartGrant(),
                    folderHint = com.example.arthax.ui.common.RecordingFolderHints.humanHint(),
                )
            }
        }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return

        viewModelScope.launch {
            val result = runCatching {
                // Must be taken immediately: the grant handed to onActivityResult is
                // good only for this process lifetime unless persisted right now.
                finder.takePersistableGrant(uri)
                require(finder.hasValidGrant(uri)) { "The folder could not be opened for reading" }
                settings.setRecordingsTreeUri(uri.toString())
            }

            result.onSuccess {
                val (name, error) = describeFolder(uri.toString())
                _state.update { it.copy(folderUri = uri.toString(), folderDisplayName = name, folderError = error) }
                logger.success(LogStage.SETUP, "Recordings folder set to ${name ?: uri}")
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

    fun acknowledgeAutostart() {
        _state.update { it.copy(autostartAcknowledged = true) }
        viewModelScope.launch { settings.setAutostartPromptShown(true) }
    }

    fun onPermissionResult() {
        viewModelScope.launch { settings.setCallLogRequested(true) }
        refresh()
        // Re-check immediately: the app decided it had no call log permission before this
        // moment, and without a nudge nothing would revisit that until the next process start.
        workScheduler.enqueueReconcile(WorkScheduler.REASON_APP_OPENED)
    }

    fun finish() {
        if (!_state.value.canFinish) return
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            logger.success(LogStage.SETUP, "Setup completed")
            _state.update { it.copy(completed = true) }
        }
    }

    private fun describeFolder(uriString: String?): Pair<String?, String?> {
        if (uriString.isNullOrBlank()) return null to null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return null to "Saved folder is unreadable, please pick it again"

        if (!finder.hasValidGrant(uri)) {
            return null to "Access to this folder was lost, please pick it again"
        }

        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        return (name ?: uri.lastPathSegment) to null
    }
}
