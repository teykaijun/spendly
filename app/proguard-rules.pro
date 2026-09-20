# Room generates classes reflectively referenced by the runtime.
-keep class com.spendly.data.** { *; }

# NotificationListenerService is instantiated by the system by name.
-keep class com.spendly.notify.SpendNotificationListener { *; }
-keep class com.spendly.notify.PendingActionReceiver { *; }

# PDFBox can decode JPEG2000 images through an optional JP2Decoder dependency.
# Spendly only extracts text from statements, never images, so that library is
# deliberately not on the classpath and the reference is unreachable.
-dontwarn com.gemalto.jp2.**

# PDFBox loads filters and font handlers reflectively by class name.
-keep class com.tom_roush.pdfbox.filter.** { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }

# Our own statement parsing is pure Kotlin but is reached from the UI only;
# keep the public surface so an over-eager pass cannot strip it.
-keep class com.spendly.pdf.** { *; }
