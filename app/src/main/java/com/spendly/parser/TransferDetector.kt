package com.spendly.parser

/**
 * Recognises money moving between your own accounts, which is not spending.
 *
 * The case that matters is an e-wallet reload. Topping up Touch 'n Go from your
 * bank produces *two* records — the bank debit and, later, whatever you actually
 * buy with the wallet balance. Counting both double-counts every ringgit: the
 * money is spent once, when it leaves the wallet, not when it moves into it.
 *
 * Shared by the notification parser and the statement importer so both agree,
 * and kept free of Android imports so it can be tested on the JVM.
 */
object TransferDetector {

    /**
     * Phrases that mean "this moved between accounts you control".
     *
     * Deliberately conservative about what counts: only phrasings that are
     * overwhelmingly used for reloads and self-transfers, not every sentence
     * containing the word "transfer" — paying a person by bank transfer *is*
     * spending, and must keep counting.
     */
    private val TRANSFER_KEYWORDS = listOf(
        // E-wallet reloads
        "reload", "reloaded", "reloading", "re-load",
        "top up", "top-up", "topup", "topped up", "topping up",
        "add money", "added money", "add funds", "added funds",
        "cash in", "cash-in", "cashin",
        "load wallet", "wallet load", "fund wallet", "wallet funding",
        // Moving between your own accounts
        "transfer to own", "own account", "to your own",
        "between your accounts", "self transfer", "self-transfer",
        "balance transfer", "internal transfer",
        // Card/wallet balance movements
        "moved to wallet", "added to wallet", "added to balance",
        "credited to your wallet", "wallet credited",
    )

    /**
     * True when [text] describes a reload or a transfer between the user's own
     * accounts, and therefore must not be recorded as spending.
     */
    fun isTransfer(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val haystack = text.lowercase()
        return TRANSFER_KEYWORDS.any { containsPhrase(haystack, it) }
    }

    /**
     * A short reason for the UI, so a skipped row explains itself instead of
     * silently vanishing.
     */
    fun reasonFor(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val haystack = text.lowercase()
        val hit = TRANSFER_KEYWORDS.firstOrNull { containsPhrase(haystack, it) } ?: return null
        return "Looks like a reload or transfer (“$hit”), not spending"
    }

    /**
     * Word-boundary aware substring match, so "reload" does not fire inside
     * "preloaded" and "cash in" does not fire inside "cashing".
     */
    private fun containsPhrase(haystack: String, phrase: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(phrase, from)
            if (i < 0) return false
            val before = haystack.getOrNull(i - 1)
            val after = haystack.getOrNull(i + phrase.length)
            val startOk = before == null || !before.isLetterOrDigit()
            val endOk = after == null || !after.isLetterOrDigit()
            if (startOk && endOk) return true
            from = i + 1
        }
    }
}
