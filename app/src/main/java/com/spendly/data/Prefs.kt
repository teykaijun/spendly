package com.spendly.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Currency
import java.util.Locale

/**
 * App settings. Backed by SharedPreferences (small, synchronous, survives
 * reinstall-from-backup) and surfaced as StateFlows so Compose can read them
 * directly. The notification listener also reads these, from its own process
 * entry point, which is why every getter goes back to disk-backed state rather
 * than an in-memory cache owned by the UI.
 */
class Prefs internal constructor(private val sp: SharedPreferences) {

    private val _baseCurrency = MutableStateFlow(sp.getString(KEY_CURRENCY, null) ?: deviceCurrency())
    val baseCurrency: StateFlow<String> = _baseCurrency.asStateFlow()

    private val _autoSave = MutableStateFlow(sp.getBoolean(KEY_AUTO_SAVE, false))
    val autoSave: StateFlow<Boolean> = _autoSave.asStateFlow()

    private val _notifyOnDetect = MutableStateFlow(sp.getBoolean(KEY_NOTIFY_ON_DETECT, true))
    val notifyOnDetect: StateFlow<Boolean> = _notifyOnDetect.asStateFlow()

    private val _oneTapSave = MutableStateFlow(sp.getBoolean(KEY_ONE_TAP_SAVE, true))
    val oneTapSave: StateFlow<Boolean> = _oneTapSave.asStateFlow()

    private val _minConfidence = MutableStateFlow(sp.getInt(KEY_MIN_CONFIDENCE, 55))
    val minConfidence: StateFlow<Int> = _minConfidence.asStateFlow()

    private val _monthlyBudgetMinor = MutableStateFlow(sp.getLong(KEY_MONTHLY_BUDGET, 0L))
    val monthlyBudgetMinor: StateFlow<Long> = _monthlyBudgetMinor.asStateFlow()

    private val _capturePaused = MutableStateFlow(sp.getBoolean(KEY_CAPTURE_PAUSED, false))
    val capturePaused: StateFlow<Boolean> = _capturePaused.asStateFlow()

    /** Where the updater looks: "owner/repo" for GitHub, or an https JSON feed. */
    private val _updateSource = MutableStateFlow(sp.getString(KEY_UPDATE_SOURCE, "") ?: "")
    val updateSource: StateFlow<String> = _updateSource.asStateFlow()

    /**
     * Off by default on purpose. While it is off, the app makes no network
     * request unless you press Check for updates yourself.
     */
    private val _checkUpdatesOnOpen = MutableStateFlow(sp.getBoolean(KEY_CHECK_ON_OPEN, false))
    val checkUpdatesOnOpen: StateFlow<Boolean> = _checkUpdatesOnOpen.asStateFlow()

    fun setBaseCurrency(v: String) = set(KEY_CURRENCY, _baseCurrency, v) { putString(KEY_CURRENCY, v) }
    fun setAutoSave(v: Boolean) = set(KEY_AUTO_SAVE, _autoSave, v) { putBoolean(KEY_AUTO_SAVE, v) }
    fun setNotifyOnDetect(v: Boolean) = set(KEY_NOTIFY_ON_DETECT, _notifyOnDetect, v) { putBoolean(KEY_NOTIFY_ON_DETECT, v) }
    fun setOneTapSave(v: Boolean) = set(KEY_ONE_TAP_SAVE, _oneTapSave, v) { putBoolean(KEY_ONE_TAP_SAVE, v) }
    fun setMinConfidence(v: Int) = set(KEY_MIN_CONFIDENCE, _minConfidence, v) { putInt(KEY_MIN_CONFIDENCE, v) }
    fun setMonthlyBudgetMinor(v: Long) = set(KEY_MONTHLY_BUDGET, _monthlyBudgetMinor, v) { putLong(KEY_MONTHLY_BUDGET, v) }
    fun setCapturePaused(v: Boolean) = set(KEY_CAPTURE_PAUSED, _capturePaused, v) { putBoolean(KEY_CAPTURE_PAUSED, v) }
    fun setUpdateSource(v: String) = set(KEY_UPDATE_SOURCE, _updateSource, v) { putString(KEY_UPDATE_SOURCE, v) }
    fun setCheckUpdatesOnOpen(v: Boolean) = set(KEY_CHECK_ON_OPEN, _checkUpdatesOnOpen, v) { putBoolean(KEY_CHECK_ON_OPEN, v) }

    private inline fun <T> set(
        @Suppress("UNUSED_PARAMETER") key: String,
        flow: MutableStateFlow<T>,
        value: T,
        crossinline write: SharedPreferences.Editor.() -> Unit,
    ) {
        sp.edit { write() }
        flow.value = value
    }

    companion object {
        private const val FILE = "spendly_prefs"
        private const val KEY_CURRENCY = "base_currency"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_NOTIFY_ON_DETECT = "notify_on_detect"
        private const val KEY_ONE_TAP_SAVE = "one_tap_save"
        private const val KEY_MIN_CONFIDENCE = "min_confidence"
        private const val KEY_MONTHLY_BUDGET = "monthly_budget"
        private const val KEY_CAPTURE_PAUSED = "capture_paused"
        private const val KEY_UPDATE_SOURCE = "update_source"
        private const val KEY_CHECK_ON_OPEN = "check_updates_on_open"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }

        private fun deviceCurrency(): String = try {
            Currency.getInstance(Locale.getDefault()).currencyCode
        } catch (e: Exception) {
            "USD"
        }
    }
}
