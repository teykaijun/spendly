package com.spendly.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spendly.data.SpendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ongoing notifications are progress bars, media players and foreground
        // service badges. They re-post constantly and are never a transaction.
        if (sbn.isOngoing) return
        if (sbn.packageName == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.readText(Notification.EXTRA_TITLE)
        val body = listOfNotNull(
            extras.readText(Notification.EXTRA_BIG_TEXT),
            extras.readText(Notification.EXTRA_TEXT),
            extras.readText(Notification.EXTRA_SUB_TEXT),
            extras.readText(Notification.EXTRA_INFO_TEXT),
        ).distinct().joinToString(" ")

        if (title.isNullOrBlank() && body.isBlank()) return

        val pkg = sbn.packageName
        val label = appLabel(pkg)
        val postedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        scope.launch {
            try {
                when (val result = repo.ingestNotification(pkg, label, title, body, postedAt)) {
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch {
            runCatching { repo.prunePending() }
        }
    }

    private fun android.os.Bundle.readText(key: String): String? =
        getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

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
