package com.duke.dash

import java.util.concurrent.CopyOnWriteArraySet

data class NavigationState(val direction:String="NONE", val distanceMeters:Int?=null, val instruction:String="NO ROUTE", val road:String="", val raw:String="")
data class MusicState(val title:String="", val artist:String="", val playing:Boolean=false)
data class MessageState(val app:String="", val text:String="", val timestamp:Long=0L)

object DashState {
    @Volatile var navigation=NavigationState()
    @Volatile var music=MusicState()
    @Volatile var message=MessageState()
    @Volatile var phoneConnected=false
    @Volatile var displayRunning=false
    @Volatile var displayStarting=false
    @Volatile var displayError=""
    private val listeners=CopyOnWriteArraySet<()->Unit>()
    fun observe(listener:()->Unit){listeners.add(listener)}
    fun remove(listener:()->Unit){listeners.remove(listener)}
    fun changed(){listeners.forEach{it.invoke()}}
}
