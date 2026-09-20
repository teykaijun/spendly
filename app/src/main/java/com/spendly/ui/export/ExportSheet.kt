package com.spendly.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.Money
import com.spendly.ui.components.EntryRow
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pick a date range, see exactly what falls inside it, then export it.
 *
 * Deliberately one surface rather than two: "filter by a range" and "export a
 * range" are the same question asked twice, and showing the matching entries
 * before the file is written means the export is never a surprise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    viewModel: ExportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val from by viewModel.customFrom.collectAsStateWithLifecycle()
    val to by viewModel.customTo.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<DateEdge?>(null) }
    var emptyWarning by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Spending by date range",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RangePreset.entries.toList(), key = { it.name }) { option ->
                    FilterChip(
                        selected = preset == option,
                        onClick = { viewModel.selectPreset(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (preset == RangePreset.Custom) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DateButton(
                        label = "From",
                        date = from,
                        modifier = Modifier.weight(1f),
                        onClick = { picking = DateEdge.From },
                    )
                    DateButton(
                        label = "To",
                        date = to,
                        modifier = Modifier.weight(1f),
                        onClick = { picking = DateEdge.To },
                    )
                }
                Spacer(Modifier.height(10.dp))
            } else if (preset != RangePreset.Everything) {
                val (b0, b1) = preset.bounds()
                if (b0 != null && b1 != null) {
                    Text(
                        "${b0.format(prettyFormat)} – ${b1.format(prettyFormat)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (summary.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }

            // Totals, one line per currency. Never summed together.
            if (summary.totals.isEmpty()) {
                Text(
                    "No spending in this range.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                summary.totals.entries.sortedByDescending { it.value }.forEach { (code, total) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(code, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Money.format(total, code),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${summary.entries.size} " +
                        if (summary.entries.size == 1) "entry" else "entries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (emptyWarning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nothing to export in this range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    emptyWarning = false
                    viewModel.export(
                        onReady = { context.startActivity(it) },
                        onEmpty = { emptyWarning = true },
                    )
                },
                enabled = summary.entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export CSV")
            }

            if (summary.entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(summary.entries, key = { it.entry.id }) { row ->
                        EntryRow(item = row)
                    }
                }
            }
        }
    }

    picking?.let { edge ->
        RangeDatePicker(
            initial = if (edge == DateEdge.From) from else to,
            onDismiss = { picking = null },
            onPicked = { date ->
                if (edge == DateEdge.From) viewModel.setCustomFrom(date) else viewModel.setCustomTo(date)
                picking = null
            },
        )
    }
}

private enum class DateEdge { From, To }

private val prettyFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun DateButton(
    label: String,
    date: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(date.format(prettyFormat), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    // Material3's picker reads and writes UTC midnight, so both directions use
    // UTC — seeding from the local zone lands on the previous day east of GMT.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPicked(
                        java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate(),
                    )
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
