package com.paradisemc.rokid.plugin.voicerelay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.provider.ContactsContract
import android.provider.MediaStore
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramClientManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object AiuiBleBridge {
    private val SERVICE_UUID = UUID.fromString("7f9c0001-069b-4e65-9d46-0f6680a4d953")
    private val COMMAND_UUID = UUID.fromString("7f9c0002-069b-4e65-9d46-0f6680a4d953")
    private val EVENT_UUID = UUID.fromString("7f9c0003-069b-4e65-9d46-0f6680a4d953")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val EVENT_MARKER = 0xA5
    private const val UPLOAD_MARKER = 0x5A
    private const val COMMAND_MARKER = 0x5B
    private const val EVENT_JSON = 1
    private const val EVENT_AUDIO = 2
    private const val FLAG_FIRST = 1
    private const val FLAG_LAST = 2
    private const val MAX_MEDIA_BYTES = 8 * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor()
    private val transferIds = AtomicInteger(100)
    private val outgoing = ArrayDeque<ByteArray>()
    private val commands = mutableMapOf<Int, ByteArrayOutputStream>()
    private val uploads = mutableMapOf<Int, UploadSession>()
    @Volatile private var appContext: Context? = null
    @Volatile private var manager: BluetoothManager? = null
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var eventCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var notificationsEnabled = false
    @Volatile private var notificationInFlight = false
    @Volatile private var negotiatedMtu = 23

    private data class UploadSession(
        val targetId: String,
        val expectedSize: Int,
        val duration: Int,
        val mode: String,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!hasPermissions(ctx) || server != null) return
        val bt = ctx.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bt.adapter ?: return
        if (!adapter.isEnabled) return
        manager = bt
        val gatt = bt.openGattServer(ctx, callback) ?: return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val command = BluetoothGattCharacteristic(COMMAND_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val events = BluetoothGattCharacteristic(EVENT_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        events.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(command); service.addCharacteristic(events)
        if (!gatt.addService(service)) { gatt.close(); return }
        server = gatt; eventCharacteristic = events
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        val ctx = appContext
        if (ctx != null && hasPermissions(ctx)) {
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            runCatching { server?.close() }
        }
        server = null; eventCharacteristic = null; connectedDevice = null; notificationsEnabled = false; notificationInFlight = false
        synchronized(outgoing) { outgoing.clear() }; synchronized(commands) { commands.clear() }; synchronized(uploads) { uploads.clear() }
    }

    fun notifyInboxChanged() { if (connectedDevice != null && notificationsEnabled) sendInbox() }

    private fun hasPermissions(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    private val advertiseCallback = object : AdvertiseCallback() {}
    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { connectedDevice = device; negotiatedMtu = 23 }
            else if (connectedDevice?.address == device?.address) { connectedDevice = null; notificationsEnabled = false; synchronized(outgoing) { outgoing.clear() }; notificationInFlight = false }
        }
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) { if (device?.address == connectedDevice?.address) negotiatedMtu = mtu.coerceAtLeast(23) }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val bytes = value ?: ByteArray(0)
            var status = BluetoothGatt.GATT_SUCCESS
            if (characteristic?.uuid != COMMAND_UUID || preparedWrite || offset != 0 || bytes.isEmpty()) status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            else runCatching { handleIncomingFrame(bytes) }.onFailure { status = BluetoothGatt.GATT_FAILURE }
            if (responseNeeded && device != null) runCatching { server?.sendResponse(device, requestId, status, 0, null) }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor?.uuid == CCCD_UUID && !preparedWrite && offset == 0) notificationsEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            else status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (responseNeeded && device != null) runCatching { server?.sendResponse(device, requestId, status, 0, null) }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            if (device != null) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, if (characteristic?.uuid == EVENT_UUID) "VoiceRelay/1".toByteArray() else ByteArray(0)) }
        }
        override fun onNotificationSent(device: BluetoothDevice?, status: Int) { notificationInFlight = false; sendNextFrame() }
    }

    private fun handleIncomingFrame(bytes: ByteArray) { when (bytes[0].toInt() and 0xff) { COMMAND_MARKER -> receiveCommandFragment(bytes); UPLOAD_MARKER -> receiveUploadFragment(bytes) } }

    private fun receiveCommandFragment(bytes: ByteArray) {
        if (bytes.size < 5) return
        val id = (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8); val flags = bytes[3].toInt() and 0xff
        val output = synchronized(commands) { if (flags and FLAG_FIRST != 0) commands[id] = ByteArrayOutputStream(); commands[id] ?: ByteArrayOutputStream().also { commands[id] = it } }
        output.write(bytes, 5, bytes.size - 5)
        if (flags and FLAG_LAST != 0) { synchronized(commands) { commands.remove(id) }; handleCommand(JSONObject(output.toString(Charsets.UTF_8.name()))) }
    }

    private fun receiveUploadFragment(bytes: ByteArray) {
        if (bytes.size < 5) return
        val id = (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8); val flags = bytes[3].toInt() and 0xff
        val session = synchronized(uploads) { uploads[id] } ?: return
        session.output.write(bytes, 5, bytes.size - 5)
        if (session.output.size() > session.expectedSize + 1024) { synchronized(uploads) { uploads.remove(id) }; sendError("Audio upload exceeded expected size.", "reply.error"); return }
        if (flags and FLAG_LAST != 0) {
            synchronized(uploads) { uploads.remove(id) }; val audio = session.output.toByteArray()
            if (audio.size != session.expectedSize) { sendError("Audio upload was incomplete (${audio.size}/${session.expectedSize} bytes).", "reply.error"); return }
            io.execute { deliverReply(session, audio) }
        }
    }

    private fun handleCommand(command: JSONObject) {
        val context = appContext ?: return
        when (command.optString("op")) {
            "hello" -> sendJson(JSONObject().put("type", "hello").put("protocol", 1).put("settings", AiuiNotificationPreferences.settingsJson(context)))
            "inbox.list" -> sendInbox()
            "settings.get" -> sendJson(JSONObject().put("type", "settings").put("settings", AiuiNotificationPreferences.settingsJson(context)))
            "settings.set" -> if (!AiuiNotificationPreferences.set(context, command.optString("key"), command.optBoolean("value"))) sendError("Unknown setting.") else sendJson(JSONObject().put("type", "settings").put("settings", AiuiNotificationPreferences.settingsJson(context)))
            "media.get" -> sendRequestedMedia(command.optString("id"))
            "reply.begin" -> beginReply(command)
            else -> sendError("Unknown Voice Relay command.")
        }
    }

    private fun sendInbox() {
        val context = appContext ?: return; val messages = JSONArray()
        PendingMessageStore.inbox(context).filter { it.packageName?.let { pkg -> AiuiNotificationPreferences.isAppEnabled(context, pkg) } == true }.forEach { message ->
            messages.put(JSONObject().put("id", messageId(message)).put("app", message.app).put("sender", message.sender).put("text", message.text).put("voice", message.voiceMessage).put("receivedAt", message.receivedAt))
        }
        sendJson(JSONObject().put("type", "inbox").put("messages", messages))
    }

    private fun beginReply(command: JSONObject) {
        val id = command.optString("id"); if (findMessage(id) == null) { sendError("That conversation is no longer pending.", "reply.error"); return }
        val size = command.optInt("size", -1); if (size <= 0 || size > MAX_MEDIA_BYTES) { sendError("Invalid reply audio size.", "reply.error"); return }
        var transfer = transferIds.incrementAndGet() and 0xffff; if (transfer == 0) transfer = 1
        synchronized(uploads) { uploads[transfer] = UploadSession(id, size, command.optInt("duration", 1).coerceIn(1, 60), command.optString("mode", "voice")) }
        sendJson(JSONObject().put("type", "reply.ready").put("transfer", transfer).put("chunk", 120))
    }

    private fun sendRequestedMedia(id: String) {
        val context = appContext ?: return; val target = findMessage(id)
        if (target == null || !target.voiceMessage) { sendError("No voice message is available for that conversation.", "media.error"); return }
        val uriText = target.mediaUri
        if (uriText.isNullOrBlank()) {
            val msg = if (target.packageName?.startsWith("com.whatsapp") == true) "WhatsApp did not expose this encrypted voice note to Android. Open it on the phone once and try again." else "Telegram did not expose an Android-readable media URI for this notification."
            sendError(msg, "media.error"); return
        }
        io.execute {
            runCatching {
                val uri = Uri.parse(uriText)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(16384)
                    while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); if (output.size() > MAX_MEDIA_BYTES) error("Voice message is larger than 8 MB.") }
                    output.toByteArray()
                } ?: error("Android denied access to the voice-message media.")
                sendAudio(bytes, target.mediaMimeType ?: context.contentResolver.getType(uri) ?: "audio/ogg", "${target.app} · ${target.sender}")
            }.onFailure { sendError(it.message ?: "Voice-message media could not be read.", "media.error") }
        }
    }

    private fun deliverReply(session: UploadSession, audio: ByteArray) {
        val context = appContext ?: return; val target = findMessage(session.targetId) ?: run { sendError("Target conversation expired.", "reply.error"); return }
        val pkg = target.packageName.orEmpty()
        if (pkg.startsWith("org.telegram") || pkg == "org.thunderdog.challegram") {
            if (session.mode == "file") {
                runCatching { launchShare(context, target, publishOgg(context, audio)); sendJson(JSONObject().put("type", "reply.sent").put("pendingPhone", true)) }.onFailure { sendError(it.message ?: "Telegram audio-file share failed.", "reply.error") }
            } else {
                val file = File.createTempFile("aiui_reply_", ".ogg", context.cacheDir).apply { writeBytes(audio) }
                TelegramClientManager.get(context).sendVoiceNote(target, file.absolutePath, session.duration) { result ->
                    file.delete(); result.fold(onSuccess = { finishSuccessfulReply(target); sendJson(JSONObject().put("type", "reply.sent").put("pendingPhone", false)) }, onFailure = { sendError(it.message ?: "Telegram voice reply failed.", "reply.error") })
                }
            }
            return
        }
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            runCatching {
                val uri = publishOgg(context, audio)
                if (VoiceRelayNotificationListener.sendAudioDataReply(target, uri, "audio/ogg")) { finishSuccessfulReply(target); sendJson(JSONObject().put("type", "reply.sent").put("pendingPhone", false)) }
                else sendJson(JSONObject().put("type", "reply.sent").put("pendingPhone", launchWhatsAppVoiceIntent(context, target, uri)))
            }.onFailure { sendError(it.message ?: "WhatsApp audio reply failed.", "reply.error") }
            return
        }
        sendError("Unsupported messaging app.", "reply.error")
    }

    private fun finishSuccessfulReply(target: IncomingMessage) { val context = appContext ?: return; PendingMessageStore.remove(context, target); VoiceRelayNotificationListener.dismissNotification(target.notificationKey); VoiceRelayPluginService.notifyInboxChanged() }
    private fun findMessage(id: String): IncomingMessage? { val context = appContext ?: return null; return PendingMessageStore.inbox(context).firstOrNull { messageId(it) == id } }
    private fun messageId(message: IncomingMessage): String = MessageDigest.getInstance("SHA-256").digest(message.stableKey().toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun publishOgg(context: Context, bytes: ByteArray): Uri {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, "RokidVoice_${System.currentTimeMillis()}.ogg"); put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg"); put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RokidVoiceRelay"); put(MediaStore.Audio.Media.IS_PENDING, 1) }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create audio file.")
        try { context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("Could not write audio file."); context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null); return uri }
        catch (error: Throwable) { context.contentResolver.delete(uri, null, null); throw error }
    }

    private fun launchWhatsAppVoiceIntent(context: Context, target: IncomingMessage, uri: Uri): Boolean {
        val packageName = target.packageName ?: error("WhatsApp package unavailable.")
        val chatId = target.shortcutId?.takeIf { it.isNotBlank() } ?: target.senderPersonKey?.takeIf { it.isNotBlank() } ?: target.senderPersonUri?.removePrefix("tel:")?.takeIf { it.isNotBlank() }
        val intent = Intent(ContactsContract.Intents.ACTION_VOICE_SEND_MESSAGE_TO_CONTACTS).apply { setPackage(packageName); type = "audio/ogg"; clipData = ClipData.newUri(context.contentResolver, "Rokid voice note", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(ContactsContract.Intents.EXTRA_RECIPIENT_CONTACT_NAME, arrayOf(target.sender)); if (chatId != null) putExtra(ContactsContract.Intents.EXTRA_RECIPIENT_CONTACT_CHAT_ID, arrayOf(chatId)) }
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) context.startActivity(intent) else launchShare(context, target, uri)
        return true
    }

    private fun launchShare(context: Context, target: IncomingMessage, uri: Uri) {
        val packageName = target.packageName ?: error("Messaging package unavailable.")
        val intent = Intent(Intent.ACTION_SEND).apply { setPackage(packageName); type = "audio/ogg"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newUri(context.contentResolver, "Voice Relay audio", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) error("Messaging app cannot accept audio-file share."); context.startActivity(intent)
    }

    private fun sendAudio(bytes: ByteArray, mime: String, title: String) { var transfer = transferIds.incrementAndGet() and 0xffff; if (transfer == 0) transfer = 1; sendJson(JSONObject().put("type", "media.begin").put("transfer", transfer).put("mime", mime).put("title", title)); enqueueFrames(EVENT_AUDIO, transfer, bytes) }
    private fun sendError(message: String, type: String = "error") = sendJson(JSONObject().put("type", type).put("message", message.take(300)))
    private fun sendJson(json: JSONObject) { var transfer = transferIds.incrementAndGet() and 0xffff; if (transfer == 0) transfer = 1; enqueueFrames(EVENT_JSON, transfer, json.toString().toByteArray(Charsets.UTF_8)) }

    private fun enqueueFrames(kind: Int, transfer: Int, payload: ByteArray) {
        val payloadSize = (negotiatedMtu - 9).coerceIn(14, 160); val frames = mutableListOf<ByteArray>(); var offset = 0
        if (payload.isEmpty()) frames += eventFrame(kind, transfer, FLAG_FIRST or FLAG_LAST, ByteArray(0))
        while (offset < payload.size) { val end = (offset + payloadSize).coerceAtMost(payload.size); val flags = (if (offset == 0) FLAG_FIRST else 0) or (if (end == payload.size) FLAG_LAST else 0); frames += eventFrame(kind, transfer, flags, payload.copyOfRange(offset, end)); offset = end }
        synchronized(outgoing) { frames.forEach { outgoing.addLast(it) } }; sendNextFrame()
    }

    private fun eventFrame(kind: Int, transfer: Int, flags: Int, payload: ByteArray): ByteArray { val frame = ByteArray(6 + payload.size); frame[0] = EVENT_MARKER.toByte(); frame[1] = kind.toByte(); frame[2] = (transfer and 0xff).toByte(); frame[3] = ((transfer ushr 8) and 0xff).toByte(); frame[4] = flags.toByte(); payload.copyInto(frame, 6); return frame }

    @SuppressLint("MissingPermission")
    private fun sendNextFrame() {
        if (notificationInFlight || !notificationsEnabled) return
        val gatt = server ?: return; val device = connectedDevice ?: return; val characteristic = eventCharacteristic ?: return; val frame = synchronized(outgoing) { if (outgoing.isEmpty()) null else outgoing.removeFirst() } ?: return
        notificationInFlight = true
        val started = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) gatt.notifyCharacteristicChanged(device, characteristic, false, frame) == BluetoothStatusCodes.SUCCESS else { @Suppress("DEPRECATION") characteristic.value = frame; @Suppress("DEPRECATION") gatt.notifyCharacteristicChanged(device, characteristic, false) } }.getOrDefault(false)
        if (!started) { notificationInFlight = false; synchronized(outgoing) { outgoing.addFirst(frame) } }
    }
}
