package com.spendly.parser

import com.spendly.data.Money

/**
 * Finds currency amounts inside free text.
 *
 * Deliberately has no Android dependencies so it can be unit tested on the JVM —
 * this is the part of the app most likely to need tuning against real-world
 * notification text, so it needs to be cheap to test.
 */
object AmountDetector {

    /** One currency amount found in the text, with where it was found. */
    data class Hit(
        val amountMinor: Long,
        val currency: String,
        /** Index in the source string where the whole match started. */
        val start: Int,
        val end: Int,
        /** True when the currency was written ambiguously (a bare "$" or "¥"). */
        val currencyWasAmbiguous: Boolean,
    )

    /**
     * A number as banks and wallets actually write it. Two shapes:
     *  - grouped:  1,234.50   1.234,50   1 234,50
     *  - plain:    25.50      25,50      25
     */
    private const val NUM =
        "(?:\\d{1,3}(?:[  ,.]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"

    /**
     * Symbol/prefix tokens mapped to ISO codes.
     *
     * Order matters. The regex engine scans left to right, so a token that is a
     * prefix of another must not shadow it: "US$" has to be present or "US$25"
     * would match the "S$" alternative and be read as Singapore dollars.
     */
    private val SYMBOL_TOKENS: List<Pair<String, String>> = listOf(
        "US$" to "USD", "AU$" to "AUD", "CA$" to "CAD", "NZ$" to "NZD",
        "HK$" to "HKD", "NT$" to "TWD", "SG$" to "SGD",
        "S$" to "SGD", "A$" to "AUD", "C$" to "CAD", "R$" to "BRL",
        "RM" to "MYR", "Rp" to "IDR", "Rs." to "INR", "Rs" to "INR",
        "zł" to "PLN", "CHF" to "CHF", "AED" to "AED", "SAR" to "SAR",
        "₨" to "PKR", "৳" to "BDT", "₹" to "INR", "₱" to "PHP",
        "฿" to "THB", "₫" to "VND", "₩" to "KRW", "₺" to "TRY",
        "₪" to "ILS", "₦" to "NGN", "₽" to "RUB",
        "€" to "EUR", "£" to "GBP",
        // Ambiguous on purpose — resolved against the user's base currency below.
        "¥" to AMBIGUOUS_YEN, "$" to AMBIGUOUS_DOLLAR,
    )

    private val ISO_CODES: Set<String> = setOf(
        "USD", "EUR", "GBP", "JPY", "CNY", "MYR", "SGD", "AUD", "CAD", "NZD",
        "HKD", "TWD", "BRL", "IDR", "PHP", "INR", "THB", "VND", "KRW", "TRY",
        "ILS", "NGN", "RUB", "PLN", "ZAR", "CHF", "SEK", "NOK", "DKK", "AED",
        "SAR", "PKR", "BDT", "LKR", "MMK", "KHR", "LAK", "BND", "MXN", "ARS",
    )

    /** Base currencies that make a bare "$" unambiguous. */
    private val DOLLAR_BASES = setOf("USD", "SGD", "AUD", "CAD", "NZD", "HKD", "TWD", "BRL")
    private val YEN_BASES = setOf("JPY", "CNY")

    private val symbolRegex: Regex by lazy {
        val alt = SYMBOL_TOKENS.joinToString("|") { Regex.escape(it.first) }
        Regex("($alt)\\s{0,3}($NUM)", RegexOption.IGNORE_CASE)
    }

    private val codeSuffixRegex: Regex by lazy {
        val alt = ISO_CODES.joinToString("|")
        Regex("($NUM)\\s{0,3}($alt)", RegexOption.IGNORE_CASE)
    }

    private val codePrefixRegex: Regex by lazy {
        val alt = ISO_CODES.joinToString("|")
        Regex("($alt)\\s{0,3}($NUM)", RegexOption.IGNORE_CASE)
    }

    /**
     * All currency amounts in [text], in order of appearance, de-duplicated by
     * position so overlapping patterns ("MYR 25.00" matches both a code-prefix
     * and, on a different engine pass, nothing else) only yield one hit.
     */
    fun findAll(text: String, baseCurrency: String): List<Hit> {
        if (text.isBlank()) return emptyList()
        val hits = mutableListOf<Hit>()

        fun add(match: MatchResult, rawCurrency: String, rawNumber: String) {
            val minor = Money.parseToMinor(rawNumber) ?: return
            if (minor <= 0) return
            val ambiguous = rawCurrency == AMBIGUOUS_DOLLAR || rawCurrency == AMBIGUOUS_YEN
            hits += Hit(
                amountMinor = minor,
                currency = resolveCurrency(rawCurrency, baseCurrency),
                start = match.range.first,
                end = match.range.last + 1,
                currencyWasAmbiguous = ambiguous,
            )
        }

        for (m in codePrefixRegex.findAll(text)) {
            if (!boundariesOk(text, m.range.first, m.range.last + 1)) continue
            add(m, m.groupValues[1].uppercase(), m.groupValues[2])
        }
        for (m in codeSuffixRegex.findAll(text)) {
            if (!boundariesOk(text, m.range.first, m.range.last + 1)) continue
            add(m, m.groupValues[2].uppercase(), m.groupValues[1])
        }
        for (m in symbolRegex.findAll(text)) {
            if (!boundariesOk(text, m.range.first, m.range.last + 1)) continue
            val token = m.groupValues[1]
            val iso = SYMBOL_TOKENS.firstOrNull { it.first.equals(token, ignoreCase = true) }?.second
                ?: continue
            add(m, iso, m.groupValues[2])
        }

        // Prefer the longest match starting at any given offset, then sort by position.
        return hits
            .groupBy { it.start }
            .map { (_, group) -> group.maxBy { it.end - it.start } }
            .sortedBy { it.start }
    }

    /**
     * Rejects matches glued to surrounding word characters, so "PLATFORM 25"
     * does not read as "RM 25" and "USDT 10" does not read as "USD T10".
     */
    private fun boundariesOk(text: String, start: Int, end: Int): Boolean {
        val before = text.getOrNull(start - 1)
        if (before != null && (before.isLetterOrDigit())) return false
        val after = text.getOrNull(end)
        if (after != null && after.isLetter()) return false
        return true
    }

    private fun resolveCurrency(raw: String, baseCurrency: String): String {
        val base = baseCurrency.uppercase()
        return when (raw) {
            AMBIGUOUS_DOLLAR -> if (base in DOLLAR_BASES) base else "USD"
            AMBIGUOUS_YEN -> if (base in YEN_BASES) base else "JPY"
            else -> raw.uppercase()
        }
    }

    private const val AMBIGUOUS_DOLLAR = "__DOLLAR__"
    private const val AMBIGUOUS_YEN = "__YEN__"
}
