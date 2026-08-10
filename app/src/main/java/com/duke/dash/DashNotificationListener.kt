package com.duke.dash

import android.app.Notification
import android.content.ComponentName
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
        if (DashState.music.title.isNotBlank()) {
            DashState.music = MusicState()
            DashState.changed()
        }
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = process(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.google.android.apps.maps") return
        // Media playback is handled through MediaSessionManager, not generic notifications.
    }

    fun refreshActive() {
        try { activeNotifications?.forEach(::process) } catch (_: Exception) {}
        refreshMusic()
    }

    private fun process(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val parts = listOf(title, text, big, sub).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        // Only Google Maps is allowed to feed navigation data.
        if (pkg == "com.google.android.apps.maps") {
            val raw = parts.joinToString(" — ")
            if (raw.isNotBlank()) {
                DashState.navigation = NavigationParser.parse(raw)
                DashState.changed()
            }
            return
        }

        // Only explicitly supported messaging apps are allowed on the road display.
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
            mediaListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                attachBestController(controllers)
            }
            mediaManager?.addOnActiveSessionsChangedListener(mediaListener, component)
            attachBestController(mediaManager?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) {
            // Notification listener access is required for active media sessions.
        } catch (_: Exception) {
            // Keep the rest of Duke Dash working if a device/media app does not expose sessions.
        }
    }

    private fun attachBestController(controllers: List<MediaController>) {
        val controller = controllers
            .sortedWith(compareByDescending<MediaController> { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                .thenByDescending { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank() })
            .firstOrNull()

        if (controller?.sessionToken == activeController?.sessionToken) {
            refreshMusic()
            return
        }

        clearMediaController()
        activeController = controller
        controller?.let { c ->
            controllerCallback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMusic()
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = refreshMusic()
                override fun onSessionDestroyed() {
                    if (activeController?.sessionToken == c.sessionToken) {
                        clearMediaController()
                        refreshMusic()
                    }
                }
            }
            try { c.registerCallback(controllerCallback) } catch (_: Exception) {}
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

        // Ignore sessions with no actual media title; this prevents random system audio from appearing.
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
