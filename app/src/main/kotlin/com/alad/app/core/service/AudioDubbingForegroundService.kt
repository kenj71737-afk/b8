package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.AudioUtils
import com.alad.app.core.audio.AudioBurstDetector
import com.alad.app.core.audio.BackgroundAudioPlayer
import com.alad.app.core.audio.BrowserAudioBridge
import com.alad.app.core.audio.LatencyEma
import com.alad.app.core.audio.RotationPolicy
import com.alad.app.core.audio.SentenceDurationTracker
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.core.util.UtteranceFilter
import com.alad.app.data.repository.DubbingSessionRepository
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        // FIX18: the browser video was seeked to a new scene. The service
        // drops everything that belongs to the old position.
        const val ACTION_RESYNC = "ACTION_RESYNC"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "DubbingService"

        // FIX28: the free Gemini Live key silently stops translating after
        // ~5 minutes — no error message, no socket close — so the FIX27
        // error-only failover never fires. The service now (a) rotates to
        // the other key every 5 minutes, (b) watches for a stall (no
        // translated output for 60 s after output had been flowing) and
        // switches keys, and (c) reconnects on any unexpected disconnect.
        // With a single key the same reconnects still run — a fresh
        // session revives the key — they just keep the current key.
        // FIX34: the rotation waits for a quiet moment (no speech and no
        // translated output for 3 s) so it never cuts a sentence mid-flow,
        // and the stall check requires speech to have been flowing — a
        // quiet scene is not a dead key.
        private const val KEY_ROTATION_INTERVAL_MS = 5 * 60 * 1000L
        private const val FORCE_ROTATION_MS = 6 * 60 * 1000L
        private const val ROTATION_QUIET_MS = 3000L
        private const val STALL_TIMEOUT_MS = 60_000L
        // FIX39: translation overhead added to a source sentence's
        // duration to estimate the whole-sentence latency (the model
        // needs ~1-2 s after the sentence ends to translate it).
        private const val TRANSLATION_OVERHEAD_MS = 1500L
        private const val STALL_CHECK_INTERVAL_MS = 10_000L
        private const val MAX_AUTO_RECONNECTS = 5

        // Delegated to DubbingSessionRepository for backward compatibility
        val isRunning: StateFlow<Boolean> get() = DubbingSessionRepository.isDubbingRunning
        val audioAmplitude: StateFlow<Float> get() = DubbingSessionRepository.audioAmplitude
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in serviceScope", throwable)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    // FIX29: filtered-background player (alarm stream), external-app mode only.
    private var backgroundAudioPlayer: BackgroundAudioPlayer? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var settingsObservationJob: Job? = null
    private var catchUpJob: Job? = null
    private var keyWatchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RESYNC -> {
                // FIX18: the browser video jumped to a new scene (user seek).
                // Discard translated audio still waiting to play and clear
                // the subtitle, so nothing from the old scene is spoken over
                // the new picture. The WebAudio/frame delay re-syncs itself
                // in JS (see __aladSeekAt in BrowserActivity).
                audioPlayerManager?.clearPendingAudio()
                DubbingSessionRepository.clearSubtitle()
                Log.i(TAG, "Re-sync after video seek: pending audio + subtitle cleared")
            }
        }

        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, data: Intent) {
        DubbingSessionRepository.setDubbingRunning(true)
        DubbingSessionRepository.setConnectionStatus("Initializing...")
        DubbingSessionRepository.clearSubtitle()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "Failed to get MediaProjection! ResultCode: $resultCode")
            DubbingSessionRepository.setConnectionStatus("Capture Denied")
            DubbingSessionRepository.setDubbingRunning(false)
            return
        }

        // CRITICAL FOR ANDROID 14 (API 34): Register MediaProjection.Callback before starting capture
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        stopDubbing()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping dubbing from projection callback", e)
                    }
                }
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        serviceScope.launch {
            try {
                val repository = UserPreferencesRepository(applicationContext)

                val apiKey = repository.apiKeyFlow.first()
                val targetLang = repository.targetLangFlow.first()
                val targetDialect = repository.targetDialectFlow.first()
                val volumeRatio = repository.volumeRatioFlow.first()
                val isDubbingMuted = repository.isDubbingMutedFlow.first()
                val originalVolume = repository.originalVolumeFlow.first()
                val isOriginalMuted = repository.isOriginalMutedFlow.first()
                val voicePersona = repository.voicePersonaFlow.first()
                val dubbingMode = repository.dubbingModeFlow.first()
                val showDualSubtitles = repository.showDualSubtitlesFlow.first()
                val externalAppMode = repository.externalAppModeFlow.first()
                // FIX29 (cách B): "bỏ âm tiếng gốc, chỉ giữ âm nền". Only in
                // external-app mode with a voice dub (subtitle-only has no
                // voice to dub over the background). Read once at session
                // start — flipping the switch mid-session takes effect on
                // the next dub start, like the external-app switch itself.
                val filterOriginalVoice = repository.filterOriginalVoiceFlow.first()
                // FIX37: browser delayed-background mode. In browser mode the
                // service falls back to system capture when the WebAudio tap
                // is inactive (the site blocks WebAudio, so the DelayNode
                // can't delay the original audio) — the translation input
                // still works, but the original audio then plays live while
                // the picture is delayed. In that case route the original
                // audio through the delayed background player (system
                // capture -> delay -> alarm stream) so it matches the
                // picture, with the dub voice on the alarm stream too and
                // the live WebView audio muted. Decided once at session
                // start, like the other session flags. The voice-filter
                // switch controls filtering in both alarm modes.
                val useSystemCapture = externalAppMode || !BrowserAudioBridge.isActive
                val browserDelayedBackground = !externalAppMode && useSystemCapture
                val alarmMode = (externalAppMode && filterOriginalVoice || browserDelayedBackground) &&
                    dubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY
                // FIX35: dịch nguyên câu hoàn chỉnh. Đọc một lần lúc bắt
                // đầu phiên — đổi công tắc giữa chừng có hiệu lực từ lần
                // bật lồng tiếng sau (lời dặn gửi cho model lúc mở phiên).
                val wholeSentence = repository.wholeSentenceFlow.first()
                // FIX19: live mirror of the external-app mode switch, so the
                // voice hold can be dropped even if the user flips the switch
                // mid-session (before the first translated audio arrives).
                // FIX39: moved up here (was below the text callbacks) so the
                // source-text sampler can read the current mode too.
                var activeExternalMode = externalAppMode
                // FIX27: two Gemini keys (two Google accounts). Key 1 is the
                // existing "api_key"; key 2 is optional. The active key is
                // shared by this whole session; a failover moves it to the
                // other key and persists the choice, so the next session
                // starts on the working key.
                val geminiKeys = listOf(apiKey, repository.geminiApiKey2Flow.first())
                val storedActiveKey = repository.geminiActiveKeyFlow.first().coerceIn(0, 1)
                val activeGeminiKeyIndex = AtomicInteger(
                    when {
                        geminiKeys[storedActiveKey].isNotEmpty() -> storedActiveKey
                        geminiKeys[1 - storedActiveKey].isNotEmpty() -> 1 - storedActiveKey
                        else -> 0
                    }
                )
                val geminiFailoverTried = AtomicBoolean(false)
                fun currentGeminiKey(): String =
                    geminiKeys.getOrNull(activeGeminiKeyIndex.get()).orEmpty()

                // FIX27: switch to the other Gemini key (1 -> 2 or 2 -> 1)
                // and persist the choice. Returns false when there is no
                // other key or the other key already failed in this error
                // episode — the caller then reports that both keys are spent.
                // The flag resets on the next successful Live setup, so a
                // later error can flip back.
                // FIX28: `reason` also picks the status text — rotation /
                // stall / disconnect get their own message instead of the
                // quota wording. Watchdog callers ignore the return value:
                // with a single key they reconnect on the current key.
                fun switchGeminiKey(reason: String): Boolean {
                    if (!geminiFailoverTried.compareAndSet(false, true)) return false
                    val other = 1 - activeGeminiKeyIndex.get()
                    if (geminiKeys.getOrNull(other).isNullOrEmpty()) return false
                    activeGeminiKeyIndex.set(other)
                    serviceScope.launch {
                        try { repository.updateGeminiActiveKey(other) } catch (_: Exception) { }
                    }
                    val msg = when (reason) {
                        "rotation" -> "Xoay key 5 phút — đang dùng Key ${other + 1}"
                        "stall" -> "Gemini ngừng dịch — đã chuyển sang Key ${other + 1}"
                        "disconnect" -> "Mất kết nối — đã chuyển sang Key ${other + 1}"
                        else -> "Key ${2 - other} hết hạn mức — đã chuyển sang Key ${other + 1}"
                    }
                    Log.i(TAG, "Gemini key failover ($reason): $msg")
                    DubbingSessionRepository.setConnectionStatus(msg)
                    return true
                }

                // FIX28: liveness tracking + reconnect helpers. A free Live
                // key dies SILENTLY after ~5 minutes (no error, no close),
                // so the service watches the session itself instead of
                // waiting for an error that never comes.
                val lastConnectAtMs = AtomicLong(SystemClock.elapsedRealtime())
                // FIX34: seeded with the connect time (was 0) so the stall
                // check measures "no output since connect" correctly.
                val lastOutputAtMs = AtomicLong(SystemClock.elapsedRealtime())
                val reconnectInFlight = AtomicBoolean(false)
                val failedReconnects = AtomicInteger(0)

                // Reconnect with the CURRENT key (fresh prefs, new session).
                // Single-flight: overlapping triggers (stall + disconnect
                // at once) collapse into one reconnect. Aborts when the
                // user stopped the dub while the reconnect was queued.
                fun reconnectNow() {
                    if (!reconnectInFlight.compareAndSet(false, true)) return
                    serviceScope.launch {
                        try {
                            if (!DubbingSessionRepository.isDubbingRunning.value) return@launch
                            val lang = repository.targetLangFlow.first()
                            val dialect = repository.targetDialectFlow.first()
                            val voice = repository.voicePersonaFlow.first()
                            val mode = repository.dubbingModeFlow.first()
                            val whole = repository.wholeSentenceFlow.first()
                            // FIX36: keep the VAD gate state across the
                            // reconnect (same dubbing session) so the
                            // sentence start in its pre-roll is not lost.
                            webSocketManager?.disconnect(resetGate = false)
                            val now = SystemClock.elapsedRealtime()
                            lastConnectAtMs.set(now)
                            lastOutputAtMs.set(now)
                            webSocketManager?.connect(
                                apiKey = currentGeminiKey(),
                                sourceLang = "",
                                targetLang = lang,
                                voicePersona = voice,
                                dubbingMode = mode,
                                targetDialect = dialect,
                                wholeSentence = whole
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Reconnect failed", e)
                        } finally {
                            reconnectInFlight.set(false)
                        }
                    }
                }

                // Switch to the other key when one exists, then reconnect
                // either way: with a single key the fresh session alone
                // revives the dub.
                fun rotateKeyAndReconnect(reason: String) {
                    switchGeminiKey(reason)
                    reconnectNow()
                }

                // Sync repository values into the active session
                DubbingSessionRepository.setDubbingVolume(volumeRatio)
                DubbingSessionRepository.setDubbingMuted(isDubbingMuted)
                DubbingSessionRepository.setOriginalVolumeRatio(originalVolume)
                DubbingSessionRepository.setOriginalMuted(isOriginalMuted)
                DubbingSessionRepository.setShowDualSubtitles(showDualSubtitles)

                val okHttpClient = OkHttpClient.Builder()
                    .pingInterval(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                webSocketManager = ALADWebSocketManager(okHttpClient)

                // FIX20: auto catch-up speed coordination. The effective
                // playback speed is the faster of the user's chosen speed
                // and the catch-up speed (0 = catch-up off). Both the
                // settings observer (item 3) and the catch-up loop go
                // through this helper so they never fight over the
                // AudioTrack, and the track is only touched when the
                // effective speed actually changes.
                var basePlaybackSpeed = DubbingSessionRepository.dubbingPlaybackSpeed.value
                var catchUpSpeed = 0f
                var lastAppliedSpeed = -1f
                val applyEffectivePlaybackSpeed: () -> Unit = {
                    val effective = maxOf(basePlaybackSpeed, catchUpSpeed).coerceIn(0.75f, 1.35f)
                    if (effective != lastAppliedSpeed) {
                        lastAppliedSpeed = effective
                        audioPlayerManager?.setPlaybackSpeed(effective)
                    }
                }

                // Initialize AudioPlayerManager if not in SUBTITLE_ONLY mode
                if (dubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                    audioPlayerManager = AudioPlayerManager(applicationContext)
                    // FIX29: alarm mode mutes the other app (STREAM_MUSIC
                    // -> 0) and moves the dub onto the alarm stream.
                    audioPlayerManager?.start(useAlarmStream = alarmMode)
                    audioPlayerManager?.setVoicePersona(voicePersona)
                    audioPlayerManager?.setDubbingAudio(volumeRatio, isDubbingMuted)
                    audioPlayerManager?.setOriginalMediaAudio(originalVolume, isOriginalMuted)
                    applyEffectivePlaybackSpeed()

                    if (alarmMode) {
                        // FIX29: play the voice-filtered background (music
                        // + effects, dialogue removed) on the alarm stream
                        // under the dub. The "original media" slider now
                        // drives this background loudness.
                        // FIX37: in browser delayed-background mode the
                        // filter follows the user's switch (off = the full
                        // original audio, dialogue included) and the
                        // background is delayed to match the picture, so
                        // the music and effects stay in sync with it.
                        backgroundAudioPlayer = BackgroundAudioPlayer(applicationContext)
                        backgroundAudioPlayer?.onPassthroughChanged = { passthrough ->
                            DubbingSessionRepository.setConnectionStatus(
                                if (passthrough) "Phim này tiếng mono — không lọc được giọng gốc, đang phát nguyên tiếng phim"
                                else "Đang lọc giọng gốc — chỉ giữ tiếng nền"
                            )
                        }
                        backgroundAudioPlayer?.start()
                        backgroundAudioPlayer?.setVolume(originalVolume, isOriginalMuted)
                        backgroundAudioPlayer?.setFilterEnabled(filterOriginalVoice)
                        if (browserDelayedBackground) {
                            backgroundAudioPlayer?.setDelayMs(
                                DubbingSessionRepository.selectedMediaDelayMs.value.toLong()
                            )
                            DubbingSessionRepository.setConnectionStatus(
                                "Tiếng gốc đang trễ theo hình qua kênh báo thức — nhạc nền vẫn giữ nguyên"
                            )
                        } else {
                            DubbingSessionRepository.setConnectionStatus("Đang lọc giọng gốc — tiếng nền + giọng dịch phát qua kênh báo thức")
                        }
                        Log.i(TAG, "Voice-filter mode on: background player started (alarm stream)")
                    }
                }

                webSocketManager?.onStatusChanged = { status ->
                    // FIX27: a successful Live setup ends the error episode —
                    // a later quota error may flip to the other key again.
                    // FIX28: it also clears the auto-reconnect failure count.
                    if (status == "Gemini Ready") {
                        geminiFailoverTried.set(false)
                        failedReconnects.set(0)
                    }
                    DubbingSessionRepository.setConnectionStatus(status)
                }

                // FIX27: Gemini reported a key/quota error on the Live
                // connection. Switch to the other key and reconnect instead
                // of stopping the dub; if both keys are spent, say so.
                webSocketManager?.onKeyError = { errMsg ->
                    if (switchGeminiKey(errMsg)) {
                        reconnectNow()
                    } else if (!reconnectInFlight.get()) {
                        // FIX28: if a rotation/stall reconnect is already
                        // running, this error belongs to the dying socket —
                        // say nothing, the fresh session is on its way.
                        val both = geminiKeys[0].isNotEmpty() && geminiKeys[1].isNotEmpty()
                        DubbingSessionRepository.setConnectionStatus(
                            if (both) "Cả 2 key Gemini đều hết hạn mức — hạn mức miễn phí hồi lại theo ngày"
                            else "Lỗi key Gemini: $errMsg"
                        )
                    }
                }

                // FIX28: the socket dropped on its own (server closed the
                // session, network hiccup...) — reconnect without making
                // the user stop/start by hand, alternating keys so a dead
                // key is not retried immediately. After 5 straight failures
                // with no successful setup in between, stop trying and say
                // so instead of looping forever on a dead network.
                // NOTE: no `return@onDisconnected` here — a lambda assigned
                // to a property has no implicit label (that broke the first
                // FIX28 build), so this is plain if/else.
                webSocketManager?.onDisconnected = {
                    if (DubbingSessionRepository.isDubbingRunning.value) {
                        if (failedReconnects.get() >= MAX_AUTO_RECONNECTS) {
                            DubbingSessionRepository.setConnectionStatus(
                                "Mất kết nối Gemini — kiểm tra mạng rồi bật lại"
                            )
                        } else {
                            failedReconnects.incrementAndGet()
                            serviceScope.launch {
                                delay(2000)
                                if (DubbingSessionRepository.isDubbingRunning.value) {
                                    rotateKeyAndReconnect("disconnect")
                                }
                            }
                        }
                    }
                }

                // FIX17: the translate model returns translated AUDIO (the dubbing
                // voice, played through AudioPlayerManager) plus outputTranscription
                // text for subtitles. It never emits [[TVB:...]] speaker markers —
                // formatting requests are unsupported in translation mode — so the
                // old code routed every text delta through a marker parser that
                // dropped it all: no subtitles and no voice. Subtitles now come
                // straight from the transcription delta, and the Android TTS
                // director is no longer used (it would double-speak over Gemini).
                // FIX34: translated text of the CURRENT voice burst, for the
                // FIX30 noise gate. The gate used to judge each chunk by the
                // live subtitle, which accumulates across turns (turnComplete
                // never fires, so it is never cleared) — after a cry scene
                // the stale cry text made the gate drop the first chunks of
                // the next real sentence. Now the text resets at each burst
                // start (see the FIX33 sampler below), so a burst is judged
                // only by its own words. Written from the text callback,
                // read/cleared from the audio callback — both run on the
                // WebSocket reader thread, so no synchronization is needed.
                val burstTranslatedText = StringBuilder()

                // FIX32/FIX33/FIX39: continuous auto-delay adaptation.
                // (Moved up here so the source-text callback below can
                // feed it too; see the FIX39 note there.)
                val latencyEma = LatencyEma()
                val burstDetector = AudioBurstDetector()
                val sentenceDurationTracker = SentenceDurationTracker()

                webSocketManager?.onTextMessageReceived = { textDelta ->
                    // FIX28: translated output proves the key is alive.
                    lastOutputAtMs.set(SystemClock.elapsedRealtime())
                    burstTranslatedText.append(textDelta)
                    DubbingSessionRepository.appendSubtitleText(textDelta)
                }

                webSocketManager?.onSourceTextReceived = { sourceChunk ->
                    DubbingSessionRepository.appendSourceSubtitleText(sourceChunk)
                    // FIX39: whole-sentence mode — time source sentences
                    // from the live transcription. Burst sampling fails
                    // here: the model speaks continuously with no >= 1 s
                    // gap, so no burst start ever fires and the delay
                    // freezes at its seed (16.134 s on the user's phone).
                    // A source sentence's duration + the translation
                    // overhead IS the latency in this mode (the model
                    // waits for the full sentence, then translates it).
                    if (wholeSentence && !activeExternalMode) {
                        val nowMs = SystemClock.elapsedRealtime()
                        for (duration in sentenceDurationTracker.onText(sourceChunk, nowMs)) {
                            val current = DubbingSessionRepository.estimatedDubbingDelayMs.value
                            val updated = latencyEma.sample(duration + TRANSLATION_OVERHEAD_MS, current.toLong())
                            if (updated != null) {
                                DubbingSessionRepository.setEstimatedDubbingDelayMs(updated)
                                Log.i(TAG, "FIX39: auto delay $current -> $updated ms (source sentence ${duration}ms + overhead)")
                            }
                        }
                    }
                }

                // FIX32/FIX33: continuous auto-delay adaptation. Before
                // FIX32 the auto delay was measured exactly once (the first
                // translated audio) and then frozen — and that first
                // latency was clamped at 8 s, so the delay showed exactly
                // 10 s (8 + 2) on every video. FIX32 added per-turn EMA
                // sampling, but armed it on the turn-complete signal — and
                // the translate model does not send turn-complete, so no
                // sample ever ran (the delay stayed at its 12.901 s seed
                // for 24 minutes on the user's phone). FIX33 samples on
                // burst starts instead: a translated-audio chunk arriving
                // after >= 1 s of silence. Same EMA, same guards; only
                // the trigger changed. (latencyEma / burstDetector are
                // defined above the text callbacks so FIX39 can use them.)

                webSocketManager?.onTurnComplete = {
                    // FIX28: a completed turn is translated output too.
                    lastOutputAtMs.set(SystemClock.elapsedRealtime())
                    // FIX21 safety net: if the whole turn was only non-verbal
                    // noise (a pain cry, a groan, laughter...), drop its
                    // queued audio and clear the subtitle at once instead of
                    // letting it linger. The system instruction is the main
                    // filter; this catches the cases where the model
                    // vocalized a groan anyway. In external-app mode the
                    // audio is usually already played by now, so the
                    // instruction does the work there; in browser mode the
                    // audio is still queued behind the video delay and this
                    // drops it before it is heard.
                    val sourceText = DubbingSessionRepository.liveSourceSubtitleText.value
                    val translatedText = DubbingSessionRepository.liveSubtitleText.value
                    if (UtteranceFilter.isMeaningless(sourceText) || UtteranceFilter.isMeaningless(translatedText)) {
                        audioPlayerManager?.clearPendingAudio()
                        DubbingSessionRepository.clearSubtitle()
                        Log.i(TAG, "Filtered meaningless utterance (source='$sourceText' translated='$translatedText')")
                    } else {
                        DubbingSessionRepository.onTurnComplete()
                    }
                }

                var activeDubbingMode = dubbingMode
                val firstAudioSentAtMs = AtomicLong(0L)
                val firstTranslatedAudioMeasured = AtomicBoolean(false)

                webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                    // FIX28: translated audio proves the key is alive.
                    lastOutputAtMs.set(SystemClock.elapsedRealtime())
                    if (!firstTranslatedAudioMeasured.get()) {
                        val sentAt = firstAudioSentAtMs.get()
                        if (sentAt > 0L) {
                            // FIX32: the first-latency clamp was 8 s, so the
                            // delay showed exactly 10 s (8 + 2) whenever the
                            // first translation took 8 s or more — which is
                            // nearly always (cold start + first phrase).
                            // 18 s lets the seed reach the 20 s delay cap.
                            val elapsed = (SystemClock.elapsedRealtime() - sentAt).coerceIn(0L, 18000L)
                            firstTranslatedAudioMeasured.set(true)
                            latencyEma.seed(elapsed)
                            if (activeExternalMode) {
                                // FIX19: the video plays in another app and is
                                // NOT delayed, so there is nothing to align
                                // the voice with — play it the moment it
                                // arrives. Only Gemini's own translation
                                // latency (~1-2 s) remains, and no app can
                                // remove that: the model must hear a phrase
                                // before it can translate it.
                                audioPlayerManager?.setInitialVoiceDelayMs(0L)
                                Log.i(TAG, "External-app mode: first translation latency=${elapsed}ms; no voice hold")
                            } else {
                                // Add a modest playback safety margin; keep the automatic range bounded.
                                // FIX31: adaptive delay cap 10s -> 20s.
                                val adaptiveDelay = (elapsed + 2000L).coerceIn(3000L, 20000L).toInt()
                                DubbingSessionRepository.setEstimatedDubbingDelayMs(adaptiveDelay)
                                val selectedDelay = DubbingSessionRepository.selectedMediaDelayMs.value
                                val remainingVoiceDelay = (selectedDelay - elapsed).coerceAtLeast(0L)
                                audioPlayerManager?.setInitialVoiceDelayMs(remainingVoiceDelay)
                                Log.i(TAG, "First translation latency=${elapsed}ms; source delay=${selectedDelay}ms; translated voice hold=${remainingVoiceDelay}ms")
                            }
                        }
                    }
                    // FIX33: burst-start latency sample for the continuous
                    // auto delay. The detector is fed for every chunk;
                    // only a burst start samples (the first chunk of the
                    // session counts as a burst start, and its sample just
                    // refines the seed). Skipped in external-app mode (no
                    // media delay there) and when no utterance has been
                    // seen yet. A stale utterance start (older than 20 s)
                    // means this burst belongs to an older utterance —
                    // skip the sample instead of feeding a wrong latency
                    // into the EMA.
                    val nowMs = SystemClock.elapsedRealtime()
                    val isBurstStart = burstDetector.onChunk(nowMs)
                    // FIX34: a new burst resets the gate's text, so the
                    // burst is judged only by its own words.
                    if (isBurstStart) burstTranslatedText.clear()
                    if (!activeExternalMode && isBurstStart) {
                        // FIX38: whole-sentence mode translates per
                        // sentence, so its latency is measured from the
                        // sentence start — measuring from the last clause
                        // (a pause over 450 ms opens a new utterance)
                        // understates the latency, leaves the picture
                        // under-delayed, and the voice trails the lips.
                        // Phrase mode keeps the utterance start.
                        val utteranceStart = if (wholeSentence) {
                            webSocketManager?.lastSentenceStartMs() ?: -1L
                        } else {
                            webSocketManager?.lastUtteranceStartMs() ?: -1L
                        }
                        if (utteranceStart > 0L && nowMs - utteranceStart <= 20000L) {
                            val current = DubbingSessionRepository.estimatedDubbingDelayMs.value
                            val updated = latencyEma.sample(nowMs - utteranceStart, current.toLong())
                            if (updated != null) {
                                DubbingSessionRepository.setEstimatedDubbingDelayMs(updated)
                                Log.i(TAG, "FIX33: auto delay $current -> $updated ms (burst sample ${nowMs - utteranceStart}ms, latency EMA ${latencyEma.ema()}ms)")
                            }
                        }
                    }
                    if (activeDubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                        // FIX30: progressive noise gate — while the
                        // translated text of this burst SO FAR is only
                        // cries/groans (no real word yet), drop the voice
                        // chunks instead of speaking them. This works in
                        // external-app mode too, where the turn-complete
                        // filter arrives too late because the voice plays
                        // the moment it arrives. Real sentences are never
                        // dropped: the first real word opens the gate, and
                        // empty text (no transcription yet) means "no
                        // evidence", so the chunk plays.
                        // FIX34: the text judged here is the CURRENT BURST's
                        // text (reset at each burst start above), not the
                        // live subtitle — the subtitle accumulates across
                        // turns, so stale cry text used to drop the first
                        // chunks of the next real sentence after a cry scene.
                        val translatedSoFar = burstTranslatedText.toString()
                        if (translatedSoFar.isNotEmpty() && UtteranceFilter.isMeaningless(translatedSoFar)) {
                            Log.d(TAG, "FIX30: dropped voice chunk, burst so far is noise ('$translatedSoFar')")
                        } else {
                            audioPlayerManager?.playAudioData(audioChunk)
                        }
                    }
                }

                webSocketManager?.connect(
                    apiKey = currentGeminiKey(),
                    sourceLang = "",
                    targetLang = targetLang,
                    voicePersona = voicePersona,
                    dubbingMode = dubbingMode,
                    targetDialect = targetDialect,
                    wholeSentence = wholeSentence
                )

                // FIX19: in external-app mode the phone shows another app, so
                // the floating widget is the only place subtitles can appear.
                // Start it automatically when the overlay permission is
                // already granted; otherwise the user turns it on from the
                // widget card on the main screen.
                if (externalAppMode && android.provider.Settings.canDrawOverlays(applicationContext)) {
                    try {
                        applicationContext.startService(Intent(applicationContext, OverlayWidgetService::class.java))
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not auto-start overlay widget", e)
                    }
                }

                // Dynamic observation for audio controls and settings
                settingsObservationJob?.cancel()
                settingsObservationJob = serviceScope.launch {
                    // 1. Reactive dubbing volume updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.dubbingVolume,
                            DubbingSessionRepository.isDubbingMuted
                        ) { vol, muted -> Pair(vol, muted) }.collect { (vol, muted) ->
                            audioPlayerManager?.setDubbingAudio(vol, muted)
                        }
                    }

                    // 2. Reactive original media volume & ducking updates
                    // FIX29: in alarm mode the slider drives the filtered
                    // background loudness (STREAM_MUSIC stays 0).
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.originalVolumeRatio,
                            DubbingSessionRepository.isOriginalMuted
                        ) { ratio, muted -> Pair(ratio, muted) }.collect { (ratio, muted) ->
                            if (alarmMode) {
                                backgroundAudioPlayer?.setVolume(ratio, muted)
                            } else {
                                audioPlayerManager?.setOriginalMediaAudio(ratio, muted)
                            }
                        }
                    }

                    // FIX37: in browser delayed-background mode, keep the
                    // background delay matched to the picture delay as the
                    // auto delay adapts.
                    if (browserDelayedBackground) {
                        launch {
                            DubbingSessionRepository.selectedMediaDelayMs.collect { delayMs ->
                                backgroundAudioPlayer?.setDelayMs(delayMs.toLong())
                            }
                        }
                    }

                    // 3. Apply translated voice playback speed immediately when adjusted.
                    // FIX20: this only changes the BASE speed; the catch-up
                    // loop may raise the effective speed above it.
                    launch {
                        DubbingSessionRepository.dubbingPlaybackSpeed.collect { speed ->
                            basePlaybackSpeed = speed
                            applyEffectivePlaybackSpeed()
                        }
                    }

                    // 4. Real-time voice persona timbre shaping without dropping WebSocket
                    launch {
                        repository.voicePersonaFlow.collect { newVoice ->
                            audioPlayerManager?.setVoicePersona(newVoice)
                        }
                    }

                    // 5. Real-time dubbing mode switching without dropping WebSocket
                    launch {
                        repository.dubbingModeFlow.collect { newMode ->
                            activeDubbingMode = newMode
                        }
                    }

                    // 6. Reconnect WebSocket ONLY when target language or dialect genuinely changes
                    launch {
                        var activeLang = targetLang
                        var activeDialect = targetDialect
                        kotlinx.coroutines.flow.combine(
                            repository.targetLangFlow,
                            repository.targetDialectFlow
                        ) { lang, dialect -> Pair(lang, dialect) }.collect { (newLang, newDialect) ->
                            if (newLang != activeLang || newDialect != activeDialect) {
                                activeLang = newLang
                                activeDialect = newDialect
                                val currentVoice = repository.voicePersonaFlow.first()
                                val currentMode = repository.dubbingModeFlow.first()
                                val currentWhole = repository.wholeSentenceFlow.first()
                                // FIX36: keep the VAD gate state across the
                                // reconnect (same dubbing session).
                                webSocketManager?.disconnect(resetGate = false)
                                webSocketManager?.connect(
                                    apiKey = currentGeminiKey(),
                                    sourceLang = "",
                                    targetLang = newLang,
                                    voicePersona = currentVoice,
                                    dubbingMode = currentMode,
                                    targetDialect = newDialect,
                                    wholeSentence = currentWhole
                                )
                            }
                        }
                    }

                    // 7. FIX19: external-app mode switch. Dropping the voice hold
                    // live only matters before the first translated audio
                    // arrives; after that the hold is already spent.
                    launch {
                        repository.externalAppModeFlow.collect { enabled ->
                            activeExternalMode = enabled
                            if (enabled) audioPlayerManager?.setInitialVoiceDelayMs(0L)
                        }
                    }
                }

                // FIX20: auto catch-up — only in external-app mode. In browser
                // mode the video is delayed to match the voice, so speeding
                // the voice up would break the sync the delay provides. In
                // external mode the video runs at real time, so when
                // translated audio piles up in the player queue (the voice
                // is falling behind the picture) the player speeds up until
                // it catches up, then returns to the user's chosen speed.
                // Thresholds with a hysteresis band so the speed does not
                // flap: >4 s queued -> 1.35x, >2 s -> 1.20x, <0.8 s -> off.
                catchUpJob?.cancel()
                catchUpJob = serviceScope.launch {
                    while (isActive) {
                        delay(500)
                        val player = audioPlayerManager ?: continue
                        if (!activeExternalMode) {
                            if (catchUpSpeed != 0f) {
                                catchUpSpeed = 0f
                                DubbingSessionRepository.setCatchUpSpeed(0f)
                                applyEffectivePlaybackSpeed()
                            }
                            continue
                        }
                        val pendingMs = player.getPendingAudioMs()
                        val target = when {
                            pendingMs > 4000L -> 1.35f
                            pendingMs > 2000L -> 1.20f
                            pendingMs < 800L -> 0f
                            else -> catchUpSpeed // hysteresis band: hold current speed
                        }
                        if (target != catchUpSpeed) {
                            catchUpSpeed = target
                            DubbingSessionRepository.setCatchUpSpeed(target)
                            applyEffectivePlaybackSpeed()
                            val shown = if (target == 0f) "off" else "${target}x"
                            Log.i(TAG, "Auto catch-up: pending=${pendingMs}ms, speed=$shown")
                        }
                    }
                }

                // FIX28: key watchdog — one loop, two checks, every 10 s.
                // (1) STALL: speech has been flowing but no translated
                // output for 60 s — the key died silently. Switch keys (or
                // just reconnect with the same key) and the dub resumes.
                // FIX34: the old check fired on ANY 60 s output gap once
                // any output had arrived, so long quiet scenes (music, an
                // action scene without dialogue) caused pointless
                // reconnects that each lost a sentence. Now the stall
                // requires speech to have been flowing recently.
                // (2) ROTATION: the session is 5 minutes old — switch to
                // the other key before the free key's silent ~5-minute
                // death. FIX34: the rotation waits for a quiet moment (no
                // speech and no translated output for 3 s) because every
                // reconnect drops 1-3 s of source audio while the new
                // session sets up — rotating mid-sentence loses that
                // sentence. Forced at 6 minutes so non-stop dialogue
                // cannot defer it forever. Any reconnect resets the
                // 5-minute clock.
                keyWatchdogJob?.cancel()
                keyWatchdogJob = serviceScope.launch {
                    val rotationPolicy = RotationPolicy(
                        rotationIntervalMs = KEY_ROTATION_INTERVAL_MS,
                        forceRotationMs = FORCE_ROTATION_MS,
                        quietMs = ROTATION_QUIET_MS,
                        stallTimeoutMs = STALL_TIMEOUT_MS
                    )
                    while (isActive) {
                        delay(STALL_CHECK_INTERVAL_MS)
                        val now = SystemClock.elapsedRealtime()
                        val lastSpeech = webSocketManager?.lastSpeechAtMs() ?: -1L
                        if (rotationPolicy.shouldReconnectForStall(now, lastOutputAtMs.get(), lastSpeech)) {
                            Log.i(TAG, "Key watchdog: speech flowing but no translated output for ${STALL_TIMEOUT_MS / 1000}s — switching key")
                            rotateKeyAndReconnect("stall")
                            continue
                        }
                        if (rotationPolicy.shouldRotate(now, lastConnectAtMs.get(), lastOutputAtMs.get(), lastSpeech)) {
                            Log.i(TAG, "Key watchdog: 5-minute rotation (quiet moment)")
                            rotateKeyAndReconnect("rotation")
                        }
                    }
                }

                // Prefer the in-WebView WebAudio tap. It splits source PCM before
                // DelayNode playback, so Gemini receives live audio while speakers
                // receive the delayed original mix. System capture remains fallback
                // for pages where WebAudio is unavailable or CORS/DRM returns silence.
                // FIX19: in external-app mode the video plays in another app, so
                // the in-WebView tap is never used — system capture is the only
                // source. Skip the WebAudio listeners entirely.
                if (!externalAppMode) BrowserAudioBridge.setListeners(
                    onPcm = { pcmData ->
                        firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                        webSocketManager?.sendAudioData(pcmData)
                        val currentAmp = DubbingSessionRepository.audioAmplitude.value
                        DubbingSessionRepository.setAudioAmplitude(
                            AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                        )
                    },
                    onStatus = { message -> Log.i(TAG, "Browser audio: $message") },
                    onModeChanged = { webAudioActive ->
                        serviceScope.launch(Dispatchers.IO) {
                            if (webAudioActive) {
                                // Stop system playback capture only after the first
                                // non-silent WebAudio PCM block has reached this service.
                                audioCaptureManager?.stopCapture()
                                audioCaptureManager = null
                                Log.i(TAG, "WebAudio active: system capture stopped to prevent duplicate PCM")
                            } else if (DubbingSessionRepository.isDubbingRunning.value &&
                                audioCaptureManager == null && mediaProjection != null) {
                                val projectionForFallback = mediaProjection
                                audioCaptureManager = AudioCaptureManager()
                                audioCaptureManager?.startCapture(projectionForFallback!!, applicationInfo.uid) { pcmData ->
                                    if (BrowserAudioBridge.isActive) return@startCapture
                                    firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                                    webSocketManager?.sendAudioData(pcmData)
                                    val amp = DubbingSessionRepository.audioAmplitude.value
                                    DubbingSessionRepository.setAudioAmplitude(AudioUtils.processPcmAmplitude(pcmData, amp))
                                }
                                Log.w(TAG, "WebAudio unavailable: system capture fallback resumed")
                            }
                        }
                    }
                )

                audioCaptureManager = AudioCaptureManager()
                val appUid = applicationInfo.uid
                // FIX19: external-app mode always uses system capture (the
                // video is in another app); otherwise keep the old rule.
                // (useSystemCapture was decided once at session start,
                // next to alarmMode, so the mode and the capture agree.)
                if (useSystemCapture) mediaProjection?.let { projection ->
                    if (alarmMode) {
                        // FIX29: stereo capture — the mono downmix feeds
                        // Gemini exactly as before, the stereo feeds the
                        // voice-filtered background player.
                        audioCaptureManager?.startCaptureStereo(
                            projection, appUid,
                            onMonoAudioData = { pcmData ->
                                if (BrowserAudioBridge.isActive) return@startCaptureStereo
                                firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                                webSocketManager?.sendAudioData(pcmData)
                                val currentAmp = DubbingSessionRepository.audioAmplitude.value
                                DubbingSessionRepository.setAudioAmplitude(
                                    AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                                )
                            },
                            onStereoAudioData = { stereoData ->
                                // FIX37: in browser delayed-background mode
                                // the background player is the only original
                                // audio (the WebView is muted), so keep
                                // feeding it even if the WebAudio tap comes
                                // alive later. External mode keeps the old
                                // guard.
                                if (externalAppMode && BrowserAudioBridge.isActive) return@startCaptureStereo
                                backgroundAudioPlayer?.feedPcm(stereoData)
                            }
                        )
                    } else {
                        audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                            // Once a non-silent WebAudio block arrives, stop forwarding
                            // system-captured copies to prevent duplicate Gemini input.
                            if (BrowserAudioBridge.isActive) return@startCapture
                            firstAudioSentAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
                            webSocketManager?.sendAudioData(pcmData)
                            val currentAmp = DubbingSessionRepository.audioAmplitude.value
                            DubbingSessionRepository.setAudioAmplitude(
                                AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during dubbing session startup", e)
                DubbingSessionRepository.setConnectionStatus("Error: ${e.message}")
            }
        }
    }

    private fun stopDubbing() {
        BrowserAudioBridge.reset()
        DubbingSessionRepository.resetSession()

        audioCaptureManager?.stopCapture()
        audioCaptureManager = null

        // FIX29: stop the background player BEFORE the dub player restores
        // the system volumes, so no burst plays at the restored alarm level.
        backgroundAudioPlayer?.stop()
        backgroundAudioPlayer = null

        audioPlayerManager?.stop()
        audioPlayerManager = null

        webSocketManager?.disconnect()
        webSocketManager = null

        settingsObservationJob?.cancel()
        settingsObservationJob = null
        catchUpJob?.cancel()
        catchUpJob = null
        keyWatchdogJob?.cancel()
        keyWatchdogJob = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DubbingSessionRepository.isDubbingRunning.value) {
            stopDubbing()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Dubbing Service"
            val descriptionText = "Capturing and streaming audio for dubbing"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD Live Dubbing")
            .setContentText("Connected and dubbing...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
        }
}
