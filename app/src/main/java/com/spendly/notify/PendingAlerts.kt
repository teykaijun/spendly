package com.spendly.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spendly.MainActivity
import com.spendly.R
import com.spendly.data.Money
import com.spendly.data.PendingEntry
import com.spendly.data.Prefs

/**
 * The "you don't even have to open the app" path: when a spend is detected we
 * post our own notification with Confirm and Dismiss buttons, so recording it
 * is one tap from the shade.
 */
object PendingAlerts {

    private const val TAG = "SpendlyAlerts"

    const val CHANNEL_ID = "pending_spends"

    /** Notification ids are derived from row ids, offset so the two kinds never collide. */
    private const val PENDING_ID_BASE = 100_000
    private const val SAVED_ID_BASE = 200_000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_pending_name),
            // Default, not High: these should be glanceable, not interruptive.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_pending_desc)
            setShowBadge(true)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, pending: PendingEntry) {
        if (!Prefs.get(context).notifyOnDetect.value) return
        if (!canPost(context)) return

        val amount = Money.format(pending.amountMinor, pending.currency)
        val where = pending.merchant ?: pending.sourceAppLabel

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$amount · $where")
            .setContentText("Tap Confirm to record this spend")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "From ${pending.sourceAppLabel}: ${pending.rawTitle} ${pending.rawText}".trim(),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openInboxIntent(context, pending.id))
            .addAction(
                0,
                "Confirm",
                actionIntent(context, PendingActionReceiver.ACTION_CONFIRM, pending.id),
            )
            .addAction(
                0,
                "Not a spend",
                actionIntent(context, PendingActionReceiver.ACTION_DISMISS, pending.id),
            )
            .build()

        postSafely(context, PENDING_ID_BASE + pending.id.toInt(), notification)
    }

    fun showAutoSaved(context: Context, entryId: Long, amountMinor: Long, currency: String) {
        if (!Prefs.get(context).notifyOnDetect.value) return
        if (!canPost(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recorded ${Money.format(amountMinor, currency)}")
            .setContentText("Saved automatically")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openInboxIntent(context, entryId))
            .addAction(
                0,
                "Undo",
                actionIntent(context, PendingActionReceiver.ACTION_UNDO_SAVE, entryId),
            )
            .build()

        postSafely(context, SAVED_ID_BASE + entryId.toInt(), notification)
    }

    fun cancelPending(context: Context, pendingId: Long) {
        NotificationManagerCompat.from(context).cancel(PENDING_ID_BASE + pendingId.toInt())
    }

    fun cancelSaved(context: Context, entryId: Long) {
        NotificationManagerCompat.from(context).cancel(SAVED_ID_BASE + entryId.toInt())
    }

    /**
     * Whether Android will actually show our alerts. Public because the UI has
     * to ask for it: the permission is runtime-granted on Android 13+, and until
     * this was checked on launch, a default-on alert setting meant the request
     * was simply never made — so detections queued silently with no prompt.
     */
    fun hasPostPermission(context: Context): Boolean = canPost(context)

    /**
     * POST_NOTIFICATIONS only exists from API 33; below that, posting is always
     * allowed and the constant must not be referenced at runtime.
     */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The permission can be revoked between [canPost] and the post itself, which
     * throws. A missed alert is never worth crashing a background listener over.
     */
    private fun postSafely(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked before posting", e)
        }
    }

    private fun openInboxIntent(context: Context, requestId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_INBOX, true)
        }
        return PendingIntent.getActivity(
            context,
            requestId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, id: Long): PendingIntent {
        val intent = Intent(context, PendingActionReceiver::class.java).apply {
            this.action = action
            putExtra(PendingActionReceiver.EXTRA_ID, id)
        }
        // Request codes must differ per (action, id) or the extras get reused.
        val requestCode = (action.hashCode() * 31 + id.toInt())
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
