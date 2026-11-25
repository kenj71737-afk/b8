package com.alad.app.core.audio

/**
 * FIX30: VAD send gate — only forwards captured audio to Gemini once real
 * speech has been observed (at least 150 ms above the speech RMS
 * threshold). Background music, sound effects and room noise never reach
 * the model, so it cannot invent translations for them.
 *
 * Mirrors the v1.7 gate in the Gemini web userscript. The speech span is
 * measured as lastSpeech - utteranceStart (not the utterance's age), so a
 * short transient such as a door slam — only 1-2 frames above the
 * threshold — never opens the gate. The first frames of a sentence are
 * held in a pre-roll buffer and flushed when the span reaches 150 ms, so
 * the start of a sentence is never cut; if the utterance closes first,
 * the pre-roll is dropped.
 *
 * Pure Kotlin, no Android dependencies: the caller passes a monotonic
 * timestamp (SystemClock.elapsedRealtime()), so the logic is unit-testable.
 * All state access is synchronized; the gate is fed from the audio
 * capture threads.
 */
class SpeechSendGate(
    private val sampleRateHz: Int = 16000,
    private val speechRms: Float = 600f / 32768f,
    private val silenceToEndMs: Long = 450L,
    private val minSpeechSpanMs: Long = 150L,
    private val maxPreRollBytes: Int = 16000, // ~500 ms at 16 kHz 16-bit mono
    // FIX38: a pause shorter than this still belongs to the same sentence
    // (a clause break); a longer pause starts a new sentence. Whole-
    // sentence mode translates per sentence, so its latency must be
    // measured from the sentence start, not the last clause's start.
    private val sentenceGapMs: Long = 1500L
) {
    private var utteranceStartMs: Long = -1L
    private var lastSpeechMs: Long = -1L
    // FIX32: start of the most recent utterance (kept after it closes, so
    // the service can measure per-turn translation latency: first
    // translated audio of a turn minus the utterance's start).
    @Volatile private var lastUtteranceStartMs: Long = -1L
    // FIX38: start of the most recent sentence (utterances separated by
    // less than sentenceGapMs belong to the same sentence). Whole-sentence
    // mode measures its latency from here: the model waits for the full
    // sentence, so measuring from the last clause would understate the
    // latency and leave the picture under-delayed.
    @Volatile private var lastSentenceStartMs: Long = -1L
    // FIX34: time of the most recent speech frame (kept after the utterance
    // closes, so the watchdog can tell a quiet scene from a dead key, and
    // can wait for a quiet moment before rotating keys).
    @Volatile private var lastSpeechAtMs: Long = -1L
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes: Int = 0
    private var sending: Boolean = false

    // FIX44: spectral music filter (ported from DubLive v1.0.8). When
    // enabled, a loud block may only open / advance an utterance while
    // it has not started sending yet if its spectrum looks like speech
    // (voice-band energy + peaky spectrum). Loud background music fails
    // the gate and is dropped here, so it never opens an utterance or
    // reaches Gemini. Once the utterance is sending, the gate is not
    // checked — music under dialogue must never cut a sentence.
    private val spectralGate = SpectralGate()
    @Volatile var spectralGateEnabled: Boolean = true
        private set

    fun setSpectralGateEnabled(enabled: Boolean) {
        spectralGateEnabled = enabled
    }

    /** First 20 ms (320 samples) of a PCM block, as shorts, for the
     * spectral gate. Shorter blocks are used whole (the gate zero-pads). */
    private fun firstFrameSamples(pcm: ByteArray): ShortArray {
        val samples = minOf(pcm.size / 2, 320)
        val out = ShortArray(samples)
        var i = 0
        while (i < samples) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            out[i] = ((high shl 8) or low).toShort()
            i++
        }
        return out
    }

    /**
     * Feed one PCM block (16-bit mono at [sampleRateHz]). Returns the
     * blocks to send to Gemini now (pre-roll flushed + this block), or
     * an empty list when the block is held or dropped.
     */
    @Synchronized
    fun feed(pcm: ByteArray, nowMs: Long): List<ByteArray> {
        val rms = rmsOf(pcm)
        // FIX44: spectral gate — a loud block that does not look like
        // speech (music / noise) is dropped while the utterance has not
        // started sending yet. It does not open or advance an utterance
        // and does not enter the pre-roll, so no music is ever sent. The
        // utterance stays open on its earlier passing blocks; if no more
        // speech comes, the 450 ms silence timeout closes it and drops
        // the pre-roll. A single speech frame that fails the gate only
        // delays the utterance start by one block.
        if (rms >= speechRms && spectralGateEnabled && !sending) {
            if (!spectralGate.isSpeechLike(firstFrameSamples(pcm))) {
                return emptyList()
            }
        }
        if (rms >= speechRms) {
            if (utteranceStartMs < 0) {
                utteranceStartMs = nowMs
                lastUtteranceStartMs = nowMs
                // FIX38: a new sentence starts only after a pause of at
                // least sentenceGapMs; shorter pauses are clause breaks
                // inside the same sentence. lastSpeechAtMs still holds
                // the previous utterance's last speech frame here (it is
                // updated below), so the gap is exact.
                if (lastSentenceStartMs < 0 || lastSpeechAtMs < 0 ||
                    nowMs - lastSpeechAtMs >= sentenceGapMs
                ) {
                    lastSentenceStartMs = nowMs
                }
            }
            lastSpeechMs = nowMs
            lastSpeechAtMs = nowMs
        } else if (utteranceStartMs >= 0 && lastSpeechMs >= 0 &&
            nowMs - lastSpeechMs >= silenceToEndMs
        ) {
            utteranceStartMs = -1L
            lastSpeechMs = -1L
        }
        if (utteranceStartMs < 0) {
            // No utterance open: drop any buffered transient, close the gate.
            preRoll.clear()
            preRollBytes = 0
            sending = false
            return emptyList()
        }
        val speechSpan = lastSpeechMs - utteranceStartMs
        if (sending || speechSpan >= minSpeechSpanMs) {
            val out = ArrayList<ByteArray>(preRoll.size + 1)
            out.addAll(preRoll)
            preRoll.clear()
            preRollBytes = 0
            sending = true
            out.add(pcm)
            return out
        }
        preRoll.addLast(pcm)
        preRollBytes += pcm.size
        while (preRollBytes > maxPreRollBytes && preRoll.isNotEmpty()) {
            preRollBytes -= preRoll.removeFirst().size
        }
        return emptyList()
    }

    @Synchronized
    fun reset() {
        utteranceStartMs = -1L
        lastSpeechMs = -1L
        lastUtteranceStartMs = -1L
        lastSentenceStartMs = -1L
        lastSpeechAtMs = -1L
        preRoll.clear()
        preRollBytes = 0
        sending = false
    }

    /** Start of the most recent utterance, or -1 if none has been seen. */
    fun lastUtteranceStartMs(): Long = lastUtteranceStartMs

    /** Start of the most recent sentence, or -1 if none has been seen. */
    fun lastSentenceStartMs(): Long = lastSentenceStartMs

    /** Time of the most recent speech frame, or -1 if none has been seen. */
    fun lastSpeechAtMs(): Long = lastSpeechAtMs

    /** RMS over every 4th sample — plenty for a speech/noise decision. */
    private fun rmsOf(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val f = sample / 32768f
            sum += (f * f).toDouble()
            count++
            i += 8
        }
        if (count == 0) return 0f
        return kotlin.math.sqrt(sum / count).toFloat()
    }
}
