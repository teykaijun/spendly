package com.spendly.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Receives the outcome of a PackageInstaller session.
 *
 * The important case is STATUS_PENDING_USER_ACTION: the system is telling us it
 * needs the user to confirm, and hands back an Intent to show. Everything else
 * is terminal and is surfaced in the UI.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    events.tryEmit(InstallEvent.Failed("The installer did not return a confirmation screen"))
                    return
                }
                runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    events.tryEmit(InstallEvent.AwaitingConfirmation)
                }.onFailure {
                    Log.w(TAG, "Could not show the install confirmation", it)
                    events.tryEmit(InstallEvent.Failed("Could not open the installer"))
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                events.tryEmit(InstallEvent.Succeeded)

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                events.tryEmit(InstallEvent.Cancelled)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                events.tryEmit(InstallEvent.Failed(describe(status, message)))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The install was blocked by the device"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Conflicts with the installed app — usually a different signing key"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "That build is not compatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The package is malformed"
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "Not enough storage to install"
        else ->
            message?.takeIf { it.isNotBlank() } ?: "Install failed (code $status)"
    }

    companion object {
        private const val TAG = "SpendlyInstall"
        const val ACTION_INSTALL_STATUS = "com.spendly.action.INSTALL_STATUS"

        /**
         * Install results arrive on a broadcast receiver, which has no reference
         * to the ViewModel that started the install — so they are republished
         * here. extraBufferCapacity lets tryEmit succeed from onReceive, which
         * cannot suspend.
         */
        private val events = MutableSharedFlow<InstallEvent>(
            replay = 0,
            extraBufferCapacity = 8,
        )

        val installEvents: SharedFlow<InstallEvent> = events.asSharedFlow()
    }
}

sealed interface InstallEvent {
    data object AwaitingConfirmation : InstallEvent
    data object Succeeded : InstallEvent
    data object Cancelled : InstallEvent
    data class Failed(val message: String) : InstallEvent
}
