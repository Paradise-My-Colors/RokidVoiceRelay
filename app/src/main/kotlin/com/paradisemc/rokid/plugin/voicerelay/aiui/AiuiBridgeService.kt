package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
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
    private var advertising: AdvertiseCallback? = null
    @Volatile private var gattEpoch = 0L
    private lateinit var api: BridgeApi
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Peer>()
    private val results = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var response = encode(JSONObject().put("ok", false).put("error", "No request"))
    @Volatile private var currentId = ""
    @Volatile private var pairUntil = 0L
    @Volatile private var bondApproval: String? = null
    private var bondReceiverRegistered = false
    private data class Peer(var mtu: Int = 23, var sequence: Int = 0, val command: ByteArrayOutputStream = ByteArrayOutputStream(), var offset: Int = 0, var media: Boolean = false, @Volatile var helloSeen: Boolean = false)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this; api = BridgeApi(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice Relay connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AiuiSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(901, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Voice Relay ready").setContentText("Bluetooth bridge for your glasses").setContentIntent(open).setOngoing(true).build())
        RelayMedia.cleanup(this)
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bondReceiver, filter)
        bondReceiverRegistered = true
        startGatt()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pair") {
            pairUntil = SystemClock.elapsedRealtime() + 180_000
            status = if (pendingPeer != null) "Approval window open. Wait for the glasses to ask for approval, then tap Approve glasses." else "Pairing open for 3 minutes. Open Voice Relay on your glasses and tap Connect once."
        }
        if (intent?.action == "restart") {
            gattEpoch++
            handler.removeCallbacksAndMessages(null)
            runCatching { advertising?.let { advertiser?.stopAdvertising(it) } }; runCatching { server?.close() }
            server = null; peers.clear(); pendingPeer = null; bondApproval = null; currentId = ""; activeUntil = 0
            serviceState = "Restarting"
            status = "Restarting Bluetooth bridge…"
            handler.postDelayed({ startGatt() }, 400)
        }
        return START_STICKY
    }
    private fun trusted(device: BluetoothDevice): Boolean = device.bondState == BluetoothDevice.BOND_BONDED &&
        getSharedPreferences("aiui-bridge", 0).getString("trusted", null) == device.address
    fun approve(): Boolean {
        val address = pendingPeer ?: run { status = "On the glasses, tap Connect first and leave that page open."; return false }
        if (peers[address]?.helloSeen != true) { status = "Bluetooth link exists, but the glasses have not found Voice Relay yet. Wait for their approval instruction."; return false }
        if (SystemClock.elapsedRealtime() > pairUntil) { status = "Pairing expired. Tap Pair glasses again."; return false }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            bondApproval = address
            val started = device.bondState == BluetoothDevice.BOND_BONDING || runCatching { device.createBond() }.getOrDefault(false)
            status = if (started) "Confirm the Bluetooth pairing prompt. Approval completes automatically; leave the glasses app open." else "Bluetooth pairing could not start. Restart the bridge and try again."
            if (!started) bondApproval = null
            return false
        }
        finishApproval(device)
        return true
    }
    private fun finishApproval(device: BluetoothDevice) {
        val address = device.address
        getSharedPreferences("aiui-bridge", 0).edit().putString("trusted", address).apply()
        pairUntil = 0; bondApproval = null; pendingPeer = null
        status = "Glasses approved. The open glasses app will continue connecting."
    }
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.address != bondApproval) return
            if (device.bondState == BluetoothDevice.BOND_BONDED && SystemClock.elapsedRealtime() <= pairUntil) finishApproval(device)
            else if (device.bondState == BluetoothDevice.BOND_NONE) {
                bondApproval = null; status = "Bluetooth pairing was cancelled. Tap Approve glasses to try again."
            }
        }
    }
    private fun startGatt() {
        val epoch = ++gattEpoch
        serviceState = "Registering service"; status = "Starting Bluetooth bridge…"
        try {
            val manager = getSystemService(BluetoothManager::class.java)
            val adapter = manager.adapter ?: error("This phone has no Bluetooth")
            if (!adapter.isEnabled) error("Turn on Bluetooth, then restart the bridge")
            advertiser = adapter.bluetoothLeAdvertiser ?: error("Bluetooth advertising is unavailable")
            server = manager.openGattServer(this, callback(epoch)) ?: error("Bluetooth bridge could not start")
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            // Only six bytes of protocol/approval status are readable before bonding.
            // Message, command and audio characteristics retain OS-enforced encryption.
            service.addCharacteristic(BluetoothGattCharacteristic(HELLO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            // OS bonding encrypts message/audio traffic. The phone must also approve the peer.
            service.addCharacteristic(BluetoothGattCharacteristic(CONTROL, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            service.addCharacteristic(BluetoothGattCharacteristic(RESPONSE, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            service.addCharacteristic(BluetoothGattCharacteristic(AUDIO, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED))
            check(server!!.addService(service)) { "Could not register Bluetooth service" }
        } catch (e: Throwable) { serviceState = "Registration failed"; status = e.message ?: "Bluetooth failed"; stopSelf() }
    }
    private fun advertisingCallback(epoch: Long) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            if (epoch != gattEpoch) return
            serviceState = "Registered: 4 characteristics · Advertising ready"
            if (peers.isEmpty()) status = if (SystemClock.elapsedRealtime() <= pairUntil) "Pairing open for 3 minutes. Connect on the glasses, then approve them here." else "Bridge ready · 0.9.2. Open Voice Relay on the glasses."
        }
        override fun onStartFailure(errorCode: Int) { if (epoch == gattEpoch) { serviceState = "Registered · Advertising failed ($errorCode)"; status = "Bluetooth advertising failed ($errorCode). Stop and restart the bridge." } }
    }
    private fun callback(epoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(code: Int, service: BluetoothGattService?) {
            if (epoch != gattEpoch) return
            try {
                check(code == BluetoothGatt.GATT_SUCCESS && service?.uuid == SERVICE) { "Could not register Voice Relay service ($code)" }
                val registered = server?.getService(SERVICE) ?: error("Voice Relay service missing from phone database")
                check(listOf(HELLO, CONTROL, RESPONSE, AUDIO).all { registered.getCharacteristic(it) != null }) { "Voice Relay service incomplete" }
                serviceState = "Registered: 4 characteristics · Starting advertising"
                val advertising = advertisingCallback(epoch); this@AiuiBridgeService.advertising = advertising
                advertiser?.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertising)
            } catch (e: Throwable) { serviceState = "Service startup failed"; status = e.message ?: "Bluetooth service failed" }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, code: Int, state: Int) {
            if (epoch != gattEpoch) return
            if (state == BluetoothProfile.STATE_CONNECTED && code == BluetoothGatt.GATT_SUCCESS) {
                if (peers.keys.any { it != device.address }) { server?.cancelConnection(device); return }
                val peer = peers.getOrPut(device.address) { Peer() }
                if (!trusted(device)) {
                    pendingPeer = device.address
                    // Starting bonding inside this callback can race GATT discovery.
                    // The explicit Approve button starts pairing after public discovery.
                }
                if (!peer.helloSeen) status = "Bluetooth link detected. Waiting for the glasses to find the Voice Relay service…"
                handler.postDelayed({
                    if (epoch == gattEpoch && peers[device.address] === peer && !peer.helloSeen)
                        status = "Bluetooth link only: glasses have not read the Voice Relay service. Check Connection details on the glasses."
                }, 20_000)
            } else {
                if (peers.remove(device.address) == null) return
                activeUntil = 0
                if (pendingPeer == device.address) pendingPeer = null
                if (code != BluetoothGatt.GATT_SUCCESS) status = "Glasses Bluetooth disconnected (status $code). Restart the bridge, then Connect once on the glasses."
                else status = "Voice Relay disconnected. Bridge ready for Connect on the glasses."
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { if (epoch == gattEpoch) peers[device.address]?.mtu = mtu.coerceIn(23, 517) }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (epoch != gattEpoch) return
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
                                    .put("version", 2).put("approved", trusted(device)).put("mtu", peer.mtu)))
                                if (trusted(device)) status = "Secure handshake received. Waiting for the glasses to open Inbox…"
                            } else {
                                require(trusted(device)) { "Approve your glasses on the phone" }
                                if (q.optString("op") == "inbox") status = "Voice Relay connected securely · 0.9.2 · Inbox requested"
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
            if (epoch != gattEpoch) return
            try {
                val peer = peers[device.address] ?: error("Disconnected")
                if (characteristic.uuid == HELLO) {
                    if (!peer.helloSeen) {
                        peer.helloSeen = true
                        status = if (trusted(device)) "Voice Relay service found. Checking the secure connection…"
                            else if (SystemClock.elapsedRealtime() <= pairUntil) "Voice Relay service found. Tap Approve glasses, then confirm any pairing prompt."
                            else "Voice Relay service found. Tap Pair glasses, then Approve glasses."
                    }
                    val flags = (if (trusted(device)) 1 else 0) or (if (device.bondState == BluetoothDevice.BOND_BONDED) 2 else 0)
                    val value = byteArrayOf(86, 82, 2, flags.toByte(), peer.mtu.toByte(), (peer.mtu ushr 8).toByte())
                    require(offset in 0..value.size)
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                    return
                }
                require(characteristic.uuid == RESPONSE)
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
                    val source = if (trusted(device)) response else encode(JSONObject().put("id", currentId).put("ok", true).put("value", JSONObject().put("version", 2).put("approved", false).put("mtu", peer.mtu)))
                    val start = peer.offset + offset; require(start <= source.size)
                    source.copyOfRange(start, minOf(source.size, start + peer.mtu - 1, peer.offset + 512))
                }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            } catch (e: Throwable) { server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null) }
        }
    }
    override fun onDestroy() {
        gattEpoch++
        handler.removeCallbacksAndMessages(null)
        if (bondReceiverRegistered) runCatching { unregisterReceiver(bondReceiver) }
        runCatching { advertising?.let { advertiser?.stopAdvertising(it) } }; runCatching { server?.close() }
        executor.shutdown(); instance = null; activeUntil = 0; pendingPeer = null; super.onDestroy()
        serviceState = "Bridge stopped"
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("8f1b9000-8c77-4a7a-9e52-018260091600")
        val CONTROL: UUID = UUID.fromString("8f1b9001-8c77-4a7a-9e52-018260091600")
        val RESPONSE: UUID = UUID.fromString("8f1b9002-8c77-4a7a-9e52-018260091600")
        val AUDIO: UUID = UUID.fromString("8f1b9003-8c77-4a7a-9e52-018260091600")
        val HELLO: UUID = UUID.fromString("8f1b9004-8c77-4a7a-9e52-018260091600")
        const val CHANNEL = "aiui-connection"
        @Volatile var instance: AiuiBridgeService? = null
        @Volatile var status = "Bridge stopped"
        @Volatile var serviceState = "Bridge stopped"
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
