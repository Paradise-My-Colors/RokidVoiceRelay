package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.max

data class EncodedVoice(
    val file: File,
    val durationSeconds: Int,
)

object OpusVoiceEncoder {
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val PCM_BYTES_PER_SAMPLE = 2
    private const val BIT_RATE = 32_000
    private const val WAV_HEADER_BYTES = 44L
    private val executor = Executors.newSingleThreadExecutor()

    fun encode(
        context: Context,
        sourceUri: String,
        durationMs: Long,
        callback: (Result<EncodedVoice>) -> Unit,
    ) {
        executor.execute {
            callback(runCatching { encodeBlocking(context, sourceUri, durationMs) })
        }
    }

    private fun encodeBlocking(
        context: Context,
        sourceUri: String,
        durationMs: Long,
    ): EncodedVoice {
        val output = File.createTempFile("voicerelay_", ".ogg", context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val input = context.contentResolver.openInputStream(Uri.parse(sourceUri))
                ?: error("Could not open the recorded voice note.")
            input.use {
                skipFully(it, WAV_HEADER_BYTES)

                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS,
                    SAMPLE_RATE,
                    CHANNELS,
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
                }

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                muxer = MediaMuxer(
                    output.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                )

                var trackIndex = -1
                var inputDone = false
                var outputDone = false
                var totalPcmBytes = 0L
                val info = MediaCodec.BufferInfo()

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec!!.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec!!.getInputBuffer(inputIndex)
                                ?: error("Opus encoder input buffer unavailable.")
                            buffer.clear()
                            val chunk = ByteArray(minOf(buffer.remaining(), 8192))
                            val read = it.read(chunk)
                            val presentationTimeUs =
                                (totalPcmBytes / PCM_BYTES_PER_SAMPLE) * 1_000_000L / SAMPLE_RATE

                            if (read < 0) {
                                codec!!.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, read)
                                codec!!.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    read,
                                    presentationTimeUs,
                                    0,
                                )
                                totalPcmBytes += read
                            }
                        }
                    }

                    when (val outputIndex = codec!!.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Opus output format changed twice." }
                            trackIndex = muxer!!.addTrack(codec!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec!!.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(trackIndex, buffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec!!.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            check(muxerStarted && output.length() > 0L) { "No OGG/Opus audio was produced." }
            return EncodedVoice(
                file = output,
                durationSeconds = max(1, ((durationMs + 999L) / 1000L).toInt()),
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() == -1) error("Recorded WAV is too short.")
                remaining--
            }
        }
    }
}
