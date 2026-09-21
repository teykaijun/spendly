package com.spendly.ui.edit

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.EntryWithCategory
import com.spendly.data.Money
import com.spendly.data.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Edit any recorded spend: amount, currency, category, date, where, and note.
 *
 * Amount is a plain decimal field here rather than the Add screen's keypad. The
 * keypad is for speed on a fresh entry; correcting one is usually changing a
 * single digit, which is easiest to do in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(
    item: EntryWithCategory,
    onDismiss: () -> Unit,
    viewModel: EditEntryViewModel = viewModel(),
) {
    val entry = item.entry
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by remember(entry.id) {
        mutableStateOf(Money.formatPlain(entry.amountMinor, entry.currency).replace(",", ""))
    }
    var currency by remember(entry.id) { mutableStateOf(entry.currency) }
    var categoryId by remember(entry.id) { mutableLongStateOf(entry.categoryId) }
    var merchant by remember(entry.id) { mutableStateOf(entry.merchant.orEmpty()) }
    var note by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
    var date by remember(entry.id) { mutableStateOf(LocalDate.ofEpochDay(entry.epochDay)) }

    var pickingDate by remember { mutableStateOf(false) }
    var pickingCurrency by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // Parse leniently — "12,50", "12.5" and "1,234.50" all mean what they look
    // like — and refuse to save anything that is not a positive amount.
    val amountMinor = Money.parseToMinor(amountText)
    val amountValid = amountMinor != null && amountMinor > 0

    val changed = amountMinor != entry.amountMinor ||
        currency != entry.currency ||
        categoryId != entry.categoryId ||
        merchant.trim() != entry.merchant.orEmpty() ||
        note.trim() != entry.note.orEmpty() ||
        date.toEpochDay() != entry.epochDay

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Edit spend",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    sourceLabel(entry.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = !amountValid,
                    supportingText = if (!amountValid) {
                        { Text("Enter an amount above zero") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { pickingCurrency = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("${Money.symbol(currency)}  $currency")
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(date.format(dateFormat))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categories, key = { it.id }) { category ->
                    FilterChip(
                        selected = category.id == categoryId,
                        onClick = { categoryId = category.id },
                        label = { Text("${category.emoji} ${category.name}") },
                    )
                }
            }
            if (entry.source == Source.NOTIFICATION && categoryId != entry.categoryId &&
                merchant.isNotBlank()
            ) {
                Text(
                    "Future spends at ${merchant.trim()} will be filed here too.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Where") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmingDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(
                    enabled = amountValid && changed,
                    onClick = {
                        viewModel.save(
                            original = entry,
                            amountMinor = amountMinor!!,
                            currency = currency,
                            categoryId = categoryId,
                            merchant = merchant,
                            note = note,
                            date = date,
                            onDone = onDismiss,
                        )
                    },
                ) { Text("Save") }
            }
        }
    }

    if (pickingDate) {
        EditDatePicker(
            initial = date,
            onDismiss = { pickingDate = false },
            onPicked = {
                date = it
                pickingDate = false
            },
        )
    }

    if (pickingCurrency) {
        AlertDialog(
            onDismissRequest = { pickingCurrency = false },
            title = { Text("Currency") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(Money.COMMON_CURRENCIES) { code ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currency = code
                                    pickingCurrency = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                Money.symbol(code),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(code)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingCurrency = false }) { Text("Close") } },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this spend?") },
            text = {
                Text("${Money.format(entry.amountMinor, entry.currency)} on ${date.format(dateFormat)}")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(entry.id, onDismiss)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

private fun sourceLabel(source: String): String = when (source) {
    Source.NOTIFICATION -> "from a notification"
    Source.STATEMENT -> "from a statement"
    else -> "added by hand"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    // Material3's picker speaks UTC midnight in both directions.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}
