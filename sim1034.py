#!/usr/bin/env python3
"""sim1034.py — Fix A is WRONG: it breaks genuine step recovery.
Findings (sim1032/sim1033/sim1033b):
  * Fix A (trial ref never takes effect) kills the r=1.40
    explosion (1940->460) BUT the step run NEVER recovers
    (Old recovers at minute 5 via trial-2 ADOPTION at 316 s;
    ABCD/A3 stay ~2 s late through minute 9).
  * Fix A3 (tx corroboration) does NOT restore recovery: the
    tx anchors do NOT jump with a step (lat_tx stays ~4 s —
    start_est shifts with burst_arrival, so lat_tx is
    step-invariant). Both step trials are uncorroborated and
    rejected; D never rises. A3 is REJECTED as a fix.
  * Fix D2 (valve divergence gate) is USELESS: |D-lag_est|
    median is ~787 ms on seeds 29/30 too (lag_est tracks D
    into the wrong mode) — the gate never triggers. REJECTED.
  * Old's step recovery works via ADOPTION: trial 1 (rejected)
    primes the system, trial 2 ADOPTS at 316 s (max_run 5,
    med_cand 570), ref jumps to ~7055, D jumps to ~7350.
    The trial ref taking effect DURING trials is part of the
    recovery path — it cannot simply be removed.

New hypothesis (test here): drop Fix A; bound the wrong-trial
damage with B (D up-slew) + C (hold clamp + decay) alone:
  * Wrong trial: D may spike during the ~17 s trial, but the
    hold clamp (<= margin) caps end-burst at ~400 ms, and the
    trial is rejected so D falls back. Transient video-delay
    jump (~+1.7 s, self-recovering in ~1 min) remains — a
    known bounded artifact, not a sync failure.
  * Genuine step: trial path intact (Old behaviour) -> trial-2
    adoption -> recovery like Old.
  * BCD_TB variant adds a TRIAL BUDGET: while any trial is
    active, D may move at most +-500 ms from its value at
    trial start (EMA state still updates fully; the cap lifts
    on adoption). This bounds the wrong-trial D spike itself
    while (maybe) preserving the trial-1 priming for trial-2
    adoption.

Classes (cap 1.35): BCD = B+C+D (no Fix A); BCD_TB = BCD +
trial budget. Controls: Old, ABCD (Fix A, for the explosion
comparison only).
Runs: filmA r=1.40 seed 28, filmA r=1.35 seed 28, step
recovery (+2000 ms at t=120 s), seeds 29/30 filmA r=1.35.

Usage: python3 sim1034.py
Output: sim1034_run.txt
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


class NewTracker34BCD(s31.NewTracker31ABCD):
    """B + C + D, WITHOUT Fix A: _ref_lag is Old's (trial ref
    takes effect during trials). The wrong-trial damage is
    bounded by the D up-slew (B) + hold clamp/decay (C)."""

    def _ref_lag(self):
        return super(s31.NewTracker31A, self)._ref_lag()


class NewTracker34BCD_TB(NewTracker34BCD):
    """BCD + trial budget: while any trial is active, D may
    move at most +-500 ms from its trial-start value."""

    TB_MS = 500

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._tb_D0 = None

    def _set_delay(self, v):
        active = (getattr(self, '_sb_trial_active', False)
                  or getattr(self, '_bc_trial_active', False))
        if not active:
            self._tb_D0 = None
            return super()._set_delay(v)
        if self._tb_D0 is None:
            self._tb_D0 = self.delay
        v = max(self._tb_D0 - self.TB_MS,
                min(self._tb_D0 + self.TB_MS, v))
        return super()._set_delay(v)


def _run(label, cls, world, ratio, lag0, film='A', **extra):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **dict(s29.spec29(), **extra))
    tr = s26._TRACKER26_LAST[0]
    d = d26 or {}
    print("34 %-26s endp90t60=%6.0f drop=%5.1f%% valve=%3d/%6d"
          " ms Dmax=%5.0f holdMax=%5.0f pairs=%4.1f sbT=%d/%d"
          % (label, d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_d_max31', -1),
             getattr(tr, '_hold_max31_post', -1),
             d.get('pairs_mean_win') or -1,
             getattr(tr, '_sb_trials', -1),
             getattr(tr, '_sb_adopts', -1)))
    return res, d26


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1034: drop Fix A (it breaks step recovery);"
          " bound the wrong trial with B+C, optionally + trial"
          " budget (TB).")
    print()
    BCD = NewTracker34BCD
    TB = NewTracker34BCD_TB
    ABCD = s31.NewTracker31ABCD
    OLD = s29.NewTracker29Cap135OldValve
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    print("--- filmA r=1.40 seed 28 (explosion) ---")
    _run("BCD-filmA-r1.40", BCD, worldA, 1.40, s22.LAG22_A,
         film='A')
    _run("TB-filmA-r1.40", TB, worldA, 1.40, s22.LAG22_A,
         film='A')
    print("--- filmA r=1.35 seed 28 (control) ---")
    _run("BCD-filmA-r1.35", BCD, worldA, 1.35, s22.LAG22_A,
         film='A')
    _run("TB-filmA-r1.35", TB, worldA, 1.35, s22.LAG22_A,
         film='A')
    print("--- lag-step recovery (+2000 ms at t=120 s) ---")
    r0 = s26.run26("R0-step-OLD", OLD, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    r1 = s26.run26("R1-step-BCD", BCD, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    r2 = s26.run26("R2-step-TB", TB, worldA, 1.35,
                   s22.LAG22_A, film='A', dub_speed=1.5,
                   lag_step_ms=2000, **s29.spec29())
    ok0 = s29.recovery_gate29("R0 OLD", r0[0])
    ok1 = s29.recovery_gate29("R1 BCD", r1[0])
    ok2 = s29.recovery_gate29("R2 TB", r2[0])
    print("RECOVERY34: OLD %s / BCD %s / TB %s"
          % ("PASS" if ok0 else "FAIL",
             "PASS" if ok1 else "FAIL",
             "PASS" if ok2 else "FAIL"))
    print()
    print("--- seeds 29/30 filmA r=1.35 ---")
    for seed in (20261129, 20261130):
        wA = s29b.build_world_seed(seed, **s22.WORLD22)
        _run("BCD-s%d-filmA-r1.35" % seed, BCD, wA, 1.35,
             s22.LAG22_A, film='A')
        _run("TB-s%d-filmA-r1.35" % seed, TB, wA, 1.35,
             s22.LAG22_A, film='A')
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
