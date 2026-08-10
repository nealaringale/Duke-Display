package com.duke.dash

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Calendar
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var messageClearRunnable: Runnable? = null
    private var navigationClearRunnable: Runnable? = null
    private var navigationGeneration = 0L

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
        messageClearRunnable?.let(mainHandler::removeCallbacks)
        navigationClearRunnable?.let(mainHandler::removeCallbacks)
        clearMediaController()
        mediaListener?.let { listener ->
            try { mediaManager?.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        }
        mediaListener = null
        mediaManager = null
        DashState.music = MusicState()
        DashState.message = MessageState()
        DashState.call = CallState()
        DashState.navigation = NavigationState()
        DashState.changed()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = process(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.google.android.apps.maps") {
            val generation = navigationGeneration
            navigationClearRunnable?.let(mainHandler::removeCallbacks)
            navigationClearRunnable = Runnable {
                if (generation == navigationGeneration) {
                    DashState.navigation = NavigationState()
                    DashState.changed()
                }
            }
            mainHandler.postDelayed(navigationClearRunnable!!, 3000L)
            return
        }

        if (sbn.notification.category == Notification.CATEGORY_CALL && DashState.call.active) {
            DashState.call = CallState()
            DashState.changed()
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
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
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
        val parts = listOf(title, text, big, sub)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (sbn.notification.category == Notification.CATEGORY_CALL) {
            val caller = parts.firstOrNull().orEmpty().ifBlank { "Incoming call" }
            DashState.call = CallState(true, caller)
            DashState.changed()
            return
        }

        if (pkg == "com.google.android.apps.maps") {
            val raw = parts.joinToString(" — ")
            if (raw.isNotBlank()) {
                navigationGeneration++
                navigationClearRunnable?.let(mainHandler::removeCallbacks)
                DashState.navigation = NavigationParser.parse(raw)
                DashState.changed()
            }
            return
        }

        val appName = MESSAGE_APPS[pkg] ?: return
        val body = parts.joinToString(" — ")
        if (body.isNotBlank()) {
            messageClearRunnable?.let(mainHandler::removeCallbacks)
            DashState.message = MessageState(appName, body, System.currentTimeMillis())
            DashState.changed()
            messageClearRunnable = Runnable {
                if (DashState.message.timestamp != 0L &&
                    System.currentTimeMillis() - DashState.message.timestamp >= 5000L
                ) {
                    DashState.message = MessageState()
                    DashState.changed()
                }
            }
            mainHandler.postDelayed(messageClearRunnable!!, 5000L)
        }
    }

    private fun setupMediaSessions() {
        try {
            mediaManager = getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(this, DashNotificationListener::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                attachBestController(controllers?.toList().orEmpty())
            }
            mediaListener = listener
            mediaManager?.let { manager ->
                manager.addOnActiveSessionsChangedListener(listener, component)
                attachBestController(manager.getActiveSessions(component)?.toList().orEmpty())
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun attachBestController(controllers: List<MediaController>) {
        val controller = controllers
            .sortedWith(
                compareByDescending<MediaController> {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                }.thenByDescending {
                    it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank()
                }
            )
            .firstOrNull()

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
            if (DashState.music != MusicState()) {
                DashState.music = MusicState()
                DashState.changed()
            }
            return
        }

        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ).orEmpty().trim()
        val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

        if (title.isBlank() && artist.isBlank()) {
            if (DashState.music != MusicState()) {
                DashState.music = MusicState()
                DashState.changed()
            }
            return
        }

        val next = MusicState(title, artist, playing)
        if (next != DashState.music) {
            DashState.music = next
            DashState.changed()
        }
    }
}

object NavigationParser {
    private val distanceRegex = Regex(
        """(?:(\\d+(?:\\.\\d+)?)\\s*(km|kilometer|kilometre|m|meter|metre))""",
        RegexOption.IGNORE_CASE
    )
    private val etaClockRegex = Regex(
        """(?:arrive|arrival|eta|reach).*?\\b(\\d{1,2}:\\d{2})\\b""",
        RegexOption.IGNORE_CASE
    )
    private val durationRegex = Regex(
        """\\b(\\d+)\\s*(min|mins|minute|minutes|h|hr|hour|hours)\\b""",
        RegexOption.IGNORE_CASE
    )
    private val roadRegex = Regex(
        """\\b(?:onto|on)\\s+(.+?)(?:\\s+(?:toward|for|in)\\b|$)""",
        RegexOption.IGNORE_CASE
    )

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

        val distance = distanceRegex.find(raw)?.let {
            val number = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val unit = it.groupValues[2].lowercase(Locale.US)
            if (unit.startsWith("km") || unit.startsWith("kilo")) (number * 1000.0).roundToIntSafe() else number.roundToIntSafe()
        }

        val etaFromClock = etaClockRegex.find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val eta = if (etaFromClock.isNotBlank()) {
            etaFromClock
        } else {
            durationRegex.find(raw)?.let { match ->
                val amount = match.groupValues[1].toLongOrNull() ?: return@let ""
                val unit = match.groupValues[2].lowercase(Locale.US)
                val minutes = if (unit.startsWith("h")) amount * 60 else amount
                val calendar = Calendar.getInstance().apply { add(Calendar.MINUTE, minutes.toInt()) }
                String.format(Locale.US, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            }.orEmpty()
        }

        val road = roadRegex.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val destinationDistance = if (lower.contains("destination") || lower.contains("remaining")) distance else null

        val instruction = when (direction) {
            "RIGHT" -> "TURN RIGHT"
            "LEFT" -> "TURN LEFT"
            "SLIGHT_RIGHT" -> "SLIGHT RIGHT"
            "SLIGHT_LEFT" -> "SLIGHT LEFT"
            "SHARP_RIGHT" -> "SHARP RIGHT"
            "SHARP_LEFT" -> "SHARP LEFT"
            "KEEP_RIGHT" -> "KEEP RIGHT"
            "KEEP_LEFT" -> "KEEP LEFT"
            "UTURN" -> "U-TURN"
            "ROUNDABOUT" -> "ROUNDABOUT"
            "ARRIVE" -> "ARRIVE"
            "NORTH" -> "HEAD NORTH"
            "SOUTH" -> "HEAD SOUTH"
            "EAST" -> "HEAD EAST"
            "WEST" -> "HEAD WEST"
            else -> "STRAIGHT"
        }

        return NavigationState(direction, distance, instruction, road, eta, destinationDistance, true, raw)
    }

    private fun Double.roundToIntSafe(): Int =
        coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
}
