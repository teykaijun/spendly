package com.spendly.parser

import com.spendly.data.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples below are written in the style real banks and wallets use. When a
 * notification from your own bank is missed or misread, add it here first —
 * a failing test is a much faster loop than reinstalling the app.
 */
class NotificationParserTest {

    private fun parse(
        title: String?,
        text: String?,
        pkg: String = "com.example.bank",
        label: String = "Bank",
        base: String = "MYR",
    ) = NotificationParser.parse(title, text, pkg, label, base)

    // ---------- amounts across currencies ----------

    @Test
    fun `parses ringgit with symbol prefix`() {
        val r = parse("Maybank2u", "You have paid RM25.50 to STARBUCKS KLCC on 12 Jan")
        assertNotNull(r)
        assertEquals(2550L, r!!.amountMinor)
        assertEquals("MYR", r.currency)
    }

    @Test
    fun `parses singapore dollars without confusing them for usd`() {
        val r = parse("DBS", "You paid S$18.90 to FairPrice", base = "SGD")
        assertNotNull(r)
        assertEquals(1890L, r!!.amountMinor)
        assertEquals("SGD", r.currency)
    }

    @Test
    fun `us dollar prefix is not read as singapore dollar`() {
        val r = parse("Card alert", "Transaction of US$42.00 at AMAZON", base = "MYR")
        assertNotNull(r)
        assertEquals("USD", r!!.currency)
        assertEquals(4200L, r.amountMinor)
    }

    @Test
    fun `bare dollar resolves to the user's base currency`() {
        val r = parse("Chase", "You spent $12.34 at WALGREENS", base = "USD")
        assertEquals("USD", r!!.currency)

        val sg = parse("Card", "You spent $12.34 at NTUC", base = "SGD")
        assertEquals("SGD", sg!!.currency)
    }

    @Test
    fun `parses grouped thousands in both separator styles`() {
        val uk = parse("Bank", "You paid £1,234.56 to LANDLORD", base = "GBP")
        assertEquals(123456L, uk!!.amountMinor)

        val eu = parse("Bank", "Payment of €1.234,56 to VERMIETER", base = "EUR")
        assertEquals(123456L, eu!!.amountMinor)
    }

    @Test
    fun `parses iso code before and after the number`() {
        val before = parse("Alert", "MYR 88.00 debited from your account")
        assertEquals(8800L, before!!.amountMinor)
        assertEquals("MYR", before.currency)

        val after = parse("Alert", "88.00 MYR debited from your account")
        assertEquals(8800L, after!!.amountMinor)
        assertEquals("MYR", after.currency)
    }

    @Test
    fun `parses rupee and baht symbols`() {
        val inr = parse("PhonePe", "You paid ₹1,250 to BIG BAZAAR", base = "INR")
        assertEquals(125000L, inr!!.amountMinor)
        assertEquals("INR", inr.currency)

        val thb = parse("Alert", "Purchase of ฿350.00 at 7-ELEVEN", base = "THB")
        assertEquals(35000L, thb!!.amountMinor)
        assertEquals("THB", thb.currency)
    }

    // ---------- things that must NOT become spends ----------

    @Test
    fun `ignores incoming money`() {
        assertNull(parse("Maybank2u", "You have received RM500.00 from JOHN DOE"))
        assertNull(parse("Bank", "RM120.00 has been credited to your account"))
        assertNull(parse("Bank", "Refund of RM45.00 processed"))
    }

    @Test
    fun `ignores marketing`() {
        assertNull(parse("Shopee", "Flash sale! Get RM50 off your next purchase"))
        assertNull(parse("Grab", "Save up to RM15 on your next ride"))
        assertNull(parse("Store", "Special offer: spend RM100, get RM20 voucher"))
    }

    @Test
    fun `ignores otp and security messages`() {
        assertNull(parse("Bank", "Your OTP is 123456 for a payment of RM250.00. Do not share."))
        assertNull(parse("Bank", "Verification code 8891 to authorise RM99 purchase"))
    }

    @Test
    fun `ignores failed and scheduled transactions`() {
        assertNull(parse("Bank", "Your payment of RM75.00 has failed"))
        assertNull(parse("Bank", "Payment of RM75.00 was declined"))
        assertNull(parse("Bank", "Reminder: your bill of RM180.00 is due on 20 Jan"))
    }

    @Test
    fun `ignores notifications with no amount`() {
        assertNull(parse("WhatsApp", "You paid attention to the group chat"))
    }

    @Test
    fun `ignores notifications with no spend verb`() {
        assertNull(parse("News", "The RM50 note is being redesigned"))
    }

    // ---------- reloads are not spending ----------

    @Test
    fun `wallet reloads are not recorded as spending`() {
        // Counting the reload AND the later wallet purchase double-counts, so
        // the money is recorded once, when it actually leaves the wallet.
        assertNull(parse("TNG eWallet", "Reload of RM50.00 successful"))
        assertNull(parse("Maybank2u", "You paid RM100.00 to reload your Touch n Go eWallet"))
        assertNull(parse("GrabPay", "Top up of RM80.00 successful"))
        assertNull(parse("Boost", "RM30.00 has been debited for wallet top-up"))
        assertNull(parse("ShopeePay", "Add Money RM200.00 completed"))
        assertNull(parse("Bank", "Cash in RM150.00 to your wallet"))
    }

    @Test
    fun `self transfers are not spending`() {
        assertNull(parse("Bank", "Transfer to own account RM500.00 successful"))
        assertNull(parse("Bank", "Internal transfer of RM200.00 completed"))
        assertNull(parse("Card", "Balance transfer of RM1,000.00 processed"))
    }

    @Test
    fun `paying someone by transfer is still spending`() {
        // Only self-transfers are excluded; a DuitNow to a person is a real spend.
        val r = parse("Maybank2u", "DuitNow payment of RM30.00 to Ali Bin Abu successful")
        assertNotNull(r)
        assertEquals(3000L, r!!.amountMinor)
    }

    @Test
    fun `a spend at a merchant whose name contains a reload word still counts`() {
        // Word boundaries stop "preloaded" from reading as "reload".
        val r = parse("Card", "You paid RM25.00 at PRELOADED CARD SHOP")
        assertNotNull(r)
        assertEquals(2500L, r!!.amountMinor)
    }

    // ---------- amount selection ----------

    @Test
    fun `picks the spend amount not the running balance`() {
        val r = parse("Maybank2u", "RM25.50 spent at MYDIN. Available balance: RM1,234.00")
        assertNotNull(r)
        assertEquals(2550L, r!!.amountMinor)
    }

    // ---------- merchant extraction ----------

    @Test
    fun `extracts merchant after at`() {
        val r = parse("Card", "You spent RM19.90 at VILLAGE GROCER on 3 Feb")
        assertEquals("Village Grocer", r!!.merchant)
    }

    @Test
    fun `extracts merchant after to`() {
        val r = parse("DuitNow", "Payment of RM30.00 to Ali Bin Abu successful")
        assertEquals("Ali Bin Abu", r!!.merchant)
    }

    @Test
    fun `falls back to the title when it is merchant-shaped`() {
        val r = parse("Tealive", "Payment successful RM12.50", label = "TNG eWallet")
        assertEquals("Tealive", r!!.merchant)
    }

    @Test
    fun `does not glue trailing clauses onto the merchant`() {
        val r = parse("Card", "RM88.00 charged at PETRONAS BANGSAR using card ending 1234")
        assertEquals("Petronas Bangsar", r!!.merchant)
    }

    // ---------- word boundaries ----------

    @Test
    fun `does not read RM out of the middle of a word`() {
        assertNull(parse("App", "Your PLATFORM 25 subscription was purchased"))
    }

    @Test
    fun `does not read USD out of USDT`() {
        val r = parse("Wallet", "You paid 10 USDT for something", base = "MYR")
        // No valid currency should be found, so no candidate.
        assertNull(r)
    }

    // ---------- confidence ----------

    @Test
    fun `known financial package scores higher than an unknown one`() {
        val bank = parse(
            "Maybank2u", "You paid RM25.50 at STARBUCKS",
            pkg = "com.maybank2u.life", label = "Maybank",
        )!!
        val unknown = parse(
            "Some App", "You paid RM25.50 at STARBUCKS",
            pkg = "com.random.notes", label = "Notes",
        )!!
        assertTrue(bank.confidence > unknown.confidence)
    }

    // ---------- subscription and invoice emails ----------
    // Gmail puts the sender in the title and the subject/snippet in the body.

    private fun gmail(sender: String, subject: String) =
        parse(sender, subject, pkg = "com.google.android.gm", label = "Gmail")

    @Test
    fun `reads a subscription receipt email`() {
        val r = gmail("Netflix", "Your receipt from Netflix — RM54.90")
        assertNotNull(r)
        assertEquals(5490L, r!!.amountMinor)
        assertEquals("Netflix", r.merchant)
    }

    @Test
    fun `reads an invoice email`() {
        val r = gmail("Spotify", "Invoice for your subscription: RM22.90")
        assertEquals(2290L, r!!.amountMinor)
    }

    @Test
    fun `reads an auto-renewal email`() {
        val r = gmail("Adobe", "Your subscription renewed — you were charged $19.99", )
        assertNotNull(r)
        assertEquals(1999L, r!!.amountMinor)
    }

    @Test
    fun `reads a payment confirmation email`() {
        val r = gmail("Digital Ocean", "We received your payment of $12.00. Thank you.")
        assertEquals(1200L, r!!.amountMinor)
    }

    @Test
    fun `ignores marketing email that merely mentions a price`() {
        assertNull(gmail("Shopee", "Price drop! Sneakers now RM89. Unsubscribe here"))
        assertNull(gmail("Some Shop", "Plans starting at RM19/month — view in browser"))
        assertNull(gmail("Store", "You left items in your cart worth RM120"))
        assertNull(gmail("Airline", "Fares as low as RM99 — book now"))
    }

    @Test
    fun `ignores an unpaid bill reminder email`() {
        assertNull(gmail("TNB", "Your electricity bill of RM180.00 is due on 20 Jan"))
    }

    // ---------- which apps are trusted by default ----------

    @Test
    fun `chat and social apps start switched off`() {
        assertEquals(false, NotificationParser.defaultEnabledFor("com.whatsapp"))
        assertEquals(false, NotificationParser.defaultEnabledFor("org.telegram.messenger"))
        assertEquals(false, NotificationParser.defaultEnabledFor("com.google.android.gm"))
    }

    @Test
    fun `sms apps start switched on because banks text you`() {
        assertEquals(true, NotificationParser.defaultEnabledFor("com.google.android.apps.messaging"))
        assertEquals(true, NotificationParser.defaultEnabledFor("com.android.mms"))
    }

    @Test
    fun `unknown apps start switched on so a bank is never missed silently`() {
        assertEquals(true, NotificationParser.defaultEnabledFor("com.some.obscure.bank"))
        assertEquals(true, NotificationParser.defaultEnabledFor("com.maybank2u.life"))
    }

    // ---------- category guessing ----------

    @Test
    fun `guesses categories from merchant names`() {
        assertEquals("Food & Drink", CategoryGuesser.guess("Starbucks KLCC"))
        assertEquals("Groceries", CategoryGuesser.guess("Village Grocer"))
        assertEquals("Transport", CategoryGuesser.guess("Petronas Bangsar"))
        assertEquals("Fun", CategoryGuesser.guess("Netflix"))
        assertEquals("Health", CategoryGuesser.guess("Guardian Pharmacy"))
    }

    @Test
    fun `unknown merchant has no guess`() {
        assertNull(CategoryGuesser.guess("Zzzz Qqqq"))
    }

    // ---------- money helpers ----------

    @Test
    fun `money parsing handles messy separators`() {
        assertEquals(2550L, Money.parseToMinor("25.50"))
        assertEquals(2550L, Money.parseToMinor("25,50"))
        assertEquals(123456L, Money.parseToMinor("1,234.56"))
        assertEquals(123456L, Money.parseToMinor("1.234,56"))
        assertEquals(100000L, Money.parseToMinor("1,000"))
        assertEquals(500L, Money.parseToMinor("5"))
        assertNull(Money.parseToMinor("abc"))
    }

    @Test
    fun `compact formatting keeps whole amounts whole`() {
        // Regression: trimming trailing zeros off "20.00" used to yield "2".
        assertEquals("RM20", Money.formatCompact(2000, "MYR"))
        assertEquals("RM50", Money.formatCompact(5000, "MYR"))
        assertEquals("RM20.5", Money.formatCompact(2050, "MYR"))
        assertEquals("RM0.5", Money.formatCompact(50, "MYR"))
        assertEquals("RM99.99", Money.formatCompact(9999, "MYR"))
        assertEquals("RM150", Money.formatCompact(15000, "MYR"))
        assertEquals("RM1.2k", Money.formatCompact(120000, "MYR"))
        assertEquals("RM45k", Money.formatCompact(4500000, "MYR"))
    }

    @Test
    fun `zero decimal currencies drop the cents everywhere`() {
        assertEquals(0, Money.decimals("JPY"))
        assertEquals(0, Money.decimals("KRW"))
        assertEquals(2, Money.decimals("MYR"))
        assertEquals("¥1,250", Money.format(125000, "JPY"))
    }

    @Test
    fun `money formatting puts the symbol in the right place`() {
        assertEquals("RM25.50", Money.format(2550, "MYR"))
        assertEquals("$1,234.56", Money.format(123456, "USD"))
        // Zero-decimal currencies drop the cents.
        assertEquals("¥1,200", Money.format(120000, "JPY"))
    }
}
