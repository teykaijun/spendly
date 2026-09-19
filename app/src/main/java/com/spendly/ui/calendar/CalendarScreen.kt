package com.spendly.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.data.Money
import com.spendly.ui.components.EntryRow
import com.spendly.ui.components.StatTile
import com.spendly.ui.theme.heatLevel
import com.spendly.ui.theme.heatmapRamp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = viewModel()) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedEntries by viewModel.selectedEntries.collectAsStateWithLifecycle()
    val selectedTotals by viewModel.selectedTotal.collectAsStateWithLifecycle()
    val currency by viewModel.displayCurrency.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val currenciesInMonth by viewModel.currenciesInMonth.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            MonthHeader(
                month = month,
                onPrev = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::jumpToToday,
            )
        }

        // Only worth showing when there is actually more than one currency in
        // play — a single-currency month should look no busier than before.
        if (currenciesInMonth.size > 1) {
            item {
                CurrencySwitcher(
                    currencies = currenciesInMonth,
                    selected = currency,
                    base = baseCurrency,
                    onSelect = viewModel::showCurrency,
                )
            }
        }

        item {
            MonthSummary(stats = stats, currency = currency)
        }

        item {
            HeatmapGrid(
                month = month,
                days = days,
                maxDayMinor = stats.maxDayMinor,
                selected = selected,
                currency = currency,
                onSelect = viewModel::select,
            )
        }

        item { HeatLegend(maxDayMinor = stats.maxDayMinor, currency = currency) }

        item { HorizontalDivider(Modifier.padding(16.dp)) }

        if (selected != null) {
            item {
                DayHeader(date = selected!!, totals = selectedTotals, count = selectedEntries.size)
            }
            if (selectedEntries.isEmpty()) {
                item {
                    Text(
                        "Nothing recorded on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(selectedEntries, key = { it.entry.id }) { item ->
                    EntryRow(
                        item = item,
                        onDelete = { viewModel.deleteEntry(item.entry.id) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        } else {
            item {
                Text(
                    "Tap a day to see what you spent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        if (stats.categories.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(16.dp)) }
            item {
                Text(
                    "Where ${month.format(monthTitleFormat)} went",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            items(stats.categories, key = { it.name }) { slice ->
                CategoryBar(slice = slice, currency = currency)
            }
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * Compose's locale, as a [java.util.Locale]. Reading `Locale.getDefault()`
 * directly inside a composable is invisible to recomposition, so a language
 * change would leave a stale calendar until the screen was rebuilt.
 */
@Composable
private fun rememberJavaLocale(): Locale {
    val tag = androidx.compose.ui.text.intl.Locale.current.toLanguageTag()
    return remember(tag) { Locale.forLanguageTag(tag) }
}

private val monthTitleFormat = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayTitleFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            text = month.format(monthTitleFormat),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
        }
        TextButton(onClick = onToday) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Today")
        }
    }
}

/**
 * Switches which currency the heatmap, totals and category breakdown describe.
 * Nothing is converted, so this is a filter, not a rate lookup.
 */
@Composable
private fun CurrencySwitcher(
    currencies: List<String>,
    selected: String,
    base: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Showing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(currencies, key = { it }) { code ->
                FilterChip(
                    selected = code == selected,
                    onClick = { onSelect(code) },
                    label = {
                        Text(
                            // The base currency is marked so it is obvious which
                            // view is the "normal" one to come back to.
                            if (code == base) "${Money.symbol(code)} $code ·" else "${Money.symbol(code)} $code",
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MonthSummary(stats: MonthStats, currency: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            StatTile("Month total", Money.format(stats.totalShownMinor, currency))
            StatTile("Per active day", Money.format(stats.avgPerActiveDayMinor, currency))
            StatTile("Days spent", stats.daysWithSpend.toString())
        }
        if (stats.otherTotals.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                // Kept separate rather than converted: the app holds no exchange
                // rates, and inventing one would make the headline total a lie.
                text = "Also " + stats.otherTotals.entries.joinToString(", ") {
                    Money.format(it.value, it.key)
                } + " — not converted, tap a currency above to see it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeatmapGrid(
    month: YearMonth,
    days: List<DayCell>,
    maxDayMinor: Long,
    selected: LocalDate?,
    currency: String,
    onSelect: (LocalDate) -> Unit,
) {
    val ramp = heatmapRamp(isSystemInDarkTheme())
    // Read through Compose's locale so the grid re-lays-out if the user changes
    // language — which moves the first day of the week and the day initials.
    val locale = rememberJavaLocale()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val weekdays = (0..6).map { firstDayOfWeek.plus(it.toLong()) }

    // How many blank cells before the 1st, so the month starts on the right column.
    val leadingBlanks = ((month.atDay(1).dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val cells: List<DayCell?> = List(leadingBlanks) { null } + days

    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                // Pad the final week so the last row's cells keep the same width.
                val padded = week + List(7 - week.size) { null }
                padded.forEach { cell ->
                    Box(Modifier.weight(1f)) {
                        if (cell == null) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                        } else {
                            DaySquare(
                                cell = cell,
                                level = heatLevel(cell.shownMinor, maxDayMinor, ramp.size),
                                selected = cell.date == selected,
                                isToday = cell.date == LocalDate.now(),
                                currency = currency,
                                ramp = ramp,
                                onClick = { onSelect(cell.date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySquare(
    cell: DayCell,
    level: Int,
    selected: Boolean,
    isToday: Boolean,
    currency: String,
    ramp: com.spendly.ui.theme.HeatmapRamp,
    onClick: () -> Unit,
) {
    val filled = level > 0
    val background = if (filled) ramp.colorFor(level) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val content = if (filled) ramp.onColorFor(level) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(
                when {
                    selected -> Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(10.dp))
                    isToday -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    else -> Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = content,
            )
            if (filled) {
                Text(
                    text = Money.formatCompact(cell.shownMinor, currency),
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            } else if (cell.otherCurrencyCount > 0) {
                // A day whose only spends were in another currency is not empty,
                // but it cannot be placed on this scale either — so it gets a
                // marker rather than a misleading heat level.
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun HeatLegend(maxDayMinor: Long, currency: String) {
    val ramp = heatmapRamp(isSystemInDarkTheme())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "Less",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        )
        (1..ramp.size).forEach { level ->
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ramp.colorFor(level)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "More",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (maxDayMinor > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                "peak ${Money.formatCompact(maxDayMinor, currency)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, totals: Map<String, Long>, count: Int) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            text = date.format(dayTitleFormat),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val summary = if (totals.isEmpty()) {
            "No entries"
        } else {
            totals.entries.joinToString("  ") { Money.format(it.value, it.key) } +
                "  ·  $count ${if (count == 1) "entry" else "entries"}"
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryBar(slice: CategorySlice, currency: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(slice.emoji, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = slice.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Money.format(slice.totalMinor, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${(slice.share * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { slice.share },
            color = Color(slice.colorArgb),
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}
