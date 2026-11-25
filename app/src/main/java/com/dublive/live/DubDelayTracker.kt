package com.dublive.live

/**
 * v1.0.10 — continuous slice measurement. REPLACES the v1.0.9
 * burst-start-only sampling (which replaced the v1.0.8 slice FIFO).
 *
 * WHY: the user report 21:51 after v1.0.9 ("Chưa khớp với hình.
 * Độ trễ hình không tự động cập nhật theo đoạn âm thanh gốc") is the
 * same trap ALDA's own FIX39/FIX43 notes admit: burst-start sampling
 * needs (a) a >= 500 ms gap in the dubbed voice to open a new burst
 * and (b) a source-utterance start younger than 20 s. Continuous
 * Cantonese dialogue + a continuously streaming dub voice have
 * neither — no gap ever opens and one VAD utterance lasts minutes —
 * so no valid sample ever fires and D freezes at its seed. Measuring
 * by EVENTS (burst / sentence) can never work with continuous speech.
 * The measurement must be CONTINUOUS, per slice of source audio, with
 * no boundary to wait for.
 *
 * HOW IT WORKS (the v1.0.9 sync machine is KEPT — only the
 * measurement changes):
 *  1. SPEECH SIDE — the service feeds every in-speech frame
 *     (VAD onSpeechChunk) into [onSpeechMs]. Every 500 ms of speech
 *     pushes its END time into a FIFO (max 40 slices = 20 s; when
 *     full the oldest slice is dropped and counted, never a full
 *     clear — the v1.0.7/v1.0.8 "clear the whole queue to resync"
 *     threw the pairing away every time a music gap produced one bad
 *     sample).
 *  2. DUB SIDE — the service feeds every arriving Live chunk into
 *     [onDubAudioArrival] with its duration in ms (24 kHz PCM:
 *     ms = bytes / 48). Every 500 ms of ARRIVING dub pops the OLDEST
 *     speech slice: sample = arrival − slice time. The sample is
 *     taken at ARRIVAL, never at playback — with the v1.0.9 voice
 *     hold, measuring at playback would be self-confirming (the voice
 *     plays at utteranceStart + D by construction). A sample outside
 *     [500 ms, 20000 ms] drops JUST THAT SAMPLE and pairing continues.
 *     A dub slice with an empty speech queue produces no sample
 *     (the dub is reading a long sentence while the source is
 *     already silent — pairing it against anything would be wrong).
 *  3. AGGREGATION per SOURCE-AUDIO SEGMENT — samples are collected
 *     in a 4 s wall-clock window (NOT waiting for a sentence
 *     boundary). Every 4 s the window's MEDIAN feeds LatencyEma
 *     (0.3/sample + 2000 ms margin, clamped 3–20 s, 500 ms deadband),
 *     so D updates at most once per 4 s segment of source audio —
 *     exactly what the user asked ("cập nhật theo đoạn âm thanh
 *     gốc"). The burst-start sample from v1.0.9 is KEPT as a
 *     supplementary source: a burst start with a fresh utterance
 *     start (< 20 s) feeds the EMA at once, without waiting for the
 *     4 s window.
 *  4. STALL — when no new speech slice has arrived for > 10 s
 *     (silence / music between lines) measuring stops: the queue is
 *     cleared, the window is dropped, and no stale sample is ever
 *     emitted. Measuring resumes with the next speech slice.
 *
 * DIAGNOSTICS: [diagnostics] renders one line for the HUD (and the
 * "Xem lỗi Live" dialog): current D, last accepted sample + its age,
 * samples in the last 60 s, slices waiting in the queue, current
 * voice hold, VAD state. Refreshed every second by the service, so
 * the user reports numbers instead of "không cập nhật".
 *
 * All methods are @Synchronized: speech frames arrive on the capture
 * thread, dub chunks on the Live socket thread, manual nudges on the
 * main thread, diagnostics on the level ticker.
 */
class DubDelayTracker(
    private val maxDelayMs: Long,
    initialDelayMs: Long,
    private val onDelayChanged: (Long) -> Unit
) {
    companion object {
        /** One measurement slice, ms. */
        const val SLICE_MS = 500L
        /** Max speech slices kept (20 s of speech). */
        const val MAX_SLICES = 40
        /** Aggregation window per source-audio segment, ms. */
        const val WINDOW_MS = 4000L
        /** Valid latency sample range, ms. */
        const val MIN_SAMPLE_MS = 500L
        const val MAX_SAMPLE_MS = 20000L
        /** No new speech slice for this long = stalled, stop measuring. */
        const val STALL_MS = 10_000L
        /** A sample counts as "recent" for the 60 s counter window. */
        const val SAMPLES_WINDOW_MS = 60_000L
        /** VAD counts as "speaking" while a slice arrived this recently. */
        const val SPEAKING_RECENT_MS = 1000L
    }

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

    // ---- continuous slice pairing (v1.0.10) ----
    /** End-times of 500 ms speech slices, oldest first. */
    private val speechSlices = ArrayDeque<Long>()
    /** In-speech ms accumulated since the last slice was pushed. */
    private var speechAccumMs = 0L
    /** When the latest speech slice was pushed (0 = none yet). */
    private var lastSpeechSliceAtMs = 0L
    /** Arriving dub ms accumulated since the last slice pairing. */
    private var dubAccumMs = 0L
    /** Latency samples collected in the current 4 s window. */
    private val windowSamples = ArrayList<Long>()
    /** Wall-clock start of the current 4 s window (0 = not started). */
    private var windowStartMs = 0L

    // ---- diagnostics ----
    private var lastSampleMs = 0L
    private var lastSampleAtMs = 0L
    private val sampleTimes60s = ArrayDeque<Long>()
    private var droppedSlices = 0
    private var droppedSamples = 0
    private var emptyPairings = 0

    val currentDelayMs: Long get() = delayMs
    val currentHoldMs: Long get() = lastHoldMs

    /**
     * [ms] of in-speech audio just passed the VAD (called from
     * onSpeechChunk, any chunk size). Every 500 ms pushes a slice.
     * A gap > [STALL_MS] since the previous slice restarts the
     * window — samples from before the silence must not mix with
     * samples after it.
     */
    @Synchronized
    fun onSpeechMs(ms: Long, nowMs: Long) {
        if (ms <= 0L) return
        if (lastSpeechSliceAtMs > 0L && nowMs - lastSpeechSliceAtMs > STALL_MS) {
            // Silence / music gap: the old window is stale.
            windowSamples.clear()
            windowStartMs = nowMs
            speechAccumMs = 0L
        }
        if (windowStartMs == 0L) windowStartMs = nowMs
        speechAccumMs += ms
        while (speechAccumMs >= SLICE_MS) {
            speechAccumMs -= SLICE_MS
            speechSlices.addLast(nowMs)
            lastSpeechSliceAtMs = nowMs
            while (speechSlices.size > MAX_SLICES) {
                speechSlices.removeFirst()
                droppedSlices++
            }
        }
    }

    /**
     * One translated-audio chunk arrived from Live. Returns the hold
     * (ms) VoicePlayer must wait before playing this chunk: 0 means
     * play at once (already past utteranceStart + D).
     *
     * [dubMs] is the chunk's duration (24 kHz PCM: bytes / 48).
     * [utteranceStartMs] is the VAD onSpeechStart of the current
     * source utterance (0 when none seen yet) — only used for the
     * supplementary burst-start sample. [firstAudioSentAtMs] is when
     * the first source audio of the session was sent to Live (0 when
     * none yet) — used once, for the ALDA first-audio seed.
     */
    @Synchronized
    fun onDubAudioArrival(
        nowMs: Long,
        dubMs: Long,
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
        // Supplementary burst-start sample (v1.0.9, kept): a burst
        // start with a fresh utterance start feeds the EMA at once.
        // With continuous speech this rarely fires — the slice
        // pairing below is the main measurement now.
        if (burstDetector.onChunk(nowMs)) {
            hold = onBurstStart(nowMs, utteranceStartMs)
            lastHoldMs = hold
        }
        // Continuous slice pairing (v1.0.10, the main measurement).
        pairDubSlices(dubMs, nowMs)
        // 4 s window flush: median of the window's samples -> EMA.
        flushWindowIfDue(nowMs)
        return hold
    }

    /**
     * Pair arriving dub ms against the oldest speech slices. One
     * sample per 500 ms of dub. A stalled source (no new speech
     * slice for > [STALL_MS]) stops measuring entirely: the queue is
     * cleared and no stale sample is emitted.
     */
    private fun pairDubSlices(dubMs: Long, nowMs: Long) {
        if (dubMs <= 0L) return
        if (lastSpeechSliceAtMs == 0L) {
            // No speech ever seen: dub with nothing to pair against
            // (hallucinated voice). Do not accumulate it — it must
            // never pair with a future speech slice.
            dubAccumMs = 0L
            return
        }
        if (nowMs - lastSpeechSliceAtMs > STALL_MS) {
            // Source silent for a while: stop measuring, drop stale state.
            speechSlices.clear()
            dubAccumMs = 0L
            windowSamples.clear()
            windowStartMs = nowMs
            return
        }
        dubAccumMs += dubMs
        while (dubAccumMs >= SLICE_MS) {
            dubAccumMs -= SLICE_MS
            val slice = speechSlices.removeFirstOrNull()
            if (slice == null) {
                // Dub flowing while the source is silent (a long
                // translated sentence): no sample, and crucially no
                // pairing against anything else.
                emptyPairings++
                continue
            }
            val sample = nowMs - slice
            if (sample in MIN_SAMPLE_MS..MAX_SAMPLE_MS) {
                if (windowStartMs == 0L) windowStartMs = nowMs
                windowSamples.add(sample)
                lastSampleMs = sample
                lastSampleAtMs = nowMs
                sampleTimes60s.addLast(nowMs)
                while (sampleTimes60s.isNotEmpty() &&
                    nowMs - sampleTimes60s.first() > SAMPLES_WINDOW_MS
                ) {
                    sampleTimes60s.removeFirst()
                }
            } else {
                // One bad sample (e.g. a music gap): drop JUST this
                // sample and keep pairing. Never clear the queue.
                droppedSamples++
            }
        }
    }

    /**
     * Every [WINDOW_MS] the window's median feeds the EMA and moves
     * D (when the change is >= the 500 ms deadband). A window whose
     * newest sample is older than [STALL_MS] is stale and dropped.
     * An empty window just restarts the clock.
     */
    private fun flushWindowIfDue(nowMs: Long) {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            return
        }
        if (nowMs - windowStartMs < WINDOW_MS) return
        if (windowSamples.isNotEmpty() &&
            nowMs - lastSampleAtMs <= STALL_MS
        ) {
            val sorted = windowSamples.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
            val updated = latencyEma.sample(median, delayMs)
            if (updated != null) {
                delayMs = updated.toLong()
                onDelayChanged(delayMs)
            }
        }
        windowSamples.clear()
        windowStartMs = nowMs
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

    /**
     * One-line diagnostics for the HUD (under the "Trễ hình" number)
     * and the "Xem lỗi Live" dialog. Refreshed every second by the
     * service. Reports numbers, so a frozen delay is diagnosable
     * from a screenshot instead of "không cập nhật".
     */
    @Synchronized
    fun diagnostics(nowMs: Long): String {
        while (sampleTimes60s.isNotEmpty() &&
            nowMs - sampleTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            sampleTimes60s.removeFirst()
        }
        val delaySec = "%.1f".format(delayMs / 1000.0)
        val samplePart = if (lastSampleMs > 0L) {
            val ageSec = ((nowMs - lastSampleAtMs) / 1000L).coerceAtLeast(0L)
            "mẫu cuối %.1f s (%d s trước)".format(lastSampleMs / 1000.0, ageSec)
        } else {
            "chưa có mẫu trễ"
        }
        val vadPart = if (lastSpeechSliceAtMs > 0L &&
            nowMs - lastSpeechSliceAtMs <= SPEAKING_RECENT_MS
        ) {
            "VAD: đang nói"
        } else {
            "VAD: nghỉ"
        }
        val droppedPart = if (droppedSlices > 0 || droppedSamples > 0) {
            " · bỏ %d lát/%d mẫu".format(droppedSlices, droppedSamples)
        } else {
            ""
        }
        return "D %s s · %s · %d mẫu/60 s · chờ %d lát · hold %.1f s · %s%s".format(
            delaySec, samplePart, sampleTimes60s.size, speechSlices.size,
            lastHoldMs / 1000.0, vadPart, droppedPart
        )
    }

    /** Fresh pairing after a (re)connect: the burst detector restarts
     * (the next chunk is a burst start and samples at once); the
     * slice state restarts too; the EMA and D are kept — the delay
     * does not jump on reconnect. */
    @Synchronized
    fun resetForReconnect() {
        burstDetector.reset()
        speechSlices.clear()
        speechAccumMs = 0L
        dubAccumMs = 0L
        windowSamples.clear()
        windowStartMs = 0L
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
        speechSlices.clear()
        speechAccumMs = 0L
        lastSpeechSliceAtMs = 0L
        dubAccumMs = 0L
        windowSamples.clear()
        windowStartMs = 0L
        lastSampleMs = 0L
        lastSampleAtMs = 0L
        sampleTimes60s.clear()
        droppedSlices = 0
        droppedSamples = 0
        emptyPairings = 0
    }
}
