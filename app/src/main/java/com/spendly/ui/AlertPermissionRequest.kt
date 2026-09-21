package com.spendly.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.spendly.data.Prefs
import com.spendly.notify.PendingAlerts
import com.spendly.notify.SpendNotificationListener

/**
 * Asks for permission to post the "tap to confirm" alerts, at the one moment it
 * makes sense.
 *
 * This is the fix for notification spends appearing not to save. The alert
 * setting defaults to on, and the permission used to be requested only when
 * that switch was flipped — which, being on already, it never was. So on
 * Android 13+ detections queued silently in the Inbox with no prompt at all.
 *
 * Asked on resume rather than on first launch: the permission only matters once
 * notification access is granted, and that happens in system settings, so the
 * natural moment is when the user comes back from there. Asked at most once per
 * session so a "no" is respected rather than re-prompted on every return.
 */
@Composable
fun AlertPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var askedThisSession by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The Inbox shows a card when this is still denied. */ }

    LifecycleResumeEffect(Unit) {
        val wantsAlerts = Prefs.get(context).notifyOnDetect.value
        val capturing = SpendNotificationListener.isEnabled(context)
        val allowed = PendingAlerts.hasPostPermission(context)
        if (!askedThisSession && wantsAlerts && capturing && !allowed) {
            askedThisSession = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onPauseOrDispose { }
    }
}
