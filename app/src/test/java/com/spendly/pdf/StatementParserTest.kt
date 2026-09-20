package com.spendly.pdf

import com.spendly.parser.TransferDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Statement layouts vary per bank, so these fixtures are written in the shapes
 * real statements actually use. When an import misreads your bank, paste the
 * offending line in here first — it is a much faster loop than re-importing a
 * PDF on a phone.
 */
class StatementParserTest {

    private fun parse(text: String, base: String = "MYR", year: Int = 2026) =
        StatementParser.parse(text.trimIndent(), base, year)

    // ---------- the classic bank layout: date, description, amount, balance ----------

    @Test
    fun `reads a bank statement with a running balance column`() {
        val r = parse(
            """
            01/02/2026  STARBUCKS KLCC            25.50      1,234.00
            03/02/2026  PETRONAS BANGSAR          80.00      1,154.00
            05/02/2026  MYDIN SUBANG             120.75      1,033.25
            """,
        )
        assertEquals(3, r.spends.size)
        // The balance column must not be mistaken for the transaction.
        assertEquals(2550L, r.spends[0].amountMinor)
        assertEquals(8000L, r.spends[1].amountMinor)
        assertEquals(12075L, r.spends[2].amountMinor)
        assertEquals(LocalDate.of(2026, 2, 1), r.spends[0].date)
        assertTrue(r.spends[0].description.contains("STARBUCKS", ignoreCase = true))
    }

    @Test
    fun `a single amount on the line is the transaction`() {
        val r = parse("01/02/2026  STARBUCKS KLCC   25.50")
        assertEquals(1, r.spends.size)
        assertEquals(2550L, r.spends[0].amountMinor)
    }

    // ---------- reloads must never count ----------

    @Test
    fun `reload lines are excluded from spending`() {
        val r = parse(
            """
            01/02/2026  STARBUCKS KLCC            25.50      1,234.00
            02/02/2026  RELOAD TNG EWALLET        50.00      1,184.00
            03/02/2026  TOP UP GRABPAY           100.00      1,084.00
            """,
        )
        assertEquals(1, r.spends.size)
        assertEquals(2550L, r.spends[0].amountMinor)

        assertEquals(2, r.skipped.size)
        assertTrue(r.skipped.all { it.kind == StatementParser.Kind.TRANSFER })
        // Skipped rows explain themselves rather than vanishing.
        assertTrue(r.skipped.all { !it.skipReason.isNullOrBlank() })
    }

    @Test
    fun `every reload phrasing is caught`() {
        val phrasings = listOf(
            "RELOAD TNG EWALLET",
            "Reloaded Touch n Go",
            "TOP UP GRABPAY",
            "Top-up Boost Wallet",
            "TOPUP SHOPEEPAY",
            "Add Money to Wallet",
            "Cash In via Maybank",
            "Transfer to own account",
            "Internal transfer",
            "Balance transfer",
        )
        phrasings.forEach { p ->
            assertTrue("should be a transfer: $p", TransferDetector.isTransfer(p))
        }
    }

    @Test
    fun `paying a person is still spending, not a transfer`() {
        // Only self-transfers are excluded. Paying someone by transfer counts.
        assertFalse(TransferDetector.isTransfer("DUITNOW TRANSFER TO ALI BIN ABU"))
        assertFalse(TransferDetector.isTransfer("Payment to landlord"))
        assertFalse(TransferDetector.isTransfer("Transfer to Ahmad"))
    }

    @Test
    fun `reload words inside other words do not fire`() {
        assertFalse(TransferDetector.isTransfer("PRELOADED CARD PURCHASE"))
        assertFalse(TransferDetector.isTransfer("Cashing a cheque"))
    }

    // ---------- money in ----------

    @Test
    fun `credits are excluded from spending`() {
        val r = parse(
            """
            01/02/2026  STARBUCKS KLCC            25.50      1,234.00
            25/02/2026  SALARY CREDIT          3,000.00      4,234.00
            26/02/2026  REFUND SHOPEE             45.00      4,279.00
            """,
        )
        assertEquals(1, r.spends.size)
        val credits = r.skipped.filter { it.kind == StatementParser.Kind.CREDIT }
        assertEquals(2, credits.size)
    }

    @Test
    fun `explicit CR and DR markers win over keywords`() {
        val r = parse(
            """
            01/02/2026  SOMETHING AMBIGUOUS     100.00 CR   1,000.00
            02/02/2026  SOMETHING AMBIGUOUS      40.00 DR     960.00
            """,
        )
        assertEquals(1, r.spends.size)
        assertEquals(4000L, r.spends[0].amountMinor)
    }

    // ---------- e-wallet layout with signed amounts ----------

    @Test
    fun `reads a signed e-wallet statement`() {
        val r = parse(
            """
            01 Feb 2026   Payment to Starbucks      -25.50
            02 Feb 2026   Reload from Maybank       +50.00
            03 Feb 2026   Payment to Grab           -18.00
            """,
        )
        assertEquals(2, r.spends.size)
        assertEquals(2550L, r.spends[0].amountMinor)
        assertEquals(1800L, r.spends[1].amountMinor)
        assertEquals(1, r.skipped.size)
        assertEquals(StatementParser.Kind.TRANSFER, r.skipped[0].kind)
    }

    // ---------- date handling ----------

    @Test
    fun `reads the common date formats`() {
        assertEquals(
            LocalDate.of(2026, 2, 1),
            parse("01/02/2026  SHOP  10.00").spends[0].date,
        )
        assertEquals(
            LocalDate.of(2026, 2, 1),
            parse("2026-02-01  SHOP  10.00").spends[0].date,
        )
        assertEquals(
            LocalDate.of(2026, 2, 1),
            parse("01-02-2026  SHOP  10.00").spends[0].date,
        )
        assertEquals(
            LocalDate.of(2026, 2, 1),
            parse("01 Feb 2026  SHOP  10.00").spends[0].date,
        )
    }

    @Test
    fun `year-less dates use the statement year`() {
        val r = parse("01 FEB  SHOP  10.00", year = 2025)
        assertEquals(LocalDate.of(2025, 2, 1), r.spends[0].date)
    }

    @Test
    fun `detects the statement year from the document`() {
        assertEquals(2025, StatementParser.detectYear("Statement period 01/01/2025 to 31/01/2025"))
    }

    // ---------- noise ----------

    @Test
    fun `header footer and balance lines are ignored`() {
        val r = parse(
            """
            MAYBANK BERHAD STATEMENT OF ACCOUNT
            Account Number 1234567890
            Statement Date 28/02/2026
            OPENING BALANCE                        1,259.50
            01/02/2026  STARBUCKS KLCC    25.50    1,234.00
            CLOSING BALANCE                        1,234.00
            Page 1 of 3
            """,
        )
        assertEquals(1, r.spends.size)
        assertTrue(r.spends[0].description.contains("STARBUCKS", ignoreCase = true))
    }

    @Test
    fun `bare integers are not treated as amounts`() {
        // Card fragments and reference numbers must not become transactions.
        val r = parse("01/02/2026  PURCHASE CARD ENDING 1234  25.50  1,234.00")
        assertEquals(1, r.spends.size)
        assertEquals(2550L, r.spends[0].amountMinor)
    }

    @Test
    fun `lines without a date are skipped`() {
        val r = parse("STARBUCKS KLCC  25.50")
        assertEquals(0, r.rows.size)
    }

    // ---------- currency ----------

    @Test
    fun `picks up the statement currency from the document`() {
        val r = parse(
            """
            Statement in RM
            01/02/2026  STARBUCKS  25.50  1,234.00
            """,
            base = "SGD",
        )
        assertEquals("MYR", r.currency)
    }

    @Test
    fun `falls back to the default currency when the document says nothing`() {
        val r = parse("01/02/2026  STARBUCKS  25.50  1,234.00", base = "SGD")
        assertEquals("SGD", r.currency)
    }

    // ---------- description ----------

    @Test
    fun `description strips the date and the amounts`() {
        val r = parse("01/02/2026  STARBUCKS KLCC  25.50  1,234.00")
        val d = r.spends[0].description
        assertNotNull(d)
        assertFalse("date leaked into description: $d", d.contains("01/02/2026"))
        assertFalse("amount leaked into description: $d", d.contains("25.50"))
        assertFalse("balance leaked into description: $d", d.contains("1,234.00"))
        assertEquals("STARBUCKS KLCC", d)
    }

    @Test
    fun `an empty statement parses to nothing rather than throwing`() {
        val r = parse("")
        assertEquals(0, r.rows.size)
    }
}
