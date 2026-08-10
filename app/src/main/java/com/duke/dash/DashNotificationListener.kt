package com.duke.dash

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class DashNotificationListener : NotificationListenerService() {

    companion object {
        var instance: DashNotificationListener? = null
        val MESSAGE_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "com.facebook.orca" to "Messenger",
            "org.thoughtcrime.securesms" to "Signal",
            "com.discord" to "Discord",
            "com.google.android.apps.messaging" to "Google Messages",
            "com.snapchat.android" to "Snapchat"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActive()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = process(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    fun refreshActive() {
        try { activeNotifications?.forEach(::process) } catch (_: Exception) {}
    }

    private fun process(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val parts = listOf(title, text, big, sub).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        if (pkg == "com.google.android.apps.maps") {
            val raw = parts.joinToString(" — ")
            if (raw.isNotBlank()) {
                DashState.navigation = NavigationParser.parse(raw)
                DashState.changed()
            }
            return
        }

        val appName = MESSAGE_APPS[pkg] ?: return
        val body = parts.joinToString(" — ")
        if (body.isNotBlank()) {
            DashState.message = MessageState(appName, body, System.currentTimeMillis())
            DashState.changed()
        }
    }
}

object NavigationParser {
    fun parse(raw: String): NavigationState {
        val lower = raw.lowercase(Locale.US)
        val direction = when {
            lower.contains("u-turn") -> "UTURN"
            lower.contains("sharp left") -> "SHARP_LEFT"
            lower.contains("sharp right") -> "SHARP_RIGHT"
            lower.contains("slight left") -> "SLIGHT_LEFT"
            lower.contains("slight right") -> "SLIGHT_RIGHT"
            lower.contains("turn left") || lower.contains("left onto") -> "LEFT"
            lower.contains("turn right") || lower.contains("right onto") -> "RIGHT"
            lower.contains("keep left") -> "KEEP_LEFT"
            lower.contains("keep right") -> "KEEP_RIGHT"
            lower.contains("roundabout") -> "ROUNDABOUT"
            lower.contains("head north") -> "NORTH"
            lower.contains("head south") -> "SOUTH"
            lower.contains("head east") -> "EAST"
            lower.contains("head west") -> "WEST"
            lower.contains("arrive") || lower.contains("destination") -> "ARRIVE"
            else -> "STRAIGHT"
        }
        val distance = Regex("""(?:(\d+(?:\.\d+)?)\s*(km|kilometer|kilometre|m|meter|metre))""", RegexOption.IGNORE_CASE)
            .find(raw)?.let {
                val n = it.groupValues[1].toDoubleOrNull() ?: return@let null
                val unit = it.groupValues[2].lowercase(Locale.US)
                if (unit.startsWith("km") || unit.startsWith("kilo")) (n * 1000).toInt() else n.toInt()
            }
        val instruction = when (direction) {
            "RIGHT" -> "TURN RIGHT"; "LEFT" -> "TURN LEFT"
            "SLIGHT_RIGHT" -> "SLIGHT RIGHT"; "SLIGHT_LEFT" -> "SLIGHT LEFT"
            "SHARP_RIGHT" -> "SHARP RIGHT"; "SHARP_LEFT" -> "SHARP LEFT"
            "KEEP_RIGHT" -> "KEEP RIGHT"; "KEEP_LEFT" -> "KEEP LEFT"
            "UTURN" -> "U-TURN"; "ROUNDABOUT" -> "ROUNDABOUT"; "ARRIVE" -> "ARRIVE"
            "NORTH" -> "HEAD NORTH"; "SOUTH" -> "HEAD SOUTH"
            "EAST" -> "HEAD EAST"; "WEST" -> "HEAD WEST"
            else -> "STRAIGHT"
        }
        return NavigationState(direction, distance, instruction, "", raw)
    }
}
