package com.dublive.live

import android.util.Log

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
        private const val TAG = "DubDelayTracker"
        /** One measurement slice, ms. */
        const val SLICE_MS = 500L
        /** Max speech slices kept (60 s of speech, v1.0.22 T-b:
         * was 40 = 20 s — the tx re-anchor fell back to a VAD
         * marker ~24 s away whenever the line start was older
         * than 20 s; the sim's audioAt now covers 60 s). */
        const val MAX_SLICES = 120
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
        /** v1.0.17 — jitter headroom inside the dynamic margin.
         * v1.0.19 — 400 → 800 ms: the hold (≈ margin − pipeline
         * ≈ jitter) saturated at 0 under a 2.5x generation burst
         * and the drop hit 23%; the larger jitter is the slack
         * that absorbs burst-latency variation. */
        const val PIPELINE_JITTER_MS = 800L
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
        /** v1.0.23 — TRỄ CỐ ĐỊNH (sim1028 GATE PASS, ship config
         * (e)): D = 40 s cố định, KHÔNG còn D thích ứng, KHÔNG còn
         * seed/Sa2/S-b lái D. D chỉ đổi khi user bấm nút ±1 s
         * (35–45 s). Mọi chỗ cũ gán delayMs từ mẫu/seed/EMA đã bị
         * gỡ — delayMs khởi tạo ở D cố định và chỉ [adjustManual]
         * mới đổi nó. */
        const val FIXED_DELAY_MS = 40000L
        /** v1.0.23 — trần stretch 1,25× (KHÔNG 1,5×: user 12:11
         * "Giọng bị méo khi bắt kịp"; KHÔNG 1,35×: sim chứng minh
         * 1,35 không hơn gì 1,25 mà mạo hiểm ngưỡng méo chưa
         * biết). Mọi đường nâng tốc độ (error-driven, level,
         * lateness, ratio floor) đều kẹp ở đây. */
        const val MAX_STRETCH_SPEED = 1.25
        /** v1.0.19 — level-driven stretch ceiling: a full buffer
         * alone does not justify the fastest stretch. */
        const val STRETCH_LEVEL_CAP = 1.25
        /** v1.0.19 — burst D-validation window (burst-successor
         * ratio gate). */
        const val BURST_D_VALIDATE_MS = 1000L
        /** v1.0.20 — BURST D-CHECK BYPASS: after this many
         * consecutive D-check-only rejections the next burst
         * bypasses the D-check (the anchor-implied + range checks
         * still apply, the ratio validation is skipped for the
         * bypassed burst). The v1.0.19 device test pinned D at
         * the 8 s cap from a stale 7.2 s sample, so honest bursts
         * (true lag ~2.4 s) failed the 1500 ms D-check forever
         * and D could never recover — a deadlock the slice
         * D-check's 6-streak re-anchor does not cover for
         * bursts. */
        const val BURST_DCHECK_BYPASS_STREAK = 3
        /** v1.0.20 — GARBAGE GUARD: a sync/frame value beyond
         * this cannot be real (the v1.0.19 clock bug produced
         * -135,043,353 ms). Such a value is dropped (shown as
         * "—") and its raw inputs are logged. */
        const val SYNC_GARBAGE_MS = 60_000L
        /** v1.0.22 — FRAME-RESIDUAL guard (tighter than the
         * sync guard): a real frame residual is bounded by
         * roughly D + drift (a few seconds). The 09:10 device
         * photo showed "khung hình -52199 ms" while "lệch hình"
         * was +13 ms — the video time had jumped ~47 s
         * backwards between the marker and the burst play
         * (episode change / seek / reload) and the ±60 s guard
         * let it through. Beyond ±10 s the residual is dropped
         * (shown as "—") and its raw inputs are logged; the
         * continuity check in [tryComputeFrameResidual] is the
         * primary defence (a jumped video fails it first). */
        const val FRAME_GARBAGE_MS = 10_000L
        /** v1.0.19 — slice D-check window (feedSample). */
        const val SLICE_D_CHECK_MS = 1500L
        /** v1.0.19 — sustained slice D-rejections before re-anchor. */
        const val SLICE_D_REANCHOR_STREAK = 6
        /** v1.0.19 — hold slew limit per burst (ms). */
        const val HOLD_SLEW_MS = 300L
        /** v1.0.19 — lateness threshold for the stretch raise. */
        const val LATENESS_STRETCH_MS = 200L
        /** v1.0.21 (21a) — D-based + monotone marker selection:
         * a marker is picked only when its boundary is this close
         * to (now − selection lag); the same window the burst
         * D-check uses, so a D-based pick can never fail the
         * D-check for the wrong reason. */
        const val DBASED_RES_MS = 1500L
        /** v1.0.21 (21b) — transcription anchor: a tx sentence's
         * estimated start must fall inside this window before the
         * burst arrival. */
        const val TX_WIN_MS = 10000L
        /** v1.0.21 (21b/21i) — tx D-check window once the
         * reference is established; the bootstrap window while
         * it is not. */
        const val TX_DCHECK_MS = 2000L
        const val TX_BOOT_WIN_MS = 6000L
        /** v1.0.21 (21b/21i) — tx selection residual bound once
         * the reference is established (off during bootstrap,
         * when the target is wrong while the seed is wrong). */
        const val TX_RES_MS = 1200L
        /** v1.0.21 (21b) — gap between input-transcription
         * fragments that closes a tx sentence (a new sentence
         * starts). */
        const val TX_GAP_MS = 800L
        /** v1.0.21 (21b) — mean delay of a line's first
         * transcription fragment into the line (lineStart ≈
         * firstFrag − asrLagEma − this). */
        const val TX_FRAG_DELAY_MS = 500L
        /** v1.0.21 (21b) — asrLag calibration sample range. */
        const val TX_ASR_CAL_MAX_MS = 3000L
        /** v1.0.21 (21h/21j) — densest-cluster window for the
         * independent lag reference, and the boundary-sample /
         * bootstrap-candidate pool caps. */
        const val CLUSTER_WINDOW_MS = 1200L
        const val MAX_BND_SAMPLES = 30
        const val MAX_SEED_CAND = 200
        /** v1.0.21 (21d) — sync-error history caps (per burst /
         * per slice-play unit, oldest dropped). */
        const val MAX_SYNC_ERR_HISTORY = 600
        const val MAX_SLICE_PLAY_HISTORY = 1200
        /** v1.0.21 (21d) — D pinned at/above this counts as
         * pinned. v1.0.23: D cố định 40 s, "ghim" chỉ còn nghĩa
         * khi user nudge tay lên trần 45 s. */
        const val D_PINNED_MS = 44900L
        /** v1.0.22 — ratio-baseline stretch (V-a) + tail valve
         * (V-c, round-2 tuned V2000cap500): the stretch floor is
         * the measured dub/speech ratio so the steady-state drift
         * is ~0; the valve drops at most 500 ms of stale
         * head-audio at the next burst when the corrected
         * end-lag exceeds 2000 ms with the stretch already at
         * the 1.5x ceiling. Sim rounds 1–4 (sim1022–sim1025).
         *
         * v1.0.23 (sim1028 ship config (e), STRONG valve):
         * ngưỡng 1000 ms / giữ 500 ms / xả tối đa 2000 ms mỗi
         * lần + skip-ahead đầu burst tối đa 2000 ms khi burst
         * bắt đầu trễ >1000 ms. Van là lưới an toàn, chỉ nổ khi
         * lỗi — trên-nominal sim nó không nổ lần nào (0 fires).
         * V-b (catch-up ×(1+endLag/5000)) ĐÃ GỠ: chính sách tốc
         * độ ship là tốc độ = độ dài giọng / cửa sổ còn lại tới
         * cuối câu nguồn, kẹp 1,25× — tức đúng ratio floor, không
         * nhân thêm hệ số trôi. */
        const val VALVE_THRESH_MS = 1000L
        const val VALVE_KEEP_MS = 500L
        const val VALVE_MAX_DROP_MS = 2000L
        /** v1.0.23 — skip-ahead đầu burst (sim1028 STRONG valve):
         * burst bắt đầu trễ hơn lịch >1000 ms thì bỏ tối đa
         * 2000 ms (và tối đa 50% độ dài giọng ước tính của
         * burst) ở ĐẦU burst để phần còn lại kịp về đúng giờ. */
        const val SKIP_AHEAD_THRESH_MS = 1000L
        const val SKIP_AHEAD_KEEP_MS = 500L
        const val SKIP_AHEAD_MAX_MS = 2000L
        /** v1.0.22 — tx sub-split (T-a): anchor groups close on
         * a >=300 ms fragment gap (dense dialogue never leaves
         * an 800 ms gap, so groups never closed); the SENTENCE
         * grouping for the seed path keeps the 800 ms gap
         * ([TX_GAP_MS]) in a parallel list. */
        const val TX_SUB_GAP_MS = 300L
        /** v1.0.22 — R-b: once the reference is established, a
         * tx sample more than this above the selection lag is a
         * +1-line error even inside the 1200 ms residual. */
        const val RB_UP_MS = 600L
        /** v1.0.22 — Rf1 ratio measurement: counted speech
         * corrected for music fed during inter-line pauses
         * (counted − 0.40 × Σ max(0, fragGap − 900 ms)); the
         * uncorrected estimator read 0.83 vs the true 0.704. */
        const val RATIO_PAUSE_K = 0.40
        const val RATIO_PAUSE_GAP_MS = 900L
        /** v1.0.22 — Sa2 seed v2: the first paired slice sample
         * picks the closed SENTENCE group nearest
         * (firstBurstArrival − sliceLag), but only a group whose
         * start is at least this far past the first marker wall
         * (the sim's gap was 80 ms — thinner than real marker /
         * startEst noise; +500 ms still leaves the true group
         * ~2.4 s clear). Window lower bound for the candidate
         * search (same 300 ms as the tx anchor). */
        const val S2_MARKER_MARGIN_MS = 500L
        const val S2_MIN_GAP_MS = 300L
        /** v1.0.22 — S-b slice referee: trial when the median of
         * the last 3 per-burst slice medians deviates from the
         * reference by more than this; a trial lasts 5 bursts
         * with a 10-burst cooldown and at most 3 trials; adopt
         * on 3 consecutive bursts agreeing within 600 ms. */
        const val SB_N = 3
        const val SB_TRIAL_BURSTS = 5
        const val SB_TRIG_MS = 1500L
        const val SB_AGREE_MS = 600L
        const val SB_AGREE_RUN = 3
        const val SB_COOLDOWN_BURSTS = 10
        const val SB_MAX_TRIALS = 3
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
        val seq: Long,
        /** v1.0.21 — the VAD turn ordinal this marker belongs to
         * (split markers share their turn's ordinal; the tx
         * anchor keys its source entries by it, like the sim's
         * turn_idx). */
        val turnIdx: Long
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

    // ---- v1.0.19 — slice D-check + D-based re-anchor ----
    /** Slice samples rejected by the D-check, and the current
     * run of consecutive rejections (6 in a row re-anchors). */
    private var sliceDReject = 0
    private var sliceDStreak = 0
    /** Re-anchors to the D-based marker (slice streak / burst
     * no-marker recovery). */
    private var dReanchors = 0

    // ---- v1.0.19 — burst-successor ratio measurement ----
    /** Last accepted burst's marker, for the successor chain.
     * Named lastRatioBurstSeq (not lastBurstSeq): the public
     * val lastBurstSeq already exists (the burst sequence the
     * service reads) and a private var of the same name would
     * collide with it. */
    private var lastRatioBurstSeq: Long? = null
    private var lastBurstAudioMs = 0L
    private var lastBurstDubMs = 0L
    private var lastBurstValidated = false
    private var ratioBurstMeas = 0

    // ---- v1.0.19 — time-stretch controller ----
    /** Target time-stretch speed (1.0 = no stretch). Read by the
     * service via [currentStretchSpeed]; raised by noteLevel /
     * noteLateness, steered by [stretchUpdate]. */
    private var targetSpeed = 1.0
    /** When the level/lateness demand last raised the speed: the
     * hysteresis lowering in [stretchUpdate] holds off for 5 s. */
    private var levelRaiseAtMs: Long? = null
    private var lastStretchUpdateMs = 0L

    // ---- v1.0.19 — marker diagnostics ----
    /** Marker-split attempts (gap condition met with an open
     * marker) vs [splitMarkers] (splits that actually fired). */
    private var splitAttempts = 0
    private var stallResets = 0

    // ---- v1.0.20 — stall EPISODES + burst D-check bypass ----
    /** True while inside a stall episode: [resetForStall] is
     * episode-gated (a no-op while already stalled) so a long
     * silence cannot wipe the accumulating speech over and over
     * — the v1.0.19 non-self-healing lock (device "stall 159":
     * per-chunk/per-turn-start resets, each wiping the speech
     * before a 500 ms slice could ever complete). Cleared when
     * a slice completes ([onSpeechMs]) or when a turn starts
     * without a reset firing ([onSpeechTurnStart]). */
    private var stalled = false
    /** Stall EPISODES (not reset invocations): the diagnostics
     * counter. [stallResets] (invocations) is kept for
     * debugging but no longer displayed. */
    private var stallEpisodes = 0
    /** Consecutive bursts rejected ONLY by the D-check (the
     * anchor-implied + range checks passed). At
     * [BURST_DCHECK_BYPASS_STREAK] the next burst bypasses the
     * D-check. Reset on any accepted burst. */
    private var burstDCheckStreak = 0
    /** Stall episodes, for the diagnostics HUD. */
    val stallEpisodeCount: Int get() = stallEpisodes
    /** Total speech ms fed to the tracker this session (for the
     * service's /60 s fed-speech diagnostic). */
    val totalSpeechMs: Long get() = cumSpeechMs

    // ---- v1.0.21 (21a) — D-based + monotone selection ----
    /** Sequence of the last marker an accepted burst/tc paired
     * with; the D-based selection only considers newer markers
     * (monotone — the anchor must never jump backwards). */
    private var lastAcceptedSeq: Long? = null
    /** Bursts/tcs whose marker came from the D-based path. */
    private var dBasedSel = 0

    // ---- v1.0.21 (21c) — split-attempt counting, both ways ----
    /** Attempts counted the v1.0.20 way (before the counted>0
     * guard — every turn start wastes one); [splitAttempts]
     * is the fixed count (only counted>0, == splits). */
    private var splitAttemptsOld = 0

    // ---- v1.0.21 (21b) — transcription anchor ----
    /** One finalized VAD turn's tx source record, keyed by the
     * VAD turn ordinal (the sim's turn_idx). [sentMs] is the
     * turn's counted speech (its marker's countedMs). */
    private data class TxSourceEntry(
        val startWallMs: Long,
        val endWallMs: Long,
        val sentMs: Long,
        var firstFragMs: Long?,
        var lastFragMs: Long?,
        var nFrag: Int
    )
    /** One input-transcription sentence (fragments grouped by a
     * >=800 ms arrival gap). Only CLOSED groups may be selected
     * — an open group is a future line, never this burst's. */
    private data class TxGroup(
        val firstMs: Long,
        var lastMs: Long,
        var closed: Boolean
    )
    private val txSrc = HashMap<Long, TxSourceEntry>()
    private val txPendingFrags = HashMap<Long, MutableList<Long>>()
    private val txGroups = ArrayList<TxGroup>()
    private var txCurGroup: TxGroup? = null
    private var txLastFragAtMs: Long? = null
    /** asrLag EMA (ms, seeded 600) + calibration samples from
     * short turns only (a merged turn's end wall belongs to its
     * LAST line, not to this fragment's line). */
    private var txAsrEmaMs = 600.0
    private var txAsrSamples = 0
    private val txCalibratedTurns = HashSet<Long>()
    private val txOutFrags = HashMap<Long, Int>()
    private var txOk = 0
    private var txNoMk = 0
    private var txCons = 0
    private var txAgree = 0
    private var txGroupsSeen = 0
    private var txLastMarkerSeq: Long? = null
    private var txLastAudioPos: Long? = null
    private var txAnchoredBurst: Long? = null
    /** VAD turn ordinal bookkeeping for the tx source keys:
     * incremented at every VAD turn start; the completed
     * turn's ordinal + counted ms are captured at turn end. */
    private var currentTurnIdx = 0L
    private var lastCompletedTurnIdx: Long? = null
    private var lastCompletedTurnCountedMs = 0L
    /** Wall of the completed turn's start (its marker's wall —
     * the true start, VAD-lag-corrected). Used as the tx source
     * entry's start: the service's lastUtteranceStartMs can
     * already belong to the NEXT turn when an end arrives after
     * the next start (pause shorter than the VAD tail). */
    private var lastCompletedTurnWallMs = 0L

    // ---- v1.0.21 (21h/21j) — independent lag reference ----
    /** Accepted boundary samples (tx + VAD burst + tc), most
     * recent 30 — the reference is the centre of their densest
     * 1200 ms cluster, NOT an EMA (an EMA creeps toward a
     * systematic bias) and NOT a median (a majority of accepts
     * can be +1-line errors). */
    private val bndSamples = ArrayList<Long>()
    /** Bootstrap candidate pool: lat of EVERY in-window tx
     * group per burst (recorded only while the reference is not
     * established). Each burst contributes one correct candidate
     * (clustering at the true lag) and a few wrong ones spread
     * out, so the densest cluster is the true lag even when the
     * seed is a whole line off. */
    private val seedCand = ArrayList<Long>()
    /** Last VAD burst the tracker paired (arrival wall, seq,
     * whether the VAD path paired it, its marker) — the tx
     * anchor applies post-hoc to this burst when its first
     * output-transcription fragment arrives. */
    private var lastBurstArrivalMs: Long? = null
    private var lastBurstSeqTx: Long? = null
    private var lastBurstPaired = false
    private var lastBurstMarker: TurnMarker? = null

    // ---- v1.0.22 — hold/queue redesign (sim1022–sim1025) ----
    /** Parallel SENTENCE grouping of the tx fragments (800 ms
     * gap = [TX_GAP_MS]) for the seed path: the anchor groups
     * ([txGroups]) close at 300 ms (T-a), but the seed must see
     * whole sentences — a 300 ms group is one fragment, and
     * seeding from it picks line 2 instead of line 1 (Ba, seed
     * err −2336 ms in the sim). Pure bookkeeping; no turn
     * attachment (only firstMs is ever read). */
    private val sentGroups = ArrayList<TxGroup>()
    private var sentCurGroup: TxGroup? = null
    private var sentLastFragAtMs: Long? = null
    /** Every tx-in fragment arrival (for the Rf1 pause
     * estimate); pruned to the last 10 minutes. */
    private val txFragArrivals = ArrayDeque<Long>()
    /** Rf1 prev-tracking: the previous tx-ok's audio position,
     * cumDub and group (its firstMs bounds the pause window). */
    private var rfPrevAudioPos: Long? = null
    private var rfPrevCumDubMs = 0L
    private var rfPrevGroupFirstMs: Long? = null
    private var rfMeas = 0
    /** Corrected end-lag of the most recently PLAYED burst
     * (play-anchored, stretch-corrected: playWall +
     * offset/speed − sourceWall − D). Drives the STRONG tail
     * valve (v1.0.23); 0 when the burst had no pairing entries.
     * The sim computes it for every burst; Kotlin only for
     * bursts with a record (marker-less bursts get 0 —
     * conservative: no false valve arms). */
    private var lastCorrectedEndLagMs = 0L
    /** Tail valve (V-c): ms of stale head-audio to drop at the
     * next burst(s); the last played burst's seq and the seq
     * the valve already armed from (one arm per play report). */
    private var pendingTailDropMs = 0L
    private var tailDroppedMs = 0L
    private var tailValveFires = 0
    /** v1.0.23 — skip-ahead đầu burst (sim1028 STRONG valve):
     * số lần + tổng ms đã bỏ ở ĐẦU burst vì burst bắt đầu trễ
     * >1000 ms so với lịch (utterance start + D). Cùng cơ chế
     * drop với tail valve (pendingTailDropMs) — chỉ khác lý do
     * arm. Hiển thị gộp vào "xả … ms" trên HUD. */
    private var skipHeadFires = 0
    private var skipHeadDroppedMs = 0L
    private var vbPlaySeq: Long? = null
    private var vbValveUsedSeq: Long? = null
    /** v1.0.23 — thiết bị đo tốc độ stretch THẬT (xem
     * VoicePlayer): tốc độ target của tracker, tốc độ đọc lại
     * từ track (readback), tốc độ thật đo bằng độ dốc playhead
     * (slope), và cờ stretch có được hỗ trợ không. Service đẩy
     * vào đây mỗi giây (level ticker) + mỗi lần burst phát
     * (onBurstPlayed). HUD hiện cả target lẫn thật để vạch mặt
     * trường hợp HUD cũ in 1,50× trong khi track kẹt 1,0×. */
    private var lastAppliedReadback: Double? = null
    private var lastMeasuredSpeed: Double? = null
    private var lastStretchSupported: Boolean? = null
    /** Sa2 seed v2: arrival wall of the session's first dub
     * chunk; the first paired slice sample re-seeds from it.
     * Set once per session (stop() clears it; reconnect/stall
     * resets do NOT re-arm it). */
    private var saFirstArrivalMs: Long? = null
    private var saPending = false
    /** S-b slice referee: per-burst slice samples (non-boundary
     * only, accepted or D-check-rejected alike), first/last,
     * and the tx-ok lat of each burst; finalised burst records
     * and the per-burst median history. */
    private data class SbBurstRec(
        val seq: Long,
        val med: Long?,
        var latTx: Long?
    )
    private val sbSamples = HashMap<Long, MutableList<Long>>()
    private val sbFirst = HashMap<Long, Long>()
    private val sbLast = HashMap<Long, Long>()
    private val sbLatOk = HashMap<Long, Long>()
    private val sbBursts = ArrayList<SbBurstRec>()
    private val sbHist = ArrayDeque<Long>()
    private var sbLastSeq: Long? = null
    private var sbTrialActive = false
    private var sbTrialRef: Long? = null
    private var sbTrialLeft = 0
    private var sbTrialBaseRef: Long? = null
    private var sbTrialBaseMed: Long? = null
    private var sbTrialRun = 0
    private var sbTrialMaxRun = 0
    private val sbTrialBursts = ArrayList<SbBurstRec>()
    private var sbTrials = 0
    private var sbAdopts = 0
    private var sbAdoptedRef: Long? = null
    private var sbCooldown = 0

    // ---- v1.0.21 (21d) — sync-error history ----
    /** Burst-start sync errors ((play − marker wall) − D), one
     * per burst that played with a marker. */
    private val syncErrs = ArrayDeque<Long>()
    /** Slice-play errors ((play-anchored sample) − D) for every
     * paired 500 ms unit of every played burst. */
    private val slicePlayErrs = ArrayDeque<Long>()
    /** Per-burst END lateness: the last slice-play error of
     * each burst (the voice lateness at the END of the
     * sentence — what the user hears as drift). */
    private val endLatenessErrs = ArrayDeque<Long>()
    /** D samples (now, D) recorded by [diagnostics] (~1/s) for
     * the D-pinned fraction. */
    private val dHistory = ArrayDeque<Pair<Long, Long>>()
    /** All accepted turn-boundary pair times this session (for
     * the pairs-in-first-60-s count). */
    private val turnPairTimesAll = ArrayList<Long>()
    /** Session start wall (first VAD turn start) and the first
     * measured sync error's wall, for the "first sync" time. */
    private var sessionStartMs = 0L
    private var firstSyncAtMs: Long? = null

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
     * already corrected to the event wall by the service — plus
     * that event wall itself (v1.0.22: the continuity check in
     * [tryComputeFrameResidual] needs it to tell a real residual
     * from a video that seeked / reloaded / changed episode
     * between the two events). */
    private val markerVideoTimes = ArrayDeque<Triple<Long, Double, Long>>()
    private val burstVideoTimes = ArrayDeque<Triple<Long, Double, Long>>()
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

    // v1.0.23 — TRỄ CỐ ĐỊNH: hàm kẹp D từ đo đạc (capDelayMs)
    // đã gỡ. D khởi tạo ở giá trị phiên (mặc định 40 s, xem
    // [Prefs]) và chỉ [adjustManual] (nút ±1 s của user, 35–45 s)
    // mới đổi nó. Các mẫu trễ/slice/tx vẫn chạy để chẩn đoán +
    // tính hold, nhưng không bao giờ lái D nữa — đó chính là
    // điểm sửa gốc của v1.0.23 (D kẹt dưới lag thật + ratchet
    // ở v1.0.22).

    /** v1.0.18 — D-based lag: D − dynamic margin. The delay is the
     * EMA of the accepted boundary/slice samples + margin, so D −
     * margin is the best available estimate of the current lag
     * whenever the slice median is untrustworthy. */
    private fun parentDBasedLagMs(): Long =
        maxOf(MIN_SAMPLE_MS, delayMs - dynamicMarginMs())

    /** v1.0.21 (21h/21j) — centre (median) of the densest
     * [CLUSTER_WINDOW_MS] window of the values. */
    private fun clusterCentre(vals: List<Long>): Long {
        val s = vals.sorted()
        var bestI = 0
        var bestN = 0
        for (i in s.indices) {
            val v = s[i]
            var n = 0
            for (u in s) if (u in v..v + CLUSTER_WINDOW_MS) n++
            if (n > bestN) {
                bestI = i
                bestN = n
            }
        }
        val lo = s[bestI]
        val cluster = s.filter { it in lo..lo + CLUSTER_WINDOW_MS }
        return cluster[cluster.size / 2]
    }

    /** v1.0.21 (21h/21j/21n/21o) — independent lag reference.
     * Once the tx anchor is established (tx_ok > 0 and >= 5
     * boundary samples): the centre of the densest cluster of
     * the accepted boundary samples.
     * v1.0.22 (Rc2): the bootstrap pool ([seedCand]) is NO
     * LONGER mixed in once established — the pool freezes at
     * the wrong mode when the seed is wrong, and keeping it
     * locks the reference there (round-2 D-ratchet).
     * v1.0.22 (S-b): a running trial's reference overrides
     * everything end-to-end; an adopted reference reads the
     * median of boundary samples near it once they accumulate.
     * During bootstrap: the densest cluster of the candidate
     * pool (every in-window tx group per burst, trimmed to one
     * per burst by Bb). Then the seed lag; then the parent's
     * D-based lag. */
    private fun refLagMs(): Long {
        if (sbTrialActive && sbTrialRef != null) return sbTrialRef!!
        if (sbAdoptedRef != null) {
            val near = bndSamples.filter {
                kotlin.math.abs(it - sbAdoptedRef!!) <= CLUSTER_WINDOW_MS
            }.sorted()
            if (near.size >= 3) return near[near.size / 2]
            return sbAdoptedRef!!
        }
        if (txOk > 0 && bndSamples.size >= 5) {
            return clusterCentre(bndSamples)
        }
        if (seedCand.size >= 8) {
            return clusterCentre(seedCand)
        }
        seedLagMs?.let { return it }
        return parentDBasedLagMs()
    }

    /** v1.0.21 (21h) — the reference is established once the tx
     * anchor has accepted and >= 5 boundary samples exist. */
    private fun refEstablished(): Boolean =
        txOk > 0 && bndSamples.size >= 5

    /** v1.0.21 (21n/21o) — the D-based lag the VAD/tc/slice
     * D-checks, the ratio gate and the re-anchors read. It
     * returns the cluster reference ONLY once the tx anchor is
     * established (tx_ok > 0 — one accepted tx sample proves
     * the tx path works on this world); before that, including
     * on VAD-only worlds where the tx anchor never accepts, it
     * keeps the parent's D-based lag. The seed must never leak
     * into the VAD path. */
    private fun dBasedLagMs(): Long =
        if (txOk > 0) refLagMs() else parentDBasedLagMs()

    /** v1.0.21 (21f/21h) — lag used to SELECT sentences/markers
     * and to validate tx samples. The cluster reference (seed
     * as bootstrap) — independent of D. D is deliberately NOT
     * used here: D is fed by the samples these selections
     * produce, so steering the selection by D is a feedback
     * loop. Falls back to the parent's D-based lag when no seed
     * exists yet (the sim's _selLag fallback — this keeps it
     * from recursing). */
    private fun selLagMs(): Long = refLagMs()

    /** v1.0.19 — D is ALWAYS a trusted second opinion now. The
     * v1.0.18 gate disabled the D-check exactly when it was most
     * needed (D moves make it false; a wrong anchor makes the
     * last boundary disagree). The D-check is the only ABSOLUTE
     * guard (the anchor-implied check is tautological when the
     * anchor is wrong). A genuine lag transient moves the lag
     * <200 ms per burst interval, far inside the 1500 ms
     * D-check window. Signature and all call sites unchanged. */
    private fun isDTrusted(nowMs: Long): Boolean = true

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

    /** v1.0.21 (21a) — D-BASED + MONOTONE selection (the PRIMARY
     * path for bursts and turnCompletes): the marker whose
     * boundary (start for a burst, end for a turnComplete) is
     * nearest (now − selection lag), among markers NEWER than
     * the last accepted one, residual ≤ 1500 ms. The
     * anchor-implied path is self-referential when the anchor
     * drifts (device: no-mk 28/75, cons 19/75) — the selection
     * lag is absolute. Null when nothing qualifies. */
    private fun selectDBased(nowMs: Long, isTc: Boolean): TurnMarker? {
        val dlag = selLagMs()
        val target = if (isTc) nowMs - TURN_COMPLETE_GAP_MS - dlag else nowMs - dlag
        var best: TurnMarker? = null
        var bestResidual: Long? = null
        for (marker in markers) {
            if (isTc && (!marker.done || marker.countedMs <= 0L)) continue
            val lastAcc = lastAcceptedSeq
            if (lastAcc != null && marker.seq <= lastAcc) continue
            val pos = if (isTc) marker.wallMs + marker.countedMs else marker.wallMs
            val residual = kotlin.math.abs(pos - target)
            if (bestResidual == null || residual < bestResidual) {
                best = marker
                bestResidual = residual
            }
        }
        if (best == null || (bestResidual ?: Long.MAX_VALUE) > DBASED_RES_MS) {
            return null
        }
        return best
    }

    /** v1.0.21 (21a) — record an accepted marker's sequence so
     * later D-based selections only consider newer markers. */
    private fun noteAccepted(seq: Long?) {
        if (seq != null && (lastAcceptedSeq == null || seq > lastAcceptedSeq!!)) {
            lastAcceptedSeq = seq
        }
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
     *
     * v1.0.20 — EPISODE-GATED (the v1.0.19 non-self-healing lock):
     * the old code did NOT refresh [lastSpeechSliceAtMs], so once
     * the source went >10 s stale, EVERY later turn start and
     * every dub chunk re-fired the reset, wiping the accumulating
     * speech before a 500 ms slice could ever complete — permanent
     * death (device 28/11: "stall 159", 1 sample/60 s, 5 bursts).
     * Now the reset is a no-op while already [stalled] (count
     * EPISODES, not invocations) and it refreshes
     * [lastSpeechSliceAtMs] to now, so the stall condition clears
     * and the next speech can accumulate into a slice.
     */
    private fun resetForStall(nowMs: Long) {
        // v1.0.20 — episode gate: while already stalled this is
        // a no-op (the state was already wiped by the first reset
        // of this episode; wiping again would kill the speech
        // accumulating since).
        if (stalled) return
        stalled = true
        stallEpisodes++
        // v1.0.19 — stall-reset counter (diagnostics; invocations,
        // kept for debugging — the HUD shows episodes now).
        stallResets++
        // v1.0.20 — refresh the slice clock: the reset IS the
        // acknowledgement of the stall, so the next speech frame
        // must not re-trigger it before a slice can complete.
        lastSpeechSliceAtMs = nowMs
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
        // v1.0.22 — the tail valve's pending drop and end-lag
        // belong to the stalled burst; the next chunk starts a
        // new turn and must not be eaten or stretched on a stale
        // backlog. Sa2 / S-b state survives (once per run).
        pendingTailDropMs = 0L
        lastCorrectedEndLagMs = 0L
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
        // v1.0.19 — split ATTEMPT: the gap condition met with an
        // open marker, whether or not the counted>0 guard below
        // lets the split fire (the head chunk's marker was just
        // created with counted 0). Attempts vs splits tells a
        // merged-turn screenshot apart from a no-silence one.
        // v1.0.21 (21c) — SPLIT-ATTEMPT COUNTING, both ways: the
        // v1.0.20 code counted BEFORE the counted>0 guard, so
        // every turn start wasted one attempt (device: 85
        // attempts, 4 splits). The fixed count only counts when
        // counted>0 (then attempts == splits by construction);
        // both are kept for comparison.
        if (lastMarker != null && !lastMarker.done &&
            lastLoudAtMs > 0L && nowMs - lastLoudAtMs >= SPLIT_SILENCE_MS
        ) {
            splitAttemptsOld++
        }
        if (lastMarker != null && !lastMarker.done && lastMarker.countedMs > 0L &&
            lastLoudAtMs > 0L && nowMs - lastLoudAtMs >= SPLIT_SILENCE_MS
        ) {
            splitAttempts++
            lastMarker.done = true
            markerSeq++
            markers.addLast(
                TurnMarker(
                    audioMs = cumSpeechMs,
                    wallMs = nowMs,
                    countedMs = 0L,
                    done = false,
                    seq = markerSeq,
                    turnIdx = lastMarker.turnIdx
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
            // v1.0.20 — a slice completed: the stall episode is
            // over (the source is speaking again and pairing can
            // resume). This is what makes the stall self-healing.
            stalled = false
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
        } else {
            // v1.0.20 — no reset fired: the source is not stalled
            // (a slice arrived within 10 s), so clear the episode
            // flag. The turn start itself proves speech is flowing.
            stalled = false
        }
        // v1.0.21 — session start (for the first-60-s pair count
        // and the first-sync time) and the VAD turn ordinal (the
        // tx anchor keys its source entries by it).
        if (sessionStartMs == 0L) sessionStartMs = nowMs
        currentTurnIdx++
        markerSeq++
        markers.addLast(
            TurnMarker(
                audioMs = cumSpeechMs,
                wallMs = nowMs,
                countedMs = 0L,
                done = false,
                seq = markerSeq,
                turnIdx = currentTurnIdx
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
                // v1.0.21 (21b) — the completed turn's ordinal +
                // counted ms: noteTxSource (called by the service
                // right after this) keys its entry by them.
                lastCompletedTurnIdx = marker.turnIdx
                lastCompletedTurnCountedMs = marker.countedMs
                lastCompletedTurnWallMs = marker.wallMs
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
        // v1.0.22 (Sa2) — the first dub chunk's arrival wall is
        // the seed-correction anchor: the first paired slice
        // sample re-seeds from it (see [feedSample]). Set once
        // per session (stop() clears it; reconnect/stall resets
        // do not re-arm it).
        if (saFirstArrivalMs == null) {
            saFirstArrivalMs = nowMs
            saPending = true
        }
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
            // v1.0.21 (21g) — seed anchored to the FIRST MARKER
            // wall (first turn start ~= first line start — the
            // same reference every sample uses), NOT to the first
            // sent audio: the first sent audio can be MUSIC before
            // the first line (~2.5 s in on the device world, so
            // the old seed was ~7080 ms vs a true lag ~4600 ms).
            // A wrong seed poisons every (21f) reference. Falls
            // back to the first sent audio when no marker exists
            // yet (e.g. a stall reset cleared them).
            val seedRefMs = markers.firstOrNull()?.wallMs ?: firstAudioSentAtMs
            val elapsed = (nowMs - seedRefMs).coerceIn(0L, 18000L)
            // v1.0.18 — the seed lag (first dub arrival − first
            // line start ≈ true lag L0) is the bootstrap reference
            // for the first boundary (see seedMarker): it points at
            // the right turn while the lag is still near L0.
            seedLagMs = elapsed
            latencyEma.seed(elapsed)
            // v1.0.23 — TRỄ CỐ ĐỊNH: D KHÔNG đổi theo seed nữa.
            // Seed lag vẫn là bootstrap reference cho marker
            // selection (seedMarker), nhưng hold tính từ D cố
            // định của phiên: hold = D − latency − pipeline, để
            // giọng đầu tiên đã phát đúng (utterance start + D).
            val newDelay = delayMs
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
            // v1.0.21 (21b) — remember this burst for the post-hoc
            // tx anchor: its arrival wall, its seq, and whether
            // the VAD path paired it (the tx anchor only adds a
            // pair when the VAD path did not).
            val burstOkBefore = burstOk
            val (burstHold, marker) = onDubBurst(nowMs)
            hold = burstHold
            burstMarker = marker
            lastBurstArrivalMs = nowMs
            lastBurstSeqTx = burstSeq
            lastBurstPaired = burstOk > burstOkBefore
            lastBurstMarker = marker
        } else if (isFirstChunk) {
            // v1.0.17 — the session's (or post-reconnect) first
            // chunk is not a burst by the old test, but it still
            // starts a voice burst physically: record it so its
            // play report measures the (cold-start) pipeline.
            // No marker yet — the play still yields the pipeline,
            // just no sync sample.
            burstSeq++
        }
        // v1.0.23 — SKIP-AHEAD đầu burst (sim1028 STRONG valve):
        // burst này bắt đầu trễ hơn lịch (utterance start + D)
        // quá 1000 ms thì bỏ tối đa 2000 ms (và tối đa 50% độ
        // dài giọng ước tính của burst) ở ĐẦU burst để phần còn
        // lại kịp về đúng giờ. Với D cố định 40 s, startLate
        // = latency − D nên bình thường âm sâu (latency chỉ
        // 4–12 s) — van chỉ nổ khi dịch kẹt >41 s (lỗi), đúng
        // vai trò lưới an toàn. Arm vào cùng pendingTailDropMs
        // như tail valve nên chunk đầu burst này (kể cả chunk
        // hiện tại, vì phép drop ở cuối hàm này chạy sau) bị bỏ.
        if (isBurst && burstMarker != null) {
            val startLate = (nowMs + hold) - (burstMarker!!.wallMs + delayMs)
            if (startLate > SKIP_AHEAD_THRESH_MS) {
                val r = ratio()
                val estDubMs = if (r > 0.0) {
                    (burstMarker!!.countedMs / r).toLong()
                } else {
                    0L
                }
                val drop = minOf(
                    startLate - SKIP_AHEAD_KEEP_MS,
                    SKIP_AHEAD_MAX_MS,
                    (estDubMs / 2L).coerceAtLeast(0L)
                )
                if (drop > 0L) {
                    pendingTailDropMs += drop
                    skipHeadDroppedMs += drop
                    skipHeadFires++
                    Log.i(TAG, "skip-ahead burst seq=%d startLate=%d ms drop=%d ms (estDub=%d ms)"
                        .format(burstSeq, startLate, drop, estDubMs))
                }
            }
        }
        // v1.0.22 (S-b) — a new burst starts: finalise the
        // previous burst's slice record (its samples are
        // complete; its tx anchor, if any, landed during it) and
        // remember this seq. Runs AFTER the new burst's anchor
        // work above, like the sim's on_dub_burst (finalise
        // after super()).
        if (isBurst || isFirstChunk) {
            sbOnBurstStart(nowMs)
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
        // v1.0.22 (V-c) — tail valve: drop this whole chunk from
        // playback while an armed amount remains (the sim drops
        // whole chunks in its loop AFTER on_dub ran, so the chunk
        // still counts in cumDubMs/pairing — only the BYTES are
        // discarded; the service sees the -1 return and skips
        // playAudio). The armed amount decreases by the full
        // chunk (it may go negative — the next chunk then plays,
        // exactly like the sim).
        if (pendingTailDropMs > 0L) {
            pendingTailDropMs -= dubMs
            tailDroppedMs += dubMs
            // A drop is a dropped slice in the HUD count
            // ("bỏ … lát") — the sim's dropped-audio metric
            // counts valve drops the same way.
            droppedSlices++
            return -1L
        }
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
    fun onBurstPlayed(seq: Long, playWallMs: Long, pipelineMs: Long, estimated: Boolean, backlogMs: Long = 0L, trueSpeed: Double = 0.0) {
        // v1.0.23 — thiết bị đo tốc độ THẬT: tốc độ đo bằng độ dốc
        // playhead mà VoicePlayer gửi kèm (0.0 = chưa đo được).
        // Dùng nó — không dùng target — để chia offset dub khi tính
        // playSliceWall, và để HUD đối chiếu target vs thật. Nếu
        // chưa đo được thì rơi về target như cũ (và số cuối-burst
        // mang cờ "đo bằng tốc độ giả định" qua lastPipelineEstimated
        // / diagnostics).
        if (trueSpeed in 0.5..2.0) {
            lastMeasuredSpeed = trueSpeed
            if (kotlin.math.abs(trueSpeed - targetSpeed) > 0.05) {
                Log.w(TAG, "applied speed mismatch: target=%.2f measured=%.2f (burst seq=%d)"
                    .format(targetSpeed, trueSpeed, seq))
            }
        }
        lastPipelineMs = pipelineMs.coerceIn(0L, 2000L)
        lastPipelineEstimated = estimated
        // v1.0.18 — the audio already queued ahead of this burst's
        // first frame at write time (the VoicePlayer reads the
        // playhead before writing): it separates backlog from
        // hardware in the "phát" diagnostic ("chờ Z ms").
        lastBacklogMs = backlogMs.coerceAtLeast(0L)
        // v1.0.19 — the BURST-FIRST pipeline must NOT feed the
        // EMA: the burst-first chunk renders after an idle loop
        // (~50 ms) while the typical pipeline is ~1 s, so feeding
        // it drags the EMA to ~700 ms while the true typical
        // pipeline is ~1125 ms. Only mid-burst samples (via
        // onPipelineSample, every 20th chunk from the VoicePlayer)
        // feed the EMA now. The burst pipeline still seeds the
        // EMA (first burst only) and still feeds lastPipelineMs /
        // the sync error below.
        if (pipelineEmaMs == null) {
            pipelineEmaMs = lastPipelineMs
        }
        applyDynamicMargin()
        lastBurstPlayWallMs = playWallMs
        val record = pendingBursts.firstOrNull { it.seq == seq }
        // Prune this burst and everything older: their reports
        // will never arrive now (or arrived already).
        while (pendingBursts.isNotEmpty() && pendingBursts.first().seq <= seq) {
            pendingBursts.removeFirst()
        }
        // v1.0.22 — the play seq + corrected end-lag (V-b/V-c
        // input) and the play-anchored slice samples. These are
        // computed for EVERY burst with a record, marker or not:
        // the sim computes the end-lag from the pairing log
        // alone, and only the sync error below needs the marker.
        // Default end-lag 0 (no pairing entries → no drift
        // signal, no valve arm — conservative: a marker-less
        // burst never arms the valve on an unmeasurable lag).
        // The speed used is the MEASURED applied speed when the
        // VoicePlayer managed to measure it (v1.0.23 — the
        // tracker target is NOT the applied speed: on the 12:00
        // device photo the HUD printed 1.50x while the track may
        // have been stuck at 1.0x, which alone explains the
        // ~1800 ms unexplained within-burst growth: dividing by
        // 1.5 instead of 1.0 understates end-lag by 33% of the
        // dub length in the burst). Fall back to the target only
        // when nothing was measured.
        vbPlaySeq = seq
        lastCorrectedEndLagMs = 0L
        if (record != null) {
            val playSpeed = (lastMeasuredSpeed?.takeIf { it in 0.5..2.0 }
                ?: targetSpeed).coerceIn(1.0, 2.0)
            val slices = pairingLog.filter { it.burstSeq == seq }
                .sortedBy { it.dubCumMs }
            if (slices.isNotEmpty()) {
                val samples = slices.map { entry ->
                    val playSliceWall = playWallMs +
                        ((entry.dubCumMs - record.startCumDubMs) / playSpeed).toLong()
                    playSliceWall - entry.sourceWallMs
                }
                lastPlaySliceSampleMs = samples.last()
                playDriftMs = if (samples.size >= 2) {
                    samples.last() - samples.first()
                } else {
                    0L
                }
                // v1.0.21 (21d) — slice-play errors ((play sample)
                // − D) and the per-burst END lateness (the last
                // slice-play error of this burst — the voice
                // lateness at the END of the sentence).
                val sliceErrs = samples.map { it - delayMs }
                for (e in sliceErrs) {
                    slicePlayErrs.addLast(e)
                }
                while (slicePlayErrs.size > MAX_SLICE_PLAY_HISTORY) slicePlayErrs.removeFirst()
                endLatenessErrs.addLast(sliceErrs.last())
                while (endLatenessErrs.size > MAX_SYNC_ERR_HISTORY) endLatenessErrs.removeFirst()
                lastCorrectedEndLagMs = sliceErrs.last()
            }
        }
        if (record?.markerWallMs != null) {
            // v1.0.20 — GARBAGE GUARD (Error 1): a sync error
            // beyond ±60 s cannot be real (the v1.0.19 clock bug
            // produced -135,043,353 ms). Drop it (show "—") and
            // log the raw inputs so the next diagnosis has them;
            // never feed it to the stretch controller either
            // (stretchUpdate treats null as 0).
            val syncErr = (playWallMs - record.markerWallMs!!) - delayMs
            lastSyncErrorMs = if (kotlin.math.abs(syncErr) > SYNC_GARBAGE_MS) {
                Log.w(TAG, "garbage sync error dropped: playWall=%d markerWall=%d D=%d err=%d (clock type: playWall from VoicePlayer readPlayhead, nanoTime base)"
                    .format(playWallMs, record.markerWallMs!!, delayMs, syncErr))
                null
            } else {
                syncErr
            }
            // v1.0.21 (21d) — keep the sync error for the
            // max/p90 diagnostics (the end-of-session photo hides
            // mid-session drift; these numbers do not).
            if (lastSyncErrorMs != null) {
                syncErrs.addLast(lastSyncErrorMs!!)
                while (syncErrs.size > MAX_SYNC_ERR_HISTORY) syncErrs.removeFirst()
                if (firstSyncAtMs == null) firstSyncAtMs = playWallMs
            }
        }
        // Pairing-log entries of this burst and older are spent.
        while (pairingLog.isNotEmpty() &&
            pairingLog.first().burstSeq <= seq
        ) {
            pairingLog.removeFirst()
        }
        // v1.0.19 — steer the time-stretch speed from the fresh
        // sync error / backlog.
        stretchUpdate(playWallMs)
    }

    /** v1.0.19 — current time-stretch target speed (1.0 = no
     * stretch). The service reads this and applies it to the
     * VoicePlayer drain; the tracker only decides the speed. */
    val currentStretchSpeed: Double get() = targetSpeed

    /**
     * v1.0.23 — THIẾT BỊ ĐO TỐC ĐỘ ÁP DỤNG THẬT (service đẩy vào
     * mỗi giây + mỗi burst phát): tốc độ đọc lại từ AudioTrack
     * sau setPlaybackParams ([readback], 0.0 = chưa đọc được),
     * tốc độ thật đo bằng độ dốc playhead ([measured], 0.0 =
     * chưa đo được), và cờ stretch có được hỗ trợ trên máy này
     * không. Chỉ lưu để chẩn đoán/HUD — KHÔNG lái tốc độ target
     * (target vẫn do [stretchUpdate]/[ratioFloorMs] quyết).
     */
    @Synchronized
    fun noteAppliedSpeed(readback: Double, measured: Double, supported: Boolean) {
        if (readback in 0.5..2.0) lastAppliedReadback = readback
        if (measured in 0.5..2.0) lastMeasuredSpeed = measured
        lastStretchSupported = supported
    }

    /**
     * v1.0.19 — MID-BURST PIPELINE SAMPLE. The burst-first
     * chunk's pipeline (~50 ms — the loop is idle at burst
     * starts) systematically UNDER-measures the typical pipeline
     * (~1 s: the backlog builds up as a burst's chunks arrive
     * compressed). The hold subtracts the pipeline EMA, so the
     * under-measurement plays the voice ~800 ms late against the
     * picture. The player reports the pipeline of every 20th
     * chunk (a mid-burst chunk); only those samples feed the EMA
     * (0.3 weight, capped at 1000 ms — same formula as
     * onBurstPlayed's old EMA update), so the EMA tracks the
     * TYPICAL pipeline, not the burst-first one.
     */
    @Synchronized
    fun onPipelineSample(pipelineMs: Long, backlogMs: Long = 0L) {
        val sample = pipelineMs.coerceIn(0L, 2000L)
        lastPipelineMs = sample
        lastBacklogMs = backlogMs.coerceAtLeast(0L)
        val ema = pipelineEmaMs
        pipelineEmaMs = if (ema == null) {
            sample
        } else {
            ((ema * 7 + sample * 3) / 10).coerceIn(0L, 1000L)
        }
        applyDynamicMargin()
    }

    /**
     * v1.0.19 — FAST level demand: the track level (audio queued
     * in the track) is read per chunk, unlike the sync error
     * (per burst). A high level means the queue is growing
     * DURING a line — raise the speed at once instead of waiting
     * for the next burst play. Thresholds are fractions of the
     * buffer cap (the level saturates at the cap, so absolute ms
     * thresholds would never fire at a small minBuf).
     * Raise-only; the lowering is the hysteresis + decay in
     * [stretchUpdate]. The raise caps at [STRETCH_LEVEL_CAP]
     * (1.25): a full buffer alone does not justify the fastest
     * stretch. v1.0.23: the error-driven path ALSO caps at 1.25
     * now ([MAX_STRETCH_SPEED] = 1.25 — 1.5x méo giọng, user
     * 12:11) — there is no path to 1.5x left in the tracker.
     */
    @Synchronized
    fun noteLevel(levelMs: Long, capMs: Long, nowMs: Long) {
        if (capMs <= 0L) return
        val frac = levelMs.toDouble() / capMs.toDouble()
        val wantRaw = if (frac > 0.85) MAX_STRETCH_SPEED
            else if (frac > 0.6) 1.20
            else if (frac > 0.3) 1.10
            else return
        val want = minOf(wantRaw, STRETCH_LEVEL_CAP)
        if (want > targetSpeed) {
            targetSpeed = want
            levelRaiseAtMs = nowMs
        }
    }

    /**
     * v1.0.19 — LATENESS-DRIVEN stretch. The loop lateness (how
     * far behind the player loop is when a chunk is due) cleanly
     * separates a 2.5x generation burst (median ~330 ms) from a
     * 1.5x one (~40 ms); the sync error and track backlog overlap
     * between the two speeds. When the loop is >200 ms behind,
     * raise the speed to [MAX_STRETCH_SPEED] (v1.0.23: 1.25x, was
     * 1.5x) so the drain outruns the burst; the level-raise hold
     * in [stretchUpdate] keeps it from being lowered again for 5 s.
     */
    @Synchronized
    fun noteLateness(lateMs: Long, nowMs: Long) {
        if (lateMs > LATENESS_STRETCH_MS && targetSpeed < MAX_STRETCH_SPEED) {
            targetSpeed = MAX_STRETCH_SPEED
            levelRaiseAtMs = nowMs
        }
    }

    /**
     * v1.0.19 — time-stretch controller: target speed from the
     * last closed-loop sync error + the last track backlog, with
     * hysteresis bands (up at 500/1200/2500 ms, down below
     * 200/600/1500 ms) and a staleness decay (no burst play for
     * >5 s steps the speed back toward 1.0). Called at the end
     * of [onBurstPlayed] and at the end of [flushWindowIfDue].
     */
    private fun stretchUpdate(nowMs: Long) {
        lastStretchUpdateMs = nowMs
        val err = lastSyncErrorMs ?: 0L
        val backlog = lastBacklogMs ?: 0L
        val want = if (err > 2500 || backlog > 4000) MAX_STRETCH_SPEED
            else if (err > 1200 || backlog > 2500) 1.20
            else if (err > 500 || backlog > 1200) 1.10
            else 1.00
        if (want > targetSpeed) {
            targetSpeed = want
        } else if (want < targetSpeed) {
            // Do not lower the speed while a level demand is fresh
            // (raised within the last 5 s): the queue is still
            // growing during the line, and lowering here would undo
            // noteLevel at every burst play (the stretch was stuck
            // at 1.0 for the whole session in the sim).
            if (levelRaiseAtMs != null && nowMs - levelRaiseAtMs!! <= 5000L) {
                return
            }
            val downOk = when (targetSpeed) {
                MAX_STRETCH_SPEED -> err < 1500 && backlog < 2500
                1.20 -> err < 600 && backlog < 1200
                1.10 -> err < 200 && backlog < 600
                else -> true
            }
            if (downOk) targetSpeed = want
        }
        // Staleness decay: the sync error is only as fresh as the
        // last burst play; a stale error must not hold the speed up.
        if (lastBurstPlayWallMs != null && nowMs - lastBurstPlayWallMs!! > 5000L &&
            targetSpeed > 1.0
        ) {
            targetSpeed = maxOf(1.0, kotlin.math.round((targetSpeed - 0.10) * 100) / 100)
        }
        // v1.0.22 (V-a) — ratio-baseline stretch, max-wins:
        // the dub runs ~1.42x the source, so the floor is the
        // measured ratio (1/ratio(), clamped [1.0, 1.25]);
        // it decays to 1.0 when silent >5 s (no burst play —
        // the floor is a dubbing speed, not an idle speed).
        // Applied AFTER the base logic (including the decay
        // above), like the sim's _stretch_update: the lowering
        // paths may never pull the speed below the floor while
        // dubbing is active. v1.0.23: the V-b catch-up
        // multiplier is GONE (sim1028 ship policy — speed =
        // dub length / remaining window to the source line end,
        // capped 1.25); the floor here IS that policy for an
        // on-time burst (window = line length, speed = ratio).
        // v1.0.23: MAX_STRETCH_SPEED is 1.25 (was 1.5 — user
        // 12:11 "Giọng bị méo khi bắt kịp").
        val floor = ratioFloorMs(nowMs)
        if (floor > targetSpeed) targetSpeed = floor
    }

    /** v1.0.22 — the V-a stretch floor (see [stretchUpdate]).
     * v1.0.23 — V-b catch-up multiplier ĐÃ GỠ (sim1028 ship
     * policy: tốc độ = độ dài giọng / cửa sổ còn lại tới cuối
     * câu nguồn, kẹp 1,25× — với burst bắt đầu đúng giờ thì cửa
     * sổ còn lại đúng bằng độ dài câu nguồn nên tốc độ = đúng
     * tỉ lệ giọng/gốc đo được; burst bắt đầu trễ được bù bằng
     * các dải lỗi của [stretchUpdate] (tối đa 1,25×) + van
     * skip-ahead, không bằng nhân hệ số trôi. */
    private fun ratioFloorMs(nowMs: Long): Double {
        if (lastBurstPlayWallMs != null &&
            nowMs - lastBurstPlayWallMs!! > 5000L
        ) {
            return 1.0
        }
        return (1.0 / ratio()).coerceIn(1.0, MAX_STRETCH_SPEED)
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
     * v1.0.22 — CONTINUITY CHECK: the video normally advances
     * ~1x between the two event walls, so (vBurst − vMarker) must
     * roughly equal (burstWall − markerWall). A video that
     * seeked / reloaded / changed episode in between jumps by
     * tens of seconds (the 09:10 device photo: -52 s while the
     * sync error was +13 ms) — the residual is dropped, not
     * displayed. A paused video also fails the check (video
     * time frozen while the wall advances), which is correct:
     * the residual is meaningless while the picture is frozen.
     */
    @Synchronized
    fun setMarkerVideoTime(markerSeq: Long, videoTimeSec: Double, eventWallMs: Long = 0L) {
        markerVideoTimes.addLast(Triple(markerSeq, videoTimeSec, eventWallMs))
        while (markerVideoTimes.size > MAX_VIDEO_TIME_RECORDS) {
            markerVideoTimes.removeFirst()
        }
        // A burst video time may already be waiting for this marker.
        for ((seq, _, _) in burstVideoTimes) {
            tryComputeFrameResidual(seq)
        }
    }

    @Synchronized
    fun onBurstVideoTime(seq: Long, videoTimeSec: Double, eventWallMs: Long = 0L) {
        burstVideoTimes.addLast(Triple(seq, videoTimeSec, eventWallMs))
        while (burstVideoTimes.size > MAX_VIDEO_TIME_RECORDS) {
            burstVideoTimes.removeFirst()
        }
        tryComputeFrameResidual(seq)
    }

    private fun tryComputeFrameResidual(seq: Long) {
        val markerSeq = recentBurstMarkers.firstOrNull { it.first == seq }?.second
            ?: return
        val vMarkerRec = markerVideoTimes.firstOrNull { it.first == markerSeq }
            ?: return
        val vBurstRec = burstVideoTimes.firstOrNull { it.first == seq }
            ?: return
        val vMarker = vMarkerRec.second
        val vBurst = vBurstRec.second
        // v1.0.22 — continuity: the video-time advance between
        // the two events must match the wall-clock advance
        // (1x playback). Tolerances (-3 s behind, +5 s ahead)
        // cover JS round-trip jitter, the service's correction
        // error, and short pauses. A seek / reload / episode
        // change fails it by tens of seconds and the residual
        // is dropped (shown as "—") with the raw inputs logged.
        // A paused video also fails it (video time frozen while
        // the wall advances) — correct, the residual is
        // meaningless while the picture is frozen. Event walls
        // of 0 mean "unknown" (old callers) — then the check is
        // skipped and only the magnitude guard below applies.
        val markerWall = vMarkerRec.third
        val burstWall = vBurstRec.third
        if (markerWall > 0L && burstWall > 0L) {
            val wallDeltaMs = burstWall - markerWall
            val videoDeltaMs = ((vBurst - vMarker) * 1000.0).toLong()
            if (wallDeltaMs < 0L ||
                videoDeltaMs < wallDeltaMs - 3000L ||
                videoDeltaMs > wallDeltaMs + 5000L
            ) {
                Log.w(TAG, "frame residual dropped: video jumped (vMarker=%.3f vBurst=%.3f videoDelta=%d ms wallDelta=%d ms D=%d seq=%d)"
                    .format(vMarker, vBurst, videoDeltaMs, wallDeltaMs, delayMs, seq))
                lastFrameResidualMs = null
                return
            }
        }
        // v1.0.20 — GARBAGE GUARD (Error 1, same poisoned wall as
        // the sync error: the service's video-time correction
        // inherits the play wall). v1.0.22 — tightened for the
        // frame residual: beyond ±10 s it cannot be real (a real
        // residual is bounded by ~D + drift); dropped (show "—")
        // and the raw inputs are logged.
        val residual = ((vBurst - vMarker) * 1000.0 - delayMs).toLong()
        lastFrameResidualMs = if (kotlin.math.abs(residual) > FRAME_GARBAGE_MS) {
            Log.w(TAG, "garbage frame residual dropped: vBurst=%.3f vMarker=%.3f D=%d residual=%d seq=%d"
                .format(vBurst, vMarker, delayMs, residual, seq))
            null
        } else {
            residual
        }
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
        // v1.0.21 (21a) — D-BASED + MONOTONE selection FIRST: the
        // marker nearest (now − selection lag) among markers newer
        // than the last accepted one. Then the anchor-implied path,
        // then the seed-lag fallback.
        val dBased = selectDBased(nowMs, isTc = true)
        if (dBased != null) {
            dBasedSel++
            acceptTurnComplete(dBased, nowMs, viaSeed = false, sPos = null, viaDBased = true)
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
        sPos: Long?,
        viaDBased: Boolean = false
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
        if (!viaSeed && !viaDBased) {
            // Anchor path: consistency against the anchor-implied
            // lag (wall of S, interpolated from the slices), not
            // the floor-pinned slice median.
            val ref = impliedLagMs(nowMs, sPos)
            if (kotlin.math.abs(latency - ref) > SAMPLE_CONSISTENCY_MS) {
                tcConsistency++
                return false
            }
        } else if (viaSeed) {
            // Seed-lag fallback recovery: cross-check against D
            // when D is trusted (a transient lag would make the
            // seed lag wrong by seconds).
            // (v1.0.21: a D-BASED marker gets neither check — the
            // sim's _accept_tc likewise checks only 'anchor' and
            // 'seed-fb'; the D-based pick is itself bounded by
            // the 1500 ms residual against the selection lag.)
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
        // v1.0.21 (21a) — the D-based selection only considers
        // markers newer than this one from now on.
        noteAccepted(marker.seq)
        recordTurnPair(nowMs)
        feedSample(latency, nowMs, boundary = true)
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
        // v1.0.23 — STRONG tail valve (sim1028 ship config (e)):
        // arm at the next burst boundary when the last PLAYED
        // burst drifted >1000 ms with the stretch already at the
        // 1.25x ceiling — the queue cannot drain by speed alone,
        // so up to 2000 ms of stale head-audio of this burst is
        // dropped instead (see the drop in [onDubAudioArrival]),
        // keeping 500 ms. One arm per play report (a report that
        // already armed never arms again); the arm reads only the
        // last play's end-lag + the current speed, so it runs
        // before every return path below, like the sim's
        // on_dub_burst (which arms after its anchor work
        // regardless of outcome). Safety net only: on-nominal
        // sim1028 (e) never fires it (0 fires).
        if (vbPlaySeq != null && vbPlaySeq != vbValveUsedSeq &&
            lastCorrectedEndLagMs > VALVE_THRESH_MS &&
            targetSpeed >= MAX_STRETCH_SPEED - 0.01
        ) {
            val drop = minOf(
                lastCorrectedEndLagMs - VALVE_KEEP_MS,
                VALVE_MAX_DROP_MS
            )
            if (drop > 0L) {
                pendingTailDropMs += drop
                tailValveFires++
                vbValveUsedSeq = vbPlaySeq
            }
        }
        // v1.0.19 — previous hold, for the hold slew limit (the
        // hold may not drop more than HOLD_SLEW_MS below it).
        val prevHoldMs = lastHoldMs
        if (!everExactAnchor) {
            // Before the first exact anchor a burst has no reliable
            // marker — except via the seed lag, which points at the
            // right turn while the lag is still near L0. Skip the
            // burst rather than mis-pair it.
            val marker = seedMarker(nowMs, isTc = false)
            if (marker == null) {
                burstNoMarker++
                // v1.0.19 — NO-MARKER RE-ANCHOR (see 5b below):
                // re-anchor at once to the D-based marker so the
                // next bursts find their markers again.
                reanchorToDMarker(nowMs)
                return lastHoldMs to null
            }
            val latency = nowMs - marker.wallMs
            if (latency !in 0L..MAX_SAMPLE_MS) {
                burstConsistency++
                burstDCheckStreak = 0
                return lastHoldMs to null
            }
            burstOk++
            burstDCheckStreak = 0
            lastBoundarySampleMs = latency
            lastBoundaryAtMs = nowMs
            // v1.0.21 (21a/21b) — monotone record + the marker
            // the tx anchor's agreement check reads.
            noteAccepted(marker.seq)
            lastBurstMarker = marker
            recordTurnPair(nowMs)
            feedSample(latency, nowMs, boundary = true)
            reanchor(marker.audioMs, nowMs, exact = true)
            noteBurstRatio(marker, latency)
            // v1.0.19 — ROBUST HOLD + SLEW (see robustHoldMs).
            return robustHoldMs(nowMs, prevHoldMs) to marker
        }
        // v1.0.21 (21a) — D-BASED + MONOTONE selection FIRST: the
        // marker nearest (now − selection lag) among markers newer
        // than the last accepted one. Then the anchor-implied path,
        // then the seed-lag fallback.
        var viaSeed = false
        var viaDBased = false
        val dBasedMarker = selectDBased(nowMs, isTc = false)
        val marker: TurnMarker
        val sPos: Long?
        if (dBasedMarker != null) {
            dBasedSel++
            viaDBased = true
            marker = dBasedMarker
            sPos = null
        } else {
            val (anchorMarker, anchorSPos) = selectByAnchor(nowMs, isTc = false)
            sPos = anchorSPos
            marker = if (anchorMarker != null) {
                anchorMarker
            } else {
                // Fallback: seed-lag marker (independent reference).
                // A wide anchor net only catches stale markers.
                val seed = seedMarker(nowMs, isTc = false)
                if (seed == null) {
                    burstNoMarker++
                    // v1.0.19 — NO-MARKER RE-ANCHOR (see 5b below).
                    reanchorToDMarker(nowMs)
                    return lastHoldMs to null
                }
                viaSeed = true
                burstFallback++
                seed
            }
        }
        // A marker older than the anchor (a late burst for an old
        // turn) or inconsistent with the anchor: no sample, and no
        // marker for the play report either — a sync error against
        // the wrong sentence would be worse than none. Only an
        // exact anchor may veto (here it always is exact).
        if (marker.audioMs < anchorSpeechMs && everExactAnchor) {
            burstAnchorBack++
            burstDCheckStreak = 0
            return lastHoldMs to null
        }
        val latency = nowMs - marker.wallMs
        if (latency !in 0L..MAX_SAMPLE_MS) {
            burstConsistency++
            burstDCheckStreak = 0
            return lastHoldMs to null
        }
        if (!viaSeed && !viaDBased) {
            val ref = impliedLagMs(nowMs, sPos)
            if (kotlin.math.abs(latency - ref) > SAMPLE_CONSISTENCY_MS) {
                burstConsistency++
                burstDCheckStreak = 0
                return lastHoldMs to null
            }
            // v1.0.20 — BURST D-CHECK BYPASS: the D-check is the
            // last gate, but a D pinned from a stale sample (the
            // v1.0.19 device test: D 8 s from a 7.2 s sample vs a
            // true lag ~2.4 s) rejects every honest burst forever
            // and D can never recover — a deadlock. After 3
            // consecutive D-check-only rejections the next burst
            // bypasses the D-check (the anchor-implied + range
            // checks above still apply). The bypassed burst's
            // ratio validation is skipped (see below).
            var bypassedDCheck = false
            if (isDTrusted(nowMs) &&
                kotlin.math.abs(latency - dBasedLagMs()) > BURST_D_CHECK_MS
            ) {
                if (burstDCheckStreak >= BURST_DCHECK_BYPASS_STREAK) {
                    bypassedDCheck = true
                    burstDCheckStreak = 0
                    Log.i(TAG, "burst D-check bypassed after %d streak rejections (latency=%d, dBased=%d)"
                        .format(BURST_DCHECK_BYPASS_STREAK, latency, dBasedLagMs()))
                } else {
                    burstConsistency++
                    burstDCheckStreak++
                    return lastHoldMs to null
                }
            } else {
                burstDCheckStreak = 0
            }
            burstOk++
            lastBoundarySampleMs = latency
            lastBoundaryAtMs = nowMs
            // v1.0.21 (21a/21b) — monotone record + the marker
            // the tx anchor's agreement check reads.
            noteAccepted(marker.seq)
            lastBurstMarker = marker
            recordTurnPair(nowMs)
            feedSample(latency, nowMs, boundary = true)
            reanchor(marker.audioMs, nowMs, exact = true)
            if (!bypassedDCheck) noteBurstRatio(marker, latency)
            // v1.0.19 — ROBUST HOLD + SLEW (see robustHoldMs).
            return robustHoldMs(nowMs, prevHoldMs) to marker
        } else if (isDTrusted(nowMs) &&
            kotlin.math.abs(latency - dBasedLagMs()) > SEED_FB_D_CHECK_MS
        ) {
            // v1.0.21: this 2500 ms D-check is the sim's burst
            // path for BOTH the seed-fb and the d-based marker
            // (the d-based pick itself is bounded by the 1500 ms
            // residual, the implied-consistency check is skipped
            // for it — like the sim's _accept_tc for tc).
            burstConsistency++
            burstDCheckStreak = 0
            return lastHoldMs to null
        }
        burstOk++
        burstDCheckStreak = 0
        lastBoundarySampleMs = latency
        lastBoundaryAtMs = nowMs
        // v1.0.21 (21a/21b) — monotone record + the marker the
        // tx anchor's agreement check reads.
        noteAccepted(marker.seq)
        lastBurstMarker = marker
        recordTurnPair(nowMs)
        feedSample(latency, nowMs, boundary = true)
        reanchor(marker.audioMs, nowMs, exact = true)
        noteBurstRatio(marker, latency)
        // v1.0.19 — ROBUST HOLD + SLEW (see robustHoldMs).
        return robustHoldMs(nowMs, prevHoldMs) to marker
    }

    /** v1.0.19 — marker whose wall time is nearest (nowMs −
     * lagMs), provided it is within [MARKER_RESIDUAL_MS] (2500
     * ms) of that target; null otherwise. The D-based marker:
     * the absolute reference for re-anchoring when the anchor
     * has drifted (no-marker bursts, sustained slice D-check
     * rejections). */
    private fun markerNearLagMs(nowMs: Long, lagMs: Long): TurnMarker? {
        val target = nowMs - lagMs
        var best: TurnMarker? = null
        var bestResidual: Long? = null
        for (marker in markers) {
            val residual = kotlin.math.abs(marker.wallMs - target)
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

    /** v1.0.19 — re-anchor at once to the D-based marker
     * (no-marker recovery). Returns true when a marker was
     * found and the anchor moved. */
    private fun reanchorToDMarker(nowMs: Long): Boolean {
        val marker = markerNearLagMs(nowMs, dBasedLagMs()) ?: return false
        reanchor(marker.audioMs, nowMs, exact = true)
        dReanchors++
        return true
    }

    /** v1.0.19 — ROBUST HOLD + SLEW. A single burst's latency is
     * noisy (a mid-turn burst's lat is inflated by speech
     * elapsed in the turn); one outlier zeroes the hold and the
     * dues cluster → drops. So the hold is recomputed from the
     * robust lag estimate instead of the single burst latency:
     * hold = max(0, D − lagEstimate − pipeline). The hold may
     * not drop more than [HOLD_SLEW_MS] (300 ms) below the
     * previous hold — a 1 s hold drop makes a whole second of
     * dub due at once and the queue drops it; a rise is
     * unlimited (it only delays dues). */
    private fun robustHoldMs(nowMs: Long, prevHoldMs: Long): Long {
        val robust = (delayMs - lagEstimateMs(nowMs) - pipelineMs())
            .coerceAtLeast(0L)
        return maxOf(prevHoldMs - HOLD_SLEW_MS, robust)
    }

    /** v1.0.19 — BURST-SUCCESSOR RATIO. The marker this burst
     * paired with and the previous accepted burst's marker are
     * consecutive lines (seq + 1): the speech between them is
     * the earlier line's counted, the dub between them is that
     * line's total dub. Same sanity bounds as the turnComplete
     * successor chain. GATED on the D-check: the measurement is
     * only trusted when BOTH bursts' latencies agree with the
     * D-based lag (|lat − (D − margin)| ≤ 1000 ms). Without the
     * gate the ratio feeds back into the very selection that
     * produced it and diverges (measured 0.50, latencies
     * overestimated ~5 s); the D-based lag is an independent
     * reference that breaks the loop. Feeds the existing
     * ratio() sums; the per-turn ratio from turnCompletes is
     * untouched. */
    private fun noteBurstRatio(marker: TurnMarker, latency: Long) {
        val validated =
            kotlin.math.abs(latency - dBasedLagMs()) <= BURST_D_VALIDATE_MS
        val prevSeq = lastRatioBurstSeq
        if (prevSeq != null && marker.seq == prevSeq + 1L &&
            lastBurstValidated && validated
        ) {
            val countedDiff = marker.audioMs - lastBurstAudioMs
            val dubDiff = cumDubMs - lastBurstDubMs
            val expected = countedDiff * 1.3
            // v1.0.20 — TIGHTENED bounds [0.7, 1.6] (was [0.5,
            // 2.0]) + counted >= 500 ms. The v1.0.19 device test
            // showed ratio display 2.00x = the clamp ceiling:
            // undercounted speech (feed gate 600) + fragmented
            // markers (small countedDiff, full-turn dubDiff)
            // saturated the ratio at the 0.5 clamp, and the
            // saturated ratio drifted the anchor (~76% of true
            // speed), feeding the consistency rejections. The
            // turnComplete successor chain (acceptTurnComplete)
            // keeps [0.5, 2.0] — only this BURST chain tightens
            // (it is the chain that fires on the device; tc_recv
            // is ~0 there).
            if (countedDiff >= 500L && dubDiff >= 0.7 * expected &&
                dubDiff <= 1.6 * expected
            ) {
                sumCountedMs += countedDiff
                sumTotalDubMs += dubDiff
                ratioBurstMeas++
            }
        }
        lastRatioBurstSeq = marker.seq
        lastBurstAudioMs = marker.audioMs
        lastBurstDubMs = cumDubMs
        lastBurstValidated = validated
    }

    private fun reanchor(anchorSpeechNew: Long, nowMs: Long, exact: Boolean) {
        anchorSpeechMs = anchorSpeechNew
        anchorDubMs = cumDubMs
        pairedDubMs = 0L
        lastAnchorWallMs = nowMs
        if (exact) everExactAnchor = true
    }

    // ---- v1.0.21 (21b) — transcription anchor ----
    /**
     * The service finalizes one VAD turn's tx source record at
     * its end ([startWallMs]/[endWallMs] are the turn's walls;
     * the counted speech comes from the completed marker, which
     * [onSpeechTurnEnd] captured). Fragments that arrived before
     * the turn ended were parked in [txPendingFrags] and are
     * attached now (which is also when the asrLag calibration
     * can run for them).
     */
    @Synchronized
    fun noteTxSource(startWallMs: Long, endWallMs: Long) {
        val turnIdx = lastCompletedTurnIdx ?: return
        // The completed marker's own start wall is the turn's
        // true start; the service-passed start is only a fallback
        // (it can already be the NEXT turn's start when the end
        // arrives late).
        val startMs = if (lastCompletedTurnWallMs > 0L) lastCompletedTurnWallMs else startWallMs
        val entry = TxSourceEntry(
            startWallMs = startMs,
            endWallMs = endWallMs,
            sentMs = lastCompletedTurnCountedMs,
            firstFragMs = null,
            lastFragMs = null,
            nFrag = 0
        )
        txSrc[turnIdx] = entry
        val pending = txPendingFrags.remove(turnIdx)
        if (pending != null) {
            for (arrival in pending) attachTxFrag(entry, turnIdx, arrival)
        }
    }

    /** asrLag calibration + fragment bookkeeping for one source
     * entry. The first fragment arriving AFTER the turn ended is
     * (approximately) the line's last fragment: sample =
     * arrival − turnEnd + 300 ms (the VAD end rule fires ~300 ms
     * after the last speech frame). Only for short turns (a
     * merged turn's end wall belongs to its LAST line, not to
     * this fragment's line). */
    private fun attachTxFrag(entry: TxSourceEntry, turnIdx: Long, arrival: Long) {
        if (entry.firstFragMs == null) entry.firstFragMs = arrival
        entry.lastFragMs = arrival
        entry.nFrag++
        if (arrival > entry.endWallMs &&
            entry.endWallMs - entry.startWallMs <= 5000L &&
            !txCalibratedTurns.contains(turnIdx)
        ) {
            val sample = arrival - entry.endWallMs + 300L
            if (sample in 0L..TX_ASR_CAL_MAX_MS) {
                txAsrEmaMs = 0.7 * txAsrEmaMs + 0.3 * sample.toDouble()
                txAsrSamples++
                txCalibratedTurns.add(turnIdx)
            }
        }
    }

    /** The VAD turn an input-transcription fragment belongs to:
     * the currently open turn while one is open, else the last
     * completed turn (a fragment can arrive just after its turn
     * ended). Null only before the first turn. */
    private fun currentTxTurnIdx(): Long? {
        val last = markers.lastOrNull()
        if (last != null && !last.done) return currentTurnIdx
        return lastCompletedTurnIdx ?: if (markers.isNotEmpty()) currentTurnIdx else null
    }

    /**
     * One input-transcription fragment arrived (Gemini Live
     * streams them DURING each line). Grouping: a new tx
     * sentence starts when a fragment arrives >=800 ms after
     * the previous fragment (any turn). Groups merge across
     * short-pause lines — the anchor's time-based selection
     * handles that. Also attaches the fragment to its turn's
     * source entry for the asrLag calibration (or parks it
     * until the turn finalizes).
     */
    @Synchronized
    fun noteTxInFragment(arrivalMs: Long) {
        // v1.0.22 (Rf1) — record every fragment arrival for the
        // pause estimate (pruned to the last 10 minutes).
        txFragArrivals.addLast(arrivalMs)
        while (txFragArrivals.isNotEmpty() &&
            arrivalMs - txFragArrivals.first() > 600_000L
        ) {
            txFragArrivals.removeFirst()
        }
        // v1.0.22 (T-a) — anchor groups close on a >=300 ms
        // fragment gap: dense dialogue never leaves an 800 ms
        // gap, so groups never closed and the anchor found no
        // CLOSED group (tx no-mk 98% on the dense device film).
        if (txLastFragAtMs != null && arrivalMs - txLastFragAtMs!! >= TX_SUB_GAP_MS) {
            closeTxGroup()
        }
        txLastFragAtMs = arrivalMs
        val cur = txCurGroup
        if (cur == null) {
            val g = TxGroup(firstMs = arrivalMs, lastMs = arrivalMs, closed = false)
            txCurGroup = g
            txGroups.add(g)
            txGroupsSeen++
            while (txGroups.size > 500) txGroups.removeAt(0)
        } else {
            cur.lastMs = arrivalMs
        }
        // v1.0.22 (BaSent/Sa2) — parallel SENTENCE grouping at
        // the 800 ms gap, for the seed path only: the anchor
        // groups above are sub-splits (one fragment each under
        // T-a), and seeding from them picks line 2 instead of
        // line 1. Pure bookkeeping; the tracker's own grouping
        // is untouched.
        if (sentLastFragAtMs != null && arrivalMs - sentLastFragAtMs!! >= TX_GAP_MS) {
            sentCurGroup?.let { it.closed = true }
            sentCurGroup = null
        }
        sentLastFragAtMs = arrivalMs
        if (sentCurGroup == null) {
            val g = TxGroup(firstMs = arrivalMs, lastMs = arrivalMs, closed = false)
            sentCurGroup = g
            sentGroups.add(g)
            while (sentGroups.size > 500) sentGroups.removeAt(0)
        } else {
            sentCurGroup!!.lastMs = arrivalMs
        }
        val turnIdx = currentTxTurnIdx()
        if (turnIdx == null) return
        val entry = txSrc[turnIdx]
        if (entry == null) {
            val list = txPendingFrags.getOrPut(turnIdx) { ArrayList() }
            list.add(arrivalMs)
        } else {
            attachTxFrag(entry, turnIdx, arrivalMs)
        }
    }

    private fun closeTxGroup() {
        txCurGroup?.let { it.closed = true }
        txCurGroup = null
    }

    /** One output-transcription fragment arrived for a burst
     * (counted per burst seq; a burst has 1–2). */
    @Synchronized
    fun noteTxOutFragment(burstSeq: Long, arrivalMs: Long) {
        txOutFrags[burstSeq] = (txOutFrags[burstSeq] ?: 0) + 1
    }

    /** Inverse of [wallAtSpeech]: counted-speech position of a
     * wall time, interpolated from the slices. Null when no
     * slice covers it.
     * v1.0.22 (T-d) — extrapolate 1:1 wall->speech past the
     * slice edges, so the tx re-anchor never needs the VAD
     * marker fallback (audioAt covered 20 s at MAX_SLICES=40
     * and the newest marker was 30 s old on the device, so the
     * fallback failed too). Only when no slice covers the wall
     * at all; with no slices at all, still null (the anchor
     * then uses the marker fallback as before). */
    private fun audioAt(wallMs: Long): Long? {
        for (slice in speechSlices) {
            if (wallMs in slice.startWallMs..slice.endWallMs) {
                val spanWall = maxOf(1L, slice.endWallMs - slice.startWallMs)
                val spanAudio = maxOf(1L, slice.endAudioMs - slice.startAudioMs)
                return slice.startAudioMs +
                    (wallMs - slice.startWallMs) * spanAudio / spanWall
            }
        }
        if (speechSlices.isEmpty()) return null
        val first = speechSlices.first()
        if (wallMs < first.startWallMs) {
            return maxOf(0L, first.startAudioMs - (first.startWallMs - wallMs))
        }
        val last = speechSlices.last()
        return last.endAudioMs + (wallMs - last.endWallMs)
    }

    /** Estimated start wall of a tx sentence: its first fragment
     * arrived at lineStart + fragDelay + asrLag. */
    private fun txSentenceStartMs(group: TxGroup): Long =
        (group.firstMs - txAsrEmaMs - TX_FRAG_DELAY_MS).toLong()

    /**
     * v1.0.21 (21b) — POST-HOC tx anchor for a burst, called
     * when the first output-transcription fragment of that burst
     * arrives (the burst is confirmed to be a real translation).
     * [nowMs] is the fragment arrival; the burst arrival wall is
     * the stored [lastBurstArrivalMs].
     *
     * MARKER-FREE: with VAD turns ~24 s long, the only VAD
     * markers are turn starts ~24 s apart — a mid-turn burst has
     * NO marker within 2.5 s, so requiring a VAD marker makes
     * the anchor fail exactly when it is needed most. The sample
     * is therefore computed against the LINE start estimated from
     * the transcription (firstFrag − asrLagEma − 500 ms), and the
     * re-anchor uses the interpolated speech position of that
     * start ([audioAt]); a VAD marker is only a fallback for the
     * re-anchor when no slice covers the start. The monotone
     * check is on the audio position, not the marker seq. A
     * merged tx group (several lines) can still give a +1-line
     * error — the D-check and the cluster reference absorb it
     * (the anchor must be right more often than wrong).
     *
     * NOT implemented (reverted in the sim, see the task): the
     * tx-hold (E2) and the staleness skip (E3).
     */
    @Synchronized
    fun applyTxAnchor(nowMs: Long, burstSeq: Long) {
        if (lastBurstSeqTx != burstSeq || lastBurstArrivalMs == null) return
        // One application per burst: a burst has 1–2 output-tx
        // fragments, and each one calls this — without the guard
        // a rescued burst counted 2 pairs / 2 tx_ok.
        if (txAnchoredBurst == burstSeq) return
        val burstArrival = lastBurstArrivalMs!!
        val dlag = selLagMs()
        val target = burstArrival - dlag
        // Select the tx sentence whose estimated start is nearest
        // the selection-lag target, inside the window. During
        // bootstrap, record EVERY in-window candidate's lat into
        // the pool: each burst contributes one correct candidate
        // (its own line) and a few wrong ones spread out, so the
        // densest cluster is the true lag even when the seed is a
        // whole line off.
        var best: TxGroup? = null
        var bestRes: Long? = null
        // v1.0.22 (Bb) — one seed candidate per burst: the loop
        // records EVERY in-window group's lat into the pool, and
        // the pool then votes for the wrong line (cluster race,
        // round 2). Keep only the candidate nearest the current
        // reference. (Counted by adds, not by index: the pool
        // prunes from the front at the 200 cap, so a stored
        // index would go stale.)
        val bbOn = !refEstablished()
        var bbAdded = 0
        val bbExpected = ArrayList<Long>()
        for (g in txGroups) {
            // CLOSED groups only: the next line's group is still
            // OPEN when this burst arrives (its first fragment
            // just arrived), while this burst's own line group
            // closed ~1.5 s ago. An open group is a future line,
            // never this burst's line.
            if (!g.closed) continue
            val startEst = txSentenceStartMs(g)
            if (startEst !in (burstArrival - TX_WIN_MS)..(burstArrival - 300L)) continue
            if (!refEstablished()) {
                seedCand.add(burstArrival - startEst)
                while (seedCand.size > MAX_SEED_CAND) seedCand.removeAt(0)
                bbExpected.add(burstArrival - startEst)
                bbAdded++
            }
            val res = kotlin.math.abs(startEst - target)
            if (bestRes == null || res < bestRes) {
                best = g
                bestRes = res
            }
        }
        if (bbOn && bbAdded > 0 && bbExpected.isNotEmpty()) {
            val chosen = bbExpected.minByOrNull {
                kotlin.math.abs(it - selLagMs())
            }!!
            val tail = seedCand.takeLast(bbAdded)
            val kept = ArrayList<Long>()
            var keptChosen = false
            for (v in tail) {
                if (v == chosen && !keptChosen) {
                    kept.add(v)
                    keptChosen = true
                }
            }
            if (!keptChosen && tail.isNotEmpty()) {
                kept.add(tail.minByOrNull {
                    kotlin.math.abs(it - chosen)
                }!!)
            }
            repeat(bbAdded) { seedCand.removeAt(seedCand.size - 1) }
            seedCand.addAll(kept)
        }
        if (best == null) {
            txNoMk++
            return
        }
        // v1.0.22 (S-b) — the tx lat of this burst's best group,
        // captured before the anchor runs (side-effect free);
        // stored only if the anchor accepts below.
        val sbLatTx = burstArrival - txSentenceStartMs(best)
        // v1.0.22 (Rb) — once established, a sample more than
        // 600 ms above the selection lag is a +1-line error even
        // inside the 1200 ms residual — block it before it feeds
        // D. The VAD-agree path (same line, no sample) is
        // exempt: R-b only stops a sample from feeding D, it must
        // not block an agreement.
        if (refEstablished() && bestRes != null && bestRes <= TX_RES_MS) {
            val agree = lastBurstMarker != null &&
                kotlin.math.abs(lastBurstMarker!!.wallMs - txSentenceStartMs(best)) <= 500L
            if (!agree && sbLatTx > dlag + RB_UP_MS) {
                txCons++
                return
            }
        }
        // Residual bound once the reference is established: a
        // mid-turn burst in a merged group is ~3.4 s from its
        // group's start — it cannot be attributed to that start,
        // so reject it instead of feeding a +1-line sample. OFF
        // during bootstrap (the target is wrong while the seed is
        // wrong).
        if (refEstablished() && bestRes != null && bestRes > TX_RES_MS) {
            txNoMk++
            return
        }
        val startEst = txSentenceStartMs(best)
        val latTx = burstArrival - startEst
        if (latTx !in 0L..20000L) {
            txCons++
            return
        }
        // Wide window while bootstrapping (both clusters get in,
        // the clustering sorts them out); tight once the cluster
        // reference is established.
        val txWin = if (refEstablished()) TX_DCHECK_MS else TX_BOOT_WIN_MS
        if (kotlin.math.abs(latTx - dlag) > txWin) {
            txCons++
            return
        }
        // The VAD path already paired this burst to the marker
        // whose wall is the same line start: agreement, no double
        // sample.
        val lastMarker = lastBurstMarker
        if (lastMarker != null &&
            kotlin.math.abs(lastMarker.wallMs - startEst) <= 500L
        ) {
            txAgree++
            txAnchoredBurst = burstSeq
            return
        }
        // Re-anchor position: interpolated speech position of the
        // line start; nearest VAD marker only as a fallback.
        var audioPos = audioAt(startEst)
        var mBest: TurnMarker? = null
        var mRes: Long? = null
        for (m in markers) {
            val res = kotlin.math.abs(m.wallMs - startEst)
            if (mRes == null || res < mRes) {
                mBest = m
                mRes = res
            }
        }
        if (audioPos == null) {
            if (mBest == null || (mRes ?: Long.MAX_VALUE) > 2500L) {
                txNoMk++
                return
            }
            audioPos = mBest.audioMs
        }
        // Monotone in speech position (not marker seq): the
        // anchor must not jump backwards.
        val lastAudio = txLastAudioPos
        if (lastAudio != null && audioPos < lastAudio) {
            txCons++
            return
        }
        lastBoundarySampleMs = latTx
        lastBoundaryAtMs = nowMs
        feedSample(latTx, nowMs, boundary = true)
        reanchor(audioPos, nowMs, exact = true)
        if (!lastBurstPaired) {
            recordTurnPair(nowMs)
        }
        txOk++
        txAnchoredBurst = burstSeq
        txLastAudioPos = audioPos
        // v1.0.22 (S-b) — the tx-ok lat of this burst, for the
        // slice referee (stored only on accept, like the sim);
        // patch an already-finalised record of this burst.
        sbLatOk[burstSeq] = sbLatTx
        for (rec in sbBursts) {
            if (rec.seq == burstSeq && rec.latTx == null) rec.latTx = sbLatTx
        }
        // v1.0.22 (Rf1) — ratio from consecutive tx-ok line
        // positions, with the counted speech corrected for music
        // fed during inter-line pauses (see [pauseEstimate]).
        // Same sanity bounds as the burst chain. This REPLACES
        // the uncorrected round-1 RATIO_TX (off in the winner:
        // the uncorrected estimator read 0.83 vs the true
        // 0.704). The prev-tracking updates on every tx-ok,
        // measured or not.
        val rfPrevPos = rfPrevAudioPos
        val rfPrevGroupFirst = rfPrevGroupFirstMs
        if (rfPrevPos != null && rfPrevGroupFirst != null) {
            val countedDiff = audioPos - rfPrevPos
            val dubDiff = cumDubMs - rfPrevCumDubMs
            val pause = pauseEstimate(rfPrevGroupFirst, best.lastMs)
            val countedUse = countedDiff - RATIO_PAUSE_K * pause
            val expected = countedUse * 1.3
            if (countedUse >= 500.0 && dubDiff >= 0.7 * expected &&
                dubDiff <= 1.6 * expected
            ) {
                sumCountedMs += countedUse.toLong()
                sumTotalDubMs += dubDiff
                rfMeas++
            }
        }
        rfPrevAudioPos = audioPos
        rfPrevCumDubMs = cumDubMs
        rfPrevGroupFirstMs = best.firstMs
        if (mBest != null && (mRes ?: Long.MAX_VALUE) <= 2500L) {
            txLastMarkerSeq = mBest.seq
            lastBurstMarker = mBest
            noteAccepted(mBest.seq)
        }
        burstDCheckStreak = 0
    }

    // ---- v1.0.22 — Sa2 seed v2 + S-b slice referee ----

    /**
     * v1.0.22 (Sa2) — re-seed from the first paired slice
     * sample. The slice sample is a DIRECT lag measurement
     * (burstArrival − sourceWall of the paired slice), so
     * target = firstBurstArrival − sliceLag is the true start
     * of the line this burst translates. Pick the closed
     * SENTENCE group whose estimated start is nearest the
     * target, but only a group whose start is at least
     * [S2_MARKER_MARGIN_MS] past the first marker wall: the
     * untranslated line 0 sits just before the marker wall,
     * and the sim's 80 ms gap is thinner than real marker /
     * startEst noise. Fallback (no candidate): the BaSent
     * most-recent-closed rule. No candidate at all: keep the
     * loop seed. Runs BEFORE the D-check judges this sample
     * (see [feedSample]) and applies the same four seed
     * updates the loop seed does (seed lag, EMA seed, D, hold).
     * The sim's S-a literal (no marker bound) FAILS 3/4 worlds
     * (seed +2589 ms — the first slice measures
     * burstArrival − wall(~500 ms) of the line being spoken,
     * not the absolute lag); Sa2 passes 4/4 ex-cold.
     */
    private fun saCorrect(sliceLagMs: Long, nowMs: Long) {
        val arrival = saFirstArrivalMs ?: return
        val target = arrival - sliceLagMs
        val markerWall = markers.firstOrNull()?.wallMs
        val loopSeed = seedLagMs
        var chosen: TxGroup? = null
        var bestDist: Long? = null
        var bestSe: Long? = null
        for (g in sentGroups) {
            if (!g.closed) continue
            val se = txSentenceStartMs(g)
            if (se !in (arrival - TX_WIN_MS)..(arrival - S2_MIN_GAP_MS)) continue
            if (markerWall != null && se < markerWall + S2_MARKER_MARGIN_MS) continue
            val dist = kotlin.math.abs(se - target)
            if (bestDist == null || dist < bestDist ||
                (dist == bestDist && se < (bestSe ?: Long.MAX_VALUE))
            ) {
                chosen = g
                bestDist = dist
                bestSe = se
            }
        }
        if (chosen == null) {
            // Fallback: BaSent most-recent-closed (no bound).
            var fbSe: Long? = null
            for (g in sentGroups) {
                if (!g.closed) continue
                val se = txSentenceStartMs(g)
                if (se !in (arrival - TX_WIN_MS)..(arrival - S2_MIN_GAP_MS)) continue
                if (fbSe == null || se > fbSe) {
                    chosen = g
                    fbSe = se
                }
            }
        }
        val group = chosen ?: return
        val se = txSentenceStartMs(group)
        val el = arrival - se
        seedLagMs = el
        latencyEma.seed(el)
        // v1.0.23 — TRỄ CỐ ĐỊNH: Sa2 chỉ còn sửa seed lag (reference
        // cho marker selection); D KHÔNG đổi theo nó nữa. Hold tính
        // từ D cố định của phiên.
        val newDelay = delayMs
        lastHoldMs = (newDelay - el - pipelineMs()).coerceAtLeast(0L)
        Log.i(TAG, "Sa2 seed corrected: sliceLag=%d target=%d startEst=%d seed=%d (loop seed was %s)"
            .format(sliceLagMs, target, se, el, loopSeed?.toString() ?: "none"))
    }

    /**
     * v1.0.22 (S-b) — a new burst started: finalise the
     * previous burst's slice record (its samples are complete;
     * its tx anchor, if any, landed during it) and remember
     * this seq. Called from [onDubAudioArrival] after the new
     * burst's anchor work, like the sim's on_dub_burst
     * (finalise after super()).
     */
    private fun sbOnBurstStart(nowMs: Long) {
        val prev = sbLastSeq
        if (prev != null && prev != burstSeq) {
            sbFinalise(prev, nowMs)
        }
        sbLastSeq = burstSeq
    }

    /** v1.0.22 (S-b) — finalise one burst's slice record. */
    private fun sbFinalise(seq: Long, nowMs: Long) {
        val samples = sbSamples.remove(seq) ?: ArrayList()
        sbFirst.remove(seq)
        sbLast.remove(seq)
        val med = if (samples.isNotEmpty()) {
            samples.sorted()[samples.size / 2]
        } else {
            null
        }
        val rec = SbBurstRec(seq = seq, med = med, latTx = sbLatOk[seq])
        sbBursts.add(rec)
        if (med != null) {
            sbHist.addLast(med)
            while (sbHist.size > 60) sbHist.removeFirst()
        }
        sbStep(nowMs, rec)
    }

    /**
     * v1.0.22 (S-b) — trigger / trial accounting, one step per
     * finalised burst. A trial only STARTS once the reference
     * is established; the trigger is the median of the last 3
     * per-burst slice medians deviating from the reference by
     * more than [SB_TRIG_MS]. During a trial the reference is
     * the trial value end-to-end (see [refLagMs]); adoption
     * needs 3 consecutive agreeing bursts AND the candidate
     * beating the base on the trial medians. The sim proved
     * the trigger signal is a transient crossing of a drifting
     * stale-anchor sample and the adopt rule is
     * self-reinforcing — S-b is a safety net that fires late,
     * not a referee to rely on for fixing the seed (it fired
     * 0 times in the winning sim run: the win is Sa2's).
     */
    private fun sbStep(nowMs: Long, rec: SbBurstRec) {
        if (sbTrialActive) {
            sbTrialBursts.add(rec)
            // Locals first: rec.latTx / rec.med are mutable data
            // class properties, so the compiler cannot smart-cast
            // them after a null check (compileReleaseKotlin error
            // on the first v1.0.22 build attempt).
            val latTx = rec.latTx
            val med = rec.med
            val agree = latTx != null && med != null &&
                kotlin.math.abs(latTx - med) <= SB_AGREE_MS
            if (agree) {
                sbTrialRun++
                if (sbTrialRun > sbTrialMaxRun) sbTrialMaxRun = sbTrialRun
            } else {
                sbTrialRun = 0
            }
            sbTrialLeft--
            if (sbTrialLeft <= 0) sbEndTrial(nowMs)
            return
        }
        if (sbCooldown > 0) {
            sbCooldown--
            return
        }
        if (sbHist.size < SB_N) return
        val lastN = sbHist.takeLast(SB_N)
        val medN = lastN.sorted()[lastN.size / 2]
        val ref = refLagMs()
        val dev = medN - ref
        if (kotlin.math.abs(dev) > SB_TRIG_MS && refEstablished() &&
            sbTrials < SB_MAX_TRIALS
        ) {
            sbStartTrial(nowMs, medN, ref)
        }
    }

    private fun sbStartTrial(nowMs: Long, medN: Long, ref: Long) {
        val baseMeds = sbHist.takeLast(SB_N)
        sbTrialBaseMed = baseMeds.map {
            kotlin.math.abs(it - ref)
        }.sorted().let { it[it.size / 2] }
        sbTrialRef = medN
        sbTrialBaseRef = ref
        sbTrialActive = true
        sbTrialLeft = SB_TRIAL_BURSTS
        sbTrialRun = 0
        sbTrialMaxRun = 0
        sbTrialBursts.clear()
        sbTrials++
        Log.i(TAG, "S-b trial start: ref=%d -> trial=%d".format(ref, medN))
    }

    private fun sbEndTrial(nowMs: Long) {
        val trialRef = sbTrialRef
        val bursts = sbTrialBursts.toList()
        val meds = bursts.mapNotNull { it.med }
        val medCand = if (meds.isNotEmpty() && trialRef != null) {
            meds.map {
                kotlin.math.abs(it - trialRef)
            }.sorted().let { it[it.size / 2] }
        } else {
            null
        }
        val medBase = if (meds.isNotEmpty() && sbTrialBaseRef != null) {
            meds.map {
                kotlin.math.abs(it - sbTrialBaseRef!!)
            }.sorted().let { it[it.size / 2] }
        } else {
            null
        }
        val adopt = sbTrialMaxRun >= SB_AGREE_RUN && meds.size >= 3 &&
            medCand != null && medBase != null && medCand < medBase
        if (adopt) {
            sbAdoptedRef = trialRef
            sbAdopts++
            Log.i(TAG, "S-b trial ADOPT: ref=%s (run=%d, medCand=%s, medBase=%s)"
                .format(trialRef?.toString() ?: "?", sbTrialMaxRun,
                    medCand?.toString() ?: "?", medBase?.toString() ?: "?"))
        } else {
            Log.i(TAG, "S-b trial reject: ref=%s (run=%d, medCand=%s, medBase=%s)"
                .format(trialRef?.toString() ?: "?", sbTrialMaxRun,
                    medCand?.toString() ?: "?", medBase?.toString() ?: "?"))
        }
        sbTrialActive = false
        sbTrialRef = null
        sbTrialLeft = 0
        sbCooldown = SB_COOLDOWN_BURSTS
    }

    /**
     * v1.0.22 (Rf1) — Σ max(0, gap − 900 ms) over consecutive
     * tx fragment arrivals in (firstFrag, lastFrag]. Within a
     * line fragments step 500–900 ms, so only inter-line gaps
     * (line tail + pause + next line's frag delay) contribute.
     * The counted speech fed during those gaps is music, not
     * dialogue.
     */
    private fun pauseEstimate(firstFragMs: Long, lastFragMs: Long): Long {
        var p = 0L
        var prev: Long? = null
        for (a in txFragArrivals) {
            if (a <= firstFragMs) {
                prev = a
                continue
            }
            if (a > lastFragMs) break
            if (prev != null) {
                p += maxOf(0L, a - prev - RATIO_PAUSE_GAP_MS)
            }
            prev = a
        }
        return p
    }

    private fun recordTurnPair(nowMs: Long) {
        turnPairTimes60s.addLast(nowMs)
        while (turnPairTimes60s.isNotEmpty() &&
            nowMs - turnPairTimes60s.first() > SAMPLES_WINDOW_MS
        ) {
            turnPairTimes60s.removeFirst()
        }
        // v1.0.21 (21d) — all-time list for the pairs-in-first-
        // 60-s count.
        turnPairTimesAll.add(nowMs)
    }

    /**
     * Feed one latency sample (slice or boundary) into the 4 s
     * window and the recent-samples buffer. Returns the hold for
     * this sample: max(0, D − sample).
     *
     * v1.0.19 — SLICE D-CHECK ([boundary] = false only): a wrong
     * anchor inflates the slice sample; feeding it corrupts the
     * window median → D → the D-check itself (anchor and D lock
     * in wrong together). The D-based lag is the absolute
     * reference: a slice sample >1500 ms from it cannot be a
     * genuine lag, so it is REJECTED — never added to
     * recentSamples or windowSamples (never feeds the EMA) — but
     * lastSampleMs/lastSampleAtMs still update so the HUD shows
     * it, and the hold (D − sample) is still returned. 6
     * consecutive rejections = the anchor is wrong (not a noisy
     * sample): re-anchor to the D-based marker. Boundary samples
     * bypass the check entirely (they have their own checks in
     * acceptTurnComplete/onDubBurst).
     */
    private fun feedSample(sampleMs: Long, nowMs: Long, boundary: Boolean): Long {
        if (!boundary) {
            // v1.0.22 (S-b bucketing + Sa2 seed) — BEFORE the
            // D-check below: the sample is the raw pair() output,
            // accepted or D-check-rejected alike — the referee
            // sees the slice measurement as pairDubSlices
            // produced it, and the corrected seed (not the loop
            // seed) is what the D-check judges this sample
            // against. The bucket is keyed by the tracker's
            // current burst seq (the burst this sample arrived
            // in); it is finalised when the next burst starts.
            val bucket = sbSamples.getOrPut(burstSeq) { ArrayList() }
            bucket.add(sampleMs)
            if (sbFirst[burstSeq] == null) sbFirst[burstSeq] = sampleMs
            sbLast[burstSeq] = sampleMs
            if (saPending) {
                saPending = false
                saCorrect(sampleMs, nowMs)
            }
            if (kotlin.math.abs(sampleMs - dBasedLagMs()) > SLICE_D_CHECK_MS) {
                lastSampleMs = sampleMs
                lastSampleAtMs = nowMs
                sliceDReject++
                sliceDStreak++
                if (sliceDStreak >= SLICE_D_REANCHOR_STREAK) {
                    reanchorToDMarker(nowMs)
                    sliceDStreak = 0
                }
                return (delayMs - sampleMs).coerceAtLeast(0L)
            }
            sliceDStreak = 0
        }
        // v1.0.21 (21h) — every accepted boundary sample feeds
        // the independent lag reference (most recent 30, tx +
        // VAD burst + tc alike).
        if (boundary) {
            bndSamples.add(sampleMs)
            while (bndSamples.size > MAX_BND_SAMPLES) bndSamples.removeAt(0)
        }
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
            feedSample(sample.toLong(), nowMs, boundary = false)
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
            // v1.0.23 — TRỄ CỐ ĐỊNH: mẫu vẫn feed EMA (giữ
            // latencyEma + lag estimate cho hold/chẩn đoán), nhưng
            // kết quả KHÔNG gán vào delayMs nữa — D cố định cả
            // phiên, không EMA theo mẫu (đây là đường đã kéo D
            // kẹt dưới lag thật ở v1.0.22).
            latencyEma.sample(median, delayMs)
        }
        windowSamples.clear()
        windowStartMs = nowMs
        // v1.0.19 — steer the time-stretch speed (runs when the
        // window actually flushes, every ~4 s, not on the
        // early-return paths above).
        stretchUpdate(nowMs)
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
            " · bỏ %d lát/%d mẫu (nhỏ %d hoãn/lớn %d)%s".format(
                droppedSlices, droppedSamples, deferredTooSmall, droppedTooLarge,
                // v1.0.22 (V-c) — total ms the tail valve
                // discarded (each fired drop is also a "bỏ lát").
                // v1.0.23 — STRONG valve: xả gộp cả tail valve +
                // skip-ahead đầu burst; số lần skip-ahead hiện
                // riêng để biết van nổ vì lý do nào.
                run {
                    val totalDropped = tailDroppedMs + skipHeadDroppedMs
                    if (totalDropped > 0L) {
                        " · xả %d ms".format(totalDropped) +
                            if (skipHeadFires > 0) " (skip-đầu %d lần)".format(skipHeadFires) else ""
                    } else ""
                }
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
            // v1.0.20 — belt-and-braces garbage guard (the setters
            // already null out |.| > 60 s; this covers any value
            // set through another path).
            if (kotlin.math.abs(it) > SYNC_GARBAGE_MS) " · lệch hình —"
            else " · lệch hình %+d ms".format(it)
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
            // v1.0.22 — belt-and-braces guard: the frame guard is
            // FRAME_GARBAGE_MS (10 s); the computation already
            // drops jumped videos (see tryComputeFrameResidual).
            if (kotlin.math.abs(it) > FRAME_GARBAGE_MS) " · khung hình —"
            else " · khung hình %+d ms".format(it)
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
        // v1.0.19 — marker diagnostics, appended after the
        // rejection counters: marker count + newest marker age,
        // split attempts vs splits that fired, stall EPISODES, and
        // the current time-stretch speed. (v1.0.20: stall shows
        // EPISODES now, not reset invocations — one long silence
        // is one episode, not 159 resets.)
        val newestAgePart = markers.lastOrNull()?.let {
            "%d ms trước".format((nowMs - it.wallMs).coerceAtLeast(0L))
        } ?: "—"
        // v1.0.23 — tốc độ THẬT vs target: HUD in "tốc độ <target>×"
        // (thật <đo>×)" và gắn cờ CHÊNH! khi lệch >0,05, hoặc cảnh
        // báo khi stretch không được hỗ trợ (kẹt 1,0× vĩnh viễn).
        // Đây là thiết bị đo đóng số hạng ~1800 ms chưa giải thích
        // được trên ảnh 12:00 (HUD cũ in 1,50× = target, không ai
        // biết track thật chạy bao nhiêu).
        val speedPart = run {
            val parts = StringBuilder("tốc độ %.2f×".format(targetSpeed))
            val measured = lastMeasuredSpeed
            if (measured != null) {
                parts.append(" (thật %.2f×".format(measured))
                if (kotlin.math.abs(measured - targetSpeed) > 0.05) parts.append(" CHÊNH!")
                parts.append(")")
            }
            if (lastStretchSupported == false) {
                parts.append(" [stretch KHÔNG hỗ trợ — kẹt 1,0×]")
            } else {
                val readback = lastAppliedReadback
                if (readback != null &&
                    kotlin.math.abs(readback - targetSpeed) > 0.02
                ) {
                    parts.append(" [readback %.2f×]".format(readback))
                }
            }
            parts.toString()
        }
        val markerPart = " · mốc %d (mới nhất %s) · tách thử cũ %d/mới %d/được %d · stall %d · %s".format(
            markers.size, newestAgePart, splitAttemptsOld, splitAttempts, splitMarkers,
            stallEpisodes, speedPart
        )
        // v1.0.21 (21d) — the end-of-session photo hides
        // mid-session drift, so the max/p90 over the whole
        // session are reported: burst-start sync error, slice-play
        // error, per-burst END lateness, the D-pinned fraction,
        // pairs in the first 60 s, the first measurable sync time,
        // and the tx-anchor counters + the D-based pick count.
        // (Slice-play here is all-session, not the sim's "steady"
        // window — the tracker has no steady-window concept.)
        dHistory.addLast(nowMs to delayMs)
        while (dHistory.size > 7200) dHistory.removeFirst()
        val diag21Part = run {
            val syncMax = maxAbsErr(syncErrs)
            val syncP90 = p90AbsErr(syncErrs)
            val sliceMax = maxAbsErr(slicePlayErrs)
            val sliceP90 = p90AbsErr(slicePlayErrs)
            val endMax = maxAbsErr(endLatenessErrs)
            val endP90 = p90AbsErr(endLatenessErrs)
            val pinnedPct = if (dHistory.isEmpty()) 0 else
                dHistory.count { it.second >= D_PINNED_MS } * 100 / dHistory.size
            val firstBase = if (sessionStartMs > 0L) sessionStartMs
                else turnPairTimesAll.firstOrNull() ?: nowMs
            val pairsFirst60 = turnPairTimesAll.count { it - firstBase <= 60_000L }
            val firstSyncPart = firstSyncAtMs?.let { fs ->
                val base = if (sessionStartMs > 0L) sessionStartMs else fs
                "%d s".format(((fs - base).coerceAtLeast(0L)) / 1000L)
            } ?: "—"
            " · lệch max %s/p90 %s (n=%d) · lát-phát max %s/p90 %s (n=%d) · cuối-burst max %s/p90 %s (n=%d) · D ghim %d%% · cặp 60 s đầu %d · đồng bộ đầu %s · tx ok %d/no-mk %d/cons %d/agree %d asr %d ms (n=%d) · chọn-D %d".format(
                syncMax?.toString() ?: "—", syncP90?.toString() ?: "—", syncErrs.size,
                sliceMax?.toString() ?: "—", sliceP90?.toString() ?: "—", slicePlayErrs.size,
                endMax?.toString() ?: "—", endP90?.toString() ?: "—", endLatenessErrs.size,
                pinnedPct, pairsFirst60, firstSyncPart,
                txOk, txNoMk, txCons, txAgree, txAsrEmaMs.toInt(), txAsrSamples, dBasedSel
            )
        }
        // v1.0.22 (S-b) — slice-referee trials: how many fired
        // and how many adopted a new reference. In the winning
        // sim run this was 0/0 (the win is Sa2's); a non-zero
        // number on the device means the referee fired and its
        // verdict is worth checking against the sync numbers.
        val sbPart = if (sbTrials > 0) {
            " · sb %d thử/%d nhận".format(sbTrials, sbAdopts)
        } else {
            ""
        }
        return "D %s s (cố định) · %s · %d mẫu/60 s · cặp mốc %d/60 s · chờ %d lát · hold %.1f s · %s%s%s%s%s%s%s%s%s%s".format(
            delaySec, samplePart, sampleTimes60s.size, turnPairTimes60s.size,
            speechSlices.size, lastHoldMs / 1000.0, vadPart, droppedPart,
            syncPart, pipePart, ratioPart, framePart, rejectPart, markerPart, diag21Part, sbPart
        )
    }

    /** v1.0.21 (21d) — max / p90 of the absolute values. */
    private fun maxAbsErr(errs: List<Long>): Long? =
        errs.maxOfOrNull { kotlin.math.abs(it) }

    private fun p90AbsErr(errs: List<Long>): Long? {
        if (errs.isEmpty()) return null
        val sorted = errs.map { kotlin.math.abs(it) }.sorted()
        val idx = (sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)
        return sorted[idx]
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
        // v1.0.19 — burst-ratio chain + stretch state restart;
        // the ratio sums, EMA and D are kept (as before).
        lastRatioBurstSeq = null
        lastBurstValidated = false
        targetSpeed = 1.0
        levelRaiseAtMs = null
        sliceDStreak = 0
        // v1.0.20 — burst D-check streak + stall episode flag
        // restart (a new connection means new speech is coming).
        burstDCheckStreak = 0
        stalled = false
        // v1.0.22 — the tail valve's pending drop and end-lag
        // belong to the pre-reconnect burst (see resetForStall).
        // Sa2 / S-b state survives (once per run).
        pendingTailDropMs = 0L
        lastCorrectedEndLagMs = 0L
    }

    /** Manual nudge from the "Trễ −1s / +1s" buttons: shift D by the
     * delta and push it out at once. v1.0.23 — TRỄ CỐ ĐỊNH: nudge
     * tay chỉ đi trong 35–45 s quanh 40 s chuẩn; KHÔNG còn mẫu tự
     * động nào kéo D về sau đó. */
    @Synchronized
    fun adjustManual(deltaMs: Long) {
        delayMs = (delayMs + deltaMs).coerceIn(
            Prefs.FIXED_DELAY_MIN_SEC * 1000L, Prefs.FIXED_DELAY_MAX_SEC * 1000L
        )
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
        // v1.0.19 — burst-ratio chain, slice D-check, D-based
        // re-anchors, marker diagnostics and stretch state.
        lastRatioBurstSeq = null
        lastBurstAudioMs = 0L
        lastBurstDubMs = 0L
        lastBurstValidated = false
        ratioBurstMeas = 0
        sliceDReject = 0
        sliceDStreak = 0
        dReanchors = 0
        splitAttempts = 0
        stallResets = 0
        // v1.0.20 — stall episodes + burst D-check streak.
        stalled = false
        stallEpisodes = 0
        burstDCheckStreak = 0
        // v1.0.21 — D-based selection, split counting, tx anchor,
        // lag reference, burst memory and sync-error history.
        lastAcceptedSeq = null
        dBasedSel = 0
        splitAttemptsOld = 0
        txSrc.clear()
        txPendingFrags.clear()
        txGroups.clear()
        txCurGroup = null
        txLastFragAtMs = null
        txAsrEmaMs = 600.0
        txAsrSamples = 0
        txCalibratedTurns.clear()
        txOutFrags.clear()
        txOk = 0
        txNoMk = 0
        txCons = 0
        txAgree = 0
        txGroupsSeen = 0
        txLastMarkerSeq = null
        txLastAudioPos = null
        txAnchoredBurst = null
        currentTurnIdx = 0L
        lastCompletedTurnIdx = null
        lastCompletedTurnCountedMs = 0L
        lastCompletedTurnWallMs = 0L
        bndSamples.clear()
        seedCand.clear()
        lastBurstArrivalMs = null
        lastBurstSeqTx = null
        lastBurstPaired = false
        lastBurstMarker = null
        syncErrs.clear()
        slicePlayErrs.clear()
        endLatenessErrs.clear()
        dHistory.clear()
        turnPairTimesAll.clear()
        sessionStartMs = 0L
        firstSyncAtMs = null
        targetSpeed = 1.0
        levelRaiseAtMs = null
        lastStretchUpdateMs = 0L
        // v1.0.22 — Sa2 seed, sentence groups, Rf1
        // prev-tracking, tail valve, S-b referee.
        saFirstArrivalMs = null
        saPending = false
        sentGroups.clear()
        sentCurGroup = null
        sentLastFragAtMs = null
        txFragArrivals.clear()
        rfPrevAudioPos = null
        rfPrevCumDubMs = 0L
        rfPrevGroupFirstMs = null
        rfMeas = 0
        lastCorrectedEndLagMs = 0L
        pendingTailDropMs = 0L
        tailDroppedMs = 0L
        tailValveFires = 0
        vbPlaySeq = null
        vbValveUsedSeq = null
        sbSamples.clear()
        sbFirst.clear()
        sbLast.clear()
        sbLatOk.clear()
        sbBursts.clear()
        sbHist.clear()
        sbLastSeq = null
        sbTrialActive = false
        sbTrialRef = null
        sbTrialBaseRef = null
        sbTrialBaseMed = null
        sbTrialLeft = 0
        sbTrialRun = 0
        sbTrialMaxRun = 0
        sbTrialBursts.clear()
        sbTrials = 0
        sbAdopts = 0
        sbAdoptedRef = null
        sbCooldown = 0
        latencyEma.reset()
        latencyEma.setMarginMs(2000L)
    }
}
