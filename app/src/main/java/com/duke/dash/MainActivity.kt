package com.duke.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
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

    private val orange = Color.rgb(255, 101, 0)
    private val bg = Color.rgb(5, 5, 5)
    private val card = Color.rgb(17, 17, 17)
    private val card2 = Color.rgb(22, 22, 22)
    private val text = Color.rgb(247, 247, 247)
    private val muted = Color.rgb(145, 145, 145)
    private val green = Color.rgb(92, 210, 116)

    private val observer: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
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
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
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
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                setMargins(0, 0, dp(14), 0)
            }
        }
        header.addView(logo)

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBox.addView(label("DUKE DASH", 25f, text, true))
        titleBox.addView(label("RIDE  •  CONNECT  •  KNOW", 10f, muted, true).apply {
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val version = label("V1", 10f, muted, true).apply {
            gravity = Gravity.CENTER
            background = rounded(card2, 10)
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        header.addView(version)
        root.addView(header)

        status = label("", 12f, muted, true).apply {
            setPadding(dp(2), dp(16), 0, dp(5))
        }
        root.addView(status)

        val navCard = cardView()
        navCard.addView(label("NAVIGATION", 11f, orange, true))
        nav = label("—", 31f, text, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        }
        navCard.addView(nav)
        navMeta = label("Waiting for Google Maps…", 14f, muted)
        navCard.addView(navMeta)
        root.addView(navCard)

        val musicCard = cardView()
        musicCard.addView(label("NOW PLAYING", 11f, orange, true))
        music = label("No active music", 19f, text, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        }
        musicCard.addView(music)
        musicMeta = label("Start music on your phone", 13f, muted)
        musicCard.addView(musicMeta)
        root.addView(musicCard)

        val messageCard = cardView()
        messageCard.addView(label("MESSAGING", 11f, orange, true))
        message = label("No new messages", 17f, text, true).apply {
            setPadding(0, dp(10), 0, dp(5))
        }
        messageCard.addView(message)
        messageMeta = label("Supported messaging apps only", 13f, muted)
        messageCard.addView(messageMeta)
        root.addView(messageCard)

        val bleCard = cardView()
        bleCard.addView(label("DISPLAY LINK", 11f, orange, true))
        ble = label("○  READY • WAITING FOR ESP32", 15f, text, true).apply {
            setPadding(0, dp(10), 0, 0)
        }
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
        actions.addView(action("START DISPLAY") { startBleService() })
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
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(0, 0, dp(7), 0)
        }
    }

    private fun flash(view: View) {
        val anim = AlphaAnimation(0.72f, 1f).apply {
            duration = 180
            repeatCount = 0
        }
        view.startAnimation(anim)
    }

    private fun render() {
        val listenerReady = DashNotificationListener.instance != null
        status.text = if (listenerReady) "●  PHONE DATA READY" else "○  ENABLE NOTIFICATION ACCESS TO START"
        status.setTextColor(if (listenerReady) green else muted)

        val n = DashState.navigation
        nav.text = when {
            n.direction == "RIGHT" -> "↗  RIGHT"
            n.direction == "LEFT" -> "↖  LEFT"
            n.direction == "SLIGHT_RIGHT" -> "↗  SLIGHT RIGHT"
            n.direction == "SLIGHT_LEFT" -> "↖  SLIGHT LEFT"
            n.direction == "SHARP_RIGHT" -> "↗  SHARP RIGHT"
            n.direction == "SHARP_LEFT" -> "↖  SHARP LEFT"
            n.direction == "UTURN" -> "↶  U-TURN"
            n.direction == "KEEP_RIGHT" -> "↗  KEEP RIGHT"
            n.direction == "KEEP_LEFT" -> "↖  KEEP LEFT"
            n.direction == "ARRIVE" -> "●  ARRIVE"
            n.direction == "ROUNDABOUT" -> "↻  ROUNDABOUT"
            n.direction == "NORTH" -> "↑  HEAD NORTH"
            n.direction == "SOUTH" -> "↓  HEAD SOUTH"
            n.direction == "EAST" -> "→  HEAD EAST"
            n.direction == "WEST" -> "←  HEAD WEST"
            else -> "↑  STRAIGHT"
        }
        navMeta.text = buildString {
            if (n.distanceMeters != null && n.distanceMeters >= 0) append("${n.distanceMeters} m  •  ")
            append(if (n.instruction.isNotBlank()) n.instruction else "Waiting for Google Maps…")
            if (n.road.isNotBlank()) append("\n${n.road}")
        }

        val m = DashState.music
        music.text = if (m.title.isBlank()) "No active music" else m.title
        musicMeta.text = if (m.title.isBlank()) {
            "Start music on your phone"
        } else {
            "${m.artist.ifBlank { "Unknown artist" }}  •  ${if (m.playing) "PLAYING" else "PAUSED"}"
        }

        val msg = DashState.message
        message.text = if (msg.app.isBlank()) "No new messages" else msg.text.ifBlank { "New message" }
        messageMeta.text = if (msg.app.isBlank()) "Supported messaging apps only" else msg.app.uppercase()

        ble.text = if (DashState.phoneConnected) "●  DISPLAY CONNECTED" else "○  READY • WAITING FOR ESP32"
        ble.setTextColor(if (DashState.phoneConnected) green else text)
    }

    private fun startBleService() {
        val intent = Intent(this, DashBleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                390
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 391)
        }
    }
}
