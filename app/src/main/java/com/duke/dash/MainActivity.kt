package com.duke.dash

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        var notificationText = "Waiting for notification access…"
        var refresh: (() -> Unit)? = null
    }

    private lateinit var musicText: TextView
    private lateinit var notificationTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun label(text: String, size: Float = 18f): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setPadding(0, 12, 0, 12)
            }

        root.addView(label("DUKE DASH — V1 TEST", 24f))
        root.addView(label("This first build tests PHONE → APP data only.", 14f))

        root.addView(label("🎵 MUSIC", 20f))
        musicText = label("Checking active media…", 16f)
        root.addView(musicText)

        root.addView(label("🔔 NOTIFICATIONS", 20f))
        notificationTextView = label(notificationText, 16f)
        root.addView(notificationTextView)

        val settingsButton = Button(this).apply {
            text = "Enable Notification Access"
            setOnClickListener {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        root.addView(settingsButton)

        val refreshButton = Button(this).apply {
            text = "Refresh Music + Status"
            setOnClickListener { updateAll() }
        }
        root.addView(refreshButton)

        root.addView(label("🗺️ NAVIGATION", 20f))
        root.addView(label("V1: Not connected yet. Navigation is the next test.", 16f))

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        refresh = { runOnUiThread { updateAll() } }
        updateAll()
    }

    override fun onResume() {
        super.onResume()
        updateAll()
    }

    private fun updateAll() {
        notificationTextView.text = notificationText
        updateMusic()
    }

    private fun updateMusic() {
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(this, DashNotificationListener::class.java)
            val controllers = msm.getActiveSessions(component)

            val controller = controllers.firstOrNull()
            if (controller == null) {
                musicText.text = "No active media session found."
                return
            }

            val metadata = controller.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown title"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown artist"
            val state = controller.playbackState?.state ?: -1

            musicText.text = "$title\n$artist\nPlayback state: $state"
        } catch (e: SecurityException) {
            musicText.text =
                "Media access needs Notification Access enabled.\n\n${e.message ?: ""}"
        } catch (e: Exception) {
            musicText.text = "Music error: ${e.message}"
        }
    }
}