# Room generates classes reflectively referenced by the runtime.
-keep class com.spendly.data.** { *; }

# NotificationListenerService is instantiated by the system by name.
-keep class com.spendly.notify.SpendNotificationListener { *; }
-keep class com.spendly.notify.PendingActionReceiver { *; }
