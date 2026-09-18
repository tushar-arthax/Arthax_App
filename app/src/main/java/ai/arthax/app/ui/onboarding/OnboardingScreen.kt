package ai.arthax.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import ai.arthax.app.ui.theme.ArthaxIcons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arthax.app.core.ApiConfig
import ai.arthax.app.ui.common.AppPermissions
import ai.arthax.app.ui.common.DeviceSetup
import ai.arthax.app.ui.common.ErrorBanner
import ai.arthax.app.ui.theme.statusColors

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionResult() }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onFolderPicked(uri) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    LaunchedEffect(state.completed) {
        if (state.completed) onFinished()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(40.dp))

        Text("Set up Arthax", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Three quick steps so calls get linked to the right lead automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        // The prominent disclosure. Shown before any permission is requested, and the
        // rest of setup stays hidden until it is accepted — a rep who declines is done
        // here and lands on the lead list.
        if (!state.disclosureAccepted) {
            DisclosureCard(
                onAccept = viewModel::acceptDisclosure,
                onDecline = viewModel::declineDisclosure,
            )
            Spacer(Modifier.height(32.dp))
            return@Column
        }

        SetupStep(
            index = 1,
            icon = Icons.Default.Lock,
            title = "Allow permissions",
            description = "Arthax needs to place calls, and to read your call log so it can " +
                "tell an answered call from one that rang out. It never reads your contacts " +
                "or messages, and calls to numbers that are not leads are ignored entirely.",
            isDone = state.permissionsDone,
        ) {
            if (state.callLogBlocked) {
                ErrorBanner(
                    message = "Android is blocking the call log permission on this install",
                    detail = "Reading the call log is a restricted permission. If it was " +
                        "switched off with \"Don't ask again\", turn it on in app settings. " +
                        "If it does not appear there at all, this copy of Arthax was " +
                        "installed in a way that blocks it - reinstall the APK from your " +
                        "file manager, or via adb install, and grant it when asked.",
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!state.permissionsDone) {
                Button(onClick = { permissionLauncher.launch(AppPermissions.all().toTypedArray()) }) {
                    Text("Grant permissions")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { settingsLauncher.launch(AppPermissions.appSettingsIntent(context)) },
                ) {
                    Text("Open app settings instead")
                }
            } else if (state.missingOptionalPermissions.isNotEmpty()) {
                Text(
                    text = "Notifications are off, so you will not be warned if an upload fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.warning,
                )
                TextButton(
                    onClick = { permissionLauncher.launch(state.missingOptionalPermissions.toTypedArray()) },
                ) {
                    Text("Turn on notifications")
                }
            }
        }

        SetupStep(
            index = 2,
            icon = ArthaxIcons.Folder,
            title = "Choose your recordings folder",
            description = "Pick the folder where your phone saves call recordings. Arthax only " +
                "ever reads this one folder, and only files created during your lead calls.",
            isDone = state.folderDone,
        ) {
            if (state.folderDone) {
                Text(
                    text = "Selected: ${state.folderDisplayName.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { folderLauncher.launch(state.folderPickerStart) }) {
                    Text("Change folder")
                }
            } else {
                if (state.folderError != null) {
                    ErrorBanner(message = state.folderError.orEmpty())
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = state.folderHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { folderLauncher.launch(state.folderPickerStart) }) {
                    Text("Select folder")
                }
            }
        }

        SetupStep(
            index = 3,
            icon = Icons.Default.Warning,
            title = "Keep Arthax running",
            description = "Android may close Arthax in the background and stop it from finding " +
                "your recordings. These two settings prevent that.",
            isDone = !state.batteryOptimised && (!state.needsAutostart || state.autostartAcknowledged),
            isOptional = true,
        ) {
            if (state.batteryOptimised) {
                OutlinedButton(
                    onClick = {
                        val intent = DeviceSetup.batteryExemptionIntent(context)
                        val fallback = DeviceSetup.batterySettingsFallbackIntent()
                        settingsLauncher.launch(
                            if (context.packageManager.resolveActivity(intent, 0) != null) intent else fallback,
                        )
                    },
                ) {
                    Text("Allow unrestricted battery use")
                }
            } else {
                Text(
                    text = "Battery restrictions are off. Good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.success,
                )
            }

            if (state.needsAutostart) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${state.manufacturer} phones also need Autostart switched on by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = DeviceSetup.autostartInstructions(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(
                        onClick = {
                            val intent = DeviceSetup.firstResolvable(context, DeviceSetup.autostartIntents())
                            if (intent != null) {
                                settingsLauncher.launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            viewModel.acknowledgeAutostart()
                        },
                    ) {
                        Text("Open autostart settings")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = viewModel::acknowledgeAutostart) { Text("Done") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::finish,
            enabled = state.canFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start using Arthax", style = MaterialTheme.typography.labelLarge)
        }

        if (!state.canFinish) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Steps 1 and 2 are required. Step 3 is strongly recommended.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * What is collected, where it goes, and a real choice. The wording is deliberately plain
 * and complete: call-log metadata and recordings of business calls, sent to the employer's
 * Arthax CRM. Anything less than that on this screen would be a policy problem on Play and
 * a trust problem with the rep.
 */
@Composable
private fun DisclosureCard(onAccept: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Before you start", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Arthax links your work calls to leads in your employer's Arthax CRM. " +
                    "To do that it collects:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            DisclosureItem(
                "Call log details — the number, direction, time and duration of your calls, " +
                    "read from the phone's call log.",
            )
            DisclosureItem(
                "Call recordings of business calls — recordings your phone's dialler saves for " +
                    "calls with numbers that match a lead, read from the one folder you choose.",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This information is sent to your employer's Arthax CRM and stored there as " +
                    "part of each lead's call history. Calls to numbers that are not leads are " +
                    "never uploaded. Nothing is shared with anyone else, and nothing is read " +
                    "from your contacts or messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "If you decline, Arthax still shows your leads and lets you dial them, but " +
                    "reads no call history and uploads nothing. You can change this in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ApiConfig.PRIVACY_POLICY_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ) { Text("Read the privacy policy") }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text("Accept and continue") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Decline — leads only") }
        }
    }
}

@Composable
private fun DisclosureItem(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SetupStep(
    index: Int,
    icon: ImageVector,
    title: String,
    description: String,
    isDone: Boolean,
    modifier: Modifier = Modifier,
    isOptional: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = if (isDone) statusColors.successContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDone) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Done",
                            tint = statusColors.success,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            text = index.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (isOptional) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Recommended",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}
