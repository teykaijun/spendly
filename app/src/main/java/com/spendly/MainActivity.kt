package com.spendly

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spendly.ui.SpendlyRoot
import com.spendly.ui.Tab
import com.spendly.ui.theme.SpendlyTheme

class MainActivity : ComponentActivity() {

    /**
     * Set when the activity is opened from one of our own "detected spend"
     * notifications. Held on the activity rather than inside the composition so
     * that [onNewIntent] can update it — the activity is singleTask, so a tap
     * while Spendly is already open never goes through [onCreate].
     */
    private var pendingTab by mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingTab = tabFor(intent)

        setContent {
            SpendlyTheme {
                SpendlyRoot(
                    requestedTab = pendingTab,
                    onRequestedTabHandled = { pendingTab = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTab = tabFor(intent)
    }

    private fun tabFor(intent: Intent?): Tab? =
        if (intent?.getBooleanExtra(EXTRA_OPEN_INBOX, false) == true) Tab.Inbox else null

    companion object {
        const val EXTRA_OPEN_INBOX = "com.spendly.extra.OPEN_INBOX"
    }
}
