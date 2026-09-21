package com.spendly.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * Every way an entry reaches the database, exercised against a real SQLite
 * database rather than mocks.
 *
 * Each test ends by reading back through the same query the calendar uses,
 * because "the insert did not throw" is not the claim that matters — the
 * claim is "it shows up where the user looks".
 */
@RunWith(RobolectricTestRunner::class)
class SavePathsTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var prefs: Prefs
    private lateinit var repo: SpendRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .addCallback(AppDatabase.Companion.SeedCallback)
            .allowMainThreadQueries()
            .build()
        // A fresh preferences file per test, so one test's settings never leak
        // into the next.
        prefs = Prefs(app.getSharedPreferences("test-${System.nanoTime()}", Context.MODE_PRIVATE))
        prefs.setBaseCurrency("MYR")
        repo = SpendRepository(db, prefs, app)
    }

    @After
    fun tearDown() = db.close()

    // ---------- helpers ----------

    private val today: LocalDate get() = LocalDate.now()

    /** What the calendar shows for the current month. */
    private suspend fun calendarThisMonth(): List<EntryWithCategory> {
        val month = YearMonth.now()
        return db.entryDao().entriesBetween(
            month.atDay(1).toEpochDay(),
            month.atEndOfMonth().toEpochDay(),
        ).first()
    }

    private suspend fun pendingCount(): Int = db.pendingDao().count().first()

    private suspend fun categoryId(name: String): Long =
        db.categoryDao().activeOnce().first { it.name == name }.id

    private suspend fun ingestStarbucks(postedAt: Long = System.currentTimeMillis()) =
        repo.ingestNotification(
            packageName = "com.maybank2u.life",
            appLabel = "Maybank",
            title = "Maybank2u",
            text = "You have paid RM25.50 to STARBUCKS KLCC",
            postedAt = postedAt,
        )

    // ---------- manual ----------

    @Test
    fun `a manual entry is saved and appears on the calendar`() = runTest {
        val food = categoryId("Food & Drink")
        val id = repo.addManualEntry(
            amountMinor = 1250,
            currency = "MYR",
            categoryId = food,
            merchant = "Kopitiam",
            note = "lunch",
            date = today,
        )

        val shown = calendarThisMonth()
        assertEquals(1, shown.size)
        val e = shown.single().entry
        assertEquals(id, e.id)
        assertEquals(1250L, e.amountMinor)
        assertEquals("MYR", e.currency)
        assertEquals(food, e.categoryId)
        assertEquals("Kopitiam", e.merchant)
        assertEquals("lunch", e.note)
        assertEquals(today.toEpochDay(), e.epochDay)
        assertEquals(Source.MANUAL, e.source)
        assertEquals("Food & Drink", shown.single().category?.name)
    }

    // ---------- notification, confirm-first (the default) ----------

    @Test
    fun `with auto-save off a detection is queued, not yet an entry`() = runTest {
        prefs.setAutoSave(false)

        val result = ingestStarbucks()

        assertTrue("expected Queued, got $result", result is SpendRepository.Ingest.Queued)
        assertEquals(1, pendingCount())
        assertEquals(0, calendarThisMonth().size)
    }

    @Test
    fun `confirming a queued detection saves it as an entry`() = runTest {
        prefs.setAutoSave(false)
        val queued = ingestStarbucks() as SpendRepository.Ingest.Queued

        val entryId = repo.confirmPending(queued.pendingId)

        assertNotNull("confirmPending returned null — nothing was saved", entryId)
        assertEquals(0, pendingCount())

        val shown = calendarThisMonth()
        assertEquals(1, shown.size)
        val e = shown.single().entry
        assertEquals(2550L, e.amountMinor)
        assertEquals("MYR", e.currency)
        assertEquals(Source.NOTIFICATION, e.source)
        assertEquals("com.maybank2u.life", e.sourcePackage)
        assertEquals(today.toEpochDay(), e.epochDay)
        assertEquals("Food & Drink", shown.single().category?.name)
    }

    @Test
    fun `confirming with a different category remembers it for that merchant`() = runTest {
        prefs.setAutoSave(false)
        val work = categoryId("Work")

        val first = ingestStarbucks() as SpendRepository.Ingest.Queued
        repo.confirmPending(first.pendingId, categoryId = work)

        // The same merchant again, a day later so it is not a duplicate.
        val later = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val second = ingestStarbucks(postedAt = later) as SpendRepository.Ingest.Queued
        assertEquals(work, second.preview.guessedCategoryId)
    }

    @Test
    fun `confirm all saves every queued detection`() = runTest {
        prefs.setAutoSave(false)
        repo.ingestNotification("com.maybank2u.life", "Maybank", "Maybank2u",
            "You have paid RM10.00 to MYDIN", System.currentTimeMillis())
        repo.ingestNotification("com.maybank2u.life", "Maybank", "Maybank2u",
            "You have paid RM20.00 to TESCO", System.currentTimeMillis())
        assertEquals(2, pendingCount())

        repo.confirmAllPending()

        assertEquals(0, pendingCount())
        assertEquals(2, calendarThisMonth().size)
    }

    @Test
    fun `dismissing a detection saves nothing`() = runTest {
        prefs.setAutoSave(false)
        val queued = ingestStarbucks() as SpendRepository.Ingest.Queued

        repo.dismissPending(queued.pendingId)

        assertEquals(0, pendingCount())
        assertEquals(0, calendarThisMonth().size)
    }

    // ---------- notification, auto-save ----------

    @Test
    fun `with auto-save on a detection becomes an entry immediately`() = runTest {
        prefs.setAutoSave(true)

        val result = ingestStarbucks()

        assertTrue("expected Saved, got $result", result is SpendRepository.Ingest.Saved)
        assertEquals(0, pendingCount())
        assertEquals(1, calendarThisMonth().size)
        assertEquals(Source.NOTIFICATION, calendarThisMonth().single().entry.source)
    }

    // ---------- notification, things that must not save ----------

    @Test
    fun `the same notification twice is only kept once`() = runTest {
        prefs.setAutoSave(false)
        ingestStarbucks()
        val again = ingestStarbucks()

        assertTrue(again is SpendRepository.Ingest.Ignored)
        assertEquals(1, pendingCount())
    }

    @Test
    fun `a reload is never saved`() = runTest {
        prefs.setAutoSave(true)
        val result = repo.ingestNotification(
            "com.tngdigital.ewallet", "TNG eWallet", "TNG eWallet",
            "Reload of RM50.00 successful", System.currentTimeMillis(),
        )
        assertTrue(result is SpendRepository.Ingest.Ignored)
        assertEquals(0, calendarThisMonth().size)
    }

    @Test
    fun `a muted app is not saved`() = runTest {
        prefs.setAutoSave(true)
        ingestStarbucks() // registers the app
        db.entryDao().allEntriesOnce().forEach { db.entryDao().deleteById(it.entry.id) }
        db.watchedAppDao().setEnabled("com.maybank2u.life", false)

        val later = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val result = ingestStarbucks(postedAt = later)

        assertTrue(result is SpendRepository.Ingest.Ignored)
        assertEquals(0, db.entryDao().allEntriesOnce().size)
    }

    @Test
    fun `paused capture saves nothing`() = runTest {
        prefs.setAutoSave(true)
        prefs.setCapturePaused(true)
        assertTrue(ingestStarbucks() is SpendRepository.Ingest.Ignored)
        assertEquals(0, calendarThisMonth().size)
    }

    // ---------- statement import ----------

    @Test
    fun `an imported statement row is saved and appears on the calendar`() = runTest {
        val shopping = categoryId("Shopping")
        repo.addImportedEntry(
            amountMinor = 8990,
            currency = "MYR",
            categoryId = shopping,
            merchant = "UNIQLO KLCC",
            date = today,
            dedupeKey = "statement|test",
        )

        val shown = calendarThisMonth()
        assertEquals(1, shown.size)
        val e = shown.single().entry
        assertEquals(8990L, e.amountMinor)
        assertEquals(Source.STATEMENT, e.source)
        assertEquals("statement|test", e.dedupeKey)
    }

    // ---------- editing ----------

    @Test
    fun `an edited entry keeps its id and shows the new values`() = runTest {
        val food = categoryId("Food & Drink")
        val shopping = categoryId("Shopping")
        val id = repo.addManualEntry(1000, "MYR", food, merchant = "Cafe", date = today)

        val original = repo.entryById(id)!!
        repo.updateEntry(
            original.copy(
                amountMinor = 4200,
                currency = "SGD",
                categoryId = shopping,
                merchant = "Bookshop",
                note = "gift",
                epochDay = today.minusDays(1).toEpochDay(),
            ),
        )

        val edited = repo.entryById(id)!!
        assertEquals(id, edited.id)
        assertEquals(4200L, edited.amountMinor)
        assertEquals("SGD", edited.currency)
        assertEquals(shopping, edited.categoryId)
        assertEquals("Bookshop", edited.merchant)
        assertEquals("gift", edited.note)
        assertEquals(today.minusDays(1).toEpochDay(), edited.epochDay)
        // An edit must not duplicate the row.
        assertEquals(1, db.entryDao().allEntriesOnce().size)
    }

    @Test
    fun `editEntry changes every field and keeps the row's identity`() = runTest {
        val food = categoryId("Food & Drink")
        val shopping = categoryId("Shopping")
        val id = repo.addManualEntry(1000, "MYR", food, merchant = "Cafe", note = "x", date = today)
        val original = repo.entryById(id)!!

        repo.editEntry(
            original = original,
            amountMinor = 4200,
            currency = "SGD",
            categoryId = shopping,
            merchant = "  Bookshop  ",
            note = "",
            date = today.minusDays(2),
        )

        val e = repo.entryById(id)!!
        assertEquals(4200L, e.amountMinor)
        assertEquals("SGD", e.currency)
        assertEquals(shopping, e.categoryId)
        assertEquals("Bookshop", e.merchant) // trimmed
        assertNull(e.note) // blank becomes null, not ""
        assertEquals(today.minusDays(2).toEpochDay(), e.epochDay)
        // Identity and provenance are not the user's to change by editing.
        assertEquals(original.source, e.source)
        assertEquals(original.createdAt, e.createdAt)
        assertEquals(1, db.entryDao().allEntriesOnce().size)
    }

    @Test
    fun `recategorising a notification entry teaches that merchant`() = runTest {
        prefs.setAutoSave(true)
        val work = categoryId("Work")
        val saved = ingestStarbucks() as SpendRepository.Ingest.Saved
        val original = repo.entryById(saved.entryId)!!

        repo.editEntry(original, original.amountMinor, original.currency, work,
            original.merchant, original.note, today)

        // The next Starbucks notification should now arrive as Work.
        prefs.setAutoSave(false)
        val later = System.currentTimeMillis() + 24L * 60 * 60 * 1000
        val next = ingestStarbucks(postedAt = later) as SpendRepository.Ingest.Queued
        assertEquals(work, next.preview.guessedCategoryId)
    }

    @Test
    fun `recategorising a manual entry teaches nothing`() = runTest {
        // Manual entries were never a guess, so changing one is not a correction.
        val id = repo.addManualEntry(1000, "MYR", categoryId("Food & Drink"),
            merchant = "STARBUCKS KLCC", date = today)
        repo.editEntry(repo.entryById(id)!!, 1000, "MYR", categoryId("Work"),
            "STARBUCKS KLCC", null, today)

        prefs.setAutoSave(false)
        val next = ingestStarbucks() as SpendRepository.Ingest.Queued
        assertEquals(categoryId("Food & Drink"), next.preview.guessedCategoryId)
    }

    @Test
    fun `editing an imported amount keeps it recognisable as already imported`() = runTest {
        val id = repo.addImportedEntry(8990, "MYR", categoryId("Shopping"), "UNIQLO",
            today, dedupeKey = "statement|original-line")
        repo.editEntry(repo.entryById(id)!!, 9990, "MYR", categoryId("Shopping"),
            "UNIQLO", null, today)

        // Re-importing the same statement must still see this row as done.
        assertEquals("statement|original-line", repo.entryById(id)!!.dedupeKey)
        assertEquals(1, db.entryDao().countByDedupeKeySince("statement|original-line", 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an edit to a zero amount is refused`() = runTest {
        val id = repo.addManualEntry(1000, "MYR", categoryId("Other"), date = today)
        repo.editEntry(repo.entryById(id)!!, 0, "MYR", categoryId("Other"), null, null, today)
    }

    @Test
    fun `deleting an entry removes it from the calendar`() = runTest {
        val id = repo.addManualEntry(500, "MYR", categoryId("Other"), date = today)
        repo.deleteEntry(id)
        assertNull(repo.entryById(id))
        assertEquals(0, calendarThisMonth().size)
    }
}
