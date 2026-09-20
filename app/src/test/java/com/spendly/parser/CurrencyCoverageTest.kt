package com.spendly.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exhaustive check that a real notification in each supported currency is read
 * correctly — the right amount *and* the right currency code.
 *
 * Written as a sweep rather than one test per currency so that a regression
 * names every currency it broke, not just the first.
 */
class CurrencyCoverageTest {

    private fun parse(text: String, base: String = "MYR") =
        NotificationParser.parse(
            title = "Bank",
            text = text,
            packageName = "com.example.bank",
            appLabel = "Bank",
            baseCurrency = base,
        )

    /** notification text -> expected (minor units, ISO code) */
    private data class Case(val text: String, val minor: Long, val code: String, val base: String = "MYR")

    @Test
    fun `every supported symbol is parsed with the right currency`() {
        val cases = listOf(
            // --- symbol before the number ---
            Case("You paid RM25.50 at Mydin", 2550, "MYR"),
            Case("You paid S\$18.90 at FairPrice", 1890, "SGD"),
            Case("You paid US\$42.00 at Amazon", 4200, "USD"),
            Case("You paid A\$15.00 at Coles", 1500, "AUD"),
            Case("You paid C\$15.00 at Loblaws", 1500, "CAD"),
            Case("You paid NZ\$15.00 at PaknSave", 1500, "NZD"),
            Case("You paid HK\$88.00 at ParknShop", 8800, "HKD"),
            Case("You paid NT\$350.00 at FamilyMart", 35000, "TWD"),
            Case("You paid R\$75.00 at Extra", 7500, "BRL"),
            Case("You paid Rp150000 at Indomaret", 15000000, "IDR"),
            Case("You paid €24.99 at Lidl", 2499, "EUR"),
            Case("You paid £12.75 at Tesco", 1275, "GBP"),
            Case("You paid ₹1,250 at BigBazaar", 125000, "INR"),
            Case("You paid ₱500.00 at SM", 50000, "PHP"),
            Case("You paid ฿350.00 at 7-Eleven", 35000, "THB"),
            Case("You paid ₫250000 at VinMart", 25000000, "VND"),
            Case("You paid ₩15000 at GS25", 1500000, "KRW"),
            Case("You paid ₺150.00 at Migros", 15000, "TRY"),
            Case("You paid ₪45.00 at Shufersal", 4500, "ILS"),
            Case("You paid ₦5000 at Shoprite", 500000, "NGN"),
            Case("You paid ₽1500 at Pyaterochka", 150000, "RUB"),
            Case("You paid ₨2500 at Imtiaz", 250000, "PKR"),
            Case("You paid CHF 45.00 at Migros", 4500, "CHF"),
            Case("You paid AED 120.00 at Carrefour", 12000, "AED"),
            Case("You paid SAR 85.00 at Panda", 8500, "SAR"),

            // --- ISO code before the number ---
            Case("MYR 88.00 debited from your account", 8800, "MYR"),
            Case("SGD 45.60 debited from your account", 4560, "SGD"),
            Case("JPY 1200 debited from your account", 120000, "JPY"),
            Case("CNY 88.50 debited from your account", 8850, "CNY"),
            Case("ZAR 250.00 debited from your account", 25000, "ZAR"),
            Case("SEK 199.00 debited from your account", 19900, "SEK"),
            Case("PLN 89.00 debited from your account", 8900, "PLN"),

            // --- ISO code after the number ---
            Case("88.00 MYR debited from your account", 8800, "MYR"),
            Case("1,299.00 THB debited from your account", 129900, "THB"),
            Case("45.00 EUR debited from your account", 4500, "EUR"),

            // --- separator conventions ---
            Case("You paid €1.234,56 to Vermieter", 123456, "EUR"),
            Case("You paid £1,234.56 to Landlord", 123456, "GBP"),
            Case("You paid RM1,000 at Ikea", 100000, "MYR"),

            // --- no space between symbol and number ---
            Case("You paid RM25.50 at Kedai", 2550, "MYR"),
            Case("Purchase of ₹499 at Swiggy", 49900, "INR"),
        )

        val failures = mutableListOf<String>()
        cases.forEach { case ->
            val result = parse(case.text, case.base)
            when {
                result == null ->
                    failures += "NOT PARSED  ${case.code.padEnd(4)} | ${case.text}"
                result.amountMinor != case.minor ->
                    failures += "WRONG AMOUNT ${case.code.padEnd(4)} | expected ${case.minor}, got ${result.amountMinor} | ${case.text}"
                result.currency != case.code ->
                    failures += "WRONG CODE  ${case.code.padEnd(4)} | got ${result.currency} | ${case.text}"
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${cases.size} currency cases failed:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    // ---------- the ambiguous ones, which deserve their own tests ----------

    @Test
    fun `bare dollar follows the user's default currency`() {
        assertEquals("USD", parse("You paid \$12.34 at Walgreens", base = "USD")!!.currency)
        assertEquals("SGD", parse("You paid \$12.34 at NTUC", base = "SGD")!!.currency)
        assertEquals("AUD", parse("You paid \$12.34 at Coles", base = "AUD")!!.currency)
        assertEquals("HKD", parse("You paid \$12.34 at Wellcome", base = "HKD")!!.currency)
        // A base with no dollar of its own falls back to USD rather than guessing.
        assertEquals("USD", parse("You paid \$12.34 at Amazon", base = "MYR")!!.currency)
    }

    @Test
    fun `bare yen follows the user's default currency`() {
        assertEquals("JPY", parse("You paid ¥1200 at Lawson", base = "JPY")!!.currency)
        assertEquals("CNY", parse("You paid ¥88 at Meituan", base = "CNY")!!.currency)
        assertEquals("JPY", parse("You paid ¥1200 at Lawson", base = "MYR")!!.currency)
    }

    @Test
    fun `an ambiguous symbol scores lower than an explicit one`() {
        val explicit = parse("You paid RM25.50 at Mydin", base = "MYR")!!
        val ambiguous = parse("You paid \$25.50 at Mydin", base = "MYR")!!
        // Confidence should reflect that we had to guess which dollar it was.
        assert(explicit.confidence > ambiguous.confidence) {
            "explicit ${explicit.confidence} should beat ambiguous ${ambiguous.confidence}"
        }
    }

    // ---------- currencies genuinely NOT supported, so behaviour is defined ----------

    @Test
    fun `unsupported symbols are skipped rather than mis-assigned`() {
        // "kr" is Swedish, Norwegian and Danish at once. Rather than pick one,
        // the bare symbol is not recognised; the ISO code still works.
        assertNull(parse("You paid 199 kr at ICA", base = "SEK"))
        assertEquals("SEK", parse("You paid SEK 199 at ICA", base = "SEK")!!.currency)
    }

    @Test
    fun `mixed currencies in one notification take the one nearest the verb`() {
        val r = parse("You paid \$42.00 (RM198.00) at Amazon", base = "MYR")
        assertNotNull(r)
        // "$42.00" sits closest to "paid", so that is the transaction amount.
        assertEquals(4200L, r!!.amountMinor)
        assertEquals("USD", r.currency)
    }

    @Test
    fun `foreign card transactions keep the charged currency`() {
        val r = parse(
            "Your card was charged THB 1,299.00 at BANGKOK HOTEL",
            base = "MYR",
        )
        assertNotNull(r)
        assertEquals(129900L, r!!.amountMinor)
        assertEquals("THB", r.currency)
    }
}
