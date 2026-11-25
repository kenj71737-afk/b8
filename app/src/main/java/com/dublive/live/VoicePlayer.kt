package com.dublive.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

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
 */
class VoicePlayer(
    context: Context,
    private val onSpeechStarted: () -> Unit
) {
    companion object {
        private const val TAG = "DubLive-Voice"
    }

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private var playerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var speechStartedSignalled = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
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
                        if (!speechStartedSignalled) {
                            speechStartedSignalled = true
                            onSpeechStarted()
                        }
                    }

                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(24000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        playerThread = Thread({
            while (running) {
                try {
                    val chunk = audioQueue.take()
                    if (!speechStartedSignalled) {
                        speechStartedSignalled = true
                        onSpeechStarted()
                    }
                    audioTrack?.write(chunk, 0, chunk.size)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DubLive-AudioOut").apply { start() }
    }

    /** Queue Gemini Live PCM (24 kHz, mono, 16-bit). */
    fun playAudio(pcm24k: ByteArray) {
        if (running) audioQueue.offer(pcm24k)
    }

    /** Speak translated text with catch-up speed based on current lag. */
    fun speakText(text: String, lagMs: Long) {
        val tts = tts ?: return
        if (!ttsReady) return
        val rate = when {
            lagMs > 4000 -> 1.35f
            lagMs > 2000 -> 1.2f
            else -> 1.0f
        }
        tts.setSpeechRate(rate)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        speechStartedSignalled = false
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "dublive-${System.currentTimeMillis()}")
    }

    fun resetTurnFlag() {
        speechStartedSignalled = false
    }

    fun stop() {
        running = false
        playerThread?.interrupt()
        playerThread = null
        audioQueue.clear()
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
