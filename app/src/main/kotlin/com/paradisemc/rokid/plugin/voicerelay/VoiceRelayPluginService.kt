package com.paradisemc.rokid.plugin.voicerelay

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class VoiceRelayPluginService : NexusPluginService() {

    private val main = Handler(Looper.getMainLooper())
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var wavRecorder: WavRecorder? = null
    private var recordingStarted = false
    private var keepRecordingOnStop = true
    private var offeredMessage: IncomingMessage? = null
    private var inboxIndex = 0
    private var showingInbox = false

    private val safetyStop = Runnable {
        if (audio != null) {
            keepRecordingOnStop = true
            audio?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        main.removeCallbacksAndMessages(null)
        if (audio != null) {
            keepRecordingOnStop = false
            audio?.stop()
        }
        wavRecorder?.discard()
        wavRecorder = null
        super.onDestroy()
    }

    override fun onNexusOpen() {
        showInbox()
    }

    override fun onNexusClose() {
        if (audio != null) {
            keepRecordingOnStop = false
            audio?.stop()
        }
        surface?.hide()
        surface = null
        showingInbox = false
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return

        if (audio != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> if (recordingStarted) stopAndSave()
                KeyEvent.KEYCODE_BACK -> cancelRecording()
            }
            return
        }

        if (!showingInbox) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) showInbox()
            return
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> moveInbox(-1)

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveInbox(1)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                val messages = PendingMessageStore.inbox(this)
                if (messages.isNotEmpty()) {
                    inboxIndex = inboxIndex.coerceIn(0, messages.lastIndex)
                    offeredMessage = messages[inboxIndex]
                    beginVoiceRecording()
                }
            }

            KeyEvent.KEYCODE_BACK -> surface?.hide()
        }
    }

    private fun moveInbox(delta: Int) {
        val messages = PendingMessageStore.inbox(this)
        if (messages.isEmpty()) {
            inboxIndex = 0
            showInbox()
            return
        }
        inboxIndex = (inboxIndex + delta).coerceIn(0, messages.lastIndex)
        showInbox()
    }

    private fun showInbox() {
        showingInbox = true
        surface = surface ?: nexusSurfaceSession("main")
        val messages = PendingMessageStore.inbox(this)
        if (messages.isEmpty()) {
            inboxIndex = 0
            offeredMessage = null
            surface?.showCard(
                NexusCard(
                    title = "Voice Relay Inbox",
                    lines = listOf(
                        "No pending messages.",
                        "New Telegram / WhatsApp notifications will appear here after the popup closes.",
                    ),
                    footer = "back",
                    handlesBack = true,
                ),
            )
            return
        }

        inboxIndex = inboxIndex.coerceIn(0, messages.lastIndex)
        val message = messages[inboxIndex]
        offeredMessage = message
        surface?.showCard(
            NexusCard(
                title = message.sender.clean(42),
                lines = listOf(
                    "${inboxIndex + 1}/${messages.size} · ${message.app.clean(24)}",
                    message.text.clean(220),
                ),
                footer = "←/↑ previous · →/↓ next · tap voice reply",
                handlesBack = true,
            ),
        )
    }

    private fun beginVoiceRecording() {
        if (audio != null || offeredMessage == null) return
        showingInbox = false
        recordingStarted = false
        keepRecordingOnStop = true
        wavRecorder = null

        surface = nexusSurfaceSession("recording")
        showRecordingCard("Starting microphone…")

        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                if (format.sampleRate != 16_000 || format.channels != 1) {
                    keepRecordingOnStop = false
                    audio?.stop()
                    return
                }
                wavRecorder = WavRecorder(this@VoiceRelayPluginService)
                recordingStarted = true
                showRecordingCard("● Recording…")
                main.removeCallbacks(safetyStop)
                main.postDelayed(safetyStop, 60_000L)
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                wavRecorder?.write(pcm)
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                main.removeCallbacks(safetyStop)
                val recorder = wavRecorder
                wavRecorder = null
                audio = null
                recordingStarted = false

                if (keepRecordingOnStop && reason == NexusAudioStopReason.RELEASED && recorder != null) {
                    val published = recorder.finishAndPublish()
                    if (published != null) {
                        PendingMessageStore.setLastRecording(
                            this@VoiceRelayPluginService,
                            published.uri,
                            published.name,
                            offeredMessage,
                        )
                        showResultCard(
                            "Voice note saved",
                            "Target: ${offeredMessage?.app} · ${offeredMessage?.sender}\nTelegram sending is the next step.",
                        )
                    } else {
                        showResultCard("Save failed", "The recording could not be published.")
                    }
                } else {
                    recorder?.discard()
                    if (reason != NexusAudioStopReason.RELEASED) {
                        showResultCard("Recording stopped", humanReason(reason))
                    } else {
                        showInbox()
                    }
                }
            }
        }) ?: run {
            showResultCard("Microphone unavailable", "Could not create a Nexus audio session.")
            return
        }

        audio = session
        when (val result = session.start()) {
            NexusSdkResult.SENT -> Unit
            else -> {
                audio = null
                wavRecorder?.discard()
                wavRecorder = null
                showResultCard("Microphone unavailable", result.toString())
            }
        }
    }

    private fun stopAndSave() {
        keepRecordingOnStop = true
        audio?.stop()
    }

    private fun cancelRecording() {
        keepRecordingOnStop = false
        audio?.stop()
        wavRecorder?.discard()
        wavRecorder = null
        main.postDelayed({ showInbox() }, 150L)
    }

    private fun showRecordingCard(status: String) {
        val target = offeredMessage
        surface?.showCard(
            NexusCard(
                title = "Voice note",
                lines = listOfNotNull(
                    target?.let { "${it.app} · ${it.sender.clean(38)}" },
                    status,
                ),
                footer = "tap stop · back cancel · max 60s",
                handlesBack = true,
            ),
        )
    }

    private fun showResultCard(title: String, detail: String) {
        showingInbox = false
        surface = surface ?: nexusSurfaceSession("recording")
        surface?.showCard(
            NexusCard(
                title = title,
                lines = detail.split('\n').map { it.clean(180) }.take(3),
                footer = "back → inbox",
                handlesBack = true,
            ),
        )
    }

    private fun humanReason(reason: NexusAudioStopReason): String = when (reason) {
        NexusAudioStopReason.DENIED_BUSY -> "Microphone is busy in another Nexus feature."
        NexusAudioStopReason.DENIED_NO_LINK -> "The glasses are not connected."
        NexusAudioStopReason.DENIED_START_FAILED -> "The glasses microphone could not start."
        NexusAudioStopReason.DENIED_NOT_GRANTED -> "Microphone access is not granted."
        NexusAudioStopReason.REVOKED -> "Microphone access or the glasses link was lost."
        NexusAudioStopReason.ERROR -> "Nexus reported an audio error."
        NexusAudioStopReason.RELEASED -> "Stopped."
    }

    private fun String.clean(max: Int): String =
        replace(Regex("[\\r\\n]+"), " ").trim().ifBlank { "Message" }.take(max)

    companion object {
        @Volatile
        private var current: VoiceRelayPluginService? = null

        fun notifyInboxChanged() {
            val active = current ?: return
            active.main.post {
                if (active.showingInbox && active.audio == null) active.showInbox()
            }
        }
    }
}
