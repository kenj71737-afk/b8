package com.dublive.live

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln

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
 *
 * v1.0.1 crash fixes (target device: iQOO Z9 Turbo, OriginOS 6, Android 16):
 *  Android 16 enforces foreground-service / MediaProjection rules harder
 *  than Android 15, and OriginOS defaults the capture scope to "share a
 *  single app". v1.0.0 died right after the user granted screen capture
 *  (it had never requested RECORD_AUDIO, and startForegroundService() from
 *  the projection callback was unguarded). Every step below is now guarded:
 *  startForeground, getMediaProjection, AudioPlaybackCapture config,
 *  AudioRecord build/start — each in its own try/catch, and any failure
 *  falls back to MIC capture (or stops the service cleanly) instead of
 *  killing the app. The service also checks RECORD_AUDIO itself before
 *  capturing, and ACTION_START_MIC runs the whole pipeline on the mic
 *  with no MediaProjection token at all.
 */
class DubbingService : Service() {

    companion object {
        const val ACTION_START = "com.dublive.live.START"
        const val ACTION_START_MIC = "com.dublive.live.START_MIC"
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
        /** Persistent status line for the main screen (never Toast-only). */
        const val TYPE_STATUS = "status"
        /** Live input level, 0..1000, sent every 500 ms while capturing. */
        const val TYPE_LEVEL = "level"
        const val EXTRA_LEVEL = "level"
        /** Raw RMS of the last audio frame (Double), sent with TYPE_LEVEL. */
        const val EXTRA_RMS = "rms"
        /** Full Live error history (last 8), as one multi-line text. */
        const val TYPE_LIVE_ERRORS = "live_errors"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"

        private const val TAG = "DubLive-Service"
        private const val CHANNEL_ID = "dublive"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val KEY_ROTATE_MS = 5 * 60 * 1000L
        private const val SILENCE_LIMIT_MS = 60_000L
        /** No translated output at all this long after (re)connect -> retry. */
        private const val NO_OUTPUT_LIMIT_MS = 25_000L
        /**
         * v1.0.4: total reconnect budget = 6 (model, key) combinations x 2
         * attempts each. The decision to advance is driven by the
         * PER-COMBINATION failure counts (comboFailures), never by this
         * shared counter alone — v1.0.3 burned all 6 combos in seconds
         * because one stale-socket race fed every failure into the same
         * counter. This is only a safety cap against looping forever.
         */
        private const val MAX_RECONNECTS = 12
        /** Pre-connect audio buffer cap: ~6 s of 16 kHz 16-bit mono
         * (ALDA FIX36: the send queue holds ~6 s, no more). */
        private const val PRECONNECT_MAX_BYTES = 192_000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var capturing = false
    @Volatile private var stopped = false
    private var micOnly = false
    private var captureThread: Thread? = null

    private var liveClient: GeminiLiveClient? = null
    private var groq: GroqFallback? = null
    private var voicePlayer: VoicePlayer? = null
    private var tracker: DubDelayTracker? = null
    private var vad: VadDetector? = null

    private var keyIndex = 0
    private var modelIndex = 0
    private var rotateAtMs = 0L
    private var lastOutputAtMs = 0L
    private var startedAtMs = 0L
    private var lastLiveAttemptAtMs = 0L
    private var reconnects = 0
    private var useGroqOnly = false
    private var liveConnected = false
    private var reconnectPending = false
    /**
     * v1.0.5 — rhythmic ducking. The old applyDuckOnce() dropped the
     * original audio to the user's level the moment the first dubbed
     * speech played and NEVER restored it, so the user could only hear
     * the dub voice and never the original sound. Now the duck ticker
     * (every 500 ms) ducks ONLY while VoicePlayer.isSpeaking() is true
     * and restores the original to 100% as soon as the dub voice has
     * been silent for 1.5 s. `ducked` is the current duck state.
     */
    private var ducked = false
    /** v1.0.5 — VAD RMS threshold for this session (Prefs, default 600). */
    private var vadThreshold = 600.0
    /** v1.0.5 — subtitle auto-hide generation: bumped on every new
     * subtitle so a stale 6 s timer cannot hide a newer one. */
    private var subtitleGen = 0
    private var lastStatus = ""
    private var lastHeardAtMs = 0L

    /**
     * v1.0.4 — generation guard (with GeminiLiveClient's own socket
     * identity check). Every connectLive() bumps this counter and captures
     * its value in the listener closures; any callback whose generation
     * is not the current one belongs to a socket that has already been
     * replaced and is dropped silently. This replaces the old
     * closingForReconnect flag, which was racy by design: close() is
     * async, so the old socket's onClosed could arrive AFTER the flag
     * had been reset to false, and was then mistaken for a server drop —
     * advancing the model/key and closing the brand-new socket, whose
     * own onClosed fired the same chain again. That loop is what burned
     * all 6 combos in seconds on v1.0.3 ("Lỗi Live [2.5-native]: Gemini
     * Live tự ngắt kết nối").
     */
    private val liveGeneration = AtomicInteger(0)

    /** v1.0.4 — last 8 Live errors, full message (never truncated), for
     * the "Xem lỗi Live" dialog. The status line only ever showed the
     * LAST error, so the first failure (translate-preview key 1) was
     * invisible and the cause could not be diagnosed. */
    private val liveErrorHistory = ArrayDeque<LiveErrorEntry>()

    /**
     * v1.0.4 — consecutive failures per (model, key) combination,
     * keyed "modelIndex:keyIndex". A combination is retried once before
     * advancing to the next one: a one-off server hiccup must not burn
     * a model/key. Cleared for a combination when it connects.
     */
    private val comboFailures = HashMap<String, Int>()

    private data class LiveErrorEntry(
        val timeMs: Long,
        val model: String,
        val keyNo: Int,
        val message: String
    )

    private val utteranceBytes = ByteArrayOutputStream()
    private val pendingText = StringBuilder()
    private var audioPlayedThisTurn = false

    /** Audio spoken before Live is connected: kept (max ~10 s) and flushed
     * the moment onConnected arrives, instead of being thrown away. */
    private val pendingChunks = ArrayDeque<ByteArray>()
    private var pendingBytes = 0

    private val levelTicker = object : Runnable {
        override fun run() {
            if (!capturing) return
            val rms = try { vad?.lastRms ?: 0.0 } catch (_: Exception) { 0.0 }
            if (rms > 50.0) lastHeardAtMs = System.currentTimeMillis()
            // v1.0.4 — LOG scale. The old linear scale (rms/2000*1000)
            // saturated at 1000 for any loud sound (the v1.0.3 screenshot
            // showed a flat 1000), hiding how loud it really was, and
            // background music kept the VAD thinking speech was constant.
            // Log scale: rms 8000 -> 1000, rms 2000 -> ~846, rms 600
            // (VAD threshold, v1.0.5 default) -> ~623, rms 50 -> ~396.
            val level = (ln(1.0 + rms) / ln(1.0 + 8000.0) * 1000.0)
                .toInt().coerceIn(0, 1000)
            broadcast(TYPE_LEVEL, level = level, rms = rms)
            // Silence diagnosis: capturing but nothing heard for a while.
            val now = System.currentTimeMillis()
            if (lastOutputAtMs == 0L && lastHeardAtMs > 0L &&
                now - lastHeardAtMs > 15_000L
            ) {
                setStatus("Không nghe thấy tiếng — kiểm tra video có phát tiếng không")
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    /**
     * v1.0.5 — duck ticker: every 500 ms, compare the dub voice state
     * with the current duck state and only send a TYPE_DUCK broadcast
     * when it CHANGES, so the WebView volume is not hammered every tick.
     * Speaking -> original drops to the user's level (default 80% in
     * v1.0.6, 0..1 float from Prefs.originalVolume — verified 0.5 for
     * 50%, NOT 50). Silent -> original back to 100% so the user can
     * hear the film's own sound between dubbed lines.
     */
    private val duckTicker = object : Runnable {
        override fun run() {
            if (!capturing) return
            try {
                val speaking = try { voicePlayer?.isSpeaking() == true } catch (_: Exception) { false }
                if (speaking && !ducked) {
                    ducked = true
                    broadcast(TYPE_DUCK, message = Prefs.originalVolume(this@DubbingService).toString())
                } else if (!speaking && ducked) {
                    ducked = false
                    broadcast(TYPE_DUCK, message = "1.0")
                }
            } catch (e: Exception) {
                Log.w(TAG, "duckTicker failed", e)
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!capturing) return
            val now = System.currentTimeMillis()
            if (!useGroqOnly) {
                if (lastOutputAtMs > 0 && now - lastOutputAtMs > SILENCE_LIMIT_MS) {
                    if (reconnects < MAX_RECONNECTS) {
                        reconnects++
                        recordLiveError("Im lặng 60s sau khi đã có output — nối lại Live")
                        Log.w(TAG, "Silent for 60s, reconnecting ($reconnects/$MAX_RECONNECTS)")
                        reconnectLive()
                    } else {
                        Log.w(TAG, "Live silent after $MAX_RECONNECTS reconnects, Groq fallback")
                        recordLiveError("Im lặng 60s, đã hết $MAX_RECONNECTS lần nối lại — chuyển Groq")
                        switchToGroq("Gemini Live im lặng quá lâu, chuyển sang dự phòng Groq")
                    }
                } else if (lastOutputAtMs == 0L && !liveConnected &&
                    now - lastLiveAttemptAtMs > NO_OUTPUT_LIMIT_MS
                ) {
                    // v1.0.2: the old watchdog only ran after output had
                    // started (lastOutputAtMs > 0), so a session that NEVER
                    // connected — dead model, bad key — sat silent forever.
                    // v1.0.3: advance through the (model, key) order; when
                    // every combination is exhausted, fall back to Groq.
                    if (reconnects < MAX_RECONNECTS && advanceAttempt()) {
                        reconnects++
                        Log.w(TAG, "Live never connected/output in 25s, retrying ($reconnects/$MAX_RECONNECTS) with ${GeminiLiveClient.shortModelName(currentLiveModel())} key ${keyIndex + 1}")
                        reconnectLive()
                    } else {
                        Log.w(TAG, "Live never produced output, Groq fallback")
                        recordLiveError("Không có output nào sau 25s, đã thử hết các tổ hợp — chuyển Groq")
                        switchToGroq("Gemini Live không nối được, chuyển sang dự phòng Groq")
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
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopDub()
                    return START_NOT_STICKY
                }
                ACTION_START_MIC -> {
                    startDub(0, null, micOnly = true)
                    return START_NOT_STICKY
                }
                ACTION_START -> {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    }
                    startDub(code, data, micOnly = false)
                    return START_STICKY
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
            try { broadcast(TYPE_ERROR, message = "Không mở được lồng tiếng: ${e.message}") } catch (_: Exception) { }
            try { stopSelf() } catch (_: Exception) { }
        }
        return START_NOT_STICKY
    }

    private fun startDub(resultCode: Int, resultData: Intent?, micOnly: Boolean) {
        if (capturing) return
        this.micOnly = micOnly
        stopped = false
        try { createChannel() } catch (e: Exception) { Log.w(TAG, "createChannel failed", e) }
        val notif = try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DubLive")
                .setContentText(getString(R.string.dub_running))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Notification build failed", e)
            stopSelf()
            return
        }
        // Android 16 rule: startForeground (with the mediaProjection type)
        // MUST happen before getMediaProjection() is called. If this throws
        // (e.g. started from background), there is no capture path at all —
        // report it and stop cleanly instead of crashing.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            broadcast(TYPE_ERROR, message = "Không mở được dịch vụ nền: ${e.message}")
            broadcast(TYPE_STATE, state = STATE_STOPPED)
            try { stopSelf() } catch (_: Exception) { }
            return
        }

        val keys = Prefs.geminiKeys(this)
        if (keys.isEmpty()) {
            broadcast(TYPE_ERROR, message = getString(R.string.need_key_first))
            broadcast(TYPE_STATE, state = STATE_STOPPED)
            stopForegroundSafely()
            try { stopSelf() } catch (_: Exception) { }
            return
        }
        // Both capture paths need RECORD_AUDIO on Android 16 (system audio
        // via AudioPlaybackCapture AND the mic). The activity asks first,
        // but re-check here: a denied permission must stop the service
        // cleanly, never crash it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            broadcast(TYPE_ERROR, message = getString(R.string.mic_no_permission))
            broadcast(TYPE_STATE, state = STATE_STOPPED)
            stopForegroundSafely()
            try { stopSelf() } catch (_: Exception) { }
            return
        }
        try {
            voicePlayer = VoicePlayer(this) {
                tracker?.onDubSpeechStarted()
            }.also {
                it.start()
                // v1.0.6 — track/TTS at max (1.0f); the Settings slider
                // now drives the SYSTEM call-stream volume, which is
                // what the user actually hears. Apply the saved slider
                // value when dubbing starts so it takes effect at once.
                try { it.setDubVolume(1.0f) } catch (_: Exception) { }
                try { VoicePlayer.applyCallVolume(this, Prefs.dubVolumePct(this)) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VoicePlayer failed", e)
            voicePlayer = null
            broadcast(TYPE_ERROR, message = "Không mở được loa đọc giọng: ${e.message}")
        }
        try {
            tracker = DubDelayTracker(
                Prefs.maxDelayMs(this),
                // v1.0.4: the delay tracker only samples once a translation
                // has actually arrived; before that the delay stays frozen
                // at the 5.2 s seed instead of ballooning to 19+ s.
                hasDubOutput = { lastOutputAtMs > 0L }
            ) { delayMs ->
                broadcast(TYPE_HUD, delayMs = delayMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DubDelayTracker failed", e)
            tracker = null
        }
        try {
            groq = GroqFallback(Prefs.groqKey(this), keys[0], Prefs.sourceLang(this))
        } catch (e: Exception) {
            Log.e(TAG, "GroqFallback failed", e)
            groq = null
        }
        useGroqOnly = false
        reconnects = 0
        modelIndex = 0
        reconnectPending = false
        comboFailures.clear()
        liveErrorHistory.clear()
        broadcastLiveErrors()
        ducked = false
        subtitleGen = 0
        // v1.0.5 — VAD threshold for this session (Settings, default 600).
        vadThreshold = try { Prefs.vadThreshold(this).toDouble() } catch (_: Exception) { 600.0 }
        rotateAtMs = System.currentTimeMillis() + KEY_ROTATE_MS
        lastOutputAtMs = 0L
        startedAtMs = System.currentTimeMillis()
        lastLiveAttemptAtMs = 0L
        lastHeardAtMs = 0L
        lastStatus = ""
        synchronized(pendingChunks) { pendingChunks.clear() }
        pendingBytes = 0
        setStatus("Đang nối Gemini Live…")

        if (!micOnly && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, resultData)
                if (mediaProjection == null) {
                    broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
                } else {
                    val cb = object : MediaProjection.Callback() {
                        override fun onStop() {
                            // User (or OriginOS) stopped sharing mid-session.
                            // Switch to the mic instead of dying with the token.
                            Log.w(TAG, "MediaProjection stopped by system/user, switching to mic")
                            mainHandler.post {
                                if (capturing && !stopped) {
                                    broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
                                    switchCaptureToMic()
                                }
                            }
                        }
                    }
                    projectionCallback = cb
                    try {
                        mediaProjection?.registerCallback(cb, mainHandler)
                    } catch (e: Exception) {
                        Log.w(TAG, "registerCallback failed", e)
                    }
                }
            } catch (e: Exception) {
                // Single-app token (user picked DubLive itself), OriginOS
                // blocking the capture, or an expired token: fall back to mic.
                Log.w(TAG, "getMediaProjection failed, falling back to mic", e)
                mediaProjection = null
                broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
            }
        }
        capturing = true
        try {
            startCapture()
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
        }
        try {
            connectLive()
        } catch (e: Exception) {
            Log.e(TAG, "connectLive failed", e)
            switchToGroq("Gemini Live lỗi kết nối (${e.message}), chuyển sang dự phòng Groq")
        }
        broadcast(TYPE_STATE, state = STATE_RUNNING)
        // v1.0.5: do NOT duck the original audio here, and do NOT duck
        // it once forever either (the old applyDuckOnce). The duck ticker
        // ducks only while the dubbed voice is really speaking and
        // restores 100% when it goes silent, so the original sound stays
        // audible between dubbed lines. AudioPlaybackCapture records the
        // sound after ducking, so ducking at start would also have
        // starved the VAD (the v1.0.2 lesson).
        mainHandler.postDelayed(watchdog, 10_000L)
        mainHandler.postDelayed(levelTicker, 500L)
        mainHandler.postDelayed(duckTicker, 500L)
    }

    /** Stop the old (projection) capture thread, then restart it on the mic. */
    private fun switchCaptureToMic() {
        if (stopped) return
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null
        val oldThread = captureThread
        capturing = false
        Thread({
            try { oldThread?.join(1500) } catch (_: Exception) { }
            if (stopped) return@Thread
            capturing = true
            mainHandler.post {
                if (!stopped) {
                    try { startCapture() } catch (e: Exception) {
                        Log.e(TAG, "mic restart failed", e)
                        broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
                    }
                }
            }
        }, "DubLive-SwitchMic").start()
    }

    private fun connectLive() {
        val keys = Prefs.geminiKeys(this)
        if (keys.isEmpty()) return
        val key = keys[keyIndex % keys.size]
        val model = GeminiLiveClient.LIVE_MODELS[modelIndex % GeminiLiveClient.LIVE_MODELS.size]
        try { liveClient?.close() } catch (_: Exception) { }
        liveConnected = false
        reconnectPending = false
        lastLiveAttemptAtMs = System.currentTimeMillis()
        setStatus("Đang nối Gemini Live… ($model)")
        Log.i(TAG, "Connecting Live: model=$model key=${keyIndex % keys.size + 1}")
        // v1.0.4 generation: this attempt's id. Every callback below
        // returns immediately when its generation is no longer current —
        // a stale socket's late onClosed/onError/onMessage must never
        // touch the live state or kill the new socket. (Together with the
        // client's own socket-identity check this is the ALDA guard
        // DubLive was missing; the old closingForReconnect flag is gone.)
        val gen = liveGeneration.incrementAndGet()
        val isCurrent: () -> Boolean = { gen == liveGeneration.get() }
        val client = try {
            GeminiLiveClient(
                apiKey = key,
                voiceName = Prefs.voiceName(this),
                sourceLang = Prefs.sourceLang(this),
                wholeSentence = Prefs.wholeSentence(this),
                listener = object : GeminiLiveClient.Listener {
                    override fun onConnected() {
                        if (!isCurrent()) return
                        liveConnected = true
                        reconnects = 0
                        reconnectPending = false
                        // This combination works: its failure count resets.
                        comboFailures.remove(comboKey())
                        Log.i(TAG, "Gemini Live connected (key ${keyIndex % keys.size + 1}, model=$model)")
                        setStatus("Đã nối, đang nghe…")
                        flushPendingAudio()
                    }

                    override fun onAudioChunk(pcm24k: ByteArray) {
                        if (!isCurrent()) return
                        lastOutputAtMs = System.currentTimeMillis()
                        audioPlayedThisTurn = true
                        try { voicePlayer?.playAudio(pcm24k) } catch (e: Exception) {
                            Log.w(TAG, "playAudio failed", e)
                        }
                    }

                    override fun onTranslatedText(text: String, isFinal: Boolean) {
                        if (!isCurrent()) return
                        lastOutputAtMs = System.currentTimeMillis()
                        pendingText.append(text)
                        showSubtitle(pendingText.toString())
                    }

                    override fun onTurnComplete() {
                        if (!isCurrent()) return
                        val text = pendingText.toString().trim()
                        pendingText.clear()
                        if (!SpeechFilter.isMeaningful(text)) {
                            // Noise-only turn: hide the subtitle at once
                            // and cancel its 6 s auto-hide timer.
                            subtitleGen++
                            broadcast(TYPE_SUBTITLE, text = "")
                        } else if (!audioPlayedThisTurn) {
                            // Model returned text but no audio: read it with TTS.
                            try {
                                voicePlayer?.speakText(text, tracker?.currentDelayMs ?: 0L)
                            } catch (e: Exception) {
                                Log.w(TAG, "speakText failed", e)
                            }
                        }
                        audioPlayedThisTurn = false
                        try { voicePlayer?.resetTurnFlag() } catch (_: Exception) { }
                    }

                    override fun onInputText(text: String) {
                        if (!isCurrent()) return
                        Log.i(TAG, "Heard (source): $text")
                    }

                    override fun onError(message: String) {
                        if (!isCurrent()) return
                        Log.w(TAG, "Live error: $message")
                        if (capturing && !useGroqOnly) handleLiveError(message)
                    }

                    override fun onClosed(code: Int, reason: String) {
                        if (!isCurrent()) return
                        liveConnected = false
                        // v1.0.4: a close of the CURRENT socket is a real
                        // failure — but carry the code + reason so the
                        // history can tell 1000/1006/1011 apart. (A close
                        // of an OLD socket never reaches here: the
                        // generation guard above drops it, and the client
                        // drops it by socket identity too.)
                        if (capturing && !useGroqOnly && !reconnectPending) {
                            handleLiveError(
                                "Live tự ngắt (mã $code: ${reason.ifBlank { "không rõ lý do" }})"
                            )
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "GeminiLiveClient creation failed", e)
            handleLiveError("Gemini Live lỗi (${e.message})")
            return
        }
        liveClient = client
        try {
            client.connect(model)
        } catch (e: Exception) {
            Log.e(TAG, "GeminiLiveClient.connect failed", e)
            handleLiveError("Gemini Live lỗi kết nối (${e.message})")
        }
    }

    /**
     * v1.0.4 — per-combination retry + error history.
     *
     * Retry policy: a (model, key) combination is retried ONCE on the
     * same combination before advancing — a one-off server hiccup must
     * not burn a model/key. Only after 2 consecutive failures on the
     * same combination does the order advance (same model, next key
     * first, then next model, key 1 — a key/quota error is the common
     * case for a model known to work). The decision is driven by the
     * per-combination counts in [comboFailures]; the shared [reconnects]
     * counter is only a safety cap (6 combos x 2 attempts).
     *
     * Every failure is also recorded FULL (never truncated to 90 chars)
     * in [liveErrorHistory] with time, model and key number, and pushed
     * to the UI — v1.0.3 only ever showed the LAST error on the status
     * line, so the first failure (translate-preview key 1) was invisible
     * and the cause could not be diagnosed.
     */
    private fun handleLiveError(message: String) {
        if (!capturing || useGroqOnly || reconnectPending) return
        reconnectPending = true
        val failedModel = GeminiLiveClient.shortModelName(currentLiveModel())
        recordLiveError(message)
        val keyNote =
            if (GeminiLiveClient.isKeyError(message)) " (lỗi key/hạn mức?)" else ""
        setStatus("Lỗi Live [$failedModel]: ${shortError(message)}")
        Log.w(TAG, "Live error on $failedModel key ${keyIndex + 1}$keyNote: $message")
        val combo = comboKey()
        val fails = (comboFailures[combo] ?: 0) + 1
        comboFailures[combo] = fails
        if (fails < 2 && reconnects < MAX_RECONNECTS) {
            reconnects++
            Log.w(TAG, "Live error, retrying SAME combo ($reconnects/$MAX_RECONNECTS): $failedModel key ${keyIndex + 1} (failure $fails/2)")
            scheduleReconnect(2000L)
        } else if (reconnects < MAX_RECONNECTS && advanceAttempt()) {
            reconnects++
            val nextModel = GeminiLiveClient.shortModelName(currentLiveModel())
            Log.w(TAG, "Live error, advancing ($reconnects/$MAX_RECONNECTS) to $nextModel key ${keyIndex + 1}")
            scheduleReconnect(2000L)
        } else {
            reconnectPending = false
            switchToGroq("Gemini Live lỗi ($message), chuyển sang dự phòng Groq")
        }
    }

    /** "modelIndex:keyIndex" — key for [comboFailures]. */
    private fun comboKey(): String = "$modelIndex:$keyIndex"

    /** Record one Live failure (full message) into the 8-entry history
     * and push the whole history to the UI for the "Xem lỗi Live" dialog. */
    private fun recordLiveError(message: String) {
        val entry = LiveErrorEntry(
            System.currentTimeMillis(),
            currentLiveModel(),
            keyIndex + 1,
            message
        )
        liveErrorHistory.addLast(entry)
        while (liveErrorHistory.size > 8) liveErrorHistory.removeFirst()
        broadcastLiveErrors()
    }

    private fun broadcastLiveErrors() {
        broadcast(TYPE_LIVE_ERRORS, message = liveErrorHistoryText())
    }

    private fun liveErrorHistoryText(): String {
        if (liveErrorHistory.isEmpty()) return ""
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return liveErrorHistory.joinToString("\n") { e ->
            "${fmt.format(java.util.Date(e.timeMs))} · [${GeminiLiveClient.shortModelName(e.model)}] key ${e.keyNo} · ${e.message}"
        }
    }

    /** Model the current connection attempt uses. */
    private fun currentLiveModel(): String =
        GeminiLiveClient.LIVE_MODELS[modelIndex % GeminiLiveClient.LIVE_MODELS.size]

    /**
     * Move to the next (model, key) combination in the fixed attempt
     * order: same model with the next key first, then the next model
     * with the first key. Returns false when every combination has
     * already been tried.
     */
    private fun advanceAttempt(): Boolean {
        val keyCount = Prefs.geminiKeys(this).size.coerceAtLeast(1)
        return if (keyIndex + 1 < keyCount) {
            keyIndex++
            true
        } else if (modelIndex + 1 < GeminiLiveClient.LIVE_MODELS.size) {
            keyIndex = 0
            modelIndex++
            true
        } else {
            false
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        mainHandler.postDelayed({
            reconnectPending = false
            if (capturing && !useGroqOnly && !stopped) reconnectLive()
        }, delayMs)
    }

    private fun shortError(message: String): String {
        val m = message.trim()
        return if (m.length > 90) m.substring(0, 90) + "…" else m
    }

    /** Keep the last status line and push it to the UI (persistent). */
    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        broadcast(TYPE_STATUS, message = text)
    }

    /**
     * v1.0.5 — subtitle trim + auto-hide. The translate-preview model
     * streams continuously and rarely sends turnComplete, so the old
     * code appended every fragment into pendingText and broadcast the
     * WHOLE text: it grew without bound and the user screenshot showed
     * one giant block covering the whole video. Now only the last 1–2
     * sentences are shown (max ~120 chars; if the text is over 200
     * chars only the last 120 are kept), and the subtitle hides itself
     * 6 s after the last fragment so it never lingers. A new subtitle
     * bumps [subtitleGen], so a stale timer cannot hide a newer one.
     */
    private fun showSubtitle(fullText: String) {
        val shown = trimSubtitle(fullText)
        subtitleGen++
        val gen = subtitleGen
        broadcast(TYPE_SUBTITLE, text = shown)
        mainHandler.postDelayed({
            if (gen == subtitleGen && capturing) {
                broadcast(TYPE_SUBTITLE, text = "")
            }
        }, 6000L)
    }

    private fun trimSubtitle(full: String): String {
        val t = full.trim()
        if (t.length <= 120) return t
        // Split into sentences, keeping the terminator with its sentence.
        val sentences = t.split(Regex("(?<=[.?!…。！？])\\s*|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val tail = if (sentences.size >= 2) {
            sentences.takeLast(2).joinToString(" ")
        } else {
            t
        }
        if (tail.length <= 120) return tail
        // Still too long: keep the last 120 chars, dropping a cut word.
        val cut = tail.takeLast(120).trimStart()
        val firstSpace = cut.indexOf(' ')
        return if (firstSpace in 1..20) cut.substring(firstSpace + 1) else cut
    }

    /** Queue audio spoken while Live is still connecting (max ~6 s,
     * ALDA FIX36). Flushed paced (2 blocks/tick) on setupComplete. */
    private fun bufferAudio(bytes: ByteArray) {
        synchronized(pendingChunks) {
            pendingChunks.addLast(bytes)
            pendingBytes += bytes.size
            while (pendingBytes > PRECONNECT_MAX_BYTES && pendingChunks.isNotEmpty()) {
                pendingBytes -= pendingChunks.removeFirst().size
            }
        }
    }

    /** Queue buffered pre-connect audio now that Live is connected.
     * v1.0.4 — ALDA FIX36: the backlog is ENQUEUED into the client's
     * send queue and drains 2 blocks per tick (2x real-time); it is
     * NEVER blasted onto the socket in one loop. v1.0.3 sent the whole
     * ~10 s buffer at once on setupComplete, flooding the socket queue
     * past the 64 KB congestion drop — a plausible trigger for the server
     * closing the session right after connect. The remaining backlog
     * drains through the normal sendAudio ticks as speech continues. */
    private fun flushPendingAudio() {
        val chunks: List<ByteArray>
        synchronized(pendingChunks) {
            chunks = pendingChunks.toList()
            pendingChunks.clear()
            pendingBytes = 0
        }
        if (chunks.isEmpty()) return
        Log.i(TAG, "Queueing ${chunks.size} buffered audio chunks to Live (paced drain)")
        val client = liveClient ?: return
        try {
            for (bytes in chunks) client.queueAudio(bytes)
            client.drainPending(2)
        } catch (e: Exception) {
            Log.w(TAG, "flush queueAudio failed", e)
        }
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
        // v1.0.4: no closingForReconnect flag — close() is async and the
        // flag was racy (see liveGeneration). The old socket's late
        // onClosed is dropped by the generation guard in connectLive().
        try { liveClient?.close() } catch (_: Exception) { }
        try { connectLive() } catch (e: Exception) {
            Log.e(TAG, "reconnectLive failed", e)
        }
    }

    private fun switchToGroq(reason: String) {
        if (useGroqOnly) return
        useGroqOnly = true
        try { liveClient?.close() } catch (_: Exception) { }
        // v1.0.3: the Groq pipeline object was built once in startDub with
        // the key as it was then. If the user typed the Groq key while the
        // service was already running, the old object still holds an empty
        // key and every utterance fails with "chưa có key Groq" even though
        // Settings already has the key. Rebuild it here with the CURRENT
        // key (and Gemini key) so the fallback works right away.
        try {
            groq = GroqFallback(
                Prefs.groqKey(this),
                Prefs.geminiKeys(this).firstOrNull().orEmpty(),
                Prefs.sourceLang(this)
            )
        } catch (e: Exception) {
            Log.w(TAG, "GroqFallback rebuild failed", e)
        }
        broadcast(TYPE_FALLBACK, message = reason)
        if (Prefs.groqKey(this).isEmpty()) {
            setStatus("Lỗi: $reason — chưa có key Groq. Nhập key Groq trong Cài đặt, Dừng lồng tiếng hẳn rồi Bắt đầu lại.")
            broadcast(TYPE_ERROR, message = "Chưa có key Groq dự phòng — nhập key trong Cài đặt rồi Dừng lồng tiếng hẳn và Bắt đầu lại")
        } else {
            setStatus("Đang dùng dự phòng Groq")
        }
    }

    private fun minBufferSize(): Int {
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.w(TAG, "getMinBufferSize failed", e)
            AudioRecord.ERROR_BAD_VALUE
        }
        return if (minBuf <= 0) 6400 else minBuf
    }

    /** Null when the projection token is unusable (single-app token, OEM block). */
    private fun buildPlaybackRecord(minBuf: Int): AudioRecord? {
        val proj = mediaProjection ?: return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val record = AudioRecord.Builder()
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
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                try { record.release() } catch (_: Exception) { }
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture unavailable", e)
            null
        }
    }

    /** Null when RECORD_AUDIO is missing or the mic is busy. */
    private fun buildMicRecord(minBuf: Int): AudioRecord? {
        return try {
            val record = AudioRecord.Builder()
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
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                try { record.release() } catch (_: Exception) { }
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mic AudioRecord unavailable", e)
            null
        }
    }

    private fun startCapture() {
        try {
            vad = VadDetector(
                // v1.0.5 — threshold from Settings (default 600, ALDA's
                // value) instead of the hard-coded 250.
                energyThreshold = vadThreshold,
                listener = object : VadDetector.Listener {
                override fun onSpeechStart(preRollAndHead: ShortArray) {
                    try { tracker?.startUtterance() } catch (_: Exception) { }
                    try { voicePlayer?.resetTurnFlag() } catch (_: Exception) { }
                    utteranceBytes.reset()
                    val bytes = shortsToBytes(preRollAndHead)
                    utteranceBytes.write(bytes)
                    setStatus(if (useGroqOnly) "Đang nghe bằng Groq…" else "Đang nghe câu nói…")
                    if (liveConnected) {
                        flushPendingAudio()
                        try { liveClient?.sendAudio(bytes) } catch (e: Exception) {
                            Log.w(TAG, "sendAudio failed", e)
                        }
                    } else {
                        // v1.0.2: never throw away speech just because Live
                        // is still connecting — buffer it (max ~10 s) and
                        // flush it the moment onConnected arrives.
                        bufferAudio(bytes)
                    }
                }

                override fun onSpeechChunk(samples: ShortArray) {
                    val bytes = shortsToBytes(samples)
                    utteranceBytes.write(bytes)
                    if (liveConnected) {
                        // v1.0.5 — second noise gate: inside an utterance
                        // the VAD also forwards quiet frames (music/noise
                        // between words) until 700 ms of silence ends it.
                        // A chunk below the VAD threshold is noise, not
                        // speech: do NOT send it to Live, or the model
                        // invents words for it. (It still goes into
                        // utteranceBytes for the Groq path — Whisper
                        // tolerates noise far better than Live does.)
                        if (rmsOf(samples) >= vadThreshold) {
                            try { liveClient?.sendAudio(bytes) } catch (e: Exception) {
                                Log.w(TAG, "sendAudio failed", e)
                            }
                        }
                    } else {
                        bufferAudio(bytes)
                    }
                }

                override fun onSpeechEnd() {
                    try { tracker?.endUtterance() } catch (_: Exception) { }
                    setStatus(if (useGroqOnly) "Đang dịch bằng Groq…" else "Đang dịch…")
                    if (liveConnected) {
                        try { liveClient?.endTurn() } catch (e: Exception) {
                            Log.w(TAG, "endTurn failed", e)
                        }
                    }
                    if (useGroqOnly) {
                        val pcm = utteranceBytes.toByteArray()
                        if (pcm.isEmpty()) {
                            Log.w(TAG, "Groq: empty utterance, nothing to transcribe")
                            return
                        }
                        if (pcm.size <= 3200) {
                            // Blip shorter than ~100 ms: ignore, no error.
                            return
                        }
                        val groqClient = groq
                        if (groqClient == null) {
                            setStatus("Lỗi Groq: chưa khởi tạo được bộ dự phòng")
                            broadcast(TYPE_ERROR, message = "Lỗi Groq: chưa khởi tạo được bộ dự phòng")
                            return
                        }
                        // v1.0.3: refresh keys right before the call — a Groq
                        // key typed mid-session takes effect immediately.
                        try {
                            groqClient.updateKeys(
                                Prefs.groqKey(this@DubbingService),
                                Prefs.geminiKeys(this@DubbingService).firstOrNull().orEmpty()
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Groq updateKeys failed", e)
                        }
                        try {
                            groqClient.transcribeAndTranslate(pcm, object : GroqFallback.Listener {
                                override fun onTranslated(text: String) {
                                    lastOutputAtMs = System.currentTimeMillis()
                                    setStatus("Đang dùng dự phòng Groq")
                                    showSubtitle(text)
                                    try {
                                        voicePlayer?.speakText(text, tracker?.currentDelayMs ?: 0L)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "speakText failed", e)
                                    }
                                }

                                override fun onError(message: String) {
                                    setStatus("Lỗi Groq: $message")
                                    broadcast(TYPE_ERROR, message = message)
                                }

                                override fun onProgress(message: String) {
                                    setStatus(message)
                                }
                            })
                        } catch (e: Exception) {
                            Log.w(TAG, "Groq fallback call failed", e)
                            setStatus("Lỗi Groq: ${e.message}")
                            broadcast(TYPE_ERROR, message = "Lỗi Groq: ${e.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "VadDetector failed", e)
            broadcast(TYPE_ERROR, message = "Không khởi động được bộ lọc giọng: ${e.message}")
            mainHandler.post { if (capturing) stopDub() }
            return
        }
        captureThread = Thread({
            val minBuf = minBufferSize()
            var record: AudioRecord? = null
            try {
                // Step 1: system audio via the projection token, if we have one.
                record = if (mediaProjection != null) buildPlaybackRecord(minBuf) else null
                if (record == null && mediaProjection != null) {
                    // Android 16 single-app token (user picked DubLive itself)
                    // or OriginOS blocked the capture — fall back to the mic.
                    broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
                    try { mediaProjection?.stop() } catch (_: Exception) { }
                    mediaProjection = null
                }
                // Step 2: mic (also the whole capture path in mic-only mode).
                if (record == null) record = buildMicRecord(minBuf)
                if (record == null) {
                    broadcast(
                        TYPE_ERROR,
                        message = "Không thu được tiếng: kiểm tra quyền micro trong Cài đặt ứng dụng"
                    )
                    mainHandler.post { if (capturing) stopDub() }
                    return@Thread
                }
                audioRecord = record
                // Step 3: startRecording — its own try/catch, one mic retry.
                var started = false
                try {
                    record.startRecording()
                    started = true
                } catch (e: Exception) {
                    Log.w(TAG, "startRecording failed", e)
                }
                if (!started) {
                    try { record.release() } catch (_: Exception) { }
                    audioRecord = null
                    if (mediaProjection != null) {
                        mediaProjection = null
                        record = buildMicRecord(minBuf)
                    } else {
                        record = null
                    }
                    if (record == null) {
                        broadcast(TYPE_ERROR, message = "Không thu được tiếng: micro đang bận hoặc chưa cấp quyền")
                        mainHandler.post { if (capturing) stopDub() }
                        return@Thread
                    }
                    audioRecord = record
                    try {
                        record.startRecording()
                    } catch (e: Exception) {
                        Log.w(TAG, "mic startRecording failed", e)
                        broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
                        mainHandler.post { if (capturing) stopDub() }
                        return@Thread
                    }
                }
                val buf = ShortArray(1600) // 100 ms at 16 kHz
                while (capturing) {
                    val n = try {
                        record.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord.read failed", e)
                        break
                    }
                    if (n > 0) {
                        try { vad?.feed(buf, n) } catch (e: Exception) {
                            Log.w(TAG, "VAD feed failed", e)
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error $n")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed: ${e.message}")
                broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
                mainHandler.post { if (capturing) stopDub() }
            } finally {
                try { record?.stop() } catch (_: Exception) { }
                try { record?.release() } catch (_: Exception) { }
                audioRecord = null
            }
        }, "DubLive-Capture").apply { start() }
    }

    private fun stopForegroundSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }

    private fun stopDub() {
        stopped = true
        capturing = false
        try { mainHandler.removeCallbacks(watchdog) } catch (_: Exception) { }
        try { mainHandler.removeCallbacks(levelTicker) } catch (_: Exception) { }
        try { mainHandler.removeCallbacks(duckTicker) } catch (_: Exception) { }
        // v1.0.5 — restore the original volume (the duck ticker is gone
        // now, so a ducked state would otherwise stick).
        if (ducked) {
            ducked = false
            try { broadcast(TYPE_DUCK, message = "1.0") } catch (_: Exception) { }
        }
        try { broadcast(TYPE_SUBTITLE, text = "") } catch (_: Exception) { }
        try { captureThread?.join(1000) } catch (_: Exception) { }
        captureThread = null
        try { liveClient?.close() } catch (_: Exception) { }
        liveClient = null
        try { tracker?.stop() } catch (_: Exception) { }
        tracker = null
        try { vad?.reset() } catch (_: Exception) { }
        vad = null
        try { voicePlayer?.release() } catch (_: Exception) { }
        voicePlayer = null
        synchronized(pendingChunks) { pendingChunks.clear() }
        pendingBytes = 0
        ducked = false
        reconnectPending = false
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (_: Exception) { }
        projectionCallback = null
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null
        try { setStatus("Đã dừng lồng tiếng") } catch (_: Exception) { }
        try { broadcast(TYPE_STATE, state = STATE_STOPPED) } catch (_: Exception) { }
        stopForegroundSafely()
        try { stopSelf() } catch (_: Exception) { }
    }

    private fun broadcast(type: String, text: String? = null, delayMs: Long? = null,
                          state: String? = null, message: String? = null,
                          level: Int? = null, rms: Double? = null) {
        try {
            val intent = Intent(ACTION_DUB_EVENT).setPackage(packageName)
            intent.putExtra(EXTRA_TYPE, type)
            if (text != null) intent.putExtra(EXTRA_TEXT, text)
            if (delayMs != null) intent.putExtra(EXTRA_DELAY_MS, delayMs)
            if (state != null) intent.putExtra(EXTRA_STATE, state)
            if (message != null) intent.putExtra(EXTRA_MESSAGE, message)
            if (level != null) intent.putExtra(EXTRA_LEVEL, level)
            if (rms != null) intent.putExtra(EXTRA_RMS, rms)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast failed", e)
        }
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

    /** v1.0.5 — RMS of one PCM chunk, for the noise gate in onSpeechChunk. */
    private fun rmsOf(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(sum / samples.size)
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    override fun onDestroy() {
        try {
            if (capturing) stopDub()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
