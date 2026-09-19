package com.spendly.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spendly.data.SpendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Confirm / Not a spend / Undo buttons on Spendly's own
 * notifications, so a detected spend can be dealt with without opening the app.
 */
class PendingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val repo = SpendRepository.get(appContext)

        // The broadcast must not outlive onReceive, so take a pending result and
        // release it once the database work is done.
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_CONFIRM -> {
                        repo.confirmPending(id)
                        PendingAlerts.cancelPending(appContext, id)
                    }

                    ACTION_DISMISS -> {
                        repo.dismissPending(id)
                        PendingAlerts.cancelPending(appContext, id)
                    }

                    ACTION_UNDO_SAVE -> {
                        repo.deleteEntry(id)
                        PendingAlerts.cancelSaved(appContext, id)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Action $action failed for id $id", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SpendlyAction"
        const val EXTRA_ID = "com.spendly.extra.ID"
        const val ACTION_CONFIRM = "com.spendly.action.CONFIRM"
        const val ACTION_DISMISS = "com.spendly.action.DISMISS"
        const val ACTION_UNDO_SAVE = "com.spendly.action.UNDO_SAVE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
