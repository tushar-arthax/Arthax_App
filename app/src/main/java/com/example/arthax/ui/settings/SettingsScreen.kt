package com.example.arthax.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.arthax.BuildConfig
import com.example.arthax.core.ApiConfig
import com.example.arthax.data.local.prefs.AppSettings
import com.example.arthax.domain.model.CallMode
import com.example.arthax.ui.common.AppPermissions
import com.example.arthax.ui.common.DeviceSetup
import com.example.arthax.ui.common.ErrorBanner
import com.example.arthax.ui.theme.statusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onFolderPicked(uri) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) onLoggedOut()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "Signed in") {
            Text(
                text = state.repName.ifBlank { "Sales rep" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOfNotNull(
                    state.repPhone.takeIf { it.isNotBlank() },
                    state.repEmail.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.repRole.isNotBlank()) {
                Text(
                    text = state.repRole.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                enabled = !state.isLoggingOut,
            ) {
                if (state.isLoggingOut) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Signing out...")
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Log out")
                }
            }
        }

        SettingsSection(title = "How the CALL button works") {
            CallModeOption(
                selected = state.callMode == CallMode.DIRECT,
                title = "Dial immediately",
                description = "One tap places the call. Fastest, but on dual-SIM phones the " +
                    "system picks the SIM.",
                onClick = { viewModel.setCallMode(CallMode.DIRECT) },
            )
            Spacer(Modifier.height(8.dp))
            CallModeOption(
                selected = state.callMode == CallMode.DIALER,
                title = "Open the phone dialler",
                description = "Opens your dialler with the number filled in, so you choose the " +
                    "SIM and press call yourself.",
                onClick = { viewModel.setCallMode(CallMode.DIALER) },
            )
        }

        SettingsSection(title = "Calls being matched") {
            if (!state.callTrackingOn) {
                StatusLine(
                    ok = false,
                    text = "Call tracking is off — you declined it at setup",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Nothing is read from your call log and no recording is uploaded. " +
                        "Leads can still be viewed and dialled. Turning it on shows the " +
                        "disclosure again and asks for the permissions it needs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = viewModel::turnOnCallTracking) { Text("Turn on call tracking") }
                Spacer(Modifier.height(6.dp))
                PrivacyPolicyLink()
            } else {
            StatusLine(
                ok = state.callLogPermission,
                text = if (state.callLogPermission) {
                    "Every call is checked against your leads"
                } else {
                    "Call log permission missing — no calls can be detected"
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Incoming and outgoing, whether or not Arthax is open. Every call is " +
                    "checked against your CRM as it happens, so a lead added a minute ago is " +
                    "matched and a lead removed stops being matched straight away - nothing " +
                    "is downloaded in bulk. Calls to numbers that are not leads are kept " +
                    "on this phone for a few days in case the lead is added, and are never " +
                    "uploaded until it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.waitingForLead > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${state.waitingForLead} call(s) waiting for a matching lead",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.uploadsPausedUntil != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Recording uploads are paused until " +
                        "${formatClock(state.uploadsPausedUntil ?: 0L)} — the organisation is " +
                        "out of credits. Calls are still logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning,
                )
            }
            if (state.cachedLookups > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${state.knownLeadNumbers} lead number(s) saved for matching " +
                        "while you have no signal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastCheckSummary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.lastCheckSummary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::checkCallsNow,
                    enabled = !state.isCheckingCalls,
                ) {
                    if (state.isCheckingCalls) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking...")
                    } else {
                        Text("Check for calls now")
                    }
                }
                TextButton(onClick = viewModel::clearLookupCache) {
                    Text("Re-check numbers")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrivacyPolicyLink()
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::turnOffCallTracking) { Text("Turn off") }
            }
            }
        }

        if (state.callTrackingOn) {
        SettingsSection(title = "Recordings folder") {
            if (state.folderError != null) {
                ErrorBanner(message = state.folderError.orEmpty())
                Spacer(Modifier.height(10.dp))
            } else {
                StatusLine(
                    ok = true,
                    text = "Using: ${state.folderDisplayName.orEmpty()}",
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = state.folderHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { folderLauncher.launch(state.folderPickerStart) }) {
                Text(if (state.folderError == null) "Change folder" else "Select folder")
            }
        }
        }

        SettingsSection(title = "How long to wait for a recording") {
            Text(
                text = "${state.scanTimeoutSeconds} seconds after a call ends",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Some phones take a while to finish saving. Raise this if recordings " +
                    "are sometimes missed; lower it to save a little battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.scanTimeoutSeconds.toFloat(),
                onValueChange = { viewModel.setScanTimeout(it.toInt()) },
                valueRange = AppSettings.MIN_SCAN_TIMEOUT_SECONDS.toFloat()..
                    AppSettings.MAX_SCAN_TIMEOUT_SECONDS.toFloat(),
                steps = 10,
            )
        }

        SettingsSection(title = "Background access") {
            StatusLine(
                ok = state.permissionsOk,
                text = if (state.permissionsOk) {
                    "Call permissions granted"
                } else {
                    "Call permissions missing — calls cannot be tracked"
                },
            )
            if (!state.permissionsOk) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { settingsLauncher.launch(AppPermissions.appSettingsIntent(context)) },
                ) {
                    Text("Open app permissions")
                }
            }

            Spacer(Modifier.height(12.dp))

            StatusLine(
                ok = !state.batteryOptimised,
                text = if (state.batteryOptimised) {
                    "Battery optimisation is on — recordings may be missed"
                } else {
                    "Battery optimisation is off"
                },
            )
            if (state.batteryOptimised) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = DeviceSetup.batteryExemptionIntent(context)
                        val fallback = DeviceSetup.batterySettingsFallbackIntent()
                        settingsLauncher.launch(
                            if (context.packageManager.resolveActivity(intent, 0) != null) {
                                intent
                            } else {
                                fallback
                            },
                        )
                    },
                ) {
                    Text("Allow unrestricted battery use")
                }
            }

            if (state.needsAutostart) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${state.manufacturer} autostart",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = DeviceSetup.autostartInstructions(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        DeviceSetup.firstResolvable(context, DeviceSetup.autostartIntents())
                            ?.let { settingsLauncher.launch(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                ) {
                    Text("Open autostart settings")
                }
            }
        }

        SettingsSection(title = "About") {
            Text(
                text = "Arthax ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                // Shown so a rep can tell support which backend they are actually on,
                // rather than everyone guessing.
                text = "${ApiConfig.environmentLabel} · ${ApiConfig.BASE_URL}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.heldBytes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${state.heldBytes / 1024} KB of recordings waiting to upload",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                // Which server config this phone is on, and the one number from it a rep
                // may be asked about: how far back the call log is read.
                text = if (state.configVersion > 0) {
                    "Server config v${state.configVersion} · looks back ${state.lookbackHours}h"
                } else {
                    "Built-in config · looks back ${state.lookbackHours}h"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            PrivacyPolicyLink()
        }

        Spacer(Modifier.height(32.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = {
                Text(
                    "Your access token will be removed from this phone. Calls that have not " +
                        "reached the server yet stay saved here and will be sent when you " +
                        "sign back in.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        viewModel.logout()
                    },
                ) { Text("Log out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PrivacyPolicyLink() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ApiConfig.PRIVACY_POLICY_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
    ) {
        Text("Privacy policy")
    }
}

private fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) statusColors.success else statusColors.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CallModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
