package com.dublive.live

/**
 * v1.0.9 — ALDA-style delay tracker. REPLACES the v1.0.8 slice FIFO +
 * per-sentence median.
 *
 * WHY THE REPLACEMENT: v1.0.8 measured the delay by pairing 500 ms
 * slices of speech with 500 ms slices of dubbed audio — but it never
 * made the three things play at the same moment. The picture was
 * delayed by D, the dub voice played the instant it arrived
 * (latency ≈ 2–8 s, varying), and the original audio played live.
 * Three clocks, three times. The user report 21:08 ("video gốc và
 * âm thanh gốc không khớp với giọng dịch — độ trễ hình làm giống
 * alad là được") is exactly that: only ALDA's full mechanism syncs
 * all three, and DubLive had only 1 of its 3 pieces (picture delay).
 *
 * ALDA's mechanism (FIX32/FIX33/FIX39/FIX43, ported here):
 *  1. MEASURE at burst-start ARRIVAL: a translated-audio chunk
 *     arriving after >= 500 ms of dub silence starts a new burst
 *     (AudioBurstDetector). Sample = burst start − source utterance
 *     start (VAD onSpeechStart); skipped when the utterance start is
 *     stale (> 20 s — the burst belongs to an older utterance).
 *     The sample feeds LatencyEma (0.3/sample + 2000 ms margin,
 *     clamped 3–20 s, 500 ms deadband) and moves D.
 *     The FIRST audio chunk also seeds the EMA and sets
 *     D = (first latency + 2000 ms) clamped, exactly like ALDA.
 *     NOTE: the sample is taken at ARRIVAL, not at playback — with
 *     the voice hold below, measuring at playback would be
 *     self-confirming (voice plays at utteranceStart + D by
 *     construction) and would tell us nothing.
 *  2. HOLD the dubbed voice: hold = max(0, D − latency of this
 *     burst), recomputed at every burst start. The voice waits until
 *     (utterance start + D) — the moment the delayed picture reaches
 *     that sentence — and only then speaks. The hold is handed to
 *     VoicePlayer, which queues each chunk of the burst with that
 *     fixed due time (DelayedAudioQueue fixed-hold mode).
 *  3. The ORIGINAL audio is delayed by the same D through
 *     DelayedBackgroundPlayer (see DubbingService) — that piece lives
 *     in the service, not here, but it reads the same D from here.
 *
 * Manual "Trễ −1s / +1s" shifts D directly and broadcasts at once;
 * later automatic samples keep EMA-ing from the manual value (the
 * LatencyEma compares against the current D, so a manual value
 * persists until a sample moves it by >= 500 ms).
 *
 * All methods are @Synchronized: chunks arrive on the Live socket
 * thread, manual nudges on the main thread, the reconnect reset on
 * the service thread.
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    initialDelayMs: Long,
    private val onDelayChanged: (Long) -> Unit
) {
    private val latencyEma = LatencyEma(maxDelayMs = maxDelayMs)
    private val burstDetector = AudioBurstDetector(500L)

    /** D — the delay the picture, the original audio and the voice
     * hold all follow. Starts at the Settings base-delay seed. */
    private var delayMs: Long = initialDelayMs.coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)

    /** Hold computed at the latest burst start (for the subtitle
     * delay: the subtitle shows when the voice STARTS PLAYING, i.e.
     * arrival + hold, not at arrival). */
    private var lastHoldMs: Long = 0L

    private var firstAudioSeeded = false

    val currentDelayMs: Long get() = delayMs
    val currentHoldMs: Long get() = lastHoldMs

    /**
     * One translated-audio chunk arrived from Live. Returns the hold
     * (ms) VoicePlayer must wait before playing this chunk: 0 means
     * play at once (already past utteranceStart + D).
     *
     * [utteranceStartMs] is the VAD onSpeechStart of the current
     * source utterance (0 when none seen yet). [firstAudioSentAtMs]
     * is when the first source audio of the session was sent to Live
     * (0 when none yet) — used once, for the ALDA first-audio seed.
     */
    @Synchronized
    fun onAudioChunkArrival(
        nowMs: Long,
        utteranceStartMs: Long,
        firstAudioSentAtMs: Long
    ): Long {
        var hold = lastHoldMs
        // ALDA FIX32 first-audio seed: the very first translation's
        // latency sets the EMA and the delay (latency + 2000 ms).
        if (!firstAudioSeeded && firstAudioSentAtMs > 0L) {
            firstAudioSeeded = true
            val elapsed = (nowMs - firstAudioSentAtMs).coerceIn(0L, 18000L)
            latencyEma.seed(elapsed)
            val newDelay = (elapsed + 2000L).coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
            if (newDelay != delayMs) {
                delayMs = newDelay
                onDelayChanged(delayMs)
            }
            hold = (newDelay - elapsed).coerceAtLeast(0L)
            lastHoldMs = hold
        }
        // FIX33/FIX43 burst-start sample + per-burst hold.
        if (burstDetector.onChunk(nowMs)) {
            hold = onBurstStart(nowMs, utteranceStartMs)
            lastHoldMs = hold
        }
        return hold
    }

    /**
     * A new dubbed burst started. Sample the latency (burst start −
     * utterance start), feed the EMA (it moves D when the change is
     * >= 500 ms), and return this burst's hold = max(0, D − latency).
     * A stale/missing utterance start skips the sample and keeps the
     * previous hold.
     */
    private fun onBurstStart(nowMs: Long, utteranceStartMs: Long): Long {
        if (utteranceStartMs > 0L && nowMs - utteranceStartMs <= 20_000L) {
            val latency = nowMs - utteranceStartMs
            val updated = latencyEma.sample(latency, delayMs)
            if (updated != null) {
                delayMs = updated.toLong()
                onDelayChanged(delayMs)
            }
            return (delayMs - latency).coerceAtLeast(0L)
        }
        return lastHoldMs
    }

    /** Fresh pairing after a (re)connect: the burst detector restarts
     * (the next chunk is a burst start and samples at once); the EMA
     * and D are kept — the delay does not jump on reconnect. */
    @Synchronized
    fun resetForReconnect() {
        burstDetector.reset()
    }

    /** Manual nudge from the "Trễ −1s / +1s" buttons: shift D by the
     * delta and push it out at once. Later automatic samples keep
     * EMA-ing from this manual value — they never pull it back. */
    @Synchronized
    fun adjustManual(deltaMs: Long) {
        delayMs = (delayMs + deltaMs).coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
        onDelayChanged(delayMs)
    }

    @Synchronized
    fun stop() {
        burstDetector.reset()
        lastHoldMs = 0L
    }
}
