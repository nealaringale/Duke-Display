package com.duke.dash

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var nav: TextView
    private lateinit var music: TextView
    private lateinit var message: TextView
    private lateinit var ble: TextView
    private val observer: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestRuntimePermissions()
        DashState.observe(observer)
        render()
    }

    override fun onResume() {
        super.onResume()
        DashNotificationListener.instance?.refreshActive()
        render()
    }

    override fun onDestroy() {
        DashState.remove(observer)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF050505.toInt())
        }
        fun title(t: String, size: Float) = TextView(this).apply { text=t; textSize=size; setTextColor(0xFFF5F5F5.toInt()); setPadding(0,8,0,8) }
        fun section(t: String) = TextView(this).apply { text=t; textSize=18f; setTextColor(0xFFFF6500.toInt()); setPadding(0,20,0,6) }
        fun body() = TextView(this).apply { textSize=15f; setTextColor(0xFFD0D0D0.toInt()); setPadding(0,6,0,10) }

        root.addView(title("DUKE DASH", 28f)); root.addView(title("FULL PROTOTYPE • V1", 13f))
        status = body(); root.addView(status)
        root.addView(section("🗺️ NAVIGATION")); nav=body(); root.addView(nav)
        root.addView(section("🎵 MUSIC")); music=body(); root.addView(music)
        root.addView(section("💬 MESSAGING")); message=body(); root.addView(message)
        root.addView(section("📡 DISPLAY LINK")); ble=body(); root.addView(ble)

        root.addView(Button(this).apply { text="Enable Notification Access"; setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } })
        root.addView(Button(this).apply { text="Start Duke Dash Display Link"; setOnClickListener { startBleService() } })
        root.addView(Button(this).apply { text="Refresh"; setOnClickListener { DashNotificationListener.instance?.refreshActive(); render() } })
        root.addView(body().apply { text="Messaging filter: WhatsApp, Instagram, Telegram, Messenger, Signal, Discord, Google Messages and Snapchat. System notifications are ignored. Google Maps is treated separately as navigation."; setTextColor(0xFF888888.toInt()) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun render() {
        status.text = if (DashNotificationListener.instance != null) "● Notification listener connected" else "○ Notification access not connected"
        val n=DashState.navigation
        nav.text = buildString { append("${n.direction}\n${n.instruction}\n"); n.distanceMeters?.let { append("${it} m\n") }; append(if(n.raw.isNotBlank()) "RAW: ${n.raw}" else "No Google Maps navigation notification detected.") }
        val m=DashState.music
        music.text = if(m.title.isBlank()) "No active media session." else "${m.title}\n${m.artist}\n${if(m.playing) "PLAYING" else "PAUSED"}"
        val msg=DashState.message
        message.text = if(msg.app.isBlank()) "No messaging notification detected." else "${msg.app}\n${msg.text}"
        ble.text = if(DashState.phoneConnected) "● BLE client connected" else "○ Advertising as DUKE DASH • waiting for display"
    }

    private fun startBleService() {
        val intent=Intent(this, DashBleService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent)
    }

    private fun requestRuntimePermissions() {
        if(Build.VERSION.SDK_INT>=31) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS), 390)
        else if(Build.VERSION.SDK_INT>=33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 391)
    }
}
