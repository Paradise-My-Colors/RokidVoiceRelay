package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import org.json.JSONObject

data class IncomingMessage(
    val app: String,
    val sender: String,
    val text: String,
    val receivedAt: Long = System.currentTimeMillis(),
)

object PendingMessageStore {
    private const val PREFS = "nexus_plugin_voicerelay"
    private const val KEY_PENDING = "pending_message"
    private const val KEY_LAST_RECORDING_URI = "last_recording_uri"
    private const val KEY_LAST_RECORDING_NAME = "last_recording_name"

    fun put(context: Context, message: IncomingMessage) {
        val json = JSONObject()
            .put("app", message.app)
            .put("sender", message.sender)
            .put("text", message.text)
            .put("receivedAt", message.receivedAt)
        prefs(context).edit().putString(KEY_PENDING, json.toString()).apply()
    }

    fun peek(context: Context): IncomingMessage? {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            IncomingMessage(
                app = json.optString("app", "Message"),
                sender = json.optString("sender", "Unknown"),
                text = json.optString("text", ""),
                receivedAt = json.optLong("receivedAt", System.currentTimeMillis()),
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    fun setLastRecording(context: Context, uri: String, name: String) {
        prefs(context).edit()
            .putString(KEY_LAST_RECORDING_URI, uri)
            .putString(KEY_LAST_RECORDING_NAME, name)
            .apply()
    }

    fun lastRecordingUri(context: Context): String? =
        prefs(context).getString(KEY_LAST_RECORDING_URI, null)

    fun lastRecordingName(context: Context): String? =
        prefs(context).getString(KEY_LAST_RECORDING_NAME, null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
