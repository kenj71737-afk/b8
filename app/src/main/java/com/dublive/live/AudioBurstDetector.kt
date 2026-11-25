package com.dublive.live

/**
 * v1.0.9 — port of ALDA FIX33/FIX43's AudioBurstDetector
 * (core/audio/AudioBurstDetector.kt). Splits the stream of
 * translated-audio chunks into bursts: a chunk arriving after [gapMs]
 * or more of silence starts a new burst (the first chunk counts as a
 * burst start).
 *
 * FIX43 uses a 500 ms gap (was the 1000 ms default): in phrase mode
 * the latency sampler only runs at burst starts, and rapid dialogue
 * (Cantonese film) has < 1 s pauses between phrases, so no burst ever
 * started and the auto delay stayed frozen at its seed. 500 ms
 * catches those pauses; the EMA (30%/sample) + 500 ms deadband still
 * smooth out any noisy sample from a mid-phrase pause.
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class AudioBurstDetector(private val gapMs: Long = 500L) {
    private var lastChunkAtMs: Long = -1L

    /**
     * Feed one audio chunk arrival. Returns true when this chunk starts
     * a new burst (first chunk, or [gapMs]+ since the previous chunk).
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
