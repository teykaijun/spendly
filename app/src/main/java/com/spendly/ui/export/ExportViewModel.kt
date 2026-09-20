package com.spendly.ui.export

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.EntryWithCategory
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The named ranges worth one tap. "Custom" is the escape hatch, and everything
 * else exists so the common cases never need a date picker at all.
 */
enum class RangePreset(val label: String) {
    ThisMonth("This month"),
    LastMonth("Last month"),
    Last30Days("Last 30 days"),
    ThisYear("This year"),
    Custom("Custom"),
    Everything("Everything"),
    ;

    /** null..null means unbounded, which the repository reads as "no limit". */
    fun bounds(today: LocalDate = LocalDate.now()): Pair<LocalDate?, LocalDate?> = when (this) {
        ThisMonth -> YearMonth.from(today).let { it.atDay(1) to it.atEndOfMonth() }
        LastMonth -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        Last30Days -> today.minusDays(29) to today
        ThisYear -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        Custom -> null to null
        Everything -> null to null
    }
}

/**
 * What the chosen range actually contains. Shown before exporting so the file
 * is never a surprise, and useful on its own as a "what did I spend between
 * these dates" answer.
 */
data class RangeSummary(
    val entries: List<EntryWithCategory> = emptyList(),
    /** Per currency, because totals across currencies are not summable. */
    val totals: Map<String, Long> = emptyMap(),
    val loading: Boolean = false,
)

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    val baseCurrency: StateFlow<String> = prefs.baseCurrency

    private val _preset = MutableStateFlow(RangePreset.ThisMonth)
    val preset: StateFlow<RangePreset> = _preset.asStateFlow()

    private val _customFrom = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val customFrom: StateFlow<LocalDate> = _customFrom.asStateFlow()

    private val _customTo = MutableStateFlow(LocalDate.now())
    val customTo: StateFlow<LocalDate> = _customTo.asStateFlow()

    private val _summary = MutableStateFlow(RangeSummary())
    val summary: StateFlow<RangeSummary> = _summary.asStateFlow()

    init {
        reload()
    }

    /** The resolved bounds for whatever is selected right now. */
    fun activeBounds(): Pair<LocalDate?, LocalDate?> = when (_preset.value) {
        RangePreset.Custom -> _customFrom.value to _customTo.value
        else -> _preset.value.bounds()
    }

    fun selectPreset(value: RangePreset) {
        _preset.value = value
        reload()
    }

    fun setCustomFrom(date: LocalDate) {
        _customFrom.value = date
        // Keep the range valid rather than rejecting the tap — dragging the
        // start past the end should move the end, not produce an error.
        if (_customTo.value < date) _customTo.value = date
        _preset.value = RangePreset.Custom
        reload()
    }

    fun setCustomTo(date: LocalDate) {
        _customTo.value = date
        if (date < _customFrom.value) _customFrom.value = date
        _preset.value = RangePreset.Custom
        reload()
    }

    private fun reload() {
        val (from, to) = activeBounds()
        _summary.value = _summary.value.copy(loading = true)
        viewModelScope.launch {
            val rows = repo.entriesInRange(from, to)
            _summary.value = RangeSummary(
                entries = rows.sortedWith(
                    compareByDescending<EntryWithCategory> { it.entry.epochDay }
                        .thenByDescending { it.entry.createdAt },
                ),
                totals = rows.groupBy { it.entry.currency }
                    .mapValues { (_, list) -> list.sumOf { it.entry.amountMinor } },
                loading = false,
            )
        }
    }

    /** Builds the CSV for the active range and hands back a share intent. */
    fun export(onReady: (Intent) -> Unit, onEmpty: () -> Unit) {
        if (_summary.value.entries.isEmpty()) {
            onEmpty()
            return
        }
        val (from, to) = activeBounds()
        viewModelScope.launch {
            val context = getApplication<Application>()
            val csv = repo.exportCsv(from, to)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "${fileNameFor(from, to)}.csv")
            file.writeText(csv)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Spendly — ${rangeLabel()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onReady(
                Intent.createChooser(intent, "Export spending")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun rangeLabel(): String {
        val (from, to) = activeBounds()
        return when {
            from == null || to == null -> "all time"
            else -> "${from.format(fileFormat)} to ${to.format(fileFormat)}"
        }
    }

    /** A filename that says what is in the file without needing to open it. */
    private fun fileNameFor(from: LocalDate?, to: LocalDate?): String = when {
        from == null || to == null -> "spendly-all"
        else -> "spendly-${from.format(fileFormat)}_to_${to.format(fileFormat)}"
    }

    private companion object {
        val fileFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
