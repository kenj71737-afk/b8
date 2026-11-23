package com.alad.app.core.audio

/**
 * FIX39: times source sentences from the live input transcription.
 *
 * In whole-sentence mode the model speaks continuously — sentence after
 * sentence with no >= 1 s gap — so the burst detector (FIX33) sees one
 * long burst and no burst starts, and no latency sample ever runs: the
 * auto delay freezes at its seed (16.134 s on the user's phone, 06:02
 * 2026-11-24). The live source transcription still has sentence
 * boundaries (punctuation), so a source sentence's duration can be timed
 * directly: the model waits for the full sentence, then translates it,
 * so the latency IS the sentence duration plus the translation overhead
 * (~1.5 s, added by the caller).
 *
 * Durations under [minDurationMs] are rejected: if a whole sentence
 * arrives in one transcription chunk, its measured duration is ~0, which
 * is a batching artifact, not a real sentence. Durations over
 * [maxDurationMs] are rejected the same way (a sentence boundary was
 * missed). Pure Kotlin, no Android dependencies, unit-testable.
 */
class SentenceDurationTracker(
    private val minDurationMs: Long = 1000L,
    private val maxDurationMs: Long = 20000L
) {
    private val sentenceEnds = setOf('。', '！', '？', '.', '!', '?', '…')

    private var sentenceStartMs: Long = -1L
    private val pendingText = StringBuilder()

    /**
     * Feed one source-transcription chunk. Returns the durations of all
     * sentences completed by this chunk (usually zero or one).
     */
    fun onText(text: String, nowMs: Long): List<Long> {
        val durations = mutableListOf<Long>()
        if (text.isNotEmpty()) {
            if (sentenceStartMs < 0L) sentenceStartMs = nowMs
            pendingText.append(text)
        }
        while (true) {
            val endIdx = pendingText.indexOfFirst { it in sentenceEnds }
            if (endIdx < 0) break
            val duration = nowMs - sentenceStartMs
            val rest = pendingText.substring(endIdx + 1)
            pendingText.clear()
            pendingText.append(rest)
            sentenceStartMs = if (rest.isNotBlank()) nowMs else -1L
            if (duration in minDurationMs..maxDurationMs) durations.add(duration)
        }
        return durations
    }

    fun reset() {
        sentenceStartMs = -1L
        pendingText.clear()
    }
}
