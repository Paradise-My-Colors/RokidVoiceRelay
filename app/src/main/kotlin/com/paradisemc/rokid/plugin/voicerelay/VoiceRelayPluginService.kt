package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession

class VoiceRelayPluginService : NexusPluginService() {

    private val main = Handler(Looper.getMainLooper())
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var wavRecorder: WavRecorder? = null
    private var recordingStarted = false
    private var keepRecordingOnStop = true
    private var offeredMessage: IncomingMessage? = null

    private val safetyStop = Runnable {
        if (audio != null) {
            keepRecordingOnStop = true
            audio?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        listOf(250L, 750L, 1500L, 3000L).forEach { delay ->
            main.postDelayed({ tryShowPendingNotice() }, delay)
        }
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
        if (tryShowPendingNotice()) return
        surface = nexusSurfaceSession("main")
        surface?.showCard(
            NexusCard(
                title = "Voice Relay",
                lines = listOf(
                    "Ready for Telegram / WhatsApp",
                    "Incoming messages will appear as an 8-second band.",
                ),
                footer = "back",
                handlesBack = true,
            ),
        )
    }

    override fun onNexusClose() {
        if (audio != null) {
            keepRecordingOnStop = false
            audio?.stop()
        }
        surface?.hide()
        surface = null
    }

    override fun onNexusNoticeAction(id: String) {
        if (id != ACTION_RECORD || audio != null) return
        nexusClient?.hideNotice()
        main.postDelayed({ beginVoiceRecording() }, 120L)
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (audio != null && recordingStarted) stopAndSave()
            }

            KeyEvent.KEYCODE_BACK -> {
                if (audio != null) cancelRecording() else surface?.hide()
            }
        }
    }

    private fun tryShowPendingNotice(): Boolean {
        val message = PendingMessageStore.peek(this) ?: return false
        val client = nexusClient ?: return false
        if (!client.supportsNoticeSurface) return false

        val result = client.showNotice(
            NexusNotice(
                title = message.sender.clean(32),
                body = message.text.clean(1024),
                footer = "${message.app.clean(22)} · tap mic",
                actions = listOf(
                    NexusNoticeAction(
                        id = ACTION_RECORD,
                        glyph = "mic",
                        label = "Voice note",
                    ),
                ),
                ttlMs = 8_000L,
                wakeDisplay = true,
            ),
        )

        if (result == NexusSdkResult.SENT) {
            offeredMessage = message
            PendingMessageStore.clear(this)
            return true
        }
        return false
    }

    private fun beginVoiceRecording() {
        if (audio != null) return
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
                        )
                        showResultCard("Saved", published.name)
                    } else {
                        showResultCard("Save failed", "The recording could not be published.")
                    }
                } else {
                    recorder?.discard()
                    if (reason != NexusAudioStopReason.RELEASED) {
                        showResultCard("Recording stopped", humanReason(reason))
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
        surface?.hide()
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
        surface = surface ?: nexusSurfaceSession("recording")
        surface?.showCard(
            NexusCard(
                title = title,
                lines = listOf(detail.clean(180)),
                footer = "back",
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
        private const val ACTION_RECORD = "record_voice_note"

        @Volatile
        private var current: VoiceRelayPluginService? = null

        fun deliverIncoming(context: Context, message: IncomingMessage) {
            PendingMessageStore.put(context, message)
            val active = current
            if (active != null) {
                active.main.post { active.tryShowPendingNotice() }
                return
            }

            runCatching {
                context.startService(Intent(context, VoiceRelayPluginService::class.java))
            }
        }
    }
}
