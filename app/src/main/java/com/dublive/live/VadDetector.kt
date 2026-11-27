package com.dublive.live

import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector for 16 kHz mono PCM.
 *
 * - Speech counts only after >= 80 ms of energy above threshold
 *   (v1.0.20: 4 frames, was 8 = 160 ms; tolerance 2 frames).
 * - Keeps a ~500 ms pre-roll ring buffer so the start of a sentence is not cut.
 * - An utterance ends after ~700 ms of continuous silence, or
 *   300 ms of non-speech-like audio below the start threshold
 *   (v1.0.20: the below-START rule is spectral-gated, so quiet
 *   speech does not end a sentence).
 * This is a fresh implementation written for DubLive.
 *
 * v1.0.8 — HYSTERESIS (two thresholds) + spectral gate. The old code
 * used ONE threshold for both starting and ending an utterance, which
 * is why the user report 20:37 ("chỉnh độ nhạy nghe tiếng thì giọng
 * dịch bị nghẹn, nói không liền câu") happened: with the threshold
 * dragged up to 800–1000, quiet syllables inside a sentence fell
 * below it, the VAD counted them as silence, ended the utterance
 * after 700 ms, and the audio sent to Live came out full of holes —
 * the model translated a choppy stream and the dub voice stuttered.
 * Now the START threshold is the user's setting (default 600) and
 * the END threshold is 40% of it (e.g. 240): inside an utterance
 * only frames below the LOW threshold count as silence, and frames
 * between the two thresholds are still speech and still go to Live.
 * On top of that, a frame may only START an utterance if it also
 * passes the SpectralGate (voice-band energy + non-flat spectrum),
 * so loud background music cannot open an utterance at a low
 * threshold — the energy threshold alone cannot tell loud music
 * from loud speech (the 20:40 report). The gate is NOT checked
 * inside an utterance, so music under dialogue never cuts a sentence.
 */
class VadDetector(
    private val sampleRate: Int = 16000,
    /** v1.0.8: START threshold (Settings "Độ nhạy nghe tiếng",
     * default 600, ALDA's value). Live-updatable via [updateThreshold]. */
    @Volatile var startThreshold: Double = 600.0,
    private val listener: Listener
) {
    interface Listener {
        /** A new utterance started; [preRollAndHead] contains pre-roll + first audio. */
        fun onSpeechStart(preRollAndHead: ShortArray)
        fun onSpeechChunk(samples: ShortArray)
        fun onSpeechEnd()
    }

    private val frameSamples = sampleRate / 50 // 20 ms frames
    /**
     * v1.0.20 — START need 4 frames (was 8). The v1.0.19 device
     * test (28/11, "giọng đứng, lúc dịch lúc không"): quiet
     * drama dialogue (RMS 300–550) rarely holds 8 consecutive
     * loud frames, so turns almost never started and Live got
     * loud fragments only. The sim grid (grid1020.py, calibrated
     * world): need 8/tol 0 sends 24% of speech, need 4/tol 2
     * sends 95%, need 4/tol 2 + warm 3 sends 97%. The START
     * threshold itself is NOT lowered (music at 250–650 would
     * false-start); only the frame count + tolerance change.
     */
    private val speechFramesNeeded = 4 // 4 x 20 ms = 80 ms
    /** v1.0.20 — START tolerance: up to 2 consecutive
     * non-passing frames (a quiet syllable / a gate-failing
     * frame) inside the start window do not reset the counter.
     * The single most important VAD change (tol 0 -> 2 lifts
     * sent speech 24% -> 75% at need 8). */
    private val startToleranceFrames = 2
    private var startFailStreak = 0
    /**
     * v1.0.20 — WARM / SOFT START. Dialogue comes in bursts:
     * for 5 s after a turn ends, a new line needs only 3 frames
     * ([warmStartFramesNeeded]) and they only need to clear the
     * END threshold ([warmThreshold], 240) — an ALL-SOFT line
     * (no frame above START) can still open a turn while
     * dialogue is ongoing. Music false-starts need 3
     * consecutive gate-passing frames above END (p ~ 0.2% per
     * triple) and the gated below-START rule kills a music turn
     * after 300 ms anyway. Applies only to the start rule; the
     * cold start (no recent turn) keeps need 4 @ START.
     */
    private val warmStartFramesNeeded = 3
    private val warmWindowMs = 5000L
    private var lastTurnEndMs = 0L
    private val silenceFramesToEnd = 35 // 35 x 20 ms = 700 ms
    /**
     * v1.0.19 — BELOW-START counter. The 700 ms below-END rule
     * alone never ends a turn while background music plays:
     * music is RMS 300–800, above the END threshold (240) so it
     * resets quietFrames, but below the START threshold (600) so
     * it is not speech. The turn stays open, the marker ages,
     * and every burst is rejected as no-marker (device 28/11:
     * no-mk 23/31, pairs 0/60 s). End the turn after 15 frames
     * (300 ms) below the START threshold as well.
     * v1.0.20 — GATED: the v1.0.19 rule counted EVERY frame
     * below START, including quiet speech (RMS 300–550 spends
     * most frames below START), so it cut sentences ~300 ms
     * after they started (the "giọng đứng" mechanism: a turn
     * ends, re-start needs 8 loud frames + gate, the audio
     * between is lost). Now only a frame that is NOT
     * speech-like advances the counter: quiet speech (gate
     * pass) resets it, music (gate fail) advances it. The gate
     * is evaluated only on frames between END and START (frames
     * below END are silence and advance it directly; frames at
     * or above START reset it) so the FFT cost is ~50/s max.
     */
    private val belowStartFramesToEnd = 15 // 15 x 20 ms = 300 ms

    /** v1.0.20 — diagnostics counters (read by the service's
     * 1 s ticker for the /60 s rates). [turnsStarted] counts
     * every turn the VAD opens; [gateRejectedFrames] counts
     * energetic frames that failed the spectral gate while
     * starting a turn (the sim's gate_rejects). */
    @Volatile var turnsStarted = 0
        private set
    @Volatile var gateRejectedFrames = 0
        private set

    /** RMS of the most recent 20 ms frame (0 when silent). Read by the
     * service's level ticker so the UI can show a live input-level meter. */
    @Volatile var lastRms: Double = 0.0
        private set

    /**
     * v1.0.8 — hysteresis END threshold = 40% of the start threshold.
     * Inside an utterance, only frames at or below this count toward
     * the 700 ms silence that ends it. Updated together with the start
     * threshold by [updateThreshold].
     */
    @Volatile var endThreshold: Double = startThreshold * 0.4
        private set

    /** v1.0.8 — spectral gate on/off (Settings, default ON). When off,
     * starting an utterance needs energy only, exactly like v1.0.7. */
    @Volatile var spectralGateEnabled: Boolean = true

    private val spectralGate = SpectralGate()

    private val preRoll = ArrayDeque<Short>()
    private val preRollMax = sampleRate / 2 // 500 ms
    private var inSpeech = false
    private var loudFrames = 0
    private var quietFrames = 0
    /** v1.0.19 — consecutive frames below the START threshold
     * inside an utterance (music / pause). */
    private var belowStartFrames = 0
    private var pendingHead = mutableListOf<Short>()

    /** v1.0.8 — live-update from Settings: the service's watchdog
     * re-reads Prefs every 10 s, so dragging the slider mid-session
     * takes effect without restarting the dub. */
    fun updateThreshold(newStart: Double) {
        startThreshold = newStart.coerceIn(250.0, 900.0)
        endThreshold = startThreshold * 0.4
    }

    /** v1.0.8 — live-update of the spectral-gate toggle. */
    fun updateSpectralGate(enabled: Boolean) {
        spectralGateEnabled = enabled
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size)
    }

    /** Feed raw PCM. May be called with any chunk size; internally framed at 20 ms. */
    fun feed(pcm: ShortArray, length: Int) {
        var offset = 0
        while (offset + frameSamples <= length) {
            val frame = pcm.copyOfRange(offset, offset + frameSamples)
            offset += frameSamples
            processFrame(frame)
        }
        if (offset < length) {
            // Keep the tail in the pre-roll so no audio is lost between calls.
            for (i in offset until length) pushPreRoll(pcm[i])
        }
    }

    private fun pushPreRoll(s: Short) {
        preRoll.addLast(s)
        while (preRoll.size > preRollMax) preRoll.removeFirst()
    }

    private fun processFrame(frame: ShortArray) {
        val frameRms = rms(frame)
        lastRms = frameRms
        if (!inSpeech) {
            // START (v1.0.20): need [speechFramesNeeded] (4)
            // frames, with up to [startToleranceFrames] (2)
            // consecutive non-passing frames tolerated inside the
            // window. WARM/SOFT START: for 5 s after a turn ends
            // (dialogue is ongoing) only 3 frames are needed and
            // they only need to clear the END threshold (240), so
            // an all-soft line can still open a turn. A loud music
            // frame fails the gate, counts as a non-passing frame
            // (tolerated, not a reset) and goes to pre-roll like
            // any quiet frame — it never opens an utterance on its
            // own. The gate runs only on energetic frames.
            val nowMs = System.currentTimeMillis()
            val warmActive = lastTurnEndMs > 0L &&
                nowMs - lastTurnEndMs < warmWindowMs
            val needNow = if (warmActive) warmStartFramesNeeded
                else speechFramesNeeded
            val thrNow = if (warmActive) endThreshold
                else startThreshold
            val energetic = frameRms > thrNow
            val speechLike = !energetic || !spectralGateEnabled ||
                spectralGate.isSpeechLike(frame)
            if (energetic && speechLike) {
                startFailStreak = 0
                loudFrames++
                pendingHead.addAll(frame.toList())
                if (loudFrames >= needNow) {
                    inSpeech = true
                    quietFrames = 0
                    startFailStreak = 0
                    turnsStarted++
                    val head = ShortArray(preRoll.size + pendingHead.size)
                    var idx = 0
                    for (s in preRoll) head[idx++] = s
                    for (s in pendingHead) head[idx++] = s
                    pendingHead.clear()
                    preRoll.clear()
                    listener.onSpeechStart(head)
                }
            } else {
                if (energetic) gateRejectedFrames++
                startFailStreak++
                for (s in frame) pushPreRoll(s)
                if (startFailStreak > startToleranceFrames) {
                    loudFrames = 0
                    pendingHead.clear()
                    startFailStreak = 0
                }
            }
        } else {
            // INSIDE an utterance: hysteresis — only frames at or below
            // the LOW end threshold count as silence. A syllable dip
            // below the start threshold but above the end threshold is
            // still speech: it is forwarded (onSpeechChunk) and does not
            // advance the 700 ms silence counter, so the sentence is
            // never cut mid-way and the audio sent to Live has no holes.
            if (frameRms > endThreshold) {
                quietFrames = 0
            } else {
                quietFrames++
            }
            // v1.0.20 — GATED below-START counter (see the field
            // comment): only a frame that is NOT speech-like
            // advances it. Quiet speech (between END and START,
            // gate pass) resets it; music (gate fail) advances
            // it; silence (below END) advances it directly; loud
            // speech (at/above START) resets it. The gate is
            // evaluated only on the in-between frames, so the
            // FFT cost is ~50/s max.
            if (frameRms >= startThreshold) {
                belowStartFrames = 0
            } else if (frameRms <= endThreshold) {
                belowStartFrames++
            } else {
                val speechLike = !spectralGateEnabled ||
                    spectralGate.isSpeechLike(frame)
                if (speechLike) belowStartFrames = 0
                else belowStartFrames++
            }
            listener.onSpeechChunk(frame)
            if (quietFrames >= silenceFramesToEnd ||
                belowStartFrames >= belowStartFramesToEnd) {
                inSpeech = false
                loudFrames = 0
                quietFrames = 0
                belowStartFrames = 0
                startFailStreak = 0
                lastTurnEndMs = System.currentTimeMillis()
                listener.onSpeechEnd()
            }
        }
    }

    fun reset() {
        inSpeech = false
        loudFrames = 0
        quietFrames = 0
        belowStartFrames = 0
        startFailStreak = 0
        lastTurnEndMs = 0L
        pendingHead.clear()
        preRoll.clear()
    }
}
