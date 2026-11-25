package com.dublive.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Plays the dubbed Vietnamese voice.
 *
 * Two sources:
 *  - Gemini Live audio (24 kHz PCM) played through AudioTrack. It uses
 *    USAGE_VOICE_COMMUNICATION on purpose: the system-audio capture is
 *    configured for USAGE_MEDIA only, so the dub voice itself is NOT
 *    re-captured (that echo loop made the old app hear its own voice).
 *  - TextToSpeech fallback (Vietnamese) when the model returns text only.
 *    Reading speeds up to catch up: lag > 2 s -> 1.2x, lag > 4 s -> 1.35x.
 *
 * v1.0.9 — VOICE HOLD (ALDA mechanism, per burst): the translated
 * voice must NOT play the moment it arrives — it must wait until
 * (source utterance start + D), the moment the delayed picture
 * reaches that sentence. Each burst's hold (= D − latency of that
 * burst, recomputed at every burst start) is set by the service via
 * [setVoiceHoldMs]; chunks are queued in a [DelayedAudioQueue] with
 * that fixed due time and only written to the track once due. The
 * subtitle still shows when the voice STARTS PLAYING (the
 * onSpeechStarted callback fires when the first chunk is TAKEN from
 * the queue, i.e. after its hold), not when the chunk arrives.
 */
class VoicePlayer(
    context: Context,
    private val onSpeechStarted: () -> Unit,
    private val onTtsStarted: () -> Unit = {}
) {
    companion object {
        private const val TAG = "DubLive-Voice"

        /**
         * v1.0.6 — the dub voice plays on STREAM_VOICE_CALL
         * (USAGE_VOICE_COMMUNICATION), a SEPARATE stream from media:
         * turning up the media volume does nothing for it, which is why
         * the user reported "giọng dịch nhỏ quá" no matter how loud the
         * video was. Map the 0..100% slider straight onto the system's
         * call-stream volume (0..max), effective immediately, no restart
         * needed. Needs MODIFY_AUDIO_SETTINGS (a normal permission,
         * already in the manifest — no runtime request). USAGE stays
         * VOICE_COMMUNICATION on purpose: switching to USAGE_MEDIA would
         * let AudioPlaybackCapture re-capture the dub voice (echo loop).
         *
         * v1.0.10 — the alarm option is GONE (user: "Không dùng qua
         * báo thức nhé"): the slider always drives the call stream
         * now. The delayed original audio shares the same stream
         * (DelayedBackgroundPlayer), so the hardware buttons move
         * both voices together; the two in-app sliders set the
         * ratio between them.
         */
        fun applyCallVolume(context: Context, pct: Int) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = AudioManager.STREAM_VOICE_CALL
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val index = ((pct.coerceIn(0, 100) / 100f) * max).roundToInt().coerceIn(0, max)
                am.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
            } catch (e: Exception) {
                Log.w(TAG, "setStreamVolume (dub voice) failed", e)
            }
        }

        /**
         * v1.0.10 — the dub voice always uses USAGE_VOICE_COMMUNICATION
         * (the alarm option was removed at the user's request).
         * Still NOT captured: DubbingService.buildPlaybackRecord only
         * matches USAGE_MEDIA + USAGE_GAME, so a call-stream dub voice
         * cannot be re-captured (no echo loop). USAGE_MEDIA is
         * deliberately never used here.
         */
        fun dubUsage(context: Context): Int =
            AudioAttributes.USAGE_VOICE_COMMUNICATION
    }

    private val appContext = context.applicationContext

    /**
     * v1.0.9 — the dubbed voice waits behind its burst hold instead of
     * playing on arrival. [currentHoldMs] is set by the service at
     * every burst start (hold = D − latency of that burst); each chunk
     * enqueued by [playAudio] is due at arrival + that hold. A later
     * burst's different hold never retimes chunks already queued —
     * the queue stores a fixed due time per block.
     */
    private val holdQueue = DelayedAudioQueue()
    @Volatile private var currentHoldMs = 0L
    private var audioTrack: AudioTrack? = null
    /**
     * v1.0.7 — LoudnessEnhancer on the dub AudioTrack: the call stream
     * is quiet even at max index on this user's phone, and track gain
     * is already at its 1.0f ceiling. The enhancer boosts beyond that
     * (+8 dB target) while staying on the same stream. Released in
     * stop(); never allowed to crash start() (gain fallbacks 800→500→0).
     * TTS has no public audio-session id, so it gets no enhancer — it
     * is only the fallback path anyway.
     */
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var playerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var speechStartedSignalled = false
    /**
     * v1.0.5 — when the last dubbed audio chunk was taken from the
     * queue (Live audio) or TTS started. The service's duck ticker
     * reads isSpeaking(): the original audio is only ducked while the
     * dub voice is REALLY playing, and returns to 100% 1.5 s after it
     * stops — v1.0.4 ducked once forever, so the user could never hear
     * the original sound again.
     */
    @Volatile private var lastDubAudioAtMs = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        // TextToSpeech can fail on some OEM builds — never let it kill the service.
        tts = try {
            TextToSpeech(context.applicationContext) { status ->
                try {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        val result = tts?.setLanguage(Locale("vi", "VN"))
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            Log.w(TAG, "Vietnamese TTS voice missing on this device")
                        }
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                lastDubAudioAtMs = System.currentTimeMillis()
                                // v1.0.8 — a TTS read started: its own
                                // callback (one sample per read for the
                                // tracker). No flag guard: onStart fires
                                // once per utterance.
                                try { onTtsStarted() } catch (_: Exception) { }
                            }

                            override fun onDone(utteranceId: String?) {}
                            override fun onError(utteranceId: String?) {}
                        })
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TTS init callback failed", e)
                    ttsReady = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech creation failed", e)
            null
        }
    }

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder()
            // v1.0.10 — always the call stream (the alarm option was
            // removed). Never USAGE_MEDIA.
            .setUsage(dubUsage(appContext))
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes((if (minBuf > 0) minBuf else 4096) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack creation failed", e)
            null
        } ?: return
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                try { track.release() } catch (_: Exception) { }
                return
            }
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.play failed", e)
            try { track.release() } catch (_: Exception) { }
            return
        }
        audioTrack = track
        running = true
        try { track.setVolume(1.0f) } catch (_: Exception) { }
        // v1.0.7 — LoudnessEnhancer: boost the dub voice beyond the
        // 1.0f gain ceiling (+8 dB target). Try 800 mB, then 500, then
        // 0 (enabled but no gain); any failure just means no boost —
        // start() must never die here.
        try {
            val enhancer = try { LoudnessEnhancer(track.audioSessionId) } catch (e: Exception) { null }
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
        playerThread = Thread({
            while (running) {
                try {
                    // v1.0.9 — poll the hold queue instead of blocking
                    // on take(): a chunk is only written once its hold
                    // has elapsed. pollDue(now, 0): the 0 is ignored
                    // for fixed-hold blocks (their due time was stored
                    // at enqueue time).
                    val chunk = holdQueue.pollDue(System.currentTimeMillis(), 0L)
                    if (chunk == null) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    lastDubAudioAtMs = System.currentTimeMillis()
                    if (!speechStartedSignalled) {
                        speechStartedSignalled = true
                        try { onSpeechStarted() } catch (_: Exception) { }
                    }
                    try { audioTrack?.write(chunk, 0, chunk.size) } catch (_: Exception) { }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DubLive-AudioOut").apply { start() }
    }

    /** v1.0.6 — track gain is always max (1.0f); the parameter is kept
     * for callers but ignored — loudness now comes from the system
     * call-stream volume (applyCallVolume), not from scaling the track. */
    fun setDubVolume(vol: Float) {
        try { audioTrack?.setVolume(1.0f) } catch (_: Exception) { }
    }

    /**
     * v1.0.5 — true while the dubbed voice is really playing: a Live
     * audio chunk was taken within the last 1.5 s, or TTS is speaking.
     * The service ducks the original audio only while this is true.
     */
    fun isSpeaking(): Boolean {
        if (!running) return false
        try {
            if (tts?.isSpeaking == true) return true
        } catch (_: Exception) { }
        return System.currentTimeMillis() - lastDubAudioAtMs < 1500L
    }

    /** Queue Gemini Live PCM (24 kHz, mono, 16-bit) behind the
     * current burst hold — it plays at arrival + hold, not at once. */
    fun playAudio(pcm24k: ByteArray) {
        if (running) holdQueue.add(System.currentTimeMillis(), pcm24k, currentHoldMs)
    }

    /**
     * v1.0.9 — set this burst's voice hold (ms): the voice waits this
     * long after each chunk's arrival before playing it. Computed by
     * the service at every burst start (hold = D − latency of that
     * burst). Chunks already queued keep the hold they were enqueued
     * with; only the NEXT burst's chunks use the new value.
     */
    fun setVoiceHoldMs(holdMs: Long) {
        currentHoldMs = holdMs.coerceAtLeast(0L)
    }

    /** Speak translated text with catch-up speed based on current lag. */
    fun speakText(text: String, lagMs: Long) {
        val tts = tts ?: return
        if (!ttsReady) return
        try {
            val rate = when {
                lagMs > 4000 -> 1.35f
                lagMs > 2000 -> 1.2f
                else -> 1.0f
            }
            tts.setSpeechRate(rate)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    // v1.0.10 — same output as the Live track: the call
                    // stream, always.
                    .setUsage(dubUsage(appContext))
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            speechStartedSignalled = false
            lastDubAudioAtMs = System.currentTimeMillis()
            val params = Bundle().apply {
                // v1.0.6 — TTS at max too; loudness comes from the
                // system call-stream volume, not from scaling the voice.
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, "dublive-${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
        }
    }

    fun resetTurnFlag() {
        speechStartedSignalled = false
    }

    fun stop() {
        running = false
        playerThread?.interrupt()
        playerThread = null
        holdQueue.clear()
        try { loudnessEnhancer?.release() } catch (_: Exception) { }
        loudnessEnhancer = null
        try { audioTrack?.stop() } catch (_: Exception) { }
        try { audioTrack?.release() } catch (_: Exception) { }
        audioTrack = null
        try { tts?.stop() } catch (_: Exception) { }
    }

    fun release() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
    }
}
