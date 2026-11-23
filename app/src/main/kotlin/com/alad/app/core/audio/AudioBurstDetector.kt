package com.alad.app.core.audio

/**
 * FIX33: splits the stream of translated-audio chunks into bursts. A chunk
 * that arrives after [gapMs] or more of silence starts a new burst (the
 * first chunk counts as a burst start).
 *
 * FIX32 sampled the translation latency on the first audio chunk of each
 * model turn, armed by the turn-complete signal — but the translate model
 * does not send turn-complete (on the user's phone the auto delay stayed
 * frozen at its 12.901 s seed for 24 minutes), so no sample ever ran.
 * Bursts are model-independent: the model generates audio continuously
 * while translating a phrase and pauses between phrases, so a burst start
 * is a turn boundary the app can see without any server signal.
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class AudioBurstDetector(private val gapMs: Long = 1000L) {
    private var lastChunkAtMs: Long = -1L

    /**
     * Feed one audio chunk arrival. Returns true when this chunk starts a
     * new burst (first chunk, or [gapMs]+ since the previous chunk).
     */
    fun onChunk(nowMs: Long): Boolean {
        val isBurstStart = lastChunkAtMs < 0L || nowMs - lastChunkAtMs >= gapMs
        lastChunkAtMs = nowMs
        return isBurstStart
    }

    fun reset() {
        lastChunkAtMs = -1L
    }
}
