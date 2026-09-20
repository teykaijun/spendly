package com.spendly.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.EntryWithCategory
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** One square in the month grid. */
data class DayCell(
    val date: LocalDate,
    /** Total in the currency currently being displayed — the heat level's input. */
    val shownMinor: Long,
    /** Entries in a different currency, which cannot be summed with the above. */
    val otherCurrencyCount: Int,
    val entryCount: Int,
)

data class CategorySlice(
    val name: String,
    val emoji: String,
    val colorArgb: Int,
    val totalMinor: Long,
    val share: Float,
)

data class MonthStats(
    val totalShownMinor: Long = 0L,
    /** Totals for every other currency, kept separate rather than converted. */
    val otherTotals: Map<String, Long> = emptyMap(),
    val daysWithSpend: Int = 0,
    val avgPerActiveDayMinor: Long = 0L,
    val busiest: LocalDate? = null,
    val maxDayMinor: Long = 0L,
    val categories: List<CategorySlice> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    val baseCurrency: StateFlow<String> = prefs.baseCurrency

    /** null means "follow the base currency from Settings". */
    private val _currencyOverride = MutableStateFlow<String?>(null)

    /**
     * Which currency the heatmap and the month total are showing.
     *
     * Totals are never converted between currencies — the app holds no exchange
     * rates — so with spending in several currencies the honest thing is to let
     * you pick which one you are looking at, and report the others separately.
     */
    val displayCurrency: StateFlow<String> = combine(_currencyOverride, prefs.baseCurrency) { override, base ->
        override ?: base
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.baseCurrency.value)

    private val monthEntries: StateFlow<List<EntryWithCategory>> = _month
        .flatMapLatest { ym ->
            repo.entryDao.entriesBetween(
                ym.atDay(1).toEpochDay(),
                ym.atEndOfMonth().toEpochDay(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Totals are computed in Kotlin rather than SQL because entries can be in
     * different currencies and SUM() over mixed currencies is simply wrong. A
     * month is at most a few hundred rows, so the cost is irrelevant.
     */
    val days: StateFlow<List<DayCell>> = combine(monthEntries, _month, displayCurrency) { entries, ym, shown ->
        val byDay = entries.groupBy { it.entry.epochDay }
        (1..ym.lengthOfMonth()).map { dayOfMonth ->
            val date = ym.atDay(dayOfMonth)
            val forDay = byDay[date.toEpochDay()].orEmpty()
            DayCell(
                date = date,
                shownMinor = forDay.filter { it.entry.currency == shown }.sumOf { it.entry.amountMinor },
                otherCurrencyCount = forDay.count { it.entry.currency != shown },
                entryCount = forDay.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<MonthStats> = combine(monthEntries, displayCurrency, days) { entries, shown, dayCells ->
        val shownEntries = entries.filter { it.entry.currency == shown }
        val total = shownEntries.sumOf { it.entry.amountMinor }

        val foreign = entries
            .filter { it.entry.currency != shown }
            .groupBy { it.entry.currency }
            .mapValues { (_, rows) -> rows.sumOf { it.entry.amountMinor } }

        val activeDays = dayCells.count { it.entryCount > 0 }
        val busiestCell = dayCells.maxByOrNull { it.shownMinor }?.takeIf { it.shownMinor > 0 }

        val categories = shownEntries
            .groupBy { it.category?.name ?: "Uncategorised" }
            .map { (name, rows) ->
                val sum = rows.sumOf { it.entry.amountMinor }
                CategorySlice(
                    name = name,
                    emoji = rows.firstOrNull()?.category?.emoji ?: "➕",
                    colorArgb = rows.firstOrNull()?.category?.colorArgb ?: 0xFF90A4AE.toInt(),
                    totalMinor = sum,
                    share = if (total > 0) sum.toFloat() / total.toFloat() else 0f,
                )
            }
            .sortedByDescending { it.totalMinor }

        MonthStats(
            totalShownMinor = total,
            otherTotals = foreign,
            daysWithSpend = activeDays,
            avgPerActiveDayMinor = if (activeDays > 0) total / activeDays else 0L,
            busiest = busiestCell?.date,
            maxDayMinor = dayCells.maxOfOrNull { it.shownMinor } ?: 0L,
            categories = categories,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthStats())

    /**
     * Currencies actually present in the visible month, with the display one
     * guaranteed to appear so the selected chip never vanishes mid-month.
     */
    val currenciesInMonth: StateFlow<List<String>> = combine(monthEntries, displayCurrency) { entries, shown ->
        (listOf(shown) + entries.map { it.entry.currency }).distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun showCurrency(code: String) {
        _currencyOverride.value = code.takeIf { !it.equals(prefs.baseCurrency.value, ignoreCase = true) }
    }

    val selectedEntries: StateFlow<List<EntryWithCategory>> = _selectedDate
        .flatMapLatest { date ->
            if (date == null) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                repo.entryDao.entriesForDay(date.toEpochDay())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedTotal: StateFlow<Map<String, Long>> = selectedEntries
        .map { rows ->
            rows.groupBy { it.entry.currency }
                .mapValues { (_, list) -> list.sumOf { it.entry.amountMinor } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun showMonth(ym: YearMonth) {
        _month.value = ym
        // Keep a selection inside the visible month so the detail pane is never
        // showing a day the user can no longer see.
        val current = _selectedDate.value
        if (current == null || YearMonth.from(current) != ym) {
            _selectedDate.value = null
        }
    }

    fun previousMonth() = showMonth(_month.value.minusMonths(1))

    fun nextMonth() = showMonth(_month.value.plusMonths(1))

    fun jumpToToday() {
        _month.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }

    fun select(date: LocalDate?) {
        _selectedDate.value = if (_selectedDate.value == date) null else date
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { repo.deleteEntry(id) }
    }
}
