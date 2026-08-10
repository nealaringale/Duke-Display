package com.duke.dash

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class DashNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        MainActivity.notificationText =
            "${sbn.packageName.substringAfterLast('.')}: $title ${if (text.isNotBlank()) "— $text" else ""}".trim()
        MainActivity.refresh?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit
}