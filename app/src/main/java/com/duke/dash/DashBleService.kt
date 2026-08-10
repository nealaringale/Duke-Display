package com.duke.dash

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.IBinder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

class DashBleService:Service(){
    companion object{val SERVICE_UUID=UUID.fromString("8f8a0001-5d3e-4d0a-9c7b-000000000001"); val DATA_UUID=UUID.fromString("8f8a0002-5d3e-4d0a-9c7b-000000000002"); val CCCD_UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); const val CHANNEL="duke_dash_ble"; const val NOTIFICATION_ID=390}
    private var server:BluetoothGattServer?=null; private var advertiser:BluetoothLeAdvertiser?=null
    private lateinit var characteristic:BluetoothGattCharacteristic
    private val clients=CopyOnWriteArraySet<BluetoothDevice>()
    private val observer:()->Unit={sendState()}
    private val callback=object:BluetoothGattServerCallback(){
        override fun onConnectionStateChange(d:BluetoothDevice,status:Int,state:Int){DashState.phoneConnected=state==BluetoothProfile.STATE_CONNECTED;if(state==BluetoothProfile.STATE_DISCONNECTED)clients.remove(d);DashState.changed()}
        override fun onDescriptorWriteRequest(d:BluetoothDevice,id:Int,desc:BluetoothGattDescriptor,prepared:Boolean,response:Boolean,offset:Int,value:ByteArray){if(desc.uuid==CCCD_UUID){if(value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))clients.add(d) else clients.remove(d)};if(response)server?.sendResponse(d,id,BluetoothGatt.GATT_SUCCESS,offset,null);sendState()}
        override fun onCharacteristicReadRequest(d:BluetoothDevice,id:Int,offset:Int,c:BluetoothGattCharacteristic){if(c.uuid!=DATA_UUID){server?.sendResponse(d,id,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null);return};val b=payload().toByteArray(StandardCharsets.UTF_8);val o=offset.coerceIn(0,b.size);server?.sendResponse(d,id,BluetoothGatt.GATT_SUCCESS,offset,b.copyOfRange(o,b.size))}
    }
    override fun onCreate(){super.onCreate();createChannel();startForeground(NOTIFICATION_ID,Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Duke Dash").setContentText("Display link is running").setOngoing(true).build());DashState.observe(observer);startBle()}
    private fun startBle(){val manager=getSystemService(BluetoothManager::class.java)?:return;val adapter=manager.adapter?:return;server=manager.openGattServer(this,callback);val service=BluetoothGattService(SERVICE_UUID,BluetoothGattService.SERVICE_TYPE_PRIMARY);characteristic=BluetoothGattCharacteristic(DATA_UUID,BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ);characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE));service.addCharacteristic(characteristic);server?.addService(service);advertiser=adapter.bluetoothLeAdvertiser;advertiser?.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build(),object:AdvertiseCallback(){override fun onStartSuccess(s:AdvertiseSettings?){DashState.changed()};override fun onStartFailure(e:Int){DashState.phoneConnected=false;DashState.changed()}})}
    private fun payload()=buildString{val n=DashState.navigation;val m=DashState.music;val x=DashState.message;append("TIME|").append(SimpleDateFormat("HH:mm",Locale.US).format(Date())).append('\n');append("NAV|").append(n.direction).append('|').append(n.distanceMeters?:-1).append('|').append(n.instruction.replace('|',' ')).append('|').append(n.road.replace('|',' ')).append('\n');append("MUSIC|").append(m.title.replace('|',' ')).append('|').append(m.artist.replace('|',' ')).append('|').append(if(m.playing)"PLAYING" else "PAUSED").append('\n');if(x.app.isNotBlank())append("MSG|").append(x.app.replace('|',' ')).append('|').append(x.text.replace('|',' ')).append('\n')}
    private fun sendState(){if(!::characteristic.isInitialized||clients.isEmpty())return;val b=payload().toByteArray(StandardCharsets.UTF_8).copyOfRange(0,minOf(payload().toByteArray(StandardCharsets.UTF_8).size,500));clients.forEach{try{server?.notifyCharacteristicChanged(it,characteristic,false,b)}catch(_:Exception){clients.remove(it)}}}
    override fun onDestroy(){DashState.remove(observer);try{advertiser?.stopAdvertising(object:AdvertiseCallback(){})}catch(_:Exception){};try{server?.close()}catch(_:Exception){};clients.clear();super.onDestroy()}
    override fun onBind(i:Intent?):IBinder?=null
    private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Duke Dash Bluetooth",NotificationManager.IMPORTANCE_LOW))}
}
