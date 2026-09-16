package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class AiuiBridgeService : Service() {
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var api: BridgeApi
    private val executor = Executors.newSingleThreadExecutor()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val results = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var response = encode(JSONObject().put("ok", false).put("error", "No request"))
    @Volatile private var currentId = ""
    private var pairUntil = 0L
    private data class Peer(var mtu: Int = 23, var sequence: Int = 0, val command: ByteArrayOutputStream = ByteArrayOutputStream(), var offset: Int = 0, var media: Boolean = false)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this; api = BridgeApi(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice Relay connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AiuiSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(901, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Voice Relay ready").setContentText("Bluetooth bridge for your glasses").setContentIntent(open).setOngoing(true).build())
        RelayMedia.cleanup(this)
        startGatt()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pair") { pairUntil = SystemClock.elapsedRealtime() + 60_000; pendingPeer = null; status = "Open Voice Relay on your glasses, then approve them here." }
        return START_STICKY
    }
    private fun trusted(device: BluetoothDevice): Boolean = device.bondState == BluetoothDevice.BOND_BONDED &&
        getSharedPreferences("aiui-bridge", 0).getString("trusted", null) == device.address
    fun approve(): Boolean {
        val address = pendingPeer ?: return false
        if (SystemClock.elapsedRealtime() > pairUntil) { status = "Pairing expired. Tap Pair glasses again."; return false }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) { device.createBond(); status = "Confirm Android's Bluetooth pairing prompt, then tap Approve again."; return false }
        getSharedPreferences("aiui-bridge", 0).edit().putString("trusted", address).apply()
        pairUntil = 0; status = "Glasses approved. Tap Connect on the glasses."; return true
    }
    private fun startGatt() {
        try {
            val manager = getSystemService(BluetoothManager::class.java)
            val adapter = manager.adapter ?: error("This phone has no Bluetooth")
            if (!adapter.isEnabled) error("Turn on Bluetooth, then restart the bridge")
            advertiser = adapter.bluetoothLeAdvertiser ?: error("Bluetooth advertising is unavailable")
            server = manager.openGattServer(this, callback) ?: error("Bluetooth bridge could not start")
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            // OS bonding encrypts message/audio traffic. The phone must also approve the peer.
            service.addCharacteristic(BluetoothGattCharacteristic(CONTROL, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            service.addCharacteristic(BluetoothGattCharacteristic(RESPONSE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            service.addCharacteristic(BluetoothGattCharacteristic(AUDIO, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            check(server!!.addService(service)) { "Could not register Bluetooth service" }
        } catch (e: Throwable) { status = e.message ?: "Bluetooth failed"; stopSelf() }
    }
    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { status = "Bridge ready. Tap Pair glasses for first setup." }
        override fun onStartFailure(errorCode: Int) { status = "Bluetooth advertising failed ($errorCode). Stop and restart the bridge." }
    }
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(code: Int, service: BluetoothGattService?) {
            if (code != BluetoothGatt.GATT_SUCCESS) { status = "Could not register Bluetooth service ($code)"; return }
            advertiser?.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertising)
        }
        override fun onConnectionStateChange(device: BluetoothDevice, code: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                peers[device.address] = Peer()
                if (!trusted(device)) {
                    if (SystemClock.elapsedRealtime() <= pairUntil) {
                        pendingPeer = device.address; status = "Glasses found. Confirm Bluetooth pairing and tap Approve glasses."
                        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                    } else { status = "New device blocked. Tap Pair glasses to allow setup."; server?.cancelConnection(device) }
                }
            } else { peers.remove(device.address); activeUntil = 0 }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { peers[device.address]?.mtu = mtu.coerceIn(23, 517) }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            var code = BluetoothGatt.GATT_SUCCESS
            try {
                require(!preparedWrite && offset == 0) { "Prepared writes are unsupported" }
                val peer = peers[device.address] ?: error("Disconnected")
                if (characteristic.uuid == AUDIO) {
                    require(trusted(device) && value.size >= 5)
                    api.append(leInt(value, 0), value.copyOfRange(4, value.size))
                } else {
                    require(characteristic.uuid == CONTROL && value.isNotEmpty())
                    val kind = value[0].toInt() and 255
                    if (kind == 16 || kind == 17) {
                        require(value.size == 5 && (kind == 16 || trusted(device))); peer.offset = leInt(value, 1)
                        require(peer.offset >= 0); peer.media = kind == 17
                    } else {
                        require(kind in 0..2 && value.size >= 3)
                        val sequence = (value[1].toInt() and 255) or ((value[2].toInt() and 255) shl 8)
                        if (kind == 0) { peer.command.reset(); peer.sequence = 0 }
                        require(sequence == peer.sequence++) { "Missing command fragment" }
                        require(peer.command.size() + value.size - 3 <= 8192)
                        peer.command.write(value, 3, value.size - 3)
                        if (kind == 2) {
                            val q = JSONObject(peer.command.toString("UTF-8")); peer.offset = 0; peer.media = false
                            val id = q.getString("id"); require(id.length in 1..100)
                            currentId = id
                            if (q.optString("op") == "hello") {
                                response = encode(JSONObject().put("id", id).put("ok", true).put("value", JSONObject()
                                    .put("version", 1).put("approved", trusted(device)).put("mtu", peer.mtu)))
                            } else {
                                require(trusted(device)) { "Approve your glasses on the phone" }
                                activeUntil = SystemClock.elapsedRealtime() + 15_000
                                val old = results[id]
                                if (old != null) response = old else {
                                    response = encode(JSONObject().put("id", id).put("pending", true))
                                    executor.execute { api.execute(q) { result ->
                                        val bytes = encode(result.put("id", id))
                                        if (results.size > 100) results.clear()
                                        results[id] = bytes
                                        if (currentId == id) response = bytes
                                    } }
                                }
                            }
                        }
                    }
                }
                if (trusted(device)) activeUntil = SystemClock.elapsedRealtime() + 15_000
            } catch (e: Throwable) { code = BluetoothGatt.GATT_FAILURE }
            if (responseNeeded) server?.sendResponse(device, requestId, code, 0, null)
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            try {
                require(characteristic.uuid == RESPONSE)
                val peer = peers[device.address] ?: error("Disconnected")
                require(offset in 0..512)
                val data = if (peer.media) {
                    require(trusted(device)); val file = api.download ?: error("No audio selected")
                    RandomAccessFile(file, "r").use { f ->
                        val start = peer.offset.toLong() + offset
                        require(start <= f.length()); f.seek(start)
                        val count = minOf((f.length() - start).toInt(), peer.mtu - 1, 512 - offset)
                        ByteArray(count).also { f.readFully(it) }
                    }
                } else {
                    // Before approval only the non-sensitive hello response is available.
                    require(trusted(device) || currentId.isNotBlank())
                    val source = if (trusted(device)) response else encode(JSONObject().put("id", currentId).put("ok", true).put("value", JSONObject().put("version", 1).put("approved", false).put("mtu", peer.mtu)))
                    val start = peer.offset + offset; require(start <= source.size)
                    source.copyOfRange(start, minOf(source.size, start + peer.mtu - 1, peer.offset + 512))
                }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            } catch (e: Throwable) { server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null) }
        }
    }
    override fun onDestroy() {
        runCatching { advertiser?.stopAdvertising(advertising) }; runCatching { server?.close() }
        executor.shutdown(); instance = null; activeUntil = 0; super.onDestroy()
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("8f1b9000-8c77-4a7a-9e52-018260091600")
        val CONTROL: UUID = UUID.fromString("8f1b9001-8c77-4a7a-9e52-018260091600")
        val RESPONSE: UUID = UUID.fromString("8f1b9002-8c77-4a7a-9e52-018260091600")
        val AUDIO: UUID = UUID.fromString("8f1b9003-8c77-4a7a-9e52-018260091600")
        const val CHANNEL = "aiui-connection"
        @Volatile var instance: AiuiBridgeService? = null
        @Volatile var status = "Bridge stopped"
        @Volatile var pendingPeer: String? = null
        @Volatile private var activeUntil = 0L
        fun hasActivePage() = SystemClock.elapsedRealtime() < activeUntil
        fun leInt(bytes: ByteArray, start: Int): Int = ByteBuffer.wrap(bytes, start, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun encode(value: JSONObject): ByteArray {
            val data = value.toString().toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()
        }
    }
}
