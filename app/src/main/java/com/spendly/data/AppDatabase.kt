package com.spendly.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SpendEntry::class,
        Category::class,
        PendingEntry::class,
        MerchantRule::class,
        WatchedApp::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun pendingDao(): PendingDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun watchedAppDao(): WatchedAppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spendly.db")
                .addCallback(SeedCallback)
                .build()

        /**
         * Seeds the starter categories the first time the database is created.
         * Done in raw SQL so it happens inside the same transaction as the schema
         * creation — the app can never come up with an empty category list.
         */
        private object SeedCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DefaultCategories.seed.forEachIndexed { index, c ->
                    db.execSQL(
                        "INSERT INTO categories (name, emoji, colorArgb, sortOrder, usageCount, isArchived) " +
                            "VALUES (?, ?, ?, ?, 0, 0)",
                        arrayOf<Any?>(c.name, c.emoji, c.colorArgb, index),
                    )
                }
            }
        }
    }
}

/** The out-of-the-box category set. Order here is the order of the Quick Add chips. */
object DefaultCategories {
    data class Seed(val name: String, val emoji: String, val colorArgb: Int)

    val seed = listOf(
        Seed("Food & Drink", "\uD83C\uDF5C", 0xFFEF5350.toInt()),
        Seed("Groceries", "\uD83D\uDED2", 0xFF26A69A.toInt()),
        Seed("Transport", "\uD83D\uDE97", 0xFF42A5F5.toInt()),
        Seed("Shopping", "\uD83D\uDECD", 0xFFAB47BC.toInt()),
        Seed("Bills & Home", "\uD83C\uDFE0", 0xFFFFA726.toInt()),
        Seed("Fun", "\uD83C\uDFAC", 0xFFEC407A.toInt()),
        Seed("Health", "\uD83D\uDC8A", 0xFF66BB6A.toInt()),
        Seed("Work", "\uD83D\uDCBC", 0xFF78909C.toInt()),
        Seed("Gifts", "\uD83C\uDF81", 0xFFFFCA28.toInt()),
        Seed("Other", "\u2795", 0xFF90A4AE.toInt()),
    )

    /** Name of the bucket the parser falls back to when it cannot guess. */
    const val FALLBACK = "Other"
}
