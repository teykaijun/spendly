package com.spendly.ui.inbox

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.spendly.data.CaptureLog
import com.spendly.data.Category
import com.spendly.data.Money
import com.spendly.data.PendingEntry
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The confirm queue. Everything the notification reader found, waiting on a
 * single tap each.
 */
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val listenerEnabled by viewModel.listenerEnabled.collectAsStateWithLifecycle()
    val paused by viewModel.capturePaused.collectAsStateWithLifecycle()
    val alertsAllowed by viewModel.alertsAllowed.collectAsStateWithLifecycle()
    val alertsWanted by viewModel.alertsWanted.collectAsStateWithLifecycle()
    val captureLog by viewModel.captureLog.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }

    // Permission is granted in system settings, which gives no callback — so
    // re-check every time this screen comes back to the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshListenerState()
        onPauseOrDispose { }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!listenerEnabled) {
            item { EnableAccessCard() }
        } else if (paused) {
            item { PausedCard(onResume = { viewModel.setPaused(false) }) }
        } else if (alertsWanted && !alertsAllowed) {
            // The exact state that made detections look unsaved: capture works,
            // but the confirm prompt can never appear.
            item { AlertsBlockedCard() }
        }

        if (pending.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${pending.size} to review",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        TextButton(onClick = viewModel::dismissAll) { Text("Clear all") }
                        Spacer(Modifier.width(4.dp))
                        FilledTonalButton(onClick = viewModel::confirmAll) { Text("Confirm all") }
                    }
                }
            }
        }

        items(pending, key = { it.id }) { item ->
            PendingCard(
                pending = item,
                categories = categories,
                expanded = expandedId == item.id,
                onToggleExpand = { viewModel.toggleExpanded(item.id) },
                onConfirm = { viewModel.confirm(item.id) },
                onConfirmWith = { viewModel.confirm(item.id, it) },
                onDismiss = { viewModel.dismiss(item.id) },
            )
        }

        if (pending.isEmpty() && listenerEnabled) {
            item { EmptyInbox() }
        }

        if (captureLog.isNotEmpty()) {
            item {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(
                        if (showLog) "Hide recent activity" else
                            "What Spendly checked recently (${captureLog.size})",
                    )
                }
            }
            if (showLog) {
                item {
                    Text(
                        "Notifications that mentioned money, and what happened to each. " +
                            "Only the app name and amount are kept — never the message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(captureLog, key = { "${it.at}-${it.appLabel}-${it.detail.hashCode()}" }) { event ->
                    CaptureLogRow(event)
                }
                item {
                    TextButton(onClick = viewModel::clearCaptureLog) { Text("Clear") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

private val timeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun PendingCard(
    pending: PendingEntry,
    categories: List<Category>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmWith: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val guessed = categories.firstOrNull { it.id == pending.guessedCategoryId }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = Money.format(pending.amountMinor, pending.currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = pending.merchant ?: pending.sourceAppLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pending.sourceAppLabel} · " +
                            Instant.ofEpochMilli(pending.postedAt)
                                .atZone(ZoneId.systemDefault())
                                .format(timeFormat) +
                            " · ${pending.confidence}% sure",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Tapping the category chip opens the picker; the big button just
            // accepts the guess. Both are one tap.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryPill(
                    emoji = guessed?.emoji ?: "❓",
                    name = guessed?.name ?: "Pick category",
                    color = guessed?.let { Color(it.colorArgb) }
                        ?: MaterialTheme.colorScheme.outline,
                    onClick = onToggleExpand,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.height(38.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Not a spend")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, modifier = Modifier.height(38.dp)) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Confirm")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Save as — this also teaches Spendly for next time",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories, key = { it.id }) { category ->
                            CategoryPill(
                                emoji = category.emoji,
                                name = category.name,
                                color = Color(category.colorArgb),
                                onClick = { onConfirmWith(category.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "“${pending.rawTitle} ${pending.rawText}”".trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(
    emoji: String,
    name: String,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 14.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EnableAccessCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Turn on automatic capture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Spendly can read your bank and wallet notifications and fill in " +
                    "the entry for you. Everything is parsed on this phone and nothing " +
                    "is uploaded anywhere.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("Open notification access")
            }
        }
    }
}

@Composable
private fun AlertsBlockedCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Confirm prompts are blocked",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Spendly is reading your notifications, but Android is not letting it " +
                    "post the \"tap to confirm\" alert. Detected spends wait here " +
                    "silently until you confirm them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Allow notifications") }
        }
    }
}

private val logTime = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun CaptureLogRow(event: CaptureLog.Event) {
    val (label, tint) = when (event.outcome) {
        CaptureLog.Outcome.QUEUED -> "Queued" to MaterialTheme.colorScheme.primary
        CaptureLog.Outcome.SAVED -> "Saved" to MaterialTheme.colorScheme.primary
        CaptureLog.Outcome.IGNORED -> "Ignored" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                event.appLabel + (event.amount?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Instant.ofEpochMilli(event.at).atZone(ZoneId.systemDefault()).format(logTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            event.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PausedCard(onResume: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Capture is paused.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Nothing to review", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Spends found in your notifications will land here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
