package com.spendly.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Everything money-shaped: symbols, formatting, and turning messy human/bank
 * number strings ("1,234.50", "1.234,50", "1 234,5") into minor units.
 */
object Money {

    /** Currencies that actually have no minor unit — displayed without decimals. */
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "IDR", "CLP", "ISK")

    /** Display symbol per ISO code. Falls back to the code itself. */
    private val SYMBOLS = mapOf(
        "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "¥",
        "MYR" to "RM", "SGD" to "S$", "AUD" to "A$", "CAD" to "C$", "NZD" to "NZ$",
        "HKD" to "HK$", "TWD" to "NT$", "BRL" to "R$", "IDR" to "Rp", "PHP" to "₱",
        "INR" to "₹", "THB" to "฿", "VND" to "₫", "KRW" to "₩", "TRY" to "₺",
        "ILS" to "₪", "NGN" to "₦", "RUB" to "₽", "PLN" to "zł", "ZAR" to "R",
        "CHF" to "CHF", "SEK" to "kr", "NOK" to "kr", "DKK" to "kr",
        "AED" to "AED", "SAR" to "SAR", "PKR" to "₨", "BDT" to "৳", "LKR" to "Rs",
    )

    /** A short, sensible list for the currency picker. */
    val COMMON_CURRENCIES = listOf(
        "MYR", "SGD", "USD", "EUR", "GBP", "AUD", "CAD", "NZD", "HKD", "TWD",
        "JPY", "CNY", "KRW", "INR", "IDR", "THB", "PHP", "VND", "CHF", "SEK",
        "NOK", "DKK", "PLN", "BRL", "ZAR", "TRY", "AED", "SAR", "NGN", "RUB",
    )

    fun symbol(currency: String): String = SYMBOLS[currency.uppercase()] ?: currency.uppercase()

    fun decimals(currency: String): Int = if (currency.uppercase() in ZERO_DECIMAL) 0 else 2

    /** "RM 12.50" — the everyday display form. */
    fun format(amountMinor: Long, currency: String, withSymbol: Boolean = true): String {
        val sym = symbol(currency)
        val body = formatPlain(amountMinor, currency)
        if (!withSymbol) return body
        return if (needsSpace(sym)) "$sym $body" else "$sym$body"
    }

    /** The number alone, grouped, with the right number of decimals. */
    fun formatPlain(amountMinor: Long, currency: String): String {
        val dp = decimals(currency)
        val value = BigDecimal(amountMinor).movePointLeft(2).setScale(dp, RoundingMode.HALF_UP)
        return String.format(Locale.US, "%,.${dp}f", value)
    }

    /**
     * Compact form for tight spaces: 1.2k, 45.3k, 1.1M.
     * Rounds hard on purpose — this is for heatmap cells, not for the ledger.
     */
    fun formatCompact(amountMinor: Long, currency: String): String {
        val units = amountMinor / 100.0
        val sym = symbol(currency)
        val body = when {
            units >= 1_000_000 -> String.format(Locale.US, "%.1fM", units / 1_000_000)
            units >= 10_000 -> String.format(Locale.US, "%.0fk", units / 1_000)
            units >= 1_000 -> String.format(Locale.US, "%.1fk", units / 1_000)
            units >= 100 -> String.format(Locale.US, "%.0f", units)
            // Drop cents only when they are actually zero. Trimming trailing
            // zeros off the formatted string would turn "20.00" into "2".
            units == Math.floor(units) -> String.format(Locale.US, "%.0f", units)
            else -> String.format(Locale.US, "%.2f", units).trimEnd('0')
        }
        return if (needsSpace(sym)) "$sym $body" else "$sym$body"
    }

    /**
     * Short symbols sit flush against the number the way people write them
     * ("RM25.50", "S$18.90", "$12.34"). Three-letter codes — either a real
     * word-like symbol such as CHF, or the fallback for a currency we have no
     * symbol for — need the space to stay readable.
     */
    private fun needsSpace(symbol: String): Boolean = symbol.length > 2

    /**
     * Parse a number the way it appears in the wild, where the thousands and
     * decimal separators differ by locale and by bank.
     *
     * Rules, applied in order:
     *  - Both `.` and `,` present -> whichever comes *last* is the decimal point.
     *  - Only one separator present, with 1-2 digits after it -> decimal point.
     *  - Only one separator present, with exactly 3 digits after it -> thousands.
     *  - Anything else -> strip it as a grouping character.
     *
     * Returns minor units (always x100), or null if there is no usable number.
     */
    fun parseToMinor(raw: String): Long? {
        val cleaned = raw.trim().replace("\u00A0", " ").replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (!cleaned.any { it.isDigit() }) return null

        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')

        val normalized: String = when {
            lastDot >= 0 && lastComma >= 0 -> {
                if (lastDot > lastComma) {
                    cleaned.replace(",", "")
                } else {
                    cleaned.replace(".", "").replace(',', '.')
                }
            }
            lastDot >= 0 -> normalizeSingleSeparator(cleaned, '.', lastDot)
            lastComma >= 0 -> normalizeSingleSeparator(cleaned, ',', lastComma)
            else -> cleaned
        }

        val digitsOnly = normalized.filter { it.isDigit() || it == '.' }
        if (digitsOnly.isEmpty() || digitsOnly == ".") return null

        return try {
            BigDecimal(digitsOnly).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun normalizeSingleSeparator(s: String, sep: Char, lastIndex: Int): String {
        val after = s.length - lastIndex - 1
        val occurrences = s.count { it == sep }
        return when {
            // "1.234.567" -> grouping, every group is 3 digits
            occurrences > 1 -> s.replace(sep.toString(), "")
            after == 3 -> s.replace(sep.toString(), "")
            after in 1..2 -> s.replace(sep, '.')
            else -> s.replace(sep.toString(), "")
        }
    }
}
