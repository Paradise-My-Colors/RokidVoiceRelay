package com.paradisemc.rokid.plugin.voicerelay.aiui

import android.app.*
import android.content.*
import android.os.*
import com.paradisemc.rokid.plugin.voicerelay.link.LinkServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LinkBridgeService : Service() {
    private var link: LinkServer? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this
        stopService(Intent(this, AiuiBridgeService::class.java))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice-link-network", "Voice Relay Link", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, AiuiSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(902, Notification.Builder(this, "voice-link-network").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Voice Relay Link running").setContentText("Private connection over Wi-Fi / phone hotspot").setContentIntent(open).setOngoing(true).build())
        startLink()
    }
    private fun startLink() {
        try {
            RelayMedia.cleanup(this)
            val api = BridgeApi(this)
            link = LinkServer(LinkSetup.key(this), 8766, { request ->
                activeUntil = SystemClock.elapsedRealtime() + 20_000
                val answer = CompletableFuture<JSONObject>()
                api.execute(request) { answer.complete(it) }
                answer.get(90, TimeUnit.SECONDS)
            }, { value -> status = value }).also { it.start() }
        } catch (e: Exception) { status = "Could not start phone link: ${e.message}"; stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "restart") { link?.close(); activeUntil = 0; startLink() }
        return START_STICKY
    }
    override fun onDestroy() { link?.close(); link = null; instance = null; activeUntil = 0; super.onDestroy() }
    companion object {
        @Volatile var instance: LinkBridgeService? = null
        @Volatile var status = "Phone link stopped"
        @Volatile private var activeUntil = 0L
        fun hasActivePage() = SystemClock.elapsedRealtime() < activeUntil
    }
}

object LinkSetup {
    @Synchronized fun key(c: Context): ByteArray {
        val p = c.getSharedPreferences("voice-link", 0)
        val saved = p.getString("key", null)
        if (saved != null && saved.matches(Regex("[a-f0-9]{64}"))) return LinkServer.unhex(saved)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        check(p.edit().putString("key", LinkServer.hex(key)).commit()) { "Could not save phone setup" }
        return key
    }
    fun endpoints(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback &&
            !it.name.matches(Regex("(?i)(rmnet|ccmni|pdp|tun|ipsec|dummy|lo).*")) }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.map { "http://${it.hostAddress}:8766" }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun export(c: Context, output: java.io.OutputStream) {
        val addresses = endpoints(); check(addresses.isNotEmpty()) { "Connect the phone to Wi-Fi or enable its hotspot, then export again." }
        val profile = JSONObject().put("key", LinkServer.hex(key(c))).put("endpoints", JSONArray(addresses)).put("version", 1)
        val prefs = c.getSharedPreferences("voice-link", 0)
        val files = listOf("app.json", "app.js", "AGENTS.md", "README.md", "THIRD_PARTY_LICENSES.txt", "pages/link/home.ink")
        ZipOutputStream(output).use { zip ->
            for (name in files) {
                var bytes = c.assets.open("link-template/$name").use { it.readBytes() }
                if (name.endsWith(".ink")) {
                    val page = String(bytes, Charsets.UTF_8)
                    val marker = "\"__VOICE_RELAY_PHONE_PROFILE__\""
                    check(page.split(marker).size == 2) { "Setup template is incomplete" }
                    bytes = page.replace(marker, profile.toString()).toByteArray(Charsets.UTF_8)
                }
                zip.putNextEntry(ZipEntry("voice-relay-link/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
        prefs.edit().putString("exported_addresses", addresses.joinToString("\n")).apply()
    }
    fun networkHint(c: Context): String {
        val addresses = endpoints()
        if (addresses.isEmpty()) return "Connect this phone to Wi-Fi or enable its hotspot. Connect the glasses to that same network."
        val previous = c.getSharedPreferences("voice-link", 0).getString("exported_addresses", null)
        return addresses.joinToString("\n") + if (previous != null && previous != addresses.joinToString("\n"))
            "\nPhone address changed. Export a new glasses setup ZIP and sync it." else "\nUse the same Wi-Fi, or connect the glasses to this phone's hotspot."
    }
}
