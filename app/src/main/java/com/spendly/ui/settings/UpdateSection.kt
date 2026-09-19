package com.spendly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Settings > Updates.
 *
 * One button drives the whole flow — check, download, verify, install — and the
 * label changes to say what the next tap will actually do, rather than making
 * you guess which of four controls applies right now.
 */
@Composable
fun UpdateSection(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val source by viewModel.updateSource.collectAsStateWithLifecycle()
    val checkOnOpen by viewModel.checkOnOpen.collectAsStateWithLifecycle()

    var sourceDraft by remember(source) { mutableStateOf(source) }
    val sourceValid = viewModel.isSourceValid(sourceDraft)

    Column(Modifier.padding(horizontal = 20.dp)) {

        Text(
            text = "Version ${viewModel.currentVersionName} (build ${viewModel.currentVersionCode})",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = sourceDraft,
            onValueChange = { sourceDraft = it },
            label = { Text("Update source") },
            placeholder = { Text("your-name/spendly") },
            supportingText = {
                Text(
                    when {
                        !sourceValid -> "Use owner/repo, or an https:// link to a JSON feed. Plain http is refused."
                        sourceDraft.isBlank() -> "A GitHub repo (owner/repo) or an https:// JSON feed."
                        else -> "Checked only when you press the button below."
                    },
                )
            },
            isError = !sourceValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (sourceDraft != source) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { sourceDraft = source }) { Text("Cancel") }
                TextButton(
                    enabled = sourceValid,
                    onClick = { viewModel.setUpdateSource(sourceDraft.trim()) },
                ) { Text("Save") }
            }
        }

        Spacer(Modifier.height(8.dp))

        StatusCard(state = state, viewModel = viewModel)

        Spacer(Modifier.height(10.dp))

        PrimaryAction(state = state, sourceSet = source.isNotBlank(), viewModel = viewModel)

        Spacer(Modifier.height(4.dp))

        SwitchRowInline(
            title = "Check when the app opens",
            subtitle = if (checkOnOpen) {
                "Spendly contacts the update source each time you open it."
            } else {
                "Off — no network request happens unless you press the button."
            },
            checked = checkOnOpen,
            onChange = viewModel::setCheckOnOpen,
        )

        Text(
            text = "Updating needs internet access and permission to install apps. " +
                "Android always shows its own install confirmation — no app can " +
                "skip that unless it ships with the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatusCard(state: UpdateUiState, viewModel: UpdateViewModel) {
    when (state) {
        UpdateUiState.Idle -> Unit

        UpdateUiState.Checking -> InfoRow {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking…", style = MaterialTheme.typography.bodyMedium)
        }

        is UpdateUiState.UpToDate -> InfoRow {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "You're on the latest version (${state.versionName})",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is UpdateUiState.Available -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Version ${state.manifest.versionName} is available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (state.manifest.sizeBytes > 0) {
                    Text(
                        "${state.manifest.sizeBytes / 1024 / 1024} MB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (state.manifest.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.manifest.notes.lineSequence().take(6).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        is UpdateUiState.Downloading -> Column {
            Text(
                "Downloading ${state.manifest.versionName}… ${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }

        is UpdateUiState.ReadyToInstall -> InfoRow {
            Text(
                "Verified ${state.manifest.versionName} — opening the installer",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        UpdateUiState.AwaitingConfirmation -> InfoRow {
            Text(
                "Waiting for you to confirm the install",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        UpdateUiState.Installed -> InfoRow {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Updated. Reopen Spendly to use the new version.", style = MaterialTheme.typography.bodyMedium)
        }

        UpdateUiState.NeedsInstallPermission -> WarningCard(
            text = "Android needs your permission for Spendly to install apps. " +
                "This is the \"install unknown apps\" switch.",
            actionLabel = "Open setting",
            onAction = viewModel::openInstallPermissionSettings,
        )

        UpdateUiState.NotConfigured -> WarningCard(
            text = "Set an update source above first — the repository or feed that " +
                "publishes new builds.",
        )

        is UpdateUiState.Failed -> WarningCard(
            text = state.message,
            actionLabel = "Dismiss",
            onAction = viewModel::dismiss,
        )
    }
}

@Composable
private fun PrimaryAction(
    state: UpdateUiState,
    sourceSet: Boolean,
    viewModel: UpdateViewModel,
) {
    when (state) {
        is UpdateUiState.Available -> Button(
            onClick = { viewModel.downloadAndInstall(state.manifest) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download and install ${state.manifest.versionName}") }

        is UpdateUiState.Downloading -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Downloading…") }

        is UpdateUiState.ReadyToInstall -> Button(
            onClick = { viewModel.install(state.apk) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Install now") }

        UpdateUiState.NeedsInstallPermission -> Button(
            onClick = viewModel::openInstallPermissionSettings,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow installing apps") }

        else -> Button(
            onClick = viewModel::check,
            enabled = sourceSet && state != UpdateUiState.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Check for updates")
        }
    }
}

/** Local copy: the one in SettingsScreen is private and pads to the list gutter. */
@Composable
private fun SwitchRowInline(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun WarningCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
