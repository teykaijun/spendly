package com.spendly.data

import android.app.Application
import android.content.Context
import com.spendly.parser.CategoryGuesser
import com.spendly.parser.NotificationParser
import com.spendly.widget.MonthSpendWidget
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single place that knows how a notification becomes a spend, and the only
 * type the UI and the notification listener both talk to.
 */
class SpendRepository private constructor(
    private val db: AppDatabase,
    private val prefs: Prefs,
    /** Application, not a bare Context: this instance is a process-wide singleton. */
    private val app: Application,
) {

    /**
     * Anything that changes a total has to poke the home-screen widget, which
     * otherwise only refreshes on its own half-hourly schedule. Cheap and a
     * no-op when no widget is placed.
     */
    private fun notifyWidget() = MonthSpendWidget.refresh(app)

    val entryDao: EntryDao get() = db.entryDao()
    val categoryDao: CategoryDao get() = db.categoryDao()
    val pendingDao: PendingDao get() = db.pendingDao()
    val watchedAppDao: WatchedAppDao get() = db.watchedAppDao()

    fun pendingCount(): Flow<Int> = db.pendingDao().count()

    // ------------------------------------------------------------------
    // Manual entry
    // ------------------------------------------------------------------

    suspend fun addManualEntry(
        amountMinor: Long,
        currency: String,
        categoryId: Long,
        merchant: String? = null,
        note: String? = null,
        date: LocalDate = LocalDate.now(),
    ): Long {
        val id = db.entryDao().insert(
            SpendEntry(
                amountMinor = amountMinor,
                currency = currency,
                categoryId = categoryId,
                merchant = merchant?.takeIf { it.isNotBlank() },
                note = note?.takeIf { it.isNotBlank() },
                epochDay = date.toEpochDay(),
                source = Source.MANUAL,
            ),
        )
        db.categoryDao().bumpUsage(categoryId)
        notifyWidget()
        return id
    }

    /**
     * An entry read out of a PDF statement. Separate from [addManualEntry] so
     * imports carry their own source and dedupe key — re-importing an
     * overlapping statement must not silently double every row.
     */
    suspend fun addImportedEntry(
        amountMinor: Long,
        currency: String,
        categoryId: Long,
        merchant: String?,
        date: LocalDate,
        dedupeKey: String?,
    ): Long {
        val id = db.entryDao().insert(
            SpendEntry(
                amountMinor = amountMinor,
                currency = currency,
                categoryId = categoryId,
                merchant = merchant?.takeIf { it.isNotBlank() },
                note = null,
                epochDay = date.toEpochDay(),
                source = Source.STATEMENT,
                dedupeKey = dedupeKey,
            ),
        )
        db.categoryDao().bumpUsage(categoryId)
        notifyWidget()
        return id
    }

    suspend fun updateEntry(entry: SpendEntry) {
        db.entryDao().update(entry)
        notifyWidget()
    }

    suspend fun deleteEntry(id: Long) {
        db.entryDao().deleteById(id)
        notifyWidget()
    }

    suspend fun entryById(id: Long) = db.entryDao().byId(id)

    // ------------------------------------------------------------------
    // Notification ingest
    // ------------------------------------------------------------------

    sealed interface Ingest {
        /** Not a spend, a duplicate, or from a muted app. */
        data class Ignored(val reason: String) : Ingest

        /** Waiting in the inbox for one-tap confirmation. */
        data class Queued(val pendingId: Long, val preview: PendingEntry) : Ingest

        /** Auto-save was on and confidence cleared the bar. */
        data class Saved(val entryId: Long, val amountMinor: Long, val currency: String) : Ingest
    }

    /**
     * How long an identical notification is treated as a repeat rather than a
     * second, genuine spend. Apps re-post the same notification when it is
     * updated or the phone reconnects, and buying the same coffee twice inside
     * six hours is rarer than that — so the window errs on the side of dropping.
     */
    private val dedupeWindowMs = 6 * 60 * 60 * 1000L

    suspend fun ingestNotification(
        packageName: String,
        appLabel: String,
        title: String?,
        text: String?,
        postedAt: Long = System.currentTimeMillis(),
    ): Ingest {
        // Record the app even if we ignore this one, so Settings can list it.
        // The default only applies the first time an app is seen; after that the
        // user's own on/off choice is preserved.
        db.watchedAppDao().markSeen(
            pkg = packageName,
            label = appLabel,
            now = postedAt,
            defaultEnabled = NotificationParser.defaultEnabledFor(packageName),
        )

        if (prefs.capturePaused.value) return Ingest.Ignored("capture paused")
        if (db.watchedAppDao().isEnabled(packageName) == false) {
            return Ingest.Ignored("app muted")
        }

        val parsed = NotificationParser.parse(
            title = title,
            text = text,
            packageName = packageName,
            appLabel = appLabel,
            baseCurrency = prefs.baseCurrency.value,
        ) ?: return Ingest.Ignored("not a spend")

        if (parsed.confidence < prefs.minConfidence.value) {
            return Ingest.Ignored("confidence ${parsed.confidence} below threshold")
        }

        val merchantKey = NotificationParser.merchantKey(parsed.merchant)
        val dedupeKey = buildString {
            append(packageName).append('|')
            append(parsed.amountMinor).append('|')
            append(parsed.currency).append('|')
            append(merchantKey.orEmpty())
        }

        val since = postedAt - dedupeWindowMs
        if (db.pendingDao().countByDedupeKeySince(dedupeKey, since) > 0) {
            return Ingest.Ignored("duplicate (already queued)")
        }
        if (db.entryDao().countByDedupeKeySince(dedupeKey, since) > 0) {
            return Ingest.Ignored("duplicate (already saved)")
        }

        val categoryId = resolveCategory(merchantKey, parsed.merchant, "${title.orEmpty()} ${text.orEmpty()}")
        val epochDay = epochDayOf(postedAt)

        if (prefs.autoSave.value) {
            val entryId = db.entryDao().insert(
                SpendEntry(
                    amountMinor = parsed.amountMinor,
                    currency = parsed.currency,
                    categoryId = categoryId,
                    merchant = parsed.merchant,
                    note = null,
                    epochDay = epochDay,
                    createdAt = postedAt,
                    source = Source.NOTIFICATION,
                    sourcePackage = packageName,
                    dedupeKey = dedupeKey,
                ),
            )
            db.categoryDao().bumpUsage(categoryId)
            db.watchedAppDao().bumpDetected(packageName)
            notifyWidget()
            return Ingest.Saved(entryId, parsed.amountMinor, parsed.currency)
        }

        val pending = PendingEntry(
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            merchant = parsed.merchant,
            guessedCategoryId = categoryId,
            rawTitle = title.orEmpty(),
            rawText = text.orEmpty(),
            sourcePackage = packageName,
            sourceAppLabel = appLabel,
            postedAt = postedAt,
            epochDay = epochDay,
            confidence = parsed.confidence,
            dedupeKey = dedupeKey,
        )
        val id = db.pendingDao().insert(pending)
        if (id == -1L) return Ingest.Ignored("duplicate (unique key)")
        db.watchedAppDao().bumpDetected(packageName)
        return Ingest.Queued(id, pending.copy(id = id))
    }

    /**
     * A category the user set for this merchant beats the keyword guess, which
     * beats the catch-all bucket. This is what makes the app get quieter over
     * time: one correction and that merchant is right from then on.
     */
    private suspend fun resolveCategory(
        merchantKey: String?,
        merchant: String?,
        rawText: String,
    ): Long {
        if (merchantKey != null) {
            db.merchantRuleDao().byKey(merchantKey)?.let { return it.categoryId }
        }
        val guessedName = CategoryGuesser.guess(merchant, rawText)
        if (guessedName != null) {
            categoryIdByName(guessedName)?.let { return it }
        }
        return fallbackCategoryId()
    }

    private suspend fun categoryIdByName(name: String): Long? =
        db.categoryDao().activeOnce().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    suspend fun fallbackCategoryId(): Long {
        val active = db.categoryDao().activeOnce()
        return active.firstOrNull { it.name.equals(DefaultCategories.FALLBACK, ignoreCase = true) }?.id
            ?: active.firstOrNull()?.id
            ?: db.categoryDao().insert(
                Category(name = DefaultCategories.FALLBACK, emoji = "➕", colorArgb = 0xFF90A4AE.toInt()),
            )
    }

    // ------------------------------------------------------------------
    // Inbox actions
    // ------------------------------------------------------------------

    /**
     * Turns a queued detection into a real entry. Passing [categoryId] records a
     * merchant rule so the same merchant is categorised that way next time.
     */
    suspend fun confirmPending(pendingId: Long, categoryId: Long? = null): Long? {
        val p = db.pendingDao().byId(pendingId) ?: return null
        val finalCategory = categoryId ?: p.guessedCategoryId ?: fallbackCategoryId()

        if (categoryId != null && categoryId != p.guessedCategoryId) {
            NotificationParser.merchantKey(p.merchant)?.let { key ->
                db.merchantRuleDao().upsert(MerchantRule(merchantKey = key, categoryId = categoryId))
            }
        }

        val entryId = db.entryDao().insert(
            SpendEntry(
                amountMinor = p.amountMinor,
                currency = p.currency,
                categoryId = finalCategory,
                merchant = p.merchant,
                note = null,
                epochDay = p.epochDay,
                createdAt = p.postedAt,
                source = Source.NOTIFICATION,
                sourcePackage = p.sourcePackage,
                dedupeKey = p.dedupeKey,
            ),
        )
        db.categoryDao().bumpUsage(finalCategory)
        db.pendingDao().deleteById(pendingId)
        notifyWidget()
        return entryId
    }

    suspend fun dismissPending(pendingId: Long) = db.pendingDao().deleteById(pendingId)

    suspend fun dismissAllPending() = db.pendingDao().deleteAll()

    /**
     * Confirms the whole inbox. Done one at a time rather than as a bulk insert
     * so every entry goes through the same category-rule and usage-count path as
     * a single confirmation. The guard stops a malformed row from looping.
     */
    suspend fun confirmAllPending() {
        var guard = 0
        while (guard++ < 500) {
            val next = db.pendingDao().oldest() ?: break
            if (confirmPending(next.id) == null) {
                db.pendingDao().deleteById(next.id)
            }
        }
    }

    /** Housekeeping: drop detections the user never acted on. */
    suspend fun prunePending(olderThanDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - olderThanDays * 24L * 60 * 60 * 1000
        db.pendingDao().deleteOlderThan(cutoff)
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Entries in a date range, as a one-shot read for the range filter and export.
     * Null bounds mean "no limit", so the same call serves "everything".
     */
    suspend fun entriesInRange(from: LocalDate?, to: LocalDate?): List<EntryWithCategory> =
        db.entryDao().entriesBetweenOnce(
            from?.toEpochDay() ?: Long.MIN_VALUE,
            to?.toEpochDay() ?: Long.MAX_VALUE,
        )

    /**
     * CSV for a date range, or for everything when both bounds are null.
     *
     * Amounts are written unformatted with a plain dot decimal so a spreadsheet
     * reads them as numbers, and the currency stays in its own column — summing
     * across currencies is the reader's problem to think about, not something
     * this file should quietly paper over.
     */
    suspend fun exportCsv(from: LocalDate? = null, to: LocalDate? = null): String {
        val rows = entriesInRange(from, to)
        return buildString {
            appendLine("date,amount,currency,category,merchant,note,source")
            rows.forEach { row ->
                val e = row.entry
                appendLine(
                    listOf(
                        LocalDate.ofEpochDay(e.epochDay).toString(),
                        Money.formatPlain(e.amountMinor, e.currency).replace(",", ""),
                        e.currency,
                        row.category?.name.orEmpty(),
                        e.merchant.orEmpty(),
                        e.note.orEmpty(),
                        e.source,
                    ).joinToString(",") { csvCell(it) },
                )
            }
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    companion object {
        @Volatile
        private var instance: SpendRepository? = null

        fun get(context: Context): SpendRepository = instance ?: synchronized(this) {
            instance ?: SpendRepository(
                AppDatabase.get(context),
                Prefs.get(context),
                context.applicationContext as Application,
            ).also { instance = it }
        }

        fun epochDayOf(millis: Long): Long =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    }
}
