package com.spendly.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.Category
import com.spendly.data.SpendEntry
import com.spendly.data.SpendRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Backs the edit sheet. Shared by every screen that lists entries, so editing
 * behaves identically wherever you tap a row.
 */
class EditEntryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)

    val categories: StateFlow<List<Category>> = repo.categoryDao.activeByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        original: SpendEntry,
        amountMinor: Long,
        currency: String,
        categoryId: Long,
        merchant: String,
        note: String,
        date: LocalDate,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repo.editEntry(
                original = original,
                amountMinor = amountMinor,
                currency = currency,
                categoryId = categoryId,
                merchant = merchant,
                note = note,
                date = date,
            )
            onDone()
        }
    }

    fun delete(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteEntry(id)
            onDone()
        }
    }
}
