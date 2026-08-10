package com.duke.dash

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

class DashBleService : Service() {
    companion object {
        val SERVICE_UUID = UUID.fromString("8f8a0001-5d3e-4d0a-9c7b-000000000001")
        val DATA_UUID = UUID.fromString("8f8a0002-5d3e-4d0a-9c7b-000000000002")
        val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CHANNEL = "duke_dash_ble"
        const val NOTIFICATION_ID = 390
        private const val FRAME_PREFIX = "DD1"
        private const val CHUNK_BYTES = 72
    }

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var characteristic: BluetoothGattCharacteristic
    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val notifyingDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val observer: () -> Unit = { sendState() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            DashState.displayStarting = false
            DashState.displayRunning = true
            DashState.displayError = ""
            DashState.changed()
        }

        override fun onStartFailure(errorCode: Int) {
            DashState.displayStarting = false
            DashState.displayRunning = false
            DashState.displayError = "BLE advertising error $errorCode"
            DashState.phoneConnected = false
            DashState.changed()
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS || service?.uuid != SERVICE_UUID) {
                fail("BLE service setup error $status")
                return
            }
            startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connectedDevices.add(device)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    notifyingDevices.remove(device)
                }
            }
            DashState.phoneConnected = connectedDevices.isNotEmpty()
            DashState.changed()
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notifyingDevices.add(device)
                } else {
                    notifyingDevices.remove(device)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            sendState()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != DATA_UUID) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val bytes = payload().toByteArray(StandardCharsets.UTF_8)
            if (offset < 0 || offset > bytes.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                bytes.copyOfRange(offset, bytes.size)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Duke Dash")
                .setContentText("Display link is running")
                .setOngoing(true)
                .build()
        )

        DashState.displayStarting = true
        DashState.displayRunning = false
        DashState.displayError = ""
        DashState.phoneConnected = false
        DashState.observe(observer)
        DashState.changed()

        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        startBle()
    }

    private fun updateBattery(intent: Intent) {
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
    }

    private fun startBle() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off")
            return
        }

        try {
            server = manager.openGattServer(this, callback)
            if (server == null) {
                fail("Could not open BLE server")
                return
            }

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            characteristic = BluetoothGattCharacteristic(
                DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(characteristic)

            if (server?.addService(service) != true) fail("Could not register BLE service")
        } catch (_: SecurityException) {
            fail("Bluetooth permission denied")
        } catch (error: Exception) {
            fail(error.javaClass.simpleName)
        }
    }

    private fun startAdvertising() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val bleAdvertiser = adapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            fail("BLE advertising unavailable")
            return
        }

        advertiser = bleAdvertiser
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
            bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (_: SecurityException) {
            fail("Bluetooth advertise permission denied")
        } catch (error: Exception) {
            fail(error.javaClass.simpleName)
        }
    }

    private fun fail(reason: String) {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        DashState.displayStarting = false
        DashState.displayRunning = false
        DashState.displayError = reason
        DashState.phoneConnected = false
        DashState.changed()
    }

    private fun payload(): String = buildString {
        val n = DashState.navigation
        val m = DashState.music
        val x = DashState.message
        val c = DashState.call
        append("TIME|").append(SimpleDateFormat("HH:mm", Locale.US).format(Date())).append('\n')
        append("BATTERY|").append(DashState.phoneBattery).append('|')
            .append(if (DashState.phoneCharging) "CHARGING" else "NORMAL").append('\n')
        append("CONN|").append(if (DashState.phoneConnected) "CONNECTED" else "DISCONNECTED").append('\n')
        append("NAV|").append(n.direction).append('|').append(n.distanceMeters ?: -1).append('|')
            .append(n.instruction.replace('|', ' ')).append('|').append(n.road.replace('|', ' ')).append('|')
            .append(n.eta.replace('|', ' ')).append('|').append(n.destinationDistanceMeters ?: -1).append('|')
            .append(if (n.active) "ACTIVE" else "INACTIVE").append('\n')
        append("MUSIC|").append(m.title.replace('|', ' ')).append('|').append(m.artist.replace('|', ' ')).append('|')
            .append(if (m.playing) "PLAYING" else "PAUSED").append('\n')
        if (x.app.isNotBlank()) {
            append("MSG|").append(x.app.replace('|', ' ')).append('|')
                .append(x.text.replace('|', ' ')).append('|').append(x.timestamp).append('\n')
        }
        append("CALL|").append(if (c.active) "ACTIVE" else "NONE").append('|')
            .append(c.caller.replace('|', ' ')).append('\n')
    }

    private fun sendState() {
        if (!::characteristic.isInitialized || notifyingDevices.isEmpty()) return
        val bytes = payload().toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val frameId = UUID.randomUUID().toString().replace("-", "").take(8)
        val chunks = encoded.chunked(CHUNK_BYTES)
        val total = chunks.size

        notifyingDevices.forEach { device ->
            try {
                chunks.forEachIndexed { index, chunk ->
                    val frame = "$FRAME_PREFIX|$frameId|${index + 1}|$total|$chunk"
                    val frameBytes = frame.toByteArray(StandardCharsets.UTF_8)
                    characteristic.value = frameBytes
                    server?.notifyCharacteristicChanged(device, characteristic, false, frameBytes)
                }
            } catch (_: Exception) {
                notifyingDevices.remove(device)
            }
        }
    }

    override fun onDestroy() {
        DashState.remove(observer)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { server?.clearServices(); server?.close() } catch (_: Exception) {}
        connectedDevices.clear()
        notifyingDevices.clear()
        DashState.phoneConnected = false
        DashState.displayStarting = false
        DashState.displayRunning = false
        DashState.changed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Duke Dash Bluetooth", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
