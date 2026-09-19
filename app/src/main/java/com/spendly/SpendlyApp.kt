package com.spendly

import android.app.Application
import com.spendly.notify.PendingAlerts

class SpendlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The listener can post before the UI has ever run, so the channel is
        // created here rather than on first screen.
        PendingAlerts.ensureChannel(this)
    }
}
