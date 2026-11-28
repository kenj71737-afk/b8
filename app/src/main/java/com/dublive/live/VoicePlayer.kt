package com.dublive.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
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
 *  - Gemini Live audio (24 kHz PCM) played through AudioTrack.
 *  - TextToSpeech fallback (Vietnamese) when the model returns text only.
 *    Reading speeds up to catch up: lag > 2 s -> 1.2x, lag > 4 s -> 1.35x.
 *
 * OUTPUT STREAM (v1.0.11 — selectable, was always the call stream):
 *  - USAGE_VOICE_COMMUNICATION (default): the system-capture mode.
 *    The capture only matches USAGE_MEDIA/GAME, so the dub voice is
 *    NOT re-captured (no echo loop). The call stream is quiet, so the
 *    track carries a LoudnessEnhancer (+8 dB).
 *  - USAGE_MEDIA: the WebAudio-tap and local-tap modes. The film
 *    sound comes from INSIDE the page / the ExoPlayer tap processor,
 *    never from the system capture — so no capture runs at all and
 *    the dub voice on MEDIA cannot be re-captured. Loud and on the
 *    stream the user's volume buttons already control; no enhancer
 *    (media is loud already, +8 dB would clip).
 *  [setOutputUsage] rebuilds the AudioTrack live (mode switch on tap
 *  fallback) WITHOUT clearing the hold queue — in-flight dubbed audio
 *  survives the switch.
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
 *
 * v1.0.17 — PLAY-START MEASUREMENT (the old delay was anchored on
 * ARRIVAL, not on sound). A chunk taken from the hold queue is
 * written to the AudioTrack, but it only SOUNDS once the hardware
 * drains the frames already queued ahead of it — the pipeline
 * (buffer backlog + hardware latency, typically 100–300 ms). The
 * old measurement assumed that pipeline was 0 and hid it inside a
 * fixed 2000 ms margin; the voice therefore always played
 * (pipeline) late against the picture, and no D could fix it
 * (the hold moves the voice by exactly as much as D moves the
 * picture). Now every burst's first chunk is measured: the service
 * calls [noteBurstStart] before the burst's first [playAudio], and
 * the player thread reports the real play start through
 * [onBurstPlayed] (burst seq, wall time the first frame sounded,
 * pipeline = play − due, estimated flag). Measurement uses
 * AudioTrack.getTimestamp(): at write time it predicts when the
 * chunk's first frame will render, then the thread polls the
 * playhead every loop tick until the frame position passes that
 * frame and reports the observed wall time (accurate to the ~5 ms
 * poll). If getTimestamp is unavailable (some OEM builds), the
 * fallback is due + queued audio + 30 ms hardware estimate, flagged
 * estimated=true so the diagnostics do not present it as measured.
 * TTS turns are not measured (no AudioTrack PCM for them) — the
 * tracker keeps its last pipeline value for those turns.
 */
class VoicePlayer(
    context: Context,
    private val onSpeechStarted: () -> Unit,
    private val onTtsStarted: () -> Unit = {},
    /** v1.0.11 — output stream for this session (see class doc). */
    initialUsage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
    /**
     * v1.0.17 — per-burst play report: (burst seq, wall time the
     * burst's first frame actually sounded, pipeline ms =
     * play − (arrival + hold), true when the value is the
     * queue-based fallback instead of a getTimestamp measurement).
     * v1.0.18 — fifth value: the backlog (ms) already queued ahead
     * of the burst's first frame at write time.
     * v1.0.23 — sixth value: the MEASURED applied stretch speed
     * (playhead slope, [measuredSpeed]) at burst play, 0.0 when
     * not measured — the tracker divides the in-burst dub offset
     * by THIS, not by the target (HUD 1,50× trên ảnh 12:00 có thể
     * là số giả trong khi track kẹt 1,0×).
     */
    private val onBurstPlayed: (Long, Long, Long, Boolean, Long, Double) -> Unit = { _, _, _, _, _, _ -> },
    /**
     * v1.0.19 — TIME-STRETCH catch-up (the runaway-backlog fix).
     * Three callbacks into the tracker, all called on the player
     * thread:
     *  - [onPipelineSample]: every 20th chunk, the TYPICAL
     *    pipeline (render − due) so the tracker feeds its EMA
     *    with the mid-burst pipeline, not the burst-first one
     *    (which under-measures it ~10x).
     *  - [onWriteLate]: every chunk taken, how late the take was
     *    vs its due (take − due, ms). The tracker raises the
     *    stretch to 1.5x when the loop is >200 ms behind.
     *  - [onTrackLevel]: after every chunk write, the audio
     *    queued in the track (ms) and the buffer cap (ms). The
     *    tracker raises the stretch when the level is high.
     */
    private val onPipelineSample: (Long, Long) -> Unit = { _, _ -> },
    private val onWriteLate: (Long) -> Unit = { _ -> },
    private val onTrackLevel: (Long, Long) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "DubLive-Voice"

        /**
         * v1.0.11 — the dub slider drives the SYSTEM volume of the
         * stream the voice is currently on: STREAM_VOICE_CALL in
         * capture mode, STREAM_MUSIC in tap mode (media). The service
         * picks the right one for the current audio mode
         * (DubbingService.applyDubStreamVolume); Settings sends
         * ACTION_APPLY_DUB_VOLUME so a mid-session drag lands on the
         * stream that is actually playing.
         */
        fun applyCallVolume(context: Context, pct: Int) {
            applyStreamVolume(context, AudioManager.STREAM_VOICE_CALL, pct)
        }

        /** v1.0.11 — dub slider -> media-stream volume (tap modes). */
        fun applyMediaVolume(context: Context, pct: Int) {
            applyStreamVolume(context, AudioManager.STREAM_MUSIC, pct)
        }

        private fun applyStreamVolume(context: Context, stream: Int, pct: Int) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val index = ((pct.coerceIn(0, 100) / 100f) * max).roundToInt().coerceIn(0, max)
                am.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
            } catch (e: Exception) {
                Log.w(TAG, "setStreamVolume (dub voice) failed", e)
            }
        }

        /**
         * v1.0.10 — the dub voice used USAGE_VOICE_COMMUNICATION
         * always (the alarm option was removed at the user's request).
         * v1.0.11 — the usage is per-session now: the call stream in
         * system-capture mode, USAGE_MEDIA in the tap modes (no
         * capture runs there, so MEDIA cannot echo). Kept for
         * callers that need the capture-mode usage.
         */
        fun dubUsage(context: Context): Int =
            AudioAttributes.USAGE_VOICE_COMMUNICATION
    }

    private val appContext = context.applicationContext

    /**
     * v1.0.11 — current output stream. Changed live by
     * [setOutputUsage] on a mode switch (tap -> capture fallback);
     * the hold queue survives the rebuild.
     */
    var outputUsage: Int = initialUsage
        private set

    /**
     * v1.0.9 — the dubbed voice waits behind its burst hold instead of
     * playing on arrival. [currentHoldMs] is set by the service at
     * every burst start (hold = D − latency of that burst); each chunk
     * enqueued by [playAudio] is due at arrival + that hold. A later
     * burst's different hold never retimes chunks already queued —
     * the queue stores a fixed due time per block.
     *
     * v1.0.19 — staleMs 700 (was the 500 default): a chunk >700 ms
     * overdue is dropped instead of played late. The sim showed
     * 500 ms drops 7.2% of the audio at a 2.5x generation burst,
     * 700 ms drops 3.1% and the 1.5x sync stays <500 ms, while
     * 1000 ms breaks the 1.5x sync (511 ms). Only the dub voice
     * gets 700 ms — the background player's queue keeps 500 ms.
     */
    private val holdQueue = DelayedAudioQueue(staleMs = 700L)
    /** v1.0.21 (21d) — dubbed-voice chunks dropped by the hold
     * queue (overdue >700 ms, or evicted past the cap) this
     * session, for the diagnostics HUD. */
    val droppedChunks: Int get() = holdQueue.droppedBlocks
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

    // ---- v1.0.17 — per-burst play measurement ----
    /**
     * The service calls [noteBurstStart] before the burst's first
     * [playAudio]; the note is attached to the next chunk enqueued
     * (chunks are FIFO, so the burst's first chunk is identified by
     * its enqueue index). The player thread confirms the play start
     * by polling the AudioTrack playhead until the chunk's first
     * frame has rendered. All three structures are touched from the
     * Live thread (note/playAudio) and the player thread (take/
     * confirm) — guarded by [measureLock].
     */
    private val measureLock = Any()
    private data class BurstStartInfo(
        val seq: Long,
        val arrivalWallMs: Long,
        val holdMs: Long,
        val chunkIndex: Long
    )
    private data class PendingConfirm(
        val seq: Long,
        val dueWallMs: Long,
        val firstFrame: Long,
        val queuedAtWallMs: Long,
        val predictedPlayWallMs: Long?,
        val predictedEstimated: Boolean,
        /** v1.0.18 — audio already queued ahead of this burst's
         * first frame at write time (ms), for the diagnostics. */
        val backlogMs: Long,
        val arrivalWallMs: Long,
        val holdMs: Long
    )
    private var chunksAdded = 0L
    private var chunksTaken = 0L
    private var totalFramesWritten = 0L
    private var pendingBurstNote: BurstStartInfo? = null
    private val burstStartQueue = ArrayDeque<BurstStartInfo>()
    private var pendingConfirm: PendingConfirm? = null
    /** A playhead confirmation not seen within this long falls back
     * to the timestamp prediction made at write time. */
    private val CONFIRM_TIMEOUT_MS = 2000L
    /** Hardware latency assumed by the fallback estimate (no
     * getTimestamp available). */
    private val FALLBACK_HW_MS = 30L
    /** v1.0.18 — max bytes per AudioTrack.write() call (50 ms of
     * 24 kHz mono 16-bit audio): the playhead poll runs between
     * slices, so it is never starved longer than ~50 ms. */
    private val SLICE_BYTES = 2400
    /**
     * v1.0.19 — TIME-STRETCH (AudioTrack.setPlaybackParams, Sonic
     * inside the framework: speed up without changing pitch).
     * The tracker asks for 1.0–1.5x; the service calls
     * [setPlaybackSpeed] and the player loop applies it. The
     * pending speed is applied in the player thread (not in the
     * setter) so a track rebuild never races a setPlaybackParams
     * call on a dead track. [stretchSupported] goes false forever
     * once setPlaybackParams throws — the player then stays at
     * 1.0x and the tracker falls back to word-drop.
     */
    @Volatile private var pendingSpeed = 1.0
    @Volatile private var currentSpeed = 1.0
    @Volatile private var stretchSupported = true
    /** v1.0.23 — THIẾT BỊ ĐO TỐC ĐỘ ÁP DỤNG THẬT (báo cáo khả thi
     * §5 — đóng số hạng ~1800 ms chưa giải thích được trên ảnh
     * 12:00, khi HUD in 1,50× nhưng không ai biết track thật
     * chạy bao nhiêu):
     *  - [appliedSpeedReadback]: tốc độ đọc lại từ
     *    track.playbackParams ngay sau setPlaybackParams.
     *  - [appliedSpeedMismatch]: readback lệch target >0,02.
     *  - [measuredSpeed]: tốc độ thật đo bằng độ dốc playhead
     *    (dFrames/dWall/24000) trên cửa sổ 2 s, null khi không
     *    phát/không đo được. framePosition đếm frame ĐẦU RA sau
     *    stretch nên độ dốc chính là tốc độ thật — không cần ánh
     *    xạ input/output.
     *  - stretchSupported=false KHÔNG còn vĩnh viễn: mỗi lần
     *    buildTrack (track mới) thử lại một lần; chỉ khi throw
     *    tiếp mới lại false.
     */
    @Volatile private var appliedSpeedReadback = 1.0
    @Volatile private var appliedSpeedMismatch = false
    @Volatile private var stretchUnsupportedLogged = false
    private val speedSamples = ArrayDeque<Pair<Long, Long>>()
    private var lastSpeedSampleAtMs = 0L
    @Volatile private var measuredSpeedValue: Double? = null
    /** Tốc độ thật đang phát (độ dốc playhead), null khi chưa đo được. */
    val measuredSpeed: Double? get() = measuredSpeedValue
    /** Tốc độ đọc lại từ track sau lần setPlaybackParams gần nhất. */
    val appliedSpeed: Double get() = appliedSpeedReadback
    /** True khi readback lệch target >0,02. */
    val isAppliedSpeedMismatch: Boolean get() = appliedSpeedMismatch
    /** v1.0.19 — how often a mid-burst pipeline sample is taken
     * (every 20th chunk ≈ every 10 s of dub). */
    private val PIPELINE_SAMPLE_EVERY = 20
    private var chunksSincePipelineSample = 0
    /**
     * v1.0.19 — the framework's minimum buffer (bytes) and the
     * buffer actually built (bytes), for the diagnostics/log. The
     * honest hardware floor: at minBuf 32 KB the pipeline cannot
     * go below ~682 ms, and the dynamic margin must be built from
     * the MEASURED pipeline, not from the sim's 100–300 ms hope.
     */
    @Volatile var minBufferBytes = 0
        private set
    @Volatile var bufferBytes = 0
        private set

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
        buildTrack()
    }

    /**
     * v1.0.11 — build (or rebuild) the AudioTrack + playback thread
     * for the current [outputUsage]. The hold queue is NOT touched:
     * chunks queued before a mode switch keep playing after it.
     * The LoudnessEnhancer only applies to the call stream — the
     * media stream is loud already and +8 dB would clip it.
     */
    private fun buildTrack() {
        val attrs = AudioAttributes.Builder()
            .setUsage(outputUsage)
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
            // v1.0.18 — the buffer was minBuf*4 (64 KB = 1365 ms of
            // audio at minBuf 16 KB: the 1438 ms pipeline spike on
            // the 27/11 device photos matches 1365 + ~70 ms
            // hardware/poll). Now max(minBuf, min(minBuf*2, 12000 B))
            // — cap 250 ms, never below the framework minimum.
            // At minBuf 16 KB the buffer stays 16 KB (341 ms, the
            // framework floor); at 4 KB it is 8 KB (170 ms).
            val bufBytes = if (minBuf > 0) {
                maxOf(minBuf, minOf(minBuf * 2, 12000))
            } else {
                4096
            }
            // v1.0.19 — remember the floor for the diagnostics/log:
            // the device's minBuf is the honest pipeline floor.
            minBufferBytes = if (minBuf > 0) minBuf else 0
            bufferBytes = bufBytes
            Log.i(TAG, "AudioTrack minBuf=%d B (%d ms) buf=%d B (%d ms)"
                .format(minBufferBytes, minBufferBytes / 48,
                    bufferBytes, bufferBytes / 48))
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
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
        // v1.0.23 — track MỚI = thử lại stretch một lần: false cũ
        // chỉ áp cho track đã chết (setPlaybackParams throw một
        // lần → kẹt 1,0× vĩnh viễn, không chỗ nào set lại true —
        // ứng viên số 1 cho số hạng ~1800 ms trên ảnh 12:00).
        // Nếu máy thật sự không hỗ trợ, lần áp đầu tiên sẽ throw
        // lại và false như cũ — nhưng ít nhất mỗi rebuild được
        // một cơ hội, và HUD (isStretchSupported) nói rõ trạng
        // thái thay vì im lặng.
        stretchSupported = true
        stretchUnsupportedLogged = false
        appliedSpeedReadback = 1.0
        appliedSpeedMismatch = false
        synchronized(speedSamples) { speedSamples.clear() }
        measuredSpeedValue = null
        try { track.setVolume(1.0f) } catch (_: Exception) { }
        // v1.0.7 — LoudnessEnhancer: boost the dub voice beyond the
        // 1.0f gain ceiling (+8 dB target). Try 800 mB, then 500, then
        // 0 (enabled but no gain); any failure just means no boost —
        // start() must never die here. v1.0.11 — call stream only:
        // on USAGE_MEDIA the enhancer would clip a stream that is
        // already loud.
        if (outputUsage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
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
        }
        playerThread = Thread({
            while (running) {
                try {
                    // v1.0.19 — apply the pending time-stretch speed
                    // (the service pushes the tracker's target; the
                    // setter only stores it, the loop applies it so
                    // a rebuild never races setPlaybackParams on a
                    // dead track). setPlaybackParams is Sonic inside
                    // the framework: faster without changing pitch.
                    applyPendingSpeed()
                    // v1.0.17 — even with no chunk due, poll the
                    // playhead: a burst-start confirmation may be
                    // waiting for its first frame to render.
                    confirmPlayStart()
                    // v1.0.23 — tốc độ thật bằng độ dốc playhead
                    // (mỗi vòng lặp, throttle 50 ms bên trong).
                    samplePlayheadForSpeed()
                    val takeNow = System.currentTimeMillis()
                    val dueInfo = holdQueue.pollDueInfo(takeNow, 0L)
                    if (dueInfo == null) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    val chunk = dueInfo.first
                    val dueMs = dueInfo.second
                    // v1.0.19 — loop lateness (take − due): how far
                    // behind the player loop is. The tracker raises
                    // the stretch to 1.5x when this exceeds 200 ms
                    // (a 2.5x generation burst sits at ~330 ms
                    // median, a 1.5x one at ~40 ms).
                    try { onWriteLate((takeNow - dueMs).coerceAtLeast(0L)) } catch (_: Exception) { }
                    lastDubAudioAtMs = takeNow
                    if (!speechStartedSignalled) {
                        speechStartedSignalled = true
                        try { onSpeechStarted() } catch (_: Exception) { }
                    }
                    // v1.0.17 — is this chunk a burst's first one?
                    // Its enqueue index matches the burst-start note
                    // the service attached before playAudio().
                    val chunkIndex = synchronized(measureLock) {
                        val idx = chunksTaken
                        chunksTaken++
                        idx
                    }
                    val burstStart = synchronized(measureLock) {
                        // Drop notes for chunks that never arrived
                        // (e.g. a burst noted but never played).
                        while (burstStartQueue.isNotEmpty() &&
                            burstStartQueue.first().chunkIndex < chunkIndex
                        ) {
                            burstStartQueue.removeFirst()
                        }
                        if (burstStartQueue.isNotEmpty() &&
                            burstStartQueue.first().chunkIndex == chunkIndex
                        ) {
                            burstStartQueue.removeFirst()
                        } else {
                            null
                        }
                    }
                    val track = audioTrack
                    val firstFrame = totalFramesWritten
                    // v1.0.18 — the backlog ahead of this chunk's
                    // first frame, read from the playhead BEFORE
                    // writing (the tracker shows it as "chờ Z ms"
                    // so a big pipeline can be told apart: backlog
                    // vs hardware). Null playhead = unknown.
                    val backlogMs = try {
                        val head = readPlayhead()
                        if (head != null) {
                            maxOf(0L, ((firstFrame - head.first) * 1000L) / 24000L)
                        } else {
                            0L
                        }
                    } catch (_: Exception) {
                        0L
                    }
                    // v1.0.18 — write in <=2400 B (50 ms) slices
                    // with confirmPlayStart() between slices. The
                    // old code wrote a whole chunk in ONE blocking
                    // write() while confirmPlayStart() only ran at
                    // the top of the loop — the playhead poll was
                    // STARVED for the whole blocking write and the
                    // observed play wall was late by the block
                    // duration (the 1438 ms spike). A slice blocks
                    // at most ~50 ms, so the poll is never starved
                    // longer than that.
                    var writtenBytes = 0
                    if (track != null) {
                        var off = 0
                        while (off < chunk.size) {
                            val n = minOf(SLICE_BYTES, chunk.size - off)
                            try {
                                track.write(chunk, off, n)
                                writtenBytes += n
                            } catch (_: Exception) {
                                break
                            }
                            off += n
                            if (off < chunk.size) confirmPlayStart()
                        }
                    }
                    totalFramesWritten += (writtenBytes / 2).toLong()
                    // v1.0.19 — post-write track level + mid-burst
                    // pipeline sample. The level after this write is
                    // the audio queued in the track; the backlog
                    // ahead of THIS chunk's first frame is the level
                    // minus the chunk just written (the chunk sits
                    // at the END of the queue). Its first frame
                    // renders after that backlog drains at the
                    // current stretch speed, plus hardware — so the
                    // typical pipeline is (now − due) + backlog /
                    // speed + HW. Every 20th chunk this feeds the
                    // tracker's pipeline EMA (the burst-first
                    // pipeline under-measures the typical one ~10x).
                    val writeDoneWall = System.currentTimeMillis()
                    val chunkMs = (writtenBytes / 2L * 1000L) / 24000L
                    val capMs = if (bufferBytes > 0) bufferBytes / 48L else 0L
                    val levelAfterMs = try {
                        val head = readPlayhead()
                        if (head != null) {
                            maxOf(0L, ((totalFramesWritten - head.first) * 1000L) / 24000L)
                        } else {
                            backlogMs + chunkMs
                        }
                    } catch (_: Exception) {
                        backlogMs + chunkMs
                    }
                    try { onTrackLevel(levelAfterMs, capMs) } catch (_: Exception) { }
                    chunksSincePipelineSample++
                    if (chunksSincePipelineSample >= PIPELINE_SAMPLE_EVERY) {
                        chunksSincePipelineSample = 0
                        val aheadMs = maxOf(0L, levelAfterMs - chunkMs)
                        val speed = currentSpeed.coerceAtLeast(1.0)
                        val pipeSample = (writeDoneWall - dueMs +
                            (aheadMs / speed).toLong() + FALLBACK_HW_MS)
                            .coerceAtLeast(0L)
                        try { onPipelineSample(pipeSample, backlogMs) } catch (_: Exception) { }
                    }
                    if (burstStart != null && track != null) {
                        beginPlayMeasurement(burstStart, firstFrame, backlogMs)
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DubLive-AudioOut").apply { start() }
    }

    /**
     * v1.0.17 — the AudioTrack playhead, as (framePosition,
     * wall time the timestamp was taken).
     * v1.0.20 — CLOCK FIX (Error 1, device 28/11: "lệch hình
     * -135,043,353 ms" ≈ -37.5 h). AudioTimestamp.nanoTime is
     * in the CLOCK_MONOTONIC base (same as System.nanoTime()).
     * The old code subtracted SystemClock.elapsedRealtimeNanos(),
     * which is CLOCK_BOOTTIME (MONOTONIC + total suspend time
     * since boot) — the difference is MINUS the phone's total
     * suspend time, so the predicted play wall was ~37.5 h in
     * the past and every sync number derived from it was
     * garbage (its own comment claiming the two share a base
     * was WRONG). Reference System.nanoTime() (same base) and
     * validate the timestamp is fresh: a nanoTime more than
     * 60 s from now is stale/bogus — return null so the caller
     * falls back to due + hardware (estimated).
     */
    private fun readPlayhead(): Pair<Long, Long>? {
        val track = audioTrack ?: return null
        return try {
            val ts = AudioTimestamp()
            if (!track.getTimestamp(ts)) return null
            if (ts.framePosition < 0L) return null
            val nanoNow = System.nanoTime()
            if (kotlin.math.abs(ts.nanoTime - nanoNow) > 60_000_000_000L) return null
            val wallNow = System.currentTimeMillis()
            val tsWall = wallNow + (ts.nanoTime - nanoNow) / 1_000_000L
            ts.framePosition to tsWall
        } catch (_: Exception) {
            null
        }
    }

    /**
     * v1.0.17 — a burst's first chunk was just written: its first
     * frame is [firstFrame] in the track's frame stream and it was
     * due at (arrival + hold). Predict when it will sound from the
     * playhead now, then confirm by polling: the prediction is
     * exact only if the playhead keeps advancing at real-time rate.
     * When getTimestamp is unavailable, the fallback predicts
     * due + audio already queued ahead + 30 ms hardware.
     */
    private fun beginPlayMeasurement(start: BurstStartInfo, firstFrame: Long, backlogMs: Long) {
        val dueWall = start.arrivalWallMs + start.holdMs
        val (rawPredicted, estimated) = try {
            val head = readPlayhead()
            if (head == null) {
                // No timestamp on this device: no way to know the
                // backlog ahead of this chunk. The honest estimate
                // is due + hardware latency (a burst start usually
                // follows an idle track, so the backlog is ~0);
                // flagged estimated so the diagnostics say so.
                (dueWall + FALLBACK_HW_MS) to true
            } else {
                val (pos, tsWall) = head
                if (firstFrame <= pos) {
                    // Already rendering (underrun / tiny backlog).
                    tsWall to false
                } else {
                    // v1.0.19 — the backlog ahead drains at the
                    // current stretch speed, not real-time: divide
                    // by the speed (at 1.5x a 600 ms backlog drains
                    // in 400 ms). At 1.0x this is unchanged.
                    val speed = currentSpeed.coerceAtLeast(1.0)
                    tsWall + (((firstFrame - pos) * 1000L) / (24000L * speed)).toLong() to false
                }
            }
        } catch (_: Exception) {
            (dueWall + FALLBACK_HW_MS) to true
        }
        // v1.0.20 — clamp the prediction: a play wall more than
        // 1 s before its due or more than 20 s after it cannot be
        // real (this burst's audio is due now). A poisoned clock
        // (v1.0.19: -37.5 h) must never reach the tracker, even
        // if a future readPlayhead bug slips past the 60 s check.
        val predicted = rawPredicted.coerceIn(dueWall - 1000L, dueWall + 20000L)
        synchronized(measureLock) {
            pendingConfirm = PendingConfirm(
                seq = start.seq,
                dueWallMs = dueWall,
                firstFrame = firstFrame,
                queuedAtWallMs = System.currentTimeMillis(),
                predictedPlayWallMs = predicted,
                predictedEstimated = estimated,
                backlogMs = backlogMs,
                arrivalWallMs = start.arrivalWallMs,
                holdMs = start.holdMs
            )
        }
    }

    /**
     * v1.0.17 — called every player-loop tick: if a burst-start
     * confirmation is pending, check whether its first frame has
     * rendered yet. On confirmation report the OBSERVED wall time
     * (accurate to the ~5 ms poll); on timeout report the
     * prediction made at write time, flagged estimated.
     */
    private fun confirmPlayStart() {
        val confirm = synchronized(measureLock) { pendingConfirm } ?: return
        val nowWall = System.currentTimeMillis()
        // v1.0.19 — when the stretch is on (speed != 1.0), the
        // playhead framePosition counts POST-STRETCH output frames,
        // which advance faster than the input frames: the
        // `head.first >= firstFrame` check would fire EARLY (before
        // the audio actually sounds). Skip the poll and report the
        // prediction made at write time (which already divides the
        // backlog drain by the speed), flagged estimated=true.
        if (currentSpeed != 1.0) {
            synchronized(measureLock) {
                if (pendingConfirm === confirm) pendingConfirm = null
            }
            val playWall = confirm.predictedPlayWallMs ?: nowWall
            val pipeline = (playWall - confirm.dueWallMs).coerceAtLeast(0L)
            Log.i(TAG, "burst seq=%d arrival=%d hold=%d due=%d backlog=%d play=%d pipeline=%d est=%b (stretch %.2fx, predicted)"
                .format(confirm.seq, confirm.arrivalWallMs, confirm.holdMs,
                    confirm.dueWallMs, confirm.backlogMs, playWall, pipeline, true, currentSpeed))
            try { onBurstPlayed(confirm.seq, playWall, pipeline, true, confirm.backlogMs, measuredSpeed ?: 0.0) } catch (_: Exception) { }
            return
        }
        val head = try { readPlayhead() } catch (_: Exception) { null }
        if (head != null && head.first >= confirm.firstFrame) {
            synchronized(measureLock) {
                if (pendingConfirm === confirm) pendingConfirm = null
            }
            val pipeline = (nowWall - confirm.dueWallMs).coerceAtLeast(0L)
            // v1.0.18 — per-burst pipeline log: arrival, hold, due,
            // backlog ahead at write, play wall, pipeline, estimate
            // flag. This is the record that proves (or disproves)
            // the pipeline fix on a real device.
            Log.i(TAG, "burst seq=%d arrival=%d hold=%d due=%d backlog=%d play=%d pipeline=%d est=%b"
                .format(confirm.seq, confirm.arrivalWallMs, confirm.holdMs,
                    confirm.dueWallMs, confirm.backlogMs, nowWall, pipeline, false))
            try { onBurstPlayed(confirm.seq, nowWall, pipeline, false, confirm.backlogMs, measuredSpeed ?: 0.0) } catch (_: Exception) { }
            return
        }
        if (nowWall - confirm.queuedAtWallMs >= CONFIRM_TIMEOUT_MS) {
            synchronized(measureLock) {
                if (pendingConfirm === confirm) pendingConfirm = null
            }
            val playWall = confirm.predictedPlayWallMs ?: nowWall
            val pipeline = (playWall - confirm.dueWallMs).coerceAtLeast(0L)
            Log.i(TAG, "burst seq=%d arrival=%d hold=%d due=%d backlog=%d play=%d pipeline=%d est=%b (timeout)"
                .format(confirm.seq, confirm.arrivalWallMs, confirm.holdMs,
                    confirm.dueWallMs, confirm.backlogMs, playWall, pipeline, true))
            try { onBurstPlayed(confirm.seq, playWall, pipeline, true, confirm.backlogMs, measuredSpeed ?: 0.0) } catch (_: Exception) { }
        }
    }

    /** Stop the track + thread, keep the hold queue (mode switch). */
    private fun releaseTrack() {
        running = false
        playerThread?.interrupt()
        try { playerThread?.join(500) } catch (_: Exception) { }
        playerThread = null
        // v1.0.19 — a rebuilt track starts at 1.0x: reset the
        // applied speed so the loop re-applies the pending speed
        // to the new track (otherwise applyPendingSpeed sees
        // pending == current and never sets it on the new track).
        currentSpeed = 1.0
        // v1.0.17 — a rebuilt track starts a new frame stream: any
        // pending play measurement refers to the dead track's frames
        // and must not report against the new one.
        synchronized(measureLock) {
            pendingConfirm = null
            pendingBurstNote = null
            burstStartQueue.clear()
            totalFramesWritten = 0L
        }
        try { loudnessEnhancer?.release() } catch (_: Exception) { }
        loudnessEnhancer = null
        try { audioTrack?.stop() } catch (_: Exception) { }
        try { audioTrack?.release() } catch (_: Exception) { }
        audioTrack = null
    }

    /**
     * v1.0.11 — switch the output stream live (tap -> capture
     * fallback: MEDIA -> VOICE_COMMUNICATION). No-op when the usage
     * is unchanged or the track was never started (the usage is
     * applied at the next start() instead). The hold queue and the
     * current hold survive the rebuild; only the AudioTrack is swapped.
     */
    fun setOutputUsage(usage: Int) {
        if (usage == outputUsage) return
        outputUsage = usage
        if (audioTrack == null && !running) return
        try {
            releaseTrack()
            buildTrack()
        } catch (e: Exception) {
            Log.w(TAG, "setOutputUsage rebuild failed", e)
        }
    }

    /** v1.0.6 — track gain is always max (1.0f); the parameter is kept
     * for callers but ignored — loudness now comes from the SYSTEM
     * volume of the stream the voice is on (applyCallVolume /
     * applyMediaVolume), not from scaling the track. */
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
     * current burst hold — it plays at arrival + hold, not at once.
     * v1.0.17 — if the service noted a burst start before this
     * chunk, the note attaches to it (its enqueue index): the
     * player thread will measure this chunk's real play start. */
    fun playAudio(pcm24k: ByteArray) {
        if (!running) return
        synchronized(measureLock) {
            pendingBurstNote?.let { note ->
                burstStartQueue.addLast(
                    note.copy(chunkIndex = chunksAdded)
                )
                pendingBurstNote = null
            }
            chunksAdded++
        }
        holdQueue.add(System.currentTimeMillis(), pcm24k, currentHoldMs)
    }

    /**
     * v1.0.17 — the service calls this BEFORE the first [playAudio]
     * of a burst (burst seq from the tracker, arrival wall + the
     * hold this burst was given). The next chunk enqueued carries
     * the note; the player thread measures when that chunk's first
     * frame actually sounds and reports it via [onBurstPlayed].
     */
    fun noteBurstStart(seq: Long, arrivalWallMs: Long, holdMs: Long) {
        synchronized(measureLock) {
            pendingBurstNote = BurstStartInfo(
                seq = seq,
                arrivalWallMs = arrivalWallMs,
                holdMs = holdMs,
                chunkIndex = -1L
            )
        }
    }

    /**
     * v1.0.19 — TIME-STRETCH catch-up. The service pushes the
     * tracker's target speed (1.0–1.25x) here; the player loop
     * applies it via [applyPendingSpeed]. setPlaybackParams is
     * Sonic inside the framework (faster, same pitch). If the
     * device does not support it, the call throws: the player
     * then stays at 1.0x forever ([stretchSupported] = false) and
     * the tracker falls back to dropping late words. Never throws.
     *
     * v1.0.23 — trần 1,25× (was 1,5×: user 12:11 "Giọng bị méo
     * khi bắt kịp"). Khi stretch không được hỗ trợ thì KHÔNG
     * return im lặng nữa: log cảnh báo một lần/track (service
     * đọc [isStretchSupported] để hiện lên HUD thay vì để HUD
     * in target 1,25× trong khi giọng chạy 1,0×).
     */
    fun setPlaybackSpeed(speed: Double) {
        if (!stretchSupported) {
            if (!stretchUnsupportedLogged) {
                stretchUnsupportedLogged = true
                Log.w(TAG, "setPlaybackSpeed(%.2f) ignored: stretch unsupported on this device, voice runs at 1.0x (tracker target is NOT the applied speed)"
                    .format(speed))
            }
            return
        }
        pendingSpeed = speed.coerceIn(1.0, 1.25)
    }

    /** Current applied stretch speed (1.0 when unsupported). */
    val playbackSpeed: Double get() = currentSpeed

    /** True when this device supports setPlaybackParams. */
    val isStretchSupported: Boolean get() = stretchSupported

    /** v1.0.19 — apply [pendingSpeed] to the live track (player
     * thread only). A rebuild resets the speed to 1.0: re-apply
     * the pending value after a rebuild too (buildTrack calls
     * this once via the loop's first tick).
     *
     * v1.0.23 — READBACK: sau setSpeed thành công, đọc lại
     * track.playbackParams.speed và lưu vào [appliedSpeedReadback];
     * lệch >0,02 thì log cảnh báo + [appliedSpeedMismatch]. Đây
     * là nửa đầu của thiết bị đo tốc độ thật (nửa sau là độ dốc
     * playhead trong [samplePlayheadForSpeed]). */
    private fun applyPendingSpeed() {
        val want = pendingSpeed
        if (want == currentSpeed) return
        val track = audioTrack ?: return
        if (!stretchSupported) {
            pendingSpeed = 1.0
            currentSpeed = 1.0
            return
        }
        try {
            track.playbackParams = PlaybackParams().setSpeed(want.toFloat())
            currentSpeed = want
            val readback = try {
                track.playbackParams.speed.toDouble()
            } catch (_: Exception) {
                want
            }
            appliedSpeedReadback = readback
            appliedSpeedMismatch = kotlin.math.abs(readback - want) > 0.02
            if (appliedSpeedMismatch) {
                Log.w(TAG, "playback speed -> target %.2f (readback %.2f) MISMATCH — track runs slower than asked".format(want, readback))
            } else {
                Log.i(TAG, "playback speed -> %.2f (readback %.2f)".format(want, readback))
            }
        } catch (e: Exception) {
            Log.w(TAG, "setPlaybackParams(%.2f) unsupported on this device, staying at 1.0x".format(want), e)
            stretchSupported = false
            stretchUnsupportedLogged = true
            appliedSpeedReadback = 1.0
            appliedSpeedMismatch = false
            pendingSpeed = 1.0
            currentSpeed = 1.0
            try {
                track.playbackParams = PlaybackParams().setSpeed(1.0f)
            } catch (_: Exception) { }
        }
    }

    /**
     * v1.0.23 — TỐC ĐỘ THẬT BẰNG ĐỘ DỐC PLAYHEAD (báo cáo khả thi
     * §5.2): lấy mẫu readPlayhead() mỗi vòng lặp player (throttle
     * 50 ms), giữ cửa sổ 2 s; tốc độ thật =
     * (dFrames/dWall)/24000. framePosition đếm frame ĐẦU RA sau
     * stretch nên độ dốc chính là tốc độ áp dụng thật — không phụ
     * thuộc công thức ánh xạ input/output như confirmPlayStart.
     * Khi track đứng yên (không phát) thì dFrames = 0 → null.
     * Chạy cả khi speed != 1.0 (khác confirmPlayStart, vốn bỏ poll
     * khi stretch bật vì sợ check firstFrame fire sớm).
     */
    private fun samplePlayheadForSpeed() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSpeedSampleAtMs < 50L) return
        lastSpeedSampleAtMs = nowMs
        val head = try { readPlayhead() } catch (_: Exception) { null } ?: return
        val framePos = head.first
        val wallMs = head.second
        synchronized(speedSamples) {
            speedSamples.addLast(wallMs to framePos)
            while (speedSamples.isNotEmpty() &&
                wallMs - speedSamples.first().first > 2000L
            ) {
                speedSamples.removeFirst()
            }
            while (speedSamples.size > 60) speedSamples.removeFirst()
            val first = speedSamples.firstOrNull()
            val last = speedSamples.lastOrNull()
            if (first != null && last != null) {
                val dWall = last.first - first.first
                val dFrames = last.second - first.second
                measuredSpeedValue = if (dWall >= 500L && dFrames > 0L) {
                    ((dFrames / 24000.0) / (dWall / 1000.0))
                        .coerceIn(0.5, 2.0)
                } else {
                    null
                }
            }
        }
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
                    // v1.0.11 — same output as the Live track: the
                    // current session usage (media in tap modes,
                    // call stream in capture mode).
                    .setUsage(outputUsage)
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
        releaseTrack()
        holdQueue.clear()
        try { tts?.stop() } catch (_: Exception) { }
    }

    fun release() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
    }
}
