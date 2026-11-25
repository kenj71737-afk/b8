package com.dublive.live

/**
 * v1.0.9 — port of ALDA FIX32's LatencyEma (core/audio/LatencyEma.kt,
 * FIX43 source). Running estimate of the translation latency, used to
 * keep the automatic media delay matched to the real lag between the
 * picture and the dubbed voice.
 *
 * A sample is (dub burst start arrival − source utterance start).
 * EMA 0.3 per sample + 2000 ms safety margin, clamped to the 3–20 s
 * delay range, 500 ms deadband (a change smaller than the deadband does
 * not update the delay, so the picture does not jitter on every turn).
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class LatencyEma(
    private val marginMs: Long = 2000L,
    private val minDelayMs: Long = 3000L,
    private val maxDelayMs: Long = 20000L
) {
    private var emaMs: Long? = null

    /** Seed the estimate (the first translation's latency). */
    fun seed(latencyMs: Long) {
        emaMs = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
    }

    /**
     * Feed one per-burst latency sample. Returns the delay the app
     * should use now, or null when the change from [currentDelayMs] is
     * below the deadband.
     */
    fun sample(latencyMs: Long, currentDelayMs: Long, deadbandMs: Long = 500L): Int? {
        val sample = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
        emaMs = if (emaMs == null) sample else (emaMs!! * 7 + sample * 3) / 10
        val newDelay = (emaMs!! + marginMs).coerceIn(minDelayMs, maxDelayMs)
        return if (kotlin.math.abs(newDelay - currentDelayMs) >= deadbandMs) {
            newDelay.toInt()
        } else {
            null
        }
    }

    fun ema(): Long? = emaMs
}
