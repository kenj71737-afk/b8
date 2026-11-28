package com.dublive.live

/**
 * v1.0.9 — port of ALDA FIX32's LatencyEma (core/audio/LatencyEma.kt,
 * FIX43 source). Running estimate of the translation latency, used to
 * keep the automatic media delay matched to the real lag between the
 * picture and the dubbed voice.
 *
 * A sample is (dub burst start arrival − source utterance start).
 * EMA 0.3 per sample + safety margin, clamped to the 3–20 s
 * delay range, 500 ms deadband (a change smaller than the deadband does
 * not update the delay, so the picture does not jitter on every turn).
 *
 * v1.0.17 — DYNAMIC MARGIN (was a fixed 2000 ms). The fixed margin
 * was a guess at the playback pipeline: it neither measured the
 * pipeline nor compensated it, so the voice always played
 * (pipeline) late against the picture no matter what D was — the
 * hold (D − arrival latency) moves the voice by exactly as much as
 * D moves the picture, so no D can ever fix a pipeline offset.
 * The tracker now measures the pipeline per burst (VoicePlayer
 * reports the real play start) and sets the margin to
 * pipeline + ~400 ms jitter, clamped 500–2000 ms. The margin is
 * the headroom that keeps the hold >= 0 despite latency variance:
 * D = EMA(arrival latency) + margin must exceed
 * (arrival latency + pipeline) for the voice to be schedulable at
 * (utterance start + D) at all. [setMarginMs] is called by the
 * tracker whenever its pipeline EMA moves.
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
    private var marginMs: Long = 2000L,
    private val minDelayMs: Long = 3000L,
    private val maxDelayMs: Long = 20000L,
    private val creepMs: Long = 250L
) {
    private var emaMs: Long? = null

    /** v1.0.17 — dynamic margin (pipeline + jitter, 500–2000 ms),
     * set by the tracker as its pipeline EMA moves. */
    fun setMarginMs(margin: Long) {
        marginMs = margin.coerceIn(500L, 2000L)
    }

    fun margin(): Long = marginMs

    /** Seed the estimate (the first translation's latency). */
    fun seed(latencyMs: Long) {
        emaMs = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
    }

    /** v1.0.18 — full reset for a session restart (stop/start):
     * the EMA and the margin must not leak from the previous
     * session into the next one. */
    fun reset() {
        emaMs = null
        marginMs = 2000L
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
     *
     * v1.0.24 Fix B (sim1030/1031 — the r=1.40 explosion): an upward
     * D jump is capped at +[creepMs] (250 ms) per accepted sample,
     * the same as the downward creep. A wrong S-b trial ref used to
     * jack D +1600 ms in ~15 s (5161 → 6758) because correct ~4.5 s
     * slices were D-check-rejected while wrong ~6.5 s slices fed the
     * EMA — the uncapped jump turned a measurement error into a hold
     * jack (562 → 2399) and a 1940 ms end-burst. The EMA state still
     * updates fully; only the returned D is rate-limited. Downward
     * big jumps stay instant (the EMA target already fell). The
     * upward creep below the deadband is dropped, like the sim's
     * LatencyEma31 (only the downward creep remains): D rises only
     * through the capped jump path, never by creep.
     */
    fun sample(latencyMs: Long, currentDelayMs: Long, deadbandMs: Long = 500L): Int? {
        val sample = latencyMs.coerceIn(0L, maxDelayMs - marginMs)
        emaMs = if (emaMs == null) sample else (emaMs!! * 7 + sample * 3) / 10
        val newDelay = (emaMs!! + marginMs).coerceIn(minDelayMs, maxDelayMs)
        if (newDelay - currentDelayMs >= deadbandMs) {
            return minOf(newDelay, currentDelayMs + creepMs).toInt()
        }
        if (kotlin.math.abs(newDelay - currentDelayMs) >= deadbandMs) {
            return newDelay.toInt()
        }
        val target = targetDelay() ?: return null
        if (target < currentDelayMs) {
            return maxOf(target, currentDelayMs - creepMs).toInt()
        }
        return null
    }

    fun ema(): Long? = emaMs
}
