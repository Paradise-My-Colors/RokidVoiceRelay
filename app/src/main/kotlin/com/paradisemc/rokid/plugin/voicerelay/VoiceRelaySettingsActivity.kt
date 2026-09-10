package com.paradisemc.rokid.plugin.voicerelay

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class VoiceRelaySettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var lastRecording: TextView
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Voice Relay"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(36))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(text("Rokid Voice Relay", 26f, true))
        content.addView(text("Prototype v0.1 · raw voice-note recording", 15f, false))
        spacer(content, 20)

        content.addView(text("What this version tests", 19f, true))
        content.addView(text(
            "Telegram/WhatsApp notification → 8-second Rokid popup → tap mic → record from the glasses → tap to stop → save WAV in Music/RokidVoiceRelay. It does not send the recording back to the chat yet.",
            15f,
            false,
        ))
        spacer(content, 20)

        status = text("", 15f, true)
        content.addView(status)

        content.addView(button("Open Android notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        content.addView(button("Send test message to glasses") {
            VoiceRelayPluginService.deliverIncoming(
                this,
                IncomingMessage(
                    app = "Test",
                    sender = "Voice Relay Test",
                    text = "Tap the microphone action within 8 seconds to record a voice note.",
                ),
            )
        })

        content.addView(button("Open Rokid Nexus") {
            val launch = packageManager.getLaunchIntentForPackage("com.anezium.rokidbus.phone")
            if (launch != null) startActivity(launch)
        })

        spacer(content, 22)
        content.addView(text("Last test recording", 19f, true))
        lastRecording = text("No recording saved yet.", 14f, false)
        content.addView(lastRecording)

        content.addView(button("Play last recording") { playLastRecording() })
        content.addView(button("Open last recording") {
            val uri = PendingMessageStore.lastRecordingUri(this) ?: return@button
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(uri), "audio/wav")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
        })

        spacer(content, 22)
        content.addView(text("Before testing", 19f, true))
        content.addView(text(
            "1. Approve Voice Relay in Rokid Nexus Plugin access.\n" +
                "2. Grant Surfaces and Microphone.\n" +
                "3. Enable Voice Relay under Android Notification access.\n" +
                "4. Wear and connect the glasses.\n" +
                "5. Use the test button above, then try a real Telegram or WhatsApp message.",
            15f,
            false,
        ))
    }

    override fun onResume() {
        super.onResume()
        status.text = if (hasNotificationAccess()) {
            "Notification access: ENABLED"
        } else {
            "Notification access: NOT ENABLED"
        }
        val name = PendingMessageStore.lastRecordingName(this)
        lastRecording.text = name ?: "No recording saved yet."
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun playLastRecording() {
        val uri = PendingMessageStore.lastRecordingUri(this) ?: return
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(this@VoiceRelaySettingsActivity, Uri.parse(uri))
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            prepareAsync()
        }
    }

    private fun text(value: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

    private fun spacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(TextView(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
