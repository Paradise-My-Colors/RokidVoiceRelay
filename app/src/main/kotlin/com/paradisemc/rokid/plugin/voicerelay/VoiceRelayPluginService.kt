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
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramAuthStage
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramClientManager
import com.paradisemc.rokid.plugin.voicerelay.telegram.TelegramVoiceSender
import com.paradisemc.rokid.plugin.voicerelay.whatsapp.WhatsAppVoiceSender

class VoiceRelayPluginService : NexusPluginService() {

    private val main = Handler(Looper.getMainLooper())
    private var surface: NexusSurfaceSession? = null
    private var audio: NexusAudioSession? = null
    private var wavRecorder: WavRecorder? = null
    private var recordingStarted = false
    private var keepRecordingOnStop = true
    private var offeredMessage: IncomingMessage? = null
    private var pendingRecording: PublishedRecording? = null
    private var inboxIndex = 0
    private var showingInbox = false
    private var sending = false
    private var playing = false
    private var playbackFinished = false
    private var showingReplyMenu = false
    private var speech: NexusSpeechSession? = null
    private var dictatedText: String? = null
    private var pluginOpen = false
    private var openGeneration = 0

    private val safetyStop = Runnable {
        if (audio != null) {
            keepRecordingOnStop = true
            audio?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        TelegramClientManager.get(this).start()
    }

    override fun onDestroy() {
        if (current === this) current = null
        openGeneration += 1
        main.removeCallbacksAndMessages(null)
        VoicePlaybackManager.stop()
        speech?.stop()
        speech = null
        if (audio != null) {
            keepRecordingOnStop = false
            audio?.stop()
        }
        wavRecorder?.discard()
        wavRecorder = null
        super.onDestroy()
    }

    override fun onNexusOpen() {
        pluginOpen = true
        openGeneration += 1
        val generation = openGeneration
        showInbox()
        OPEN_RETRY_DELAYS_MS.forEach { delay ->
            main.postDelayed({
                if (pluginOpen && generation == openGeneration &&
                    audio == null && pendingRecording == null && !sending && !playing
                ) {
                    showInbox()
                }
            }, delay)
        }
    }

    override fun onNexusLinkState(state: Int) {
        if (pluginOpen && audio == null && pendingRecording == null && !sending && !playing) {
            main.post { showInbox() }
        }
    }

    override fun onNexusClose() {
        pluginOpen = false
        openGeneration += 1
        VoicePlaybackManager.stop()
        speech?.stop()
        speech = null
        dictatedText = null
        showingReplyMenu = false
        playing = false
        playbackFinished = false
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

        if (speech != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                speech?.stop()
                speech = null
                showReplyMenu()
            }
            return
        }

        if (dictatedText != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> sendDictatedText()
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> beginDictation()
                KeyEvent.KEYCODE_BACK -> {
                    dictatedText = null
                    showReplyMenu()
                }
            }
            return
        }

        if (showingReplyMenu) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> beginVoiceRecording()
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> beginDictation()
                KeyEvent.KEYCODE_BACK -> showInbox()
            }
            return
        }

        if (playing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                VoicePlaybackManager.stop()
                playing = false
                showInbox()
            }
            return
        }

        if (audio != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> if (recordingStarted) stopAndSave()
                KeyEvent.KEYCODE_BACK -> cancelActiveRecording()
            }
            return
        }

        if (sending) return

        if (playbackFinished) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    playbackFinished = false
                    showReplyMenu()
                }
                KeyEvent.KEYCODE_BACK -> {
                    playbackFinished = false
                    showInbox()
                }
            }
            return
        }

        if (pendingRecording != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> sendPendingRecording(asFile = false)

                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> sendPendingRecording(asFile = true)

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> retakeRecording()

                KeyEvent.KEYCODE_BACK -> discardPendingAndReturn()
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
                    if (offeredMessage?.voiceMessage == true) playVoiceMessage(offeredMessage!!)
                    else showReplyMenu()
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                surface?.hide()
                surface = null
                showingInbox = false
            }
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

    private fun showInbox(): NexusSdkResult {
        showingInbox = true
        showingReplyMenu = false
        dictatedText = null
        sending = false
        playbackFinished = false
        pendingRecording = null
        surface = surface ?: nexusSurfaceSession("main")
        val session = surface ?: return NexusSdkResult.NOT_REGISTERED
        val messages = PendingMessageStore.inbox(this)
        val card = if (messages.isEmpty()) {
            inboxIndex = 0
            offeredMessage = null
            NexusCard(
                title = "Voice Relay Inbox",
                lines = listOf(
                    "No pending messages.",
                    telegramStatusLine(),
                ),
                footer = "Bluetooth/Nexus link · back",
                handlesBack = true,
            )
        } else {
            inboxIndex = inboxIndex.coerceIn(0, messages.lastIndex)
            val message = messages[inboxIndex]
            offeredMessage = message
            NexusCard(
                title = message.sender.clean(42),
                lines = listOf(
                    "${inboxIndex + 1}/${messages.size} · ${message.app.clean(24)}",
                    message.text.clean(220),
                ),
                footer = if (message.voiceMessage) {
                    "←/↑ prev · →/↓ next · tap play"
                } else {
                    "←/↑ prev · →/↓ next · tap voice reply"
                },
                handlesBack = true,
            )
        }
        return session.showCard(card)
    }

    private fun playVoiceMessage(target: IncomingMessage) {
        showingInbox = false
        playing = true
        playbackFinished = false
        showResultCard(
            "Playing voice…",
            "${target.app} · ${target.sender}\nBluetooth audio only; phone speaker fallback is blocked.",
        )
        VoicePlaybackManager.play(this, target) { result ->
            main.post {
                playing = false
                playbackFinished = true
                result.fold(
                    onSuccess = {
                        showResultCard("Finished", "Voice message played.\nTap for reply options · back inbox")
                    },
                    onFailure = { error ->
                        showResultCard("Playback unavailable", "${error.message ?: "Could not play this voice message."}\nTap for reply options")
                    },
                )
            }
        }
    }

    private fun showReplyMenu() {
        if (offeredMessage == null) return showInbox().let { Unit }
        showingInbox = false
        showingReplyMenu = true
        dictatedText = null
        surface = surface ?: nexusSurfaceSession("reply")
        surface?.showCard(
            NexusCard(
                title = "Reply to ${offeredMessage?.sender.orEmpty().clean(42)}",
                lines = listOf("Tap = record voice note", "Right/Down = dictate text"),
                footer = "back inbox",
                handlesBack = true,
            ),
        )
    }

    private fun beginDictation() {
        val target = offeredMessage ?: return
        if (audio != null || speech != null) return
        showingReplyMenu = false
        dictatedText = null
        surface = surface ?: nexusSurfaceSession("reply")
        surface?.showCard(
            NexusCard(
                title = "Dictate text",
                lines = listOf("Speak your reply to ${target.sender.clean(42)}.", "Nexus will show the final text before sending."),
                footer = "back cancel",
                handlesBack = true,
            ),
        )
        val session = nexusSpeechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) = Unit
            override fun onSpeechState(state: NexusSpeechState) = Unit
            override fun onSpeechPartial(text: String) {
                if (text.isNotBlank()) {
                    surface?.showCard(
                        NexusCard(
                            title = "Listening…",
                            lines = listOf(text.clean(240)),
                            footer = "back cancel",
                            handlesBack = true,
                        ),
                    )
                }
            }
            override fun onSpeechFinal(text: String) {
                val finalText = text.trim()
                if (finalText.isBlank()) return
                dictatedText = finalText
                showDictationReview()
            }
            override fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
                speech = null
                if (dictatedText == null && reason != NexusSpeechStopReason.COMPLETED) {
                    showResultCard("Dictation stopped", error?.detail?.takeIf { it.isNotBlank() } ?: reason.toString())
                }
            }
        }) ?: run {
            showResultCard("Dictation unavailable", "Update Rokid Nexus and grant Speech to text for Voice Relay.")
            return
        }
        speech = session
        when (val result = session.start(language = "auto")) {
            NexusSdkResult.SENT -> Unit
            else -> {
                speech = null
                showResultCard("Dictation unavailable", "$result. Grant Speech to text for Voice Relay in Nexus Plugin access.")
            }
        }
    }

    private fun showDictationReview() {
        val text = dictatedText ?: return
        showingReplyMenu = false
        surface = surface ?: nexusSurfaceSession("reply")
        surface?.showCard(
            NexusCard(
                title = "Text ready",
                lines = listOf(text.clean(300)),
                footer = "tap send · ↑/← re-dictate · back cancel",
                handlesBack = true,
            ),
        )
    }

    private fun sendDictatedText() {
        val target = offeredMessage ?: return
        val text = dictatedText?.trim().orEmpty()
        if (text.isBlank()) return
        sending = true
        showResultCard("Sending text…", "${target.app} · ${target.sender}")
        if (VoiceRelayNotificationListener.sendTextReply(target, text)) {
            dictatedText = null
            sending = false
            finishSuccessfulSend(target, "Text reply sent to ${target.sender.clean(60)}.")
            return
        }
        if (TelegramVoiceSender.isTelegram(target)) {
            TelegramClientManager.get(this).sendTextMessage(target, text) { result ->
                main.post {
                    sending = false
                    result.fold(
                        onSuccess = {
                            dictatedText = null
                            finishSuccessfulSend(target, "Text reply sent to ${target.sender.clean(60)}.")
                        },
                        onFailure = { error -> showResultCard("Text send failed", error.message ?: "Telegram could not send this text.") },
                    )
                }
            }
        } else {
            sending = false
            showResultCard("Phone reply unavailable", "The WhatsApp notification no longer exposes a text-reply action. The message stays in the inbox.")
        }
    }

    private fun beginVoiceRecording() {
        if (audio != null || offeredMessage == null) return
        pendingRecording = null
        showingInbox = false
        showingReplyMenu = false
        playbackFinished = false
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

                if (keepRecordingOnStop &&
                    reason == NexusAudioStopReason.RELEASED &&
                    recorder != null
                ) {
                    val published = recorder.finishAndPublish()
                    if (published != null) {
                        pendingRecording = published
                        PendingMessageStore.setLastRecording(
                            this@VoiceRelayPluginService,
                            published.uri,
                            published.name,
                            offeredMessage,
                        )
                        showConfirmation()
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

    private fun cancelActiveRecording() {
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

    private fun showConfirmation(error: String? = null) {
        showingInbox = false
        val target = offeredMessage
        val recording = pendingRecording ?: return
        val telegram = TelegramVoiceSender.isTelegram(target)
        val whatsapp = WhatsAppVoiceSender.isWhatsApp(target)
        val connected = TelegramClientManager.get(this).isReady()

        val lines = mutableListOf<String>()
        lines += "To: ${target?.sender.orEmpty().clean(42)}"
        lines += "Length: ${formatDuration(recording.durationMs)}"
        when {
            error != null -> lines += error.clean(180)
            telegram && !connected -> lines += "Telegram setup required on phone."
            telegram -> lines += "Ready to send as a Telegram voice message."
            whatsapp -> lines += "Ready to try WhatsApp voice delivery."
            else -> lines += "Sending is not available for this app."
        }

        surface = surface ?: nexusSurfaceSession("recording")
        surface?.showCard(
            NexusCard(
                title = "Voice note ready",
                lines = lines.take(4),
                footer = if (telegram || whatsapp) {
                    "tap voice · →/↓ audio file · ↑/← retake · back cancel"
                } else {
                    "↑/← retake · back cancel"
                },
                handlesBack = true,
            ),
        )
    }

    private fun sendPendingRecording(asFile: Boolean) {
        val target = offeredMessage ?: return
        val recording = pendingRecording ?: return
        when {
            TelegramVoiceSender.isTelegram(target) && asFile -> sendTelegramFile(target, recording)
            TelegramVoiceSender.isTelegram(target) -> sendTelegram(target, recording)
            WhatsAppVoiceSender.isWhatsApp(target) -> sendWhatsApp(target, recording, asFile)
            else -> showConfirmation("This messaging app does not have a sender yet.")
        }
    }

    private fun sendTelegramFile(target: IncomingMessage, recording: PublishedRecording) {
        sending = true
        showResultCard("Sending audio file…", "${target.app} · ${target.sender}\nSending the WAV recording as an ordinary Telegram file.")
        Thread {
            val result = runCatching {
                val temp = java.io.File.createTempFile("voicerelay_audio_", ".wav", cacheDir)
                contentResolver.openInputStream(android.net.Uri.parse(recording.uri)).use { input ->
                    requireNotNull(input) { "Could not open the recording." }
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp
            }
            result.fold(
                onSuccess = { file ->
                    TelegramClientManager.get(this).sendAudioFile(target, file.absolutePath) { sent ->
                        file.delete()
                        main.post {
                            sending = false
                            sent.fold(
                                onSuccess = { finishSuccessfulSend(target, "Audio file sent to ${target.sender.clean(60)}.") },
                                onFailure = { error -> showConfirmation(error.message ?: "Telegram audio-file send failed.") },
                            )
                        }
                    }
                },
                onFailure = { error -> main.post { sending = false; showConfirmation(error.message ?: "Could not prepare audio file.") } },
            )
        }.start()
    }

    private fun sendTelegram(target: IncomingMessage, recording: PublishedRecording) {
        sending = true
        showResultCard(
            "Sending…",
            "${target.app} · ${target.sender}\nEncoding OGG/Opus and sending through Telegram.",
        )
        TelegramVoiceSender.send(this, target, recording) { result ->
            main.post {
                sending = false
                result.fold(
                    onSuccess = { finishSuccessfulSend(target, "Voice message sent to ${target.sender.clean(60)}.") },
                    onFailure = { error -> showConfirmation(error.message ?: "Telegram send failed.") },
                )
            }
        }
    }

    private fun sendWhatsApp(target: IncomingMessage, recording: PublishedRecording, asFile: Boolean) {
        sending = true
        showResultCard(
            if (asFile) "Sending audio…" else "Sending…",
            "${target.app} · ${target.sender}\n" + if (asFile) "Trying WhatsApp's supported audio attachment route." else "Trying WhatsApp notification/Android voice-message transport.",
        )
        WhatsAppVoiceSender.send(this, target, recording) { result ->
            main.post {
                sending = false
                result.fold(
                    onSuccess = { outcome ->
                        if (outcome.completedInBackground) {
                            finishSuccessfulSend(target, "Voice message handed to ${target.sender.clean(60)}.")
                        } else {
                            showResultCard("Phone confirmation", outcome.detail)
                        }
                    },
                    onFailure = { error -> showConfirmation(error.message ?: "WhatsApp send failed.") },
                )
            }
        }
    }

    private fun finishSuccessfulSend(target: IncomingMessage, detail: String) {
        PendingMessageStore.remove(this, target)
        VoiceRelayNotificationListener.dismissNotification(target.notificationKey)
        pendingRecording = null
        offeredMessage = null
        showResultCard("Sent", detail)
        main.postDelayed({ showInbox() }, 1_400L)
    }

    private fun retakeRecording() {
        pendingRecording?.delete(this)
        PendingMessageStore.clearLastRecording(this)
        pendingRecording = null
        beginVoiceRecording()
    }

    private fun discardPendingAndReturn() {
        pendingRecording?.delete(this)
        PendingMessageStore.clearLastRecording(this)
        pendingRecording = null
        showInbox()
    }

    private fun showResultCard(title: String, detail: String) {
        showingInbox = false
        surface = surface ?: nexusSurfaceSession("recording")
        surface?.showCard(
            NexusCard(
                title = title,
                lines = detail.split('\n').map { it.clean(180) }.take(3),
                footer = when {
                    playing -> "back stops playback"
                    playbackFinished -> "tap voice reply · back inbox"
                    sending -> "please keep Voice Relay open"
                    else -> "back → inbox"
                },
                handlesBack = true,
            ),
        )
    }

    private fun telegramStatusLine(): String {
        val status = TelegramClientManager.get(this).status()
        return when {
            status.stage == TelegramAuthStage.READY ->
                "Telegram connected${status.accountLabel?.let { " · $it" }.orEmpty()}."
            else -> "Telegram setup: ${status.detail}"
        }.clean(180)
    }

    private fun formatDuration(durationMs: Long): String {
        val total = (durationMs / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(total / 60L, total % 60L)
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
        private val OPEN_RETRY_DELAYS_MS = listOf(150L, 400L, 900L, 1_800L, 3_500L, 6_000L)
        @Volatile private var current: VoiceRelayPluginService? = null

        fun notifyInboxChanged() {
            val active = current ?: return
            active.main.post {
                if (active.pluginOpen && active.showingInbox && active.audio == null &&
                    active.pendingRecording == null && !active.sending && !active.playing
                ) {
                    active.showInbox()
                }
            }
        }
    }
}
