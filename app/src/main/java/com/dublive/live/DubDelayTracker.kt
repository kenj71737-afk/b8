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
 * v1.0.17 — PLAY-ANCHORED MEASUREMENT. Why the old measurement
 * was systematically wrong, and what this version changes.
 *
 * THE OLD ERROR (measured on arrival, never on sound): a sample was
 * (dub chunk ARRIVAL wall − source slice wall), and the voice was
 * held to play at (arrival + hold) with hold = D − arrival latency.
 * But a chunk taken from the hold queue only SOUNDS once the
 * AudioTrack drains the frames queued ahead of it — the playback
 * pipeline (buffer backlog + hardware, 100–300 ms on this phone).
 * The old code assumed that pipeline was 0 and hid it in a fixed
 * 2000 ms margin. The voice therefore always played (pipeline)
 * late against the picture, and NO value of D could fix it: the
 * hold moves the voice by exactly as much as D moves the picture,
 * so the offset is D-independent. The VAD added a second systematic
 * error: the turn marker was stamped at VAD TRIGGER time, ~180 ms
 * after the speaker actually started (the 160 ms head + one 20 ms
 * frame hop), so every boundary sample underestimated the lag by
 * ~180 ms. And inside one long sentence the dub (1.3x the source)
 * drifts away from the picture continuously — two boundary samples
 * cannot see that drift.
 *
 * WHY THE PLAY-ANCHORED SAMPLE MUST NOT FEED THE EMA (the
 * circularity trap — verified in sim1017.py before building):
 * with hold = D − arrival latency, a play-anchored sample is
 *   play − source = D + pipeline
 * by construction — it measures D itself, not the translation
 * latency. Feeding it to the EMA (D = EMA + margin) makes D chase
 * itself and run away by (pipeline + margin) every EMA time
 * constant until it clamps at 20 s (the sim shows exactly that).
 * The EMA therefore KEEPS its arrival-anchored samples — they are
 * the only D-independent measurement of the translation latency.
 * The play-anchored measurement instead does three jobs that the
 * arrival measurement cannot:
 *   1. It measures the PIPELINE per burst (play − arrival − hold).
 *      The hold then SUBTRACTS the pipeline EMA
 *      (hold = D − arrival latency − pipeline), so the voice
 *      sounds at (utterance start + D) instead of (D + pipeline).
 *      The margin becomes dynamic — pipeline + ~400 ms jitter,
 *      clamped 500–2000 ms — the headroom that keeps the hold >= 0
 *      despite latency variance.
 *   2. It gives the CLOSED-LOOP sync error: (play − source) − D
 *      at every burst — what the user actually sees (lips vs
 *      voice), shown in the diagnostics as "lệch hình ±X ms".
 *      In steady state it is ~0; a persistent positive value
 *      means the hold saturated (latency > D) and D must grow.
 *   3. It gives CONTINUOUS in-turn samples: every paired 500 ms
 *      dub slice gets a play time (burst play + its offset inside
 *      the burst), so the drift inside a long sentence shows up as
 *      a growing play-anchored sample instead of only at the two
 *      ends. These are diagnostics only (the EMA still eats the
 *      arrival-anchored slices — same drift, D-independent).
 * The frame check (item 6) is the second closed loop, on the
 * PICTURE side: at each burst play the service reads the video's
 * currentTime through JS and compares the frame the canvas should
 * be showing against the spoken sentence. v1.0.17 only REPORTS the
 * residual ("khung hình ±X ms"); it never steers D by it — that
 * waits for v1.0.18, once real device numbers exist.
 *
 * v1.0.18 — TURN-BOUNDARY ANCHORING WAS DEAD (device test of
 * v1.0.17, 18dj.tv, 27/11 23:07 + 23:13, TWO SEPARATE sessions —
 * the user turned dubbing OFF then ON between the photos, so every
 * counter reset: photo 2 has no "bỏ 49 lát" because it is a new
 * session, not because the bug went away). "cặp mốc 0/60 s" in BOTH
 * sessions while slice pairing still ran (6–14 samples/60 s), so
 * "lệch hình —" and "khung hình —" could never show a number.
 * Three mechanisms, all fixed here (proven by sim1018.py first):
 *  1. MERGED VAD TURNS (the real cause): a VAD turn ends only
 *     after ~700 ms of continuous silence, but drama pauses are
 *     100–400 ms, so VAD turns merge many lines into one long
 *     marker; a turnComplete then finds no DONE marker and bursts
 *     sample against a marker that started minutes ago. FIX:
 *     MARKER SPLITTING — a ≥300 ms silence inside a VAD turn
 *     closes the current marker and starts a new one (onSpeechMs).
 *  2. The bootstrap race + the lagEst lock: while no exact anchor
 *     existed, every turn start re-ran the v5 tail formula, putting
 *     the anchor ahead of every marker (so every boundary was
 *     vetoed as "backwards"), and the slice-median lagEst was
 *     floor-pinned at ~0.6 s, so a true boundary sample ~2.4–3.0 s
 *     failed the 2 s consistency guard. FIX: the anchor-backwards
 *     guard only applies once an EXACT anchor exists; the marker is
 *     picked by the ANCHOR-IMPLIED speech position
 *     (S = anchor_s + (cumDub − anchor_u) × ratio), never by
 *     nearness to the slice median; the lag estimate prefers D −
 *     margin when the slice samples are stale (>10 s) or disagree
 *     with D − margin by >1 s; the first boundary bootstraps from
 *     the SEED LAG (first dub arrival − first line start ≈ true
 *     lag L0), and the seed-lag marker is also the fallback when
 *     the anchor path finds nothing (a wide anchor net only
 *     catches stale markers); bursts get a second opinion against
 *     the D-based lag while D is trusted (|sample − (D − margin)|
 *     > 1500 ms rejects), because the anchor-implied check is
 *     tautological and passes bursts 2 s too old.
 *  3. The pipeline spike (1438 ms, TB 444/656 ms): the AudioTrack
 *     buffer was minBuf×4 (64 KB = 1365 ms at minBuf 16 KB) and the
 *     player loop wrote a whole chunk in ONE blocking write while
 *     confirmPlayStart() only ran at the top of the loop — the
 *     playhead poll was starved for the whole write. FIX (in
 *     VoicePlayer): buffer = max(minBuf, min(minBuf×2, 12000 B))
 *     (cap 250 ms, never below the framework minimum); writes
 *     sliced to ≤2400 B (50 ms) with confirmPlayStart() between
 *     slices; the backlog ahead of a burst's first frame is read
 *     from the playhead before writing and reported ("chờ Z ms").
 *  4. "Bỏ 49 lát" (session 1): slices piled up unconsumed while
 *     the anchor raced ahead — fixed by (1)+(2); MAX_SLICES stays
 *     40. HOLD REFRESH: every ACCEPTED turnComplete now recomputes
 *     hold = D − latest latency − pipeline (was: hold only updated
 *     at bursts, ~4/420 s, so the actual line-start error stayed
 *     ~362 ms); chunks already queued keep their due times. The
 *     hold is also MONOTONIC (a chunk's due never precedes the
 *     previous chunk's due). Burst gap 500 → 300 ms. Per-reason
 *     rejection counters (tc/burst × no-marker / no-audio /
 *     anchor-back / consistency / fallback) are shown in the
 *     diagnostics. stop() now resets firstAudioSeeded + the
 *     LatencyEma + the boundary state (the 2-session reset check).
 *
 * SIMULATION (sim1018.py, device world: 18dj.tv drama, lines
 * 1.5–3.0 s, pauses 100–400 ms, dub 1.46x, true lag 2.4 s):
 *  speed=2.5, minBuf=4096: pairs/60 s 17 → 21, dropped slices
 *  0 → 0, boundary-sample |err| median 0 ms, measured pipeline
 *  TB 52 → 50 ms (max 652 ms), actual line-start |err| median
 *  steady 22 → 12 ms, fresh-hold 7/393 → 4/474 ms — GATE PASS.
 *  speed=1.5, minBuf=16384: pairs 15 → 23, boundary err median
 *  0 ms, pipeline TB 273 → 233 ms (max 890 ms), fresh-hold steady
 *  184/513 → 233/542 ms — GATE PASS. tc OFF: 60 pairs from bursts
 *  alone. v6 regression: fresh-hold median 17 → 114 ms (< 300),
 *  actual line-start steady 1491 → 366 ms, pairs 0 → 3 — PASS.
 *  Known limit: the measured pipeline is the BURST-FIRST chunk's
 *  only (the loop is idle at burst starts, so it reads ~50 ms
 *  while the TRUE pipeline over all chunks is ~660 ms) — the
 *  tracker under-measures the pipeline, costing ~186 ms of
 *  in-turn drift (slice-play error); acceptable, recorded, not
 *  forced. Dropped audio (hold queue, >500 ms overdue) is
 *  37–43% at speed 2.5 — the voice skips words instead of falling
 *  behind; a catch-up (time-stretch) is a v1.0.19 question.
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
        /**
         * v1.0.17 — VAD DETECTION LAG: the VAD fires onSpeechStart
         * only after 8 loud frames (8 x 20 ms = 160 ms head) have
         * accumulated, plus one 20 ms frame hop of chunking/
         * processing. The speaker really started ~180 ms BEFORE
         * the trigger wall time. The service subtracts this from
         * the marker wall (and from the utterance-start stamps) so
         * every boundary sample measures from the true sentence
         * start; the slice walls need no correction (they are
         * capture times, stamped when the audio was recorded, not
         * when the VAD recognised it).
         */
        const val VAD_DETECTION_LAG_MS = 180L
        /** v1.0.17 — pipeline EMA seed (ms) before any burst has
         * been measured. The sim's pipeline model is 100–300 ms. */
        const val PIPELINE_SEED_MS = 150L
        /** v1.0.17 — jitter headroom inside the dynamic margin. */
        const val PIPELINE_JITTER_MS = 400L
        /** v1.0.17 — dynamic margin clamp (pipeline + jitter). */
        const val MARGIN_MIN_MS = 500L
        const val MARGIN_MAX_MS = 2000L
        /** v1.0.17 — pairing-log capacity (500 ms units). */
        const val MAX_PAIRING_LOG = 240
        /** v1.0.17 — burst/marker video-time records kept. */
        const val MAX_VIDEO_TIME_RECORDS = 12
        /** v1.0.18 — a dub chunk ≥300 ms after the previous one
         * starts a new burst (was 500 ms: the model's
         * inter-sentence pause is often 200-400 ms). */
        const val BURST_GAP_MS = 300L
        /** v1.0.18 — a silence ≥300 ms inside a VAD turn is a line
         * boundary (marker splitting): drama pauses are 100-400 ms,
         * far below the VAD's 700 ms tail. */
        const val SPLIT_SILENCE_MS = 300L
        /** v1.0.18 — seed-lag marker residual (the seed lag is a
         * good reference only while the lag is still near L0). */
        const val SEED_MARKER_RESIDUAL_MS = 3000L
        /** v1.0.18 — D is a trusted second opinion for this long
         * after the last accepted boundary sample. */
        const val D_TRUSTED_WINDOW_MS = 30_000L
        /** v1.0.18 — recent slice samples are stale after this. */
        const val LAG_EST_STALE_MS = 10_000L
        /** v1.0.18 — the slice median must agree with D − margin
         * within this to be trusted as the lag estimate. */
        const val LAG_EST_AGREE_MS = 1000L
        /** v1.0.18 — burst D-based cross-check (while D is
         * trusted): reject when |sample − (D − margin)| exceeds
         * this. */
        const val BURST_D_CHECK_MS = 1500L
        /** v1.0.18 — seed-lag fallback D-based cross-check (looser:
         * the fallback IS the recovery). */
        const val SEED_FB_D_CHECK_MS = 2500L
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
     * a chunk ≥300 ms after it starts a new dub burst. */
    private var lastDubChunkAtMs = -1L

    // ---- v1.0.18 — seed-lag bootstrap + D-trust + counters ----
    /** Seed lag (first dub arrival − first line start ≈ true lag
     * L0), set once at the first-audio seed; null = not seeded. */
    private var seedLagMs: Long? = null
    /** Wall time of the last loud speech chunk (for marker
     * splitting: a ≥300 ms gap inside a VAD turn is a line
     * boundary). */
    private var lastLoudAtMs = 0L
    /** Markers created by splitting (diagnostics). */
    private var splitMarkers = 0
    /** Last accepted boundary sample + when (for the D-trust
     * check and the burst cross-check). */
    private var lastBoundarySampleMs = 0L
    private var lastBoundaryAtMs = 0L
    /** Due time of the previous dub chunk (monotonic hold). */
    private var lastDueMs = 0L
    /** Last burst's backlog ahead at write time (diagnostics). */
    private var lastBacklogMs: Long? = null
    /** Per-reason boundary rejection counters (v1.0.18): recv =
     * turnCompletes/bursts seen; ok = accepted; noMarker = no
     * marker within residual; noAudio = turnComplete with no dub
     * audio; anchorBack = vetoed by the anchor-backwards guard;
     * consist = consistency/range rejection; fallback = recovered
     * via the seed-lag marker. */
    private var tcRecv = 0
    private var tcNoAudio = 0
    private var tcNoMarker = 0
    private var tcAnchorBack = 0
    private var tcConsistency = 0
    private var tcOk = 0
    private var tcFallback = 0
    private var burstRecv = 0
    private var burstNoMarker = 0
    private var burstAnchorBack = 0
    private var burstConsistency = 0
    private var burstOk = 0
    private var burstFallback = 0

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

    // ---- v1.0.17 — play-anchored measurement ----
    /** One dub burst: the record the VoicePlayer's play report is
     * matched against. [markerWallMs] is the TRUE sentence start
     * (VAD lag already subtracted by the service); null when the
     * burst could not be paired with a turn marker (its play still
     * measures the pipeline, it just yields no sync sample). */
    private data class BurstRecord(
        val seq: Long,
        val arrivalWallMs: Long,
        val holdMs: Long,
        var markerSeq: Long?,
        var markerWallMs: Long?,
        val startCumDubMs: Long
    )

    /** One paired 500 ms unit: its dub-stream position, the source
     * wall it was paired with, and the burst it arrived in. At
     * burst play time the play-anchored sample is
     * (burstPlay + (dubCum − burstStartCum)) − sourceWall. */
    private data class PairingRecord(
        val dubCumMs: Long,
        val sourceWallMs: Long,
        val burstSeq: Long
    )

    /** Burst sequence: incremented at every burst start (and at the
     * session's first chunk, which the old isBurst test does not
     * count as a burst). The service reads it after
     * [onDubAudioArrival] and notes the burst to the VoicePlayer
     * before the first chunk is played. */
    private var burstSeq = 0L
    val lastBurstSeq: Long get() = burstSeq
    val lastMarkerSeq: Long get() = markerSeq
    /** Bursts whose play report has not arrived yet (oldest first).
     * Pruned when a newer burst's report arrives. */
    private val pendingBursts = ArrayDeque<BurstRecord>()
    /** Recent paired units, oldest first (for the play-anchored
     * slice samples at burst play time). */
    private val pairingLog = ArrayDeque<PairingRecord>()
    /** Playback pipeline EMA (ms): play − (arrival + hold), EMA 0.3
     * over measured bursts. Seeded at 150 ms; survives reconnects
     * (it is a property of the device's audio path, not of the
     * Live socket). The hold subtracts it and the margin is built
     * from it — see [dynamicMarginMs]. */
    private var pipelineEmaMs: Long? = null
    /** Last measured pipeline (ms) and whether it was the
     * queue-based fallback (getTimestamp unavailable) rather than a
     * playhead measurement. */
    private var lastPipelineMs: Long? = null
    private var lastPipelineEstimated = false
    /** Closed-loop sync error at the last burst play:
     * (play − marker wall) − D. Positive = the voice sounded late
     * against the picture (hold saturated or pipeline
     * underestimated); ~0 in steady state. Null = no burst has
     * played yet. */
    private var lastSyncErrorMs: Long? = null
    /** Last burst's play wall and its play-anchored slice sample. */
    private var lastBurstPlayWallMs: Long? = null
    private var lastPlaySliceSampleMs: Long? = null
    /** Drift inside the last burst: last minus first play-anchored
     * slice sample (positive = the voice drifts later than the
     * picture as the sentence goes on — the 1.3x dub stretching). */
    private var playDriftMs: Long? = null
    /** Video-time records for the frame check (item 6): the video's
     * currentTime at the marker wall / at the burst play wall,
     * already corrected to the event wall by the service. */
    private val markerVideoTimes = ArrayDeque<Pair<Long, Double>>()
    private val burstVideoTimes = ArrayDeque<Pair<Long, Double>>()
    /** Recent burst seq -> marker seq, for the frame residual
     * (the pending burst record is gone by the time the JS replies
     * arrive). */
    private val recentBurstMarkers = ArrayDeque<Pair<Long, Long>>()
    /** Frame residual at the last burst play (ms): (video time now
     * − marker video time) − D. Positive = the shown frame is AHEAD
     * of the spoken sentence. Report-only in v1.0.17. */
    private var lastFrameResidualMs: Long? = null

    /** v1.0.17 — current pipeline estimate (seed before any
     * measurement). */
    private fun pipelineMs(): Long = pipelineEmaMs ?: PIPELINE_SEED_MS

    /** v1.0.17 — dynamic margin: pipeline + jitter, 500–2000 ms.
     * This is the headroom that keeps the hold >= 0: D must exceed
     * (arrival latency + pipeline) for the voice to be schedulable
     * at (utterance start + D) at all. */
    private fun dynamicMarginMs(): Long =
        (pipelineMs() + PIPELINE_JITTER_MS).coerceIn(MARGIN_MIN_MS, MARGIN_MAX_MS)

    /** v1.0.17 — push the current dynamic margin into the EMA
     * (called after every pipeline update and at the first seed). */
    private fun applyDynamicMargin() {
        latencyEma.setMarginMs(dynamicMarginMs())
    }

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

    /** v1.0.18 — D-based lag: D − dynamic margin. The delay is the
     * EMA of the accepted boundary/slice samples + margin, so D −
     * margin is the best available estimate of the current lag
     * whenever the slice median is untrustworthy. */
    private fun dBasedLagMs(): Long =
        maxOf(MIN_SAMPLE_MS, delayMs - dynamicMarginMs())

    /** v1.0.18 — is D a reliable second opinion right now? The last
     * accepted boundary sample agrees with D − margin within 1 s
     * and is recent. False during a lag transient (D chasing), so
     * the D-based checks are skipped and cannot block recovery. */
    private fun isDTrusted(nowMs: Long): Boolean =
        lastBoundaryAtMs != 0L && nowMs - lastBoundaryAtMs <= D_TRUSTED_WINDOW_MS &&
            kotlin.math.abs(lastBoundarySampleMs - dBasedLagMs()) <= 1000L

    /** Current lag estimate: median of the recent slice samples —
     * but only when the samples are fresh (newest within 10 s) and
     * agree with D − margin within 1 s. Otherwise D − margin.
     * v1.0.17 trusted the slice median unconditionally, and the
     * median is floor-pinned at ~0.6 s exactly when the tracker is
     * blind (the v1.0.13 failure mode, still visible on the 27/11
     * device photos: last sample 0.6 s). Used only as the fallback
     * for the anchor-implied consistency check when no slice covers
     * the implied speech position. */
    private fun lagEstimateMs(nowMs: Long): Long {
        val dBased = dBasedLagMs()
        if (recentSamples.isNotEmpty()) {
            val newestWall = recentSamples.last().first
            if (nowMs - newestWall <= LAG_EST_STALE_MS) {
                val sorted = recentSamples.map { it.second }.sorted()
                val median = sorted[sorted.size / 2]
                if (kotlin.math.abs(median - dBased) <= LAG_EST_AGREE_MS) {
                    return median
                }
            }
        }
        return dBased
    }

    /** v1.0.18 — the speech position the anchor implies for the
     * current dub position, or null while bootstrapping (no exact
     * anchor yet). S = anchor_s + (cumDub − anchor_u) × ratio. */
    private fun impliedSpeechPos(): Long? {
        if (!everExactAnchor) return null
        return (anchorSpeechMs + (cumDubMs - anchorDubMs) * ratio()).toLong()
    }

    /** v1.0.18 — wall time of counted-speech position [audioMs],
     * interpolated inside the speech slices. Null when no slice
     * covers it (older than the 20 s queue, or ahead of the newest
     * speech) — the caller then falls back to the lag estimate. */
    private fun wallAtSpeech(audioMs: Long): Long? {
        for (slice in speechSlices) {
            if (audioMs in slice.startAudioMs..slice.endAudioMs) {
                val spanAudio = maxOf(1L, slice.endAudioMs - slice.startAudioMs)
                val spanWall = maxOf(1L, slice.endWallMs - slice.startWallMs)
                return slice.startWallMs +
                    (audioMs - slice.startAudioMs) * spanWall / spanAudio
            }
        }
        return null
    }

    /** v1.0.18 — lag implied by the anchor (wall of [sPos]), or the
     * lag estimate when no slice covers the implied position. */
    private fun impliedLagMs(nowMs: Long, sPos: Long?): Long {
        if (sPos != null) {
            val w = wallAtSpeech(sPos)
            if (w != null) return nowMs - w
        }
        return lagEstimateMs(nowMs)
    }

    /** v1.0.18 — marker whose boundary (start for a burst, end for
     * a turnComplete) is nearest the speech position the anchor
     * implies for the current dub position. Returns (marker, S) or
     * (null, null) when no exact anchor exists or no marker is
     * within MARKER_RESIDUAL_MS. The lagEst-nearest selection is
     * NOT used any more — it is the floor-pinned trap. */
    private fun selectByAnchor(nowMs: Long, isTc: Boolean): Pair<TurnMarker?, Long?> {
        val sPos = impliedSpeechPos() ?: return null to null
        var best: TurnMarker? = null
        var bestResidual: Long? = null
        for (marker in markers) {
            if (isTc && (!marker.done || marker.countedMs <= 0L)) continue
            val pos = if (isTc) marker.audioMs + marker.countedMs else marker.audioMs
            val residual = kotlin.math.abs(pos - sPos)
            if (bestResidual == null || residual < bestResidual) {
                best = marker
                bestResidual = residual
            }
        }
        if (best == null || (bestResidual ?: Long.MAX_VALUE) > MARKER_RESIDUAL_MS) {
            return null to null
        }
        return best to sPos
    }

    /** v1.0.18 — bootstrap marker from the seed lag (first dub
     * arrival − first line start ≈ true lag L0, drift-free at
     * session start). For a turnComplete the boundary is the turn's
     * END (wall + counted); for a burst it is the turn's START
     * (wall). Residual ≤ 3000 ms. The monotonic 'oldest marker'
     * idea does NOT work: the first OBSERVED turnComplete is for
     * ~line 18, not line 1 (earlier turnCompletes never fired, and
     * the 12-marker window already dropped the early turns), so the
     * oldest available marker is ~9 lines too old. */
    private fun seedMarker(nowMs: Long, isTc: Boolean): TurnMarker? {
        val seedLag = seedLagMs ?: return null
        val target = if (isTc) nowMs - TURN_COMPLETE_GAP_MS - seedLag else nowMs - seedLag
        var best: TurnMarker? = null
        var bestResidual: Long? = null
        for (marker in markers) {
            if (isTc && (!marker.done || marker.countedMs <= 0L)) continue
            val pos = if (isTc) marker.wallMs + marker.countedMs else marker.wallMs
            val residual = kotlin.math.abs(pos - target)
            if (bestResidual == null || residual < bestResidual) {
                best = marker
                bestResidual = residual
            }
        }
        if (best == null || (bestResidual ?: Long.MAX_VALUE) > SEED_MARKER_RESIDUAL_MS) {
            return null
        }
        return best
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
        lastLoudAtMs = 0L
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
        // v1.0.18 — MARKER SPLITTING (the real cause of
        // "cặp mốc 0" on device): a >=300 ms silence inside a VAD
        // turn is a line boundary. Drama pauses are 100-400 ms, far
        // below the VAD's 700 ms tail, so VAD turns merge many lines
        // into one long marker; a turnComplete then finds no DONE
        // marker and bursts sample against a marker that started
        // minutes ago (huge sample, consistency rejects it). Split
        // the current marker here so boundaries can pair. The split
        // marker's wall is this chunk's time (the VAD is already
        // in-speech, so there is no 160 ms detection delay inside a
        // turn — unlike a turn start). The counted>0 guard skips the
        // turn-start head chunk (its marker was just created with
        // counted 0).
        val lastMarker = markers.lastOrNull()
        if (lastMarker != null && !lastMarker.done && lastMarker.countedMs > 0L &&
            lastLoudAtMs > 0L && nowMs - lastLoudAtMs >= SPLIT_SILENCE_MS
        ) {
            lastMarker.done = true
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
            splitMarkers++
        }
        lastLoudAtMs = nowMs
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
     *
     * v1.0.17 — [nowMs] is the TRUE utterance start wall: the
     * service subtracts VAD_DETECTION_LAG_MS (180 ms) before
     * calling, because the VAD only triggers ~180 ms after the
     * speaker started (160 ms head + one 20 ms frame hop). Stamping
     * the marker at trigger time underestimated every boundary
     * sample by ~180 ms.
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
     *
     * v1.0.17 — the hold subtracts the measured pipeline:
     * hold = D − arrival latency − pipeline. The old hold
     * (D − arrival latency) left the voice sounding at
     * (utterance start + D + pipeline) — systematically late against
     * the picture by the pipeline, at every D. The seed also uses
     * the dynamic margin (pipeline + jitter) instead of the fixed
     * 2000 ms. Every burst start (plus the session's first chunk,
     * which the old isBurst test does not count) records a
     * [BurstRecord] so the VoicePlayer's play report can be matched
     * to its turn marker; the service reads [lastBurstSeq] after
     * this call and notes the burst to the VoicePlayer.
     */
    @Synchronized
    fun onDubAudioArrival(
        nowMs: Long,
        dubMs: Long,
        firstAudioSentAtMs: Long
    ): Long {
        var hold = lastHoldMs
        // ALDA FIX32 first-audio seed: the very first translation's
        // latency sets the EMA and the delay (latency + margin).
        // v1.0.17 — the margin is dynamic (pipeline + jitter), and
        // the seed hold already subtracts the pipeline, so the
        // first voice sounds at (utterance start + D), not D +
        // pipeline late.
        if (!firstAudioSeeded && firstAudioSentAtMs > 0L) {
            firstAudioSeeded = true
            applyDynamicMargin()
            val elapsed = (nowMs - firstAudioSentAtMs).coerceIn(0L, 18000L)
            // v1.0.18 — the seed lag (first dub arrival − first
            // line start ≈ true lag L0) is the bootstrap reference
            // for the first boundary (see seedMarker): it points at
            // the right turn while the lag is still near L0.
            seedLagMs = elapsed
            latencyEma.seed(elapsed)
            val newDelay = (elapsed + dynamicMarginMs())
                .coerceIn(Prefs.MIN_DELAY_MS, maxDelayMs)
            if (newDelay != delayMs) {
                delayMs = newDelay
                onDelayChanged(delayMs)
            }
            hold = (newDelay - elapsed - pipelineMs()).coerceAtLeast(0L)
            lastHoldMs = hold
        }
        // v1.0.18 — dub burst: a chunk arriving ≥300 ms after the
        // previous one starts a new turn's dub (was 500 ms: the
        // model's inter-sentence pause is often 200-400 ms). It
        // anchors exactly (see onDubBurst) and gives a boundary
        // sample; the slice pairing below is still the main
        // measurement.
        val isBurst = lastDubChunkAtMs >= 0L &&
            nowMs - lastDubChunkAtMs >= BURST_GAP_MS
        val isFirstChunk = lastDubChunkAtMs < 0L
        val burstStartCumDubMs = cumDubMs
        lastDubChunkAtMs = nowMs
        cumDubMs += dubMs
        var burstMarker: TurnMarker? = null
        if (isBurst) {
            burstSeq++
            val (burstHold, marker) = onDubBurst(nowMs)
            hold = burstHold
            burstMarker = marker
        } else if (isFirstChunk) {
            // v1.0.17 — the session's (or post-reconnect) first
            // chunk is not a burst by the old test, but it still
            // starts a voice burst physically: record it so its
            // play report measures the (cold-start) pipeline.
            // No marker yet — the play still yields the pipeline,
            // just no sync sample.
            burstSeq++
        }
        // v1.0.18 — MONOTONIC HOLD: a chunk's due never precedes
        // the previous chunk's due. A hold drop (a new boundary
        // sample lowering the hold) would otherwise make the new
        // sentence due BEFORE the previous sentence's tail, and the
        // FIFO hold queue then plays it late by the whole
        // inversion (that is what the sim's ~2 s "true pipeline"
        // spikes were: hold errors, not audio latency).
        if (lastDueMs > 0L) {
            hold = maxOf(hold, lastDueMs - nowMs)
        }
        lastDueMs = nowMs + hold
        lastHoldMs = hold
        if (isBurst || isFirstChunk) {
            recordBurst(burstSeq, nowMs, hold, burstMarker, burstStartCumDubMs)
        }
        // Continuous slice pairing (v1.0.10, re-anchored in v6).
        pairDubSlices(dubMs, nowMs)
        // 4 s window flush: median of the window's samples -> EMA.
        flushWindowIfDue(nowMs)
        return hold
    }

    /** v1.0.17 — record a burst for its play report (and remember
     * its marker for the frame check). */
    private fun recordBurst(
        seq: Long,
        arrivalWallMs: Long,
        holdMs: Long,
        marker: TurnMarker?,
        startCumDubMs: Long
    ) {
        pendingBursts.addLast(
            BurstRecord(
                seq = seq,
                arrivalWallMs = arrivalWallMs,
                holdMs = holdMs,
                markerSeq = marker?.seq,
                markerWallMs = marker?.wallMs,
                startCumDubMs = startCumDubMs
            )
        )
        if (marker != null) {
            recentBurstMarkers.addLast(seq to marker.seq)
            while (recentBurstMarkers.size > MAX_VIDEO_TIME_RECORDS) {
                recentBurstMarkers.removeFirst()
            }
        }
        while (pendingBursts.size > MAX_VIDEO_TIME_RECORDS) {
            pendingBursts.removeFirst()
        }
    }

    /**
     * v1.0.17 — the VoicePlayer reports that a burst's first frame
     * actually sounded: [seq] is the burst (from [lastBurstSeq]),
     * [playWallMs] the wall time it sounded, [pipelineMs] the
     * playback pipeline (play − arrival − hold) and [estimated]
     * true when the value is the queue-based fallback (no
     * getTimestamp on this device) instead of a playhead
     * measurement. Called on the VoicePlayer's thread — the
     * tracker is @Synchronized throughout, so this is safe.
     *
     * Three jobs, none of which feeds the EMA (see the class doc
     * for why a play-anchored sample must never feed it):
     *  1. The pipeline EMA moves (0.3) and the dynamic margin
     *     follows (pipeline + 400 ms jitter, 500–2000 ms).
     *  2. The closed-loop sync error: (play − marker wall) − D —
     *     what the user sees. ~0 in steady state; persistently
     *     positive means the hold saturated and D must grow (the
     *     arrival-anchored samples already carry that: a saturated
     *     burst's arrival latency exceeds D − pipeline, so the
     *     EMA raises D through the normal path).
     *  3. The play-anchored slice samples: every paired 500 ms
     *     unit of this burst gets play time = burst play + its
     *     offset inside the burst. Their drift (last − first)
     *     shows the in-turn stretch; the last value is the
     *     current in-turn sync. Diagnostics only.
     */
    @Synchronized
    fun onBurstPlayed(seq: Long, playWallMs: Long, pipelineMs: Long, estimated: Boolean, backlogMs: Long = 0L) {
        lastPipelineMs = pipelineMs.coerceIn(0L, 2000L)
        lastPipelineEstimated = estimated
        // v1.0.18 — the audio already queued ahead of this burst's
        // first frame at write time (the VoicePlayer reads the
        // playhead before writing): it separates backlog from
        // hardware in the "phát" diagnostic ("chờ Z ms").
        lastBacklogMs = backlogMs.coerceAtLeast(0L)
        val ema = pipelineEmaMs
        pipelineEmaMs = if (ema == null) {
            lastPipelineMs
        } else {
            ((ema * 7 + lastPipelineMs!! * 3) / 10).coerceIn(0L, 1000L)
        }
        applyDynamicMargin()
        lastBurstPlayWallMs = playWallMs
        val record = pendingBursts.firstOrNull { it.seq == seq }
        // Prune this burst and everything older: their reports
        // will never arrive now (or arrived already).
        while (pendingBursts.isNotEmpty() && pendingBursts.first().seq <= seq) {
            pendingBursts.removeFirst()
        }
        if (record?.markerWallMs != null) {
            lastSyncErrorMs = (playWallMs - record.markerWallMs!!) - delayMs
            // Play-anchored slice samples for this burst.
            val slices = pairingLog.filter { it.burstSeq == seq }
                .sortedBy { it.dubCumMs }
            if (slices.isNotEmpty()) {
                val samples = slices.map { entry ->
                    val playSliceWall = playWallMs +
                        (entry.dubCumMs - record.startCumDubMs)
                    playSliceWall - entry.sourceWallMs
                }
                lastPlaySliceSampleMs = samples.last()
                playDriftMs = if (samples.size >= 2) {
                    samples.last() - samples.first()
                } else {
                    0L
                }
            }
        }
        // Pairing-log entries of this burst and older are spent.
        while (pairingLog.isNotEmpty() &&
            pairingLog.first().burstSeq <= seq
        ) {
            pairingLog.removeFirst()
        }
    }

    /**
     * v1.0.17 — frame check (report-only): the service passes the
     * video's currentTime, already corrected to the EVENT wall (the
     * JS reply arrives after the event, so the service subtracts
     * the wall time the video advanced during the round trip —
     * unless the video was paused, when no correction applies).
     * [setMarkerVideoTime] records the marker's video time;
     * [onBurstVideoTime] the video time at a burst's play start.
     * The residual is (video time now − marker video time) − D:
     * positive = the shown frame is AHEAD of the spoken sentence.
     * v1.0.17 only reports it; it never steers D.
     */
    @Synchronized
    fun setMarkerVideoTime(markerSeq: Long, videoTimeSec: Double) {
        markerVideoTimes.addLast(markerSeq to videoTimeSec)
        while (markerVideoTimes.size > MAX_VIDEO_TIME_RECORDS) {
            markerVideoTimes.removeFirst()
        }
        // A burst video time may already be waiting for this marker.
        for ((seq, _) in burstVideoTimes) {
            tryComputeFrameResidual(seq)
        }
    }

    @Synchronized
    fun onBurstVideoTime(seq: Long, videoTimeSec: Double) {
        burstVideoTimes.addLast(seq to videoTimeSec)
        while (burstVideoTimes.size > MAX_VIDEO_TIME_RECORDS) {
            burstVideoTimes.removeFirst()
        }
        tryComputeFrameResidual(seq)
    }

    private fun tryComputeFrameResidual(seq: Long) {
        val markerSeq = recentBurstMarkers.firstOrNull { it.first == seq }?.second
            ?: return
        val vMarker = markerVideoTimes.firstOrNull { it.first == markerSeq }?.second
            ?: return
        val vBurst = burstVideoTimes.firstOrNull { it.first == seq }?.second
            ?: return
        lastFrameResidualMs = ((vBurst - vMarker) * 1000.0 - delayMs).toLong()
    }

    /** Current dynamic margin, for the service / diagnostics. */
    fun currentMarginMs(): Long = dynamicMarginMs()

    /** v1.0.17 — the frame residual at the last burst play (ms),
     * or null when the JS video-time round trip has not completed
     * for any burst yet. */
    val lastFrameResidual: Long? get() = lastFrameResidualMs
    /**
     * v1.0.14 — the server finished a turn (turnComplete). [nowMs] is
     * the arrival wall time; [audioPlayed] is whether any dub audio
     * arrived during this model turn (the service's
     * audioPlayedThisTurn — a noise-only turn with no dub must not
     * re-anchor: its dub position never advanced for this turn).
     * Identifies the turn whose dub just ended, feeds its boundary
     * sample, re-anchors exactly, and measures the per-turn ratio
     * from consecutive turnCompletes. Guarded: a mis-paired boundary
     * (wrong marker, or a sample inconsistent with the anchor-implied
     * lag) is skipped, never fed. Every reason a boundary is
     * rejected is counted (v1.0.18 diagnostics).
     *
     * v1.0.18 — the marker is picked by the ANCHOR-IMPLIED speech
     * position (see selectByAnchor), never by nearness to the
     * slice-median lagEst (the floor-pinned trap that killed every
     * boundary on the 27/11 device photos: "cặp mốc 0/60 s" in both
     * sessions). While no exact anchor exists yet, the SEED LAG
     * (first dub arrival − first line start ≈ true lag L0) points
     * at the right turn; if the anchor path finds nothing later,
     * the seed-lag marker is the fallback recovery.
     */
    @Synchronized
    fun onDubTurnComplete(nowMs: Long, audioPlayed: Boolean) {
        tcRecv++
        if (!audioPlayed) {
            tcNoAudio++
            return
        }
        if (!everExactAnchor) {
            // Seed-lag bootstrap: the k-th OBSERVED turnComplete is
            // not the k-th turn (earlier turnCompletes never fired),
            // so the oldest marker is wrong; the seed lag points at
            // the right turn while the lag is still near L0.
            val marker = seedMarker(nowMs, isTc = true)
            if (marker == null) {
                tcNoMarker++
                return
            }
            acceptTurnComplete(marker, nowMs, viaSeed = true, sPos = null)
            return
        }
        val (marker, sPos) = selectByAnchor(nowMs, isTc = true)
        if (marker == null) {
            // Anchor path found nothing (stale anchor): recover from
            // the SEED LAG (an independent reference) instead of
            // widening the anchor net — a wide anchor net only
            // catches stale markers.
            val seed = seedMarker(nowMs, isTc = true)
            if (seed == null) {
                tcNoMarker++
                return
            }
            tcFallback++
            acceptTurnComplete(seed, nowMs, viaSeed = true, sPos = null)
            return
        }
        acceptTurnComplete(marker, nowMs, viaSeed = false, sPos = sPos)
    }

    /** v1.0.18 — shared accept path for a turnComplete once a marker
     * is chosen (mirrors the sim's _accept_tc). [viaSeed] = the
     * marker came from the seed lag (bootstrap or fallback
     * recovery); [sPos] = the anchor-implied speech position for
     * the anchor path (null for seed). Returns true when the sample
     * was accepted. */
    private fun acceptTurnComplete(
        marker: TurnMarker,
        nowMs: Long,
        viaSeed: Boolean,
        sPos: Long?
    ): Boolean {
        val anchorSpeechNew = marker.audioMs + marker.countedMs
        // A bootstrap (approximate) anchor must never veto an exact
        // boundary as "backwards" — only an exact anchor may.
        // Equality is fine: the bootstrap may already have set this
        // exact boundary, and the turnComplete confirms it (its
        // sample + ratio measurement still count).
        if (anchorSpeechNew < anchorSpeechMs && everExactAnchor) {
            tcAnchorBack++
            return false
        }
        // Raw (tc − marker) measures the lag of the turn's END
        // content: lag + counted + gap. Subtract both to get the lag.
        val latency = nowMs - marker.wallMs - marker.countedMs -
            TURN_COMPLETE_GAP_MS
        if (latency !in 0L..MAX_SAMPLE_MS) {
            tcConsistency++
            return false
        }
        if (!viaSeed) {
            // Anchor path: consistency against the anchor-implied
            // lag (wall of S, interpolated from the slices), not
            // the floor-pinned slice median.
            val ref = impliedLagMs(nowMs, sPos)
            if (kotlin.math.abs(latency - ref) > SAMPLE_CONSISTENCY_MS) {
                tcConsistency++
                return false
            }
        } else if (isDTrusted(nowMs)) {
            // Seed-lag fallback recovery: cross-check against D
            // when D is trusted (a transient lag would make the
            // seed lag wrong by seconds).
            if (kotlin.math.abs(latency - dBasedLagMs()) > SEED_FB_D_CHECK_MS) {
                tcConsistency++
                return false
            }
        }
        // (Bootstrap 'seed' has nothing reliable to check against:
        // the first sample SETS the scale.)
        lastBoundarySampleMs = latency
        lastBoundaryAtMs = nowMs
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
        tcOk++
        recordTurnPair(nowMs)
        feedSample(latency, nowMs)
        reanchor(anchorSpeechNew, nowMs, exact = true)
        // v1.0.18 — HOLD REFRESH at every accepted turnComplete:
        // the next burst's chunks no longer inherit a hold that
        // predates the latest latency by minutes (the v1.0.17
        // limitation: hold only updated at bursts, ~4/420 s in the
        // sim, so the actual line-start error stayed ~362 ms).
        // Applies only to chunks enqueued after this; chunks
        // already queued keep their due times.
        lastHoldMs = (delayMs - latency - pipelineMs()).coerceAtLeast(0L)
        return true
    }

    /**
     * v1.0.14 — a dub burst started (a chunk after ≥300 ms of dub
     * silence). The chunk is the START of a turn's dub: the anchor is
     * the marker's start audio, and the sample is burst − marker wall
     * (no counted correction — a burst marks a dub START, unlike a
     * turnComplete which marks a dub END). Returns (this burst's
     * hold, the turn marker it paired with — null when no marker
     * matched). The hold is max(0, D − latency − pipeline):
     * v1.0.17 subtracts the measured pipeline so the voice sounds
     * at (utterance start + D), not (D + pipeline) late.
     *
     * v1.0.18 — the marker is picked by the ANCHOR-IMPLIED speech
     * position (see selectByAnchor), never by nearness to the
     * slice-median lagEst. The consistency check is against the
     * anchor-implied lag, plus a second opinion against the
     * D-based lag while D is trusted (the anchor-implied check is
     * tautological — it agrees with a wrong anchor, so a burst 2 s
     * too old passes it and feeds an inflated sample that drags D
     * up). Before the first exact anchor, the seed lag is the only
     * reference.
     */
    private fun onDubBurst(nowMs: Long): Pair<Long, TurnMarker?> {
        burstRecv++
        if (!everExactAnchor) {
            // Before the first exact anchor a burst has no reliable
            // marker — except via the seed lag, which points at the
            // right turn while the lag is still near L0. Skip the
            // burst rather than mis-pair it.
            val marker = seedMarker(nowMs, isTc = false)
            if (marker == null) {
                burstNoMarker++
                return lastHoldMs to null
            }
            val latency = nowMs - marker.wallMs
            if (latency !in 0L..MAX_SAMPLE_MS) {
                burstConsistency++
                return lastHoldMs to null
            }
            burstOk++
            lastBoundarySampleMs = latency
            lastBoundaryAtMs = nowMs
            recordTurnPair(nowMs)
            feedSample(latency, nowMs)
            reanchor(marker.audioMs, nowMs, exact = true)
            return (delayMs - latency - pipelineMs()).coerceAtLeast(0L) to marker
        }
        val (anchorMarker, sPos) = selectByAnchor(nowMs, isTc = false)
        var viaSeed = false
        val marker: TurnMarker = if (anchorMarker != null) {
            anchorMarker
        } else {
            // Fallback: seed-lag marker (independent reference).
            // A wide anchor net only catches stale markers.
            val seed = seedMarker(nowMs, isTc = false)
            if (seed == null) {
                burstNoMarker++
                return lastHoldMs to null
            }
            viaSeed = true
            burstFallback++
            seed
        }
        // A marker older than the anchor (a late burst for an old
        // turn) or inconsistent with the anchor: no sample, and no
        // marker for the play report either — a sync error against
        // the wrong sentence would be worse than none. Only an
        // exact anchor may veto (here it always is exact).
        if (marker.audioMs < anchorSpeechMs && everExactAnchor) {
            burstAnchorBack++
            return lastHoldMs to null
        }
        val latency = nowMs - marker.wallMs
        if (latency !in 0L..MAX_SAMPLE_MS) {
            burstConsistency++
            return lastHoldMs to null
        }
        if (!viaSeed) {
            val ref = impliedLagMs(nowMs, sPos)
            if (kotlin.math.abs(latency - ref) > SAMPLE_CONSISTENCY_MS) {
                burstConsistency++
                return lastHoldMs to null
            }
            if (isDTrusted(nowMs) &&
                kotlin.math.abs(latency - dBasedLagMs()) > BURST_D_CHECK_MS
            ) {
                burstConsistency++
                return lastHoldMs to null
            }
        } else if (isDTrusted(nowMs) &&
            kotlin.math.abs(latency - dBasedLagMs()) > SEED_FB_D_CHECK_MS
        ) {
            burstConsistency++
            return lastHoldMs to null
        }
        burstOk++
        lastBoundarySampleMs = latency
        lastBoundaryAtMs = nowMs
        recordTurnPair(nowMs)
        feedSample(latency, nowMs)
        reanchor(marker.audioMs, nowMs, exact = true)
        return (delayMs - latency - pipelineMs()).coerceAtLeast(0L) to marker
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
            // v1.0.17 — log the pairing for the play-anchored slice
            // samples: at this burst's play time the slice's play
            // wall is (burst play + (dubCum − burst start cum)) and
            // its play-anchored sample is that minus the source
            // wall recorded here. The log is pruned in
            // onBurstPlayed once its burst has played.
            pairingLog.addLast(
                PairingRecord(
                    dubCumMs = anchorDubMs + pairedDubMs,
                    sourceWallMs = wall.toLong(),
                    burstSeq = burstSeq
                )
            )
            while (pairingLog.size > MAX_PAIRING_LOG) pairingLog.removeFirst()
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
        // v1.0.17 — play-anchored sync diagnostics. "lệch hình" is
        // the closed-loop error at the last burst play: (play −
        // marker wall) − D. Positive = the voice sounded late
        // against the picture; ~0 in steady state. "phát" is the
        // measured playback pipeline (play − arrival − hold) with
        // its EMA; "~" marks the queue-based fallback estimate (no
        // getTimestamp on this device). "giọng/gốc" is the measured
        // dub/speech duration ratio (1.3x = the Vietnamese dub runs
        // ~30% longer than the Chinese original). "khung hình" is
        // the JS video-time residual (report-only in v1.0.17):
        // (video time now − marker video time) − D; positive = the
        // shown frame is ahead of the spoken sentence.
        val syncPart = lastSyncErrorMs?.let {
            " · lệch hình %+d ms".format(it)
        } ?: " · lệch hình —"
        val pipePart = lastPipelineMs?.let {
            val mark = if (lastPipelineEstimated) "~" else ""
            // v1.0.18 — "chờ Z ms" is the audio already queued
            // ahead of this burst's first frame at write time
            // (backlog), so a big "phát" can be told apart: backlog
            // vs hardware.
            val backlogPart = lastBacklogMs?.let { b ->
                ", chờ %d ms".format(b)
            } ?: ""
            " · phát %s%d ms (TB %d ms%s)".format(mark, it, pipelineMs(), backlogPart)
        } ?: " · phát —"
        val ratioPart = " · giọng/gốc %.2f×".format(1.0 / ratio())
        val framePart = lastFrameResidualMs?.let {
            " · khung hình %+d ms".format(it)
        } ?: " · khung hình —"
        // v1.0.18 — per-reason boundary rejection counters (the
        // "cặp mốc 0" diagnosis): recv -> ok, and why the rest
        // were rejected — no-mk = no marker within residual,
        // no-aud = turnComplete with no dub audio, ab =
        // anchor-backwards veto, cons = consistency/range, fb =
        // recovered via the seed-lag marker. "tách N" = markers
        // created by splitting a merged VAD turn (≥300 ms silence
        // inside a turn). Only the sides with data are shown.
        fun sideCounters(
            name: String, recv: Int, ok: Int, noMarker: Int, noAudio: Int,
            anchorBack: Int, consist: Int, fallback: Int
        ): String {
            if (recv == 0) return ""
            var s = " · $name $recv ok $ok"
            if (noMarker > 0) s += " no-mk $noMarker"
            if (noAudio > 0) s += " no-aud $noAudio"
            if (anchorBack > 0) s += " ab $anchorBack"
            if (consist > 0) s += " cons $consist"
            if (fallback > 0) s += " fb $fallback"
            return s
        }
        val rejectPart = sideCounters(
            "tc", tcRecv, tcOk, tcNoMarker, tcNoAudio, tcAnchorBack,
            tcConsistency, tcFallback
        ) + sideCounters(
            "burst", burstRecv, burstOk, burstNoMarker, 0,
            burstAnchorBack, burstConsistency, burstFallback
        ) + if (splitMarkers > 0) " · tách $splitMarkers" else ""
        return "D %s s · %s · %d mẫu/60 s · cặp mốc %d/60 s · chờ %d lát · hold %.1f s · %s%s%s%s%s%s%s".format(
            delaySec, samplePart, sampleTimes60s.size, turnPairTimes60s.size,
            speechSlices.size, lastHoldMs / 1000.0, vadPart, droppedPart,
            syncPart, pipePart, ratioPart, framePart, rejectPart
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
        lastDueMs = 0L
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
        // v1.0.17 — play-anchored state.
        burstSeq = 0L
        pendingBursts.clear()
        pairingLog.clear()
        pipelineEmaMs = null
        lastPipelineMs = null
        lastPipelineEstimated = false
        lastSyncErrorMs = null
        lastBurstPlayWallMs = null
        lastPlaySliceSampleMs = null
        playDriftMs = null
        markerVideoTimes.clear()
        burstVideoTimes.clear()
        recentBurstMarkers.clear()
        lastFrameResidualMs = null
        // v1.0.18 — a stop/start must reset CLEAN (the 27/11
        // 2-session correction: session 2's D 3.7 s was seeded
        // fresh, but stop() left firstAudioSeeded + the LatencyEma
        // + the boundary state behind — harmless today because the
        // service discards the tracker on stop, but stop() must be
        // complete in case a tracker is ever reused).
        firstAudioSeeded = false
        seedLagMs = null
        lastLoudAtMs = 0L
        lastDueMs = 0L
        lastBoundarySampleMs = 0L
        lastBoundaryAtMs = 0L
        splitMarkers = 0
        tcRecv = 0
        tcNoAudio = 0
        tcNoMarker = 0
        tcAnchorBack = 0
        tcConsistency = 0
        tcOk = 0
        tcFallback = 0
        burstRecv = 0
        burstNoMarker = 0
        burstAnchorBack = 0
        burstConsistency = 0
        burstOk = 0
        burstFallback = 0
        lastBacklogMs = null
        latencyEma.reset()
        latencyEma.setMarginMs(2000L)
    }
}
