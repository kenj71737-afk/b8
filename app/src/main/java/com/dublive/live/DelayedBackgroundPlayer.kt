package com.dublive.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import android.util.Log

/**
 * v1.0.9 — port of ALDA FIX37/FIX41's BackgroundAudioPlayer
 * (core/audio/BackgroundAudioPlayer.kt), WITHOUT the VocalRemover.
 *
 * WHY: the WebView/ExoPlayer picture is delayed by D, but its sound
 * still plays live — the original audio runs D seconds AHEAD of the
 * picture, so the film's own dialogue never matches the lips and the
 * dubbed voice (which waits for the delayed picture) sounds like a
 * second, out-of-sync voice. ALDA's fix: mute the live audio at the
 * SYSTEM level (STREAM_MUSIC -> 0, which does NOT silence the
 * AudioPlaybackCapture tap — the tap reads the track before the
 * stream volume is applied) and replay the captured system audio
 * delayed by the same D through this player on
 * USAGE_VOICE_COMMUNICATION — the SAME stream as the dubbed voice
 * (v1.0.10: the user forbade the alarm stream entirely, "Không dùng
 * qua báo thức nhé").
 *
 * Differences from ALDA's player:
 *  - No vocal filtering at all: DubLive keeps the original audio
 *    whole (dialogue + music), filterEnabled=false forever. ALDA's
 *    FIX29 mode removed the centered dialogue because its external
 *    mode dubbed OVER the other app; here the original is the film
 *    itself, heard at low volume under the dub.
 *  - The capture is stereo 48 kHz (see DubbingService); the player
 *    takes raw stereo PCM16 and plays it as-is.
 *  - Ducking (original drops while the dub speaks, back to 100%
 *    1.5 s after) is applied HERE via [setVolume], not on the video
 *    element: the element volume must stay 1.0 or the capture itself
 *    goes quiet (the v1.0.2 lesson).
 *
 * The track uses USAGE_VOICE_COMMUNICATION in the system-capture
 * mode on purpose: STREAM_MUSIC is muted in this mode, so anything
 * DubLive plays must leave the music stream. The capture config only
 * matches USAGE_MEDIA/GAME, so this output is never re-captured — no
 * feedback loop. v1.0.11 — in the local-tap mode the track uses
 * USAGE_MEDIA instead (the local player is muted with player.volume
 * = 0, so no stream is muted at all) and no enhancer: the media
 * stream is loud already.
 *
 * The call stream is quiet even at max index on this user's phone,
 * so the track also carries a LoudnessEnhancer (+8 dB target,
 * 800→500→0 fallbacks, never crashes start — same as the dub voice
 * in VoicePlayer) when it runs on the call stream. The Settings
 * "tiếng gốc" slider still drives this track's software gain (the
 * ratio between the two voices); the hardware volume buttons now move
 * BOTH the dub voice and the delayed original together, since they
 * share the call stream (capture mode) or the media stream (tap
 * modes).
 *
 * [feedPcm] is called from the audio-capture thread. Blocks are
 * queued with their arrival time; a playback loop writes each block
 * once (now − arrival) >= the live delay ([setDelayMs]); blocks
 * overdue by > 500 ms are dropped so a delay decrease catches up
 * instead of trailing behind.
 */
class DelayedBackgroundPlayer(private val context: Context) {

    companion object {
        private const val TAG = "DubLive-BgAudio"
        const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** One 10 ms stereo chunk at 48 kHz: 480 frames x 2 ch x 2 bytes. */
        private const val CHUNK_BYTES = 1920
    }

    private var audioTrack: AudioTrack? = null
    /**
     * v1.0.10 — LoudnessEnhancer on this track (same as the dub
     * voice): the call stream is quiet even at max, and track gain
     * is already at its ceiling. +8 dB target, 800→500→0 fallbacks;
     * released in stop(); never allowed to crash start().
     */
    private var loudnessEnhancer: LoudnessEnhancer? = null

    @Volatile private var volumeRatio: Float = 1.0f
    @Volatile private var muted: Boolean = false

    /** Playback delay in ms (0 = live). Read live by the playback
     * loop, so a delay change takes effect immediately.
     *
     * v1.0.17 — the delay GLIDES to its target instead of jumping:
     * the queue's due time is (arrival + delay), so a 1 s jump
     * silences the original music for ~1 s (delay up) or drops up
     * to 1 s of it (delay down, the 500 ms stale drop). The loop
     * now steps the EFFECTIVE delay toward the target by at most
     * 10 ms every 10 ms (slew rate 1x real time: a 1 s D change
     * takes 1 s to settle, with gaps of at most one 10 ms step —
     * essentially inaudible), instead of applying the jump at once.
     * [setDelayMs] sets the target; [currentDelayMs] reports the
     * effective (actually playing) value. */
    @Volatile private var targetDelayMs: Long = 0L
    private var effectiveDelayMs: Long = 0L
    private var lastDelayStepAtMs: Long = 0L

    private val delayQueue = DelayedAudioQueue()
    /** Plain thread (not coroutines — this project has no
     * kotlinx-coroutines dependency): polls the queue every 5 ms
     * and writes each block once it is due. */
    private var playbackThread: Thread? = null
    @Volatile private var playbackRunning = false

    fun setDelayMs(ms: Long) {
        targetDelayMs = ms.coerceIn(0L, 25000L)
    }

    /** Current delay, for the duck ticker / status. */
    fun currentDelayMs(): Long = effectiveDelayMs

    // Scratch buffers, reused every chunk (capture delivers 100 ms chunks).
    private val shortIn = ShortArray(CHUNK_BYTES / 2)
    private val byteOut = ByteArray(CHUNK_BYTES)

    /**
     * v1.0.11 — output stream + enhancer are parameters now: the
     * delayed original plays on USAGE_MEDIA (no enhancer — media is
     * loud already) in the local-tap mode, and on
     * USAGE_VOICE_COMMUNICATION (with the +8 dB enhancer, the call
     * stream is quiet) in the system-capture mode.
     */
    fun start(usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION, useEnhancer: Boolean = true) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, CHUNK_BYTES * 6) // ~60 ms of headroom

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        applyTrackVolume()
        // v1.0.10 — LoudnessEnhancer (see field comment). Any failure
        // just means no boost — start() must never die here.
        // v1.0.11 — call stream only (useEnhancer=false on MEDIA).
        if (useEnhancer) {
            try {
                val enhancer = try { LoudnessEnhancer(audioTrack!!.audioSessionId) } catch (e: Exception) { null }
                if (enhancer != null) {
                    var applied = false
                    for (gain in intArrayOf(800, 500, 0)) {
                        try {
                            enhancer.setTargetGain(gain)
                            enhancer.enabled = true
                            applied = true
                            Log.i(TAG, "LoudnessEnhancer on, targetGain=$gain mB")
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "LoudnessEnhancer gain $gain failed", e)
                        }
                    }
                    if (applied) {
                        loudnessEnhancer = enhancer
                    } else {
                        try { enhancer.release() } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer setup failed", e)
                loudnessEnhancer = null
            }
        }
        val usageName = if (usage == AudioAttributes.USAGE_MEDIA) "media" else "voice-call"
        Log.i(TAG, "Delayed background player started ($usageName stream, ${SAMPLE_RATE}Hz stereo)")

        playbackRunning = true
        playbackThread = Thread({
            while (playbackRunning) {
                val track = audioTrack
                if (track == null) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                // v1.0.17 — glide the effective delay toward the
                // target (at most 10 ms per 10 ms tick): a D jump
                // must not silence/drop the original music.
                val nowLoop = SystemClock.elapsedRealtime()
                if (nowLoop - lastDelayStepAtMs >= 10L) {
                    lastDelayStepAtMs = nowLoop
                    val target = targetDelayMs
                    if (effectiveDelayMs < target) {
                        effectiveDelayMs = minOf(target, effectiveDelayMs + 10L)
                    } else if (effectiveDelayMs > target) {
                        effectiveDelayMs = maxOf(target, effectiveDelayMs - 10L)
                    }
                }
                var block = delayQueue.pollDue(nowLoop, effectiveDelayMs)
                while (block != null) {
                    try {
                        track.write(block, 0, block.size)
                    } catch (e: Exception) {
                        Log.w(TAG, "Background write failed", e)
                        break
                    }
                    block = delayQueue.pollDue(SystemClock.elapsedRealtime(), effectiveDelayMs)
                }
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DubLive-BgAudio").apply { start() }
    }

    /**
     * Queue one stereo PCM16 chunk (little-endian, 48 kHz) for
     * delayed playback. Any length is accepted; it is processed in
     * 10 ms blocks and a trailing partial frame (fewer than 4 bytes)
     * is dropped. Called from the capture thread with RAW audio —
     * before the VAD, never through it.
     */
    fun feedPcm(stereoPcm: ByteArray) {
        var offset = 0
        while (offset + 4 <= stereoPcm.size) {
            val blockLen = minOf(CHUNK_BYTES, (stereoPcm.size - offset) / 4 * 4)
            val samples = blockLen / 2
            for (i in 0 until samples) {
                val b = offset + i * 2
                shortIn[i] = ((stereoPcm[b + 1].toInt() shl 8) or (stereoPcm[b].toInt() and 0xFF)).toShort()
            }
            // Software gain (same vol^2 curve as the dub voice) + to bytes.
            val vol = if (muted) 0f else volumeRatio.coerceIn(0f, 1f)
            val gain = vol * vol * 1.35f
            for (i in 0 until samples) {
                val scaled = (shortIn[i].toFloat() * gain).coerceIn(-32767f, 32767f).toInt()
                byteOut[i * 2] = (scaled and 0xFF).toByte()
                byteOut[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            delayQueue.add(SystemClock.elapsedRealtime(), byteOut.copyOf(blockLen))
            offset += blockLen
        }
    }

    /**
     * The "original media" level: 1.0 = full (dub silent), the
     * Settings slider value (default 0.8) while the dub speaks.
     * The duck ticker in DubbingService drives this; the video
     * element volume is never touched in delayed mode.
     */
    fun setVolume(ratio: Float, isMuted: Boolean) {
        volumeRatio = ratio.coerceIn(0f, 1f)
        muted = isMuted
        applyTrackVolume()
    }

    private fun applyTrackVolume() {
        try {
            audioTrack?.setVolume(if (muted) 0f else volumeRatio.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set background track volume", e)
        }
    }

    fun stop() {
        // Stop the playback loop first so it never writes to a
        // released track, then drop any queued (undelayed) audio.
        playbackRunning = false
        playbackThread?.interrupt()
        try { playbackThread?.join(500) } catch (_: Exception) { }
        playbackThread = null
        delayQueue.clear()
        try { loudnessEnhancer?.release() } catch (_: Exception) { }
        loudnessEnhancer = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping background track", e)
        }
        audioTrack = null
    }
}
