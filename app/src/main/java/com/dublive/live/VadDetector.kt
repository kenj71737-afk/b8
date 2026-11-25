package com.dublive.live

import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector for 16 kHz mono PCM.
 *
 * - Speech counts only after >= 150 ms of continuous energy above threshold.
 * - Keeps a ~500 ms pre-roll ring buffer so the start of a sentence is not cut.
 * - An utterance ends after ~700 ms of continuous silence.
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
    private val speechFramesNeeded = 8 // 8 x 20 ms = 160 ms >= 150 ms
    private val silenceFramesToEnd = 35 // 35 x 20 ms = 700 ms

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
            // START: needs energy above the start threshold AND, when
            // the gate is on, a speech-like spectrum. A loud music
            // frame fails the gate, resets the counter and goes to
            // pre-roll like any quiet frame — it never opens an
            // utterance. The gate runs only on energetic frames.
            val energetic = frameRms > startThreshold
            val speechLike = !energetic || !spectralGateEnabled ||
                spectralGate.isSpeechLike(frame)
            if (energetic && speechLike) {
                loudFrames++
                pendingHead.addAll(frame.toList())
                if (loudFrames >= speechFramesNeeded) {
                    inSpeech = true
                    quietFrames = 0
                    val head = ShortArray(preRoll.size + pendingHead.size)
                    var idx = 0
                    for (s in preRoll) head[idx++] = s
                    for (s in pendingHead) head[idx++] = s
                    pendingHead.clear()
                    preRoll.clear()
                    listener.onSpeechStart(head)
                }
            } else {
                loudFrames = 0
                pendingHead.clear()
                for (s in frame) pushPreRoll(s)
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
            listener.onSpeechChunk(frame)
            if (quietFrames >= silenceFramesToEnd) {
                inSpeech = false
                loudFrames = 0
                quietFrames = 0
                listener.onSpeechEnd()
            }
        }
    }

    fun reset() {
        inSpeech = false
        loudFrames = 0
        quietFrames = 0
        pendingHead.clear()
        preRoll.clear()
    }
}
