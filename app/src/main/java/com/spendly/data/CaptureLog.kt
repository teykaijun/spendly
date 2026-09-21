package com.spendly.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the notification reader recently did, and why.
 *
 * Both bugs that made "notification spends are not saved" happen were silent:
 * one dropped ordinary bank alerts for scoring just under the threshold, the
 * other hid the confirm prompt. Nothing on screen said anything had happened,
 * so there was nothing to report. This log is so the next one is visible.
 *
 * Deliberately stores **no notification text** — only the app name, the
 * outcome, a reason, and the parsed amount when there was one. The listener
 * sees every notification on the phone, and a debugging aid must not become a
 * copy of your messages.
 *
 * SharedPreferences rather than a Room table: it is a small capped list, and
 * adding a table would mean a schema migration for a diagnostic.
 */
class CaptureLog internal constructor(private val sp: SharedPreferences) {

    enum class Outcome { QUEUED, SAVED, IGNORED }

    data class Event(
        val at: Long,
        val appLabel: String,
        val outcome: Outcome,
        /** Human-readable reason, e.g. "confidence 50 below 55". */
        val detail: String,
        /** Formatted amount when the parser found one, e.g. "RM25.50". */
        val amount: String? = null,
    )

    private val _events = MutableStateFlow(load())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    @Synchronized
    fun record(event: Event) {
        val next = (listOf(event) + _events.value).take(MAX_EVENTS)
        _events.value = next
        sp.edit { putString(KEY, encode(next)) }
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
        sp.edit { remove(KEY) }
    }

    private fun load(): List<Event> = runCatching {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Event(
                at = o.optLong("at"),
                appLabel = o.optString("app"),
                outcome = runCatching { Outcome.valueOf(o.optString("outcome")) }
                    .getOrDefault(Outcome.IGNORED),
                detail = o.optString("detail"),
                amount = o.optString("amount").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(events: List<Event>): String = JSONArray().apply {
        events.forEach { e ->
            put(
                JSONObject()
                    .put("at", e.at)
                    .put("app", e.appLabel)
                    .put("outcome", e.outcome.name)
                    .put("detail", e.detail)
                    .put("amount", e.amount ?: ""),
            )
        }
    }.toString()

    companion object {
        private const val FILE = "spendly_capture_log"
        private const val KEY = "events"
        private const val MAX_EVENTS = 40

        @Volatile
        private var instance: CaptureLog? = null

        fun get(context: Context): CaptureLog = instance ?: synchronized(this) {
            instance ?: CaptureLog(
                context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
