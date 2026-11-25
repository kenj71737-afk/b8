package com.dublive.live

import android.os.Handler
import android.os.Looper
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
 * v1.0.6 — EMA alpha 0.3 -> 0.5 so the delay tracks the real latency
 * of each sentence faster; samples over 15 s are ignored (the first
 * sample after connecting can include stale pre-connect buffer and
 * does not represent the real latency). One sample per utterance,
 * clamped to [3 s, maxDelay]. While no translation has ever arrived the
 * delay stays FROZEN at the 5.2 s seed (v1.0.4).
 * v1.0.4 sampled again every 2 s WHILE the dub was speaking, so with
 * continuous dialogue (VAD never sees a 700 ms silence, so the utterance
 * never ends) the sample grew with the utterance length and the delay
 * climbed to the 20 s cap — the user screenshot at 19:21 still showed
 * "Trễ hình: 19,4 s" even though dubbing was working. The periodic tick
 * is GONE: it only watches for a stale utterance (speech running > 30 s
 * with no dubbed voice yet) and resets its start time, so the sample
 * can never balloon. Samples are smoothed with EMA (alpha 0.3) and
 * clamped to [3 s, maxDelay]. While no translation has ever arrived the
 * delay stays FROZEN at the 5.2 s seed (v1.0.4).
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    private val hasDubOutput: () -> Boolean = { true },
    private val onDelayChanged: (Long) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var emaMs: Long = 5238L // seed: first-translation latency + 2 s margin
    private var utteranceStartMs: Long = -1L
    /** True once the dubbed voice has spoken for the current utterance. */
    private var dubSpokeThisTurn = false
    private var tickRunning = false

    /**
     * Watchdog tick only: if one utterance runs longer than 30 s with
     * no dubbed voice yet, its start time is stale (a new sentence
     * started long ago inside the same VAD utterance) — reset it to now
     * so the one sample taken later cannot be a 30 s+ balloon. This
     * tick NEVER adds a sample.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (utteranceStartMs > 0 && !dubSpokeThisTurn) {
                val age = System.currentTimeMillis() - utteranceStartMs
                if (age > 30_000L) utteranceStartMs = System.currentTimeMillis()
            }
            if (tickRunning) handler.postDelayed(this, 2000L)
        }
    }

    val currentDelayMs: Long get() = emaMs

    fun startUtterance() {
        utteranceStartMs = System.currentTimeMillis()
        dubSpokeThisTurn = false
        if (!tickRunning) {
            tickRunning = true
            handler.postDelayed(tick, 2000L)
        }
    }

    /** Called when the dubbed voice actually starts speaking. Takes the
     * ONE sample for this utterance, then never samples again until the
     * next utterance starts. */
    fun onDubSpeechStarted() {
        if (dubSpokeThisTurn) return
        dubSpokeThisTurn = true
        if (utteranceStartMs > 0 && hasDubOutput()) {
            addSample(System.currentTimeMillis() - utteranceStartMs)
        }
    }

    fun endUtterance() {
        utteranceStartMs = -1L
        if (!dubSpokeThisTurn) {
            // No dubbed speech for this utterance: stop the watchdog
            // tick — a new utterance will restart it.
            tickRunning = false
            handler.removeCallbacks(tick)
        }
    }

    fun stop() {
        tickRunning = false
        dubSpokeThisTurn = false
        utteranceStartMs = -1L
        handler.removeCallbacks(tick)
    }

    private fun addSample(sampleMs: Long) {
        // v1.0.6: ignore samples over 15 s — the first sample after
        // connecting can include stale pre-connect buffer, and a 15 s+
        // latency is not a real per-sentence delay.
        if (sampleMs < 500L || sampleMs > 15_000L) return
        val clamped = min(max(sampleMs, Prefs.MIN_DELAY_MS), maxDelayMs)
        emaMs = (0.5 * emaMs + 0.5 * clamped).toLong()
        onDelayChanged(emaMs)
    }
}
