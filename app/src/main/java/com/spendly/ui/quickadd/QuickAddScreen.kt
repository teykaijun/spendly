package com.spendly.ui.quickadd

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.Category
import com.spendly.data.Money
import com.spendly.ui.components.EntryRow
import com.spendly.ui.theme.AmountDisplayStyle
import com.spendly.ui.theme.AmountDisplayStyleCompact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The whole point of the app: amount, category, done.
 *
 * With one-tap save on (the default), tapping a category *is* the save, so a
 * normal entry is "type the amount, tap the category" — two interactions, no
 * keyboard, no scrolling, no dialogs.
 */
@Composable
fun QuickAddScreen(
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onSeeCalendar: () -> Unit,
    viewModel: QuickAddViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val currency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val oneTapSave by viewModel.oneTapSave.collectAsStateWithLifecycle()
    val amountMinor by viewModel.amountMinor.collectAsStateWithLifecycle()
    val activeCurrency by viewModel.activeCurrency.collectAsStateWithLifecycle()
    val recentCurrencies by viewModel.recentCurrencies.collectAsStateWithLifecycle()
    val entriesToday by viewModel.entriesOnDate.collectAsStateWithLifecycle()
    val totalToday by viewModel.totalOnDate.collectAsStateWithLifecycle()

    val haptics = LocalHapticFeedback.current
    var showDatePicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }

    fun commit(categoryId: Long?) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.save(categoryId) { receipt ->
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = receipt.label,
                    actionLabel = "Undo",
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undo(receipt.entryId)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {

        DateStrip(
            date = state.date,
            totalMinor = totalToday,
            currency = currency,
            onPickToday = { viewModel.setDate(LocalDate.now()) },
            onPickYesterday = { viewModel.setDate(LocalDate.now().minusDays(1)) },
            onPickOther = { showDatePicker = true },
            onSeeCalendar = onSeeCalendar,
        )

        // Amount + (when there is nothing typed yet) the day's entries, so the
        // screen is useful rather than blank before you start.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // Captured here: inside the Column below, the implicit receiver is
            // ColumnScope and BoxWithConstraints' maxHeight is out of reach.
            val availableHeight = maxHeight

            if (amountMinor == 0L && entriesToday.isNotEmpty()) {
                DayEntries(
                    entries = entriesToday,
                    onDelete = viewModel::deleteEntry,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CurrencyChip(
                        currency = activeCurrency,
                        isBase = activeCurrency == currency,
                        onClick = { showCurrencyPicker = true },
                    )
                    Spacer(Modifier.height(6.dp))
                    // The keypad and category strip have fixed heights, so on a
                    // short screen this is whatever is left over. Step the amount
                    // down rather than letting a 64sp line get clipped.
                    AmountDisplay(
                        amountMinor = amountMinor,
                        currency = activeCurrency,
                        compact = availableHeight < 150.dp,
                    )
                }
            }
        }

        DetailsSection(
            expanded = state.detailsExpanded,
            merchant = state.merchant,
            note = state.note,
            onToggle = viewModel::toggleDetails,
            onMerchant = viewModel::setMerchant,
            onNote = viewModel::setNote,
        )

        CategoryStrip(
            categories = categories,
            selectedId = state.selectedCategoryId,
            enabled = amountMinor > 0L,
            onSelect = { category ->
                viewModel.selectCategory(category.id)
                if (oneTapSave && amountMinor > 0L) commit(category.id)
            },
        )

        Keypad(
            onDigit = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.appendDigit(it)
            },
            onDoubleZero = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.appendDoubleZero()
            },
            onBackspace = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.backspace()
            },
            onClear = viewModel::clearAmount,
        )

        SaveBar(
            enabled = amountMinor > 0L,
            oneTapSave = oneTapSave,
            categoryChosen = state.selectedCategoryId != null,
            onSave = { commit(null) },
        )
    }

    if (showCurrencyPicker) {
        CurrencySheet(
            current = activeCurrency,
            base = currency,
            recent = recentCurrencies,
            onPick = {
                viewModel.setCurrency(it)
                showCurrencyPicker = false
            },
            onDismiss = { showCurrencyPicker = false },
        )
    }

    if (showDatePicker) {
        SpendDatePicker(
            initial = state.date,
            onDismiss = { showDatePicker = false },
            onPicked = {
                viewModel.setDate(it)
                showDatePicker = false
            },
        )
    }
}

// ---------------------------------------------------------------------------

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun DateStrip(
    date: LocalDate,
    totalMinor: Long,
    currency: String,
    onPickToday: () -> Unit,
    onPickYesterday: () -> Unit,
    onPickOther: () -> Unit,
    onSeeCalendar: () -> Unit,
) {
    val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(dayFormat)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${Money.format(totalMinor, currency)} so far",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onSeeCalendar),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = date == today,
                onClick = onPickToday,
                label = { Text("Today") },
            )
            FilterChip(
                selected = date == today.minusDays(1),
                onClick = onPickYesterday,
                label = { Text("Yesterday") },
            )
            FilterChip(
                selected = date != today && date != today.minusDays(1),
                onClick = onPickOther,
                label = { Text(if (date != today && date != today.minusDays(1)) date.format(dayFormat) else "Pick date") },
            )
        }
    }
}

/**
 * The currency this entry will be saved in.
 *
 * When it is not your base currency the chip is filled with the primary colour
 * rather than sitting quietly in grey. The choice persists across saves — useful
 * when you are abroad and logging several in a row — so the non-default state
 * has to be impossible to miss, or you come home and keep logging in baht.
 */
@Composable
private fun CurrencyChip(
    currency: String,
    isBase: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (isBase) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.primary
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Money.symbol(currency)}  $currency",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isBase) FontWeight.Normal else FontWeight.Bold,
                color = if (isBase) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Change currency",
                modifier = Modifier.size(16.dp),
                tint = if (isBase) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            )
        }
    }
}

@Composable
private fun CurrencySheet(
    current: String,
    base: String,
    recent: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Recently used first, then everything else — switching between the two or
    // three currencies you actually use should not mean scrolling.
    val rest = Money.COMMON_CURRENCIES.filter { it !in recent }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency for this entry") },
        text = {
            LazyColumn(Modifier.height(380.dp)) {
                if (recent.isNotEmpty()) {
                    item { SheetLabel("Recently used") }
                    items(recent, key = { "r_$it" }) { code ->
                        CurrencyRow(
                            code = code,
                            selected = code == current,
                            isBase = code == base,
                            onClick = { onPick(code) },
                        )
                    }
                    item { SheetLabel("All currencies") }
                }
                items(rest, key = { "a_$it" }) { code ->
                    CurrencyRow(
                        code = code,
                        selected = code == current,
                        isBase = code == base,
                        onClick = { onPick(code) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun CurrencyRow(
    code: String,
    selected: Boolean,
    isBase: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Money.symbol(code),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(52.dp),
        )
        Text(text = code, style = MaterialTheme.typography.bodyLarge)
        if (isBase) {
            Spacer(Modifier.width(8.dp))
            Text(
                "default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AmountDisplay(amountMinor: Long, currency: String, compact: Boolean) {
    val text = Money.format(amountMinor, currency)
    Text(
        text = text,
        style = if (compact || text.length > 10) AmountDisplayStyleCompact else AmountDisplayStyle,
        color = if (amountMinor == 0L) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun DayEntries(
    entries: List<com.spendly.data.EntryWithCategory>,
    onDelete: (Long) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        entries.forEach { item ->
            EntryRow(item = item, onDelete = { onDelete(item.entry.id) })
        }
    }
}

@Composable
private fun DetailsSection(
    expanded: Boolean,
    merchant: String,
    note: String,
    onToggle: () -> Unit,
    onMerchant: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "Hide details" else "Add where / note (optional)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = onMerchant,
                    label = { Text("Where") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = onNote,
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CategoryStrip(
    categories: List<Category>,
    selectedId: Long?,
    enabled: Boolean,
    onSelect: (Category) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            CategoryChip(
                category = category,
                selected = category.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    category: Category,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = Color(category.colorArgb)
    val container = when {
        selected -> tint
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = container.copy(alpha = if (enabled) 1f else 0.4f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("00", "0", "⌫"),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    KeypadKey(
                        label = key,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "⌫" -> onBackspace()
                                "00" -> onDoubleZero()
                                else -> onDigit(key[0])
                            }
                        },
                        onLongClick = if (key == "⌫") onClear else null,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeypadKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SaveBar(
    enabled: Boolean,
    oneTapSave: Boolean,
    categoryChosen: Boolean,
    onSave: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (oneTapSave && enabled && !categoryChosen) {
            Text(
                text = "Tap a category to save",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Button(
            onClick = onSave,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpendDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    // The Material3 date picker reads and writes its millis as UTC midnight.
    // Seeding it from the local zone would land on the previous day for anyone
    // east of Greenwich, so the conversion is UTC on the way in and out.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    // The picker hands back a UTC midnight; read it as a plain date.
                    onPicked(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
