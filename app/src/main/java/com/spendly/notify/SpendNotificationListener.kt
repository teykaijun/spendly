package com.spendly.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spendly.data.CaptureLog
import com.spendly.data.Money
import com.spendly.data.Prefs
import com.spendly.data.SpendRepository
import com.spendly.parser.AmountDetector
import com.spendly.parser.NotificationText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads posted notifications and hands the interesting ones to the parser.
 *
 * The user has to grant notification access in system settings before Android
 * will bind this at all, and it can be paused from Spendly's own settings
 * without revoking that access.
 *
 * Nothing here leaves the device: the text is parsed in-process and only the
 * resulting amount, merchant and app name are stored.
 */
class SpendNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repo: SpendRepository by lazy { SpendRepository.get(applicationContext) }
    private val log: CaptureLog by lazy { CaptureLog.get(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ongoing notifications are progress bars, media players and foreground
        // service badges. They re-post constantly and are never a transaction.
        if (sbn.isOngoing) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        // A group summary restates its children ("3 new messages"). The children
        // carry the actual transaction, and reading both would double-count.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.readText(Notification.EXTRA_TITLE)
            ?: extras.readText(Notification.EXTRA_TITLE_BIG)
        val body = NotificationText.combine(
            bigText = extras.readText(Notification.EXTRA_BIG_TEXT),
            text = extras.readText(Notification.EXTRA_TEXT),
            subText = extras.readText(Notification.EXTRA_SUB_TEXT),
            infoText = extras.readText(Notification.EXTRA_INFO_TEXT),
            textLines = extras.readLines(Notification.EXTRA_TEXT_LINES),
            latestMessage = extras.latestChatMessage(),
        )

        if (title.isNullOrBlank() && body.isBlank()) return

        val pkg = sbn.packageName
        val label = appLabel(pkg)
        val postedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        scope.launch {
            try {
                val result = repo.ingestNotification(pkg, label, title, body, postedAt)
                record(label, title, body, result)
                when (result) {
                    is SpendRepository.Ingest.Queued ->
                        PendingAlerts.show(applicationContext, result.preview)

                    is SpendRepository.Ingest.Saved ->
                        PendingAlerts.showAutoSaved(
                            applicationContext,
                            result.entryId,
                            result.amountMinor,
                            result.currency,
                        )

                    is SpendRepository.Ingest.Ignored -> Unit
                }
            } catch (e: Exception) {
                // A single malformed notification must never take the listener
                // down — Android does not restart it promptly when it crashes.
                Log.w(TAG, "Failed to ingest notification from $pkg", e)
            }
        }
    }

    /**
     * Writes the outcome to the capture log. Ignored notifications are only
     * logged when they contained an amount — otherwise every chat message and
     * weather update would fill the log with noise.
     */
    private fun record(
        label: String,
        title: String?,
        body: String,
        result: SpendRepository.Ingest,
    ) {
        val now = System.currentTimeMillis()
        val event = when (result) {
            is SpendRepository.Ingest.Queued -> CaptureLog.Event(
                at = now,
                appLabel = label,
                outcome = CaptureLog.Outcome.QUEUED,
                detail = "Waiting in the Inbox for you to confirm",
                amount = Money.format(result.preview.amountMinor, result.preview.currency),
            )

            is SpendRepository.Ingest.Saved -> CaptureLog.Event(
                at = now,
                appLabel = label,
                outcome = CaptureLog.Outcome.SAVED,
                detail = "Saved automatically",
                amount = Money.format(result.amountMinor, result.currency),
            )

            is SpendRepository.Ingest.Ignored -> {
                if (!result.worthLogging) return
                val base = Prefs.get(applicationContext).baseCurrency.value
                val mentionsMoney = result.amountMinor != null ||
                    AmountDetector.findAll("${title.orEmpty()} $body", base).isNotEmpty()
                if (!mentionsMoney) return
                CaptureLog.Event(
                    at = now,
                    appLabel = label,
                    outcome = CaptureLog.Outcome.IGNORED,
                    detail = result.reason,
                    amount = if (result.amountMinor != null && result.currency != null) {
                        Money.format(result.amountMinor, result.currency)
                    } else {
                        null
                    },
                )
            }
        }
        log.record(event)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            runCatching { repo.prunePending() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun Bundle.readText(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun Bundle.readLines(key: String): List<String> =
        getCharSequenceArray(key)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotEmpty() } }
            .orEmpty()

    /**
     * The newest message of a chat-style notification. Bank alerts that arrive
     * by SMS are posted this way by most messaging apps, and the collapsed text
     * is sometimes only "2 new messages", so this is where the words live.
     */
    @Suppress("DEPRECATION")
    private fun Bundle.latestChatMessage(): String? {
        val messages: Array<Parcelable>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
            } else {
                getParcelableArray(Notification.EXTRA_MESSAGES)
            }
        val last = messages?.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "SpendlyListener"

        /** Whether the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val expected = ComponentName(context, SpendNotificationListener::class.java)
            return flat.split(":").any { entry ->
                ComponentName.unflattenFromString(entry)?.let {
                    it.packageName == expected.packageName &&
                        it.className == expected.className
                } == true
            }
        }
    }
}
