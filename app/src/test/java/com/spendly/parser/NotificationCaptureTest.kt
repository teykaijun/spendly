package com.spendly.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two bugs that made notification spends appear not
 * to save. Both were silent: nothing errored, the notification just vanished.
 *
 * 1. A running balance counted as a "competing" amount and cost 15 points,
 *    which pushed ordinary bank alerts to 50 — below the default threshold of
 *    55 — so they were dropped before reaching the Inbox.
 * 2. The listener concatenated the expanded and collapsed text even though one
 *    contains the other, repeating the amount and making (1) worse.
 */
class NotificationCaptureTest {

    /** The app's default sensitivity. */
    private val defaultThreshold = 55

    /** Assemble exactly as SpendNotificationListener does, then parse. */
    private fun capture(
        title: String,
        text: String? = null,
        bigText: String? = null,
        pkg: String = "my.com.hlb.connect",
        label: String = "HLB Connect",
    ) = NotificationParser.parse(
        title = title,
        text = NotificationText.combine(bigText = bigText, text = text),
        packageName = pkg,
        appLabel = label,
        baseCurrency = "MYR",
    )

    // ---------- the notification that used to be dropped ----------

    @Test
    fun `an ordinary bank alert with a balance line now clears the default threshold`() {
        // Measured before the fix: confidence 50, silently dropped.
        val r = capture(
            title = "HLB Connect",
            text = "RM25.50 debited from your account",
            bigText = "RM25.50 debited from your account ending 1234. Available balance: RM1,234.00",
        )
        assertNotNull(r)
        assertEquals(2550L, r!!.amountMinor)
        assertTrue(
            "confidence ${r.confidence} is below the default threshold $defaultThreshold",
            r.confidence >= defaultThreshold,
        )
    }

    @Test
    fun `the balance is never chosen as the transaction amount`() {
        val r = capture(
            title = "Bank",
            text = "Available balance: RM1,234.00. You spent RM25.50 at MYDIN",
        )
        assertEquals(2550L, r!!.amountMinor)
    }

    @Test
    fun `balance-first alerts pick the spend`() {
        val r = capture(title = "Bank", text = "Balance RM1,234.00. RM25.50 spent at MYDIN")
        assertEquals(2550L, r!!.amountMinor)
    }

    @Test
    fun `an abbreviated balance label is still recognised`() {
        val r = capture(title = "Bank", text = "RM25.50 spent at MYDIN. Avail bal. RM1,234.00")
        assertEquals(2550L, r!!.amountMinor)
        assertTrue(r.confidence >= defaultThreshold)
    }

    @Test
    fun `a balance word in an earlier sentence does not tag the transaction`() {
        // A naive 30-character look-back would reach "Balance" and wrongly mark
        // RM25.50 as a balance. The look-back must stop at the sentence break.
        val r = capture(title = "Bank", text = "Balance updated. RM25.50 spent at MYDIN")
        assertEquals(2550L, r!!.amountMinor)
    }

    @Test
    fun `malay and indonesian balance labels are recognised`() {
        val baki = capture(title = "Bank", text = "RM25.50 dibayar di MYDIN. Baki: RM1,234.00 paid")
        assertEquals(2550L, baki!!.amountMinor)
    }

    @Test
    fun `two genuinely competing amounts still cost confidence`() {
        // Real ambiguity should still be penalised — only balances are exempt.
        val single = capture(title = "Bank", text = "You paid RM25.50 at MYDIN")!!
        val ambiguous = capture(title = "Bank", text = "You paid RM25.50 or RM30.00 at MYDIN")!!
        assertTrue(ambiguous.confidence < single.confidence)
    }

    // ---------- text assembly ----------

    @Test
    fun `expanded text that contains the collapsed text is not repeated`() {
        val body = NotificationText.combine(
            bigText = "RM25.50 debited from your account ending 1234",
            text = "RM25.50 debited from your account",
        )
        assertEquals("RM25.50 debited from your account ending 1234", body)
        assertEquals(1, Regex("RM25\\.50").findAll(body).count())
    }

    @Test
    fun `order of arrival does not matter`() {
        val a = NotificationText.combine(bigText = "long text RM5.00 here", text = "RM5.00 here")
        val b = NotificationText.combine(bigText = "RM5.00 here", text = "long text RM5.00 here")
        assertEquals(1, Regex("RM5\\.00").findAll(a).count())
        assertEquals(1, Regex("RM5\\.00").findAll(b).count())
    }

    @Test
    fun `genuinely different parts are all kept`() {
        val body = NotificationText.combine(text = "Payment of RM25.50", subText = "Card ending 1234")
        assertTrue(body.contains("Payment of RM25.50"))
        assertTrue(body.contains("Card ending 1234"))
    }

    @Test
    fun `an sms arriving as a chat message is read from the message`() {
        // Messaging apps sometimes collapse the text to a count; the words live
        // in the newest message.
        val body = NotificationText.combine(
            text = "2 new messages",
            latestMessage = "MAYBANK: RM88.00 spent at PETRONAS on 21/09",
        )
        val r = NotificationParser.parse(
            "MAYBANK", body, "com.google.android.apps.messaging", "Messages", "MYR",
        )
        assertNotNull(r)
        assertEquals(8800L, r!!.amountMinor)
    }

    @Test
    fun `inbox style lines are included`() {
        val body = NotificationText.combine(
            text = "Transaction alert",
            textLines = listOf("You paid RM12.00 at KFC"),
        )
        assertTrue(body.contains("RM12.00"))
    }

    @Test
    fun `blank and missing fields are ignored`() {
        assertEquals("", NotificationText.combine(bigText = "  ", text = null))
        assertFalse(NotificationText.combine(text = "hi", subText = "").endsWith(" "))
    }
}
