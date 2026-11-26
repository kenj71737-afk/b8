package com.dublive.live

/**
 * v1.0.14 — TURN-BOUNDARY pairing (design v6, proven by simulation
 * sim1014_v6.py). REPLACES the v1.0.10–v1.0.13 positional FIFO pairing.
 *
 * THE 05:08 FAILURE (user test of v1.0.13, cswold ep-2):
 * "D 3,4 s · mẫu cuối 0,6 s (0 s trước) · 111 mẫu/60 s · chờ 1 lát ·
 * hold 2,0 s · VAD: đang nói · bỏ 0 lát/0 mẫu (nhỏ 1421 hoãn/lớn 0)".
 * Measurement never stopped (111 samples/60 s still flowing) — every
 * sample was stuck at the ~0.6 s floor. The Vietnamese dub is ~1.3x
 * the Chinese source, so the dub side (consuming slices) runs faster
 * than the speech side (pushing slices) in TOTAL duration; the queue
 * drains to ~1 slice ("chờ 1 lát") and each pairing grabs a slice
 * pushed ~0.5–0.6 s ago. Evidence: 1421 deferrals ("nhỏ … hoãn") vs
 * 111 valid samples and 0 drops. Positional FIFO pairing (K-th dub
 * slice ↔ K-th speech slice) is only correct when total dub duration
 * = total speech duration; at 1.3x it drifts and pins at the floor.
 * Then LatencyEma turned the floor samples into a freeze trap:
 * target = 0.6 + 2.0 = 2.6 s → clamped to the 3.0 s floor → the
 * 500 ms deadband blocks the update (|3.4 − 3.0| = 0.4 < 0.5) — D can
 * never move again. (The v1.0.11 simulation did NOT reproduce this:
 * it modelled the dub arriving in per-utterance bursts with gaps, so
 * its queue rebuilt during every pause. Live streams dub audio
 * continuously — the tail keeps consuming through source pauses.)
 *
 * HOW v6 FIXES IT (simulation before/after, same 420 s harsh world —
 * fast drama dialogue, 1.3x dub, one 30 s continuous stretch, key
 * rotation at 300 s):
 *  1. ANCHOR ON DUB TURN BOUNDARIES, not on slice position. When the
 *     server fires turnComplete, the turn whose dub just ended is
 *     identified by the VAD turn-start marker whose
 *     (tc − wall − counted) is nearest the current lag estimate
 *     (median of recent slice samples). The anchor is EXACT:
 *     (cumDub now, marker audio + counted) — the dub position at the
 *     tc wall IS that turn's end audio. The sample is
 *     tc − marker wall − counted − 200 ms (gap est): the raw
 *     (tc − marker) measures the lag of the turn's END content, i.e.
 *     lag + counted + gap. A dub burst (≥500 ms gap in the arriving
 *     dub) anchors the same way at a turn START: anchor =
 *     (cumDub now, marker audio), sample = burst − marker wall (no
 *     counted correction — a burst marks a dub START). Both feed the
 *     EMA + hold + the turn-pair counter, but a bad one is guarded
 *     (marker residual > 2.5 s, anchor not moving forward, or the
 *     sample inconsistent with the slice median by > 2 s).
 *  2. The slice pairing keeps the v1.0.10/11 machinery (peek-before-
 *     pop, head counting, tail gate — still needed: they keep the
 *     queue from going negative) but the target is now
 *     anchorSpeech + pairedDub × ratio, where ratio is measured PER
 *     TURN from consecutive turnCompletes (exact totals, no backlog
 *     bias) and falls back to the cumulative ratio with a strong
 *     prior (dub ~1.3x speech). The paired slice's wall is
 *     INTERPOLATED inside the slice (using the end wall biased every
 *     sample low by up to 500 ms).
 *  3. The v5 tail formula (anchor from a VAD turn start) survives
 *     ONLY as a bootstrap anchor, before any exact (tc/burst) anchor
 *     has ever existed. It must never overwrite an exact anchor: its
 *     tail integrates the ratio bias (E_n = e + E_{n−1}) until it
 *     clamps to 0 and the anchor sits ~1.5 s too high — that is what
 *     broke v6's first draft (slice error −4.9 s, tc picks two turns
 *     too new, accepted because both lat and lag_est were wrong the
 *     same way).
 *  4. LatencyEma creep 250 ms/window (see LatencyEma.kt): D crawls
 *     toward the EMA target when the deadband would otherwise trap
 *     it at the clamp.
 *
 * SIMULATION (sim1014_v6.py, same event stream for OLD vs NEW):
 *   OLD (v1.0.13): D changes 4× in 7 min (frozen ~3.1 s), last sample
 *     0.5 s, 92 samples/60 s, deferred 1458, dropped 0/0 — reproduces
 *     the device almost exactly (D 3.4 s frozen, last sample 0.6 s,
 *     111 samples/60 s, deferred 1421).
 *   NEW (v1.0.14 v6): D changes 99×, moves in 80/83 5-s steps,
 *     D − ideal (true lag + 2 s) median −742 ms (p10 −1852, p90 −267),
 *     slice-sample error median −71 ms (p10 −1058, p90 +105, n=800),
 *     boundary-sample error median −152 ms (p10 −197, p90 +38, n=26),
 *     deferred 2, dropped 0/0, empty 11, turn pairs 26 total / 5 in
 *     the last 60 s, 121 samples/60 s. Holds per regime: before the
 *     30 s stretch slice −54 ms, during/after it −93 ms, after the
 *     300 s key rotation −52 ms.
 *
 * KEPT from v1.0.10–v1.0.13: continuous slice measurement, 4 s window
 * median → EMA, peek-before-pop, the 160 ms head counted in
 * onSpeechStart, the tail gate (only frames ≥ vadEndThreshold count),
 * tap only the main video, time-based canvas delay, FGS type per mode,
 * no alarm stream anywhere, SpectralGate default ON.
 *
 * All methods are @Synchronized: speech frames arrive on the capture
 * thread, dub chunks on the Live socket thread, turnComplete on the
 * Live thread, manual nudges on the main thread, diagnostics on the
 * level ticker.
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
        /** VAD turn-start markers kept (turns are ~5 s apart, so 12
         * span ~60 s — a turnComplete arrives ~6 s after its turn). */
        const val MAX_MARKERS = 12
        /** A turn/burst sample is trusted only when its marker's
         * implied lag is this close to the lag estimate (half a turn
         * spacing: the lag estimate may be ~1 s biased during
         * bootstrap, the sample-consistency guard is the second line
         * of defence). */
        const val MARKER_RESIDUAL_MS = 2500L
        /** A turn/burst sample must agree with the slice median
         * within this much, or it is a mis-paired boundary. */
        const val SAMPLE_CONSISTENCY_MS = 2000L
        /** Recent slice samples kept for the lag estimate, ms. */
        const val LAG_EST_WINDOW_MS = 20_000L
        /** Estimated gap between a turn's dub end and its
         * turnComplete (the model's inter-turn silence). */
        const val TURN_COMPLETE_GAP_MS = 200L
    }

    /** One 500 ms slice of counted speech: its wall span and its
     * position in the cumulative counted-speech stream. The wall is
     * interpolated inside the slice when pairing (the target rarely
     * lands exactly on a slice boundary). */
    private data class SpeechSlice(
        val startWallMs: Long,
        val startAudioMs: Long,
        val endWallMs: Long,
        val endAudioMs: Long
    )

    /** One VAD turn (speech sentence): its start in wall time and in
     * the counted-speech stream, its counted ms (filled in when the
     * turn ends), and its sequence number (for the per-turn ratio
     * successor chain). */
    private data class TurnMarker(
        val audioMs: Long,
        val wallMs: Long,
        var countedMs: Long,
        var done: Boolean,
        val seq: Long
    )

    private val latencyEma = LatencyEma(maxDelayMs = maxDelayMs)

    /** D — the delay the picture, the original audio and the voice
     * hold all follow. Starts at the Settings base-delay seed. */
    private var delayMs: Long = initialDelayMs.coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)

    /** Hold computed at the latest burst start (for the subtitle
     * delay: the subtitle shows when the voice STARTS PLAYING, i.e.
     * arrival + hold, not at arrival). */
    private var lastHoldMs: Long = 0L

    private var firstAudioSeeded = false

    // ---- cumulative streams (absolute ms since session start) ----
    /** Counted speech ms (head + loud frames only — exactly what
     * Live hears). */
    private var cumSpeechMs = 0L
    /** Arriving dub ms (24 kHz PCM: bytes / 48). */
    private var cumDubMs = 0L

    // ---- continuous slice pairing (v1.0.10, re-anchored in v6) ----
    /** 500 ms speech slices, oldest first. */
    private val speechSlices = ArrayDeque<SpeechSlice>()
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

    // ---- turn-boundary anchoring (v1.0.14) ----
    /** VAD turn-start markers, oldest first. */
    private val markers = ArrayDeque<TurnMarker>()
    private var markerSeq = 0L
    /** The anchor: dub position [anchorDubMs] corresponds to counted
     * speech position [anchorSpeechMs]. Set exactly by a turnComplete
     * / burst, or by the bootstrap tail formula, or stall-exact. */
    private var anchorSpeechMs = 0L
    private var anchorDubMs = 0L
    /** Dub ms consumed by pairing since the anchor was set. */
    private var pairedDubMs = 0L
    /** When the anchor was last set (null = never). */
    private var lastAnchorWallMs: Long? = null
    /** True once any exact (turnComplete/burst/stall) anchor existed;
     * the bootstrap tail formula never runs again after that. */
    private var everExactAnchor = false
    /** Counted ms of the current (open) turn. */
    private var turnCountedMs = 0L
    /** Bootstrap tail formula state (previous turn). */
    private var prevCountedMs = 0L
    private var prevDubStartMs = 0L
    private var curDubStartMs = 0L
    private var prevTailDubMs = 0.0
    /** Recent slice samples (wall, sample) for the lag estimate. */
    private val recentSamples = ArrayDeque<Pair<Long, Long>>()
    /** Last turnComplete that re-anchored (seq, cumDub at the time),
     * for the per-turn ratio successor chain. */
    private var lastTcSeq: Long? = null
    private var lastTcDubMs = 0L
    /** Per-turn ratio measurement: Σ counted speech / Σ dub total
     * over turns paired by consecutive turnCompletes (exact totals,
     * no backlog bias). */
    private var sumCountedMs = 0L
    private var sumTotalDubMs = 0L
    /** When the latest dub chunk arrived (-1 = none / after reset);
     * a chunk ≥500 ms after it starts a new dub burst. */
    private var lastDubChunkAtMs = -1L

    // ---- diagnostics ----
    private var lastSampleMs = 0L
    private var lastSampleAtMs = 0L
    private val sampleTimes60s = ArrayDeque<Long>()
    /** Wall times of accepted turn-boundary pairs (turnComplete /
     * burst) in the last 60 s — the new "cặp mốc/60 s" counter. */
    private val turnPairTimes60s = ArrayDeque<Long>()
    private var droppedSlices = 0
    /** Samples actually DESTROYED as stale (>20 s). In the fixed
     * code a too-small sample is never destroyed (it is deferred),
     * so droppedSamples == droppedTooLarge. */
    private var droppedSamples = 0
    private var emptyPairings = 0
    /**
     * v1.0.11 (hotfix) — WHY a pairing did not become a measurement.
     * [deferredTooSmall]: the oldest slice was too FRESH (<500 ms)
     * to pair — the queue is starved (the 22:35 failure mode: every
     * pairing attempt hit a just-pushed slice). This is a DEFERRAL,
     * not a destruction: the slice stays in the queue, ages, and is
     * paired later, and the dub credit is kept — so it is NOT
     * counted in [droppedSamples]. Counting deferrals as "dropped"
     * would make a healthy starved-then-recovering run show
     * "bỏ 900 mẫu" while valid samples are flowing. [droppedTooLarge]:
     * a slice was popped but its sample was >20 s (stale queue, or
     * the dub running shorter than the original) — a real
     * destruction, counted in [droppedSamples]. A screenshot with
     * 0 valid samples and "nhỏ" growing = the 22:35
     * starved-pairing failure; "lớn" growing = stale queue / the
     * dub running shorter than the original.
     */
    private var deferredTooSmall = 0
    private var droppedTooLarge = 0

    val currentDelayMs: Long get() = delayMs
    val currentHoldMs: Long get() = lastHoldMs

    /** Dub-to-speech ratio (speech ms per dub ms) for advancing the
     * anchor between turn boundaries. Measured per turn from
     * consecutive turnCompletes when available (exact, backlog-free);
     * otherwise the cumulative ratio with a strong prior (dub ~1.3x
     * speech for this language pair, 20 s weight — the cumulative
     * ratio alone is biased high by the latency backlog). */
    private fun ratio(): Double {
        return if (sumTotalDubMs > 0L) {
            ((sumCountedMs + 5000.0) / (sumTotalDubMs + 6500.0))
                .coerceIn(0.5, 1.5)
        } else {
            ((cumSpeechMs + 20000.0) / (cumDubMs + 26000.0))
                .coerceIn(0.5, 1.5)
        }
    }

    /** Current lag estimate: median of the recent slice samples, or
     * D − margin when no sample exists yet. Used only to pick which
     * turn marker a turnComplete/burst belongs to (the turns are ~5 s
     * apart, so the estimate only needs to be within ~2.5 s). */
    private fun lagEstimateMs(): Long {
        if (recentSamples.isNotEmpty()) {
            val sorted = recentSamples.map { it.second }.sorted()
            return sorted[sorted.size / 2]
        }
        return maxOf(MIN_SAMPLE_MS, delayMs - 2000L)
    }

    /**
     * Stall reset, shared by [onSpeechTurnStart] and [pairDubSlices].
     * After >10 s without a new speech slice the model has caught up,
     * so the anchor is exact at the newest speech. The old slices are
     * in a dead frame (the anchor moves), the old markers are done,
     * and any partial dub credit is stale — all are discarded. The
     * cumulative counters and the per-turn ratio sums are session
     * state and survive (the ratio is a property of the language
     * pair, not of this silence).
     */
    private fun resetForStall(nowMs: Long) {
        windowSamples.clear()
        windowStartMs = nowMs
        speechAccumMs = 0L
        dubAccumMs = 0L
        speechSlices.clear()
        markers.clear()
        anchorSpeechMs = cumSpeechMs
        anchorDubMs = cumDubMs
        pairedDubMs = 0L
        lastAnchorWallMs = nowMs
        everExactAnchor = true
        turnCountedMs = 0L
        prevCountedMs = 0L
        prevDubStartMs = 0L
        curDubStartMs = 0L
        prevTailDubMs = 0.0
        lastTcSeq = null
        recentSamples.clear()
        lastDubChunkAtMs = -1L
    }

    /**
     * [ms] of in-speech audio just passed the VAD (called from
     * onSpeechChunk, any chunk size, and from onSpeechStart for the
     * 160 ms head). Every 500 ms pushes a slice. A >10 s silence is
     * handled in [onSpeechTurnStart] (a new turn always starts with
     * onSpeechStart, so the stall is caught there before the new
     * marker is appended) and in [pairDubSlices] (the dub side).
     */
    @Synchronized
    fun onSpeechMs(ms: Long, nowMs: Long) {
        if (ms <= 0L) return
        if (windowStartMs == 0L) windowStartMs = nowMs
        cumSpeechMs += ms
        turnCountedMs += ms
        markers.lastOrNull()?.let { it.countedMs += ms }
        speechAccumMs += ms
        while (speechAccumMs >= SLICE_MS) {
            speechAccumMs -= SLICE_MS
            val endAudioMs = cumSpeechMs - speechAccumMs
            val startAudioMs = endAudioMs - SLICE_MS
            speechSlices.addLast(
                SpeechSlice(
                    startWallMs = nowMs - SLICE_MS,
                    startAudioMs = startAudioMs,
                    endWallMs = nowMs,
                    endAudioMs = endAudioMs
                )
            )
            lastSpeechSliceAtMs = nowMs
            while (speechSlices.size > MAX_SLICES) {
                speechSlices.removeFirst()
                droppedSlices++
            }
        }
    }

    /**
     * v1.0.14 — a VAD turn (speech sentence) started. Called from
     * onSpeechStart BEFORE the 160 ms head is fed to [onSpeechMs], so
     * the marker's audio position is the speech position at the turn
     * start. Records the marker and — only while no exact anchor has
     * ever existed (bootstrap) — sets the fallback anchor from the
     * previous turn's tail. Once an exact anchor exists the anchor
     * advances by the measured ratio and the next turnComplete
     * re-anchors it; the tail formula must never overwrite it (its
     * tail integrates the ratio bias until it clamps to 0 and the
     * anchor sits ~1.5 s too high).
     */
    @Synchronized
    fun onSpeechTurnStart(nowMs: Long) {
        // A >10 s silence before this turn: reset the stale state
        // FIRST (before the new marker is appended, or the reset
        // would wipe it).
        if (lastSpeechSliceAtMs > 0L &&
            nowMs - lastSpeechSliceAtMs > STALL_MS
        ) {
            resetForStall(nowMs)
        }
        markerSeq++
        markers.addLast(
            TurnMarker(
                audioMs = cumSpeechMs,
                wallMs = nowMs,
                countedMs = 0L,
                done = false,
                seq = markerSeq
            )
        )
        while (markers.size > MAX_MARKERS) markers.removeFirst()
        if (!everExactAnchor) {
            val r = ratio()
            var tailAudioMs = 0L
            var tailDubMs = 0.0
            if (prevCountedMs > 0L) {
                val totalDubMs = prevCountedMs / r
                val arrivedDubMs = cumDubMs - prevDubStartMs
                // + prevTailDubMs: the dub that arrived since the
                // previous turn's start includes the tail remnant
                // already present at that start; without it the tail
                // is underestimated by that remnant every turn
                // (systematic ~−1.1 s sample bias).
                tailDubMs = maxOf(
                    0.0,
                    totalDubMs - arrivedDubMs + prevTailDubMs
                )
                tailAudioMs = (tailDubMs * r).toLong()
            }
            prevTailDubMs = tailDubMs
            anchorSpeechMs = maxOf(0L, cumSpeechMs - tailAudioMs)
            anchorDubMs = cumDubMs
            pairedDubMs = 0L
            lastAnchorWallMs = nowMs
        }
        prevCountedMs = turnCountedMs
        prevDubStartMs = curDubStartMs
        curDubStartMs = cumDubMs
        turnCountedMs = 0L
    }

    /**
     * v1.0.14 — a VAD turn ended. Called from onSpeechEnd. Turns end
     * in order, but a turn's 'end' can arrive after the next turn's
     * 'start' (a pause shorter than the 700 ms VAD tail), so the
     * marker completed is the OLDEST not-yet-done one, not the last.
     */
    @Synchronized
    fun onSpeechTurnEnd() {
        for (marker in markers) {
            if (!marker.done) {
                marker.done = true
                break
            }
        }
    }

    /**
     * One translated-audio chunk arrived from Live. Returns the hold
     * (ms) VoicePlayer must wait before playing this chunk: 0 means
     * play at once (already past utteranceStart + D).
     *
     * [dubMs] is the chunk's duration (24 kHz PCM: bytes / 48).
     * [firstAudioSentAtMs] is when the first source audio of the
     * session was sent to Live (0 when none yet) — used once, for the
     * ALDA first-audio seed.
     */
    @Synchronized
    fun onDubAudioArrival(
        nowMs: Long,
        dubMs: Long,
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
        // v1.0.14 — dub burst: a chunk arriving ≥500 ms after the
        // previous one starts a new turn's dub. It anchors exactly
        // (see onDubBurst) and gives a boundary sample; the slice
        // pairing below is still the main measurement.
        val isBurst = lastDubChunkAtMs >= 0L &&
            nowMs - lastDubChunkAtMs >= 500L
        lastDubChunkAtMs = nowMs
        cumDubMs += dubMs
        if (isBurst) {
            hold = onDubBurst(nowMs)
            lastHoldMs = hold
        }
        // Continuous slice pairing (v1.0.10, re-anchored in v6).
        pairDubSlices(dubMs, nowMs)
        // 4 s window flush: median of the window's samples -> EMA.
        flushWindowIfDue(nowMs)
        return hold
    }

    /**
     * v1.0.14 — the server finished a turn (turnComplete). [nowMs] is
     * the arrival wall time; [audioPlayed] is whether any dub audio
     * arrived during this model turn (the service's
     * audioPlayedThisTurn — a noise-only turn with no dub must not
     * re-anchor: its dub position never advanced for this turn).
     * Identifies the turn whose dub just ended, feeds its boundary
     * sample, re-anchors exactly, and measures the per-turn ratio
     * from consecutive turnCompletes. Guarded: a mis-paired boundary
     * (wrong marker, or a sample inconsistent with the slice median)
     * is skipped, never fed.
     */
    @Synchronized
    fun onDubTurnComplete(nowMs: Long, audioPlayed: Boolean) {
        if (!audioPlayed) return
        val lagEst = lagEstimateMs()
        val marker = selectMarker(nowMs, lagEst, countedCorrection = true)
            ?: return
        val anchorSpeechNew = marker.audioMs + marker.countedMs
        // A late/duplicate turnComplete for an older turn must not
        // move the anchor backwards. Equality is fine: the bootstrap
        // fallback may already have set this exact boundary, and the
        // turnComplete confirms it (its sample + ratio measurement
        // still count).
        if (anchorSpeechNew < anchorSpeechMs) return
        // Raw (tc − marker) measures the lag of the turn's END
        // content: lag + counted + gap. Subtract both to get the lag.
        val latency = nowMs - marker.wallMs - marker.countedMs -
            TURN_COMPLETE_GAP_MS
        if (kotlin.math.abs(latency - lagEst) > SAMPLE_CONSISTENCY_MS) return
        // Per-turn ratio measurement (successor chain only): the dub
        // between two consecutive turnCompletes is exactly the later
        // turn's total. Guarded against a mis-paired marker (a total
        // wildly off the expected 1.3x is skipped, not trusted).
        lastTcSeq?.let { lastSeq ->
            if (marker.seq == lastSeq + 1L) {
                val totalDubMs = cumDubMs - lastTcDubMs
                val expected = marker.countedMs * 1.3
                if (totalDubMs >= 0.5 * expected &&
                    totalDubMs <= 2.0 * expected
                ) {
                    sumCountedMs += marker.countedMs
                    sumTotalDubMs += totalDubMs
                }
            }
        }
        lastTcSeq = marker.seq
        lastTcDubMs = cumDubMs
        recordTurnPair(nowMs)
        feedSample(latency, nowMs)
        reanchor(anchorSpeechNew, nowMs, exact = true)
    }

    /**
     * v1.0.14 — a dub burst started (a chunk after ≥500 ms of dub
     * silence). The chunk is the START of a turn's dub: the anchor is
     * the marker's start audio, and the sample is burst − marker wall
     * (no counted correction — a burst marks a dub START, unlike a
     * turnComplete which marks a dub END). Returns this burst's hold =
     * max(0, D − latency).
     */
    private fun onDubBurst(nowMs: Long): Long {
        val lagEst = lagEstimateMs()
        val marker = selectMarker(nowMs, lagEst, countedCorrection = false)
            ?: return lastHoldMs
        if (marker.audioMs < anchorSpeechMs) return lastHoldMs
        val latency = nowMs - marker.wallMs
        if (kotlin.math.abs(latency - lagEst) > SAMPLE_CONSISTENCY_MS) {
            return lastHoldMs
        }
        recordTurnPair(nowMs)
        feedSample(latency, nowMs)
        reanchor(marker.audioMs, nowMs, exact = true)
        return (delayMs - latency).coerceAtLeast(0L)
    }

    /** Pick the turn marker whose implied lag is nearest [lagEst].
     * With countedCorrection the implied lag is (now − wall −
     * counted) — a turnComplete marks a dub END; without it the
     * implied lag is (now − wall) — a burst marks a dub START. Only
     * completed markers (counted known) are eligible for a
     * turnComplete; any marker for a burst. Null when no marker is
     * within [MARKER_RESIDUAL_MS] of the estimate. */
    private fun selectMarker(
        nowMs: Long,
        lagEst: Long,
        countedCorrection: Boolean
    ): TurnMarker? {
        var best: TurnMarker? = null
        var bestResidual: Long? = null
        for (marker in markers) {
            if (countedCorrection && (!marker.done || marker.countedMs <= 0L)) {
                continue
            }
            var implied = nowMs - marker.wallMs
            if (countedCorrection) implied -= marker.countedMs
            val residual = kotlin.math.abs(implied - lagEst)
            if (bestResidual == null || residual < bestResidual) {
                best = marker
                bestResidual = residual
            }
        }
        if (best == null || (bestResidual ?: Long.MAX_VALUE) > MARKER_RESIDUAL_MS) {
            return null
        }
        return best
    }

    private fun reanchor(anchorSpeechNew: Long, nowMs: Long, exact: Boolean) {
        anchorSpeechMs = anchorSpeechNew
        anchorDubMs = cumDubMs
        pairedDubMs = 0L
        lastAnchorWallMs = nowMs
        if (exact) everExactAnchor = true
    }

    private fun recordTurnPair(nowMs: Long) {
        turnPairTimes60s.addLast(nowMs)
        while (turnPairTimes60s.isNotEmpty() &&
            nowMs - turnPairTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            turnPairTimes60s.removeFirst()
        }
    }

    /**
     * Feed one latency sample (slice or boundary) into the 4 s
     * window and the recent-samples buffer. Returns the hold for
     * this sample: max(0, D − sample).
     */
    private fun feedSample(sampleMs: Long, nowMs: Long): Long {
        lastSampleMs = sampleMs
        lastSampleAtMs = nowMs
        recentSamples.addLast(nowMs to sampleMs)
        while (recentSamples.isNotEmpty() &&
            nowMs - recentSamples.first().first > LAG_EST_WINDOW_MS
        ) {
            recentSamples.removeFirst()
        }
        if (windowStartMs == 0L) windowStartMs = nowMs
        windowSamples.add(sampleMs)
        sampleTimes60s.addLast(nowMs)
        while (sampleTimes60s.isNotEmpty() &&
            nowMs - sampleTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            sampleTimes60s.removeFirst()
        }
        return (delayMs - sampleMs).coerceAtLeast(0L)
    }

    /**
     * Pair arriving dub ms against the speech slices, anchored on the
     * last turn boundary. One sample per 500 ms of dub. The target
     * speech position advances by pairedDub × ratio (the dub is ~1.3x
     * the source, so positional FIFO — one slice per 500 ms of dub —
     * drifts and pins every sample at the floor). The paired slice's
     * wall is interpolated inside the slice. A stalled source (no new
     * speech slice for > [STALL_MS]) stops measuring entirely: the
     * queue is cleared and the anchor resets stall-exact (the model
     * has caught up: the dub is at the newest speech).
     *
     * v1.0.11 (hotfix) — PEEK BEFORE POPPING. The old code popped the
     * oldest slice first and THEN checked the range, so when the
     * queue had drained to (near) zero it popped a slice pushed
     * moments ago, got a <500 ms sample, dropped it AND consumed the
     * slice — the queue could never rebuild, and every later sample
     * was dropped the same way (the 22:35 screenshot: 133 dropped,
     * 0 valid, queue empty, VAD speaking, dub playing). Now a
     * too-fresh target slice is LEFT in the queue (its dub credit is
     * kept, not spent) so it ages and the queue rebuilds; pairing
     * resumes when the target is old enough. A too-old slice is still
     * popped and dropped (it is stale — try the next-oldest). A
     * too-fresh pairing is a DEFERRAL (counted in deferredTooSmall,
     * not in droppedSamples): the slice survives and pairs later.
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
            // Source silent for a while: stop measuring, drop stale
            // state, anchor stall-exact (the dub has caught up).
            resetForStall(nowMs)
            return
        }
        dubAccumMs += dubMs
        while (dubAccumMs >= SLICE_MS) {
            val target = anchorSpeechMs +
                (pairedDubMs + SLICE_MS) * ratio()
            val hasTarget = speechSlices.any { it.endAudioMs >= target }
            if (!hasTarget) {
                // Dub flowing while the source is silent (a long
                // translated sentence): no sample, and crucially no
                // pairing against anything else. The dub credit is
                // spent (this 500 ms of dub has no speech to pair
                // with) so it cannot pile up and pair against a
                // FUTURE sentence.
                dubAccumMs -= SLICE_MS
                pairedDubMs += SLICE_MS
                emptyPairings++
                continue
            }
            while (speechSlices.isNotEmpty() &&
                speechSlices.first().endAudioMs < target
            ) {
                speechSlices.removeFirst()
            }
            val slice = speechSlices.first()
            val spanAudio = maxOf(1L, slice.endAudioMs - slice.startAudioMs)
            val spanWall = maxOf(1L, slice.endWallMs - slice.startWallMs)
            val wall = slice.startWallMs +
                (target - slice.startAudioMs) * spanWall / spanAudio
            val sample = nowMs - wall
            if (sample < MIN_SAMPLE_MS) {
                // Too fresh to pair: the queue is starved and this
                // slice was pushed moments ago. Do NOT pop it and do
                // NOT spend the dub credit — it stays, ages, and the
                // queue rebuilds. Counted as "nhỏ" (a DEFERRAL, not
                // a drop) so the diagnostic shows the starved regime
                // instead of hiding it; it is deliberately NOT
                // counted in droppedSamples, because no sample was
                // destroyed — the same slice pairs later, when old
                // enough. (Counting deferrals as drops made a
                // recovering run report "bỏ 900 mẫu" while valid
                // samples were flowing.)
                deferredTooSmall++
                break
            }
            // Old enough to pair: consume the slice and the dub credit.
            speechSlices.removeFirst()
            dubAccumMs -= SLICE_MS
            pairedDubMs += SLICE_MS
            if (sample > MAX_SAMPLE_MS) {
                // Stale slice (a long dub silence let the queue go
                // stale, or the clocks drifted): drop JUST this
                // sample and try the next-oldest.
                droppedSamples++
                droppedTooLarge++
                continue
            }
            feedSample(sample.toLong(), nowMs)
        }
    }

    /**
     * Every [WINDOW_MS] the window's median feeds the EMA and moves
     * D (when the change is >= the 500 ms deadband, or by the 250 ms
     * creep toward the target when the deadband would trap D at the
     * clamp). A window whose newest sample is older than [STALL_MS]
     * is stale and dropped. An empty window just restarts the clock.
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
     * One-line diagnostics for the HUD (under the "Trễ hình" number)
     * and the "Xem lỗi Live" dialog. Refreshed every second by the
     * service. Reports numbers, so a frozen delay is diagnosable
     * from a screenshot instead of "không cập nhật".
     *
     * v1.0.14 — "cặp mốc N/60 s": turn-boundary pairs accepted in
     * the last 60 s (turnComplete / burst). It tells a frozen-delay
     * screenshot apart: pairs > 0 means the boundary measurement is
     * alive (the anchor is exact), pairs = 0 with samples flowing
     * means only the slice pairing is running.
     */
    @Synchronized
    fun diagnostics(nowMs: Long): String {
        while (sampleTimes60s.isNotEmpty() &&
            nowMs - sampleTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            sampleTimes60s.removeFirst()
        }
        while (turnPairTimes60s.isNotEmpty() &&
            nowMs - turnPairTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            turnPairTimes60s.removeFirst()
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
        val droppedPart = if (droppedSlices > 0 || droppedSamples > 0 ||
            deferredTooSmall > 0 || droppedTooLarge > 0
        ) {
            // v1.0.11 — the rejection breakdown. "nhỏ … hoãn" =
            // pairings DEFERRED because the oldest slice was too fresh
            // (<500 ms, starved queue): the slice survives and pairs
            // later, so this is NOT part of the "bỏ … mẫu" count.
            // "lớn" = samples DESTROYED because the slice was stale
            // (>20 s); those ARE the "bỏ … mẫu". A screenshot with
            // 0 valid samples and "nhỏ" growing = the 22:35
            // starved-pairing failure; "lớn" growing = stale queue /
            // the dub running shorter than the original.
            " · bỏ %d lát/%d mẫu (nhỏ %d hoãn/lớn %d)".format(
                droppedSlices, droppedSamples, deferredTooSmall, droppedTooLarge
            )
        } else {
            ""
        }
        return "D %s s · %s · %d mẫu/60 s · cặp mốc %d/60 s · chờ %d lát · hold %.1f s · %s%s".format(
            delaySec, samplePart, sampleTimes60s.size, turnPairTimes60s.size,
            speechSlices.size, lastHoldMs / 1000.0, vadPart, droppedPart
        )
    }

    /** Fresh pairing after a (re)connect: the slice state restarts;
     * the anchors, markers, ratio, EMA and D are kept — the delay
     * does not jump on reconnect. (The first chunk after the
     * reconnect is not treated as a burst: it continues the same
     * turn's dub, it does not start a new one.) */
    @Synchronized
    fun resetForReconnect() {
        lastDubChunkAtMs = -1L
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
        lastDubChunkAtMs = -1L
        lastHoldMs = 0L
        cumSpeechMs = 0L
        cumDubMs = 0L
        speechSlices.clear()
        speechAccumMs = 0L
        lastSpeechSliceAtMs = 0L
        dubAccumMs = 0L
        windowSamples.clear()
        windowStartMs = 0L
        markers.clear()
        markerSeq = 0L
        anchorSpeechMs = 0L
        anchorDubMs = 0L
        pairedDubMs = 0L
        lastAnchorWallMs = null
        everExactAnchor = false
        turnCountedMs = 0L
        prevCountedMs = 0L
        prevDubStartMs = 0L
        curDubStartMs = 0L
        prevTailDubMs = 0.0
        recentSamples.clear()
        lastTcSeq = null
        lastTcDubMs = 0L
        sumCountedMs = 0L
        sumTotalDubMs = 0L
        lastSampleMs = 0L
        lastSampleAtMs = 0L
        sampleTimes60s.clear()
        turnPairTimes60s.clear()
        droppedSlices = 0
        droppedSamples = 0
        deferredTooSmall = 0
        droppedTooLarge = 0
        emptyPairings = 0
    }
}
