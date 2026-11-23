package com.alad.app.core.audio

/**
 * FIX32: running estimate of the translation latency, used to keep the
 * automatic media delay matched to the real lag between the picture and
 * the dubbed voice. Mirrors the web dubbing script's DubSyncTracker
 * (EMA with 30% weight per sample + a 2 s safety margin, clamped to the
 * 3–20 s delay range).
 *
 * Before FIX32 the auto delay was measured exactly once — the very first
 * translated audio of the session — and then frozen; worse, that first
 * latency was clamped at 8 s, so the delay showed exactly 10 s (8 + 2)
 * on every video whenever the first translation took 8 s or more.
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
     * Feed one per-turn latency sample (first translated audio of a turn
     * minus the utterance's start). Returns the delay the app should use
     * now, or null when the change from [currentDelayMs] is below the
     * deadband (so the picture does not jitter on every turn).
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
