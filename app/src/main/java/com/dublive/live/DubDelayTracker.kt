package com.dublive.live

import kotlin.math.max
import kotlin.math.min

/**
 * Measures the real dub delay and adapts the video delay to it.
 *
 * LESSON FROM THE OLD APP (FIX43): delay stood still at 5s238ms because
 * samples were only taken at the start of a "speech burst" (audio arriving
 * after >= 1 s of silence). Fast Cantonese dialogue has no such gaps, so
 * there was never a burst start and never a sample.
 *
 * v1.0.7 — FIFO PAIRING (replaces the one-sample-per-utterance design).
 * Why the old designs both failed on continuous dialogue:
 *  - v1.0.4 sampled every 2 s WHILE the dub was speaking: with no 700 ms
 *    silence the utterance never ended, so the sample grew with the
 *    utterance LENGTH (not the latency) and the delay ballooned to 19,4 s.
 *  - v1.0.5/v1.0.6 took exactly ONE sample per utterance (dubSpokeThisTurn
 *    flag, reset only on VAD speech end): continuous Cantonese dialogue is
 *    effectively ONE utterance lasting minutes, so there was exactly one
 *    sample at the start of the session and the delay then stood FROZEN
 *    forever — user report 20:12 "Độ trễ hình đứng nguyên từ lúc vào".
 *
 * How FIFO pairing works:
 *  - The service pushes a burst-start timestamp into a FIFO queue on every
 *    VAD speech start, and the segment cutter pushes one more every 4 s
 *    while speech runs on without a 700 ms silence (so a 3-minute
 *    monologue becomes ~45 segments, not one).
 *  - Every time a new dubbed-voice segment starts (a dub audio chunk or
 *    TTS start arriving >= 800 ms after the previous dub audio), the
 *    OLDEST unpaired burst marker is popped and sample = dubStart -
 *    burstStart — the real per-sentence latency, never the utterance
 *    length. Samples are EMA-smoothed (alpha 0.5) and clamped to
 *    [3 s, maxDelay]. While no translation has ever arrived the delay
 *    stays at the seed (Settings "Trễ hình cơ bản", default 5 s).
 *  - If a sample falls outside [500 ms, 20 s] the pairing has drifted
 *    (a segment was filtered or lost) — the whole queue is cleared so
 *    the NEXT pair resyncs instead of pairing wrongly forever.
 * All methods are @Synchronized: burst markers come from the VAD/capture
 * thread, dub chunks from the Live socket thread and the TTS listener.
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    initialDelayMs: Long,
    private val onDelayChanged: (Long) -> Unit
) {
    /** FIFO of speech-burst start timestamps (ms), oldest first. */
    private val burstStarts = ArrayDeque<Long>()
    /** Last time any dubbed audio arrived (Live chunk or TTS start). */
    @Volatile private var lastDubAtMs = 0L
    private var emaMs: Long = initialDelayMs.coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)

    val currentDelayMs: Long get() = emaMs

    /** A new speech burst started (VAD speech start, or the 4 s forced
     * segment cut inside a long continuous utterance). */
    @Synchronized
    fun noteBurstStart(now: Long = System.currentTimeMillis()) {
        burstStarts.addLast(now)
        while (burstStarts.size > 32) burstStarts.removeFirst()
    }

    /** A dubbed-audio chunk (or TTS start) arrived. A chunk that comes
     * >= 800 ms after the previous dub audio starts a NEW dubbed segment:
     * pair it with the oldest unpaired burst marker. Chunks inside an
     * ongoing segment only refresh the timestamp. */
    @Synchronized
    fun noteDubChunk(now: Long = System.currentTimeMillis()) {
        if (now - lastDubAtMs >= 800L) {
            val start = burstStarts.removeFirstOrNull()
            if (start != null) {
                val sample = now - start
                if (sample in 500L..20_000L) {
                    addSample(sample)
                } else {
                    // Pairing drifted (a burst was filtered/lost, or a
                    // stale pre-connect marker survived): drop the whole
                    // queue so the next pair resyncs cleanly.
                    burstStarts.clear()
                }
            }
        }
        lastDubAtMs = now
    }

    /** Manual nudge from the "Trễ −1s / +1s" buttons: shift the EMA by
     * the delta and push it out at once. Later automatic samples keep
     * EMA-ing from this manual value — they never pull it back. */
    @Synchronized
    fun adjustManual(deltaMs: Long) {
        emaMs = (emaMs + deltaMs).coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
        onDelayChanged(emaMs)
    }

    @Synchronized
    fun stop() {
        burstStarts.clear()
        lastDubAtMs = 0L
    }

    private fun addSample(sampleMs: Long) {
        val clamped = min(max(sampleMs, Prefs.MIN_DELAY_MS), maxDelayMs)
        emaMs = (0.5 * emaMs + 0.5 * clamped).toLong()
        onDelayChanged(emaMs)
    }
}
