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
 * v1.0.14 — CREEP (the 05:08 freeze trap). The deadband + the 3 s
 * floor clamp could freeze D forever: with every sample stuck at the
 * ~0.6 s floor (the v1.0.13 failure — the dub is ~1.3x the source, so
 * positional FIFO pairing drains the queue and every sample is a
 * floor sample), the target pins at 3.0 s while D sits at 3.4 s, and
 * |3.4 − 3.0| = 0.4 < 0.5 deadband blocks every update. D can never
 * move again. The creep lets D crawl 250 ms per 4 s window toward
 * the EMA target when the deadband would otherwise trap it — D still
 * never jumps (at most the creep per window), it just cannot freeze.
 * [targetDelay] exposes the current EMA target for that crawl.
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class LatencyEma(
    private val marginMs: Long = 2000L,
    private val minDelayMs: Long = 3000L,
    private val maxDelayMs: Long = 20000L,
    private val creepMs: Long = 250L
) {
    private var emaMs: Long? = null

    /** Seed the estimate (the first translation's latency). */
    fun seed(latencyMs: Long) {
        emaMs = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
    }

    /** The delay the EMA currently targets (ema + margin, clamped).
     * Null before any sample/seed. */
    fun targetDelay(): Long? =
        emaMs?.let { (it + marginMs).coerceIn(minDelayMs, maxDelayMs) }

    /**
     * Feed one per-burst latency sample. Returns the delay the app
     * should use now, or null when the change from [currentDelayMs] is
     * below the deadband — except the creep (v1.0.14): when the
     * deadband would trap D at the clamp, D crawls [creepMs] toward
     * the target instead of freezing.
     */
    fun sample(latencyMs: Long, currentDelayMs: Long, deadbandMs: Long = 500L): Int? {
        val sample = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
        emaMs = if (emaMs == null) sample else (emaMs!! * 7 + sample * 3) / 10
        val newDelay = (emaMs!! + marginMs).coerceIn(minDelayMs, maxDelayMs)
        if (kotlin.math.abs(newDelay - currentDelayMs) >= deadbandMs) {
            return newDelay.toInt()
        }
        val target = targetDelay() ?: return null
        if (target < currentDelayMs) {
            return maxOf(target, currentDelayMs - creepMs).toInt()
        }
        if (target > currentDelayMs) {
            return minOf(target, currentDelayMs + creepMs).toInt()
        }
        return null
    }

    fun ema(): Long? = emaMs
}
