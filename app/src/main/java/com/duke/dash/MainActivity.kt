package com.duke.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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
    private lateinit var startButton: TextView

    private val orange = Color.rgb(255, 101, 0)
    private val bg = Color.rgb(5, 5, 5)
    private val card = Color.rgb(17, 17, 17)
    private val card2 = Color.rgb(22, 22, 22)
    private val text = Color.rgb(247, 247, 247)
    private val muted = Color.rgb(145, 145, 145)
    private val green = Color.rgb(92, 210, 116)
    private val red = Color.rgb(255, 92, 92)
    private val amber = Color.rgb(255, 176, 0)

    private val observer: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        requestRuntimePermissions()
        DashState.observe(observer)
        render()
        maybeStartDisplayService()
    }

    override fun onResume() {
        super.onResume()
        DashNotificationListener.instance?.refreshActive()
        maybeStartDisplayService()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 390) maybeStartDisplayService()
    }

    override fun onDestroy() {
        DashState.remove(observer)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun rounded(color: Int, radius: Int = 18, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(card, 18)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(com.duke.dash.R.drawable.ic_duke_dash)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = rounded(orange, 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { setMargins(0, 0, dp(14), 0) }
        }
        header.addView(logo)

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("DUKE DASH", 25f, text, true))
        titleBox.addView(label("RIDE  •  CONNECT  •  KNOW", 10f, muted, true).apply { setPadding(0, dp(5), 0, 0) })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val version = label("V1", 10f, muted, true).apply {
            gravity = Gravity.CENTER
            background = rounded(card2, 10)
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        header.addView(version)
        root.addView(header)

        status = label("", 12f, muted, true).apply { setPadding(dp(2), dp(16), 0, dp(5)) }
        root.addView(status)

        val navCard = cardView()
        navCard.addView(label("NAVIGATION", 11f, orange, true))
        nav = label("—", 31f, text, true).apply { setPadding(0, dp(10), 0, dp(5)) }
        navCard.addView(nav)
        navMeta = label("Waiting for Google Maps…", 14f, muted)
        navCard.addView(navMeta)
        root.addView(navCard)

        val musicCard = cardView()
        musicCard.addView(label("NOW PLAYING", 11f, orange, true))
        music = label("No active music", 19f, text, true).apply { setPadding(0, dp(10), 0, dp(5)) }
        musicCard.addView(music)
        musicMeta = label("Start music on your phone", 13f, muted)
        musicCard.addView(musicMeta)
        root.addView(musicCard)

        val messageCard = cardView()
        messageCard.addView(label("MESSAGING", 11f, orange, true))
        message = label("No new messages", 17f, text, true).apply { setPadding(0, dp(10), 0, dp(5)) }
        messageCard.addView(message)
        messageMeta = label("Supported messaging apps only", 13f, muted)
        messageCard.addView(messageMeta)
        root.addView(messageCard)

        val bleCard = cardView()
        bleCard.addView(label("DISPLAY LINK", 11f, orange, true))
        ble = label("○  DISPLAY OFF", 15f, text, true).apply { setPadding(0, dp(10), 0, 0) }
        bleCard.addView(ble)
        root.addView(bleCard)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(action("NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        startButton = action("START DISPLAY") { startBleService(true) }
        actions.addView(startButton)
        root.addView(actions)

        root.addView(label(
            "Duke Dash keeps the bike display focused: navigation, media and supported messaging only. System notifications stay off the road-facing screen.",
            10.5f,
            muted
        ).apply { setPadding(dp(2), dp(16), dp(2), 0) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        setContentView(scroll)
    }

    private fun action(title: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 11f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(this@MainActivity.text)
        background = rounded(card2, 12, Color.rgb(45, 45, 45))
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener {
            it.animate().alpha(0.55f).setDuration(60).withEndAction {
                it.animate().alpha(1f).setDuration(120).start()
                onClick()
            }.start()
        }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(7), 0) }
    }

    private fun render() {
        val listenerReady = DashNotificationListener.instance != null
        status.text = if (listenerReady) "●  PHONE DATA READY" else "○  ENABLE NOTIFICATION ACCESS TO START"
        status.setTextColor(if (listenerReady) green else muted)

        val n = DashState.navigation
        nav.text = when (n.direction) {
            "RIGHT" -> "↗  RIGHT"; "LEFT" -> "↖  LEFT"
            "SLIGHT_RIGHT" -> "↗  SLIGHT RIGHT"; "SLIGHT_LEFT" -> "↖  SLIGHT LEFT"
            "SHARP_RIGHT" -> "↗  SHARP RIGHT"; "SHARP_LEFT" -> "↖  SHARP LEFT"
            "UTURN" -> "↶  U-TURN"; "KEEP_RIGHT" -> "↗  KEEP RIGHT"; "KEEP_LEFT" -> "↖  KEEP LEFT"
            "ARRIVE" -> "●  ARRIVE"; "ROUNDABOUT" -> "↻  ROUNDABOUT"
            "NORTH" -> "↑  HEAD NORTH"; "SOUTH" -> "↓  HEAD SOUTH"; "EAST" -> "→  HEAD EAST"; "WEST" -> "←  HEAD WEST"
            else -> "↑  STRAIGHT"
        }
        navMeta.text = buildString {
            if (n.distanceMeters != null && n.distanceMeters >= 0) append("${n.distanceMeters} m  •  ")
            append(if (n.instruction.isNotBlank()) n.instruction else "Waiting for Google Maps…")
            if (n.road.isNotBlank()) append("\n${n.road}")
            if (n.eta.isNotBlank()) append("\nETA ${n.eta}")
            if (n.destinationDistanceMeters != null && n.destinationDistanceMeters >= 0) append("  •  DEST ${n.destinationDistanceMeters} m")
        }

        val m = DashState.music
        music.text = if (m.title.isBlank()) "No active music" else m.title
        musicMeta.text = if (m.title.isBlank()) "Start music on your phone" else "${m.artist.ifBlank { "Unknown artist" }}  •  ${if (m.playing) "PLAYING" else "PAUSED"}"

        val msg = DashState.message
        message.text = if (msg.app.isBlank()) "No new messages" else msg.text.ifBlank { "New message" }
        messageMeta.text = if (msg.app.isBlank()) "Supported messaging apps only" else msg.app.uppercase()

        when {
            DashState.phoneConnected -> {
                ble.text = "●  DISPLAY CONNECTED"
                ble.setTextColor(green)
            }
            DashState.displayRunning -> {
                ble.text = "●  DISPLAY LINK RUNNING • WAITING FOR DISPLAY"
                ble.setTextColor(amber)
            }
            DashState.displayStarting -> {
                ble.text = "◌  STARTING DISPLAY LINK…"
                ble.setTextColor(orange)
            }
            DashState.displayError.isNotBlank() -> {
                ble.text = "×  DISPLAY FAILED • RETRY"
                ble.setTextColor(red)
            }
            else -> {
                ble.text = "○  DISPLAY OFF"
                ble.setTextColor(text)
            }
        }

        when {
            DashState.phoneConnected -> {
                startButton.text = "DISPLAY CONNECTED"
                startButton.isEnabled = false
            }
            DashState.displayRunning -> {
                startButton.text = "DISPLAY ADVERTISING"
                startButton.isEnabled = false
            }
            DashState.displayStarting -> {
                startButton.text = "STARTING…"
                startButton.isEnabled = false
            }
            DashState.displayError.isNotBlank() -> {
                startButton.text = "RETRY DISPLAY"
                startButton.isEnabled = true
            }
            else -> {
                startButton.text = "START DISPLAY"
                startButton.isEnabled = true
            }
        }
        startButton.alpha = if (startButton.isEnabled) 1f else 0.7f
    }

    private fun startBleService(manual: Boolean = false) {
        if (manual) {
            DashState.displayError = ""
            DashState.displayStarting = true
            DashState.displayRunning = false
            DashState.changed()
        }
        try {
            val intent = Intent(this, DashBleService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            DashState.displayStarting = false
            DashState.displayRunning = false
            DashState.displayError = e.javaClass.simpleName
            DashState.changed()
        }
    }

    private fun maybeStartDisplayService() {
        if (Build.VERSION.SDK_INT >= 31) {
            val connectGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val advertiseGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!connectGranted || !advertiseGranted) return
        }
        if (!DashState.displayRunning && !DashState.displayStarting && DashState.displayError.isBlank()) {
            startBleService(false)
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 31) return
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 390)
    }
}
