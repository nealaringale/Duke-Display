package com.duke.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var nav: TextView
    private lateinit var navMeta: TextView
    private lateinit var music: TextView
    private lateinit var musicMeta: TextView
    private lateinit var message: TextView
    private lateinit var messageMeta: TextView
    private lateinit var ble: TextView
    private val orange = Color.rgb(255, 101, 0)
    private val bg = Color.rgb(7, 7, 7)
    private val card = Color.rgb(19, 19, 19)
    private val text = Color.rgb(245, 245, 245)
    private val muted = Color.rgb(150, 150, 150)
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

    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        setBackgroundColor(card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 24)
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val logo = TextView(this).apply {
            text = "DD"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(orange)
            layoutParams = LinearLayout.LayoutParams(58, 58).apply { setMargins(0, 0, 16, 0) }
        }
        header.addView(logo)
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("DUKE DASH", 25f, text, true))
        titleBox.addView(label("RIDE • CONNECT • KNOW", 11f, muted, true))
        header.addView(titleBox)
        root.addView(header)

        status = label("", 13f, muted)
        status.setPadding(0, 16, 0, 4)
        root.addView(status)

        val navCard = cardView()
        navCard.addView(label("NAVIGATION", 12f, orange, true))
        nav = label("", 30f, text, true)
        nav.setPadding(0, 12, 0, 2)
        navCard.addView(nav)
        navMeta = label("", 14f, muted)
        navCard.addView(navMeta)
        root.addView(navCard)

        val musicCard = cardView()
        musicCard.addView(label("NOW PLAYING", 12f, orange, true))
        music = label("", 20f, text, true)
        music.setPadding(0, 12, 0, 2)
        musicCard.addView(music)
        musicMeta = label("", 14f, muted)
        musicCard.addView(musicMeta)
        root.addView(musicCard)

        val messageCard = cardView()
        messageCard.addView(label("MESSAGING", 12f, orange, true))
        message = label("", 18f, text, true)
        message.setPadding(0, 12, 0, 2)
        messageCard.addView(message)
        messageMeta = label("", 14f, muted)
        messageCard.addView(messageMeta)
        root.addView(messageCard)

        val bleCard = cardView()
        bleCard.addView(label("DISPLAY LINK", 12f, orange, true))
        ble = label("", 16f, text, true)
        ble.setPadding(0, 12, 0, 2)
        bleCard.addView(ble)
        root.addView(bleCard)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        actions.addView(action("NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        actions.addView(action("START DISPLAY") { startBleService() })
        root.addView(actions)

        root.addView(label("Duke Dash only surfaces navigation, media and supported messaging notifications. System notifications stay off the bike display.", 11f, muted).apply {
            setPadding(0, 18, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun action(title: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 35, 35))
        setPadding(18, 18, 18, 18)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 8, 0) }
    }

    private fun render() {
        status.text = if (DashNotificationListener.instance != null) "●  PHONE DATA READY" else "○  ENABLE NOTIFICATION ACCESS TO START"
        val n = DashState.navigation
        nav.text = if (n.direction.isNotBlank()) n.direction else "—"
        navMeta.text = buildString {
            if (n.distanceMeters != null) append("${n.distanceMeters} m  •  ")
            append(if (n.instruction.isNotBlank()) n.instruction else "Waiting for Google Maps navigation")
            if (n.road.isNotBlank()) append("\n${n.road}")
        }

        val m = DashState.music
        music.text = if (m.title.isBlank()) "No active music" else m.title
        musicMeta.text = if (m.title.isBlank()) "Start music on your phone" else "${m.artist.ifBlank { "Unknown artist" }}  •  ${if (m.playing) "PLAYING" else "PAUSED"}"

        val msg = DashState.message
        message.text = if (msg.app.isBlank()) "No new messages" else msg.text.ifBlank { "New message" }
        messageMeta.text = if (msg.app.isBlank()) "Supported messaging apps only" else msg.app.uppercase()

        ble.text = if (DashState.phoneConnected) "● DISPLAY CONNECTED" else "○ READY • WAITING FOR ESP32"
    }

    private fun startBleService() {
        val intent = Intent(this, DashBleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS), 390)
        } else if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 391)
        }
    }
}
