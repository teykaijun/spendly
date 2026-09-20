package com.spendly

import android.app.Application
import com.spendly.notify.PendingAlerts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class SpendlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The listener can post before the UI has ever run, so the channel is
        // created here rather than on first screen.
        PendingAlerts.ensureChannel(this)

        // PDFBox ships its font and glyph-list resources as Android assets and
        // needs a Context to find them. Cheap, and doing it lazily at import
        // time would risk the first statement failing for no visible reason.
        PDFBoxResourceLoader.init(this)
    }
}
