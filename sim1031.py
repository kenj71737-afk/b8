#!/usr/bin/env python3
"""sim1031.py — validate the sim1030 fixes (A/B/C/D) + ablate.

sim1030 proved the r=1.40 explosion is NOT a drain deficit:
  58.2 s S-b trial (3 medians 6477 vs ref 4148) -> _ref_lag()
  returns the TRIAL ref -> _d_based_lag()=6477 -> slice D-check
  INVERTS (correct ~4.5 s slices rejected, wrong ~6.5 s slices
  accepted) -> EMA feeds 6.5 s samples -> D jumps 5161->5895->
  6145->6758 (no upward slew; down only creeps 250/sample) ->
  trial correctly REJECTED at 74.8 s but D stays 6758 -> burst
  21 (marker): robust = D-lag_est-pipe = 2399 -> hold jacked
  562->2399 -> hold decays only 300 ms per MARKER burst
  (155/175 no-marker) -> ~6 min of end-burst ~1.6-2.0 s.

Fixes (each isolated by an ablation class):
  A (root): a trial ref NEVER takes effect until adopted.
     _ref_lag() returns the established ref (B-c adopted ref if
     set, else the s21 cluster ref) during trials. The trial
     still runs and is still evaluated — it just cannot move
     the D-check / _sel_lag / R-b reference while active.
  B (defense): slew D UPWARD — LatencyEma.sample caps a D rise
     at +250 ms per accepted sample (same as the downward
     creep). Bounds the rate of any future D excursion.
  C (defense): clamp hold to dynamic_margin (~1000 ms), and
     run the 300 ms decay slew on EVERY burst (decay-only on
     no-marker bursts: never jack hold UP without a marker).
     A hold above the margin means D and lag_est disagree by
     more than the jitter budget — always a measurement error.
  D (valve trigger, step 2): the valve fires ONLY on the
     MEDIAN true end-burst of the last 3 COMPLETED bursts
     (full pairing log at the next burst boundary, not the
     at-play partial snapshot) AND only when hold <= margin
     (a hold jack is structural — dropping tail audio cannot
     fix it, it only deletes words). OLD strength 2000/1500/
     500 kept. The inherited s22/s23 arming is dead at cap
     1.35 anyway (speed gate 1.49 never reached).

Classes (all cap 1.35, base = NewTracker29Cap135OldValve):
  T31A / T31AC / T31ABC / T31ABCD (+ fixes cumulatively)
  Controls: Old (dead valve), Strong (NewTracker29B).

Runs (seed 20261128): filmA r=1.40 (all 6), dense r=1.40
(Old/A/ABCD), filmA r=1.35 (Old/ABCD), choppy r=1.40
(Old/ABCD). Metrics: end-p90 TRUE steady, drop%, valve
fires/ms, D max, hold max. PASS bar: end-p90 < 1000 ms,
drop <= 2%, valve drop <= Old valve.

Usage: python3 sim1031.py
Output: sim1031_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1021 as s21
import sim1018 as s18
import sim1029 as s29


def _med(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


class LatencyEma31(s18.LatencyEma18):
    """Fix B: cap an upward D jump at +250 ms per accepted
    sample (the downward creep). The EMA state still updates
    fully; only the returned D is rate-limited. The <500 ms
    deadband / downward creep behaviour is unchanged."""
    UP_SLEW_MS = 250

    def sample(self, lat, cur):
        s = max(0, min(lat, self.max_d - self.margin))
        self.ema = s if self.ema is None else \
            (self.ema * 7 + s * 3) // 10
        new = max(self.min_d,
                  min(self.ema + self.margin, self.max_d))
        if new - cur >= 500:
            return min(new, cur + self.UP_SLEW_MS)
        if abs(new - cur) >= 500:
            return new
        if self.creep:
            tgt = self.target()
            if tgt < cur:
                return max(tgt, cur - self.creep)
        return cur


class NewTracker31A(s29.NewTracker29Cap135OldValve):
    """Fix A only: trial refs never take effect until adopted."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._d_max31 = 0
        self._hold_max31 = 0

    def _set_delay(self, v):
        if v > self._d_max31:
            self._d_max31 = v
        return super()._set_delay(v)

    def _ref_lag(self):
        # Fix A: during an S-b or B-c trial, return the
        # ESTABLISHED ref — the B-c adopted ref if a trial was
        # adopted, else the s21 densest-cluster ref. The trial
        # ref is used only to evaluate the trial itself
        # (_sb_step/_bc_end_trial read the stored trial refs
        # directly), never as the D-check / selection reference.
        if self._bc_adopted_ref is not None:
            near = sorted(s for s in self._bnd_samples
                          if abs(s - self._bc_adopted_ref)
                          <= 1200)
            if len(near) >= 3:
                return near[len(near) // 2]
            return self._bc_adopted_ref
        return s21.NewTracker21._ref_lag(self)

    def on_dub_burst(self, now, true_lag, true_sample=None):
        super().on_dub_burst(now, true_lag, true_sample)
        if self.hold > self._hold_max31:
            self._hold_max31 = self.hold


class NewTracker31AC(NewTracker31A):
    """Fix A + Fix C (hold clamp + decay on every burst)."""

    def on_dub_burst(self, now, true_lag, true_sample=None):
        prev_hold = self.hold
        super().on_dub_burst(now, true_lag, true_sample)
        # Fix C2: on a no-marker burst super() left hold
        # untouched (the slew is gated on a marker). Decay it
        # toward robust, DOWN ONLY — a transient lag_est dip
        # (burst 3 in every run: robust 1566-1988) must never
        # jack hold up without a marker.
        if self._last_burst_marker is None:
            robust = max(0, self.delay - self.lag_est(now)
                         - self.pipeline_ms())
            self.hold = max(prev_hold - 300,
                            min(robust, prev_hold))
        # Fix C1: hold above the dynamic margin is always a
        # measurement error (D and lag_est disagree by more
        # than the jitter budget), never a real need.
        self.hold = min(self.hold, self.dynamic_margin())
        if self.hold > getattr(self, '_hold_max31_post', 0):
            self._hold_max31_post = self.hold


class NewTracker31ABC(NewTracker31AC):
    """Fix A + B + C."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.ema = LatencyEma31(creep=250)


class NewTracker31ABCD(NewTracker31ABC):
    """Fix A + B + C + D (valve trigger: median of 3 completed
    bursts' TRUE end-burst + hold gate, OLD strength)."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._fxd_play = {}       # seq -> (play_wall, speed, D)
        self._fxd_completed = []  # completed TRUE end-bursts
        self._fxd_done = set()
        self._fxd_valve_med = None

    def on_burst_played(self, seq, play_wall, pipeline,
                        backlog=0):
        super().on_burst_played(seq, play_wall, pipeline,
                                backlog)
        self._fxd_play[seq] = (play_wall,
                               max(1.0, self.target_speed),
                               self.delay)
        old = [s for s in self._fxd_play if s < seq - 12]
        for s in old:
            del self._fxd_play[s]

    def _fxd_completed_end(self, seq):
        """TRUE end-burst of a COMPLETED burst: the full pairing
        log (all its slices arrived before the next burst) with
        the burst's own play wall / speed / D — the same formula
        as _compute_vb_end_lag, but on the complete log instead
        of the at-play partial snapshot."""
        info = self._fxd_play.get(seq)
        rec = self.burst_records.get(seq)
        if info is None or rec is None:
            return None
        play_wall, speed, d_play = info
        entries = [e for e in self.pairing_log if e[2] == seq]
        if not entries:
            return None
        start_cum = rec['start_cum']
        last = max(entries, key=lambda e: e[0])
        dub_cum, src_wall = last[0], last[1]
        off = max(0, dub_cum - start_cum)
        return (play_wall + off / speed - src_wall) - d_play

    def on_dub_burst(self, now, true_lag, true_sample=None):
        # The previous burst is complete now (all its slices
        # arrived before this burst): finalise its TRUE end.
        prev = getattr(self, '_sb_last_seq', None)
        if prev is not None and prev not in self._fxd_done:
            ce = self._fxd_completed_end(prev)
            if ce is not None:
                self._fxd_completed.append(ce)
                self._fxd_done.add(prev)
        super().on_dub_burst(now, true_lag, true_sample)
        # Fix D: fire only on the median TRUE end-burst of the
        # last 3 COMPLETED bursts, and only when hold is normal.
        # (The inherited s22/s23 arming is dead at cap 1.35 —
        # speed gate 1.49 — so this is the only live trigger.)
        if (self.TAIL_VALVE and len(self._fxd_completed) >= 3
                and self.hold <= self.dynamic_margin()):
            med = _med(self._fxd_completed[-3:])
            self._fxd_valve_med = med
            if (med > self.VALVE_THRESH_MS
                    and self._vb_play_seq is not None
                    and self._vb_play_seq
                    != self._vb_valve_used_seq):
                drop = int(min(med - self.VALVE_KEEP_MS,
                               self.VALVE_MAX_DROP_MS))
                if drop > 0:
                    self._tail_drop_remaining += drop
                    self.tail_valve_fires += 1
                    self.tail_valve_drop_ms += drop
                    self._vb_valve_used_seq = self._vb_play_seq


def _run(label, cls, world, ratio, lag0, film='A'):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **s29.spec29())
    tr = s26._TRACKER26_LAST[0]
    endp = (d26 or {}).get('end_p90_t60')
    if endp is None:
        endp = (d26 or {}).get('end_p90')
    drop = res.get('drop_pct_steady')
    if drop is None:
        drop = res.get('drop_pct')
    print("31 %-28s endp90=%7.0f drop=%5.1f%% valve=%3d/%6d ms"
          " Dmax=%5.0f holdMax=%5.0f/%5.0f sbTrials=%d/%d"
          % (label, endp if endp is not None else -1,
             drop if drop is not None else -1,
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_d_max31', -1),
             getattr(tr, '_hold_max31', -1),
             getattr(tr, '_hold_max31_post', -1),
             getattr(tr, '_sb_trials', -1),
             getattr(tr, '_sb_adopts', -1)))
    return res, d26


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1031 FIXES A/B/C/D validation + valve ablation"
          " (seed 20261128, cap 1.35).")
    print("Fix A: trial ref not effective until adopted."
          " Fix B: D up-slew +250/sample. Fix C: hold clamped"
          " to margin + decay every burst. Fix D: valve on"
          " median of 3 COMPLETED true end-bursts + hold gate"
          " (OLD strength 2000/1500/500).")
    print()
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    old = s29.NewTracker29Cap135OldValve
    strong = s29.NewTracker29B
    print("--- filmA r=1.40 (the explosion) ---")
    _run("Old", old, worldA, 1.40, s22.LAG22_A, film='A')
    _run("A", NewTracker31A, worldA, 1.40, s22.LAG22_A, film='A')
    _run("AC", NewTracker31AC, worldA, 1.40, s22.LAG22_A,
         film='A')
    _run("ABC", NewTracker31ABC, worldA, 1.40, s22.LAG22_A,
         film='A')
    _run("ABCD", NewTracker31ABCD, worldA, 1.40, s22.LAG22_A,
         film='A')
    _run("Strong", strong, worldA, 1.40, s22.LAG22_A, film='A')
    print("--- dense r=1.40 ---")
    _run("Old-dense", old, worldB, 1.40, s22.LAG22_A, film='B')
    _run("A-dense", NewTracker31A, worldB, 1.40, s22.LAG22_A,
         film='B')
    _run("ABCD-dense", NewTracker31ABCD, worldB, 1.40,
         s22.LAG22_A, film='B')
    print("--- filmA r=1.35 (control) ---")
    _run("Old-135", old, worldA, 1.35, s22.LAG22_A, film='A')
    _run("ABCD-135", NewTracker31ABCD, worldA, 1.35,
         s22.LAG22_A, film='A')
    print("--- choppy r=1.40 (control) ---")
    _run("Old-choppy", old, worldC, 1.40, 4500, film='C')
    _run("ABCD-choppy", NewTracker31ABCD, worldC, 1.40, 4500,
         film='C')
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
