package com.spendly.pdf

import com.spendly.data.Money
import com.spendly.parser.AmountDetector
import com.spendly.parser.TransferDetector
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Turns the text of a bank or e-wallet statement into candidate spends.
 *
 * Statement layouts are not standardised in any useful sense — every bank
 * invents its own column order, date format and debit marker — so this is
 * frankly heuristic, and the import screen *always* shows what it found for
 * review. Nothing here is ever written to the database unattended.
 *
 * Pure Kotlin, no Android imports, so the heuristics can be tested against real
 * statement shapes on the JVM.
 */
object StatementParser {

    enum class Kind {
        /** Money out. The only kind that becomes an entry. */
        SPEND,

        /** Money in — salary, refunds, incoming transfers. */
        CREDIT,

        /** A reload or a move between the user's own accounts. Never spending. */
        TRANSFER,
    }

    data class Row(
        val date: LocalDate?,
        val description: String,
        val amountMinor: Long,
        val currency: String,
        val kind: Kind,
        /** Why a row is not a spend, for the "skipped" list. */
        val skipReason: String? = null,
        val rawLine: String,
    )

    data class Result(
        val rows: List<Row>,
        val currency: String,
        /** Lines that held a date and an amount but could not be read. */
        val unparsedCount: Int,
    ) {
        val spends: List<Row> get() = rows.filter { it.kind == Kind.SPEND }
        val skipped: List<Row> get() = rows.filter { it.kind != Kind.SPEND }
    }

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    /**
     * Date formats seen on statements, most specific first. Two-digit years and
     * year-less dates both appear, so the statement's own year is used as the
     * fallback rather than today's.
     */
    private val DATE_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\b(\d{4}-\d{2}-\d{2})\b""") to "yyyy-MM-dd",
        Regex("""\b(\d{2}/\d{2}/\d{4})\b""") to "dd/MM/yyyy",
        Regex("""\b(\d{2}-\d{2}-\d{4})\b""") to "dd-MM-yyyy",
        Regex("""\b(\d{2}\.\d{2}\.\d{4})\b""") to "dd.MM.yyyy",
        Regex("""\b(\d{2}/\d{2}/\d{2})\b""") to "dd/MM/yy",
        Regex("""\b(\d{2}-\d{2}-\d{2})\b""") to "dd-MM-yy",
        Regex("""\b(\d{1,2} [A-Za-z]{3,9} \d{4})\b""") to "d MMMM yyyy",
        Regex("""\b([A-Za-z]{3,9} \d{1,2},? \d{4})\b""") to "MMMM d, yyyy",
        // Year-less, e.g. "01 FEB" — resolved against the statement year.
        Regex("""\b(\d{1,2} [A-Za-z]{3})\b""") to "d MMM",
    )

    private fun formatterFor(pattern: String, fallbackYear: Int): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .apply {
                if (!pattern.contains("y")) {
                    parseDefaulting(ChronoField.YEAR, fallbackYear.toLong())
                }
            }
            .toFormatter(Locale.ENGLISH)

    /** Returns the date and the index range it occupied, so it can be stripped. */
    private fun findDate(line: String, fallbackYear: Int): Pair<LocalDate, IntRange>? {
        for ((regex, pattern) in DATE_PATTERNS) {
            val match = regex.find(line) ?: continue
            val text = match.groupValues[1]
            val parsed = runCatching {
                LocalDate.parse(text, formatterFor(pattern, fallbackYear))
            }.getOrNull() ?: runCatching {
                // "d MMMM yyyy" also has to cover "01 Feb 2026".
                LocalDate.parse(text, formatterFor(pattern.replace("MMMM", "MMM"), fallbackYear))
            }.getOrNull()
            if (parsed != null) return parsed to match.range
        }
        return null
    }

    // ------------------------------------------------------------------
    // Amounts
    // ------------------------------------------------------------------

    /**
     * A money-shaped number on a statement line. Requires either decimals or a
     * thousands separator, so bare integers — card fragments, reference numbers,
     * page numbers — are not mistaken for amounts.
     */
    private val AMOUNT = Regex(
        """(?<![\d.,])([-+]?)\s?(\d{1,3}(?:,\d{3})+(?:\.\d{2})?|\d+\.\d{2})\s?(CR|DR|-|\+)?(?![\d])""",
        RegexOption.IGNORE_CASE,
    )

    private data class AmountHit(
        val minor: Long,
        val range: IntRange,
        /** true = credit, false = debit, null = the line did not say. */
        val isCredit: Boolean?,
    )

    private fun findAmounts(line: String): List<AmountHit> =
        AMOUNT.findAll(line).mapNotNull { m ->
            val sign = m.groupValues[1]
            val body = m.groupValues[2]
            val marker = m.groupValues[3].uppercase()
            val minor = Money.parseToMinor(body) ?: return@mapNotNull null
            if (minor <= 0L) return@mapNotNull null
            val credit = when {
                marker == "CR" || sign == "+" || marker == "+" -> true
                marker == "DR" || sign == "-" || marker == "-" -> false
                else -> null
            }
            AmountHit(minor, m.range, credit)
        }.toList()

    // ------------------------------------------------------------------
    // Direction
    // ------------------------------------------------------------------

    private val CREDIT_WORDS = listOf(
        "salary", "payroll", "refund", "reversal", "reversed", "cashback",
        "rebate", "interest", "dividend", "received", "incoming", "credit",
        "deposit", "repayment received", "claim",
    )

    private val DEBIT_WORDS = listOf(
        "purchase", "payment", "paid", "pos", "debit", "withdrawal", "atm",
        "fee", "charge", "subscription", "bill", "insurance", "transfer to",
        "duitnow", "spending",
    )

    private fun looksLikeCredit(description: String): Boolean {
        val d = description.lowercase()
        val credit = CREDIT_WORDS.count { d.contains(it) }
        val debit = DEBIT_WORDS.count { d.contains(it) }
        return credit > debit
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private val NOISE = listOf(
        "opening balance", "closing balance", "balance b/f", "balance c/f",
        "brought forward", "carried forward", "total", "subtotal", "statement",
        "page ", "account number", "account no", "summary", "minimum payment",
        "credit limit", "available balance", "statement date", "due date",
    )

    private fun isNoise(line: String): Boolean {
        val l = line.lowercase()
        return NOISE.any { l.contains(it) }
    }

    /**
     * @param fallbackYear the year to assume for dates written without one.
     *   Statements are usually recent, so the caller passes the statement's own
     *   year when it can find one and the current year otherwise.
     */
    fun parse(
        text: String,
        baseCurrency: String,
        fallbackYear: Int = LocalDate.now().year,
    ): Result {
        val currency = detectCurrency(text, baseCurrency)
        val rows = mutableListOf<Row>()
        var unparsed = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim().replace(Regex("\\s{2,}"), "  ")
            if (line.length < 6) continue
            if (isNoise(line)) continue

            val date = findDate(line, fallbackYear)
            val amounts = findAmounts(line)
            if (date == null || amounts.isEmpty()) continue

            // The rightmost amount on a statement line is almost always the
            // running balance, so with two or more the transaction is the one
            // before it. With exactly one, that one is the transaction.
            val txn = if (amounts.size >= 2) amounts[amounts.size - 2] else amounts[0]

            val description = describe(line, date.second, amounts)
            if (description.isBlank()) {
                unparsed++
                continue
            }

            val isCredit = txn.isCredit ?: looksLikeCredit(description)

            val kind: Kind
            val reason: String?
            when {
                TransferDetector.isTransfer(description) -> {
                    kind = Kind.TRANSFER
                    reason = TransferDetector.reasonFor(description)
                }
                isCredit -> {
                    kind = Kind.CREDIT
                    reason = "Money in, not spending"
                }
                else -> {
                    kind = Kind.SPEND
                    reason = null
                }
            }

            rows += Row(
                date = date.first,
                description = description,
                amountMinor = txn.minor,
                currency = currency,
                kind = kind,
                skipReason = reason,
                rawLine = line,
            )
        }

        return Result(rows = rows, currency = currency, unparsedCount = unparsed)
    }

    /** Everything on the line that is not the date or an amount. */
    private fun describe(line: String, dateRange: IntRange, amounts: List<AmountHit>): String {
        val drop = BooleanArray(line.length)
        for (i in dateRange) if (i in drop.indices) drop[i] = true
        amounts.forEach { hit -> for (i in hit.range) if (i in drop.indices) drop[i] = true }

        val sb = StringBuilder()
        for (i in line.indices) if (!drop[i]) sb.append(line[i])

        return sb.toString()
            .replace(Regex("[|;]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('-', '.', ',', ':')
            .trim()
            .let { if (it.length > 80) it.take(80).trim() else it }
    }

    /**
     * The statement's currency, taken from whatever symbol or code appears in
     * the document. Statements rarely mix currencies, and the amounts on the
     * lines themselves are usually bare numbers.
     */
    private fun detectCurrency(text: String, baseCurrency: String): String {
        val hits = AmountDetector.findAll(text.take(4000), baseCurrency)
        val explicit = hits.firstOrNull { !it.currencyWasAmbiguous }
        if (explicit != null) return explicit.currency
        val code = Regex("""\b(MYR|SGD|USD|EUR|GBP|THB|IDR|PHP|VND|JPY|CNY|INR|AUD|HKD)\b""")
            .find(text.take(4000))?.groupValues?.get(1)
        return code ?: baseCurrency
    }

    /** Best guess at the year a statement covers, for year-less dates. */
    fun detectYear(text: String): Int? =
        Regex("""\b(20\d{2})\b""").findAll(text.take(4000))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 2000..LocalDate.now().year + 1 }
            .toList()
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}
