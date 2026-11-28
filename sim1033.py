#!/usr/bin/env python3
"""sim1033.py — Fix A3 (corroborated trial ref) + Fix D2 (valve
divergence gate). Repairs the sim1032 findings:

  (1) STEP-REGRESSION from Fix A: sim1032 R1 ABCD NEVER
  recovers from a genuine +2000 ms lag step (Old recovers in
  3 min). Cause: Fix A forces _ref_lag() to the OLD cluster
  ref during trials, so the D-check stays closed to the new
  lag, new-lag slices are rejected, and D can only creep via
  boundary samples. But the trial-ref mechanism IS how Old
  recovers: the trial moves the D-check to the new lag, slices
  feed, D jumps. Fix A removed the recovery path together
  with the explosion path.
  Fix A3: a trial ref takes effect ONLY if corroborated by an
  INDEPENDENT source — the median of the last 8 accepted tx
  anchors (lat_tx follows the TRUE lag: it stays ~4.4 s in
  the wrong-trial case, jumps to ~6.45 s in a genuine step;
  it does NOT share the VAD turn anchor that the S-b medians
  share). |trial_ref - tx_med| <= 1000 ms -> trial ref takes
  effect (Old behaviour, recovery works). Otherwise the
  established ref rules (Fix A behaviour, explosion blocked).
  A later trial can still be corroborated (the S-b medians
  are recorded pre-D-check, so the evaluation is unaffected).
  (2) D2 (valve): on seeds 29/30 the fixed valve fires 8-19x
  (4-9.5 s dropped) on a CONSTANT anchor offset it cannot
  fix (end-p90 stays ~2.5 s). Fire only when the tracker
  agrees with itself: |D - lag_est| <= 1500 ms. On seed 28
  the divergence is ~margin (~1000) so genuine tail firing
  (r=1.45: 3x) is preserved; on a wrong-anchor world the
  divergence is ~3000 and the valve stays silent.

Classes (cap 1.35, on top of sim1031 ABCD = A+B+C+D):
  T33A3   = ABCD with Fix A replaced by Fix A3.
  T33A3D2 = A3 + Fix D2.
Validation: filmA r=1.40 seed 28 (explosion must stay fixed),
lag-step recovery ABCD-vs-A3 (must PASS), seeds 29/30
(valve fires? drop? end-p90 no worse than ABCD), r=1.45
filmA seed 28 (valve must still fire ~3x).

Usage: python3 sim1033.py
Output: sim1033_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1021 as s21
import sim1029 as s29
import sim1029b as s29b
import sim1031 as s31


def _med(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


class NewTracker33A3(s31.NewTracker31ABCD):
    """ABCD with Fix A replaced by Fix A3 (corroborated trial
    ref). Fixes B/C/D inherited unchanged."""

    TX_HIST_N = 8
    TX_CORROBORATE_MS = 1000

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._a3_tx_hist = []
        self._div_hist = []

    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        had = burst_seq in self._sb_lat_ok
        super().apply_tx_anchor(now, burst_seq, true_lag,
                                true_sample)
        if not had and burst_seq in self._sb_lat_ok:
            self._a3_tx_hist.append(
                self._sb_lat_ok[burst_seq])
            while len(self._a3_tx_hist) > self.TX_HIST_N:
                self._a3_tx_hist.pop(0)

    def _ref_established(self):
        """The ref with NO trial influence (Fix A logic):
        B-c adopted ref if set, else the s21 densest-cluster
        ref."""
        if self._bc_adopted_ref is not None:
            near = sorted(s for s in self._bnd_samples
                          if abs(s - self._bc_adopted_ref)
                          <= 1200)
            if len(near) >= 3:
                return near[len(near) // 2]
            return self._bc_adopted_ref
        return s21.NewTracker21._ref_lag(self)

    def _ref_lag(self):
        # Fix A3: a trial ref takes effect only if corroborated
        # by recent tx anchors (independent of the VAD anchor
        # the S-b medians share). Otherwise the established ref
        # rules and the explosion path stays blocked.
        trial_ref = None
        if (getattr(self, '_sb_trial_active', False)
                and self._sb_trial_ref is not None):
            trial_ref = self._sb_trial_ref
        elif (getattr(self, '_bc_trial_active', False)
                and self._bc_trial_ref is not None):
            trial_ref = self._bc_trial_ref
        if trial_ref is not None:
            tx_med = (_med(self._a3_tx_hist)
                      if len(self._a3_tx_hist) >= 3 else None)
            if (tx_med is not None
                    and abs(trial_ref - tx_med)
                    <= self.TX_CORROBORATE_MS):
                return trial_ref
            return self._ref_established()
        return self._ref_established()

    def on_dub_burst(self, now, true_lag, true_sample=None):
        super().on_dub_burst(now, true_lag, true_sample)
        self._div_hist.append(
            abs(self.delay - self.lag_est(now)))


class NewTracker33A3D2(NewTracker33A3):
    """A3 + Fix D2: the valve fires only when the tracker
    agrees with itself (|D - lag_est| <= 1500 ms). A constant
    anchor offset (divergence ~3000 ms) is structural — the
    valve cannot fix it, so it stays silent instead of
    dropping audio for nothing."""

    DIVERGENCE_GATE_MS = 1500

    def on_dub_burst(self, now, true_lag, true_sample=None):
        # Temporarily hold the D2 decision: run the parent
        # (which may fire the valve) only if the divergence
        # gate passes; otherwise run everything EXCEPT the
        # valve trigger. The parent's on_dub_burst does the
        # completed-end bookkeeping + Fix C + the valve, so
        # instead of skipping it, snapshot the valve counters
        # and roll back a fire made while divergent.
        f0 = self.tail_valve_fires
        m0 = self.tail_valve_drop_ms
        d0 = getattr(self, '_tail_drop_remaining', 0)
        u0 = self._vb_valve_used_seq
        super().on_dub_burst(now, true_lag, true_sample)
        if (self.tail_valve_fires > f0
                and abs(self.delay - self.lag_est(now))
                > self.DIVERGENCE_GATE_MS):
            # Roll back: return the dropped audio to the queue.
            self._tail_drop_remaining = d0
            self.tail_valve_fires = f0
            self.tail_valve_drop_ms = m0
            self._vb_valve_used_seq = u0


def _run(label, cls, world, ratio, lag0, film='A', **extra):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **dict(s29.spec29(), **extra))
    tr = s26._TRACKER26_LAST[0]
    d = d26 or {}
    div = _med(getattr(tr, '_div_hist', []) or []) or -1
    print("33 %-26s endp90t60=%6.0f drop=%5.1f%% valve=%3d/%6d"
          " ms Dmax=%5.0f holdMax=%5.0f divMed=%5.0f pairs=%4.1f"
          " sbT=%d/%d"
          % (label, d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_d_max31', -1),
             getattr(tr, '_hold_max31_post', -1),
             div,
             d.get('pairs_mean_win') or -1,
             getattr(tr, '_sb_trials', -1),
             getattr(tr, '_sb_adopts', -1)))
    return res, d26


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1033 Fix A3 (corroborated trial ref) + Fix D2"
          " (valve divergence gate).")
    print()
    A3 = NewTracker33A3
    A3D2 = NewTracker33A3D2
    ABCD = s31.NewTracker31ABCD
    OLD = s29.NewTracker29Cap135OldValve
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    print("--- filmA r=1.40 seed 28 (explosion must stay fixed)"
          " ---")
    _run("A3-filmA-r1.40", A3, worldA, 1.40, s22.LAG22_A,
         film='A')
    _run("A3D2-filmA-r1.40", A3D2, worldA, 1.40, s22.LAG22_A,
         film='A')
    print("--- lag-step recovery (+2000 ms at t=120 s,"
          " filmA r=1.35) ---")
    r0 = s26.run26("R0-step-OLD r1.35", OLD, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    r1 = s26.run26("R1-step-A3 r1.35", A3, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    r2 = s26.run26("R2-step-A3D2 r1.35", A3D2, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    ok0 = s29.recovery_gate29("R0 OLD", r0[0])
    ok1 = s29.recovery_gate29("R1 A3", r1[0])
    ok2 = s29.recovery_gate29("R2 A3D2", r2[0])
    print("RECOVERY33: OLD %s / A3 %s / A3D2 %s"
          % ("PASS" if ok0 else "FAIL",
             "PASS" if ok1 else "FAIL",
             "PASS" if ok2 else "FAIL"))
    print()
    print("--- r=1.45 filmA seed 28 (valve must still fire) ---")
    _run("A3D2-filmA-r1.45", A3D2, worldA, 1.45, s22.LAG22_A,
         film='A')
    print("--- seeds 29/30 (valve must not fire for nothing) ---")
    for seed in (20261129, 20261130):
        wA = s29b.build_world_seed(seed, **s22.WORLD22)
        wB = s29b.build_world_seed(seed, **s22.WORLD22_DENSE)
        _run("A3D2-s%d-filmA-r1.35" % seed, A3D2, wA, 1.35,
             s22.LAG22_A, film='A')
        _run("A3D2-s%d-dense-r1.35" % seed, A3D2, wB, 1.35,
             s22.LAG22_A, film='B')
        _run("A3-s%d-filmA-r1.35" % seed, A3, wA, 1.35,
             s22.LAG22_A, film='A')
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
