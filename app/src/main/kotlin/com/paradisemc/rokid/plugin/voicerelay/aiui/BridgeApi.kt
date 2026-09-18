package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.paradisemc.rokid.plugin.voicerelay.*
import com.paradisemc.rokid.plugin.voicerelay.telegram.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Phone authority for settings, immutable recipients, media and send receipts. */
class BridgeApi(private val c: Context) {
    private val snapshots = ConcurrentHashMap<String, IncomingMessage>()
    private val prefs = c.getSharedPreferences("aiui-operations", 0)
    private var upload: File? = null
    private var uploadLength = 0
    private var uploadId = ""
    private var draftTarget: IncomingMessage? = null
    private var sealed = false
    @Volatile var download: File? = null
        private set
    @Volatile private var downloadId = ""

    fun settings(): JSONObject = JSONObject()
        .put("telegram_enabled", NotificationDisplayPreferences.telegramEnabled(c))
        .put("whatsapp_enabled", NotificationDisplayPreferences.whatsappEnabled(c))
        .put("hide_when_phone_unlocked", NotificationDisplayPreferences.hideWhenPhoneUnlocked(c))
        .put("respect_phone_silent", NotificationDisplayPreferences.respectPhoneSilent(c))
        .put("respect_dnd", NotificationDisplayPreferences.respectDnd(c))
        .put("nexus_notices", NotificationDisplayPreferences.nexusNotices(c))

    private fun messages() = PendingMessageStore.inbox(c).filter { NotificationDisplayPreferences.appEnabled(c, it.packageName) }
    private fun message(id: String): IncomingMessage = snapshots[id]
        ?: messages().firstOrNull { RelayMedia.id(it) == id } ?: error("Conversation changed. Reopen the inbox.")

    fun execute(q: JSONObject, done: (JSONObject) -> Unit) {
        fun success(value: Any = JSONObject()) = done(JSONObject().put("ok", true).put("value", value))
        fun failure(e: Throwable) = done(JSONObject().put("ok", false).put("error", e.message ?: "Operation failed"))
        try {
            when (q.getString("op")) {
                "status" -> {
                    val list = messages()
                    success(JSONObject().put("revision", RelayMedia.digest(list.joinToString { RelayMedia.id(it) }.toByteArray()).take(16))
                        .put("count", list.size).put("alerts", NotificationDisplayPreferences.shouldShowOnGlasses(c))
                        .put("listener", PendingMessageStore.listenerConnected(c)).put("telegram", TelegramClientManager.get(c).isReady()))
                }
                "inbox" -> {
                    val out = JSONArray()
                    if (snapshots.size > 120) snapshots.clear()
                    for (m in messages()) {
                        val id = RelayMedia.id(m); snapshots[id] = m
                        out.put(JSONObject().put("id", id).put("app", m.app).put("sender", m.sender.take(90))
                            .put("text", m.text.take(500)).put("voice", m.voiceMessage).put("date", m.receivedAt)
                            .put("telegram", TelegramVoiceSender.isTelegram(m)))
                    }
                    success(out)
                }
                "settings" -> success(settings())
                "setting" -> { NotificationDisplayPreferences.setOption(c, q.getString("key"), q.getBoolean("enabled")); success(settings()) }
                "dismiss" -> { PendingMessageStore.remove(c, message(q.getString("target"))); success() }
                "voices" -> {
                    val m = message(q.getString("target"))
                    if (TelegramVoiceSender.isTelegram(m)) {
                        TelegramClientManager.get(c).listVoiceNotes(m) { it.fold({ notes -> success(notes) }, ::failure) }
                    } else {
                        val cached = RelayMedia.cached(c, m)
                        val direct = m.mediaUri?.let { runCatching { RelayMedia.cache(c, Uri.parse(it)) }.getOrNull() }
                        val file = cached ?: direct
                        if (file == null) error("WhatsApp did not provide the audio. On your phone, share this voice message to Voice Relay, then choose this conversation.")
                        download = file
                        success(JSONArray().put(JSONObject().put("message", "shared").put("label", "Shared voice message").put("date", m.receivedAt / 1000).put("seconds", 0)))
                    }
                }
                "download" -> {
                    val m = message(q.getString("target"))
                    if (TelegramVoiceSender.isTelegram(m)) {
                        TelegramClientManager.get(c).downloadVoice(m, q.getString("message").toLong()) { result ->
                            result.fold({ file -> download = file; success(fileInfo(file)) }, ::failure)
                        }
                    } else {
                        val file = RelayMedia.cached(c, m) ?: m.mediaUri?.let { RelayMedia.cache(c, Uri.parse(it)) }
                            ?: error("Share this voice message from WhatsApp to Voice Relay first")
                        download = file; success(fileInfo(file))
                    }
                }
                "begin" -> synchronized(this) {
                    val target = message(q.getString("target"))
                    val size = q.getInt("size")
                    require(size in 1..RelayMedia.MAX_BYTES) { "Recording size is invalid" }
                    if (!sealed) upload?.delete()
                    upload = RelayMedia.newFile(c, ".audio"); uploadLength = size
                    uploadId = UUID.randomUUID().toString(); draftTarget = target; sealed = false
                    success(JSONObject().put("upload", uploadId))
                }
                "seal" -> synchronized(this) {
                    require(q.getString("upload") == uploadId) { "Recording expired. Record again." }
                    val f = upload ?: error("No recording")
                    require(f.length() == uploadLength.toLong()) { "Recording transfer was incomplete" }
                    require(RelayMedia.digest(f.readBytes()) == q.getString("sha256")) { "Recording failed its integrity check" }
                    require(RelayMedia.mime(f) in setOf("audio/wav", "audio/ogg")) { "Recorder must supply WAV or OGG/Opus" }
                    val named = File(f.parentFile, f.nameWithoutExtension + if (RelayMedia.mime(f) == "audio/ogg") ".ogg" else ".wav")
                    check(f.renameTo(named)) { "Could not finalize the recording" }
                    upload = named
                    sealed = true; success()
                }
                "upload_chunk" -> synchronized(this) {
                    require(q.getString("upload") == uploadId) { "Recording expired" }
                    val encoded = q.getString("data"); require(encoded.length <= 32768) { "Audio chunk too large" }
                    val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                    require(bytes.size in 1..24576)
                    append(q.getInt("offset"), bytes); success()
                }
                "download_chunk" -> synchronized(this) {
                    require(q.getString("download") == downloadId && downloadId.isNotEmpty()) { "Audio selection changed" }
                    val file = download ?: error("Choose audio first")
                    val offset = q.getLong("offset"); require(offset >= 0 && offset < file.length())
                    val bytes = RandomAccessFile(file, "r").use { f ->
                        f.seek(offset); ByteArray(minOf(24576L, file.length() - offset).toInt()).also { f.readFully(it) }
                    }
                    success(JSONObject().put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
                }
                "send" -> send(q, done)
                "receipt" -> {
                    val stored = prefs.getString(q.getString("operation"), null)
                    success(stored?.let(::JSONObject) ?: JSONObject().put("state", "unknown").put("detail", "No receipt. Check the conversation before sending again."))
                }
                else -> error("Unknown bridge operation")
            }
        } catch (e: Throwable) { failure(e) }
    }

    private fun fileInfo(file: File): JSONObject {
        downloadId = UUID.randomUUID().toString()
        return JSONObject().put("size", file.length()).put("sha256", RelayMedia.digest(file.readBytes())).put("mime", RelayMedia.mime(file)).put("download", downloadId)
    }

    @Synchronized fun append(offset: Int, bytes: ByteArray) {
        val f = upload ?: error("Start a recording transfer first")
        require(!sealed && offset >= 0 && offset + bytes.size <= uploadLength)
        RandomAccessFile(f, "rw").use { out ->
            if (offset < out.length()) {
                require(offset + bytes.size <= out.length())
                val existing = ByteArray(bytes.size); out.seek(offset.toLong()); out.readFully(existing)
                require(existing.contentEquals(bytes)) { "Conflicting audio chunk" }
            } else { require(offset.toLong() == out.length()) { "Missing audio chunk" }; out.seek(offset.toLong()); out.write(bytes) }
        }
    }

    private fun send(q: JSONObject, done: (JSONObject) -> Unit) {
        val operation = q.getString("operation")
        require(operation.matches(Regex("[A-Za-z0-9-]{16,80}")))
        val prior = prefs.getString(operation, null)
        if (prior != null) { done(JSONObject().put("ok", true).put("value", JSONObject(prior))); return }
        val mode = q.getString("mode"); require(mode in setOf("voice", "file", "text"))
        val target = if (mode == "text") message(q.getString("target")) else draftTarget ?: error("No recording recipient")
        require(NotificationDisplayPreferences.appEnabled(c, target.packageName)) { "This app is disabled in Settings" }
        val text = q.optString("text").trim()
        if (mode == "text") require(text.isNotEmpty() && text.length <= 4000) { "Text reply is empty or too long" }
        val file = if (mode == "text") null else upload?.takeIf { sealed && q.getString("upload") == uploadId } ?: error("Recording is not complete")
        fun receipt(state: String, detail: String) {
            val value = JSONObject().put("state", state).put("detail", detail).put("recipient", target.sender)
            prefs.edit().putString(operation, value.toString()).commit()
            done(JSONObject().put("ok", true).put("value", value))
        }
        // Persist BEFORE delivery. A process death must never cause an automatic resend.
        prefs.edit().putString(operation, JSONObject().put("state", "pending").put("detail", "Sending; check the chat if the connection is lost").toString()).commit()
        if (TelegramVoiceSender.isTelegram(target)) {
            val seconds = if (file != null) runCatching { MediaMetadataRetriever().use { it.setDataSource(file.absolutePath); (it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 1000) / 1000 } }.getOrDefault(1).toInt() else 0
            fun deliver(audio: File?) {
                TelegramClientManager.get(c).sendAiui(target, audio, mode, text, seconds) { result ->
                    result.fold({ receipt("sent", "Sent to ${target.sender}") }, { receipt("check", it.message ?: "Check Telegram before retrying") })
                }
            }
            if (mode == "voice" && file != null && RelayMedia.mime(file) == "audio/wav") {
                OpusVoiceEncoder.encode(c, RelayMedia.uri(c, file).toString(), seconds * 1000L) { result -> result.fold({ deliver(it.file) }, { receipt("failed", it.message ?: "Voice encoding failed") }) }
            } else deliver(file)
        } else {
            if (mode == "text" && VoiceRelayNotificationListener.sendTextReply(target, text)) {
                receipt("handed", "Passed to the exact WhatsApp conversation. Delivery is confirmed in WhatsApp.")
            } else if (file != null && VoiceRelayNotificationListener.sendAudioDataReply(target, RelayMedia.uri(c, file), RelayMedia.mime(file))) {
                receipt("handed", "Audio passed to the exact WhatsApp conversation. WhatsApp decides how it is displayed.")
            } else {
                PhoneHandoff.queue(c, operation, target, file, text)
                receipt("phone", "Ready on your phone. Open Voice Relay, choose Finish reply, and select ${target.sender} in WhatsApp.")
            }
        }
    }
}
