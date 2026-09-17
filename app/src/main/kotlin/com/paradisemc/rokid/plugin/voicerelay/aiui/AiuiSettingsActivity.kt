package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import com.paradisemc.rokid.plugin.voicerelay.*
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramSetupActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class AiuiSettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable { override fun run() {
        status.text = AiuiBridgeService.status + if (AiuiBridgeService.pendingPeer != null) "\nA device is waiting for approval." else ""
        handler.postDelayed(this, 1000)
    } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 40) }
        setContentView(ScrollView(this).apply { addView(content) })
        fun label(value: String, size: Float = 16f) { content.addView(TextView(this).apply { text = value; textSize = size; setPadding(0, 16, 0, 12) }) }
        fun button(value: String, action: () -> Unit) { content.addView(Button(this).apply { text = value; setOnClickListener { action() } }) }
        label("Voice Relay · AIUI", 27f)
        label("0.9.1 test build · Pairing and runtime fixes")
        label("Installs alongside Voice Relay v0.8. Enable notification access and Telegram login here. To avoid duplicate alerts, disable notification access for the older Voice Relay and enable only Voice Relay AIUI. Keep the old app installed if you want to return to it.")
        status = TextView(this).apply { textSize = 16f }; content.addView(status)
        button("1. Enable notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("2. Start Bluetooth bridge") { startBridge(false) }
        button("3. Pair glasses (first time)") { startBridge(true) }
        button("Restart Bluetooth bridge") { startBridge(false, true) }
        button("Approve glasses") {
            AiuiBridgeService.instance?.approve()
            status.text = AiuiBridgeService.status
        }
        label("Open Voice Relay 0.9.1 on your glasses and tap Connect once. Keep that page open. Tap Approve glasses here, then confirm any Android Bluetooth pairing prompt. The glasses continue automatically; Back cancels.")
        button("Telegram setup / login") { startActivity(Intent(this, TelegramSetupActivity::class.java)) }
        button("Finish reply on phone") { PhoneHandoff.openLatest(this) }
        label("Notification settings", 22f)
        val settings = BridgeApi(this).settings()
        val options = listOf("telegram_enabled" to "Telegram notifications", "whatsapp_enabled" to "WhatsApp notifications",
            "hide_when_phone_unlocked" to "Hide alerts while phone is unlocked", "respect_dnd" to "Respect Do Not Disturb",
            "respect_phone_silent" to "Respect phone Silent mode", "nexus_notices" to "Nexus popups when AIUI is closed")
        for ((key, title) in options) content.addView(Switch(this).apply {
            text = title; isChecked = settings.getBoolean(key); setPadding(0, 14, 0, 14)
            setOnCheckedChangeListener { _, value -> NotificationDisplayPreferences.setOption(this@AiuiSettingsActivity, key, value) }
        })
        label("Silent, DND and unlocked filters quiet automatic alerts. Saved messages remain available when you open the inbox. Disabling Telegram or WhatsApp hides that app and stops new capture.")
        label("WhatsApp audio", 22f)
        label("If Listen says audio is unavailable, share the voice message from WhatsApp to Voice Relay and choose its conversation. Replies that cannot be sent directly are held for you under Finish reply on phone. Select the recipient in WhatsApp and press Send there.")
        button("Android battery settings") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("Existing Nexus settings") { startActivity(Intent(this, VoiceRelaySettingsActivity::class.java)) }
        button("Stop Bluetooth bridge") { stopService(Intent(this, AiuiBridgeService::class.java)); AiuiBridgeService.status = "Bridge stopped" }
        button("Forget approved glasses") {
            AlertDialog.Builder(this).setMessage("Require approval again for your glasses?").setPositiveButton("Forget") { _, _ ->
                getSharedPreferences("aiui-bridge", 0).edit().remove("trusted").apply()
                stopService(Intent(this, AiuiBridgeService::class.java)); AiuiBridgeService.status = "Glasses forgotten"
            }.setNegativeButton("Cancel", null).show()
        }
    }
    private var pairingAfterPermission = false
    private var restartAfterPermission = false
    private fun startBridge(pair: Boolean, restart: Boolean = false) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) permissions.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pairingAfterPermission = pair; restartAfterPermission = restart; requestPermissions(missing.toTypedArray(), 91); return }
        startForegroundService(Intent(this, AiuiBridgeService::class.java).apply { if (restart) action = "restart" else if (pair) action = "pair" })
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 91 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startBridge(pairingAfterPermission, restartAfterPermission)
        else status.text = "Allow Nearby devices and notifications, then tap Start again."
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}

object PhoneHandoff {
    fun queue(c: Context, id: String, target: IncomingMessage, file: File?, text: String) {
        val value = JSONObject().put("package", target.packageName).put("recipient", target.sender).put("file", file?.absolutePath).put("text", text)
        c.getSharedPreferences("aiui-handoff", 0).edit().putString(id, value.toString()).putString("latest", id).commit()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("aiui-handoff", "Replies waiting for you", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(c, PhoneHandoffActivity::class.java).putExtra("id", id)
        val pi = PendingIntent.getActivity(c, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id.hashCode(), Notification.Builder(c, "aiui-handoff").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Finish reply to ${target.sender}").setContentText("Choose the chat and send in WhatsApp").setContentIntent(pi).setAutoCancel(true).build())
    }
    fun openLatest(c: Context) {
        val id = c.getSharedPreferences("aiui-handoff", 0).getString("latest", null)
        if (id == null) { Toast.makeText(c, "No reply is waiting", Toast.LENGTH_SHORT).show(); return }
        c.startActivity(Intent(c, PhoneHandoffActivity::class.java).putExtra("id", id))
    }
}

class PhoneHandoffActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val raw = getSharedPreferences("aiui-handoff", 0).getString(id, null) ?: run { finish(); return }
        val q = JSONObject(raw)
        AlertDialog.Builder(this).setTitle("Reply to ${q.optString("recipient")}")
            .setMessage("Choose this conversation in WhatsApp, review the reply, then press Send. Voice Relay cannot confirm delivery from this sharing screen.")
            .setPositiveButton("Open WhatsApp") { _, _ ->
                runCatching {
                    val share = Intent(Intent.ACTION_SEND).setPackage(q.getString("package"))
                    if (!q.isNull("file")) {
                        val f = File(q.getString("file")); require(f.isFile) { "Recording expired" }
                        val uri = RelayMedia.uri(this, f)
                        share.type = RelayMedia.mime(f); share.putExtra(Intent.EXTRA_STREAM, uri)
                        share.clipData = ClipData.newUri(contentResolver, "Voice reply", uri)
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else { share.type = "text/plain"; share.putExtra(Intent.EXTRA_TEXT, q.optString("text")) }
                    startActivity(share)
                }.onFailure { Toast.makeText(this, it.message ?: "Could not open WhatsApp", Toast.LENGTH_LONG).show() }
                finish()
            }.setNegativeButton("Keep for later") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}

class ImportVoiceActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (intent.action != Intent.ACTION_SEND || uri == null || !intent.type.orEmpty().startsWith("audio/")) { finish(); return }
        val choices = PendingMessageStore.inbox(this).filter { it.packageName in setOf("com.whatsapp", "com.whatsapp.w4b") }
        if (choices.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Receive a WhatsApp notification from this conversation first, then share its voice message again.")
                .setPositiveButton("OK") { _, _ -> finish() }.show(); return
        }
        AlertDialog.Builder(this).setTitle("Which WhatsApp conversation?")
            .setItems(choices.map { "${it.sender} · ${it.app}" }.toTypedArray()) { _, index ->
                val progress = TextView(this).apply { text = "Saving voice message…"; setPadding(40, 40, 40, 40) }; setContentView(progress)
                val io = Executors.newSingleThreadExecutor()
                io.execute {
                    val result = runCatching {
                        val file = RelayMedia.cache(this, uri)
                        PendingMessageStore.put(this, choices[index].copy(text = "Shared voice message", voiceMessage = true,
                            mediaUri = RelayMedia.uri(this, file).toString(), mediaMimeType = RelayMedia.mime(file), receivedAt = System.currentTimeMillis()))
                    }
                    runOnUiThread { Toast.makeText(this, if (result.isSuccess) "Available under Listen on your glasses" else "Audio could not be shared: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show(); finish() }
                    io.shutdown()
                }
            }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
