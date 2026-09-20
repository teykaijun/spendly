package com.spendly.ui.quickadd

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.Category
import com.spendly.data.EntryWithCategory
import com.spendly.data.Money
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Amount entry deliberately has no decimal key. Digits fill from the right the
 * way an ATM keypad does — "1", "2", "5", "0" reads as 12.50 — because the
 * decimal point is the single most common source of mis-keyed amounts, and
 * removing it takes a keystroke out of every entry.
 */
data class QuickAddState(
    val digits: String = "",
    val selectedCategoryId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val merchant: String = "",
    val note: String = "",
    val detailsExpanded: Boolean = false,
    /**
     * Currency for this entry, when it is not the base one. Null means "use the
     * base currency", so changing the base in Settings is picked up here without
     * this screen having to be told.
     */
    val currencyOverride: String? = null,
)

/** What a successful save produced, so the UI can offer an Undo. */
data class SavedReceipt(
    val entryId: Long,
    val label: String,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuickAddViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    private val _state = MutableStateFlow(QuickAddState())
    val state: StateFlow<QuickAddState> = _state.asStateFlow()

    val baseCurrency: StateFlow<String> = prefs.baseCurrency
    val oneTapSave: StateFlow<Boolean> = prefs.oneTapSave

    val categories: StateFlow<List<Category>> = repo.categoryDao.activeByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Entries on whichever date the user is currently adding to. */
    val entriesOnDate: StateFlow<List<EntryWithCategory>> = _state
        .map { it.date.toEpochDay() }
        // Without this, every keypress re-emits the same day and resubscribes
        // the database query underneath the list.
        .distinctUntilChanged()
        .flatMapLatest { repo.entryDao.entriesForDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalOnDate: StateFlow<Long> = entriesOnDate
        .map { list -> list.sumOf { it.entry.amountMinor } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** The currency the entry being typed will be saved in. */
    val activeCurrency: StateFlow<String> = combine(_state, prefs.baseCurrency) { s, base ->
        s.currencyOverride ?: base
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.baseCurrency.value)

    val amountMinor: StateFlow<Long> = combine(_state, prefs.baseCurrency) { s, base ->
        toMinor(s.digits, s.currencyOverride ?: base)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * Currencies offered first in the picker: the base one, then whatever has
     * actually been used recently. Travelling means switching between two or
     * three currencies, not hunting through a list of thirty.
     */
    val recentCurrencies: StateFlow<List<String>> = repo.entryDao.recentEntries(200)
        .map { rows ->
            val base = prefs.baseCurrency.value
            (listOf(base) + rows.map { it.entry.currency }).distinct().take(6)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(prefs.baseCurrency.value))

    /**
     * Keypad digits to stored minor units.
     *
     * Amounts are always stored x100. For currencies that have no minor unit
     * (JPY, KRW, VND) each keypress is a whole unit, so "1250" means 1,250 —
     * not 12.50 — and has to be scaled up to match the storage convention.
     */
    private fun toMinor(digits: String, currency: String): Long {
        val raw = digits.toLongOrNull() ?: 0L
        return if (Money.decimals(currency) == 0) raw * 100 else raw
    }

    // ---- keypad ----

    fun appendDigit(d: Char) {
        _state.value = _state.value.let { s ->
            // Cap at a hundred million major units; past that it is a typo.
            if (s.digits.length >= 10) s else s.copy(digits = (s.digits + d).trimStart('0'))
        }
    }

    fun appendDoubleZero() {
        if (_state.value.digits.isEmpty()) return
        appendDigit('0')
        appendDigit('0')
    }

    fun backspace() {
        _state.value = _state.value.let { it.copy(digits = it.digits.dropLast(1)) }
    }

    fun clearAmount() {
        _state.value = _state.value.copy(digits = "")
    }

    // ---- fields ----

    fun selectCategory(id: Long) {
        _state.value = _state.value.copy(selectedCategoryId = id)
    }

    fun setDate(date: LocalDate) {
        _state.value = _state.value.copy(date = date)
    }

    /**
     * Sets the currency for the entry being typed. The choice deliberately
     * survives a save — when you are abroad you log several spends in a row in
     * the same currency — so the chip is highlighted whenever it is not your
     * base currency, to make the non-default state obvious.
     */
    fun setCurrency(code: String) {
        _state.value = _state.value.copy(
            currencyOverride = code.takeIf { !it.equals(prefs.baseCurrency.value, ignoreCase = true) },
        )
    }

    fun setMerchant(value: String) {
        _state.value = _state.value.copy(merchant = value)
    }

    fun setNote(value: String) {
        _state.value = _state.value.copy(note = value)
    }

    fun toggleDetails() {
        _state.value = _state.value.copy(detailsExpanded = !_state.value.detailsExpanded)
    }

    val canSave: Boolean
        get() = toMinor(
            _state.value.digits,
            _state.value.currencyOverride ?: prefs.baseCurrency.value,
        ) > 0L

    /**
     * Saves and resets to a clean slate, so the next entry can start immediately.
     * Returns null when there is nothing to save.
     */
    fun save(categoryId: Long? = null, onSaved: (SavedReceipt) -> Unit) {
        val snapshot = _state.value
        val currencyNow = snapshot.currencyOverride ?: prefs.baseCurrency.value
        val minor = toMinor(snapshot.digits, currencyNow)
        if (minor <= 0L) return

        viewModelScope.launch {
            val category = categoryId
                ?: snapshot.selectedCategoryId
                ?: repo.fallbackCategoryId()
            val currency = currencyNow

            val id = repo.addManualEntry(
                amountMinor = minor,
                currency = currency,
                categoryId = category,
                merchant = snapshot.merchant,
                note = snapshot.note,
                date = snapshot.date,
            )

            val categoryName = categories.value.firstOrNull { it.id == category }?.name.orEmpty()
            onSaved(
                SavedReceipt(
                    entryId = id,
                    label = buildString {
                        append("Saved ").append(Money.format(minor, currency))
                        if (categoryName.isNotEmpty()) append(" · ").append(categoryName)
                    },
                ),
            )

            // Keep the date and the currency — people add several entries for
            // one day, and several in a row in one currency when travelling —
            // but clear everything that belongs to the entry just saved.
            _state.value = QuickAddState(
                date = snapshot.date,
                currencyOverride = snapshot.currencyOverride,
            )
        }
    }

    fun undo(entryId: Long) {
        viewModelScope.launch { repo.deleteEntry(entryId) }
    }

    fun deleteEntry(entryId: Long) {
        viewModelScope.launch { repo.deleteEntry(entryId) }
    }
}
