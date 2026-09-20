package com.spendly.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** An entry joined with its category, which is what every list in the UI needs. */
data class EntryWithCategory(
    @Embedded val entry: SpendEntry,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category?,
)

@Dao
interface EntryDao {

    @Transaction
    @Query("SELECT * FROM entries WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay DESC, createdAt DESC")
    fun entriesBetween(from: Long, to: Long): Flow<List<EntryWithCategory>>

    @Transaction
    @Query("SELECT * FROM entries WHERE epochDay = :epochDay ORDER BY createdAt DESC")
    fun entriesForDay(epochDay: Long): Flow<List<EntryWithCategory>>

    @Transaction
    @Query("SELECT * FROM entries ORDER BY epochDay DESC, createdAt DESC LIMIT :limit")
    fun recentEntries(limit: Int): Flow<List<EntryWithCategory>>

    @Transaction
    @Query("SELECT * FROM entries ORDER BY epochDay ASC, createdAt ASC")
    suspend fun allEntriesOnce(): List<EntryWithCategory>

    @Transaction
    @Query(
        "SELECT * FROM entries WHERE epochDay BETWEEN :from AND :to " +
            "ORDER BY epochDay ASC, createdAt ASC",
    )
    suspend fun entriesBetweenOnce(from: Long, to: Long): List<EntryWithCategory>

    @Query("SELECT MIN(epochDay) FROM entries")
    suspend fun earliestDay(): Long?

    @Insert
    suspend fun insert(entry: SpendEntry): Long

    @Update
    suspend fun update(entry: SpendEntry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): SpendEntry?

    /** Dedupe guard: has this exact notification already become an entry recently? */
    @Query("SELECT COUNT(*) FROM entries WHERE dedupeKey = :key AND createdAt > :since")
    suspend fun countByDedupeKeySince(key: String, since: Long): Int
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY usageCount DESC, sortOrder ASC, name ASC")
    fun activeByUsage(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun all(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY sortOrder ASC, name ASC")
    suspend fun activeOnce(): List<Category>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): Category?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Query("UPDATE categories SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun bumpUsage(id: Long)

    @Query("UPDATE categories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)
}

@Dao
interface PendingDao {

    @Query("SELECT * FROM pending ORDER BY postedAt DESC")
    fun all(): Flow<List<PendingEntry>>

    @Query("SELECT COUNT(*) FROM pending")
    fun count(): Flow<Int>

    @Query("SELECT * FROM pending WHERE id = :id")
    suspend fun byId(id: Long): PendingEntry?

    @Query("SELECT * FROM pending ORDER BY postedAt ASC LIMIT 1")
    suspend fun oldest(): PendingEntry?

    /** IGNORE, not REPLACE: the unique dedupeKey is what stops repeat notifications piling up. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pending: PendingEntry): Long

    @Query("SELECT COUNT(*) FROM pending WHERE dedupeKey = :key AND postedAt > :since")
    suspend fun countByDedupeKeySince(key: String, since: Long): Int

    @Query("DELETE FROM pending WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending")
    suspend fun deleteAll()

    @Query("DELETE FROM pending WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface MerchantRuleDao {

    @Query("SELECT * FROM merchant_rules WHERE merchantKey = :key")
    suspend fun byKey(key: String): MerchantRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRule)

    @Query("SELECT * FROM merchant_rules ORDER BY updatedAt DESC")
    fun all(): Flow<List<MerchantRule>>

    @Query("DELETE FROM merchant_rules WHERE merchantKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface WatchedAppDao {

    @Query("SELECT * FROM watched_apps ORDER BY detectedCount DESC, label ASC")
    fun all(): Flow<List<WatchedApp>>

    @Query("SELECT enabled FROM watched_apps WHERE packageName = :pkg")
    suspend fun isEnabled(pkg: String): Boolean?

    /**
     * Record that we saw this app, without clobbering the user's on/off choice.
     * Room runs raw INSERT statements fine, which is the tidiest way to express
     * "insert or partially update".
     */
    @Query(
        """
        INSERT INTO watched_apps (packageName, label, enabled, lastSeenAt, detectedCount)
        VALUES (:pkg, :label, :defaultEnabled, :now, 0)
        ON CONFLICT(packageName) DO UPDATE SET label = :label, lastSeenAt = :now
        """
    )
    suspend fun markSeen(pkg: String, label: String, now: Long, defaultEnabled: Boolean)

    @Query("UPDATE watched_apps SET detectedCount = detectedCount + 1 WHERE packageName = :pkg")
    suspend fun bumpDetected(pkg: String)

    @Query("UPDATE watched_apps SET enabled = :enabled WHERE packageName = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)
}
