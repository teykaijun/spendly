package com.spendly.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a [SpendEntry] came from. */
object Source {
    const val MANUAL = "MANUAL"
    const val NOTIFICATION = "NOTIFICATION"
    const val STATEMENT = "STATEMENT"
}

/**
 * A recorded spend.
 *
 * Amounts are stored in minor units (cents) as a [Long] so arithmetic never drifts
 * the way it does with floating point. Currencies with a different exponent (JPY,
 * KRW, VND) are still stored x100 — display handles the formatting.
 */
@Entity(
    tableName = "entries",
    indices = [
        Index("epochDay"),
        Index("categoryId"),
        Index("dedupeKey"),
    ],
)
data class SpendEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val merchant: String? = null,
    val note: String? = null,
    /** Days since epoch, in the device's local time zone. Cheap to index and group by. */
    val epochDay: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = Source.MANUAL,
    val sourcePackage: String? = null,
    val dedupeKey: String? = null,
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val colorArgb: Int,
    val sortOrder: Int = 0,
    /** Bumped on every use so the Quick Add chips can self-sort to your habits. */
    val usageCount: Int = 0,
    val isArchived: Boolean = false,
)

/**
 * A spend parsed out of a notification, waiting for one-tap confirmation.
 * Confirming turns it into a [SpendEntry]; dismissing just deletes it.
 */
@Entity(
    tableName = "pending",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class PendingEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String,
    val merchant: String? = null,
    val guessedCategoryId: Long? = null,
    val rawTitle: String = "",
    val rawText: String = "",
    val sourcePackage: String,
    val sourceAppLabel: String,
    val postedAt: Long,
    val epochDay: Long,
    /** 0-100. Used to decide auto-save vs. queue, and shown in the inbox. */
    val confidence: Int,
    val dedupeKey: String,
)

/**
 * Remembers "Starbucks -> Food & Drink" the first time you correct a guess,
 * so the same merchant is categorised correctly from then on.
 */
@Entity(tableName = "merchant_rules")
data class MerchantRule(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Every app we have seen post a notification, and whether it is allowed to
 * create spend entries. Rows are created lazily as notifications arrive, so the
 * settings screen only ever shows apps that are actually relevant to you.
 */
@Entity(tableName = "watched_apps")
data class WatchedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    val lastSeenAt: Long = System.currentTimeMillis(),
    /** How many spends we have successfully parsed from this app. */
    val detectedCount: Int = 0,
)

/** A per-day rollup used by the calendar heatmap. */
data class DayTotal(
    val epochDay: Long,
    val totalMinor: Long,
    val entryCount: Int,
)

/** A per-category rollup used by the month summary. */
data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    val emoji: String,
    val colorArgb: Int,
    val totalMinor: Long,
    val entryCount: Int,
)
