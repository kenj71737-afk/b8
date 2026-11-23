package com.alad.app.core.audio

/**
 * FIX34: decides WHEN the key watchdog may rotate keys or reconnect, so a
 * reconnect never eats a sentence.
 *
 * Before FIX34 the watchdog rotated every 5 minutes the moment the timer
 * fired — even mid-sentence — and every reconnect drops 1-3 s of source
 * audio while the new session sets up (only 2 packets are buffered), so
 * the sentence spanning the gap was never translated. The stall check was
 * also wrong: once any output had arrived, ANY 60 s output gap looked
 * like a dead key — including long quiet scenes (music, action without
 * dialogue) — causing pointless reconnects that each lost a sentence.
 *
 * Pure Kotlin, no Android dependencies, unit-testable. All times are
 * [android.os.SystemClock.elapsedRealtime] milliseconds; a value of -1
 * means "never happened".
 */
class RotationPolicy(
    private val rotationIntervalMs: Long = 5 * 60 * 1000L,
    private val forceRotationMs: Long = 6 * 60 * 1000L,
    private val quietMs: Long = 3000L,
    private val stallTimeoutMs: Long = 60_000L
) {
    /**
     * Should the watchdog rotate for the 5-minute timer? Only in a quiet
     * moment — no translated output and no input speech for [quietMs] —
     * so the reconnect lands between sentences. Forced at [forceRotationMs]
     * so non-stop dialogue cannot defer the rotation forever.
     */
    fun shouldRotate(
        nowMs: Long,
        connectAtMs: Long,
        lastOutputAtMs: Long,
        lastSpeechAtMs: Long
    ): Boolean {
        val age = nowMs - connectAtMs
        if (age < rotationIntervalMs) return false
        if (age >= forceRotationMs) return true
        val outputQuiet = lastOutputAtMs <= 0L || nowMs - lastOutputAtMs >= quietMs
        val speechQuiet = lastSpeechAtMs <= 0L || nowMs - lastSpeechAtMs >= quietMs
        return outputQuiet && speechQuiet
    }

    /**
     * Should the watchdog reconnect for a stall? Only when speech has been
     * flowing recently but no translated output came back for
     * [stallTimeoutMs] — a quiet scene (no speech for 60 s) is not a dead
     * key. This also catches a key that is dead from the start (speech
     * flowing, zero output), which the old hadOutput guard never did.
     */
    fun shouldReconnectForStall(
        nowMs: Long,
        lastOutputAtMs: Long,
        lastSpeechAtMs: Long
    ): Boolean {
        if (lastSpeechAtMs <= 0L) return false // no speech yet: nothing to judge
        if (nowMs - lastSpeechAtMs >= stallTimeoutMs) return false // quiet scene
        return nowMs - lastOutputAtMs > stallTimeoutMs
    }
}
