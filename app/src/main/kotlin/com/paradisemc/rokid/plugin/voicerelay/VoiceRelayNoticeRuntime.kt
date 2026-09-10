package com.paradisemc.rokid.plugin.voicerelay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.audioSession
import com.anezium.rokidbus.client.plugin.surfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject

/** Short-lived Nexus client owned by the Android notification listener. */
class VoiceRelayNoticeRuntime(context: Context) : NexusPluginCallbacks {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var client: NexusPluginClient? = null
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var wavRecorder: WavRecorder? = null
    private var pendingMessage: IncomingMessage? = null
    private var offeredMessage: IncomingMessage? = null
    private var recordingStarted = false
    private var keepRecordingOnStop = true
    private var transitioningFromNotice = false
    private var showGeneration = 0

    private val safetyStop = Runnable {
        if (audio != null) {
            keepRecordingOnStop = true
            audio?.stop()
        }
    }

    fun show(message: IncomingMessage) = onMain {
        pendingMessage = message
        PendingMessageStore.put(appContext, message)
        showGeneration += 1
        val generation = showGeneration

        ensureClient()
        tryShowPending()
        listOf(250L, 750L, 1500L, 3000L, 5000L).forEach { delay ->
            main.postDelayed({
                if (generation == showGeneration && pendingMessage != null) tryShowPending()
            }, delay)
        }
    }

    fun shutdown() = onMain {
        main.removeCallbacksAndMessages(null)
        if (audio != null) {
            keepRecordingOnStop = false
            audio?.stop()
        }
        wavRecorder?.discard()
        wavRecorder = null
        surface?.hide()
        surface = null
        client?.hideNotice()
        client?.close()
        client = null
        pendingMessage = null
        transitioningFromNotice = false
    }

    private fun ensureClient() {
        if (client != null) return
        client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)
    }

    private fun tryShowPending() {
        val message = pendingMessage ?: return
        val currentClient = client ?: return
        if (!currentClient.isApproved) return
        if (!currentClient.hasCapability(PluginCapability.SURFACES)) return
        if (!currentClient.supportsNoticeSurface) return

        val result = currentClient.showNotice(
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
            pendingMessage = null
            PendingMessageStore.clear(appContext)
        }
    }

    override fun onOpen() = Unit
    override fun onClose() = Unit
    override fun onLinkState(state: Int) = onMain { tryShowPending() }

    override fun onRegistrationState(result: Int) = onMain {
        if (result == PluginRegistrationResult.APPROVED) tryShowPending()
    }

    override fun onNoticeAction(id: String) = onMain {
        if (id != ACTION_RECORD || audio != null) return@onMain
        transitioningFromNotice = true
        client?.hideNotice()
        main.postDelayed({
            transitioningFromNotice = false
            beginVoiceRecording()
        }, 120L)
    }

    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {
        if (transitioningFromNotice || audio != null || surface != null) return@onMain
        closeClientIfIdle()
    }

    override fun onInput(event: NexusInputEvent) = onMain {
        if (event.action != KeyEvent.ACTION_DOWN) return@onMain
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (audio != null && recordingStarted) stopAndSave()

            KeyEvent.KEYCODE_BACK -> {
                if (audio != null) cancelRecording() else {
                    surface?.hide()
                    surface = null
                    closeClientIfIdle()
                }
            }
        }
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) = Unit

    private fun beginVoiceRecording() {
        val currentClient = client ?: return
        if (!currentClient.isApproved || !currentClient.hasCapability(PluginCapability.MICROPHONE)) {
            showResultCard("Microphone unavailable", "Grant Microphone access to Voice Relay in Nexus.")
            return
        }

        recordingStarted = false
        keepRecordingOnStop = true
        wavRecorder = null
        surface = currentClient.surfaceSession("recording")
        showRecordingCard("Starting microphone…")

        val session = currentClient.audioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) = onMain {
                if (format.sampleRate != 16_000 || format.channels != 1) {
                    keepRecordingOnStop = false
                    audio?.stop()
                    return@onMain
                }
                wavRecorder = WavRecorder(appContext)
                recordingStarted = true
                showRecordingCard("● Recording…")
                main.removeCallbacks(safetyStop)
                main.postDelayed(safetyStop, 60_000L)
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                wavRecorder?.write(pcm)
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) = onMain {
                main.removeCallbacks(safetyStop)
                val recorder = wavRecorder
                wavRecorder = null
                audio = null
                recordingStarted = false

                if (keepRecordingOnStop && reason == NexusAudioStopReason.RELEASED && recorder != null) {
                    val published = recorder.finishAndPublish()
                    if (published != null) {
                        PendingMessageStore.setLastRecording(
                            appContext,
                            published.uri,
                            published.name,
                            offeredMessage,
                        )
                        showResultCard(
                            "Saved",
                            "${published.name}\nTarget: ${targetDescription(offeredMessage)}",
                        )
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
        })

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
        surface = null
        closeClientIfIdle()
    }

    private fun showRecordingCard(status: String) {
        surface?.showCard(
            NexusCard(
                title = "Voice note",
                lines = listOfNotNull(
                    offeredMessage?.let { "${it.app} · ${it.sender.clean(38)}" },
                    status,
                ),
                footer = "tap stop · back cancel · max 60s",
                handlesBack = true,
            ),
        )
    }

    private fun showResultCard(title: String, detail: String) {
        val currentClient = client ?: return
        surface = surface ?: currentClient.surfaceSession("recording")
        surface?.showCard(
            NexusCard(
                title = title,
                lines = detail.split('\n').map { it.clean(180) }.take(3),
                footer = "back",
                handlesBack = true,
            ),
        )
    }

    private fun closeClientIfIdle() {
        if (audio != null || surface != null) return
        client?.close()
        client = null
        offeredMessage = null
    }

    private fun targetDescription(message: IncomingMessage?): String = when (message) {
        null -> "unknown"
        else -> "${message.app} · ${message.sender}"
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

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val PLUGIN_ID = "voicerelay"
        const val ACTION_RECORD = "record_voice_note"
    }
}
