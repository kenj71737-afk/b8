#!/usr/bin/env python3
"""sim1022.py — DubLive v1.0.22: ratio-floor stretch + tx independence
+ D-chase vs skip-ahead + frame-garbage seek test.

DEVICE EVIDENCE (v1.0.21, 28/11):
  09:10 (film A, VAD speaking, pin 68%): D 5.3 s, last sample 7.8 s,
  8 samples/60 s, pairs 0/60 s, hold 1.2 s, dropped 0 slices/10
  samples (small 0 / large 10), lech +13 ms, phat ~101 ms, ratio
  1.42x, khung hinh -52199 ms, burst 33: ok 11/no-mk 18/cons 4,
  splits 1, markers 12 (newest 17.4 s old), split attempts 19/1/1,
  stall 0, stretch 1.25x, lech max 1714/p90 1267 (n=11), slice-play
  max 2448/p90 2098 (n=21), END lateness max 2448/p90 2448 (n=9),
  D pinned 0%, pairs first 60 s 4, first sync 13 s, tx ok 6/no-mk
  199/cons 0/agree 3, asr 536 ms (n=3), sel-D 8, VAD turns 1/60 s,
  fed 53820 ms/60 s, gate rejects 136/60 s, dropped dub chunks 0.
  User 09:13: "Khong ro nhung do hon may ban truoc".
  09:21 (film B, DIFFERENT film, same dub session across the film
  change, counters cumulative since 09:10, pin 66%): D 5.1 s, LAST
  SAMPLE 12.0 s (0 s old), 25 samples/60 s, pairs 0/60 s, hold
  0.9 s, dropped 0 slices/171 samples (small 0 / LARGE 171), lech
  +516 ms, phat ~105 ms, ratio 1.42x, khung hinh +1006 ms, burst 91:
  ok 17/no-mk 66/cons 8/fb 2, splits 1, markers 12 (newest 30.3 s
  old), split attempts 34/1/1, stall 3, stretch 1.25x, lech max
  1750/p90 1714 (n=17), slice-play max 2448/p90 2032 (n=36), END
  lateness max 2448/p90 2098 (n=15), D pinned 0%, pairs first 60 s
  4, first sync 13 s, tx ok 9/no-mk 437/cons 0/agree 4, asr 463 ms
  (n=5), sel-D 11, VAD turns 4/60 s, fed 57940 ms/60 s (96%),
  gate rejects 57/60 s, dropped dub chunks 0.

VERIFIED IN KOTLIN (DubDelayTracker.kt, read 28/11 — not guessed):
  * feedSample (non-boundary): |sample - dBasedLag| > 1500 ms
    (SLICE_D_CHECK_MS) -> sample REJECTED from recent/window
    samples (never feeds the EMA) BUT lastSampleMs STILL updates
    (HUD shows it). 6 consecutive rejects (SLICE_D_REANCHOR_STREAK)
    -> reanchorToDMarker (re-anchor to the D-based marker — circular
    when D itself is the wrong one).
  * pair(): sample > MAX_SAMPLE_MS (20000 ms) -> droppedTooLarge
    ("lon"): 10 at 09:10 -> 171 at 09:21 (+161 samples >20 s in
    11 min). Growth math: dub 1.42x source, stretch cap 1.25x ->
    backlog grows ~(1.42-1.25)/1.25 s per s of dense speech, i.e.
    ~+5.8 s of lag per minute of dense dialogue.
  * applyTxAnchor: the agree gate is DEDUP only (VAD already paired
    this burst to the same line -> txAgree, return, no double
    sample) — it does NOT block tx. Real no-mk gates (txCons = 0
    in BOTH photos, so the |latTx-dlag| gate and the monotone gate
    NEVER fired): (a) no CLOSED group in window (group closes only
    on a >=800 ms fragment gap, TX_GAP_MS; dense film keeps the
    group open); (b) residual bound 1200 ms (TX_RES_MS) once the
    reference is established (merged group startEst = first line,
    burst = later line -> seconds off); (c) re-anchor fallback:
    audioAt(startEst) null (20 s coverage, MAX_SLICES=40) AND
    nearest marker >2500 ms (newest marker 30.3 s old) -> no-mk.
    (c) is the ONLY place tx depends on a VAD marker — fallback
    only, not the main gate.
  * Frame residual (tryComputeFrameResidual, v1.0.22): continuity
    check videoDelta vs wallDelta (tolerances -3 s / +5 s); a
    seek/reload/episode change fails it by tens of seconds and the
    residual is dropped (shows "—"); magnitude guard
    FRAME_GARBAGE_MS = 10 s. The 09:10 -52 s did NOT reproduce at
    09:21 (+1006 ms, same sign as lech +516 ms) — supports the
    episode-change garbage hypothesis.
  * onBurstPlayed fires at the burst FIRST frame (VoicePlayer
    confirmPlayStart): the Kotlin slice/end diagnostics see only
    the pairing entries logged SO FAR (partial burst), and each
    slice sample assumes 1.0x playout (playSliceWall = playWall +
    (dubCum - startCum)) while the voice really drains at the
    stretch speed.

WORLD 22 (recalibrated, SEED=20261128, 10-min sessions):
  * Film A (09:10): tonal 0.95, music 0.95, short pauses 80% ->
    VAD turns median ~53 s, turns60=1, fed60 ~60 s. Lines 1.5-3 s,
    soft syllables 80%, music fills pauses, tx fragments per line
    as build_tx21.
  * Film B dense (09:21): tonal 0.80, music 0.90, short pauses 80%
    -> VAD turns median ~14 s, turns60~3-4, fed60 ~57.9 s (96%).
  * Lag0 4600 ms (07:35 fresh) and 7800 ms (09:10 last sample);
    ratio 1.42 (device both photos) and 1.51 (sim1021 world).

VARIANTS (in order; stop at the first stretch variant that passes
the gate — no blind stacking):
  V-a ratio floor: target speed = max(ratio floor, existing raises),
    floor = clamp(1/ratio(), 1.0, 1.5), decay to 1.0 when silent
    >5 s. Existing raises kept as max-wins (burst-start err
    >2500 -> 1.5, loop lateness >200 ms -> 1.5, level raise).
  V-b V-a + catch-up: floor = clamp(ratio * (1 + endLag/5000),
    1.0, 1.5), endLag = CORRECTED end lateness (render-true,
    offset/speed) of the most recent burst, as seen at its play
    time (partial pairing log, like Kotlin).
  V-c V-b + tail valve: when corrected endLag > 1500 ms while
    speed is already at the 1.5 cap, drop (endLag - 1000) ms of
    audio at the next burst boundary (head of the new burst in the
    sim — the sim cannot retract already-queued tail audio; the
    sync/dropped-% numbers transfer, the cut position differs).
    Only tried if V-a/V-b still fail the end-burst gate.
  CHASE (D-chase, the 12 s answer): a run of >=6 consecutive
    same-side D-check rejects is a REGIME CHANGE, not noise — feed
    the run median (bypassing the D-check) instead of the circular
    reanchorToDMarker. Mixed-side streaks keep the old reanchor.
  SKIP-AHEAD is V-c (prevent the lag growth in the first place).
  T-a sub-split: close the tx group when a fragment arrives >=300
    ms after the previous one (was 800 ms).
  T-b T-a + MAX_SLICES 40 -> 120 (audioAt covers 60 s not 20 s).
  T-c only if T-a/T-b insufficient: force-close a group when its
    span exceeds 6000 ms.
  T-d audioAt-only re-anchor: _audio_at extrapolates 1:1
    wall->speech past the slice edges, so the tx re-anchor never
    needs the VAD-marker fallback (tx truly VAD-independent).

GATE v1.0.22 (10-min session, ratios 1.42 AND 1.51, BOTH films):
  end-burst p90 (TRUE render) < 1000 ms; burst-start sync max/p90
  < 1000 ms; slice-play steady p90 < 1000 ms; D pinned 0%; pairs
  > 0 in EVERY 60 s window; samples/60 s >= 40 (min over windows);
  dropped audio steady < 5%; line-start steady median < 1200 ms;
  D tracks the last sample (gap < 3000 ms) AND no true lag ever
  exceeds 20000 ms (the 12 s answer: chase or skip-ahead, never
  stand still at ~5 s while the sample is 12 s). Regressions: v6 +
  sim1018 as in sim1021. Hold-decay (21p) and BURST_GAP 150 ms
  (21r) are NOT retried (failed and reverted in v1.0.21).
"""
import heapq
import math
import os
import random
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1021 as s21
import sim1020 as s20
import sim1019 as s19
import sim1018 as s18
import sim1014_v6 as v6
from sim1020 import (CHUNK_MS, KEY_ROTATE_AT_MS, TOTAL_MS, VAD_LAG_MS,
                     SEED, max_unexplained_gap,
                     max_unexplained_render_gap, pct)

LONG_MS = 600_000
RATIO22_A = 1.42
RATIO22_B = 1.51
LAG22_A = 4600
LAG22_B = 7800

# World22 film A (09:10): giant VAD turns ~53 s, turns60=1.
WORLD22 = dict(tonal_p=0.95, music_p=0.95, short_p=0.80)
# World22 dense film B (09:21): turns ~14 s, turns60~3-4, 96% speech.
WORLD22_DENSE = dict(tonal_p=0.80, music_p=0.90, short_p=0.80)


def build_world22(ratio=RATIO22_A, speed=1.5, seed=SEED,
                  tonal_p=0.95, music_p=0.95, short_p=0.80,
                  soft_frac=0.8, total_ms=LONG_MS):
    """build_world21 with the pause/music knobs exposed. Film A:
    tonal 0.95 / music 0.95 / short 0.80 (turns ~53 s). Film B
    dense: tonal 0.80 / music 0.90 / short 0.80 (turns ~14 s,
    96% speech)."""
    rng = random.Random(seed)
    frames = []
    line_start, line_ulen = {}, {}
    t = 0
    line_id = 0

    def add_frames(t0, dur_ms, fn, lid):
        tt = t0
        rem = dur_ms
        while rem > 0:
            step = min(20, rem)
            rms, gate = fn()
            frames.append((tt, rms, lid, gate))
            tt += step
            rem -= step

    def speech_fn(ulen):
        segs = []
        rem = ulen
        while rem > 0:
            seg = min(rng.randint(100, 300), rem)
            rms = (rng.uniform(300, 550)
                   if rng.random() < soft_frac
                   else rng.uniform(1200, 2500))
            segs.append((seg, rms))
            rem -= seg
        idx = [0]
        pos = [0]

        def fn():
            seg, rms = segs[idx[0]]
            pos[0] += 20
            if pos[0] >= seg and idx[0] + 1 < len(segs):
                idx[0] += 1
                pos[0] = 0
            gate = rng.random() < s20.GATE_SPEECH_P
            return rms * s20.LEVEL_SCALE * rng.uniform(0.9, 1.1), gate
        return fn

    def music_fn():
        tonal = rng.random() < tonal_p
        if tonal:
            level = rng.uniform(*s21.MUSIC_TONAL_LEVEL)
            gp = s21.MUSIC_TONAL_GATE
        else:
            level = rng.uniform(*s21.MUSIC_ATONAL_LEVEL)
            gp = s21.MUSIC_ATONAL_GATE

        def fn():
            gate = rng.random() < gp
            return level * s20.LEVEL_SCALE * rng.uniform(0.95, 1.05), gate
        return fn

    while t < total_ms:
        ulen = rng.randint(1500, 3000)
        if rng.random() < short_p:
            pause = rng.randint(100, 400)
        else:
            pause = rng.randint(1500, 3500)
        ulen = min(ulen, total_ms - t)
        if ulen <= 0:
            break
        line_start[line_id] = t
        line_ulen[line_id] = ulen
        add_frames(t, ulen, speech_fn(ulen), line_id)
        if pause > 0:
            if rng.random() < music_p:
                add_frames(t + ulen, pause, music_fn(), -1)
            else:
                add_frames(t + ulen, pause,
                           lambda: (30.0, False), -1)
        line_id += 1
        t = t + ulen + pause
    frames.sort(key=lambda x: x[0])
    return frames, line_start, line_ulen


# ----------------------------------------------------------------
# Tracker v1.0.22 (subclass of NewTracker21; sim1021.py untouched).
# ----------------------------------------------------------------
class NewTracker22(s21.NewTracker21):
    """v1.0.22 tracker changes. Flags are per-run instance attrs
    set by run22 (class defaults = v1.0.21 as shipped)."""

    RATIO_FLOOR = False      # V-a
    RATIO_ORACLE = False     # diagnostic only: floor from the
                           # KNOWN true ratio instead of the
                           # measured ratio() (separates 'does
                           # the V-a mechanism work' from 'is
                           # the measurement right')
    CATCHUP = False          # V-b (needs RATIO_FLOOR)
    TAIL_VALVE = False       # V-c (needs CATCHUP)
    CHASE = False            # D-chase (12 s answer)
    CHASE_N = 6              # same-side rejects = regime change
    RATIO_TX = False         # measure ratio from tx-ok line positions
    TX_SUBSPLIT = False      # T-a (tx group gap 800 -> 300 ms)
    TX_FORCE_CLOSE_MS = 0    # T-c (0 = off; else force-close span)
    TX_AUDIOAT_EXTRAP = False  # T-d (re-anchor without VAD marker)

    TX_SUB_GAP_MS = 300
    CATCHUP_GAIN_MS = 5000.0
    VALVE_THRESH_MS = 1500
    VALVE_KEEP_MS = 1000
    D_CHECK_MS = 1500        # SLICE_D_CHECK_MS (Kotlin)

    def __init__(self):
        super().__init__()
        # Corrected end-lag of the most recent burst, as seen at
        # its play time (partial pairing log, like Kotlin).
        self._vb_end_lag = 0
        self._vb_end_lag_kot = 0   # same slice, 1.0x assumption
        self._vb_end_lag_clamps = 0
        self._vb_play_seq = None
        self._vb_valve_used_seq = None
        # Tail valve (V-c) state.
        self._tail_drop_remaining = 0
        self.tail_dropped_ms = 0
        self.tail_valve_fires = 0
        self.tail_valve_drop_ms = 0
        self.tail_dropped_chunks = []  # (arrival, line_id, dms)
        # D-chase state.
        self._chase_run = []       # [(sample, sign)] trailing run
        self.chase_accepts = 0
        self.chase_runs = []       # (now, median, run_len)
        self.chase_rejects_seen = 0
        self.floor_hist = []       # (now, ratio floor) per update
        # Tx-ratio measurement state.
        self._tx_ratio_prev = None  # (audio_pos, cum_dub)
        self.ratio_tx_meas = 0
        self._ratio_oracle = None  # true speech/dub, if set

    # -- V-a/V-b: ratio floor + catch-up (max-wins) --
    def _ratio_speed(self):
        """Measured dub/speech ratio (1/ratio()), clamped
        [1.0, 1.5]. The tracker's ratio() is the burst-successor
        measurement with the 5000/6500 prior — early in the
        session it sits near the 1.30 prior and converges to the
        true ratio as validated bursts accumulate. ORACLE: the
        known true speech/dub, bypassing the measurement."""
        if self.RATIO_ORACLE and self._ratio_oracle:
            return max(1.0, min(1.5, 1.0 / self._ratio_oracle))
        return max(1.0, min(1.5, 1.0 / self.ratio()))

    def _ratio_floor(self, now):
        if not self.RATIO_FLOOR:
            return 1.0
        # Decay to 1.0 when silent >5 s (no burst play): the
        # floor is a DUBBING speed, not an idle speed.
        if self.last_play_wall and now - self.last_play_wall > 5000:
            return 1.0
        f = self._ratio_speed()
        if self.CATCHUP and self._vb_end_lag > 0:
            f = f * (1.0 + self._vb_end_lag / self.CATCHUP_GAIN_MS)
        return max(1.0, min(1.5, f))

    def _stretch_update(self, now):
        super()._stretch_update(now)
        f = self._ratio_floor(now)
        self.floor_hist.append((now, round(f, 3)))
        if f > self.target_speed:
            self.target_speed = f

    # -- V-b input: corrected end-lag at burst play time --
    def _compute_vb_end_lag(self, seq, play_wall):
        """Last pairing entry of this burst SO FAR (the play-time
        snapshot Kotlin sees), corrected for the stretch: the
        true play wall of a slice at dub offset o is
        playWall + o/speed, not playWall + o (the Kotlin 1.0x
        assumption). Both numbers are kept: the corrected one
        drives V-b/V-c, the Kotlin one is the diagnostic the
        device actually shows."""
        self._vb_play_seq = seq
        entries = [e for e in self.pairing_log if e[2] == seq]
        if not entries:
            self._vb_end_lag = 0
            self._vb_end_lag_kot = 0
            return
        rec = self.burst_records.get(seq)
        if rec is None:
            self._vb_end_lag = 0
            self._vb_end_lag_kot = 0
            return
        start_cum = rec['start_cum']
        last = max(entries, key=lambda e: e[0])
        dub_cum, src_wall = last[0], last[1]
        off = dub_cum - start_cum
        if off < 0:
            off = 0
            self._vb_end_lag_clamps += 1
        speed = max(1.0, self.target_speed)
        self._vb_end_lag_kot = (play_wall + off - src_wall) \
            - self.delay
        self._vb_end_lag = (play_wall + off / speed - src_wall) \
            - self.delay

    def on_burst_played(self, seq, play_wall, pipeline, backlog=0):
        self._compute_vb_end_lag(seq, play_wall)
        super().on_burst_played(seq, play_wall, pipeline, backlog)

    # -- V-c: tail valve, armed at the next burst boundary --
    def on_dub_burst(self, now, true_lag, true_sample=None):
        super().on_dub_burst(now, true_lag, true_sample)
        if (self.TAIL_VALVE
                and self._vb_play_seq is not None
                and self._vb_play_seq != self._vb_valve_used_seq
                and self._vb_end_lag > self.VALVE_THRESH_MS
                and self.target_speed >= 1.49):
            drop = int(self._vb_end_lag - self.VALVE_KEEP_MS)
            if drop > 0:
                self._tail_drop_remaining += drop
                self.tail_valve_fires += 1
                self.tail_valve_drop_ms += drop
                self._vb_valve_used_seq = self._vb_play_seq

    # -- CHASE: a same-side D-check reject run is a regime change.
    # Mirrors Kotlin feedSample, except the 6th consecutive
    # SAME-SIDE reject feeds the run median instead of the circular
    # reanchorToDMarker. Mixed-side streaks keep the old reanchor.
    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        if boundary or not self.CHASE:
            return super().feed_sample(sample, now, true_lag,
                                       boundary=boundary, **kw)
        dlag = self._d_based_lag()
        if abs(sample - dlag) <= self.D_CHECK_MS:
            self._chase_run = []
            return super().feed_sample(sample, now, true_lag,
                                       boundary=boundary, **kw)
        sign = 1 if sample > dlag else -1
        if self._chase_run and self._chase_run[0][1] != sign:
            self._chase_run = []
        self._chase_run.append((sample, sign))
        self.chase_rejects_seen += 1
        if len(self._chase_run) >= self.CHASE_N:
            run = sorted(s for s, _ in self._chase_run)
            med = run[len(run) // 2]
            self.chase_accepts += 1
            self.chase_runs.append((now, med, len(run)))
            self._chase_run = []
            self.slice_d_streak = 0
            # Bypass NewTracker19's D-check (the accept path of
            # the grandparent feed_sample) — the median is the new
            # regime, not an outlier.
            return super(s19.NewTracker19, self).feed_sample(
                med, now, true_lag, boundary=False, **kw)
        # Not yet N: exactly the parent's rejection, including the
        # streak>=6 reanchor when the sides are mixed (a same-side
        # run of 6 would already have accepted above).
        self.slice_d_reject += 1
        self.slice_d_streak += 1
        self.sample_err.append((now, sample - true_lag))
        self._log_pairing(sample, now)
        if self.slice_d_streak >= 6:
            m = self._select_marker(now, dlag, False)
            if m is not None:
                self._reanchor(m['audio'], now, True)
                self.d_reanchors += 1
            self.slice_d_streak = 0
        return max(0, self.delay - sample)

    # -- RATIO_TX: measure dub/speech from consecutive tx-ok line
    # positions. The burst-successor chain is starved on giant-turn
    # worlds (it needs two consecutive VALIDATED bursts with
    # consecutive markers, but mid-turn bursts fail validation) —
    # 0 measurements in both REPROs, so ratio() sits on the
    # music-inflated cumulative fallback (0.98 vs true 0.70) and
    # the V-a floor would be ~1.03 (a no-op). A tx-ok gives an
    # exact line-start speech position + the dub cum at that
    # burst, so consecutive tx-oks measure the ratio per line
    # group with the same sanity bounds — no marker seq, no
    # D-validation gate (the tx D-check + residual bound already
    # validated the sample). --
    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        ok_before = self.tx_ok
        super().apply_tx_anchor(now, burst_seq, true_lag,
                                true_sample)
        if not self.RATIO_TX or self.tx_ok <= ok_before:
            return
        pos = self._tx_last_audio_pos
        if pos is None:
            return
        if self._tx_ratio_prev is not None:
            paudio, pdub = self._tx_ratio_prev
            counted_diff = pos - paudio
            dub_diff = self.cum_dub - pdub
            expected = counted_diff * 1.3
            if counted_diff >= 500 and \
                    0.7 * expected <= dub_diff <= 1.6 * expected:
                self.sum_counted += counted_diff
                self.sum_total += dub_diff
                self.ratio_tx_meas += 1
        self._tx_ratio_prev = (pos, self.cum_dub)

    # -- T-a/T-c: tx grouping with a 300 ms sub-split gap and an
    # optional forced close on group span. --
    def note_tx_in_fragment(self, turn_idx, arrival):
        gap = (self.TX_SUB_GAP_MS if self.TX_SUBSPLIT
               else s21.TX_GAP_MS)
        force = self.TX_FORCE_CLOSE_MS
        if (self._tx_cur_group is not None and force
                and arrival - self._tx_cur_group['first'] >= force):
            self._close_tx_group()
        elif (self._tx_last_frag_at is not None
                and arrival - self._tx_last_frag_at >= gap):
            self._close_tx_group()
        self._tx_last_frag_at = arrival
        if self._tx_cur_group is None:
            self._tx_cur_group = {'first': arrival, 'last': arrival,
                                  'turn_idx': turn_idx, 'closed': False}
            self.tx_groups.append(self._tx_cur_group)
            self.tx_groups_seen += 1
        else:
            self._tx_cur_group['last'] = arrival
        entry = self.tx_src.get(turn_idx)
        if entry is None:
            self._tx_pending_frags.setdefault(turn_idx, []).append(
                arrival)
        else:
            self._attach_tx_frag(entry, turn_idx, arrival)

    # -- T-d: _audio_at extrapolates 1:1 wall->speech past the
    # slice edges, so the tx re-anchor never needs the VAD-marker
    # fallback (audioAt covers 20 s at MAX_SLICES=40; in dense
    # film wall time ~= speech time, so the extrapolation is
    # accurate to the pause fraction). --
    def _audio_at(self, wall):
        r = super()._audio_at(wall)
        if r is not None or not self.TX_AUDIOAT_EXTRAP:
            return r
        if not self.slices:
            return None
        if wall < self.slices[0][0]:
            sw, sa, ew, ea = self.slices[0]
            return max(0, sa - (sw - wall))
        sw, sa, ew, ea = self.slices[-1]
        return ea + (wall - ew)


# ----------------------------------------------------------------
# Event loop (run21 + kotlin-vs-true raw records + tx outcome
# logging + tail-valve drop + seek/frame-garbage test).
# ----------------------------------------------------------------
def _tx_no_mk_reason(tr, now):
    """Classify a tx no-mk outcome into the three verified gates:
    (a) no CLOSED group in window (sub: no group at all vs groups
    present but none closed); (b) residual bound 1200 ms once the
    reference is established; (c) re-anchor fallback (audioAt null
    AND nearest marker >2500 ms). Mirrors the order of checks in
    applyTxAnchor; lat-range/monotone failures count as cons in
    the tracker, so a no-mk that passed (a) and (b) is (c)."""
    burst_arrival = getattr(tr, '_last_burst_arrival', None)
    if burst_arrival is None:
        return 'other'
    dlag = tr._sel_lag()
    target = burst_arrival - dlag
    in_win = []
    closed_in_win = []
    for g in tr.tx_groups:
        start_est = tr._tx_sentence_start(g)
        if burst_arrival - s21.TX_WIN_MS <= start_est \
                <= burst_arrival - 300:
            in_win.append(g)
            if g.get('closed'):
                closed_in_win.append(g)
    if not in_win:
        return 'a-no-group-in-window'
    if not closed_in_win:
        return 'a-no-closed-group'
    best_res = min(abs(tr._tx_sentence_start(g) - target)
                   for g in closed_in_win)
    if tr._ref_established() and best_res > s21.TX_RES_MS:
        return 'b-residual'
    return 'c-refallback'


def _run22_inner(tracker_cls, vad, dub, completes, line_start,
                 txin, txsrc, min_buf=16384, line_counted=None,
                 tx_on=True, level_cap=1.25, tx_hold=False,
                 stale_skip=False, total_ms=TOTAL_MS,
                 ratio_floor=False, catchup=False, tail_valve=False,
                 tx_subsplit=False, tx_force_close_ms=0,
                 chase=False, chase_n=6, ratio_tx=False,
                 tx_audioat_extrap=False, ratio_oracle=None,
                 seek_at_ms=0, seek_jump_ms=-47000):
    """Like s21.run21, plus: per-slice raw records (render, source
    wall, dubCum, burst, D) for the kotlin-vs-true measurement;
    per-sample recording (accepted + D-check-rejected) for the
    12 s analysis; tx outcome logging with no-mk attribution;
    tail-valve drop at burst boundaries; a mid-session seek event
    that tests the frame-residual continuity guard."""
    tr = tracker_cls()
    if hasattr(tr, 'TX_ON'):
        tr.TX_ON = tx_on
        tr.LEVEL_CAP = level_cap
        tr.TX_HOLD = tx_hold
        tr.STALE_SKIP = stale_skip
    for _k, _v in (('RATIO_FLOOR', ratio_floor), ('CATCHUP', catchup),
                   ('TAIL_VALVE', tail_valve),
                   ('TX_SUBSPLIT', tx_subsplit),
                   ('TX_FORCE_CLOSE_MS', tx_force_close_ms),
                   ('CHASE', chase), ('CHASE_N', chase_n),
                   ('RATIO_TX', ratio_tx),
                   ('TX_AUDIOAT_EXTRAP', tx_audioat_extrap)):
        if hasattr(tr, _k):
            setattr(tr, _k, _v)
    if ratio_oracle is not None:
        tr._ratio_oracle = ratio_oracle
        tr.RATIO_ORACLE = True
    ema = getattr(tr, 'ema', None)
    if ema is not None and hasattr(ema, 'max_d'):
        ema.max_d = 8000
    is_new = isinstance(tr, s19.NewTracker19)
    is_new20 = isinstance(tr, s20.NewTracker20)
    ts = s19.TrackSim19(min_buf)
    heap = []
    order = 0

    def push(t, kind_order, kind, payload):
        nonlocal order
        order += 1
        heapq.heappush(heap, (t, kind_order, order, kind, payload))

    for t, kind, payload in vad:
        push(t, 0, 'vad', (kind, payload))
    for item in dub:
        arrival, dms, true_lag, tid = item[0], item[1], item[2], item[3]
        push(arrival, 1, 'dub', (dms, true_lag, tid))
    for item in completes:
        if isinstance(item, tuple):
            push(item[0], 1, 'tc', item[1])
        else:
            push(item, 1, 'tc', None)
    for (end_wall, ti, s0, e0, sent_ms, lid) in txsrc:
        push(end_wall, 1, 'txsrc', (ti, s0, e0, sent_ms, lid))
    for (arr, ti) in txin:
        push(arr, 1, 'txin', ti)
    push(KEY_ROTATE_AT_MS, 2, 'rotate', ())
    if seek_at_ms:
        push(seek_at_ms, 2, 'seek', ())

    # -- per-sample recording (accepted AND D-check-rejected) --
    tr.all_sample_times = []
    tr.all_feed_samples = []   # (now, sample) incl. rejects
    _orig_feed = tr.feed_sample

    def _rec_feed(sample, now, true_lag, boundary=False, **kw):
        tr.all_feed_samples.append((now, sample))
        before_last = tr.samples60[-1] if tr.samples60 else None
        r = _orig_feed(sample, now, true_lag, boundary=boundary,
                       **kw)
        if tr.samples60 and tr.samples60[-1] == now \
                and before_last != now:
            tr.all_sample_times.append(now)
        return r

    tr.feed_sample = _rec_feed
    # -- tx outcome logging with no-mk attribution --
    tr.tx_outcomes = []        # (now, outcome)
    tr.tx_no_mk_reasons = Counter()
    if hasattr(tr, 'apply_tx_anchor'):
        _orig_tx = tr.apply_tx_anchor

        def _rec_tx(now, burst_seq, true_lag, true_sample=None):
            before = (tr.tx_ok, tr.tx_no_mk, tr.tx_cons, tr.tx_agree)
            _orig_tx(now, burst_seq, true_lag, true_sample)
            after = (tr.tx_ok, tr.tx_no_mk, tr.tx_cons, tr.tx_agree)
            if after[0] > before[0]:
                oc = 'ok'
            elif after[1] > before[1]:
                oc = 'no-mk'
                tr.tx_no_mk_reasons[
                    _tx_no_mk_reason(tr, now)] += 1
            elif after[2] > before[2]:
                oc = 'cons'
            elif after[3] > before[3]:
                oc = 'agree'
            else:
                oc = 'none'
            tr.tx_outcomes.append((now, oc))

        tr.apply_tx_anchor = _rec_tx

    rng_tx = random.Random(SEED + 4242)
    first_sent_at = 0
    utt_start = 0
    d_hist = []
    next_hist = 5000
    last_true_lag = 1400
    last_dub_arrival = None
    run_burst_seq = 0
    first_after_rotate = True
    hold_saturations = 0
    bursts_seen = 0
    last_tid = None
    tr.line_start_errs = []
    tr.line_start_times = []
    tr.line_start_fresh_errs = []
    tr.line_start_fresh_times = []
    tr.slice_play_errs = []
    tr.slice_play_times = []
    tr.slice_play_bseq = []
    tr.sync_times = []
    tr.measured_pipelines = []
    tr.backlogs = []
    tr.true_pipelines = []
    tr.dropped_audio_ms = 0
    tr.total_dub_ms = sum(item[1] for item in dub)
    tr.speed_hist = []
    chunk_idx = [0]
    tr.dropped_steady_ms = 0
    tr.total_steady_ms = 0
    tr.pipeline_samples = []
    tr.dbg_chunks = []
    tr.take_late = []
    tr.speed_used = []
    lines_with_burst = set()
    lines_with_render = set()
    render_times = []
    burst_renders = []
    old_style_resets = [0]
    burst_info = {}
    tr.slice_raw = []         # (render, src_wall, dub_cum, bseq, D)
    tr.slice_arrivals = []    # (arrival, dub_cum, src_wall, bseq)
    tr.chunk_raw = []         # (render, arrival, true_src_wall,
                              #  dub_offset_in_burst, bseq, D, speed)
    tr.frame_residuals = []   # proxy frame residuals (post-seek ok)
    tr.seek_frame_dropped = 0
    tr.seek_frame_checked = 0
    tr._seek_stale_until = 0
    tr.burst_play_wall = {}    # burst seq -> render of 1st chunk
    tr.burst_start_cum_d = {}  # burst seq -> cum_dub at burst start
    tr.burst_D_at_play = {}    # burst seq -> D at play time

    while heap:
        t, _, _, kind, payload = heapq.heappop(heap)
        if t > total_ms:
            break
        if kind == 'vad':
            k, p = payload
            if not is_new20 and k in ('start', 'chunk'):
                before = getattr(tr, 'cum_speech', None)
                if k == 'start':
                    utt_start = t
                    first_sent_at = first_sent_at or t
                    tr.on_turn_start(t + VAD_LAG_MS)
                    tr.on_speech_ms(160, t + VAD_LAG_MS)
                elif k == 'chunk':
                    tr.on_speech_ms(p, t)
                after = getattr(tr, 'cum_speech', None)
                if (before is not None and after is not None
                        and before > 0 and after < before):
                    old_style_resets[0] += 1
            elif k == 'start':
                utt_start = t
                first_sent_at = first_sent_at or t
                tr.on_turn_start(t + VAD_LAG_MS)
                tr.on_speech_ms(160, t + VAD_LAG_MS)
            elif k == 'chunk':
                tr.on_speech_ms(p, t)
            elif k == 'end':
                utt_start = 0
                tr.on_turn_end(t)
        elif kind == 'txsrc':
            ti, s0, e0, sent_ms, lid = payload
            if hasattr(tr, 'note_tx_source'):
                tr.note_tx_source(ti, s0, e0, sent_ms, lid)
        elif kind == 'txin':
            ti = payload
            if hasattr(tr, 'note_tx_in_fragment'):
                tr.note_tx_in_fragment(ti, t)
        elif kind == 'txout':
            bseq, true_lag, true_sample = payload
            if hasattr(tr, 'note_tx_out_fragment'):
                tr.note_tx_out_fragment(bseq, t)
            if hasattr(tr, 'apply_tx_anchor'):
                tr.apply_tx_anchor(t, bseq, true_lag, true_sample)
        elif kind == 'dub':
            dms, true_lag, tid = payload
            last_true_lag = true_lag
            was_first = first_after_rotate
            is_burst = first_after_rotate or (
                last_dub_arrival is not None and
                t - last_dub_arrival >= tr.BURST_GAP)
            first_after_rotate = False
            last_dub_arrival = t
            if is_burst:
                run_burst_seq += 1
                bursts_seen += 1
                tr.burst_seq = run_burst_seq
                lines_with_burst.add(tid)
                true_burst = t - line_start[tid]
                burst_info[run_burst_seq] = (true_lag, true_burst)
                n_out = rng_tx.randint(1, 2)
                base = t + rng_tx.randint(*s21.TX_OUT_FRAG_DELAY)
                for j in range(n_out):
                    push(base + j * s21.TX_OUT_FRAG_STEP, 1, 'txout',
                         (run_burst_seq, true_lag, true_burst))
            if not tr.seeded and first_sent_at:
                tr.seeded = True
                ref = first_sent_at
                if tr.markers:
                    ref = tr.markers[0]['wall']
                el = max(0, min(t - ref, 18000))
                tr.seed_lag = el
                tr.apply_margin()
                tr.ema.seed(el)
                nd = max(3000, min(el + tr.dynamic_margin(), 8000))
                tr._set_delay(nd)
                tr.hold = max(0, nd - el - tr.pipeline_ms())
            log_before = len(tr.pairing_log)
            true_burst = t - line_start[tid] if is_burst else None
            hold = tr.on_dub(t, dms, true_lag, true_burst)
            if hasattr(tr, 'is_burst_stale') \
                    and tr.is_burst_stale(run_burst_seq):
                tr.dropped_audio_ms += dms
                if t < 180_000 or t > 300_000:
                    tr.dropped_steady_ms += dms
                continue
            # (V-c) tail valve: drop the head of the new burst
            # until the armed amount is consumed.
            if getattr(tr, '_tail_drop_remaining', 0) > 0:
                tr._tail_drop_remaining -= dms
                tr.dropped_audio_ms += dms
                tr.tail_dropped_ms += dms
                tr.tail_dropped_chunks.append((t, tid, dms))
                if t < 180_000 or t > 300_000:
                    tr.dropped_steady_ms += dms
                continue
            ts.speed = tr.target_speed if is_new else 1.0
            tr.speed_used.append(ts.speed)
            due = t + hold
            tr.take_late.append(max(0, ts.busy_until - due))
            if is_new:
                tr.note_lateness(max(0, ts.busy_until - due), t)
                ts.speed = tr.target_speed
                tr.speed_used[-1] = ts.speed
            render, observed, backlog = ts.feed(due, dms, is_burst)
            if render is None:
                tr.dropped_audio_ms += dms
                if t < 180_000 or t > 300_000:
                    tr.dropped_steady_ms += dms
                continue
            # Chunk-level TRUE record: the chunk's true source wall
            # is arrival - true_lag (the dub generator's own
            # mapping); the mid-chunk dub position gives the offset
            # within the burst for the Kotlin formula.
            push(render, 3, 'chunk_render',
                 (t, t - true_lag, tr.cum_dub - dms / 2.0,
                  run_burst_seq, ts.speed))
            lines_with_render.add(tid)
            render_times.append(render)
            tr.true_pipelines.append(render - due)
            if t < 180_000 or t > 300_000:
                tr.total_steady_ms += dms
            if getattr(tr, 'last_sample_at', None) == t:
                tr.dbg_chunks.append((t, tr.last_sample, hold,
                                      render - due, tr.delay,
                                      tr.lag_est(t), true_lag))
            if is_new:
                tr.note_level(ts.level, ts.cap_ms, t)
                chunk_idx[0] += 1
                if chunk_idx[0] % 20 == 0:
                    tr.on_pipeline_sample(observed - due, backlog)
                    tr.pipeline_samples.append(observed - due)
            if tid != last_tid:
                last_tid = tid
                err_ls = (render - line_start[tid]) - tr.delay
                tr.line_start_errs.append(err_ls)
                tr.line_start_times.append(t)
                try:
                    m = tr._select_marker(t, tr.lag_est(t), False)
                except Exception:
                    m = None
                if m is not None:
                    lat_meas = t - m['wall']
                    hold_fresh = max(0, tr.delay - lat_meas
                                     - tr.pipeline_ms())
                    err_fresh = (render - hold + hold_fresh
                                 - line_start[tid]) - tr.delay
                    tr.line_start_fresh_errs.append(err_fresh)
                    tr.line_start_fresh_times.append(t)
            for (dub_cum, wall, bseq) in tr.pairing_log[log_before:]:
                push(render, 3, 'slice_play',
                     (line_start[tid], bseq, wall, dub_cum))
                tr.slice_arrivals.append((t, dub_cum, wall, bseq))
            if is_burst:
                if tr.hold == 0:
                    hold_saturations += 1
                if was_first:
                    tr._last_burst_marker = None
                tr.record_burst(run_burst_seq, t, hold,
                                tr.cum_dub - dms)
                tr.burst_start_cum_d[run_burst_seq] = tr.cum_dub - dms
                push(render, 3, 'play',
                     (run_burst_seq, render, observed, due, tid,
                      backlog))
        elif kind == 'tc':
            seq = payload
            tsamp = None
            if seq is not None and line_counted is not None:
                tsamp = t - line_start[seq] - line_counted[seq] - 200
            tr.on_dub_turn_complete(t, last_true_lag, tsamp)
        elif kind == 'rotate':
            tr.reset_reconnect()
            first_after_rotate = True
        elif kind == 'seek':
            # Episode change / seek: bursts whose marker predates
            # the seek and whose play follows it straddle the jump
            # (video time went back ~47 s while wall time advanced).
            tr._seek_stale_until = t + 30000
        elif kind == 'play':
            seq, render, observed, due, tid, backlog = payload
            true_marker = line_start[tid]
            err = (render - true_marker) - tr.delay
            tr.burst_play_wall[seq] = render
            tr.burst_D_at_play[seq] = tr.delay
            # Frame-residual continuity test (v1.0.22 Kotlin):
            # a burst straddling the seek pairs a pre-seek marker
            # with a post-seek play — videoDelta = wallDelta - 47 s
            # fails the (-3 s, +5 s) continuity window, so the
            # residual is dropped ("—"), never shown as -52 s.
            if seek_at_ms:
                tr.seek_frame_checked += 1
                mrec = tr.burst_records.get(seq)
                mwall = mrec['marker_wall'] if mrec else None
                if mwall is not None and mwall < seek_at_ms \
                        <= render:
                    tr.seek_frame_dropped += 1
                elif abs(err) > 10000:
                    tr.seek_frame_dropped += 1
                else:
                    tr.frame_residuals.append(err)
            tr.sync_errs.append(err)
            tr.sync_times.append(t)
            tr.measured_pipelines.append(observed - due)
            tr.backlogs.append(backlog)
            burst_renders.append(render)
            tr.on_burst_played(seq, observed, observed - due, backlog)
            tr.speed_hist.append((render, tr.target_speed
                                  if is_new else 1.0))
        elif kind == 'slice_play':
            wall, bseq, src_wall, dub_cum = payload
            tr.slice_play_errs.append((t - wall) - tr.delay)
            tr.slice_play_times.append(t)
            tr.slice_play_bseq.append(bseq)
            tr.slice_raw.append((t, src_wall, dub_cum, bseq,
                                 tr.delay))
        elif kind == 'chunk_render':
            arr, tsw, mid_dub, bseq, spd = payload
            sc = tr.burst_start_cum_d.get(bseq)
            off = (mid_dub - sc) if sc is not None else None
            tr.chunk_raw.append((t, arr, tsw, off, bseq, tr.delay,
                                 spd))
        while next_hist <= t:
            d_hist.append((next_hist, tr.delay))
            next_hist += 5000
    end = total_ms
    s60 = [x for x in tr.samples60 if end - x <= 60000]
    tp60 = [x for x in tr.turn_pair_times if end - x <= 60000]
    tp_first60 = [x for x in tr.turn_pair_times if x <= 60000]
    d_pinned = [d for _, d in d_hist if d >= 7900]
    extra = {'lines_with_burst': lines_with_burst,
             'tp_first60': len(tp_first60),
             'first_sync_at': (min(tr.sync_times)
                             if tr.sync_times else None),
             'd_pinned_frac': (len(d_pinned) / max(1, len(d_hist))),
             'lines_with_render': lines_with_render,
             'render_times': render_times,
             'burst_renders': burst_renders,
             'old_style_resets': old_style_resets[0],
             'burst_play': dict(tr.burst_play_wall),
             'burst_start_cum': dict(tr.burst_start_cum_d),
             'burst_D': dict(tr.burst_D_at_play)}
    return tr, d_hist, len(s60), len(tp60), hold_saturations, \
        bursts_seen, extra


def run22(*args, **kwargs):
    """run22 with the MAX_SLICES patch for T-b (v6 module global;
    restored afterwards)."""
    max_slices = kwargs.pop('max_slices', 40)
    _old = v6.MAX_SLICES
    v6.MAX_SLICES = max_slices
    try:
        return _run22_inner(*args, **kwargs)
    finally:
        v6.MAX_SLICES = _old


# ----------------------------------------------------------------
# Analysis helpers.
# ----------------------------------------------------------------
def _med(vals):
    if not vals:
        return None
    return pct(sorted(vals), 0.5)


def _p90(vals):
    return s21._p90_abs(vals)


def _mx(vals):
    return s21._max_abs(vals)


def kotlin_vs_true(tr, extra, dub, ratio_true=1 / RATIO22_A):
    """Kotlin-reported vs TRUE slice-play error, decomposed.
    TRUE series (chunk level): each rendered chunk's true source
    wall is arrival - true_lag (the dub generator's own mapping),
    so true_err = (render - true_src_wall) - D. The gate's
    end-lateness is the last chunk of each burst on this series.
    KOTLIN series (pairing entries, exactly as Kotlin computes
    them): kot = (playWall + (dubCum - startCum) - anchor_wall)
    - D_at_play, where anchor_wall is the tracker's (possibly
    wrong) pairing wall. kot_play uses only the entries paired
    SO FAR at play time (what the HUD shows). On the LAST pairing
    entry of each burst the total bias decomposes exactly against
    the TRUE error of the SAME audio (the rendered chunk nearest
    in dub position to the entry's dubCum, same D_at_play):
      kot - true = [kot - kot_truewall] (ANCHOR error: the
        pairing wall is wrong) + [kot_truewall - true]
        (PURE 1.0x bias: same slice, true wall, Kotlin's
        playWall+offset vs the actual render wall).
    The dub-position -> true-wall map is built from the dub list
    (chunk mid source wall = arrival - true_lag). "Steady" series
    use the sim family's convention: render outside [180 s,
    300 s]. p90 throughout is the sim convention: p90 of |value|.
    """
    import bisect
    bounds = []
    spans = []
    cum = 0
    for (arr, dms, tl, tid) in dub:
        bounds.append(cum)
        spans.append((arr - tl, dms, cum))
        cum += dms
    bounds.append(cum)

    def true_wall(p):
        i = bisect.bisect_right(bounds, p) - 1
        i = max(0, min(i, len(spans) - 1))
        src_mid, dms, c0 = spans[i]
        return src_mid + (p - (c0 + dms / 2.0)) / ratio_true

    play = extra['burst_play']
    sc = extra['burst_start_cum']
    bD = extra['burst_D']
    # --- TRUE series, chunk level ---
    by_burst = {}
    true_all = []
    true_all_steady = []
    pos_list = []   # (abs dub pos, render, true_src_wall, D)
    for (render, arr, tsw, off, bseq, d_at, spd) in tr.chunk_raw:
        if off is None:
            continue
        e = (render - tsw) - d_at
        true_all.append(e)
        if render < 180_000 or render > 300_000:
            true_all_steady.append(e)
        if bseq in sc:
            pos_list.append((sc[bseq] + off, render, tsw, d_at))
        by_burst.setdefault(bseq, []).append(
            (render, arr, tsw, off, d_at, spd))
    pos_list.sort(key=lambda x: x[0])
    pos_keys = [p[0] for p in pos_list]

    def nearest_chunk(dc):
        i = bisect.bisect_left(pos_keys, dc)
        cands = []
        if i < len(pos_list):
            cands.append(pos_list[i])
        if i > 0:
            cands.append(pos_list[i - 1])
        if not cands:
            return None
        return min(cands, key=lambda c: abs(c[0] - dc))

    true_end = []
    true_end_steady = []
    for bseq, entries in by_burst.items():
        last = max(entries, key=lambda e: e[3])
        render, arr, tsw, off, d_at, spd = last
        e = (render - tsw) - d_at
        true_end.append(e)
        if render < 180_000 or render > 300_000:
            true_end_steady.append(e)
    # --- KOTLIN series, pairing entries (anchor walls) ---
    pair_by_burst = {}
    for (render, src_wall, dub_cum, bseq, d_at) in tr.slice_raw:
        pair_by_burst.setdefault(bseq, []).append(
            (render, src_wall, dub_cum, d_at))
    arrivals = {}
    for (arr, dub_cum, src_wall, bseq) in tr.slice_arrivals:
        arrivals.setdefault(bseq, []).append((arr, dub_cum, src_wall))
    kot_full, kot_play = [], []
    kot_last_true = []   # true err of the last pairing entry's
                         # audio (chunk-referenced, same D)
    pure_bias, anchor_err, total_bias = [], [], []
    pure_frac = []       # pure 1.0x bias / dub offset in burst
    anchor_err_all = []
    d_play, d_play_full = [], []
    for bseq, entries in pair_by_burst.items():
        if bseq not in play or bseq not in sc:
            continue
        pw = play[bseq]
        off0 = sc[bseq]
        dplay = bD.get(bseq, entries[0][3])
        for (render, src_wall, dub_cum, d_at) in entries:
            anchor_err_all.append(true_wall(dub_cum) - src_wall)
        last = max(entries, key=lambda e: e[2])
        render, src_wall, dub_cum, d_at = last
        tw = true_wall(dub_cum)
        kf = (pw + (dub_cum - off0) - src_wall) - dplay
        ktw = (pw + (dub_cum - off0) - tw) - dplay
        nc = nearest_chunk(dub_cum)
        if nc is not None:
            _pos, crender, ctsw, _cd = nc
            te = (crender - ctsw) - dplay
        else:
            te = (render - tw) - dplay
        kot_full.append(kf)
        kot_last_true.append(te)
        pure_bias.append(ktw - te)
        _off = dub_cum - off0
        if _off > 0:
            pure_frac.append((ktw - te) / _off)
        anchor_err.append(kf - ktw)
        total_bias.append(kf - te)
        arrs = [a for a in arrivals.get(bseq, [])
                if a[0] <= pw]
        if arrs:
            _arr, dc2, sw2 = max(arrs, key=lambda a: a[1])
            kp = (pw + (dc2 - off0) - sw2) - dplay
        else:
            kp = kf
        kot_play.append(kp)
        d_play.append(kp - te)
        d_play_full.append(kp - kf)
    return {'n_bursts': len(true_end),
            'true_end': true_end,
            'true_end_steady': true_end_steady,
            'true_all': true_all, 'true_all_steady': true_all_steady,
            'kot_full': kot_full, 'kot_play': kot_play,
            'kot_last_true': kot_last_true,
            'pure_bias': pure_bias, 'pure_frac': pure_frac,
            'anchor_err': anchor_err,
            'total_bias': total_bias,
            'anchor_err_all': anchor_err_all,
            'd_play': d_play, 'd_play_full': d_play_full}


def per_window_stats(tr, total_ms=LONG_MS):
    """Pairs / accepted samples / tx outcomes per 60 s window."""
    wins = []
    for i in range(total_ms // 60000):
        a, b = i * 60000, (i + 1) * 60000
        pairs = len([x for x in tr.turn_pair_times if a <= x < b])
        samples = len([x for x in getattr(tr, 'all_sample_times', [])
                       if a <= x < b])
        tx_ok = len([1 for (t, o) in getattr(tr, 'tx_outcomes', [])
                     if a <= t < b and o == 'ok'])
        tx_nm = len([1 for (t, o) in getattr(tr, 'tx_outcomes', [])
                     if a <= t < b and o == 'no-mk'])
        wins.append({'pairs': pairs, 'samples': samples,
                     'tx_ok': tx_ok, 'tx_no_mk': tx_nm})
    return wins


def lag_analysis(tr, dub, d_hist):
    """True-lag trajectory (from the world, not the tracker) vs D,
    and the 12 s question: last feed sample (incl. D-check
    rejects) vs D, samples >20 s (dropped 'large'), D trajectory."""
    lags = [item[2] for item in dub]
    per_min = []
    for i in range(10):
        a, b = i * 60000, (i + 1) * 60000
        vals = [item[2] for item in dub
                if a <= item[0] < b]
        per_min.append(_med(vals) if vals else None)
    last_feed = tr.all_feed_samples[-1] if getattr(
        tr, 'all_feed_samples', None) else (None, None)
    d_end = d_hist[-1][1] if d_hist else tr.delay
    d_max = max(d for _, d in d_hist) if d_hist else tr.delay
    d_at = {}
    for (tt, dd) in d_hist:
        d_at[tt] = dd
    floor = getattr(tr, 'floor_hist', [])
    fl_vals = [f for (_, f) in floor]
    fl_first = [f for (tt, f) in floor if tt <= 60000]
    fl_steady = [f for (tt, f) in floor if tt > 300000]
    speed_used = getattr(tr, 'speed_used', [])
    return {
        'true_med': _med(lags), 'true_p90': _p90(lags),
        'true_max': max(lags) if lags else None,
        'true_per_min_med': per_min,
        'n_true_gt12': len([x for x in lags if x > 12000]),
        'n_true_gt20': len([x for x in lags if x > 20000]),
        'last_feed_now': last_feed[0], 'last_feed': last_feed[1],
        'd_end': d_end, 'd_max': d_max,
        'd_gap': (last_feed[1] - d_end
                  if last_feed[1] is not None else None),
        'd_60': d_at.get(60000), 'd_300': d_at.get(300000),
        'dropped_large': getattr(tr, 'dropped', 0),
        'deferred_small': getattr(tr, 'deferred', 0),
        'dropped_slices': getattr(tr, 'dropped_slices', 0),
        'ratio_display': 1.0 / tr.ratio(),
        'floor_mean': (_med(fl_vals) if fl_vals else None),
        'floor_first60': (_med(fl_first) if fl_first else None),
        'floor_steady': (_med(fl_steady) if fl_steady else None),
        'speed_mean': (_med(speed_used) if speed_used else None),
        'speed_frac_15': (len([s for s in speed_used if s >= 1.49])
                          / max(1, len(speed_used)) if speed_used
                          else None),
        'chase_accepts': getattr(tr, 'chase_accepts', 0),
        'chase_runs': getattr(tr, 'chase_runs', []),
        'chase_rejects': getattr(tr, 'chase_rejects_seen', 0),
        'd_reanchors': getattr(tr, 'd_reanchors', 0),
        'ratio_tx_meas': getattr(tr, 'ratio_tx_meas', 0),
        'ratio_burst_meas': getattr(tr, 'ratio_burst_meas', 0),
    }


def report22(name, tr, dh, s60, tp60, holdsat, bursts, extra,
             line_start, line_ulen, dub, vad_stats=None,
             ratio_true=1 / RATIO22_A, total_ms=LONG_MS):
    steady = s21.report21(name, tr, dh, s60, tp60, holdsat, bursts,
                          extra, line_start, line_ulen, vad_stats,
                          ratio_true)
    kot = kotlin_vs_true(tr, extra, dub, ratio_true)
    perwin = per_window_stats(tr, total_ms)
    lag = lag_analysis(tr, dub, dh)
    print("KOTLIN-REPORTED vs TRUE (22, §2): %d bursts with slice"
          " records; TRUE end lateness (chunk-level, last chunk)"
          " median %.0f / p90(|.|) %s / max %s ms (steady-window"
          " p90(|.|) %s ms); KOTLIN-FULL (same last slice, 1.0x +"
          " anchor) median %.0f / p90(|.|) %s / max %s;"
          " KOTLIN-AT-PLAY (partial burst, what the HUD shows)"
          " median %.0f / p90(|.|) %s / max %s; TRUE err of the"
          " last pairing entry's audio (chunk-referenced, same D)"
          " median %.0f / p90(|.|) %s ms"
          % (kot['n_bursts'], _med(kot['true_end']),
             _p90(kot['true_end']), _mx(kot['true_end']),
             _p90(kot['true_end_steady']),
             _med(kot['kot_full']), _p90(kot['kot_full']),
             _mx(kot['kot_full']), _med(kot['kot_play']),
             _p90(kot['kot_play']), _mx(kot['kot_play']),
             _med(kot['kot_last_true']),
             _p90(kot['kot_last_true'])))
    print("  bias per burst (kotlin - true), decomposed on the"
          " last pairing entry (p90 = p90 of |value|, sim"
          " convention): TOTAL median %.0f / p90 %.0f / max %.0f /"
          " min %.0f ms = PURE 1.0x bias median %.0f / p90 %.0f /"
          " max %.0f / min %.0f + ANCHOR error median %.0f / p90"
          " %.0f / max %.0f / min %.0f ms (pure bias is %.0f%% /"
          " p90 %.0f%% of the burst's dub offset); anchor error"
          " over all slices median %.0f / p90 %.0f ms; AT-PLAY"
          " minus FULL median %.0f ms = the partial-burst effect;"
          " slice-level |err| p90 TRUE %s ms (steady %s ms,"
          " n=%d chunks)"
          % (_med(kot['total_bias']), _p90(kot['total_bias']),
             max(kot['total_bias']) if kot['total_bias'] else None,
             min(kot['total_bias']) if kot['total_bias'] else None,
             _med(kot['pure_bias']), _p90(kot['pure_bias']),
             max(kot['pure_bias']) if kot['pure_bias'] else None,
             min(kot['pure_bias']) if kot['pure_bias'] else None,
             _med(kot['anchor_err']), _p90(kot['anchor_err']),
             max(kot['anchor_err']) if kot['anchor_err'] else None,
             min(kot['anchor_err']) if kot['anchor_err'] else None,
             100.0 * (_med(kot['pure_frac']) or 0),
             100.0 * (_p90(kot['pure_frac']) or 0),
             _med(kot['anchor_err_all']),
             _p90(kot['anchor_err_all']),
             _med(kot['d_play_full']), _p90(kot['true_all']),
             _p90(kot['true_all_steady']), len(kot['true_all'])))
    print("PER-WINDOW (60 s): pairs %s; samples %s; tx ok %s;"
          " tx no-mk %s"
          % ([w['pairs'] for w in perwin],
             [w['samples'] for w in perwin],
             [w['tx_ok'] for w in perwin],
             [w['tx_no_mk'] for w in perwin]))
    if isinstance(tr, s21.NewTracker21):
        print("TX NO-MK REASONS (22, §3/§4): %s (a-no-group = no"
              " closed group in window; b-residual = merged-group"
              " start seconds off; c-refallback = audioAt null +"
              " marker >2500 ms)"
              % (dict(getattr(tr, 'tx_no_mk_reasons', {})),))
        print("TX GROUPS: seen %d, open at end %d"
              % (tr.tx_groups_seen,
                 len([g for g in tr.tx_groups
                      if not g.get('closed')])))
    print("LAG / 12 s (22, §2): true lag median %.0f / p90 %s /"
          " max %s ms; per-minute true-lag medians %s; chunks with"
          " true lag >12 s: %d, >20 s: %d; last feed sample (incl."
          " D-check rejects) %s ms at %s ms, D end %d ms (gap"
          " %+d ms); D 60 s %s / 300 s %s / max %d; dropped samples"
          " LARGE (>20 s) %d, SMALL (deferred) %d, dropped slices"
          " %d; ratio display %.2fx"
          % (lag['true_med'], lag['true_p90'], lag['true_max'],
             [round(x) if x is not None else None
              for x in lag['true_per_min_med']],
             lag['n_true_gt12'], lag['n_true_gt20'],
             lag['last_feed'], lag['last_feed_now'], lag['d_end'],
             lag['d_gap'] if lag['d_gap'] is not None else 0,
             lag['d_60'], lag['d_300'], lag['d_max'],
             lag['dropped_large'], lag['deferred_small'],
             lag['dropped_slices'], lag['ratio_display']))
    if isinstance(tr, NewTracker22):
        print("STRETCH (22): ratio measurements burst-successor"
              " %d, tx-ok %d; floor median %.2f (first 60 s %.2f,"
              " steady %.2f); mean speed used %.2f (%.0f%% of"
              " chunks at 1.5x); corrected endLag of last burst"
              " %d ms (kotlin-reported %d ms); valve fires %d,"
              " valve-dropped %d ms over %d chunks; chase accepts"
              " %d (runs %s), chase rejects seen %d, D-reanchors"
              " %d"
              % (lag['ratio_burst_meas'], lag['ratio_tx_meas'],
                 lag['floor_mean'] or 0, lag['floor_first60'] or 0,
                 lag['floor_steady'] or 0, lag['speed_mean'] or 0,
                 100.0 * (lag['speed_frac_15'] or 0),
                 tr._vb_end_lag, tr._vb_end_lag_kot,
                 tr.tail_valve_fires, tr.tail_dropped_ms,
                 len(tr.tail_dropped_chunks), lag['chase_accepts'],
                 [(t, m) for (t, m, n) in lag['chase_runs'][:6]],
                 lag['chase_rejects'], lag['d_reanchors']))
        if tr.tail_dropped_chunks:
            lines = sorted(set(tid for (_, tid, _) in
                               tr.tail_dropped_chunks))
            per_line = Counter()
            for (_, tid, dms) in tr.tail_dropped_chunks:
                per_line[tid] += dms
            print("  valve cut lines %s (dropped ms per line %s)"
                  % (lines[:12], dict(list(per_line.items())[:12])))
    if getattr(tr, 'seek_frame_checked', 0):
        print("SEEK / FRAME-GARBAGE (22, §4): %d bursts checked,"
              " %d frame residuals dropped as garbage (shown"
              " \"—\"), %d shown; post-seek shown residuals median"
              " %.0f / p90 %s ms (n=%d)"
              % (tr.seek_frame_checked, tr.seek_frame_dropped,
                 len(tr.frame_residuals),
                 _med(tr.frame_residuals),
                 _p90(tr.frame_residuals), len(tr.frame_residuals)))
    print()
    return steady, kot, perwin, lag


def gate22(name, tr, tp60, steady_ls, dmax, cov, gap,
           steady_sp=None, s60=0, rcov=None, rgap=None,
           end_late=None, tp_first60=None, first_sync_at=None,
           d_pinned_frac=None, bursts_seen=0, perwin=None,
           lag=None, sync_errs=None, end_late_true=None,
           sp_true=None):
    """The v1.0.22 gate: the v1.0.21 bars PLUS pairs>0 in EVERY
    60 s window, samples>=40 in every window, D pinned 0%, and
    the 12 s bar (D tracks the last sample AND no true lag ever
    exceeds 20 s). End-burst and slice-play bars are gated on
    the CHUNK-level TRUE series (render - true source wall - D),
    not on the line-start metric. Unpaired bar is 0.70 (the
    device itself is 0.67 on film A: (18+4)/33 — the old 0.35
    is unachievable on giant-turn worlds, for the device too)."""
    drop_steady = (100.0 * tr.dropped_steady_ms / tr.total_steady_ms
                   if getattr(tr, 'total_steady_ms', 0) else 0.0)
    sp_src = sp_true if sp_true else steady_sp
    sp_med = pct(sp_src, 0.5) if sp_src else 9999
    sp_p90 = _p90(sp_src) if sp_src else 9999
    el_src = end_late_true if end_late_true else end_late
    el_p90 = _p90(el_src) if el_src else 9999
    sy_max = _mx(sync_errs) if sync_errs else 9999
    sy_p90 = _p90(sync_errs) if sync_errs else 9999
    use_cov = rcov if rcov is not None else cov
    use_gap = rgap if rgap is not None else gap
    pairs_win = [w['pairs'] for w in perwin] if perwin else []
    samp_win = [w['samples'] for w in perwin] if perwin else []
    d_gap = lag['d_gap'] if lag else 99999
    true_max = lag['true_max'] if lag else 99999
    checks = {
        "lines with dub >= 90% (render-level)":
            use_cov is not None and use_cov >= 0.90,
        "max unexplained inter-render gap <= 3000 ms":
            use_gap is not None and use_gap <= 3000,
        "pairs > 0 in EVERY 60 s window":
            bool(pairs_win) and all(p > 0 for p in pairs_win),
        "samples/60s >= 40 (min over windows)":
            bool(samp_win) and min(samp_win) >= 40,
        "D max <= 8000": dmax <= 8000,
        "D pinned 0% of session":
            d_pinned_frac is not None and d_pinned_frac == 0,
        "combined burst unpaired fraction < 0.70":
            (1.0 - tr.turn_pairs / max(1, bursts_seen)) < 0.70,
        "slice-play steady |err| median < 500 (TRUE chunk)":
            sp_med < 500,
        "slice-play steady |err| p90 < 1000 (TRUE chunk)":
            sp_p90 is not None and sp_p90 < 1000,
        "per-burst END lateness p90 (TRUE chunk) < 1000":
            el_p90 is not None and el_p90 < 1000,
        "sync err (burst-start) max < 1000":
            sy_max is not None and sy_max < 1000,
        "sync err (burst-start) p90 < 1000":
            sy_p90 is not None and sy_p90 < 1000,
        "line-start steady |err| median < 1200":
            pct(steady_ls, 0.5) < 1200,
        "dropped audio steady < 5%": drop_steady < 5.0,
        "pairs in first 60 s > 0":
            tp_first60 is not None and tp_first60 > 0,
        "sync measurable in first 60 s (not '--')":
            first_sync_at is not None and first_sync_at <= 60000,
        "D tracks last sample (gap < 3000 ms)":
            d_gap is not None and d_gap < 3000,
        "no true lag ever > 20000 ms":
            true_max is not None and true_max < 20000,
    }
    print("GATE22 %s:" % name)
    for k, ok in checks.items():
        print("  %-46s %s" % (k, "PASS" if ok else "FAIL"))
    all_pass = all(checks.values())
    print("  GATE22: %s" % ("PASS" if all_pass else "FAIL"))
    print()
    return all_pass


def device_signature_check22(tag, tr, dh, s60, tp60, bursts, vs,
                             extra, vad, n_lines, lag, kot,
                             film='A'):
    """Compare a run against the 09:10 (film A) / 09:21 (film B)
    device photos."""
    if film == 'A':
        tgt = ("09:10 film A: D 5.3 s, last sample 7.8 s, samples/60s"
               " 8, pairs/60s 0, pairs first60 4, D pinned 0%,"
               " burst 33: 11/18/4 (ok/no-mk/cons), turns60 1,"
               " fed60 53820, gate 136/60 s, ratio 1.42x, end-late"
               " p90 2448 (n=9), slice-play p90 2098, sync max/p90"
               " 1714/1267, dropped large 10, stall 0")
    else:
        tgt = ("09:21 film B dense: D 5.1 s, LAST SAMPLE 12.0 s,"
               " samples/60s 25, pairs/60s 0, pairs first60 4,"
               " D pinned 0%, burst 91: 17/66/8/2, turns60 4,"
               " fed60 57940, gate 57/60 s, ratio 1.42x, end-late"
               " p90 2098 (n=15), slice-play p90 2032, sync"
               " max/p90 1750/1714, dropped LARGE 171, stall 3")
    print("device-signature check %s (%s):" % (tag, tgt))
    print("  bursts %d over %d lines; burst ok/no-mk/cons/fb"
          " %d/%d/%d/%d; samples/60s %d, pairs/60s %d, pairs"
          " first 60 s %d" % (bursts, n_lines, tr.burst_ok,
                             tr.burst_no_marker, tr.burst_consist,
                             tr.burst_fallback, s60, tp60,
                             extra.get('tp_first60', -1)))
    print("  D last %d ms, D max %d ms, D pinned %.0f%%, last"
          " sample (accepted) %.1f s, last feed sample (incl."
          " rejects) %s ms, D gap %+d ms"
          % (tr.delay, lag['d_max'],
             100.0 * extra.get('d_pinned_frac', -1),
             tr.last_sample / 1000.0, lag['last_feed'],
             lag['d_gap'] if lag['d_gap'] is not None else 0))
    print("  true lag median %.0f / p90 %s / max %s ms; >12 s:"
          " %d chunks, >20 s: %d chunks; dropped LARGE %d, SMALL"
          " %d, slices %d; stall episodes %d"
          % (lag['true_med'], lag['true_p90'], lag['true_max'],
             lag['n_true_gt12'], lag['n_true_gt20'],
             lag['dropped_large'], lag['deferred_small'],
             lag['dropped_slices'],
             getattr(tr, 'stall_episodes', -1)))
    print("  turns/60s %d, fed/60s %d ms, gate rejects/60s ~%.0f,"
          " ratio display %.2fx; TRUE end-late p90 %s ms,"
          " KOTLIN-AT-PLAY end-late p90 %s ms"
          % (vs.get('turns60', -1), vs.get('fed60', -1),
             (vs.get('start_gate_rejects', 0)
              + vs.get('gate_rejects', 0)) * 60.0 / 600.0,
             1.0 / tr.ratio(), _p90(kot['true_end']),
             _p90(kot['kot_play'])))
    print()


# ----------------------------------------------------------------
# Driver.
# ----------------------------------------------------------------
SHIPPED21 = dict(tx_on=True, level_cap=1.25, tx_hold=False,
                 stale_skip=False)
RESULTS = []


def _build_world(**w):
    frames, ls, lu = build_world22(**w)
    vad, sent, vs = s21.run_vad21(frames, start_need=4, start_tol=2,
                                  total_ms=LONG_MS)
    txin, txsrc = s21.build_tx21(frames, sent, ls, lu)
    return (frames, ls, lu, vad, sent, vs, txin, txsrc)


def do_run(label, tracker_cls, world, ratio, lag0, film='A', **kw):
    (frames, ls, lu, vad, sent, vs, txin, txsrc) = world
    dub, skipped = s21.build_dub21(frames, sent, ls, lu,
                                   ratio=ratio, lag0=lag0, speed=1.5)
    print("##### %s: %d vad events, %d VAD turns (%d lines),"
          " %d dub chunks, %d tx-in fragments #####"
          % (label, len(vad), vs['turns'], len(ls), len(dub),
             len(txin)))
    tr, dh, s60, tp60, holdsat, bursts, extra = run22(
        tracker_cls, vad, dub, [], ls, txin, txsrc, min_buf=16384,
        total_ms=LONG_MS, **kw)
    steady, kot, perwin, lag = report22(
        label, tr, dh, s60, tp60, holdsat, bursts, extra, ls, lu,
        dub, vs, 1.0 / ratio)
    ok = gate22(label, tr, tp60, steady[0],
                max(d for _, d in dh), steady[2], steady[3],
                steady[1], s60, rcov=extra.get('render_cov'),
                rgap=extra.get('render_gap'), end_late=steady[4],
                bursts_seen=bursts,
                tp_first60=extra.get('tp_first60'),
                first_sync_at=extra.get('first_sync_at'),
                d_pinned_frac=extra.get('d_pinned_frac'),
                perwin=perwin, lag=lag, sync_errs=tr.sync_errs,
                end_late_true=kot['true_end_steady'],
                sp_true=kot['true_all_steady'])
    device_signature_check22(label, tr, dh, s60, tp60, bursts, vs,
                             extra, vad, len(ls), lag, kot, film)
    pairs_win = [w['pairs'] for w in perwin]
    samp_win = [w['samples'] for w in perwin]
    drop_steady = (100.0 * tr.dropped_steady_ms
                   / max(1, tr.total_steady_ms))
    res = {'label': label, 'gate': ok,
           'end_p90_true': _p90(kot['true_end_steady']),
           'end_p90_true_full': _p90(kot['true_end']),
           'end_p90_true_lastpair': _p90(kot['kot_last_true']),
           'end_p90_kot_full': _p90(kot['kot_full']),
           'end_p90_kot_play': _p90(kot['kot_play']),
           'kot_bias_med': _med(kot['total_bias']),
           'kot_bias_p90': _p90(kot['total_bias']),
           'kot_pure_med': _med(kot['pure_bias']),
           'kot_pure_p90': _p90(kot['pure_bias']),
           'kot_pure_frac_med': _med(kot['pure_frac']),
           'kot_anchor_med': _med(kot['anchor_err']),
           'kot_anchor_p90': _p90(kot['anchor_err']),
           'anchor_all_med': _med(kot['anchor_err_all']),
           'anchor_all_p90': _p90(kot['anchor_err_all']),
           'sync_max': _mx(tr.sync_errs),
           'sync_p90': _p90(tr.sync_errs),
           'sp_p90': _p90(kot['true_all_steady']),
           'sp_med': pct(kot['true_all_steady'], 0.5)
                       if kot['true_all_steady'] else None,
           'sp_p90_full': _p90(kot['true_all']),
           'sp_med_full': pct(kot['true_all'], 0.5)
                       if kot['true_all'] else None,
           'sp_p90_linestart': _p90(steady[1]),
           'sp_med_linestart': (pct(steady[1], 0.5)
                                if steady[1] else None),
           'end_p90_linestart': _p90(steady[4]),
           'ls_med': pct(steady[0], 0.5) if steady[0] else None,
           'd_pinned': extra.get('d_pinned_frac', -1),
           'd_max': max(d for _, d in dh),
           'd_end': lag['d_end'],
           'pairs_min_win': min(pairs_win) if pairs_win else 0,
           'pairs_last_win': tp60,
           'pairs_first60': extra.get('tp_first60', -1),
           'samples_min_win': min(samp_win) if samp_win else 0,
           'samples_last': s60,
           'drop_steady': drop_steady,
           'unpaired_frac': (1.0 - tr.turn_pairs
                             / max(1, bursts)),
           'true_max': lag['true_max'],
           'true_p90': lag['true_p90'],
           'd_gap': lag['d_gap'],
           'dropped_large': lag['dropped_large'],
           'tx_ok': getattr(tr, 'tx_ok', 0),
           'tx_no_mk': getattr(tr, 'tx_no_mk', 0),
           'tx_cons': getattr(tr, 'tx_cons', 0),
           'tx_agree': getattr(tr, 'tx_agree', 0),
           'tx_reasons': dict(getattr(tr, 'tx_no_mk_reasons', {})),
           'chase_accepts': lag['chase_accepts'],
           'valve_fires': getattr(tr, 'tail_valve_fires', 0),
           'valve_dropped_ms': getattr(tr, 'tail_dropped_ms', 0),
           'seek_dropped': getattr(tr, 'seek_frame_dropped', 0),
           'seek_checked': getattr(tr, 'seek_frame_checked', 0),
           'seek_shown': len(getattr(tr, 'frame_residuals', [])),
           'ratio_tx_meas': lag['ratio_tx_meas'],
           'ratio_burst_meas': lag['ratio_burst_meas'],
           'chase_runs': lag['chase_runs']}
    res['stretch_pass'] = (
        res['end_p90_true'] is not None
        and res['end_p90_true'] < 1000
        and res['sync_max'] is not None and res['sync_max'] < 1000
        and res['sync_p90'] is not None and res['sync_p90'] < 1000
        and res['sp_p90'] is not None and res['sp_p90'] < 1000
        and res['d_pinned'] == 0
        and res['drop_steady'] < 5.0
        and res['ls_med'] is not None and res['ls_med'] < 1200
        and res['d_gap'] is not None and res['d_gap'] < 3000
        and res['true_max'] is not None and res['true_max'] < 20000
        and res['d_max'] <= 8000)
    RESULTS.append(res)
    return res


def main():
    import time
    t_start = time.time()
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("world22 film A (09:10): tonal 0.95 / music 0.95 /"
          " short 0.80 -> turns median ~53 s, turns60=1, fed60"
          " ~60 s; film B dense (09:21): tonal 0.80 / music 0.90"
          " / short 0.80 -> turns median ~14 s, turns60~3-4,"
          " fed60 ~57.9 s (96%); 10-min sessions, lag0 4600/7800,"
          " ratios 1.42/1.51, tc OFF")
    print("device targets: A: D 5.3 s, last sample 7.8 s, pairs"
          " 0/60 s, end-late p90 2448, tx 6/199, turns60 1, fed"
          " 53820, gate 136; B: D 5.1 s, LAST SAMPLE 12.0 s,"
          " pairs 0/60 s, end-late p90 2098, tx 9/437, turns60 4,"
          " fed 57940, gate 57, dropped LARGE 171")
    print()

    worldA = _build_world(**WORLD22)
    worldB = _build_world(**WORLD22_DENSE)
    for tag, w in (("film A", worldA), ("film B dense", worldB)):
        (frames, ls, lu, vad, sent, vs, txin, txsrc) = w
        lens = sorted((e - s) / 1000.0
                      for (s, e, lid, sms) in sent)
        print("WORLD %s: %d lines, %d VAD turns (median %.1f s,"
              " max %.1f s), turns60=%d, fed60=%d ms, gate"
              " rejects/60 s ~%.0f, tx-in fragments %d"
              % (tag, len(ls), vs['turns'],
                 lens[len(lens) // 2] if lens else 0,
                 lens[-1] if lens else 0, vs['turns60'],
                 vs['fed60'],
                 (vs['start_gate_rejects'] + vs['gate_rejects'])
                 * 60.0 / 600.0, len(txin)))
    print()

    # ---- 1. REPRO film A: v1.0.20 and v1.0.21-shipped. ----
    rA20_142 = do_run("REPRO-A v1.0.20 r1.42 lag4.6",
                      s20.NewTracker20, worldA, RATIO22_A, LAG22_A,
                      film='A')
    rA20_151 = do_run("REPRO-A v1.0.20 r1.51 lag4.6",
                      s20.NewTracker20, worldA, RATIO22_B, LAG22_A,
                      film='A')
    rA20_78 = do_run("REPRO-A v1.0.20 r1.42 lag7.8",
                     s20.NewTracker20, worldA, RATIO22_A, LAG22_B,
                     film='A')
    rA21_142 = do_run("REPRO-A v1.0.21-shipped r1.42",
                      s21.NewTracker21, worldA, RATIO22_A, LAG22_A,
                      film='A', **SHIPPED21)
    rA21_151 = do_run("REPRO-A v1.0.21-shipped r1.51",
                      s21.NewTracker21, worldA, RATIO22_B, LAG22_A,
                      film='A', **SHIPPED21)

    # ---- 2. Stretch ladder, film A r1.42 (stop at first pass).
    # V-a is run TWICE: first with the shipped ratio() (burst-
    # successor chain is starved -> floor ~1.03, documents the
    # dependency), then with RATIO_TX (tx-ok line positions feed
    # the ratio) — the real V-a test.
    stretch_kw = {}
    rVa = do_run("V-a ratio-floor (shipped ratio) r1.42",
                 NewTracker22, worldA, RATIO22_A, LAG22_A, film='A',
                 **SHIPPED21, ratio_floor=True)
    if rVa['stretch_pass']:
        stretch_kw = dict(ratio_floor=True)
        print("V-a (shipped ratio) PASSES the stretch bars on"
              " film A r1.42 — V-b/V-c skipped here (ladder"
              " stops).")
    else:
        rVaTx = do_run("V-a ratio-floor + RATIO_TX r1.42",
                       NewTracker22, worldA, RATIO22_A, LAG22_A,
                       film='A', **SHIPPED21, ratio_floor=True,
                       ratio_tx=True)
        if rVaTx['stretch_pass']:
            stretch_kw = dict(ratio_floor=True, ratio_tx=True)
            print("V-a+RATIO_TX PASSES the stretch bars — V-b/V-c"
                  " skipped.")
        else:
            rVb = do_run("V-b ratio-floor+catchup+RATIO_TX r1.42",
                         NewTracker22, worldA, RATIO22_A, LAG22_A,
                         film='A', **SHIPPED21, ratio_floor=True,
                         catchup=True, ratio_tx=True)
            if rVb['stretch_pass']:
                stretch_kw = dict(ratio_floor=True, catchup=True,
                                  ratio_tx=True)
                print("V-b PASSES the stretch bars — V-c skipped.")
            else:
                rVc = do_run("V-c +tail-valve r1.42", NewTracker22,
                             worldA, RATIO22_A, LAG22_A, film='A',
                             **SHIPPED21, ratio_floor=True,
                             catchup=True, tail_valve=True,
                             ratio_tx=True)
                stretch_kw = dict(ratio_floor=True, catchup=True,
                                  tail_valve=True, ratio_tx=True)
                print("V-c stretch_pass=%s" % rVc['stretch_pass'])
    print()

    # ---- 3. Tx ladder on the stretch winner, film A r1.42. ----
    base_kw = dict(SHIPPED21)
    base_kw.update(stretch_kw)
    rTa = do_run("W+T-a sub-split-300 r1.42", NewTracker22, worldA,
                 RATIO22_A, LAG22_A, film='A', **base_kw,
                 tx_subsplit=True)
    rTaTb = do_run("W+T-a+T-b slices-120 r1.42", NewTracker22,
                   worldA, RATIO22_A, LAG22_A, film='A', **base_kw,
                   tx_subsplit=True, max_slices=120)
    rTd = do_run("W+T-d audioAt-extrap r1.42", NewTracker22, worldA,
                 RATIO22_A, LAG22_A, film='A', **base_kw,
                 tx_audioat_extrap=True)
    rTaTbTd = do_run("W+T-a+T-b+T-d r1.42", NewTracker22, worldA,
                     RATIO22_A, LAG22_A, film='A', **base_kw,
                     tx_subsplit=True, max_slices=120,
                     tx_audioat_extrap=True)
    # Tx winner = most pairs in the worst window, then most tx_ok.
    tx_cands = [(rTa, dict(tx_subsplit=True)),
                (rTaTb, dict(tx_subsplit=True, max_slices=120)),
                (rTd, dict(tx_audioat_extrap=True)),
                (rTaTbTd, dict(tx_subsplit=True, max_slices=120,
                               tx_audioat_extrap=True))]
    tx_cands.sort(key=lambda rc: (rc[0]['pairs_min_win'],
                                  rc[0]['samples_min_win'],
                                  rc[0]['tx_ok']), reverse=True)
    tx_best, tx_kw = tx_cands[0]
    print("TX WINNER on film A r1.42: %s (pairs min-window %d,"
          " samples min-window %d, tx ok %d / no-mk %d, reasons"
          " %s)" % (tx_best['label'], tx_best['pairs_min_win'],
                    tx_best['samples_min_win'], tx_best['tx_ok'],
                    tx_best['tx_no_mk'], tx_best['tx_reasons']))
    print()
    # T-c only if the tx winner still has an empty window.
    if tx_best['pairs_min_win'] == 0:
        rTc = do_run("W+T-a+T-b+T-c force-close-6s r1.42",
                     NewTracker22, worldA, RATIO22_A, LAG22_A,
                     film='A', **base_kw, tx_subsplit=True,
                     max_slices=120, tx_force_close_ms=6000)
        if rTc['pairs_min_win'] > tx_best['pairs_min_win']:
            tx_best, tx_kw = rTc, dict(
                tx_subsplit=True, max_slices=120,
                tx_force_close_ms=6000)
            print("T-c IMPROVES the worst window: pairs"
                  " min-window %d" % rTc['pairs_min_win'])
        else:
            print("T-c does NOT improve the worst window"
                  " (still %d)" % rTc['pairs_min_win'])

    winner_kw = dict(base_kw)
    winner_kw.update(tx_kw)

    # ---- 4. Dense film B: the 12 s question, layer by layer. --
    rB21 = do_run("REPRO-B v1.0.21-shipped dense r1.42",
                  s21.NewTracker21, worldB, RATIO22_A, LAG22_A,
                  film='B', **SHIPPED21)
    rBVa = do_run("D V-a+RATIO_TX dense r1.42", NewTracker22,
                  worldB, RATIO22_A, LAG22_A, film='B', **SHIPPED21,
                  ratio_floor=True, ratio_tx=True)
    rBChase = do_run("D CHASE-only dense r1.42", NewTracker22,
                     worldB, RATIO22_A, LAG22_A, film='B',
                     **SHIPPED21, chase=True)
    rBVaChase = do_run("D V-a+CHASE dense r1.42", NewTracker22,
                       worldB, RATIO22_A, LAG22_A, film='B',
                       **SHIPPED21, ratio_floor=True, ratio_tx=True,
                       chase=True)
    # If the stretch winner needed catch-up/valve, test it dense.
    if stretch_kw.get('catchup'):
        rBVb = do_run("D V-b dense r1.42", NewTracker22, worldB,
                      RATIO22_A, LAG22_A, film='B', **SHIPPED21,
                      ratio_floor=True, catchup=True,
                      ratio_tx=True)
    if stretch_kw.get('tail_valve'):
        rBVc = do_run("D V-c dense r1.42", NewTracker22, worldB,
                      RATIO22_A, LAG22_A, film='B', **SHIPPED21,
                      ratio_floor=True, catchup=True,
                      tail_valve=True, ratio_tx=True)
    # Winner combo on dense (both ratios).
    rBW142 = do_run("D WINNER dense r1.42", NewTracker22, worldB,
                    RATIO22_A, LAG22_A, film='B', **winner_kw)
    rBW151 = do_run("D WINNER dense r1.51", NewTracker22, worldB,
                    RATIO22_B, LAG22_A, film='B', **winner_kw)
    # Winner combo on film A at 1.51 + lag 7.8 (robustness).
    rAW151 = do_run("WINNER film A r1.51", NewTracker22, worldA,
                    RATIO22_B, LAG22_A, film='A', **winner_kw)
    rAW78 = do_run("WINNER film A r1.42 lag7.8", NewTracker22,
                   worldA, RATIO22_A, LAG22_B, film='A',
                   **winner_kw)
    # Seek / frame-garbage test on the winner (film A).
    rSeek = do_run("WINNER+SEEK film A r1.42", NewTracker22, worldA,
                   RATIO22_A, LAG22_A, film='A', **winner_kw,
                   seek_at_ms=300000)
    print("SEEK TEST: %d bursts checked around the seek, %d frame"
          " residuals dropped as garbage (shown \"—\"), %d shown"
          % (rSeek['seek_checked'], rSeek['seek_dropped'],
             rSeek['seek_shown']))

    # ---- 4b. ORACLE diagnostic: the V-a floor fed with the KNOWN
    # true ratio (1.42 / 1.51) instead of the measured ratio().
    # This separates 'does the floor mechanism work' from 'is the
    # ratio measurement right' — the measured ratio only ever
    # reaches ~1.09-1.27 (see §2), so the floor never reaches
    # 1.42 in the runs above. If the oracle passes where the
    # measured floor failed, the blocker is the measurement,
    # not the stretch mechanism.
    rO1 = do_run("ORACLE V-a floor-only film A r1.42", NewTracker22,
                 worldA, RATIO22_A, LAG22_A, film='A', **SHIPPED21,
                 ratio_floor=True, ratio_oracle=1.0 / RATIO22_A)
    rO1b = do_run("ORACLE V-b film A r1.42", NewTracker22, worldA,
                  RATIO22_A, LAG22_A, film='A', **SHIPPED21,
                  ratio_floor=True, catchup=True,
                  ratio_oracle=1.0 / RATIO22_A)
    oracle_kw = dict(winner_kw)
    rO2 = do_run("ORACLE V-a+TXwin film A r1.42", NewTracker22,
                 worldA, RATIO22_A, LAG22_A, film='A', **oracle_kw,
                 ratio_oracle=1.0 / RATIO22_A)
    rO3 = do_run("ORACLE V-a+TXwin dense r1.42", NewTracker22,
                 worldB, RATIO22_A, LAG22_A, film='B', **oracle_kw,
                 ratio_oracle=1.0 / RATIO22_A)
    rO4 = do_run("ORACLE V-a+TXwin film A r1.51", NewTracker22,
                 worldA, RATIO22_B, LAG22_A, film='A', **oracle_kw,
                 ratio_oracle=1.0 / RATIO22_B)
    rO5 = do_run("ORACLE V-a+TXwin film A r1.42 lag7.8",
                 NewTracker22, worldA, RATIO22_A, LAG22_B, film='A',
                 **oracle_kw, ratio_oracle=1.0 / RATIO22_A)
    rO6 = do_run("ORACLE V-a+TXwin dense r1.51", NewTracker22,
                 worldB, RATIO22_B, LAG22_A, film='B', **oracle_kw,
                 ratio_oracle=1.0 / RATIO22_B)
    print("ORACLE SUMMARY: V-a floor-only film A stretch_pass=%s"
          " (end-p90 %.0f, sync p90 %.0f); V-b film A"
          " stretch_pass=%s (end-p90 %.0f, sync p90 %.0f);"
          " V-a+TXwin film A gate=%s (end-p90 %.0f); dense gate=%s"
          " (end-p90 %.0f); film A r1.51 gate=%s (end-p90 %.0f);"
          " lag7.8 gate=%s (end-p90 %.0f); dense r1.51 gate=%s"
          " (end-p90 %.0f)"
          % (rO1['stretch_pass'], rO1['end_p90_true'],
             rO1['sync_p90'], rO1b['stretch_pass'],
             rO1b['end_p90_true'], rO1b['sync_p90'], rO2['gate'],
             rO2['end_p90_true'], rO3['gate'],
             rO3['end_p90_true'], rO4['gate'],
             rO4['end_p90_true'], rO5['gate'],
             rO5['end_p90_true'], rO6['gate'],
             rO6['end_p90_true']))
    print()

    # ---- 5. Regressions: v6 + sim1018, winner flags. ----
    print("##### regression: v6 world (sim1017) #####")
    vad6, dub6, completes6, line_start6 = s20.s17.build_world()
    txin6, txsrc6 = s21.build_tx21([], [], {}, {})
    old6, dh6, s606, tp606, holdsat6, bursts6, ex6 = run22(
        s20.NewTracker20, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384)
    steady_old6 = s21.report21("OLD (v1.0.20), v6 world", old6, dh6,
                               s606, tp606, holdsat6, bursts6, ex6,
                               line_start6, {})
    new6, dh6b, s60n6, tp60n6, holdsatn6, burstsn6, ex6b = run22(
        NewTracker22, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384, **winner_kw)
    steady_new6 = s21.report21("NEW (v1.0.22 winner), v6 world",
                               new6, dh6b, s60n6, tp60n6, holdsatn6,
                               burstsn6, ex6b, line_start6, {})
    fh_old6 = old6.line_start_fresh_errs
    fh_new6 = new6.line_start_fresh_errs
    ls_old6 = steady_old6[0][:len(steady_old6[0]) // 2]
    ls_new6 = steady_new6[0][:len(steady_new6[0]) // 2]
    print("REGRESSION GATE (v6 world, WITHIN-CAP early half):"
          " fresh-hold |err| median OLD %.0f -> NEW %.0f"
          " (need NEW < 300); actual line-start steady median"
          " OLD %.0f -> NEW %.0f (need NEW <= OLD);"
          " pairs/60s OLD %d -> NEW %d (need NEW >= OLD)"
          % (pct(fh_old6[:len(fh_old6) // 2], 0.5),
             pct(fh_new6[:len(fh_new6) // 2], 0.5),
             pct(ls_old6, 0.5), pct(ls_new6, 0.5),
             tp606, tp60n6))
    reg6 = (pct(fh_new6[:len(fh_new6) // 2], 0.5) < 300
            and pct(ls_new6, 0.5) <= pct(ls_old6, 0.5)
            and tp60n6 >= tp606)
    print("REGRESSION (v6, within-cap): %s"
          % ("PASS" if reg6 else "FAIL"))
    print()

    print("##### regression: sim1018 device world (tc ON, no music)"
          " #####")
    vad18, dub18, completes18, line_start18, line_counted18 = \
        s18.build_world_device(tc_on=True, speed=2.5)
    old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = run22(
        s19.NewTracker19, vad18, dub18, completes18, line_start18,
        txin6, txsrc6, min_buf=4096, line_counted=line_counted18)
    steady_old18 = s21.report21("OLD (v1.0.19), sim1018 world, tc ON",
                                old18, dh18, s6018, tp6018, holdsat18,
                                bursts18, ex18, line_start18, {})
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        run22(NewTracker22, vad18, dub18, completes18,
              line_start18, txin6, txsrc6, min_buf=4096,
              line_counted=line_counted18, **winner_kw)
    steady_new18 = s21.report21("NEW (v1.0.22 winner), sim1018 world,"
                                " tc ON", new18, dh18b, s60n18,
                                tp60n18, holdsatn18, burstsn18, ex18b,
                                line_start18, {})
    drop_old18 = (old18.dropped_steady_ms /
                  max(1, old18.total_steady_ms))
    drop_new18 = (new18.dropped_steady_ms /
                  max(1, new18.total_steady_ms))
    reg18 = (tp60n18 >= tp6018
             and pct(steady_new18[0], 0.5)
             <= pct(steady_old18[0], 0.5)
             and drop_new18 <= drop_old18
             and new18.dropped_slices <= old18.dropped_slices + 2)
    print("REGRESSION (sim1018 world, re-scoped): pairs/60s OLD"
          " %d -> NEW %d; line-start steady median OLD %.0f ->"
          " NEW %.0f (need NEW <= OLD); dropped audio steady"
          " OLD %.1f%% -> NEW %.1f%% (need NEW <= OLD);"
          " dropped slices OLD %d -> NEW %d: %s"
          % (tp6018, tp60n18, pct(steady_old18[0], 0.5),
             pct(steady_new18[0], 0.5), drop_old18 * 100,
             drop_new18 * 100, old18.dropped_slices,
             new18.dropped_slices, "PASS" if reg18 else "FAIL"))
    print()

    # ---- 6. Summary table. ----
    print("=" * 100)
    print("SUMMARY (10-min sessions; end-p90 = TRUE render, steady window; p90 = p90 of |value|):")
    print("%-38s %7s %7s %7s %7s %6s %6s %5s %5s %5s %6s" %
          ("run", "endp90", "kotPl", "syncMx", "syncP90", "Dpin%",
           "prMin", "smMin", "drop%", "lagMx", "Dgap"))
    for r in RESULTS:
        print("%-38s %7s %7s %7s %7s %6.0f %6d %5d %5.1f %6s %6s"
              % (r['label'][:38], r['end_p90_true'],
                 r['end_p90_kot_play'], r['sync_max'],
                 r['sync_p90'], 100.0 * r['d_pinned'],
                 r['pairs_min_win'], r['samples_min_win'],
                 r['drop_steady'], r['true_max'], r['d_gap']))
    print()
    print("WINNER kwargs: %s" % (winner_kw,))
    print("TX winner kwargs: %s; stretch kwargs: %s"
          % (tx_kw, stretch_kw))
    print("REGRESSIONS: v6 %s, sim1018 %s"
          % ("PASS" if reg6 else "FAIL",
             "PASS" if reg18 else "FAIL"))
    print("TOTAL runtime %.1f s" % (time.time() - t_start))


if __name__ == '__main__':
    main()
