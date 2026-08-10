package com.duke.dash

import java.util.concurrent.CopyOnWriteArraySet

data class NavigationState(
    val direction: String = "NONE",
    val distanceMeters: Int? = null,
    val instruction: String = "NO ROUTE",
    val road: String = "",
    val eta: String = "",
    val destinationDistanceMeters: Int? = null,
    val active: Boolean = false,
    val raw: String = ""
)

data class MusicState(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false
)

data class MessageState(
    val app: String = "",
    val text: String = "",
    val timestamp: Long = 0L
)

data class CallState(
    val active: Boolean = false,
    val caller: String = ""
)

object DashState {
    @Volatile var navigation = NavigationState()
    @Volatile var music = MusicState()
    @Volatile var message = MessageState()
    @Volatile var call = CallState()
    @Volatile var phoneBattery = -1
    @Volatile var phoneCharging = false
    @Volatile var phoneConnected = false
    @Volatile var displayRunning = false
    @Volatile var displayStarting = false
    @Volatile var displayError = ""

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun changed() { listeners.forEach { it.invoke() } }
}
