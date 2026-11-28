#!/usr/bin/env python3
"""sim1026.py — DubLive v1.0.22 ROUND 5: recalibrate the sim to the
CHOPPY content regime seen on the real device (28/11 12:00, 18dj.tv
ep 2/5, mid-session, 0 Live errors) and ask what the sim says about
the device-vs-sim gap. sim1025.py untouched; this file imports it
as s25 and reuses NewTracker25 (Sa2+Sb3, the v1.0.22 winner) plus
the old worlds for regression. No Kotlin touched, no APK built.

DEVICE 12:00 (verbatim diagnosis, HUD snapshot, mid-session):
  D 3.3 s, last sample 4.5 s (0 s ago), 86 samples/60 s, pairs
  29/60 s, wait 0 slices, hold 1.0 s, VAD speaking, dropped 26
  slices / 0 samples (small 14 deferred / large 0), valve 6500 ms,
  frame offset -650 ms, play ~175 ms (mean 374 ms, wait 282 ms),
  voice/source 1.47x, frame +729 ms, burst 166 ok 90 / no-mk 42 /
  ab 11 / cons 23 / fb 6, splits 1, markers 12 (newest 987 ms ago),
  split trials old 89 / new 1 / got 1, stall 1, speed 1.50x,
  sync err max 2518 / p90 1660 (n=90), slice-play max 4963 /
  p90 3446 (n=251), end-burst max 4941 / p90 3446 (n=135),
  D pinned 0%, pairs first-60 s 19, first-sync 5 s, tx ok 67 /
  no-mk 34 / cons 17 / agree 36, asr 518 ms (n=25), D-picks 65,
  Sb 3 trials / 0 adopts, VAD turns 35/60 s, speech 32880 ms/60 s,
  gate rejects 157 frames/60 s, dropped voice chunks 0.

SIM1025 PROMISE (winner Sa2+Sb3+V2000cap500, GATE25 ex-cold 4/4):
  end-burst p90 TRUE 252-726 ms, pairs 6-12/60 s, samples
  87-106/60 s, dropped audio 0.3-1.0%. Device: end-burst p90
  3446 ms (5-13x), pairs 29/60 s (BETTER than sim), samples
  86/60 s (matches sim), speed pinned 1.5x, valve 6500 ms.

THE WORLD sim1025 MODELS (read sim1022.build_world22 +
sim1021.build_dub21 / run_vad21 before trusting anything below):
  * Lines: speech 1500-3000 ms, pauses short 100-400 ms (80%) or
    long 1500-3500 ms (20%). Pauses are MUSIC-filled (music_p
    0.90-0.95, tonal level 300-800 rms, gate pass 0.40), so the
    VAD turn does NOT end at a pause: a turn ends only after
    300 ms of gate-false frames or 700 ms below the end threshold.
    Result: film A = 10 VAD turns / 10 min, median turn 53.2 s
    (max 125.2 s), turns60 = 1, fed60 = 60040 ms (VAD-fed speech
    INCLUDING the music pauses: sent-to-Live 129% of true speech,
    true speech 457892 ms / 600 s = 76% duty). Dense = 32 turns,
    median 14.1 s, turns60 = 3, fed60 = 57800 ms (96% duty).
  * Dub audio: generated PER LINE, serialised, dub_dur =
    fed_speech * ratio (1.42 / 1.51). Chunks of 100 ms arrive
    SPREAD OUT at generation speed 1.5: arrival = speak_start +
    u/1.5 (+/-25 ms jitter), i.e. 1 s of dub audio arrives in
    0.67 s of wall time, a STEADY stream roughly in step with
    real time, 200-400 ms inter-line gap. NOT a burst/clump:
    the whole line's audio never lands at once.
  * VAD markers: one marker per VAD turn start (the turn start
    is the anchor every slice sample uses). A mid-turn line's
    slice sample therefore measures burstArrival - turnStart,
    which includes the speech elapsed inside the turn before
    that line — the anchor error sim1025 documents (P1: first
    pair sample bias +2268..+2329 ms = the line-1 start offset).
  * tx fragments: 1-3 per line, first at lineStart + U(300,700)
    + asrLag ~ N(600,200) ms; groups close at >=300 ms gap
    (anchor) / >=800 ms (seed sentences). 5% of lines are
    transcribed but never translated (silent dub) — their tx
    fragments still arrive.
  * Pipeline (TrackSim19, minBuf 16 KB): measured pipeline
    (what the tracker eats) median ~52 ms, TRUE pipeline
    (render - due) median ~166-250 ms, p90 ~330-470 ms.
    Player drains at target_speed audio-ms per wall-ms;
    busy_until serialises chunks; a chunk >700 ms overdue
    (STALE_MS) is dropped.
  * Stretch: ratio floor Rf1 (pause-subtracted tx-ok ratio,
    1/ratio clamped [1.0, 1.5]) + catch-up gain
    (1 + endLag/5000) + level/error-driven _stretch_update
    (MAX_STRETCH 1.5). Winner film A r1.42: floor median 1.34,
    mean speed used 1.36, only 14% of chunks at 1.5x.
  * D: seed = loop seed (firstDubArrival - firstMarkerWall,
    err +2506 ms) corrected once by Sa2 (err ~-433 ms);
    then D follows accepted slice/tx samples via EMA
    (D = lag + dynamic margin ~1000 ms: winner D end 5486 vs
    lag 4507, |D-lag| +979 ms). D-check rejects slice samples
    >1500 ms from the cluster ref; Rb BLOCKS tx anchors with
    lat_tx > dlag + 600 ms once established — a one-sided
    ratchet: a ref that sits LOW can never be corrected upward
    by tx, because the correct (higher) samples are blocked
    as "+1-line errors". Rc2 ref = centre of the densest
    1200 ms cluster of accepted boundary samples + seed
    candidates. Sb (referee) fires 0 trials in all 4 winner
    worlds.
  * Valve V2000cap500: at the next burst boundary after a
    burst plays with corrected end-lag >2000 ms AND
    target_speed >= 1.49, drop (endLag - 1500) ms of tail
    audio, capped 500 ms per fire. Winner film A: 5 fires,
    2500 ms total over 10 min.
  * End-burst lateness (TRUE, chunk level): last chunk of a
    burst, err = (render - true_src_wall) - D_at_play, where
    true_src_wall = arrival - true_lag (the dub generator's
    own mapping). p90 is p90 of |value| (sim convention).
    "Steady" = renders outside the 180-300 s continuous-
    stretch window (it still INCLUDES the first 60 s cold
    start); diag26 below additionally reports t > 60 s.

WHAT sim1025 LACKS vs the 12:00 device (the gaps this file
tests):
  G1. NO choppy regime: sim turns are 14-53 s of near-
     continuous speech (76-96% duty); the device has 35
     turns/60 s, speech 32.9 s/60 s (55% duty), ~0.94 s of
     speech per turn, ~0.77 s of real silence between turns.
  G2. Dub arrival is a steady 1.5x stream in sim. On the
     device the dub engine (Gemini Live / Groq Whisper ->
     Gemini translate -> Google TTS) plausibly delivers a
     whole sentence's audio as one finished file, i.e. the
     line's audio lands in a CLUMP, not spread over
     dub_dur/1.5 wall. The sim has no arrival-burst knob
     except build_dub21's `speed` (tested here as 3.0/10).
  G3. Pipeline: sim measured pipeline median ~52 ms / true
     ~170 ms; device reports play ~175 ms (mean 374 ms,
     wait 282 ms) — roughly 3x the sim's measured pipeline
     and a much heavier tail. The sim's pipeline EMA/margin
     therefore underestimates the device's hold subtraction.
  G4. D BELOW lag: sim D always ends ABOVE the true lag
     (+616..+979 ms). Device D (3.3 s) sits ~1.2 s BELOW the
     last sample (4.5 s). A D deficit inflates EVERY
     D-subtracted metric (sync err, slice-play, end-burst)
     by the same ~1.2 s — the device's end-burst p90 3446
     is suspiciously close to sync p90 1660 + ~1.8 s of
     within-burst growth, and slice-play p90 == end-burst
     p90 (3446) says the lateness peaks at the burst end.
  G5. Sb: 0 trials in sim vs 3 trials / 0 adopts on the
     device — the device's slice medians keep deviating
     >1500 ms from the ref (consistent with G4: samples
     ~4.5 s vs ref ~3.3 s) yet adoption never happens.
  G6. Valve: sim 2500 ms / 10 min (5 fires); device
     6500 ms in ONE 60 s window (~13 cap-fires) — the valve
     fires almost every burst and the lag regrows anyway.
  G7. Pairs: device 29/60 s vs sim winner 6-12/60 s — the
     device pairs MORE, not fewer. (Short turns: every
     burst is a turn start, so every burst can pair; in
     sim, mid-turn bursts mostly cannot.)

CHOPPY WORLD (this file): lines 600-1300 ms (mean ~0.95 s),
pauses 400-1200 ms of TRUE SILENCE (music_p=0.0, so every
pause >=300 ms ends the VAD turn), ratio 1.47, lag0 4500 ms.
Targets from the device: ~35 turns/60 s, speech ~32.9 s/60 s
(55% duty), ~0.94 s speech/turn. Known remaining gap: gate
rejects ~0/60 s in sim vs 157 on the device (real pauses
carry music/noise energy; the sim cannot produce gate
rejects without music filling the pauses, which would merge
the turns again — documented, not hidden).

DIAGNOSTIC VARIANTS (all on the choppy world, winner logic):
  C0 REPRO choppy: winner as shipped (arrival speed 1.5).
  C1 cap2.0: stretch ceiling 1.5 -> 2.0 (ratio floor +
     _stretch_update + note_lateness). Tests the drain-rate
     deficit story: at ratio 1.47 the 1.5x ceiling leaves
     only ~2% headroom over the 1.47x inflow.
  C2 seed-true: Sa2 forced to the TRUE lag (seed err 0).
     Tests whether a perfect start STAYS perfect in the
     choppy regime, i.e. whether D-below-lag is a steady-
     state attractor or a bootstrap accident.
  C3 valveOFF: tail valve disabled. Tests how much of the
     choppy end-burst is (not) being saved by the valve.
  C4 arrival 3.0x: dub chunks arrive at 3x (clumpier).
  C5 arrival 10x: dub chunks arrive ~all at once per line
     (whole-sentence TTS file). Tests G2 directly.
  C6 cap2.0 + arrival 3.0x: combined, only if C1/C4 move.

ADDENDUM 28/11 12:11 — USER VERDICT (recorded in MEMORY.md):
  "Giong bi meo khi bat kip" — sustained 1.5x stretch DISTORTS
  the voice. v1.0.21 ran at 1.25x and the user did NOT report
  distortion. The distortion threshold sits between 1.25x and
  1.5x sustained. CONSEQUENCES FOR THIS FILE:
  * C1/C6 (cap 2.0x) are MECHANISM PROOF ONLY: they show the
    backlog drains faster with a higher ceiling. They must
    NEVER be recommended as a ship candidate for v1.0.23 —
    1.5x already distorts, 1.75-2.0x can only be worse.
  * The ship direction to EVALUATE BY SIM instead:
    (a) CAP stretch ~1.25x (the v1.0.21 level the user did
    not report as distorted) + tail valve handles the
    remaining lag — measure end-burst p90 + total valve ms +
    dropped-audio % at the 1.25x cap. NOTE: the shipped
    valve arms ONLY when target_speed >= 1.49 (Kotlin
    onDubBurst: targetSpeed >= MAX_STRETCH_SPEED - 0.01),
    so capping stretch at 1.25x DISABLES the tail valve
    unless the speed gate is removed (sim flag
    VALVE_NO_SPEED_GATE; Kotlin change required). Variants
    K1/A1 (gate as-is, valve effectively dead) vs K2/A2
    (gate removed) separate those two facts.
    (b) REDUCE the catch-up need: a more accurate seed/D
    (D starting at the true lag 4.5 s instead of the
    device's 3.3 s; Sa2 seed correct) — measure how much
    accumulated lag remains when no strong stretch is
    needed. (a+b) = cap 1.25x + seed-true.
    Compare (a)/(b)/(a+b) vs the 1.5x baseline on BOTH
    worlds (long-turn film A/dense AND choppy).
  * DRAIN-RATE CHECK (control-loop thread, to reproduce in
    sim): at the 1.5x cap vs the required 1.47x ratio the
    headroom is only ~2% -> net drain ~1.76 s per minute
    of dense speech, ~100 s to drain a 3 s backlog; the
    catch-up term (1 + endLag/5000) saturates at endLag
    ~102 ms (1.47 * (1 + 102/5000) = 1.5). diag26 now
    reports the sim's own dense-minute net drain
    (60*mean_speed - 60*ratio) and the per-minute
    end-burst medians, so a backlog that grows minute by
    minute at the 1.25x cap is visible directly. If the
    sim drains faster than ~1.76 s/min at 1.5x dense, its
    arrival/drain model is wrong vs the device.
"""
import math
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1025 as s25
import sim1024 as s24
import sim1023 as s23
import sim1022 as s22
import sim1021 as s21
import sim1020 as s20
from sim1020 import pct

LONG_MS = s22.LONG_MS
RESULTS26 = []
_DUB26 = {}   # last build_dub21 output, stashed by the wrapper


# ----------------------------------------------------------------
# Choppy world construction (copy of s22.build_world22 with the
# line/pause knobs exposed; VAD + tx reuse s21 verbatim).
# ----------------------------------------------------------------
def build_world26(seed=20261128, ulen_lo=600, ulen_hi=1300,
                  pause_lo=400, pause_hi=1200, music_p=0.0,
                  tonal_p=0.95, soft_frac=0.8, total_ms=LONG_MS,
                  start_need=4):
    """Lines ulen_lo..ulen_hi ms, pauses pause_lo..pause_hi ms of
    silence (music_p=0) or music. Returns (frames, line_start,
    line_ulen). start_need is passed through to run_vad21 by
    _build_world26 (default 4, as sim1022)."""
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
        ulen = rng.randint(ulen_lo, ulen_hi)
        pause = rng.randint(pause_lo, pause_hi)
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


def _build_world26(start_need=4, **kw):
    frames, ls, lu = build_world26(**kw)
    vad, sent, vs = s21.run_vad21(frames, start_need=start_need,
                                  start_tol=2, total_ms=LONG_MS)
    txin, txsrc = s21.build_tx21(frames, sent, ls, lu)
    return (frames, ls, lu, vad, sent, vs, txin, txsrc)


def world_stats26(tag, world):
    (frames, ls, lu, vad, sent, vs, txin, txsrc) = world
    lens = sorted((e - s) / 1000.0 for (s, e, lid, sms) in sent)
    speech_ms = sum(lu.values())
    print("WORLD26 %s: %d lines, %d VAD turns (median %.2f s,"
          " max %.2f s), turns60=%d, fed60=%d ms, speech"
          " %d ms/600 s (%.0f%% duty), speech/turn %.2f s,"
          " gate rejects/60 s ~%.0f, tx-in fragments %d"
          % (tag, len(ls), vs['turns'],
             lens[len(lens) // 2] if lens else 0,
             lens[-1] if lens else 0, vs['turns60'], vs['fed60'],
             speech_ms, 100.0 * speech_ms / LONG_MS,
             speech_ms / max(1, vs['turns']) / 1000.0,
             (vs['start_gate_rejects'] + vs['gate_rejects'])
             * 60.0 / 600.0, len(txin)))


# ----------------------------------------------------------------
# Tracker variants (subclasses of the v1.0.22 winner).
# ----------------------------------------------------------------
class NewTracker26Cap20(s25.NewTracker25):
    """Stretch ceiling 1.5 -> 2.0 (diagnostic C1/C6)."""
    CAP20 = 2.0

    def _ratio_speed(self):
        if self.RATIO_ORACLE and self._ratio_oracle:
            return max(1.0, min(self.CAP20, 1.0 / self._ratio_oracle))
        return max(1.0, min(self.CAP20, 1.0 / self.ratio()))

    def _ratio_floor(self, now):
        if not self.RATIO_FLOOR:
            return 1.0
        if self.last_play_wall and now - self.last_play_wall > 5000:
            return 1.0
        f = self._ratio_speed()
        if self.CATCHUP and self._vb_end_lag > 0:
            f = f * (1.0 + self._vb_end_lag / self.CATCHUP_GAIN_MS)
        return max(1.0, min(self.CAP20, f))

    def note_lateness(self, late_ms, now):
        if late_ms > 200 and self.target_speed < self.CAP20:
            self.target_speed = self.CAP20
            self.level_raise_at = now


class NewTracker26SeedTrue(s25.NewTracker25):
    """Sa2 forced to the TRUE lag (diagnostic C2): after the
    normal Sa2 correction, overwrite seed/D/hold with the true
    lag so the run starts exactly right."""

    def _sa_correct(self, slice_lag, now, true_lag):
        super()._sa_correct(slice_lag, now, true_lag)
        self.seed_corr = true_lag
        self.seed_corr_err = 0
        self.seed_lag = true_lag
        self.ema.seed(true_lag)
        nd = max(3000, min(true_lag + self.dynamic_margin(), 8000))
        self._set_delay(nd)
        self.hold = max(0, nd - true_lag - self.pipeline_ms())
        if self._sa_trace is not None:
            self._sa_trace['mode'] = 'Sa2-forced-true'
            self._sa_trace['seed'] = true_lag
            self._sa_trace['seed_err'] = 0


class NewTracker26Cap125(s25.NewTracker25):
    """Stretch ceiling 1.5 -> 1.25 (direction (a): the v1.0.21
    level the user did NOT report as distorted). NOTE: at this
    cap the shipped tail valve NEVER arms, because arming
    requires target_speed >= 1.49 (sim s22 / Kotlin onDubBurst)
    — see NewTracker26Cap125Valve for the gate-removed twin.
    Also note the catch-up term is dead at this cap: the floor
    is min(1.25, 1.47 * (1 + endLag/5000)) = 1.25 from
    endLag = 0, so (1 + endLag/5000) contributes nothing."""
    CAP125 = 1.25

    def _ratio_speed(self):
        if self.RATIO_ORACLE and self._ratio_oracle:
            return max(1.0, min(self.CAP125, 1.0 / self._ratio_oracle))
        return max(1.0, min(self.CAP125, 1.0 / self.ratio()))

    def _ratio_floor(self, now):
        if not self.RATIO_FLOOR:
            return 1.0
        if self.last_play_wall and now - self.last_play_wall > 5000:
            return 1.0
        f = self._ratio_speed()
        if self.CATCHUP and self._vb_end_lag > 0:
            f = f * (1.0 + self._vb_end_lag / self.CATCHUP_GAIN_MS)
        return max(1.0, min(self.CAP125, f))

    def _stretch_update(self, now):
        super()._stretch_update(now)
        if self.target_speed > self.CAP125:
            self.target_speed = self.CAP125

    def note_lateness(self, late_ms, now):
        if late_ms > 200 and self.target_speed < self.CAP125:
            self.target_speed = self.CAP125
            self.level_raise_at = now


class NewTracker26Cap125Valve(NewTracker26Cap125):
    """Cap 1.25x + tail valve with the speed gate REMOVED
    (VALVE_NO_SPEED_GATE: arm on endLag > thresh alone).
    This is the (a) variant that can actually use the valve
    at the low cap — it needs a Kotlin change (drop the
    `targetSpeed >= MAX_STRETCH_SPEED - 0.01` condition in
    onDubBurst), not just a constant change."""
    VALVE_NO_SPEED_GATE = True


class NewTracker26Cap125ValveSeedTrue(NewTracker26Cap125Valve,
                                      NewTracker26SeedTrue):
    """(a+b): cap 1.25x + valve without speed gate + seed
    forced to the true lag."""


# ----------------------------------------------------------------
# Harness: run the winner (or a variant) on a world, forcing the
# dub-arrival speed, and add choppy diagnostics (diag26).
# ----------------------------------------------------------------
_TRACKER26_LAST = [None]


def _capture26(cls):
    orig = cls.__init__

    def _init(self, *a, **kw):
        orig(self, *a, **kw)
        _TRACKER26_LAST[0] = self
    return _init, orig


def _p90v(vals):
    if not vals:
        return None
    a = sorted(abs(v) for v in vals)
    i = min(len(a) - 1, int(0.9 * len(a)))
    return a[i]


def _medv(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


def diag26(label, res, tr, dub, ratio=None):
    """Choppy diagnostics from the captured tracker + dub list.
    end-burst / slice-play / sync restricted to t > 60 s (the
    device snapshot is mid-session, not cold start), plus
    within-burst growth, stretch/valve/pairs windows."""
    true_lag_med = _medv([tl for (_, _, tl, _) in dub]) \
        if dub else None
    d_end = res.get('d_end')
    # --- per-burst TRUE end lateness, t > 60 s (chunk_raw) ---
    by_burst = {}
    for (render, arr, tsw, off, bseq, d_at, spd) in \
            getattr(tr, 'chunk_raw', []):
        if off is None:
            continue
        by_burst.setdefault(bseq, []).append(
            (render, arr, tsw, off, d_at))
    end60, first60, growth60 = [], [], []
    end_by_min = {}
    for bseq, entries in by_burst.items():
        first = min(entries, key=lambda e: e[3])
        last = max(entries, key=lambda e: e[3])
        e_last = (last[0] - last[2]) - last[4]
        e_first = (first[0] - first[2]) - first[4]
        end_by_min.setdefault(int(last[0] // 60000), []).append(e_last)
        if last[0] > 60000:
            end60.append(e_last)
            first60.append(e_first)
            growth60.append(e_last - e_first)
    end_med_min = [None if not end_by_min.get(m) else
                   round(_medv(end_by_min[m])) for m in range(10)]
    sp60 = [e for (e, t) in zip(getattr(tr, 'slice_play_errs', []),
                                getattr(tr, 'slice_play_times', []))
            if t > 60000]
    sy60 = [e for (e, t) in zip(getattr(tr, 'sync_errs', []),
                                getattr(tr, 'sync_times', []))
            if t > 60000]
    # --- stretch actually used ---
    speeds = list(getattr(tr, 'speed_used', []))
    mean_sp = (sum(speeds) / len(speeds)) if speeds else None
    frac15 = (sum(1 for s in speeds if s >= 1.49) / len(speeds)
              if speeds else None)
    floors = [f for (_, f) in getattr(tr, 'floor_hist', [])]
    # --- valve per 60 s window ---
    vwin = {}
    for (arr, lid, dms) in getattr(tr, 'tail_dropped_chunks', []):
        vwin[int(arr // 60000)] = vwin.get(int(arr // 60000), 0) + dms
    # --- pairs / samples per 60 s window ---
    def _perwin(times):
        w = [0] * 10
        for t in times:
            if 0 <= int(t // 60000) < 10:
                w[int(t // 60000)] += 1
        return w
    pairs_w = _perwin(getattr(tr, 'turn_pair_times', []))
    samp_w = _perwin(getattr(tr, 'all_sample_times', []))
    d26 = {
        'true_lag_med': true_lag_med,
        'd_end_signed_err': (d_end - true_lag_med
                             if d_end is not None
                             and true_lag_med is not None else None),
        'end_p90_t60': _p90v(end60),
        'end_max_t60': (max(abs(v) for v in end60)
                        if end60 else None),
        'end_med_t60': _medv(end60),
        'end_n_t60': len(end60),
        'first_med_t60': _medv(first60),
        'growth_med_t60': _medv(growth60),
        'growth_p90_t60': _p90v(growth60),
        'slice_p90_t60': _p90v(sp60),
        'slice_max_t60': (max(abs(v) for v in sp60)
                          if sp60 else None),
        'slice_n_t60': len(sp60),
        'sync_max_t60': (max(abs(v) for v in sy60)
                         if sy60 else None),
        'sync_p90_t60': _p90v(sy60),
        'sync_n_t60': len(sy60),
        'speed_mean': mean_sp,
        'speed_frac_ge149': frac15,
        'floor_med': _medv(floors),
        'valve_fires': getattr(tr, 'tail_valve_fires', 0),
        'valve_ms_total': getattr(tr, 'tail_valve_drop_ms', 0),
        'valve_max_win_ms': max(vwin.values()) if vwin else 0,
        'valve_last_win_ms': vwin.get(9, 0),
        'pairs_win': pairs_w,
        'pairs_mean_win': sum(pairs_w) / len(pairs_w) if pairs_w else 0,
        'samples_win': samp_w,
        'samples_mean_win': sum(samp_w) / len(samp_w) if samp_w else 0,
        'last_sample': getattr(tr, 'last_sample', None),
        'hold_end': getattr(tr, 'hold', None),
        'dropped_slices': getattr(tr, 'dropped_slices', None),
        'sb_trials': res.get('sb_trials', 0),
        'sb_adopts': res.get('sb_adopts', 0),
        'end_med_min': end_med_min,
        'total_dub_ms': sum(d for (_, d, _, _) in dub) if dub else 0,
        'drain_net_dense_min_s': (
            60.0 * mean_sp - 60.0 * ratio
            if mean_sp is not None and ratio else None),
    }
    # Drain-rate check (dense-speech minute): audio in per
    # minute = 60 * ratio; drain capacity = 60 * mean speed.
    # Control-loop claim to reproduce: at cap 1.5 / ratio 1.47
    # net = +1.76 s/min (100 s to drain a 3 s backlog). At cap
    # 1.25 / ratio 1.47 net = -13.2 s/min: the backlog grows
    # ~13 s per dense minute unless the valve drops it.
    print("DIAG26 %s:" % label)
    print("  true lag med %.0f; D end %.0f (signed err %s);"
          " last sample %s; hold end %s"
          % (true_lag_med or -1, d_end or -1,
             ("%.0f" % d26['d_end_signed_err'])
             if d26['d_end_signed_err'] is not None else None,
             d26['last_sample'], d26['hold_end']))
    print("  end-burst TRUE t>60s: p90 %s / max %s / median %s"
          " (n=%d); first-chunk median %s; within-burst growth"
          " median %s / p90 %s"
          % (d26['end_p90_t60'], d26['end_max_t60'],
             d26['end_med_t60'], d26['end_n_t60'],
             d26['first_med_t60'], d26['growth_med_t60'],
             d26['growth_p90_t60']))
    print("  slice-play t>60s: p90 %s / max %s (n=%d); sync"
          " t>60s: max %s / p90 %s (n=%d)"
          % (d26['slice_p90_t60'], d26['slice_max_t60'],
             d26['slice_n_t60'], d26['sync_max_t60'],
             d26['sync_p90_t60'], d26['sync_n_t60']))
    print("  stretch: mean %.2f, frac>=1.49 %.2f, ratio-floor"
          " median %s; valve fires %d, dropped %d ms total,"
          " max/60s %d ms, last/60s %d ms"
          % (mean_sp or -1, frac15 if frac15 is not None else -1,
             d26['floor_med'], d26['valve_fires'],
             d26['valve_ms_total'], d26['valve_max_win_ms'],
             d26['valve_last_win_ms']))
    print("  pairs/60s %s (mean %.1f); samples/60s %s"
          " (mean %.1f); drop steady %.1f%%; dropped slices %s;"
          " Sb trials %d adopts %d; seedErr %s"
          % (pairs_w, d26['pairs_mean_win'], samp_w,
             d26['samples_mean_win'], res.get('drop_steady', -1),
             d26['dropped_slices'], d26['sb_trials'],
             d26['sb_adopts'], res.get('seed_final_err')))
    print("  end-burst median by minute %s (rising = backlog"
          " accumulating faster than the drain); total dub"
          " %d ms; drain check dense-minute: net %s s/min"
          " (claim at 1.5x/1.47: +1.76 s/min)"
          % (end_med_min, d26['total_dub_ms'],
             ("%.2f" % d26['drain_net_dense_min_s'])
             if d26['drain_net_dense_min_s'] is not None else None))
    print()
    res['diag26'] = d26
    return d26


def run26(label, tracker_cls, world, ratio, lag0, film='C',
          dub_speed=1.5, lag_step_ms=0, lag_step_at_ms=120000,
          **kw):
    """Run s25.do_run25 with build_dub21 forced to dub_speed
    (arrival speed of the dub stream). lag_step_ms > 0 shifts
    every dub chunk arriving after lag_step_at_ms later by that
    much (arrival AND true_lag): a DUB-side lag step, i.e. an
    injected ~lag_step_ms backlog the tracker must drain /
    chase — the device's actual state at 12:00 (end-burst
    p90 3.4 s that never drains). Input-side tx fragments and
    VAD are untouched (a dub-side delay). Captures the
    tracker for diag26. Returns (res, diag26)."""
    orig_build_dub = s21.build_dub21

    def _forced(frames, sent, ls, lu, ratio=ratio, lag0=lag0,
                speed=1.5, seed=s22.SEED if hasattr(s22, 'SEED')
                else 20261128, skip_p=s21.TX_SKIP_P):
        out = orig_build_dub(frames, sent, ls, lu, ratio=ratio,
                             lag0=lag0, speed=dub_speed, seed=seed,
                             skip_p=skip_p)
        dub = out[0]
        if lag_step_ms:
            dub = [(a + lag_step_ms if a > lag_step_at_ms else a,
                    d,
                    tl + lag_step_ms if a > lag_step_at_ms else tl,
                    lid)
                   for (a, d, tl, lid) in dub]
        _DUB26['dub'] = dub
        _DUB26['skipped'] = out[1]
        return dub, out[1]
    s21.build_dub21 = _forced
    # s24.do_run24 also calls s21.build_dub21 directly; the
    # patch above covers it (module attribute lookup).
    _TRACKER26_LAST[0] = None
    cap_init, orig_init = _capture26(tracker_cls)
    tracker_cls.__init__ = cap_init
    try:
        res = s25.do_run25(label, tracker_cls, world, ratio, lag0,
                           film=film, **kw)
    finally:
        tracker_cls.__init__ = orig_init
        s21.build_dub21 = orig_build_dub
    tr = _TRACKER26_LAST[0]
    d26 = diag26(label, res, tr, _DUB26.get('dub', []),
                 ratio=ratio) if tr is not None else None
    res['label26'] = label
    RESULTS26.append(res)
    return res, d26


def winner_spec26():
    """v1.0.22 winner spec on NewTracker25."""
    s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
              sb_drift_gate=False)
    return s25.best25_kw()


def summary26():
    print("=" * 110)
    print("SUMMARY26 (choppy + regression; end-p90 = TRUE render,"
          " steady window; t60 = renders after 60 s):")
    print("%-30s %7s %7s %7s %7s %6s %6s %5s %5s %6s %6s %5s"
          % ("run", "endp90", "end90t60", "slc90t60",
             "syncMx60", "spdMean", "spd>=cap", "prMean",
             "smMean", "DendErr", "valveMs", "drop%"))
    for r in RESULTS26:
        d = r.get('diag26') or {}
        print("%-30s %7.0f %7.0f %7.0f %7.0f %6.2f %6.2f %5.1f"
              " %5.1f %6.0f %6.0f %5.1f"
              % (r['label'][:30], r['end_p90_true'] or -1,
                 d.get('end_p90_t60') or -1,
                 d.get('slice_p90_t60') or -1,
                 d.get('sync_max_t60') or -1,
                 d.get('speed_mean') or -1,
                 d.get('speed_frac_ge149')
                 if d.get('speed_frac_ge149') is not None else -1,
                 d.get('pairs_mean_win') or -1,
                 d.get('samples_mean_win') or -1,
                 d.get('d_end_signed_err')
                 if d.get('d_end_signed_err') is not None else -9999,
                 d.get('valve_ms_total') or 0,
                 r.get('drop_steady', -1)))
    print()


def main():
    t_start = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'choppy'
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("sim1026 ROUND 5: choppy-world recalibration. Winner"
          " logic Sa2+Sb3+V2000cap500 on the choppy world"
          " (lines 0.6-1.3 s, pauses 0.4-1.2 s silence, ratio"
          " 1.47, lag 4.5 s) + diagnostics. 10-min sessions.")
    print()

    if mode == 'probe':
        for sn in (4, 3, 2):
            w = _build_world26(start_need=sn)
            world_stats26("choppy start_need=%d" % sn, w)
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return

    worldC = _build_world26()
    world_stats26("choppy r1.47 lag4.5", worldC)
    print("DEVICE 12:00 targets: turns60 35, fed60/speech"
          " 32880 ms, speech/turn 0.94 s, duty 55%, ratio 1.47,"
          " lag ~4.5 s, D 3.3 s, samples 86/60 s, pairs 29/60 s.")
    print()
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worlds4 = [(worldA, s22.RATIO22_A, s22.LAG22_A, 'A'),
               (worldA, s22.RATIO22_B, s22.LAG22_A, 'A'),
               (worldB, s22.RATIO22_A, s22.LAG22_A, 'B'),
               (worldA, s22.RATIO22_A, s22.LAG22_B, 'A')]

    if mode in ('full', 'repro'):
        print("#### REGRESSION: winner on the 4 sim1025 worlds"
              " (must reproduce end-p90 [501, 726, 420, 252]) ####")
        kw = winner_spec26()
        s25.run4_25("REG26 r%.2f lag%.1f %s", s25.NewTracker25,
                    worlds4, **kw)
        for r in s25.RESULTS25[-4:]:
            d = r.get('diag26')
            print("  REG26 %-28s end-p90 %s |D-lag| %s seedErr %s"
                  % (r['label'], round(r['end_p90_true']),
                     round(r['d_end_err']),
                     r['seed_final_err']))
        print()
        if mode == 'repro':
            print("TOTAL runtime %.1f s" % (time.time() - t_start))
            return

    # ---- Choppy variants (diagnostics). ----
    if mode in ('full', 'choppy'):
        print("#### CHOPPY: winner + diagnostic variants ####")
        kw = winner_spec26()
        run26("C0-choppy-REPRO r1.47 lag4.5", s25.NewTracker25,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        # C1/C6 (cap 2.0x): MECHANISM PROOF ONLY — never a ship
        # candidate (user verdict 28/11 12:11: sustained 1.5x
        # already distorts the voice; 2.0x can only be worse).
        run26("C1-choppy-cap2.0-MECHONLY r1.47 lag4.5",
              NewTracker26Cap20,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        run26("C2-choppy-seedTrue r1.47 lag4.5",
              NewTracker26SeedTrue,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        run26("C3-choppy-valveOFF r1.47 lag4.5", s25.NewTracker25,
              worldC, 1.47, 4500, film='C', dub_speed=1.5,
              **s25.best25_kw(tail_valve=False))
        run26("C4-choppy-arrival3.0 r1.47 lag4.5", s25.NewTracker25,
              worldC, 1.47, 4500, film='C', dub_speed=3.0, **kw)
        run26("C5-choppy-arrival10 r1.47 lag4.5", s25.NewTracker25,
              worldC, 1.47, 4500, film='C', dub_speed=10.0, **kw)
        run26("C6-choppy-cap2.0+arr3.0-MECHONLY r1.47 lag4.5",
              NewTracker26Cap20, worldC, 1.47, 4500, film='C',
              dub_speed=3.0, **kw)

    # ---- Cap-1.25x direction (a)/(b)/(a+b) on BOTH worlds. ----
    if mode in ('full', 'cap125'):
        print("#### CAP-1.25x (a)/(b)/(a+b) vs 1.5x baseline,"
              " long-turn + choppy ####")
        kw = winner_spec26()
        # Baselines with diag26 (film A + dense; choppy C0/C2
        # already measured above / in 'choppy' mode).
        run26("B0-filmA-REPRO r1.42 lag4.6", s25.NewTracker25,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, **kw)
        run26("B0-dense-REPRO r1.42 lag4.6", s25.NewTracker25,
              worldB, s22.RATIO22_A, s22.LAG22_A, film='B',
              dub_speed=1.5, **kw)
        # (b) accurate seed/D only, cap still 1.5x.
        run26("B4-filmA-seedTrue r1.42 lag4.6",
              NewTracker26SeedTrue,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, **kw)
        # (a) cap 1.25x, valve gate as shipped (valve dead:
        # arming needs speed >= 1.49).
        run26("A1-filmA-cap125-valveDead r1.42 lag4.6",
              NewTracker26Cap125,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, **kw)
        # (a) cap 1.25x, valve gate removed (needs Kotlin change).
        run26("A2-filmA-cap125-valveLive r1.42 lag4.6",
              NewTracker26Cap125Valve,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, **kw)
        # (a+b) cap 1.25x + valve live + seed true, film A.
        run26("A3-filmA-cap125+seedTrue r1.42 lag4.6",
              NewTracker26Cap125ValveSeedTrue,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, **kw)
        # Dense world (worst case for the low cap: 96% duty).
        run26("D2-dense-cap125-valveLive r1.42 lag4.6",
              NewTracker26Cap125Valve,
              worldB, s22.RATIO22_A, s22.LAG22_A, film='B',
              dub_speed=1.5, **kw)
        # Choppy world, cap 1.25x (gaps give drain slack).
        run26("K1-choppy-cap125-valveDead r1.47 lag4.5",
              NewTracker26Cap125,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        run26("K2-choppy-cap125-valveLive r1.47 lag4.5",
              NewTracker26Cap125Valve,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        run26("K3-choppy-cap125+seedTrue r1.47 lag4.5",
              NewTracker26Cap125ValveSeedTrue,
              worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)

    # ---- Backlog-recovery probe: +2000 ms dub-side lag step
    # at t=120 s (film A). The device at 12:00 sits on ~3 s of
    # backlog that never drains; this asks whether each cap
    # can drain an injected backlog at all, and how fast.
    # Read the per-minute end-burst medians: the spike at
    # minute 2 and the decay afterwards are the verdict.
    # Caveat: recovery mixes D-chase (samples jump +2000 ms,
    # D-check rejects them until CHASE accepts the run median)
    # with queue drain, so a slow recovery implicates the
    # chase as well as the stretch ceiling. ----
    if mode in ('full', 'cap125', 'step'):
        print("#### BACKLOG-RECOVERY probe: lag +2000 ms at"
              " t=120 s, film A r1.42 ####")
        kw = winner_spec26()
        run26("R0-filmA-step2000-cap150 r1.42 lag4.6",
              s25.NewTracker25,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, lag_step_ms=2000, **kw)
        run26("R1-filmA-step2000-cap125 r1.42 lag4.6",
              NewTracker26Cap125Valve,
              worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
              dub_speed=1.5, lag_step_ms=2000, **kw)
    summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t_start))


if __name__ == '__main__':
    main()
