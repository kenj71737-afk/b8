package com.dublive.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
import kotlin.math.roundToInt

/**
 * Foreground service that owns the whole dub pipeline.
 *
 * AUDIO PATHS (v1.0.11 — the film sound reaches the VAD three ways):
 *  - TAP_WEB (primary for web films/YouTube): the sound comes
 *    straight from the page — MediaElementSource -> DelayNode(D) ->
 *    speakers on USAGE_MEDIA, plus a ScriptProcessor tap that sends
 *    live 16 kHz mono PCM to the VAD. NO system capture runs, so the
 *    dub voice on MEDIA cannot be re-captured (no echo).
 *  - TAP_LOCAL (local files): the ExoPlayer tap processor copies the
 *    normalised 48 kHz stereo PCM into the service; the player itself
 *    is muted (player.volume = 0). No capture runs either.
 *  - CAPTURE (fallback when a page blocks the tap — CORS/DRM, e.g.
 *    NetShort): system audio stereo 48 kHz (AudioPlaybackCapture via
 *    MediaProjection, mic fallback mono 16 kHz) -> DelayedBackground-
 *    Player: the raw stereo replayed D seconds late on the
 *    voice-call stream while STREAM_MUSIC is muted (ALDA FIX37) —
 *    the original audio matches the delayed picture; the VAD/Live
 *    path gets a mono 16 kHz downmix.
 *  - MIC: the room microphone.
 *  All paths feed the same pipeline: VAD (speech >= 150 ms, 500 ms
 *  pre-roll) -> Gemini Live (speech-to-speech) with watchdog + key
 *  rotation -> Groq Whisper fallback when Live is unusable ->
 *  VoicePlayer: the dubbed voice HELD until (utterance start + D)
 *  (ALDA FIX32/FIX33 hold), or Vietnamese TTS with the same hold ->
 *  DubDelayTracker (continuous slice measurement + LatencyEma,
 *  clamp 3..max s).
 *
 * v1.0.9 — ALDA sync, all three pieces: the picture waits D (canvas),
 * the original audio waits D (delayed background player), the dub
 * voice waits until (utterance start + D) (voice hold). Before this
 * version only the picture waited, so the three never matched.
 * The direct audio is muted at the SYSTEM level (STREAM_MUSIC -> 0),
 * never via the video element volume — element volume kills the
 * capture itself (the v1.0.2 lesson), while the system stream volume
 * does not touch the AudioPlaybackCapture tap.
 *
 * LESSONS FROM THE OLD APP, built into this service:
 *  - A Gemini key can die SILENTLY after ~5 minutes (no error, just no
 *    output). Watchdog: if 60 s pass with no translated output after output
 *    had started, reconnect (max 5 times), and rotate to the other key
 *    every 5 minutes. Only translated output counts as "alive".
 *  - WebAudio tapping IS the primary path now (v1.0.11): the film
 *    sound comes straight from the page (MediaElementSource ->
 *    DelayNode -> speakers, ScriptProcessor tap -> Live), so the dub
 *    voice can play on USAGE_MEDIA without being re-captured. The
 *    system capture is only the fallback for pages that block the tap
 *    (CORS/DRM — NetShort). A tainted cross-origin video is skipped
 *    entirely (ALDA FIX42: never attach, or the graph would output
 *    silence forever).
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
        /** v1.0.7 — manual delay nudge from the "Trễ −1s / +1s" buttons. */
        const val ACTION_ADJUST_DELAY = "com.dublive.live.ADJUST_DELAY"
        const val EXTRA_DELAY_DELTA_MS = "delay_delta_ms"
        /** v1.0.9 — enable/disable the video delay from the activity
         * (delay toggle button, site fullscreen enter/exit). When the
         * picture goes live, the delayed original audio and the voice
         * hold must go live too, or the sound would lag a live picture. */
        const val ACTION_SET_DELAY_ENABLED = "com.dublive.live.SET_DELAY_ENABLED"
        const val EXTRA_ENABLED = "enabled"
        /** v1.0.11 — start in WebAudio-tap mode (no projection token). */
        const val ACTION_START_TAP = "com.dublive.live.START_TAP"
        /** v1.0.11 — start in local-file tap mode (no projection token). */
        const val ACTION_START_LOCAL_TAP = "com.dublive.live.START_LOCAL_TAP"
        /** v1.0.11 — the WebAudio tap died (probe) or was blocked (CORS). */
        const val ACTION_TAP_FAILED = "com.dublive.live.TAP_FAILED"
        const val ACTION_TAP_BLOCKED = "com.dublive.live.TAP_BLOCKED"
        /** v1.0.11 — first non-silent PCM arrived through the tap. */
        const val ACTION_TAP_ALIVE = "com.dublive.live.TAP_ALIVE"
        /** v1.0.11 — the local player started / paused (tap liveness). */
        const val ACTION_LOCAL_PLAYING = "com.dublive.live.LOCAL_PLAYING"
        const val EXTRA_PLAYING = "playing"
        /** v1.0.11 — give up on the projection fallback, use the mic. */
        const val ACTION_FALLBACK_MIC = "com.dublive.live.FALLBACK_MIC"
        /** v1.0.11 — apply the dub-volume slider to the current stream. */
        const val ACTION_APPLY_DUB_VOLUME = "com.dublive.live.APPLY_DUB_VOLUME"
        const val EXTRA_VOLUME_PCT = "volume_pct"
        /** v1.0.11 — which source the session dubs ("web" | "local"). */
        const val EXTRA_SOURCE = "source"
        const val SOURCE_WEB = "web"
        const val SOURCE_LOCAL = "local"
        /** v1.0.11 — this ACTION_START is a tap->capture fallback. */
        const val EXTRA_FALLBACK = "fallback"
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
        /**
         * v1.0.10 — delay diagnostics line (one line, refreshed every
         * second): current D, last sample + age, samples/60 s, queued
         * slices, voice hold, VAD state. Shown under the HUD delay
         * number and in the "Xem lỗi Live" dialog.
         */
        const val TYPE_DIAG = "diag"
        /**
         * v1.0.17 — frame check (report-only): the service asks the
         * activity for the main video's currentTime at a marker wall
         * or a burst play wall (TYPE_VIDEO_TIME_REQUEST, broadcast),
         * and the activity answers with ACTION_VIDEO_TIME (an
         * intent, not a DUB_EVENT broadcast). EXTRA_EVENT_WALL_MS
         * is the wall time of the marker / burst play the video
         * time belongs to; the service corrects the JS round trip
         * back to that wall before handing it to the tracker.
         */
        const val TYPE_VIDEO_TIME_REQUEST = "video_time_request"
        const val ACTION_VIDEO_TIME = "com.dublive.live.VIDEO_TIME"
        const val EXTRA_VIDEO_TIME_SEC = "video_time_sec"
        const val EXTRA_VIDEO_PAUSED = "video_paused"
        const val EXTRA_VIDEO_PURPOSE = "video_purpose"
        const val EXTRA_VIDEO_SEQ = "video_seq"
        const val EXTRA_EVENT_WALL_MS = "event_wall_ms"
        /**
         * v1.0.11 — audio route broadcast: which stream the dub voice
         * (and the delayed original audio) is currently on.
         * EXTRA_AUDIO_MODE is "media" | "call" | "mic"; EXTRA_MESSAGE
         * carries the Vietnamese label for the status line
         * ("Âm thanh: phương tiện" / "Âm thanh: cuộc gọi (trang
         * chặn lấy tiếng)" / "Âm thanh: micro"). The activities show
         * it next to the status so the user always knows which path
         * is in use.
         */
        const val TYPE_AUDIO_ROUTE = "audio_route"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val AUDIO_MODE_MEDIA = "media"
        const val AUDIO_MODE_CALL = "call"
        const val AUDIO_MODE_MIC = "mic"
        /**
         * v1.0.11 — the WebAudio tap died (CORS/DRM probe or blocked
         * video) or the local tap produced no PCM while playing: the
         * foreground activity must launch the screen-capture dialog so
         * the service can fall back to system capture. EXTRA_MESSAGE
         * carries the reason.
         */
        const val TYPE_NEED_PROJECTION = "need_projection"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"

        private const val TAG = "DubLive-Service"
        private const val CHANNEL_ID = "dublive"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        /** v1.0.9 — system capture runs at stereo 48 kHz so the
         * delayed original audio keeps full quality; the VAD/Live
         * path gets a mono 16 kHz downmix of the same stream. */
        private const val STEREO_RATE = 48000
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
    /**
     * v1.0.9 — delayed original audio (ALDA FIX37): the captured
     * system audio replayed D seconds late on the voice-call stream
     * (v1.0.10: was the alarm stream, removed at the user's request)
     * while STREAM_MUSIC is muted. Only active when the system capture is
     * stereo 48 kHz; mic mode and the mono-capture fallback keep the
     * old direct-audio behaviour.
     */
    private var backgroundPlayer: DelayedBackgroundPlayer? = null
    @Volatile private var delayedBackgroundActive = false
    /**
     * v1.0.11 — which audio path this session uses. TAP_WEB: the film
     * sound comes from the page's WebAudio tap (no capture at all);
     * TAP_LOCAL: it comes from the ExoPlayer tap processor (no
     * capture); CAPTURE: system capture (the v1.0.10 path); MIC: the
     * microphone. The dub voice + delayed original play on MEDIA in
     * the tap modes and on the call stream in CAPTURE/MIC.
     */
    private enum class AudioMode { TAP_WEB, TAP_LOCAL, CAPTURE, MIC }
    @Volatile private var audioMode = AudioMode.CAPTURE
    /** "web" | "local" — which source the session dubs. */
    @Volatile private var source = SOURCE_WEB
    /** v1.0.11 — the tap died and the fallback is in progress. */
    @Volatile private var tapDead = false
    /** v1.0.11 — the 90 s mic-fallback timer is armed. */
    @Volatile private var fallbackMicArmed = false
    /** v1.0.11 — tap feeder thread (drains TapAudioQueue into VAD). */
    private var tapFeederThread: Thread? = null
    @Volatile private var tapFeederRunning = false
    /** v1.0.11 — last tap PCM chunk arrival (liveness watchdog). */
    @Volatile private var lastTapChunkAtMs = 0L
    /** v1.0.11 — the local player is currently playing (activity). */
    @Volatile private var localPlaying = false
    /** v1.0.9 — false while the user turned the video delay off
     * (delay toggle / site fullscreen): the picture is live, so the
     * background delay and the voice hold must both be 0. */
    @Volatile private var delayEnabled = true
    /** System volume saved when the delayed mode mutes STREAM_MUSIC
     * (restored on stop). -1 = not saved. */
    private var initialMusicVolume = -1
    /** True while the capture thread is reading stereo 48 kHz. */
    @Volatile private var captureIsStereo48k = false
    /**
     * v1.0.9 — source-utterance start (VAD onSpeechStart) for the
     * latency sample: sample = dub burst start − utterance start.
     * [lastUtteranceStartMs] is never cleared (the TTS hold target
     * needs it after onSpeechEnd); [currentUtteranceStartMs] is
     * cleared at onSpeechEnd so a burst arriving after the utterance
     * ended cannot sample against a stale start.
     */
    @Volatile private var currentUtteranceStartMs = 0L
    @Volatile private var lastUtteranceStartMs = 0L
    /** When the first source audio of the session was sent to Live
     * (ALDA FIX32 first-audio seed). 0 = none yet. */
    @Volatile private var firstAudioSentAtMs = 0L
    /** v1.0.17 — last burst seq noted to the VoicePlayer (the
     * player measures that burst's real play start). */
    private var lastNotedBurstSeq = 0L

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
    /** v1.0.8 — hysteresis END threshold = 40% of [vadThreshold].
     * The VAD send-gate in onSpeechChunk uses this, not the start
     * threshold, so syllable dips inside a sentence still go to Live. */
    private var vadEndThreshold = 240.0
    /** v1.0.5 — subtitle auto-hide generation: bumped on every new
     * subtitle so a stale 6 s timer cannot hide a newer one. */
    private var subtitleGen = 0
    private var lastStatus = ""
    private var lastHeardAtMs = 0L
    /** v1.0.10 — last diagnostics broadcast (throttled to 1 s). */
    private var lastDiagAtMs = 0L

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
            // v1.0.10 — delay diagnostics, throttled to 1 s: the HUD
            // line under the delay number (and the "Xem lỗi Live"
            // dialog) shows D, the last sample + its age, samples in
            // the last 60 s, queued slices, voice hold and VAD state
            // — so a frozen delay is diagnosable from a screenshot.
            val now = System.currentTimeMillis()
            if (now - lastDiagAtMs >= 1000L) {
                lastDiagAtMs = now
                try {
                    tracker?.let { broadcast(TYPE_DIAG, message = it.diagnostics(now)) }
                } catch (e: Exception) {
                    Log.w(TAG, "diagnostics broadcast failed", e)
                }
                // v1.0.19 — push the tracker's time-stretch target
                // to the player (also pushed at every burst play):
                // 1.0x when caught up, up to 1.5x when the dub is
                // falling behind the picture.
                try {
                    voicePlayer?.setPlaybackSpeed(tracker?.currentStretchSpeed ?: 1.0)
                } catch (e: Exception) {
                    Log.w(TAG, "setPlaybackSpeed failed", e)
                }
            }
            // Silence diagnosis: capturing but nothing heard for a while.
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
     * with the current duck state and only duck when it CHANGES, so
     * the original volume is not hammered every tick. Speaking ->
     * original drops to the user's level (default 80% in v1.0.6,
     * 0..1 float from Prefs.originalVolume). Silent -> original back
     * to 100% so the user can hear the film's own sound between
     * dubbed lines.
     *
     * v1.0.9 — WHERE the duck lands depends on the mode. In delayed
     * mode the original audio comes from DelayedBackgroundPlayer, so
     * the duck is applied THERE (its volume is post-delay: ducking
     * the video element would duck the CAPTURE, and the duck would
     * then play D seconds late, out of step with the voice). In mic
     * mode / mono fallback the old TYPE_DUCK broadcast still ducks
     * the WebView/ExoPlayer volume directly.
     */
    private val duckTicker = object : Runnable {
        override fun run() {
            if (!capturing) return
            try {
                val speaking = try { voicePlayer?.isSpeaking() == true } catch (_: Exception) { false }
                if (speaking && !ducked) {
                    ducked = true
                    applyDuck(true)
                } else if (!speaking && ducked) {
                    ducked = false
                    applyDuck(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "duckTicker failed", e)
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    /** Apply the duck state to the right place (see duckTicker). */
    private fun applyDuck(duck: Boolean) {
        val level = if (duck) Prefs.originalVolume(this@DubbingService) else 1.0f
        if (delayedBackgroundActive) {
            try {
                backgroundPlayer?.setVolume(level, false)
            } catch (e: Exception) {
                Log.w(TAG, "bg duck failed", e)
            }
        } else {
            broadcast(TYPE_DUCK, message = level.toString())
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!capturing) return
            val now = System.currentTimeMillis()
            // v1.0.8 — live-update VAD thresholds: the user may drag the
            // "Độ nhạy nghe tiếng" slider or toggle the spectral gate in
            // Settings mid-session; re-read every 10 s and push new
            // values into the running VAD, so no restart is needed.
            // (Prefs.vadThreshold already clamps to 250–900.)
            try {
                val t = Prefs.vadThreshold(this@DubbingService).toDouble()
                if (t != vadThreshold) {
                    vadThreshold = t
                    vadEndThreshold = t * 0.4
                    vad?.updateThreshold(t)
                }
                vad?.updateSpectralGate(Prefs.spectralGateEnabled(this@DubbingService))
            } catch (e: Exception) {
                Log.w(TAG, "VAD threshold refresh failed", e)
            }
            // v1.0.11 — local tap liveness: the player is playing but
            // no PCM arrived for 10 s -> the tap processor is dead
            // (exotic codec / a renderer without our processor). Fall
            // back to system capture like the web tap.
            if (audioMode == AudioMode.TAP_LOCAL && localPlaying && !tapDead) {
                val last = lastTapChunkAtMs
                val sinceStart = now - startedAtMs
                if ((last == 0L && sinceStart > 10_000L) ||
                    (last > 0L && now - last > 10_000L)
                ) {
                    Log.w(TAG, "Local tap produced no PCM while playing, falling back")
                    onTapDead("Không lấy được tiếng file local")
                }
            }
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
                ACTION_START_TAP -> {
                    startDub(0, null, micOnly = false, source = SOURCE_WEB, tapMode = true)
                    return START_STICKY
                }
                ACTION_START_LOCAL_TAP -> {
                    startDub(0, null, micOnly = false, source = SOURCE_LOCAL, tapMode = true)
                    return START_STICKY
                }
                ACTION_TAP_FAILED, ACTION_TAP_BLOCKED -> {
                    onTapDead(intent?.getStringExtra(EXTRA_MESSAGE).orEmpty())
                    return START_NOT_STICKY
                }
                ACTION_TAP_ALIVE -> {
                    onTapAlive()
                    return START_NOT_STICKY
                }
                ACTION_LOCAL_PLAYING -> {
                    localPlaying = intent?.getBooleanExtra(EXTRA_PLAYING, false) == true
                    return START_NOT_STICKY
                }
                ACTION_FALLBACK_MIC -> {
                    fallbackToMic()
                    return START_NOT_STICKY
                }
                ACTION_APPLY_DUB_VOLUME -> {
                    val pct = intent?.getIntExtra(EXTRA_VOLUME_PCT, Prefs.dubVolumePct(this))
                        ?: Prefs.dubVolumePct(this)
                    applyDubStreamVolume(pct)
                    return START_NOT_STICKY
                }
                ACTION_VIDEO_TIME -> {
                    // v1.0.17 — frame check reply from the activity:
                    // the main video's currentTime at JS-run time.
                    // Correct it back to the EVENT wall (the video
                    // advanced during the JS round trip, unless it
                    // was paused) and hand it to the tracker. A
                    // negative time means "no video found".
                    val timeSec = intent?.getDoubleExtra(EXTRA_VIDEO_TIME_SEC, -1.0) ?: -1.0
                    val paused = intent?.getBooleanExtra(EXTRA_VIDEO_PAUSED, false) ?: false
                    val purpose = intent?.getStringExtra(EXTRA_VIDEO_PURPOSE).orEmpty()
                    val seq = intent?.getLongExtra(EXTRA_VIDEO_SEQ, 0L) ?: 0L
                    val eventWall = intent?.getLongExtra(EXTRA_EVENT_WALL_MS, 0L) ?: 0L
                    if (timeSec >= 0.0 && eventWall > 0L) {
                        val replyWall = System.currentTimeMillis()
                        val eventVideoSec = if (paused) {
                            timeSec
                        } else {
                            timeSec - (replyWall - eventWall).coerceAtLeast(0L) / 1000.0
                        }
                        try {
                            when (purpose) {
                                "marker" -> tracker?.setMarkerVideoTime(seq, eventVideoSec)
                                "burst" -> tracker?.onBurstVideoTime(seq, eventVideoSec)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "tracker video time failed", e)
                        }
                    }
                    return START_NOT_STICKY
                }
                ACTION_ADJUST_DELAY -> {
                    // v1.0.7 — manual "Trễ −1s / +1s" nudge: shift the
                    // tracker's EMA at once (it broadcasts TYPE_HUD, so
                    // the HUD and the WebView canvas follow immediately).
                    // No-op when not dubbing (tracker is null then).
                    try {
                        tracker?.adjustManual(
                            intent.getLongExtra(EXTRA_DELAY_DELTA_MS, 0L)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "adjustManual failed", e)
                    }
                    return START_NOT_STICKY
                }
                ACTION_SET_DELAY_ENABLED -> {
                    // v1.0.9 — the activity turned the video delay off
                    // (delay toggle / site fullscreen) or back on: the
                    // delayed original audio and the voice hold follow
                    // at once, or the sound would lag a live picture.
                    delayEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                    try {
                        if (delayedBackgroundActive) {
                            backgroundPlayer?.setDelayMs(
                                if (delayEnabled) {
                                    tracker?.currentDelayMs ?: Prefs.baseDelayMs(this)
                                } else {
                                    0L
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "setDelayEnabled failed", e)
                    }
                    return START_NOT_STICKY
                }
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
                    val isFallback = intent.getBooleanExtra(EXTRA_FALLBACK, false)
                    val src = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_WEB
                    if (isFallback && capturing) {
                        // Tap -> capture fallback (or mic -> capture):
                        // switch in place, keep the Live connection and D.
                        transitionToCapture(code, data, src)
                    } else {
                        startDub(code, data, micOnly = false, source = src, tapMode = false)
                    }
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

    /**
     * v1.0.12 — pick the foreground-service type for the CURRENT
     * audioMode. From Android 14 (targetSDK 34) an FGS started with
     * the mediaProjection type is rejected unless the app currently
     * holds a MediaProjection token (android:project_media), which
     * only exists after the user grants the screen-capture dialog.
     * v1.0.11 passed MEDIA_PROJECTION unconditionally for every mode,
     * so tap (no dialog by design) and mic (no dialog by design)
     * crashed at once with "Starting FGS with type mediaProjection
     * ... requires permissions: ... [FOREGROUND_SERVICE_MEDIA_PROJECTION]
     * ... [CAPTURE_VIDEO_OUTPUT, android:project_media]".
     * Only CAPTURE — started from the dialog result, token in hand —
     * may use MEDIA_PROJECTION|MICROPHONE, and it must still
     * startForeground BEFORE getMediaProjection(), as before.
     * Tap/mic modes run MICROPHONE-only (RECORD_AUDIO is requested
     * by the activity first; the manifest declares the microphone
     * FGS type). Never pass MEDIA_PROJECTION without a token.
     * `hasProjectionToken` is false when the CAPTURE start carries no
     * dialog result (defensive): the service then starts
     * MICROPHONE-only and the capture start fails cleanly with an
     * error broadcast instead of a SecurityException.
     */
    private fun foregroundTypeForMode(hasProjectionToken: Boolean): Int {
        return if (audioMode == AudioMode.CAPTURE && hasProjectionToken) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
    }

    private fun enterForeground(notif: Notification, hasProjectionToken: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, foregroundTypeForMode(hasProjectionToken))
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * v1.0.12 — tap -> capture fallback: the service is already
     * foreground with MICROPHONE only. Re-calling startForeground
     * upgrades its type, which MUST happen BEFORE getMediaProjection()
     * is called with the fresh token. Returns false when the upgrade
     * itself is rejected — the caller then falls back to the mic
     * instead of dying.
     */
    private fun upgradeForegroundToProjection(): Boolean {
        return try {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "upgradeForegroundToProjection failed", e)
            false
        }
    }

    private fun startDub(
        resultCode: Int,
        resultData: Intent?,
        micOnly: Boolean,
        source: String = SOURCE_WEB,
        tapMode: Boolean = false
    ) {
        if (capturing) return
        this.micOnly = micOnly
        // v1.0.11 — audio path for this session (see AudioMode).
        this.source = source
        audioMode = when {
            micOnly -> AudioMode.MIC
            tapMode && source == SOURCE_LOCAL -> AudioMode.TAP_LOCAL
            tapMode -> AudioMode.TAP_WEB
            else -> AudioMode.CAPTURE
        }
        tapDead = false
        fallbackMicArmed = false
        localPlaying = false
        lastTapChunkAtMs = 0L
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
        // v1.0.12 — Android 14 rule: startForeground (with the
        // mediaProjection type) MUST happen before getMediaProjection()
        // is called — but ONLY when we actually hold a token. Tap and
        // mic modes never open the capture dialog, so they start
        // MICROPHONE-only (see foregroundTypeForMode); passing
        // MEDIA_PROJECTION without a token throws SecurityException
        // and kills the service at once (the v1.0.11 crash).
        // If this throws (e.g. started from background), there is no
        // capture path at all — report it and stop cleanly instead
        // of crashing.
        try {
            enterForeground(notif, hasProjectionToken = resultData != null)
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
            voicePlayer = VoicePlayer(
                this,
                // v1.0.9 — the tracker no longer pairs on voice-play
                // starts (it samples at burst-start ARRIVAL in
                // onAudioChunk, and the hold is applied by the voice
                // queue itself). Both callbacks are unused now.
                onSpeechStarted = {},
                onTtsStarted = {},
                // v1.0.11 — MEDIA in the tap modes (no capture runs,
                // so it cannot echo), call stream in capture/mic.
                initialUsage = if (tapMode) {
                    AudioAttributes.USAGE_MEDIA
                } else {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                },
                // v1.0.17 — per-burst play reports (VoicePlayer
                // thread): the tracker measures the playback
                // pipeline + the closed-loop sync error from the
                // real sound, and the frame check asks the activity
                // for the video's currentTime at this play start.
                // v1.0.18 — fifth value: the backlog ahead of the
                // burst's first frame at write time.
                onBurstPlayed = { seq, playWallMs, pipelineMs, estimated, backlogMs ->
                    try {
                        tracker?.onBurstPlayed(seq, playWallMs, pipelineMs, estimated, backlogMs)
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker burstPlayed failed", e)
                    }
                    // v1.0.19 — push the tracker's stretch target
                    // to the player at every burst play (it also
                    // runs in the level ticker, 1 s).
                    try {
                        voicePlayer?.setPlaybackSpeed(tracker?.currentStretchSpeed ?: 1.0)
                    } catch (e: Exception) {
                        Log.w(TAG, "setPlaybackSpeed failed", e)
                    }
                    requestVideoTime("burst", seq, playWallMs)
                },
                // v1.0.19 — time-stretch catch-up (VoicePlayer
                // thread). The mid-burst pipeline sample feeds the
                // tracker's EMA with the TYPICAL pipeline (the
                // burst-first one under-measures it ~10x); the
                // write lateness raises the stretch to 1.5x when
                // the player loop is >200 ms behind; the track
                // level raises it when the buffer is filling.
                onPipelineSample = { pipelineMs, backlogMs ->
                    try {
                        tracker?.onPipelineSample(pipelineMs, backlogMs)
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker pipelineSample failed", e)
                    }
                },
                onWriteLate = { lateMs ->
                    try {
                        tracker?.noteLateness(lateMs, System.currentTimeMillis())
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker noteLateness failed", e)
                    }
                },
                onTrackLevel = { levelMs, capMs ->
                    try {
                        tracker?.noteLevel(levelMs, capMs, System.currentTimeMillis())
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker noteLevel failed", e)
                    }
                }
            ).also {
                it.start()
                // v1.0.6 — track/TTS at max (1.0f); the Settings slider
                // now drives the SYSTEM volume of the stream the voice
                // is on (media in tap modes, call stream otherwise).
                // Apply the saved slider value when dubbing starts so
                // it takes effect at once.
                try { it.setDubVolume(1.0f) } catch (_: Exception) { }
                try { applyDubStreamVolume() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VoicePlayer failed", e)
            voicePlayer = null
            broadcast(TYPE_ERROR, message = "Không mở được loa đọc giọng: ${e.message}")
        }
        try {
            tracker = DubDelayTracker(
                Prefs.maxDelayMs(this),
                // v1.0.7 — seed from Settings "Trễ hình cơ bản"
                // (default 5 s); the ALDA first-audio seed takes over
                // from the first translation, so the delay keeps moving
                // as long as dialogue keeps coming instead of freezing
                // after one sample (the v1.0.6 bug).
                Prefs.baseDelayMs(this)
            ) { delayMs ->
                // v1.0.9 — D moves: the delayed original audio follows
                // at once (its queue reads the live delay every poll),
                // and the canvas follows via the TYPE_HUD broadcast.
                try {
                    if (delayedBackgroundActive && delayEnabled) {
                        backgroundPlayer?.setDelayMs(delayMs)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bg setDelay failed", e)
                }
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
        currentUtteranceStartMs = 0L
        lastUtteranceStartMs = 0L
        firstAudioSentAtMs = 0L
        // v1.0.18 — a stale true flag would let the new session's
        // first turnComplete through with no audio at all (the
        // reset-cleanliness check from the 27/11 2-session
        // correction: audioPlayedThisTurn was never reset in the
        // start block).
        audioPlayedThisTurn = false
        lastNotedBurstSeq = 0L
        delayEnabled = true
        captureIsStereo48k = false
        comboFailures.clear()
        liveErrorHistory.clear()
        broadcastLiveErrors()
        ducked = false
        subtitleGen = 0
        // v1.0.5 — VAD threshold for this session (Settings, default 600).
        // v1.0.8 — hysteresis: the low (end) threshold is 40% of the
        // start threshold; the watchdog re-reads both every 10 s.
        vadThreshold = try { Prefs.vadThreshold(this).toDouble() } catch (_: Exception) { 600.0 }
        vadEndThreshold = vadThreshold * 0.4
        rotateAtMs = System.currentTimeMillis() + KEY_ROTATE_MS
        lastOutputAtMs = 0L
        startedAtMs = System.currentTimeMillis()
        lastLiveAttemptAtMs = 0L
        lastHeardAtMs = 0L
        lastStatus = ""
        synchronized(pendingChunks) { pendingChunks.clear() }
        pendingBytes = 0
        setStatus("Đang nối Gemini Live…")

        if (!micOnly && !tapMode && resultData != null) {
            if (!setupMediaProjection(resultCode, resultData)) {
                mediaProjection = null
            }
        }
        capturing = true
        if (tapMode) {
            // v1.0.11 — no system capture at all in tap mode: the film
            // sound arrives through TapAudioQueue (WebAudio tap / local
            // tap processor). The local tap also starts the delayed
            // original audio (MEDIA, no stream mute: the player itself
            // is muted with player.volume = 0).
            if (source == SOURCE_LOCAL) {
                try { startDelayedBackgroundLocal() } catch (e: Exception) {
                    Log.w(TAG, "local delayed background failed", e)
                }
            }
            try {
                startTapFeeder()
            } catch (e: Exception) {
                Log.e(TAG, "startTapFeeder failed", e)
                broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
            }
            if (source == SOURCE_LOCAL) {
                broadcastAudioRoute(AUDIO_MODE_MEDIA, "Âm thanh: phương tiện")
                setStatus("Đang lồng tiếng file local qua âm thanh phương tiện")
            } else {
                setStatus("Đang thử lấy tiếng trong trang…")
            }
        } else {
            try {
                startCapture()
            } catch (e: Exception) {
                Log.e(TAG, "startCapture failed", e)
                broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
            }
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
        // v1.0.9 — the mic hears the room, not the film: the delayed
        // background player has no clean source any more. Stop it and
        // bring the direct audio (STREAM_MUSIC) back first, or the user
        // hears nothing at all after the switch.
        try { stopDelayedBackground() } catch (e: Exception) {
            Log.w(TAG, "stopDelayedBackground failed", e)
        }
        captureIsStereo48k = false
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
                        // v1.0.9 — fresh burst pairing after (re)connect:
                        // the detector restarts (the next chunk is a
                        // burst start and samples at once); the EMA and
                        // D are kept, so the delay does not jump.
                        try { tracker?.resetForReconnect() } catch (_: Exception) { }
                        flushPendingAudio()
                    }

                    override fun onAudioChunk(pcm24k: ByteArray) {
                        if (!isCurrent()) return
                        lastOutputAtMs = System.currentTimeMillis()
                        audioPlayedThisTurn = true
                        // v1.0.14 — continuous slice measurement +
                        // ALDA voice hold (FIX32/FIX33). The hold
                        // still comes from the burst start (hold = D −
                        // latency of that burst), but the MEASUREMENT
                        // is now anchored on turn boundaries: every
                        // 500 ms of arriving dub (24 kHz PCM: ms =
                        // bytes / 48) pairs against the speech slice
                        // at (anchor + paired dub × ratio) — the dub
                        // is ~1.3x the source, so positional FIFO
                        // pairing pinned every sample at the floor
                        // (the 05:08 report: D frozen at 3.4 s, 1421
                        // deferrals) — and every 4 s the window
                        // median feeds the EMA, so D moves even when
                        // neither the speech nor the dub ever pauses.
                        // turnComplete / burst re-anchor exactly
                        // (see DubDelayTracker). While the video delay
                        // is off (site fullscreen / delay toggle) the
                        // hold is 0: the picture is live, so the voice
                        // plays at once.
                        val nowMs = System.currentTimeMillis()
                        val holdMs = try {
                            val h = tracker?.onDubAudioArrival(
                                nowMs,
                                (pcm24k.size / 48).toLong(),
                                firstAudioSentAtMs
                            ) ?: 0L
                            if (delayEnabled) h else 0L
                        } catch (e: Exception) {
                            Log.w(TAG, "voice hold failed", e)
                            0L
                        }
                        try { voicePlayer?.setVoiceHoldMs(holdMs) } catch (e: Exception) {
                            Log.w(TAG, "setVoiceHoldMs failed", e)
                        }
                        // v1.0.17 — a new burst started (the tracker
                        // bumped its seq): note it to the VoicePlayer
                        // BEFORE this first chunk is enqueued, so the
                        // player thread measures this burst's real
                        // play start (pipeline + closed-loop sync).
                        try {
                            val seq = tracker?.lastBurstSeq ?: 0L
                            if (seq != 0L && seq != lastNotedBurstSeq) {
                                lastNotedBurstSeq = seq
                                voicePlayer?.noteBurstStart(seq, nowMs, holdMs)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "noteBurstStart failed", e)
                        }
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
                        // v1.0.14 — the server finished a turn: the
                        // tracker pairs it against the VAD turn
                        // markers, feeds the boundary sample and
                        // re-anchors exactly. audioPlayedThisTurn
                        // gates it: a noise-only turn with no dub
                        // must not re-anchor (its dub position never
                        // advanced for this turn). Read before the
                        // reset at the end of this method.
                        try {
                            tracker?.onDubTurnComplete(
                                System.currentTimeMillis(),
                                audioPlayedThisTurn
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "tracker turnComplete failed", e)
                        }
                        val text = pendingText.toString().trim()
                        pendingText.clear()
                        if (!SpeechFilter.isMeaningful(text)) {
                            // Noise-only turn: hide the subtitle at once
                            // and cancel its 6 s auto-hide timer.
                            subtitleGen++
                            broadcast(TYPE_SUBTITLE, text = "")
                        } else if (!audioPlayedThisTurn) {
                            // Model returned text but no audio: read it with TTS.
                            // v1.0.9 — same hold as the Live voice: the
                            // sentence speaks at (utterance start + D),
                            // or at once when that moment already passed
                            // (lagMs feeds the TTS catch-up speed).
                            // The subtitle already streamed with its own
                            // hold, so only the speech is scheduled here.
                            val targetMs = lastUtteranceStartMs +
                                (tracker?.currentDelayMs ?: 0L)
                            val nowMs = System.currentTimeMillis()
                            val waitMs = if (delayEnabled) {
                                (targetMs - nowMs).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            val lagMs = (nowMs - targetMs).coerceAtLeast(0L)
                            val run = Runnable {
                                try {
                                    voicePlayer?.speakText(text, lagMs)
                                } catch (e: Exception) {
                                    Log.w(TAG, "speakText failed", e)
                                }
                            }
                            if (waitMs > 0L) mainHandler.postDelayed(run, waitMs)
                            else mainHandler.post(run)
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
    /**
     * v1.0.9 — subtitle hold (ALDA rule: the subtitle shows when the
     * voice STARTS PLAYING, not when the text arrives). The display
     * is delayed by [holdMs] — the current burst hold by default, or
     * an explicit wait (Groq path: the same wait the TTS speech is
     * scheduled with, so subtitle and voice start together).
     * Each fragment posts its OWN runnable (no cancellation): during
     * continuous speech a newer fragment must not keep pushing the
     * display back forever. Each runnable shows the text as of ITS
     * fragment; the 6 s auto-hide timer starts at display time and
     * only the latest generation may hide it.
     */
    private fun showSubtitle(fullText: String, holdMs: Long? = null) {
        val shown = trimSubtitle(fullText)
        subtitleGen++
        val gen = subtitleGen
        val hold = holdMs ?: try { tracker?.currentHoldMs ?: 0L } catch (_: Exception) { 0L }
        val r = Runnable {
            broadcast(TYPE_SUBTITLE, text = shown)
            mainHandler.postDelayed({
                if (gen == subtitleGen && capturing) {
                    broadcast(TYPE_SUBTITLE, text = "")
                }
            }, 6000L)
        }
        if (hold > 0L) mainHandler.postDelayed(r, hold) else mainHandler.post(r)
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

    /** v1.0.9 — min buffer for stereo 48 kHz capture (bytes). */
    private fun minBufferSizeStereo(): Int {
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                STEREO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.w(TAG, "getMinBufferSize (stereo) failed", e)
            AudioRecord.ERROR_BAD_VALUE
        }
        return if (minBuf <= 0) 19200 else minBuf
    }

    /**
     * v1.0.9 — stereo 48 kHz system capture. This is the feed for the
     * delayed original audio: full-quality stereo straight into
     * DelayedBackgroundPlayer, plus a mono 16 kHz downmix for the
     * VAD/Live path. Null when the projection token is unusable
     * (single-app token, OEM block) or the device refuses the format.
     */
    private fun buildPlaybackRecordStereo(minBuf: Int, gameOnly: Boolean = false): AudioRecord? {
        val proj = mediaProjection ?: return null
        return try {
            // v1.0.11 — the capture matching follows the source: a
            // local file plays on USAGE_GAME, so the fallback capture
            // matches GAME only (MEDIA is never an input there); the
            // web fallback keeps MEDIA+GAME as before.
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(proj)
            if (!gameOnly) {
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            }
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
            val config = configBuilder.build()
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(STEREO_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
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
            Log.w(TAG, "Stereo AudioPlaybackCapture unavailable", e)
            null
        }
    }

    /** Null when the projection token is unusable (single-app token, OEM block). */
    private fun buildPlaybackRecordMono(minBuf: Int, gameOnly: Boolean = false): AudioRecord? {
        val proj = mediaProjection ?: return null
        return try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(proj)
            if (!gameOnly) {
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            }
            configBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
            val config = configBuilder.build()
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

    /**
     * v1.0.11 — extracted from startCapture: the VAD + its listener
     * are shared by the system-capture thread AND the tap feeder
     * thread (WebAudio tap / local tap processor). Returns null on
     * failure (the caller stops the service).
     */
    private fun createVad(): VadDetector? {
        return try {
            val detector = VadDetector(
                // v1.0.5 — threshold from Settings (default 600, ALDA's
                // value) instead of the hard-coded 250.
                // v1.0.8 — this is the START threshold; the VAD derives
                // the hysteresis END threshold (40%) itself.
                startThreshold = vadThreshold,
                listener = object : VadDetector.Listener {
                override fun onSpeechStart(preRollAndHead: ShortArray) {
                    // v1.0.9 — a new source sentence starts: record its
                    // start for the latency sample (burst start −
                    // utterance start) and for the TTS hold target.
                    // The ALDA first-audio seed also starts its clock
                    // here: the first source audio of the session.
                    val now = System.currentTimeMillis()
                    // v1.0.17 — VAD DETECTION LAG: the speaker really
                    // started ~180 ms BEFORE this trigger (the 160 ms
                    // head that triggered the utterance + one 20 ms
                    // frame hop). The marker wall and the utterance
                    // stamps use the TRUE start; the head audio itself
                    // still ends at `now` (its slice wall is a
                    // capture time, not a recognition time).
                    val utteranceWallMs = now - DubDelayTracker.VAD_DETECTION_LAG_MS
                    currentUtteranceStartMs = utteranceWallMs
                    lastUtteranceStartMs = utteranceWallMs
                    if (firstAudioSentAtMs == 0L) firstAudioSentAtMs = utteranceWallMs
                    // v1.0.11 (hotfix) — the 160 ms head that triggered
                    // this utterance is sent to Live (below) but was
                    // NEVER counted as speech time: onSpeechMs is only
                    // called from onSpeechChunk, and the 8 loud frames
                    // that opened the utterance never reach it. Each
                    // utterance therefore undercounted ~160 ms of speech
                    // against the dub (which translates the head), and
                    // the slice queue drained a little every sentence
                    // until it hit zero — then every sample was <500 ms
                    // and dropped (the 22:35 screenshot: 133 dropped,
                    // 0 valid, queue empty, VAD speaking). The head is
                    // exactly the 8 frames (8 x 20 ms) the VAD needed
                    // to trigger; the pre-roll before it is mostly
                    // silence (no dub for it), so only the head counts.
                    // The head audio ended at `now`, so stamping it at
                    // `now` matches the slice end-time convention.
                    // v1.0.14 — the turn starts: the tracker records
                    // the marker (for the turnComplete/burst anchor)
                    // BEFORE the head is counted, so the marker's
                    // audio position is the speech position at the
                    // turn start.
                    try {
                        tracker?.onSpeechTurnStart(utteranceWallMs)
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker turnStart failed", e)
                    }
                    // v1.0.17 — frame check: ask the activity for the
                    // video's currentTime at this (true) turn start,
                    // so the burst-play residual has a marker video
                    // time to compare against.
                    try {
                        val markerSeq = tracker?.lastMarkerSeq ?: 0L
                        if (markerSeq != 0L) {
                            requestVideoTime("marker", markerSeq, utteranceWallMs)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "marker video time request failed", e)
                    }
                    try {
                        val headMs = minOf(160L, (preRollAndHead.size / 16).toLong())
                        tracker?.onSpeechMs(headMs, now)
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker head feed failed", e)
                    }
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
                    // v1.0.19 — tracker feed gate is the START
                    // threshold (600), NOT the END threshold (240).
                    // v1.0.11 gated the tracker at END to avoid
                    // overcounting the 700 ms silence tail — but
                    // music frames (RMS 300–800) are above END, so
                    // they reached the tracker as "speech ms" and
                    // kept lastLoudAtMs fresh: the v1.0.18 marker
                    // split never saw a >= 300 ms gap (device 28/11:
                    // "tách 1" in a whole session, no-mk 23/31).
                    // Gating the tracker at START keeps music out of
                    // the marker clock, so the split sees the pause.
                    // The LIVE SEND below stays at END (240): quiet
                    // syllables (240–600) still go to the model, or
                    // the dub stutters (the 20:37 report). The
                    // tracker therefore undercounts quiet syllables
                    // slightly — the sim (tracker_gate=600) shows
                    // this is a small, stable bias the ratio absorbs.
                    val rms = rmsOf(samples)
                    if (rms >= vadThreshold) {
                        try {
                            tracker?.onSpeechMs(
                                (samples.size / 16).toLong(),
                                System.currentTimeMillis()
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "tracker onSpeechMs failed", e)
                        }
                    }
                    if (liveConnected) {
                        // v1.0.8 — hysteresis send gate: inside an
                        // utterance the VAD forwards frames down to the
                        // LOW end threshold (40% of the start threshold).
                        // A chunk below the START threshold but above the
                        // END threshold is still speech (a quiet syllable),
                        // NOT noise: it must go to Live, or the model
                        // hears a stream full of holes and the dub
                        // stutters (the 20:37 report). Only chunks below
                        // the END threshold are withheld. (They still go
                        // into utteranceBytes for the Groq path — Whisper
                        // tolerates noise far better than Live does.)
                        if (rms >= vadEndThreshold) {
                            try { liveClient?.sendAudio(bytes) } catch (e: Exception) {
                                Log.w(TAG, "sendAudio failed", e)
                            }
                        }
                    } else {
                        bufferAudio(bytes)
                    }
                }

                override fun onSpeechEnd() {
                    // v1.0.14 — this sentence ended: the tracker
                    // completes the oldest not-yet-done turn marker
                    // (a turn's 'end' can arrive after the next
                    // turn's 'start' when the pause is shorter than
                    // the 700 ms VAD tail).
                    try {
                        tracker?.onSpeechTurnEnd()
                    } catch (e: Exception) {
                        Log.w(TAG, "tracker turnEnd failed", e)
                    }
                    // v1.0.9 — this sentence ended. The Live stream is
                    // NOT cut here (endTurn below only tells the model
                    // the turn is over); the utterance start is kept
                    // in lastUtteranceStartMs for the TTS hold target,
                    // and cleared from currentUtteranceStartMs so a
                    // late burst cannot sample against it.
                    // The Groq path captures this utterance's start
                    // now: by the time the transcription returns, a
                    // new sentence may already have started.
                    val uttStart = lastUtteranceStartMs
                    currentUtteranceStartMs = 0L
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
                                    // v1.0.9 — same hold as the Live
                                    // voice: subtitle + TTS start at
                                    // (utterance start + D), or at once
                                    // when that moment already passed
                                    // (lagMs feeds the TTS catch-up
                                    // speed). showSubtitle takes the
                                    // same wait, so the two start
                                    // together.
                                    val targetMs = uttStart +
                                        (tracker?.currentDelayMs ?: 0L)
                                    val nowMs = System.currentTimeMillis()
                                    val waitMs = if (delayEnabled) {
                                        (targetMs - nowMs).coerceAtLeast(0L)
                                    } else {
                                        0L
                                    }
                                    val lagMs = (nowMs - targetMs).coerceAtLeast(0L)
                                    showSubtitle(text, waitMs)
                                    val run = Runnable {
                                        try {
                                            voicePlayer?.speakText(text, lagMs)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "speakText failed", e)
                                        }
                                    }
                                    if (waitMs > 0L) mainHandler.postDelayed(run, waitMs)
                                    else mainHandler.post(run)
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
            vad = detector
            detector
        } catch (e: Exception) {
            Log.e(TAG, "VadDetector failed", e)
            broadcast(TYPE_ERROR, message = "Không khởi động được bộ lọc giọng: ${e.message}")
            null
        }
    }

    private fun startCapture() {
        if (createVad() == null) {
            mainHandler.post { if (capturing) stopDub() }
            return
        }
        captureThread = Thread({
            val minBuf = minBufferSize()
            val minBufStereo = minBufferSizeStereo()
            var record: AudioRecord? = null
            // v1.0.9 — true while the record below is stereo 48 kHz.
            var stereo = false
            try {
                // Step 1: system audio via the projection token, if we have one.
                // v1.0.9 — try stereo 48 kHz FIRST: the delayed
                // background player needs full-quality stereo for the
                // original audio. If the device refuses stereo, mono
                // 16 kHz still works for the Live/VAD path (old
                // behaviour: direct audio, no delayed original).
                if (mediaProjection != null) {
                    // v1.0.11 — local capture fallback matches GAME
                    // only (the local player is USAGE_GAME).
                    val gameOnly = source == SOURCE_LOCAL
                    record = buildPlaybackRecordStereo(minBufStereo, gameOnly)
                    if (record != null) stereo = true
                    if (record == null) record = buildPlaybackRecordMono(minBuf, gameOnly)
                }
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
                    stereo = false
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
                // v1.0.9 — stereo system capture: start the delayed
                // background player (mutes STREAM_MUSIC, replays the
                // captured audio delayed by D on the voice-call stream).
                // The record is non-null here (every null path above
                // returned from the thread).
                val rec: AudioRecord = record ?: return@Thread
                captureIsStereo48k = stereo
                if (stereo) {
                    try {
                        startDelayedBackground()
                    } catch (e: Exception) {
                        Log.w(TAG, "delayed background failed", e)
                    }
                }
                val monoBuf = ShortArray(1600) // 100 ms at 16 kHz
                val stereoBuf = ShortArray(9600) // 100 ms stereo at 48 kHz
                while (capturing) {
                    if (captureIsStereo48k) {
                        val n = try {
                            rec.read(stereoBuf, 0, stereoBuf.size)
                        } catch (e: Exception) {
                            Log.w(TAG, "AudioRecord.read failed", e)
                            break
                        }
                        if (n > 0) {
                            // RAW stereo first: the delayed background
                            // player gets the audio before the VAD, never
                            // through it.
                            try {
                                backgroundPlayer?.feedPcm(shortsToBytes(stereoBuf, n))
                            } catch (e: Exception) {
                                Log.w(TAG, "bg feed failed", e)
                            }
                            // Then the mono 16 kHz downmix for VAD/Live.
                            val monoCount = downmixStereo48kToMono16k(stereoBuf, n, monoBuf)
                            if (monoCount > 0) {
                                try {
                                    vad?.feed(monoBuf, monoCount)
                                } catch (e: Exception) {
                                    Log.w(TAG, "VAD feed failed", e)
                                }
                            }
                        } else if (n < 0) {
                            Log.w(TAG, "AudioRecord.read error $n")
                            break
                        }
                    } else {
                        val n = try {
                            rec.read(monoBuf, 0, monoBuf.size)
                        } catch (e: Exception) {
                            Log.w(TAG, "AudioRecord.read failed", e)
                            break
                        }
                        if (n > 0) {
                            try { vad?.feed(monoBuf, n) } catch (e: Exception) {
                                Log.w(TAG, "VAD feed failed", e)
                            }
                        } else if (n < 0) {
                            Log.w(TAG, "AudioRecord.read error $n")
                            break
                        }
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

    /**
     * v1.0.11 — take the MediaProjection token and register the stop
     * callback (shared by the normal start and the tap -> capture
     * fallback transition). Returns false when the token is unusable
     * (single-app token, OEM block): the caller falls back to the mic.
     */
    private fun setupMediaProjection(resultCode: Int, resultData: Intent?): Boolean {
        if (resultData == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
                false
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
                true
            }
        } catch (e: Exception) {
            // Single-app token (user picked DubLive itself), OriginOS
            // blocking the capture, or an expired token: fall back to mic.
            Log.w(TAG, "getMediaProjection failed, falling back to mic", e)
            mediaProjection = null
            broadcast(TYPE_FALLBACK, message = getString(R.string.projection_failed))
            false
        }
    }

    /**
     * v1.0.11 — tap feeder thread: drains TapAudioQueue into the VAD
     * (and, for stereo, the delayed background player). Runs instead
     * of the system-capture thread in the tap modes. The VAD listener
     * is the same as the capture path's, so speech slices, the Groq
     * path and the level meter all work unchanged.
     */
    private fun startTapFeeder() {
        if (createVad() == null) {
            mainHandler.post { if (capturing) stopDub() }
            return
        }
        tapFeederRunning = true
        lastTapChunkAtMs = 0L
        tapFeederThread = Thread({
            val monoBuf = ShortArray(1600)
            val stereoBuf = ShortArray(9600)
            while (tapFeederRunning && capturing) {
                val chunk = try {
                    TapAudioQueue.poll(200L)
                } catch (e: Exception) {
                    null
                } ?: continue
                lastTapChunkAtMs = System.currentTimeMillis()
                try {
                    when (chunk.kind) {
                        TapAudioQueue.Kind.MONO16K -> feedMono16k(chunk.bytes, monoBuf)
                        TapAudioQueue.Kind.STEREO48K -> feedStereo48k(chunk.bytes, stereoBuf, monoBuf)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "tap feed failed", e)
                }
            }
        }, "DubLive-TapFeed").apply { start() }
    }

    private fun stopTapFeeder() {
        tapFeederRunning = false
        tapFeederThread?.interrupt()
        try { tapFeederThread?.join(500) } catch (_: Exception) { }
        tapFeederThread = null
        try { TapAudioQueue.clear() } catch (_: Exception) { }
    }

    /** WebAudio tap: 16 kHz mono PCM16 straight into the VAD. */
    private fun feedMono16k(bytes: ByteArray, monoBuf: ShortArray) {
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(3200, bytes.size - offset)
            val n = bytesToShorts(bytes, offset, len, monoBuf)
            if (n > 0) {
                try { vad?.feed(monoBuf, n) } catch (e: Exception) {
                    Log.w(TAG, "VAD feed failed", e)
                }
            }
            offset += len
        }
    }

    /** Local tap: 48 kHz stereo PCM16 — background player first (raw,
     * before the VAD), then the mono 16 kHz downmix for VAD/Live. */
    private fun feedStereo48k(bytes: ByteArray, stereoBuf: ShortArray, monoBuf: ShortArray) {
        try { backgroundPlayer?.feedPcm(bytes) } catch (e: Exception) {
            Log.w(TAG, "bg feed failed", e)
        }
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val len = minOf(19200, (bytes.size - offset) / 4 * 4)
            val n = bytesToShorts(bytes, offset, len, stereoBuf)
            if (n > 0) {
                val monoCount = downmixStereo48kToMono16k(stereoBuf, n, monoBuf)
                if (monoCount > 0) {
                    try { vad?.feed(monoBuf, monoCount) } catch (e: Exception) {
                        Log.w(TAG, "VAD feed failed", e)
                    }
                }
            }
            offset += len
        }
    }

    /** Little-endian PCM16 bytes -> shorts. Returns the count written. */
    private fun bytesToShorts(bytes: ByteArray, offset: Int, byteLen: Int, out: ShortArray): Int {
        val count = minOf(byteLen / 2, out.size)
        for (i in 0 until count) {
            val lo = bytes[offset + i * 2].toInt() and 0xFF
            val hi = bytes[offset + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return count
    }

    /**
     * v1.0.11 — delayed original audio for LOCAL tap mode: the
     * ExoPlayer tap processor's stereo PCM replayed D seconds late
     * on USAGE_MEDIA (no enhancer — media is loud already). NO
     * stream mute: the live player audio is already silent
     * (player.volume = 0 in the activity); muting STREAM_MUSIC here
     * would silence this very player, since it is on MEDIA too.
     */
    private fun startDelayedBackgroundLocal() {
        if (delayedBackgroundActive) return
        try {
            backgroundPlayer?.stop()
            backgroundPlayer = DelayedBackgroundPlayer(this).also {
                it.start(AudioAttributes.USAGE_MEDIA, useEnhancer = false)
                it.setVolume(1.0f, false)
                it.setDelayMs(
                    if (delayEnabled) {
                        tracker?.currentDelayMs ?: Prefs.baseDelayMs(this)
                    } else {
                        0L
                    }
                )
            }
            delayedBackgroundActive = true
            Log.i(TAG, "Delayed background (local tap) active on MEDIA, delay ${tracker?.currentDelayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "DelayedBackgroundPlayer (local) start failed", e)
            delayedBackgroundActive = false
            backgroundPlayer = null
        }
    }

    /** v1.0.11 — the dub slider drives the stream the voice is on. */
    private fun applyDubStreamVolume(pct: Int = Prefs.dubVolumePct(this)) {
        try {
            if (audioMode == AudioMode.TAP_WEB || audioMode == AudioMode.TAP_LOCAL) {
                VoicePlayer.applyMediaVolume(this, pct)
            } else {
                VoicePlayer.applyCallVolume(this, pct)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyDubStreamVolume failed", e)
        }
    }

    /** v1.0.11 — tell the activities which audio path is in use. */
    private fun broadcastAudioRoute(mode: String, label: String) {
        try {
            val intent = Intent(ACTION_DUB_EVENT).setPackage(packageName)
            intent.putExtra(EXTRA_TYPE, TYPE_AUDIO_ROUTE)
            intent.putExtra(EXTRA_AUDIO_MODE, mode)
            intent.putExtra(EXTRA_MESSAGE, label)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "broadcastAudioRoute failed", e)
        }
    }

    /**
     * v1.0.11 — the WebAudio tap died (probe) or was blocked (CORS).
     * Ask the foreground activity for a screen-capture token so the
     * session can fall back to system capture; arm the 90 s mic
     * safety net in case no activity answers. The Live connection
     * and D survive — only the audio input path changes.
     */
    private fun onTapDead(reason: String) {
        if (stopped || !capturing) return
        if (audioMode != AudioMode.TAP_WEB && audioMode != AudioMode.TAP_LOCAL) return
        if (tapDead) return
        tapDead = true
        Log.w(TAG, "Tap dead: $reason")
        setStatus("Trang chặn lấy tiếng, đang chuyển sang đường cuộc gọi…")
        broadcast(TYPE_NEED_PROJECTION, message = reason)
        if (!fallbackMicArmed) {
            fallbackMicArmed = true
            try { mainHandler.postDelayed(fallbackMicCheck, 90_000L) } catch (_: Exception) { }
        }
    }

    private fun onTapAlive() {
        if (stopped || !capturing) return
        if (audioMode != AudioMode.TAP_WEB) return
        broadcastAudioRoute(AUDIO_MODE_MEDIA, "Âm thanh: phương tiện")
        setStatus("Đã nhận tiếng trong trang — lồng tiếng qua âm thanh phương tiện")
    }

    /** 90 s after a tap death with no projection token: use the mic. */
    private val fallbackMicCheck = Runnable {
        if (fallbackMicArmed && tapDead && capturing && !stopped &&
            (audioMode == AudioMode.TAP_WEB || audioMode == AudioMode.TAP_LOCAL)
        ) {
            Log.w(TAG, "No projection fallback in 90 s, switching to mic")
            fallbackToMic()
        }
    }

    /**
     * v1.0.11 — tap (or mic) -> system-capture transition, in place.
     * The projection token arrives from the activity's fallback flow.
     * The Live connection, the tracker (D) and the queued dub voice
     * survive; only the audio input path and the output stream
     * change. The tap feeder stops, the voice goes back to the call
     * stream, the capture thread starts (its stereo path mutes
     * STREAM_MUSIC and starts the delayed background player, exactly
     * like the v1.0.10 start).
     */
    private fun transitionToCapture(resultCode: Int, resultData: Intent?, src: String) {
        if (stopped || !capturing) return
        fallbackMicArmed = false
        tapDead = false
        try { mainHandler.removeCallbacks(fallbackMicCheck) } catch (_: Exception) { }
        try { stopTapFeeder() } catch (e: Exception) {
            Log.w(TAG, "stopTapFeeder failed", e)
        }
        try { stopDelayedBackground() } catch (e: Exception) {
            Log.w(TAG, "stopDelayedBackground failed", e)
        }
        source = src
        audioMode = AudioMode.CAPTURE
        try {
            voicePlayer?.setOutputUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        } catch (e: Exception) {
            Log.w(TAG, "voice->call failed", e)
        }
        try { applyDubStreamVolume() } catch (_: Exception) { }
        // Stop the current capture thread first (mic mode), if any.
        val oldThread = captureThread
        capturing = false
        try { oldThread?.join(1500) } catch (_: Exception) { }
        captureThread = null
        // v1.0.12 — the service is foreground with MICROPHONE only
        // (tap mode). Upgrade to MEDIA_PROJECTION|MICROPHONE BEFORE
        // getMediaProjection(): from Android 14 the FGS type must
        // already include mediaProjection when the fresh token is
        // used. If the upgrade is rejected, fall back to the mic
        // instead of dying.
        if (!upgradeForegroundToProjection()) {
            fallbackToMic()
            return
        }
        if (!setupMediaProjection(resultCode, resultData)) {
            // Token unusable after all: mic mode instead of dying.
            mediaProjection = null
            fallbackToMic()
            return
        }
        capturing = true
        try {
            startCapture()
        } catch (e: Exception) {
            Log.e(TAG, "transition startCapture failed", e)
            broadcast(TYPE_ERROR, message = "Không thu được tiếng: ${e.message}")
        }
        broadcastAudioRoute(AUDIO_MODE_CALL, "Âm thanh: cuộc gọi (trang chặn lấy tiếng)")
        setStatus("Đã chuyển sang đường cuộc gọi — lồng tiếng tiếp tục")
    }

    /**
     * v1.0.11 — give up on the tap AND on the projection fallback:
     * hear the film through the microphone (the phone's speakers play
     * it) and dub on the call stream, like the old mic mode. Used
     * when the user denies the screen-capture dialog during a
     * fallback, and by the 90 s safety timer when no activity ever
     * answers the projection request.
     */
    private fun fallbackToMic() {
        if (stopped || !capturing) return
        if (audioMode == AudioMode.MIC) return
        fallbackMicArmed = false
        tapDead = false
        try { stopTapFeeder() } catch (e: Exception) { }
        try { stopDelayedBackground() } catch (e: Exception) { }
        audioMode = AudioMode.MIC
        source = SOURCE_WEB
        try {
            voicePlayer?.setOutputUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        } catch (e: Exception) {
            Log.w(TAG, "voice->call failed", e)
        }
        try { applyDubStreamVolume() } catch (_: Exception) { }
        broadcastAudioRoute(AUDIO_MODE_MIC, "Âm thanh: micro (trang chặn lấy tiếng — để điện thoại gần loa)")
        setStatus("Trang chặn lấy tiếng, đang nghe bằng micro — để điện thoại gần loa")
        switchCaptureToMic()
    }

    private fun stopForegroundSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }

    private fun stopDub() {
        stopped = true
        capturing = false
        // v1.0.11 — stop the tap feeder + drop queued tap audio, and
        // disarm the mic-fallback timer (no fallback after a real stop).
        tapDead = false
        fallbackMicArmed = false
        localPlaying = false
        try { mainHandler.removeCallbacks(fallbackMicCheck) } catch (_: Exception) { }
        try { stopTapFeeder() } catch (_: Exception) { }
        audioMode = AudioMode.CAPTURE
        source = SOURCE_WEB
        try { mainHandler.removeCallbacks(watchdog) } catch (_: Exception) { }
        try { mainHandler.removeCallbacks(levelTicker) } catch (_: Exception) { }
        try { mainHandler.removeCallbacks(duckTicker) } catch (_: Exception) { }
        // v1.0.9 — stop the delayed original audio first and bring
        // STREAM_MUSIC back (stopDelayedBackground restores the saved
        // system volumes). The duck restore below only matters in mic
        // / mono mode: in delayed mode the activities never ducked
        // their direct volume (no TYPE_DUCK was sent).
        val wasDelayed = delayedBackgroundActive
        try { stopDelayedBackground() } catch (e: Exception) {
            Log.w(TAG, "stopDelayedBackground failed", e)
        }
        captureIsStereo48k = false
        // v1.0.5 — restore the original volume (the duck ticker is gone
        // now, so a ducked state would otherwise stick).
        if (ducked) {
            ducked = false
            if (!wasDelayed) {
                try { broadcast(TYPE_DUCK, message = "1.0") } catch (_: Exception) { }
            }
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

    /**
     * v1.0.9 — start the delayed original audio (ALDA FIX37 end
     * state). Called once the stereo 48 kHz system capture is
     * running.
     *
     *  1. Mute the direct audio at the SYSTEM level: STREAM_MUSIC ->
     *     0. This silences the speakers but NOT the
     *     AudioPlaybackCapture tap — the tap reads the track before
     *     the stream volume is applied, which is exactly why ALDA
     *     mutes this way. NEVER mute via the video element volume
     *     (v.volume = 0): the element volume IS applied at the track,
     *     so the capture — and with it the delayed player AND the
     *     Live input — would go silent (the v1.0.2 lesson).
     *  2. The call stream now carries BOTH the dub voice and the
     *     delayed original audio (v1.0.10: the alarm stream is gone
     *     entirely at the user's request). Set STREAM_VOICE_CALL to
     *     the louder of the dub slider and the original level, so
     *     both are audible; the two in-app sliders set the ratio
     *     between them (track gains), and the hardware buttons move
     *     both together. The saved music volume is restored by
     *     [stopDelayedBackground].
     *  3. Start [DelayedBackgroundPlayer] with the current D (its
     *     queue reads the live delay every poll, so later D changes
     *     follow at once) at full volume — the duck ticker drives its
     *     volume from here on.
     */
    private fun startDelayedBackground() {
        if (delayedBackgroundActive) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (initialMusicVolume < 0) {
                initialMusicVolume = try {
                    am.getStreamVolume(AudioManager.STREAM_MUSIC)
                } catch (e: Exception) {
                    Log.w(TAG, "getStreamVolume (music) failed", e)
                    -1
                }
            }
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "mute STREAM_MUSIC failed", e)
            }
            // v1.0.10 — the shared call stream (dub voice + delayed
            // original) gets the louder of the two sliders; flag 0,
            // no volume UI flash mid-session.
            try {
                val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
                val frac = maxOf(
                    Prefs.dubVolumePct(this),
                    Prefs.originalVolumePct(this)
                ) / 100f
                val target = (frac * maxCall).roundToInt().coerceIn(0, maxCall)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
            } catch (e: Exception) {
                Log.w(TAG, "set STREAM_VOICE_CALL failed", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "delayed-background volume setup failed", e)
        }
        try {
            backgroundPlayer?.stop()
            backgroundPlayer = DelayedBackgroundPlayer(this).also {
                it.start()
                it.setVolume(1.0f, false)
                it.setDelayMs(
                    if (delayEnabled) {
                        tracker?.currentDelayMs ?: Prefs.baseDelayMs(this)
                    } else {
                        0L
                    }
                )
            }
            delayedBackgroundActive = true
            setStatus("Tiếng gốc đang trễ theo hình qua đường cuộc gọi")
            Log.i(TAG, "Delayed background active (delay ${tracker?.currentDelayMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "DelayedBackgroundPlayer start failed", e)
            delayedBackgroundActive = false
            backgroundPlayer = null
        }
    }

    /** Stop the delayed original audio and restore the system
     * volume saved by [startDelayedBackground]. No-op when the
     * delayed mode never started. */
    private fun stopDelayedBackground() {
        if (!delayedBackgroundActive && backgroundPlayer == null) return
        delayedBackgroundActive = false
        try { backgroundPlayer?.stop() } catch (e: Exception) {
            Log.w(TAG, "background player stop failed", e)
        }
        backgroundPlayer = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (initialMusicVolume >= 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, initialMusicVolume, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "restore STREAM_MUSIC failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore system volumes failed", e)
        }
        initialMusicVolume = -1
    }

    /**
     * v1.0.9 — downmix stereo 48 kHz to mono 16 kHz for the VAD/Live
     * path: average L+R, take every 3rd frame (48 kHz -> 16 kHz).
     * Returns the number of mono samples written to [out].
     */
    private fun downmixStereo48kToMono16k(stereo: ShortArray, n: Int, out: ShortArray): Int {
        val frames = n / 2
        var w = 0
        var i = 0
        while (i < frames && w < out.size) {
            val l = stereo[i * 2].toInt()
            val r = stereo[i * 2 + 1].toInt()
            out[w++] = ((l + r) / 2).coerceIn(-32768, 32767).toShort()
            i += 3
        }
        return w
    }

    /** ShortArray -> little-endian PCM bytes, first [length] shorts. */
    private fun shortsToBytes(samples: ShortArray, length: Int): ByteArray {
        val len = length.coerceIn(0, samples.size)
        val buf = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until len) buf.putShort(samples[i])
        return buf.array()
    }

    /**
     * v1.0.17 — frame check: ask the activity for the main video's
     * currentTime belonging to one event wall (a turn marker or a
     * burst play). The activity answers with ACTION_VIDEO_TIME.
     * No-op in mic mode (no WebView) and when not capturing; a
     * missing reply simply leaves the frame residual blank.
     */
    private fun requestVideoTime(purpose: String, seq: Long, eventWallMs: Long) {
        if (micOnly || !capturing) return
        try {
            val intent = Intent(ACTION_DUB_EVENT).setPackage(packageName)
            intent.putExtra(EXTRA_TYPE, TYPE_VIDEO_TIME_REQUEST)
            intent.putExtra(EXTRA_VIDEO_PURPOSE, purpose)
            intent.putExtra(EXTRA_VIDEO_SEQ, seq)
            intent.putExtra(EXTRA_EVENT_WALL_MS, eventWallMs)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "video time request failed", e)
        }
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
