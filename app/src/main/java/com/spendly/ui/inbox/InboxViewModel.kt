package com.spendly.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.Category
import com.spendly.data.PendingEntry
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import com.spendly.notify.PendingAlerts
import com.spendly.notify.SpendNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    val pending: StateFlow<List<PendingEntry>> = repo.pendingDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = repo.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val categories: StateFlow<List<Category>> = repo.categoryDao.activeByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capturePaused: StateFlow<Boolean> = prefs.capturePaused

    /**
     * Whether the OS has granted notification access. Re-read on demand because
     * the user grants it in system settings, which gives us no callback.
     */
    private val _listenerEnabled = MutableStateFlow(SpendNotificationListener.isEnabled(app))
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    fun refreshListenerState() {
        _listenerEnabled.value = SpendNotificationListener.isEnabled(getApplication())
    }

    /** Which row has its category picker open. */
    private val _expandedId = MutableStateFlow<Long?>(null)
    val expandedId: StateFlow<Long?> = _expandedId.asStateFlow()

    fun toggleExpanded(id: Long) {
        _expandedId.value = if (_expandedId.value == id) null else id
    }

    fun confirm(id: Long, categoryId: Long? = null) {
        viewModelScope.launch {
            repo.confirmPending(id, categoryId)
            PendingAlerts.cancelPending(getApplication(), id)
            if (_expandedId.value == id) _expandedId.value = null
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            repo.dismissPending(id)
            PendingAlerts.cancelPending(getApplication(), id)
            if (_expandedId.value == id) _expandedId.value = null
        }
    }

    fun confirmAll() {
        viewModelScope.launch {
            val ids = pending.value.map { it.id }
            repo.confirmAllPending()
            ids.forEach { PendingAlerts.cancelPending(getApplication(), it) }
            _expandedId.value = null
        }
    }

    fun dismissAll() {
        viewModelScope.launch {
            val ids = pending.value.map { it.id }
            repo.dismissAllPending()
            ids.forEach { PendingAlerts.cancelPending(getApplication(), it) }
            _expandedId.value = null
        }
    }

    fun setPaused(paused: Boolean) = prefs.setCapturePaused(paused)
}
