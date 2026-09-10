package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IncomingMessage(
    val app: String,
    val sender: String,
    val text: String,
    val packageName: String? = null,
    val notificationKey: String? = null,
    val shortcutId: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
) {
    /** Prefer conversation identity so repeated messages update one inbox row. */
    fun stableKey(): String = shortcutId?.let { "${packageName.orEmpty()}:shortcut:$it" }
        ?: notificationKey
        ?: "${packageName.orEmpty()}:${sender.lowercase()}"
}

object PendingMessageStore {
    private const val PREFS = "nexus_plugin_voicerelay"
    private const val KEY_PENDING = "pending_message"
    private const val KEY_INBOX = "message_inbox_v1"
    private const val KEY_LAST_CAPTURED = "last_captured_message"
    private const val KEY_LISTENER_CONNECTED = "listener_connected"
    private const val KEY_LAST_RECORDING_URI = "last_recording_uri"
    private const val KEY_LAST_RECORDING_NAME = "last_recording_name"
    private const val KEY_LAST_RECORDING_TARGET = "last_recording_target"
    private const val MAX_INBOX = 30

    @Synchronized
    fun put(context: Context, message: IncomingMessage) {
        val list = inbox(context).toMutableList()
        val key = message.stableKey()
        list.removeAll { it.stableKey() == key }
        list.add(0, message)
        saveInbox(context, list.take(MAX_INBOX))
        prefs(context).edit().putString(KEY_PENDING, toJson(message).toString()).apply()
    }

    fun peek(context: Context): IncomingMessage? =
        prefs(context).getString(KEY_PENDING, null)?.let(::fromJson)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    @Synchronized
    fun inbox(context: Context): List<IncomingMessage> {
        val raw = prefs(context).getString(KEY_INBOX, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = fromJson(array.getJSONObject(i).toString())
                    if (item != null) add(item)
                }
            }.sortedByDescending { it.receivedAt }
        }.getOrDefault(emptyList())
    }

    fun inboxCount(context: Context): Int = inbox(context).size

    @Synchronized
    fun removeByNotificationKey(context: Context, notificationKey: String?) {
        if (notificationKey.isNullOrBlank()) return
        val list = inbox(context).filterNot { it.notificationKey == notificationKey }
        saveInbox(context, list)
        val pending = peek(context)
        if (pending?.notificationKey == notificationKey) clear(context)
    }

    @Synchronized
    fun remove(context: Context, message: IncomingMessage?) {
        message ?: return
        val key = message.stableKey()
        saveInbox(context, inbox(context).filterNot { it.stableKey() == key })
        val pending = peek(context)
        if (pending?.stableKey() == key) clear(context)
    }

    fun setLastCaptured(context: Context, message: IncomingMessage) {
        prefs(context).edit().putString(KEY_LAST_CAPTURED, toJson(message).toString()).apply()
    }

    fun lastCaptured(context: Context): IncomingMessage? =
        prefs(context).getString(KEY_LAST_CAPTURED, null)?.let(::fromJson)

    fun setListenerState(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTENER_CONNECTED, connected).apply()
    }

    fun listenerConnected(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTENER_CONNECTED, false)

    fun setLastRecording(
        context: Context,
        uri: String,
        name: String,
        target: IncomingMessage? = null,
    ) {
        val edit = prefs(context).edit()
            .putString(KEY_LAST_RECORDING_URI, uri)
            .putString(KEY_LAST_RECORDING_NAME, name)
        if (target != null) edit.putString(KEY_LAST_RECORDING_TARGET, toJson(target).toString())
        else edit.remove(KEY_LAST_RECORDING_TARGET)
        edit.apply()
    }

    fun lastRecordingUri(context: Context): String? =
        prefs(context).getString(KEY_LAST_RECORDING_URI, null)

    fun lastRecordingName(context: Context): String? =
        prefs(context).getString(KEY_LAST_RECORDING_NAME, null)

    fun lastRecordingTarget(context: Context): IncomingMessage? =
        prefs(context).getString(KEY_LAST_RECORDING_TARGET, null)?.let(::fromJson)

    fun clearLastRecording(context: Context) {
        prefs(context).edit()
            .remove(KEY_LAST_RECORDING_URI)
            .remove(KEY_LAST_RECORDING_NAME)
            .remove(KEY_LAST_RECORDING_TARGET)
            .apply()
    }

    private fun saveInbox(context: Context, messages: List<IncomingMessage>) {
        val array = JSONArray()
        messages.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_INBOX, array.toString()).apply()
    }

    private fun toJson(message: IncomingMessage) = JSONObject()
        .put("app", message.app)
        .put("sender", message.sender)
        .put("text", message.text)
        .put("packageName", message.packageName)
        .put("notificationKey", message.notificationKey)
        .put("shortcutId", message.shortcutId)
        .put("receivedAt", message.receivedAt)

    private fun fromJson(raw: String): IncomingMessage? = runCatching {
        val json = JSONObject(raw)
        IncomingMessage(
            app = json.optString("app", "Message"),
            sender = json.optString("sender", "Unknown"),
            text = json.optString("text", ""),
            packageName = json.optNullableString("packageName"),
            notificationKey = json.optNullableString("notificationKey"),
            shortcutId = json.optNullableString("shortcutId"),
            receivedAt = json.optLong("receivedAt", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
