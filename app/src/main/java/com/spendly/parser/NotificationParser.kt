package com.spendly.parser

/**
 * Turns a posted notification into a candidate spend, or decides it is not one.
 *
 * The bias is deliberately towards *missing* spends rather than inventing them:
 * a missed notification costs one manual entry, whereas a false positive quietly
 * corrupts the record. So a candidate needs all three of an amount, a
 * spend-shaped verb, and the absence of any disqualifying phrase.
 *
 * Pure Kotlin, no Android imports — see `NotificationParserTest`.
 */
object NotificationParser {

    data class Result(
        val amountMinor: Long,
        val currency: String,
        val merchant: String?,
        /** 0-100. Drives auto-save vs. queue, and is shown in the inbox. */
        val confidence: Int,
        /** The phrase that made us treat this as a spend. Useful when tuning. */
        val matchedKeyword: String,
    )

    /**
     * Phrases that mean "money left your account". At least one must appear.
     * Kept as plain substrings with explicit word boundaries applied at match
     * time, which is far easier to extend than one giant alternation.
     */
    private val SPEND_KEYWORDS = listOf(
        "you spent", "spent", "you paid", "paid", "payment of", "payment to",
        "payment made", "payment successful", "successful payment", "bill payment",
        "purchase", "purchased", "debited", "debit", "charged", "charge of",
        "transaction of", "transaction at", "deducted", "deduction",
        "withdrawal", "withdrew", "cash out", "checkout", "order placed",
        "transferred to", "transfer to", "sent to", "duitnow", "paynow",
        "paylah", "card ending", "pos purchase", "tap to pay", "contactless",
        "has been used", "was used at", "swiped",
        // Receipt and subscription email shapes. Gmail puts the sender in the
        // title and the subject/snippet in the body, which is usually enough.
        "receipt", "invoice", "billed", "we've charged", "we have charged",
        "subscription renewed", "auto-renewed", "automatically renewed",
        "your renewal", "renews on", "order confirmation", "your order",
        "received your payment", "thanks for your payment",
        "thank you for your payment", "payment confirmation", "amount charged",
        "total charged", "you were charged",
    )

    /**
     * Any of these disqualifies the notification outright. Money coming *in*,
     * marketing, security codes, and failed transactions all look superficially
     * like spends because they contain an amount.
     */
    private val REJECT_KEYWORDS = listOf(
        // Money in
        "refund", "refunded", "reversed", "reversal", "credited", "credit alert",
        "received from", "you received", "money received", "has been credited",
        "cashback", "rebate", "reward", "you earned", "earned",
        // Marketing
        "% off", "discount", "promo", "voucher", "coupon", "sale ends",
        "limited time", "special offer", "deal", "save up to", "up to",
        "free shipping", "claim now", "don't miss", "flash sale",
        "off your", "next purchase", "next order", "next ride",
        // Security
        "otp", "one-time password", "one time password", "verification code",
        "security code", "do not share", "login attempt", "signed in",
        // Not (yet) money
        "failed", "unsuccessful", "declined", "cancelled", "canceled",
        "expired", "expiring", "reminder", "due on", "due date", "is due",
        "statement", "e-statement", "upcoming", "scheduled for",
        "low balance", "insufficient", "requesting", "has requested",
        "pending approval", "awaiting",
        // Email chrome. A marketing mail that happens to say "receipt" should
        // not become a spend just because it also quotes a price.
        "unsubscribe", "view in browser", "newsletter", "you may also like",
        "recommended for you", "trending now", "invitation to", "webinar",
        "your cart", "abandoned", "back in stock", "price drop",
        "starting at", "from only", "as low as",
    )

    /** Package name fragments that make a notification much more likely to be financial. */
    private val FINANCIAL_HINTS = listOf(
        "bank", "pay", "wallet", "finance", "card", "money", "duit", "cash",
        "grab", "shopee", "boost", "tng", "touchngo", "maybank", "cimb",
        "rhb", "hongleong", "ambank", "bsn", "bigpay", "dbs", "ocbc", "uob",
        "revolut", "wise", "paypal", "venmo", "chase", "citi", "hsbc",
        "gpay", "phonepe", "paytm", "monzo", "starling", "n26", "klarna",
    )

    /**
     * Words that terminate a merchant name. Bank copy runs merchant names
     * straight into trailing clauses ("at STARBUCKS KLCC on 12 Jan"), so the
     * name has to be cut at the first of these.
     */
    private val MERCHANT_STOP_WORDS = setOf(
        "on", "using", "with", "for", "from", "via", "ref", "reference",
        "at", "your", "account", "card", "and", "the", "was", "is", "has",
        "successful", "successfully", "completed", "approved", "done",
        "txn", "id", "dated", "today", "yesterday",
    )

    private val MERCHANT_PATTERNS = listOf(
        Regex("""\b(?:at|to)\s+([A-Za-z0-9][A-Za-z0-9 &'./*\-]{1,44})""", RegexOption.IGNORE_CASE),
        Regex("""\bfrom your [^.]*?\bfor\s+([A-Za-z0-9][A-Za-z0-9 &'./*\-]{1,44})""", RegexOption.IGNORE_CASE),
    )

    fun parse(
        title: String?,
        text: String?,
        packageName: String,
        appLabel: String,
        baseCurrency: String,
    ): Result? {
        val titlePart = title.orEmpty().trim()
        val textPart = text.orEmpty().trim()
        val blob = listOf(titlePart, textPart).filter { it.isNotEmpty() }.joinToString(" — ")
        if (blob.isBlank()) return null

        val lower = blob.lowercase()

        // 1. Hard rejects first — cheapest way to drop the bulk of notifications.
        REJECT_KEYWORDS.firstOrNull { containsPhrase(lower, it) }?.let { return null }

        // 2. Reloads and self-transfers move money between your own accounts.
        //    Counting them would double-count everything bought from the wallet
        //    afterwards, so they are never spending.
        if (TransferDetector.isTransfer(blob)) return null

        // 3. Must look like money leaving the account.
        val keyword = SPEND_KEYWORDS.firstOrNull { containsPhrase(lower, it) } ?: return null

        // 4. Must contain a currency amount.
        val hits = AmountDetector.findAll(blob, baseCurrency)
        if (hits.isEmpty()) return null

        // 5. A running balance is not a competing transaction amount — it is in
        //    almost every bank alert. Set balance-labelled amounts aside, then
        //    take whichever remaining amount sits closest to the spend phrase.
        val balance = hits.filter { isBalanceAmount(blob, it, hits) }
        val candidates = (hits - balance.toSet()).ifEmpty { hits }
        val keywordIndex = lower.indexOf(keyword)
        val chosen = candidates.minBy { hit ->
            val mid = (hit.start + hit.end) / 2
            kotlin.math.abs(mid - keywordIndex)
        }

        val merchant = extractMerchant(blob, titlePart, appLabel)

        var confidence = 50
        if (merchant != null) confidence += 15
        if (!chosen.currencyWasAmbiguous) confidence += 10
        if (looksFinancial(packageName)) confidence += 15
        // Only genuine ambiguity costs confidence. Penalising the balance line
        // used to push ordinary bank alerts below the default threshold, so they
        // were dropped before ever reaching the Inbox.
        if (candidates.size > 1) confidence -= 15
        if (titlePart.isNotEmpty() && textPart.isNotEmpty()) confidence += 5
        confidence = confidence.coerceIn(0, 100)

        return Result(
            amountMinor = chosen.amountMinor,
            currency = chosen.currency,
            merchant = merchant,
            confidence = confidence,
            matchedKeyword = keyword,
        )
    }

    /**
     * Messaging, mail and social apps. People genuinely write "I paid RM50 at
     * the mall" to each other, and that sentence is indistinguishable from a
     * bank alert by any rule this parser could apply.
     *
     * These are not hard-blocked — they are merely switched *off* by default
     * when first seen, so they show up in Settings ready to be turned on rather
     * than silently filling the inbox with chat messages.
     *
     * SMS apps are deliberately *not* on this list. In many countries the bank's
     * transaction alert arrives as a text message, which is one of the most
     * valuable sources there is.
     */
    private val CHATTY_PACKAGES = listOf(
        "whatsapp", "telegram", "messenger", "com.facebook", "signal",
        "instagram", "twitter", "discord", "slack", "snapchat",
        "tiktok", "reddit", "linkedin", "wechat", "viber", "jp.naver.line",
        "com.google.android.gm", "outlook", "yahoo.mail", "protonmail",
        "skype", "zoom", "teams", "threads", "tinder",
    )

    fun looksFinancial(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return FINANCIAL_HINTS.any { pkg.contains(it) }
    }

    /**
     * Whether an app should be allowed to create entries the first time we see
     * it. Unknown apps default to on — a bank whose package name we do not
     * recognise must not fail silently — but chatty apps default to off.
     */
    fun defaultEnabledFor(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (looksFinancial(pkg)) return true
        return CHATTY_PACKAGES.none { pkg.contains(it) }
    }

    /**
     * Substring match that still respects word boundaries, so "debit" does not
     * fire on "debitable" and "pay" does not fire on "paypal".
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

    private fun extractMerchant(blob: String, title: String, appLabel: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val raw = pattern.find(blob)?.groupValues?.getOrNull(1) ?: continue
            val cleaned = cleanMerchant(raw)
            if (cleaned != null) return cleaned
        }
        // Many wallet apps put only the merchant in the title.
        if (title.isNotEmpty() &&
            !title.equals(appLabel, ignoreCase = true) &&
            title.length in 2..44 &&
            title.none { it.isDigit() }
        ) {
            return cleanMerchant(title)
        }
        return null
    }

    private fun cleanMerchant(raw: String): String? {
        val words = raw.trim().split(Regex("\\s+"))
        val kept = mutableListOf<String>()
        for (word in words) {
            val bare = word.trim('.', ',', ';', ':', '!', '?', '*', '-', '/')
            if (bare.isEmpty()) break
            // Stop at a clause boundary, but never produce an empty name.
            if (kept.isNotEmpty() && bare.lowercase() in MERCHANT_STOP_WORDS) break
            // Stop at anything date-like or reference-like.
            if (kept.isNotEmpty() && bare.all { it.isDigit() }) break
            // Bank copy writes merchants in caps or title case and the trailing
            // narration in lower case ("... to Ali Bin Abu successful"), so once
            // a capitalised name has started, a lower-case word ends it.
            if (kept.isNotEmpty() && kept[0].first().isUpperCase() && isAllLowerCaseWord(bare)) break
            kept += bare
            if (kept.size >= 6) break
        }
        if (kept.isEmpty()) return null
        var name = kept.joinToString(" ").trim()
        if (name.length < 2) return null
        if (name.length > 40) name = name.take(40).trim()
        // Bank copy is usually SHOUTED; title case reads better in a list.
        if (name == name.uppercase() && name.any { it.isLetter() }) {
            name = name.split(" ").joinToString(" ") { w ->
                if (w.length <= 3 && w.all { it.isUpperCase() }) w
                else w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        return name
    }

    /**
     * Words that label the account balance rather than the transaction.
     * "baki" and "saldo" are the Malay and Indonesian terms banks in the region
     * actually print. Word-bounded so "global" does not read as "bal".
     */
    private val BALANCE_LABEL = Regex(
        """\b(balance|bal|avail|available|baki|saldo|remaining)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Abbreviations that end in a full stop without ending the sentence. */
    private val ABBREVIATIONS = setOf("bal", "avail", "acct", "ac", "no", "ref", "amt", "txn", "approx")

    private val SENTENCE_BREAK = Regex("""[.!?;]\s|\n""")

    /**
     * True when the words introducing [hit] — within its own clause — say it is
     * the account balance.
     *
     * The clause matters. A plain look-back window reaches across sentences, so
     * in "Balance updated. RM25.50 spent" the transaction would be tagged as a
     * balance. The look-back therefore stops at the previous amount and at the
     * last real sentence break, where "bal." and "avail." do not count as one.
     */
    private fun isBalanceAmount(blob: String, hit: AmountDetector.Hit, all: List<AmountDetector.Hit>): Boolean {
        val previousEnd = all.filter { it.end <= hit.start }.maxOfOrNull { it.end } ?: 0
        val from = maxOf(previousEnd, hit.start - 40).coerceAtLeast(0)
        val window = blob.substring(from, hit.start)

        var clauseStart = 0
        for (m in SENTENCE_BREAK.findAll(window)) {
            val wordBefore = window.substring(0, m.range.first)
                .takeLastWhile { it.isLetter() }
                .lowercase()
            if (wordBefore !in ABBREVIATIONS) clauseStart = m.range.last + 1
        }
        return BALANCE_LABEL.containsMatchIn(window.substring(clauseStart))
    }

    private fun isAllLowerCaseWord(word: String): Boolean =
        word.any { it.isLetter() } && word.none { it.isUpperCase() }

    /** Normalised merchant key used for "remember this category" rules. */
    fun merchantKey(merchant: String?): String? {
        val m = merchant?.lowercase()?.replace(Regex("[^a-z0-9]+"), " ")?.trim()
        return if (m.isNullOrBlank()) null else m
    }
}
