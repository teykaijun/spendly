package com.spendly.ui.settings

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import com.spendly.data.WatchedApp
import com.spendly.notify.SpendNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SpendRepository.get(app)
    private val prefs = Prefs.get(app)

    val baseCurrency: StateFlow<String> = prefs.baseCurrency
    val autoSave: StateFlow<Boolean> = prefs.autoSave
    val notifyOnDetect: StateFlow<Boolean> = prefs.notifyOnDetect
    val oneTapSave: StateFlow<Boolean> = prefs.oneTapSave
    val minConfidence: StateFlow<Int> = prefs.minConfidence
    val capturePaused: StateFlow<Boolean> = prefs.capturePaused

    val watchedApps: StateFlow<List<WatchedApp>> = repo.watchedAppDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _listenerEnabled = MutableStateFlow(SpendNotificationListener.isEnabled(app))
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    fun refreshListenerState() {
        _listenerEnabled.value = SpendNotificationListener.isEnabled(getApplication())
    }

    fun setBaseCurrency(code: String) = prefs.setBaseCurrency(code)
    fun setAutoSave(v: Boolean) = prefs.setAutoSave(v)
    fun setNotifyOnDetect(v: Boolean) = prefs.setNotifyOnDetect(v)
    fun setOneTapSave(v: Boolean) = prefs.setOneTapSave(v)
    fun setMinConfidence(v: Int) = prefs.setMinConfidence(v)
    fun setCapturePaused(v: Boolean) = prefs.setCapturePaused(v)

    fun setAppEnabled(pkg: String, enabled: Boolean) {
        viewModelScope.launch { repo.watchedAppDao.setEnabled(pkg, enabled) }
    }

    /**
     * Writes a CSV into cache and hands back a share intent. Uses FileProvider
     * because a raw file:// URI is not grantable to another app.
     */
    fun exportCsv(onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val csv = repo.exportCsv()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "spendly-export.csv")
            file.writeText(csv)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Spendly export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onReady(Intent.createChooser(intent, "Export spending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
