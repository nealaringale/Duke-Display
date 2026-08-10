package com.duke.dash

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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

    private var mediaManager: MediaSessionManager? = null
    private var mediaListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        setupMediaSessions()
        refreshActive()
        refreshMusic()
        refreshBattery()
    }

    override fun onListenerDisconnected() {
        clearMediaController()
        mediaListener?.let { listener ->
            try { mediaManager?.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        }
        mediaListener = null
        mediaManager = null
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clearMediaController()
        mediaListener?.let { listener ->
            try { mediaManager?.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        }
        mediaListener = null
        mediaManager = null
        DashState.music = MusicState()
        DashState.call = CallState()
        DashState.changed()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = process(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.google.android.apps.maps") {
            // Maps may remove/repost its navigation notification. Leave the last
            // instruction briefly available rather than flashing the display.
            return
        }
        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            if (DashState.call.active) {
                DashState.call = CallState()
                DashState.changed()
            }
        }
    }

    fun refreshActive() {
        try { activeNotifications?.forEach(::process) } catch (_: Exception) {}
        refreshMusic()
        refreshBattery()
    }

    fun refreshBattery() {
        try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (pct != DashState.phoneBattery || charging != DashState.phoneCharging) {
                DashState.phoneBattery = pct
                DashState.phoneCharging = charging
                DashState.changed()
            }
        } catch (_: Exception) {}
    }

    private fun process(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val parts = listOf(title, text, big, sub).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            val caller = parts.firstOrNull().orEmpty().ifBlank { "Incoming call" }
            DashState.call = CallState(true, caller)
            DashState.changed()
            return
        }

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

    private fun setupMediaSessions() {
        try {
            mediaManager = getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(this, DashNotificationListener::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                attachBestController(controllers?.toList() ?: emptyList())
            }
            mediaListener = listener
            mediaManager?.let { manager ->
                manager.addOnActiveSessionsChangedListener(listener, component)
                val controllers: List<MediaController> = manager.getActiveSessions(component)?.toList() ?: emptyList()
                attachBestController(controllers)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun attachBestController(controllers: List<MediaController>) {
        val controller = controllers.sortedWith(
            compareByDescending<MediaController> {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }.thenByDescending {
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank()
            }
        ).firstOrNull()

        if (controller?.sessionToken == activeController?.sessionToken) {
            refreshMusic()
            return
        }

        clearMediaController()
        activeController = controller
        controller?.let { c ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMusic()
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = refreshMusic()
                override fun onSessionDestroyed() {
                    if (activeController?.sessionToken == c.sessionToken) {
                        clearMediaController()
                        refreshMusic()
                    }
                }
            }
            controllerCallback = callback
            try { c.registerCallback(callback) } catch (_: Exception) {}
        }
        refreshMusic()
    }

    private fun clearMediaController() {
        val c = activeController
        val callback = controllerCallback
        if (c != null && callback != null) {
            try { c.unregisterCallback(callback) } catch (_: Exception) {}
        }
        activeController = null
        controllerCallback = null
    }

    private fun refreshMusic() {
        val controller = activeController
        if (controller == null) {
            if (DashState.music.title.isNotBlank() || DashState.music.artist.isNotBlank()) {
                DashState.music = MusicState()
                DashState.changed()
            }
            return
        }
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        if (title.isBlank() && artist.isBlank()) return
        val next = MusicState(title, artist.orEmpty(), playing)
        if (next != DashState.music) {
            DashState.music = next
            DashState.changed()
        }
    }
}

object NavigationParser {
    fun parse(raw: String): NavigationState {
        val lower = raw.lowercase(Locale.US)
        val direction = when {
            lower.contains("u-turn") || lower.contains("u turn") -> "UTURN"
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

        val eta = Regex("""(?:arrive|arrival|eta|reach).*?\b(\d{1,2}:\d{2})\b""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()

        val road = Regex("""\b(?:onto|on)\s+(.+?)(?:\s+(?:toward|for|in)\b|$)""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val destinationDistance = if (lower.contains("destination") || lower.contains("arrive")) distance else null

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
        return NavigationState(direction, distance, instruction, road, eta, destinationDistance, true, raw)
    }
}
