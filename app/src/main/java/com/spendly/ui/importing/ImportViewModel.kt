package com.spendly.ui.importing

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.Category
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import com.spendly.parser.CategoryGuesser
import com.spendly.pdf.PdfTextExtractor
import com.spendly.pdf.StatementParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One parsed statement line, plus whether the user wants to import it. */
data class ImportRow(
    val row: StatementParser.Row,
    val selected: Boolean,
    val categoryId: Long?,
    val categoryName: String?,
    /** Already in the database from a previous import of the same statement. */
    val duplicate: Boolean,
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class NeedsPassword(val uri: Uri, val wrongAttempt: Boolean) : ImportState
    data class Review(
        val fileName: String,
        val rows: List<ImportRow>,
        val skipped: List<StatementParser.Row>,
        val currency: String,
        val pages: Int,
    ) : ImportState

    data class Imported(val count: Int, val skippedTransfers: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = repo.categoryDao.activeByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pendingUri: Uri? = null

    fun pick(uri: Uri, fileName: String) {
        pendingUri = uri
        read(uri, fileName, password = null)
    }

    fun submitPassword(password: String) {
        val uri = pendingUri ?: return
        read(uri, currentFileName, password)
    }

    private var currentFileName: String = "statement.pdf"

    private fun read(uri: Uri, fileName: String, password: String?) {
        currentFileName = fileName
        _state.value = ImportState.Reading
        viewModelScope.launch {
            when (val extracted = PdfTextExtractor.extract(getApplication(), uri, password)) {
                is PdfTextExtractor.Result.NeedsPassword ->
                    _state.value = ImportState.NeedsPassword(uri, wrongAttempt = false)

                is PdfTextExtractor.Result.WrongPassword ->
                    _state.value = ImportState.NeedsPassword(uri, wrongAttempt = true)

                is PdfTextExtractor.Result.NoTextLayer ->
                    _state.value = ImportState.Failed(
                        "That PDF has no text in it — it is probably a scan or a photo. " +
                            "Spendly reads text, not images, so this one has to be entered by hand.",
                    )

                is PdfTextExtractor.Result.Failed ->
                    _state.value = ImportState.Failed(extracted.message)

                is PdfTextExtractor.Result.Text ->
                    buildReview(fileName, extracted.text, extracted.pages)
            }
        }
    }

    private suspend fun buildReview(fileName: String, text: String, pages: Int) {
        val base = prefs.baseCurrency.value
        val year = StatementParser.detectYear(text)
        val parsed = StatementParser.parse(text, base, year ?: java.time.LocalDate.now().year)

        if (parsed.rows.isEmpty()) {
            _state.value = ImportState.Failed(
                "No transactions could be read from that statement. The layout may be " +
                    "one Spendly does not recognise yet.",
            )
            return
        }

        val cats = repo.categoryDao.activeOnce()
        val fallback = repo.fallbackCategoryId()

        val rows = parsed.spends.map { row ->
            val guessName = CategoryGuesser.guess(row.description, row.description)
            val cat = cats.firstOrNull { it.name.equals(guessName, ignoreCase = true) }
            ImportRow(
                row = row,
                // Duplicates start unticked so re-importing an overlapping
                // statement does not quietly double everything.
                selected = !isDuplicate(row),
                categoryId = cat?.id ?: fallback,
                categoryName = cat?.name ?: cats.firstOrNull { it.id == fallback }?.name,
                duplicate = isDuplicate(row),
            )
        }

        _state.value = ImportState.Review(
            fileName = fileName,
            rows = rows,
            skipped = parsed.skipped,
            currency = parsed.currency,
            pages = pages,
        )
    }

    private suspend fun isDuplicate(row: StatementParser.Row): Boolean {
        val key = dedupeKeyFor(row) ?: return false
        // A statement line is the same transaction whenever the date, amount and
        // description match, so the window is wide rather than a few hours.
        return repo.entryDao.countByDedupeKeySince(key, 0L) > 0
    }

    private fun dedupeKeyFor(row: StatementParser.Row): String? {
        val date = row.date ?: return null
        val merchant = row.description.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return "statement|${date.toEpochDay()}|${row.amountMinor}|${row.currency}|$merchant"
    }

    fun toggle(index: Int) {
        val current = _state.value as? ImportState.Review ?: return
        _state.value = current.copy(
            rows = current.rows.mapIndexed { i, r ->
                if (i == index) r.copy(selected = !r.selected) else r
            },
        )
    }

    fun setCategory(index: Int, category: Category) {
        val current = _state.value as? ImportState.Review ?: return
        _state.value = current.copy(
            rows = current.rows.mapIndexed { i, r ->
                if (i == index) r.copy(categoryId = category.id, categoryName = category.name) else r
            },
        )
    }

    fun selectAll(selected: Boolean) {
        val current = _state.value as? ImportState.Review ?: return
        _state.value = current.copy(rows = current.rows.map { it.copy(selected = selected) })
    }

    fun confirmImport() {
        val current = _state.value as? ImportState.Review ?: return
        val chosen = current.rows.filter { it.selected }
        if (chosen.isEmpty()) return

        viewModelScope.launch {
            val fallback = repo.fallbackCategoryId()
            var written = 0
            chosen.forEach { item ->
                val date = item.row.date ?: return@forEach
                repo.addImportedEntry(
                    amountMinor = item.row.amountMinor,
                    currency = item.row.currency,
                    categoryId = item.categoryId ?: fallback,
                    merchant = item.row.description.take(60),
                    date = date,
                    dedupeKey = dedupeKeyFor(item.row),
                )
                written++
            }
            _state.value = ImportState.Imported(
                count = written,
                skippedTransfers = current.skipped.count { it.kind == StatementParser.Kind.TRANSFER },
            )
        }
    }

    fun reset() {
        pendingUri = null
        _state.value = ImportState.Idle
    }
}
