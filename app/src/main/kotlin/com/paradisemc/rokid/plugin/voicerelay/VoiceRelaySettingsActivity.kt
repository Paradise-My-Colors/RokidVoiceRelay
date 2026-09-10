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
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramAuthStage
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramClientManager
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramSetupActivity

class VoiceRelaySettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var telegramStatus: TextView
    private lateinit var lastRecording: TextView
    private var player: MediaPlayer? = null
    private var testRuntime: VoiceRelayNoticeRuntime? = null

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
        content.addView(text("Prototype v0.4 · Telegram voice-note sending", 15f, false))
        spacer(content, 20)

        content.addView(text("Telegram", 19f, true))
        telegramStatus = text("", 15f, true)
        content.addView(telegramStatus)
        content.addView(
            button("Telegram setup / login") {
                startActivity(Intent(this, TelegramSetupActivity::class.java))
            },
        )

        spacer(content, 20)
        content.addView(text("Notification bridge", 19f, true))
        status = text("", 15f, true)
        content.addView(status)

        content.addView(
            button("Open Android notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )

        content.addView(
            button("Send test message to glasses") {
                testRuntime?.shutdown()
                testRuntime = VoiceRelayNoticeRuntime(applicationContext)
                testRuntime?.show(
                    IncomingMessage(
                        app = "Test",
                        sender = "Voice Relay Test",
                        text = "This test becomes an inbox item. Real Telegram messages can be replied to after Telegram setup.",
                        packageName = packageName,
                        notificationKey = "test-${System.currentTimeMillis()}",
                    ),
                )
            },
        )

        content.addView(
            button("Open Rokid Nexus") {
                val launch = packageManager.getLaunchIntentForPackage("com.anezium.rokidbus.phone")
                if (launch != null) startActivity(launch)
            },
        )

        spacer(content, 22)
        content.addView(text("Last recording", 19f, true))
        lastRecording = text("No recording saved yet.", 14f, false)
        content.addView(lastRecording)

        content.addView(button("Play last recording") { playLastRecording() })
        content.addView(
            button("Open last recording") {
                val uri = PendingMessageStore.lastRecordingUri(this) ?: return@button
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(uri), "audio/wav")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }
            },
        )

        spacer(content, 22)
        content.addView(text("Glasses controls", 19f, true))
        content.addView(
            text(
                "Inbox: Left/Up = previous, Right/Down = next, Tap = record. Recording: Tap = stop. Confirmation: Tap = Send, Up/Left = Retake, Back = Cancel. A Telegram inbox item disappears only after Telegram confirms the send.",
                15f,
                false,
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        TelegramClientManager.get(this).start()
        refreshStatus()
    }

    private fun refreshStatus() {
        val grant = if (hasNotificationAccess()) "ENABLED" else "NOT ENABLED"
        val listener = if (PendingMessageStore.listenerConnected(this)) "CONNECTED" else "NOT CONNECTED"
        val capture = PendingMessageStore.lastCaptured(this)
        val inbox = PendingMessageStore.inbox(this)

        status.text = buildString {
            append("Notification access: $grant\nListener: $listener")
            append("\nPending inbox: ${inbox.size}")
            if (capture != null) {
                append("\nLast captured: ${capture.app} · ${capture.sender}")
                append("\nPackage: ${capture.packageName ?: "unknown"}")
                capture.shortcutId?.let { append("\nConversation shortcut: $it") }
            }
            if (inbox.isNotEmpty()) {
                append("\nNewest pending: ${inbox.first().app} · ${inbox.first().sender}")
            }
        }

        val tg = TelegramClientManager.get(this).status()
        telegramStatus.text = buildString {
            append(
                when (tg.stage) {
                    TelegramAuthStage.READY -> "Telegram: CONNECTED"
                    TelegramAuthStage.NEED_CREDENTIALS -> "Telegram: SETUP REQUIRED"
                    TelegramAuthStage.NEED_PHONE -> "Telegram: PHONE NUMBER REQUIRED"
                    TelegramAuthStage.NEED_CODE -> "Telegram: LOGIN CODE REQUIRED"
                    TelegramAuthStage.NEED_PASSWORD -> "Telegram: 2FA PASSWORD REQUIRED"
                    TelegramAuthStage.NEED_EMAIL -> "Telegram: EMAIL REQUIRED"
                    TelegramAuthStage.NEED_EMAIL_CODE -> "Telegram: EMAIL CODE REQUIRED"
                    TelegramAuthStage.ERROR -> "Telegram: ERROR"
                    else -> "Telegram: STARTING"
                },
            )
            tg.accountLabel?.let { append("\nAccount: $it") }
            append("\n${tg.detail}")
        }

        val name = PendingMessageStore.lastRecordingName(this)
        val target = PendingMessageStore.lastRecordingTarget(this)
        lastRecording.text = if (name == null) {
            "No recording saved yet."
        } else {
            buildString {
                append(name)
                if (target != null) {
                    append("\nTarget: ${target.app} · ${target.sender}")
                    target.shortcutId?.let { append("\nShortcut: $it") }
                }
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        testRuntime?.shutdown()
        testRuntime = null
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
