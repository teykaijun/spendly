package com.spendly.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.Money

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current

    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val autoSave by viewModel.autoSave.collectAsStateWithLifecycle()
    val notifyOnDetect by viewModel.notifyOnDetect.collectAsStateWithLifecycle()
    val oneTapSave by viewModel.oneTapSave.collectAsStateWithLifecycle()
    val minConfidence by viewModel.minConfidence.collectAsStateWithLifecycle()
    val capturePaused by viewModel.capturePaused.collectAsStateWithLifecycle()
    val listenerEnabled by viewModel.listenerEnabled.collectAsStateWithLifecycle()
    val watchedApps by viewModel.watchedApps.collectAsStateWithLifecycle()

    var showCurrencyPicker by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshListenerState()
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Nothing to do — the toggle reflects the pref either way. */ }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
    ) {
        item { SectionHeader("Entry") }

        item {
            SettingRow(
                title = "Default currency",
                subtitle = "$baseCurrency · ${Money.symbol(baseCurrency)} — new entries start here, " +
                    "and the calendar opens on it. You can pick a different currency " +
                    "per entry on the Add screen.",
                onClick = { showCurrencyPicker = true },
            )
        }

        item {
            SwitchRow(
                title = "One-tap save",
                subtitle = "Tapping a category saves straight away. Turn off if you " +
                    "prefer to press Save yourself.",
                checked = oneTapSave,
                onChange = viewModel::setOneTapSave,
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Reading notifications") }

        item {
            SettingRow(
                title = "Notification access",
                subtitle = if (listenerEnabled) {
                    "Granted — Spendly can read notifications"
                } else {
                    "Not granted. Without this, automatic entries cannot work."
                },
                trailing = {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(if (listenerEnabled) "Manage" else "Grant") }
                },
            )
        }

        item {
            SwitchRow(
                title = "Pause capture",
                subtitle = "Keeps access granted but stops creating entries.",
                checked = capturePaused,
                onChange = viewModel::setCapturePaused,
            )
        }

        item {
            SwitchRow(
                title = "Save automatically",
                subtitle = if (autoSave) {
                    "Detected spends are recorded immediately. Wrong reads go " +
                        "straight into your records until you spot them."
                } else {
                    "Detected spends wait in the Inbox for one tap. Recommended."
                },
                checked = autoSave,
                onChange = viewModel::setAutoSave,
            )
        }

        item {
            SwitchRow(
                title = "Alert me when a spend is found",
                subtitle = "Posts a notification with Confirm and Not-a-spend buttons, " +
                    "so you never have to open the app.",
                checked = notifyOnDetect,
                onChange = { enabled ->
                    viewModel.setNotifyOnDetect(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Sensitivity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Only record detections at least $minConfidence% certain. " +
                        "Lower catches more spends and more false alarms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = minConfidence.toFloat(),
                    onValueChange = { viewModel.setMinConfidence(it.toInt()) },
                    valueRange = 30f..90f,
                    steps = 11,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Apps Spendly listens to") }

        item {
            Text(
                "Apps appear here once they post a notification. Banks and wallets " +
                    "start switched on; chat, social and email apps start off, because " +
                    "people write \"I paid RM50\" to each other.\n\n" +
                    "Turn Gmail on here to catch subscription and invoice emails — " +
                    "Spendly reads the notification Gmail already posts, so it needs " +
                    "no access to your mail account and stays offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (watchedApps.isEmpty()) {
            item {
                Text(
                    "Nothing seen yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        } else {
            items(watchedApps, key = { it.packageName }) { app ->
                SwitchRow(
                    title = app.label,
                    subtitle = if (app.detectedCount > 0) {
                        "${app.detectedCount} spend${if (app.detectedCount == 1) "" else "s"} found"
                    } else {
                        app.packageName
                    },
                    checked = app.enabled,
                    onChange = { viewModel.setAppEnabled(app.packageName, it) },
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { SectionHeader("Data") }

        item {
            SettingRow(
                title = "Export as CSV",
                subtitle = "Every entry, as a spreadsheet file you can share or back up.",
                trailing = {
                    Button(onClick = {
                        viewModel.exportCsv { intent -> context.startActivity(intent) }
                    }) { Text("Export") }
                },
            )
        }

        item {
            Text(
                text = "Spendly has no internet permission, so it cannot send anything " +
                    "anywhere even if it tried. Notification text is parsed in memory; " +
                    "only the amount, merchant and source app name are kept. Your " +
                    "history is excluded from Google cloud backup — use the CSV export " +
                    "above for backups you choose to make.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }

    if (showCurrencyPicker) {
        CurrencyPicker(
            current = baseCurrency,
            onPick = {
                viewModel.setBaseCurrency(it)
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun CurrencyPicker(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Base currency") },
        text = {
            LazyColumn(Modifier.height(340.dp)) {
                items(Money.COMMON_CURRENCIES) { code ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(code) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = code == current,
                            onClick = { onPick(code) },
                            label = { Text(Money.symbol(code)) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            code,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
