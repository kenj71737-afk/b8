package com.dublive.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service that owns the whole dub pipeline:
 *
 *  system audio (AudioPlaybackCapture via MediaProjection, mic fallback)
 *    -> VAD (speech >= 150 ms, 500 ms pre-roll)
 *    -> Gemini Live (speech-to-speech) with watchdog + key rotation
 *    -> Groq Whisper fallback when Live is unusable
 *    -> VoicePlayer (Gemini audio, or Vietnamese TTS)
 *    -> DubDelayTracker (periodic sampling, EMA, clamp 3..max s)
 *
 * LESSONS FROM THE OLD APP, built into this service:
 *  - A Gemini key can die SILENTLY after ~5 minutes (no error, just no
 *    output). Watchdog: if 60 s pass with no translated output after output
 *    had started, reconnect (max 5 times), and rotate to the other key
 *    every 5 minutes. Only translated output counts as "alive".
 *  - WebAudio tapping is NOT used: cross-origin video without CORS made
 *    Web Audio output silence. System-audio capture does not depend on CORS.
 *  - The dub voice is played with USAGE_VOICE_COMMUNICATION so the
 *    USAGE_MEDIA capture never re-captures it (no echo loop).
 */
class DubbingService : Service() {

    companion object {
        const val ACTION_START = "com.dublive.live.START"
        const val ACTION_STOP = "com.dublive.live.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_DUB_EVENT = "com.dublive.live.DUB_EVENT"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val TYPE_SUBTITLE = "subtitle"
        const val TYPE_HUD = "hud"
        const val TYPE_STATE = "state"
        const val TYPE_ERROR = "error"
        const val TYPE_FALLBACK = "fallback"
        const val TYPE_DUCK = "duck"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"

        private const val TAG = "DubLive-Service"
        private const val CHANNEL_ID = "dublive"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val KEY_ROTATE_MS = 5 * 60 * 1000L
        private const val SILENCE_LIMIT_MS = 60_000L
        private const val MAX_RECONNECTS = 5
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    private var liveClient: GeminiLiveClient? = null
    private var groq: GroqFallback? = null
    private var voicePlayer: VoicePlayer? = null
    private var tracker: DubDelayTracker? = null
    private var vad: VadDetector? = null

    private var keyIndex = 0
    private var rotateAtMs = 0L
    private var lastOutputAtMs = 0L
    private var reconnects = 0
    private var useGroqOnly = false
    private var liveConnected = false

    private val utteranceBytes = ByteArrayOutputStream()
    private val pendingText = StringBuilder()
    private var audioPlayedThisTurn = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (!capturing) return
            val now = System.currentTimeMillis()
            if (!useGroqOnly) {
                if (lastOutputAtMs > 0 && now - lastOutputAtMs > SILENCE_LIMIT_MS) {
                    if (reconnects < MAX_RECONNECTS) {
                        reconnects++
                        Log.w(TAG, "Silent for 60s, reconnecting ($reconnects/$MAX_RECONNECTS)")
                        reconnectLive()
                    } else {
                        Log.w(TAG, "Live silent after $MAX_RECONNECTS reconnects, Groq fallback")
                        switchToGroq("Gemini Live im lặng quá lâu, chuyển sang dự phòng Groq")
                    }
                } else if (now >= rotateAtMs) {
                    rotateKey()
                }
            }
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDub()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startDub(code, data)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startDub(resultCode: Int, resultData: Intent?) {
        if (capturing) return
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DubLive")
            .setContentText(getString(R.string.dub_running))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val keys = Prefs.geminiKeys(this)
        if (keys.isEmpty()) {
            broadcast(TYPE_ERROR, message = getString(R.string.need_key_first))
            stopSelf()
            return
        }
        voicePlayer = VoicePlayer(this) {
            tracker?.onDubSpeechStarted()
        }.also { it.start() }
        tracker = DubDelayTracker(Prefs.maxDelayMs(this)) { delayMs ->
            broadcast(TYPE_HUD, delayMs = delayMs)
        }
        groq = GroqFallback(Prefs.groqKey(this), keys[0], Prefs.sourceLang(this))
        useGroqOnly = false
        reconnects = 0
        rotateAtMs = System.currentTimeMillis() + KEY_ROTATE_MS
        lastOutputAtMs = 0L

        if (resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        }
        capturing = true
        startCapture()
        connectLive()
        broadcast(TYPE_STATE, state = STATE_RUNNING)
        broadcast(TYPE_DUCK, message = Prefs.originalVolume(this).toString())
        mainHandler.postDelayed(watchdog, 10_000L)
    }

    private fun connectLive() {
        val keys = Prefs.geminiKeys(this)
        if (keys.isEmpty()) return
        val key = keys[keyIndex % keys.size]
        liveClient?.close()
        liveConnected = false
        val client = GeminiLiveClient(
            apiKey = key,
            voiceName = Prefs.voiceName(this),
            sourceLang = Prefs.sourceLang(this),
            wholeSentence = Prefs.wholeSentence(this),
            listener = object : GeminiLiveClient.Listener {
                override fun onConnected() {
                    liveConnected = true
                    reconnects = 0
                    Log.i(TAG, "Gemini Live connected (key ${keyIndex % keys.size + 1})")
                }

                override fun onAudioChunk(pcm24k: ByteArray) {
                    lastOutputAtMs = System.currentTimeMillis()
                    audioPlayedThisTurn = true
                    voicePlayer?.playAudio(pcm24k)
                }

                override fun onTranslatedText(text: String, isFinal: Boolean) {
                    lastOutputAtMs = System.currentTimeMillis()
                    pendingText.append(text)
                    broadcast(TYPE_SUBTITLE, text = pendingText.toString())
                }

                override fun onTurnComplete() {
                    val text = pendingText.toString().trim()
                    pendingText.clear()
                    if (!SpeechFilter.isMeaningful(text)) {
                        broadcast(TYPE_SUBTITLE, text = "")
                    } else if (!audioPlayedThisTurn) {
                        // Model returned text but no audio: read it with TTS.
                        voicePlayer?.speakText(text, tracker?.currentDelayMs ?: 0L)
                    }
                    audioPlayedThisTurn = false
                    voicePlayer?.resetTurnFlag()
                }

                override fun onError(message: String) {
                    Log.w(TAG, "Live error: $message")
                    if (message.contains("401", ignoreCase = true) ||
                        message.contains("403", ignoreCase = true) ||
                        message.contains("quota", ignoreCase = true) ||
                        message.contains("API key", ignoreCase = true)
                    ) {
                        switchToGroq("Key Gemini lỗi ($message), chuyển sang dự phòng Groq")
                    } else if (capturing && !useGroqOnly) {
                        broadcast(TYPE_ERROR, message = "Gemini Live lỗi: $message")
                    }
                }

                override fun onClosed() {
                    liveConnected = false
                }
            }
        )
        liveClient = client
        client.connect()
    }

    private fun rotateKey() {
        val keys = Prefs.geminiKeys(this)
        if (keys.size < 2) {
            rotateAtMs = System.currentTimeMillis() + KEY_ROTATE_MS
            return
        }
        keyIndex = (keyIndex + 1) % keys.size
        rotateAtMs = System.currentTimeMillis() + KEY_ROTATE_MS
        Log.i(TAG, "Rotating to Gemini key ${keyIndex + 1}")
        reconnectLive()
    }

    private fun reconnectLive() {
        if (!capturing || useGroqOnly) return
        liveClient?.close()
        connectLive()
    }

    private fun switchToGroq(reason: String) {
        if (useGroqOnly) return
        useGroqOnly = true
        liveClient?.close()
        broadcast(TYPE_FALLBACK, message = reason)
        if (Prefs.groqKey(this).isEmpty()) {
            broadcast(TYPE_ERROR, message = "Không có key Groq dự phòng — kiểm tra key Gemini trong Cài đặt")
        }
    }

    private fun startCapture() {
        vad = VadDetector(listener = object : VadDetector.Listener {
            override fun onSpeechStart(preRollAndHead: ShortArray) {
                tracker?.startUtterance()
                voicePlayer?.resetTurnFlag()
                utteranceBytes.reset()
                val bytes = shortsToBytes(preRollAndHead)
                utteranceBytes.write(bytes)
                if (liveConnected) liveClient?.sendAudio(bytes)
            }

            override fun onSpeechChunk(samples: ShortArray) {
                val bytes = shortsToBytes(samples)
                utteranceBytes.write(bytes)
                if (liveConnected) liveClient?.sendAudio(bytes)
            }

            override fun onSpeechEnd() {
                tracker?.endUtterance()
                liveClient?.endTurn()
                if (useGroqOnly) {
                    val pcm = utteranceBytes.toByteArray()
                    if (pcm.size > 3200) { // ignore blips shorter than ~100 ms
                        groq?.transcribeAndTranslate(pcm, object : GroqFallback.Listener {
                            override fun onTranslated(text: String) {
                                lastOutputAtMs = System.currentTimeMillis()
                                broadcast(TYPE_SUBTITLE, text = text)
                                voicePlayer?.speakText(text, tracker?.currentDelayMs ?: 0L)
                            }

                            override fun onError(message: String) {
                                broadcast(TYPE_ERROR, message = message)
                            }
                        })
                    }
                }
            }
        })
        captureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            var record: AudioRecord? = null
            try {
                record = if (mediaProjection != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ) {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build()
                    AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                } else {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .build()
                }
                audioRecord = record
                if (mediaProjection == null) {
                    broadcast(TYPE_ERROR, message = getString(R.string.capture_failed))
                }
                record.startRecording()
                val buf = ShortArray(1600) // 100 ms at 16 kHz
                while (capturing) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) vad?.feed(buf, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed: ${e.message}")
                broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
            } finally {
                try { record?.stop() } catch (_: Exception) { }
                try { record?.release() } catch (_: Exception) { }
                audioRecord = null
            }
        }, "DubLive-Capture").apply { start() }
    }

    private fun stopDub() {
        capturing = false
        mainHandler.removeCallbacks(watchdog)
        captureThread?.join(1000)
        captureThread = null
        liveClient?.close()
        liveClient = null
        tracker?.stop()
        tracker = null
        vad?.reset()
        vad = null
        voicePlayer?.release()
        voicePlayer = null
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null
        broadcast(TYPE_STATE, state = STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcast(type: String, text: String? = null, delayMs: Long? = null,
                          state: String? = null, message: String? = null) {
        val intent = Intent(ACTION_DUB_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_TYPE, type)
        if (text != null) intent.putExtra(EXTRA_TEXT, text)
        if (delayMs != null) intent.putExtra(EXTRA_DELAY_MS, delayMs)
        if (state != null) intent.putExtra(EXTRA_STATE, state)
        if (message != null) intent.putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    override fun onDestroy() {
        if (capturing) stopDub()
        super.onDestroy()
    }
}
