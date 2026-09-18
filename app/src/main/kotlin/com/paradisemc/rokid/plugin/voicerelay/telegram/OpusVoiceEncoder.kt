package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                val wav = readWav(it)
                var remainingPcm = wav[2].toLong()

                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS,
                    wav[0],
                    wav[1],
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
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L

                while (!outputDone) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Audio encoder timed out" }
                    if (!inputDone) {
                        val inputIndex = codec!!.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec!!.getInputBuffer(inputIndex)
                                ?: error("Opus encoder input buffer unavailable.")
                            buffer.clear()
                            val chunk = ByteArray(minOf(buffer.remaining(), 8192, remainingPcm.toInt()))
                            val read = if (remainingPcm == 0L) -1 else it.read(chunk)
                            val presentationTimeUs =
                                (totalPcmBytes / (PCM_BYTES_PER_SAMPLE * wav[1])) * 1_000_000L / wav[0]

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
                                remainingPcm -= read
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
                            if (buffer != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
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

    /** MediaRecorder WAVs may include extra RIFF chunks and use a different sample rate. */
    private fun readWav(input: InputStream): IntArray {
        fun bytes(count: Int): ByteArray {
            val data = ByteArray(count); var n = 0
            while (n < count) { val read = input.read(data, n, count - n); check(read > 0) { "Truncated WAV" }; n += read }
            return data
        }
        fun number(data: ByteArray, at: Int) = ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val header = bytes(12)
        check(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") { "Recording is not WAV" }
        var rate = 0; var channels = 0
        repeat(64) {
            val h = bytes(8); val size = number(h, 4)
            check(size in 0..(12 * 1024 * 1024)) { "Invalid WAV chunk size" }
            when (String(h, 0, 4)) {
                "fmt " -> {
                    check(size in 16..4096); val fmt = bytes(size)
                    val f = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    check(f.getShort(0).toInt() == 1 && f.getShort(14).toInt() == 16) { "WAV must contain 16-bit PCM" }
                    rate = f.getInt(4); channels = f.getShort(2).toInt()
                    check(rate in 8000..48000 && channels in 1..2) { "Unsupported recording format" }
                }
                "data" -> { check(rate > 0 && size > 0 && size % (channels * 2) == 0); return intArrayOf(rate, channels, size) }
                else -> skipFully(input, size.toLong())
            }
            if (size % 2 == 1) skipFully(input, 1)
        }
        error("WAV data not found")
    }
}
