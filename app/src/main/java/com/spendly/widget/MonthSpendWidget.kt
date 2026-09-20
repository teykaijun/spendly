package com.spendly.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.spendly.MainActivity
import com.spendly.R
import com.spendly.data.Money
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: what you have spent this month.
 *
 * Shows the total in your default currency, plus how many entries and the daily
 * average. Tapping the total opens the app; tapping "Add spend" goes straight to
 * the keypad, which is the whole point of having it on the home screen.
 *
 * Totals are per-currency for the same reason as everywhere else — nothing is
 * converted — so the headline figure is your default currency and any others are
 * noted underneath rather than folded in.
 */
class MonthSpendWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The month rolls over and the launcher does not know to ask us.
        if (intent.action == Intent.ACTION_DATE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            refresh(context)
        }
    }

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        if (ids.isEmpty()) return
        val appContext = context.applicationContext

        // A BroadcastReceiver cannot block, and this reads the database.
        val pendingResult = goAsync()
        scope.launch {
            try {
                val repo = SpendRepository.get(appContext)
                val currency = Prefs.get(appContext).baseCurrency.value
                val month = YearMonth.now()

                val rows = repo.entriesInRange(month.atDay(1), month.atEndOfMonth())
                val inCurrency = rows.filter { it.entry.currency == currency }
                val total = inCurrency.sumOf { it.entry.amountMinor }
                val others = rows
                    .filter { it.entry.currency != currency }
                    .groupBy { it.entry.currency }
                    .mapValues { (_, list) -> list.sumOf { it.entry.amountMinor } }

                // Average across days elapsed, not days in the month — on the 3rd,
                // dividing a month's spending by 30 would be meaningless.
                val today = LocalDate.now()
                val daysElapsed = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
                val perDay = if (daysElapsed > 0) total / daysElapsed else 0L

                val views = RemoteViews(appContext.packageName, R.layout.widget_month_spend).apply {
                    setTextViewText(R.id.widget_month, month.format(monthFormat).uppercase(Locale.getDefault()))
                    setTextViewText(R.id.widget_currency, currency)
                    setTextViewText(R.id.widget_total, Money.format(total, currency))
                    setTextViewText(
                        R.id.widget_secondary,
                        buildString {
                            append(rows.size)
                            append(if (rows.size == 1) " entry · " else " entries · ")
                            append(Money.format(perDay, currency))
                            append("/day")
                            if (others.isNotEmpty()) {
                                append("\nalso ")
                                append(others.entries.joinToString(", ") { Money.format(it.value, it.key) })
                            }
                        },
                    )
                    setOnClickPendingIntent(R.id.widget_root, openApp(appContext, OPEN_APP_REQUEST))
                    setOnClickPendingIntent(R.id.widget_add, openApp(appContext, OPEN_ADD_REQUEST))
                }

                ids.forEach { id -> manager.updateAppWidget(id, views) }
            } catch (e: Exception) {
                // A widget that fails to draw must not take the process with it.
                Log.w(TAG, "Widget update failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "SpendlyWidget"
        private const val OPEN_APP_REQUEST = 900
        private const val OPEN_ADD_REQUEST = 901

        private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Redraws every placed widget. Called after anything that changes the
         * month's total, so the home screen never shows a stale figure.
         *
         * Safe to call when no widget is placed — the id list is simply empty.
         */
        fun refresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(appContext, MonthSpendWidget::class.java))
            if (ids.isEmpty()) return
            appContext.sendBroadcast(
                Intent(appContext, MonthSpendWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
