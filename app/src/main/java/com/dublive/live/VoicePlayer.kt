package com.dublive.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.SystemClock
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
     */
    private val onBurstPlayed: (Long, Long, Long, Boolean, Long) -> Unit = { _, _, _, _, _ -> }
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
                    // v1.0.17 — even with no chunk due, poll the
                    // playhead: a burst-start confirmation may be
                    // waiting for its first frame to render.
                    confirmPlayStart()
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
     * wall time the timestamp was taken). AudioTimestamp.nanoTime
     * shares the CLOCK_MONOTONIC base with elapsedRealtimeNanos,
     * so the wall time is currentTimeMillis shifted by the
     * nanoTime/elapsedRealtime offset. Null when the track cannot
     * report (not playing yet, OEM build without the path).
     */
    private fun readPlayhead(): Pair<Long, Long>? {
        val track = audioTrack ?: return null
        return try {
            val ts = AudioTimestamp()
            if (!track.getTimestamp(ts)) return null
            if (ts.framePosition < 0L) return null
            val wallNow = System.currentTimeMillis()
            val tsWall = wallNow +
                (ts.nanoTime - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
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
        val (predicted, estimated) = try {
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
                    tsWall + ((firstFrame - pos) * 1000L) / 24000L to false
                }
            }
        } catch (_: Exception) {
            (dueWall + FALLBACK_HW_MS) to true
        }
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
            try { onBurstPlayed(confirm.seq, nowWall, pipeline, false, confirm.backlogMs) } catch (_: Exception) { }
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
            try { onBurstPlayed(confirm.seq, playWall, pipeline, true, confirm.backlogMs) } catch (_: Exception) { }
        }
    }

    /** Stop the track + thread, keep the hold queue (mode switch). */
    private fun releaseTrack() {
        running = false
        playerThread?.interrupt()
        try { playerThread?.join(500) } catch (_: Exception) { }
        playerThread = null
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
