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
 *
 * v1.0.4 — the periodic sampling had a hole: startUtterance() started the
 * 2 s tick even when the dubbed voice had NEVER played (dead Live, no
 * output at all), so the tick kept sampling (now - utteranceStart) of
 * untranslated speech and the delay ballooned to the 20 s cap — the user
 * screenshot showed "Trễ hình: 19,3 s" with zero translations. Now:
 *  - the tick only samples while dubbed speech is actually playing
 *    (after onDubSpeechStarted() for the current utterance);
 *  - while no translated output has ever arrived (hasDubOutput() false)
 *    the delay stays FROZEN at the 5.2 s seed — no periodic samples;
 *  - endUtterance() with no dubbed speech for that utterance stops the
 *    sampling outright.
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    private val hasDubOutput: () -> Boolean = { true },
    private val onDelayChanged: (Long) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var emaMs: Long = 5238L // seed: first-translation latency + 2 s margin
    private var utteranceStartMs: Long = -1L
    private var sampling = false
    /** True only between onDubSpeechStarted() and the next startUtterance(). */
    private var dubSpeaking = false
    /** True once the dubbed voice has spoken for the current utterance. */
    private var dubSpokeThisTurn = false

    private val tick = object : Runnable {
        override fun run() {
            // v1.0.4: sample ONLY while dubbed speech is actually playing
            // for this utterance AND at least one translation has arrived
            // before. Otherwise the delay would balloon while Live is
            // dead — exactly the 19.3 s in the v1.0.3 screenshot.
            if (sampling && dubSpeaking && utteranceStartMs > 0 && hasDubOutput()) {
                addSample(System.currentTimeMillis() - utteranceStartMs)
            }
            if (sampling) handler.postDelayed(this, 2000L)
        }
    }

    val currentDelayMs: Long get() = emaMs

    fun startUtterance() {
        utteranceStartMs = System.currentTimeMillis()
        // A new utterance: the dubbed voice has not spoken for THIS one
        // yet. Do NOT start periodic sampling here — it starts in
        // onDubSpeechStarted(). With no output ever, nothing samples and
        // the delay stays frozen at the 5.2 s seed.
        dubSpeaking = false
        dubSpokeThisTurn = false
    }

    /** Called when the dubbed voice actually starts speaking. */
    fun onDubSpeechStarted() {
        dubSpeaking = true
        dubSpokeThisTurn = true
        if (utteranceStartMs > 0 && hasDubOutput()) {
            addSample(System.currentTimeMillis() - utteranceStartMs)
        }
        if (!sampling) {
            sampling = true
            handler.postDelayed(tick, 2000L)
        }
    }

    fun endUtterance() {
        utteranceStartMs = -1L
        if (!dubSpokeThisTurn) {
            // No dubbed speech for this utterance: stop sampling outright
            // (v1.0.4) — otherwise the tick keeps measuring untranslated
            // speech and the delay climbs for nothing.
            dubSpeaking = false
            sampling = false
            handler.removeCallbacks(tick)
        }
        // If the dub voice DID speak, the tick keeps running (it will
        // resume sampling at the next utterance's onDubSpeechStarted);
        // dubSpeaking is reset by the next startUtterance().
    }

    fun stop() {
        sampling = false
        dubSpeaking = false
        dubSpokeThisTurn = false
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
