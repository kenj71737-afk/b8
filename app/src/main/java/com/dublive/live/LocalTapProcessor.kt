package com.dublive.live

import androidx.media3.common.C
import androidx.media3.common.audio.AudioFormat
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * v1.0.11 — ExoPlayer audio tap for local files.
 *
 * WHY (the brief's GAME trick cannot work on its own): the dub voice
 * and the delayed original audio must play on USAGE_MEDIA, and the
 * live player audio must be silenced — but the only ways to silence
 * it both break something else: muting STREAM_MUSIC at the system
 * level also silences the MEDIA dub/background (same stream:
 * USAGE_GAME maps to STREAM_MUSIC), and player.volume = 0 is applied
 * at the AudioTrack, BEFORE the AudioPlaybackCapture tap, so the
 * capture would go silent too (the v1.0.2 lesson, learned on the
 * WebView element volume). So local files do not use the system
 * capture at all: this processor taps the PCM straight out of the
 * player's audio pipeline — BEFORE the track volume — while the
 * player itself is muted with player.volume = 0. The pipeline gets
 * full-volume PCM, the speakers get silence, the dub plays on MEDIA,
 * and nothing is ever re-captured (no capture runs): no echo.
 *
 * WHAT IT DOES: a pure pass-through processor (audio always flows to
 * the track, so the media clock and the video never stall). It
 * normalises the output to 48 kHz stereo PCM16 — the format the
 * service's delayed-background player and downmixer expect — and,
 * while [enabled], copies the normalised PCM into [TapAudioQueue]
 * (STEREO48K) for the service. When disabled it only resamples.
 * Non-PCM16 input (e.g. float decoders) is passed through untouched
 * with no tap (playback must never break for an exotic format).
 *
 * SYNC: the processor adds NO delay — the delay lives in
 * DelayedBackgroundPlayer (original audio) and the voice hold (dub),
 * exactly like the system-capture path. A seek flushes the processor
 * (ExoPlayer calls flush()), so no stale PCM survives a seek.
 */
class LocalTapProcessor : AudioProcessor {

    /** Set by LocalPlayerActivity: only copy while dubbing is on. */
    @Volatile var enabled = false

    /** Normalised 48 kHz stereo PCM16 -> the service's tap queue. */
    var onPcm: ((ByteArray) -> Unit)? = null

    private var inputFormat: AudioFormat? = null
    private var outputFormat: AudioFormat? = null
    /** True while the input is PCM16 (false = pass-through, no tap). */
    private var pcm16 = false
    private val outputQueue = ArrayDeque<ByteBuffer>()
    private var ended = false

    private val emptyBuffer: ByteBuffer =
        ByteBuffer.allocate(0).order(ByteOrder.nativeOrder())

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        inputFormat = inputAudioFormat
        pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        outputFormat = if (pcm16) {
            AudioFormat.Builder()
                .setSampleRate(48000)
                .setChannelCount(2)
                .setEncoding(C.ENCODING_PCM_16BIT)
                .build()
        } else {
            // Not PCM16: pass through untouched, no tap.
            inputAudioFormat
        }
        return outputFormat!!
    }

    override fun isActive(): Boolean = outputFormat != null

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        if (!pcm16) {
            // Pass-through: forward the buffer unchanged.
            val copy = ByteBuffer.allocate(inputBuffer.remaining())
                .order(ByteOrder.nativeOrder())
            copy.put(inputBuffer)
            copy.flip()
            outputQueue.addLast(copy)
            return
        }
        val fmt = inputFormat ?: return
        val inFrames = inputBuffer.remaining() / 2 / fmt.channelCount.coerceAtLeast(1)
        if (inFrames <= 0) return
        // Read the input as shorts (native order, as ExoPlayer delivers).
        val inShorts = ShortArray(inFrames * fmt.channelCount)
        val dup = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        dup.asShortBuffer().get(inShorts)
        // Normalise to 48 kHz stereo.
        val outFrames = ((inFrames.toLong() * 48000L) / fmt.sampleRate.coerceAtLeast(1)).toInt()
        val out = ByteBuffer.allocate(outFrames * 2 * 2).order(ByteOrder.nativeOrder())
        val outShorts = ShortArray(outFrames * 2)
        val ratio = fmt.sampleRate.toDouble() / 48000.0
        val channels = fmt.channelCount
        for (f in 0 until outFrames) {
            val srcPos = f * ratio
            val i0 = floor(srcPos).toInt().coerceIn(0, inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (ch in 0 until 2) {
                val srcCh = if (ch < channels) ch else 0
                val a = inShorts[i0 * channels + srcCh].toInt()
                val b = inShorts[i1 * channels + srcCh].toInt()
                val v = (a + ((b - a) * frac)).toInt().coerceIn(-32768, 32767)
                outShorts[f * 2 + ch] = v.toShort()
            }
        }
        out.asShortBuffer().put(outShorts)
        out.flip()
        if (enabled) {
            try {
                val bytes = ByteArray(out.remaining())
                out.duplicate().get(bytes)
                onPcm?.invoke(bytes)
            } catch (_: Exception) {
                // The tap must never break playback.
            }
        }
        outputQueue.addLast(out)
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun getOutput(): ByteBuffer {
        return outputQueue.removeFirstOrNull() ?: emptyBuffer
    }

    override fun isEnded(): Boolean = ended && outputQueue.isEmpty()

    override fun flush() {
        outputQueue.clear()
        ended = false
    }

    override fun reset() {
        flush()
        inputFormat = null
        outputFormat = null
        pcm16 = false
    }
}
