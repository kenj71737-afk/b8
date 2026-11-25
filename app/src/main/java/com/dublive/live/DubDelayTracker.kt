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
 * DubLive therefore samples PERIODICALLY, independent of bursts:
 *  - a sample is taken when a translation starts speaking, and
 *  - every 2 s while a translation is in flight, a provisional sample is
 *    taken from (now - utterance start).
 * Samples are smoothed with EMA (alpha 0.3) and clamped to [3 s, maxDelay].
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    private val onDelayChanged: (Long) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var emaMs: Long = 5238L // seed: first-translation latency + 2 s margin
    private var utteranceStartMs: Long = -1L
    private var sampling = false

    private val tick = object : Runnable {
        override fun run() {
            if (sampling && utteranceStartMs > 0) {
                val now = System.currentTimeMillis()
                addSample(now - utteranceStartMs)
            }
            if (sampling) handler.postDelayed(this, 2000L)
        }
    }

    val currentDelayMs: Long get() = emaMs

    fun startUtterance() {
        utteranceStartMs = System.currentTimeMillis()
        if (!sampling) {
            sampling = true
            handler.postDelayed(tick, 2000L)
        }
    }

    /** Called when the dubbed voice actually starts speaking. */
    fun onDubSpeechStarted() {
        if (utteranceStartMs > 0) addSample(System.currentTimeMillis() - utteranceStartMs)
    }

    fun endUtterance() {
        utteranceStartMs = -1L
    }

    fun stop() {
        sampling = false
        utteranceStartMs = -1L
        handler.removeCallbacks(tick)
    }

    private fun addSample(sampleMs: Long) {
        if (sampleMs < 500L || sampleMs > 60_000L) return
        val clamped = min(max(sampleMs, Prefs.MIN_DELAY_MS), maxDelayMs)
        emaMs = (0.7 * emaMs + 0.3 * clamped).toLong()
        onDelayChanged(emaMs)
    }
}
