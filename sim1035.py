#!/usr/bin/env python3
"""sim1035.py — FINAL GATE for the ship candidate TB
(sim1034.NewTracker34BCD_TB: cap 1.35 + Fix B (D up-slew
+250/sample) + Fix C (hold clamped to margin + decay every
burst) + Fix D (valve: median of 3 COMPLETED true end-bursts
+ hold gate, OLD strength 2000/1500/500) + TB (trial budget:
D moves at most +-500 ms during a trial). NO Fix A, NO A3,
NO D2 — all three rejected (see sim1033/sim1034 notes).

Why TB (not ABCD): Fix A breaks genuine step recovery
(ABCD never recovers from +2000 ms; TB recovers like Old).
The wrong-trial damage is bounded by B+C instead: the r=1.40
explosion is fixed (357 ms) with hold clamped, and TB bounds
the transient video-delay (D) excursion to +500 ms during a
trial (D max 5510 vs Old 6758).

Gate bars: end-p90t60 < 1000 ms, drop <= 2%, valve drop <=
Old, step recovery PASS (<= 5 min), regressions (regone34)
no worse than v1.0.22 baseline (v6 >= 1/3, sim1018 >= 2/3,
drop NEW <= OLD).
Known limitation (reported, not hidden): seeds 29/30
long-turn worlds fail end-p90 (~2.5-3.3 s) for EVERY config
including Old — the Sa2 SEED is +1.8-1.9 s wrong there (seed
6367/6482 vs true 4452) and no trial/valve/hold fix can
correct a wrong seed. TB is ~0.3-0.5 s worse than ABCD there
(one wrong adoption), ~0.3 s worse than Old. The seed fix is
a separate work item (multi-hypothesis seeding); it is NOT
this round's scope. The user's primary worlds (seed 28) pass
fully.

Sections: G main gate seed 28 (r=1.35 + lag 7.8), S r
sensitivity seed 28 (1.40/1.45), M multi-seed 29/30, R step
recovery. Regressions separately: regone34.py k=0/1/2.

Usage: python3 sim1035.py [G|S|M|R|all] (default all)
Output: sim1035_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1029 as s29
import sim1029b as s29b
import sim1034 as s34


def _run(label, cls, world, ratio, lag0, film='A', **extra):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **dict(s29.spec29(), **extra))
    tr = s26._TRACKER26_LAST[0]
    d = d26 or {}
    print("35 %-26s endp90t60=%6.0f drop=%5.1f%% valve=%3d/%6d"
          " ms holdMax=%5.0f pairs=%4.1f smpl=%5.1f sbT=%d/%d"
          % (label, d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_hold_max31_post', -1),
             d.get('pairs_mean_win') or -1,
             d.get('samples_mean_win') or -1,
             getattr(tr, '_sb_trials', -1),
             getattr(tr, '_sb_adopts', -1)))
    return res, d26


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'all'
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1035 FINAL GATE: TB cap 1.35 (B+C+D+TB, no A).")
    print()
    TB = s34.NewTracker34BCD_TB
    OLD = s29.NewTracker29Cap135OldValve
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    if mode in ('all', 'G'):
        print("--- G main gate, seed 28, r=1.35 ---")
        _run("G-filmA-135-lag46", TB, worldA, 1.35,
             s22.LAG22_A, film='A')
        _run("G-dense-135-lag46", TB, worldB, 1.35,
             s22.LAG22_A, film='B')
        _run("G-choppy-135-lag45", TB, worldC, 1.35, 4500,
             film='C')
        _run("G-filmA-135-lag78", TB, worldA, 1.35,
             s22.LAG22_B, film='A')
    if mode in ('all', 'S'):
        print("--- S r sensitivity, seed 28 ---")
        for r in (1.40, 1.45):
            _run("S-filmA-r%.2f" % r, TB, worldA, r,
                 s22.LAG22_A, film='A')
            _run("S-dense-r%.2f" % r, TB, worldB, r,
                 s22.LAG22_A, film='B')
            _run("S-choppy-r%.2f" % r, TB, worldC, r, 4500,
                 film='C')
    if mode in ('all', 'M'):
        print("--- M multi-seed (29 + 30) ---")
        for seed in (20261129, 20261130):
            wA = s29b.build_world_seed(seed, **s22.WORLD22)
            wB = s29b.build_world_seed(seed,
                                       **s22.WORLD22_DENSE)
            for r in (1.35, 1.40):
                _run("M-s%d-filmA-r%.2f" % (seed, r), TB,
                     wA, r, s22.LAG22_A, film='A')
                _run("M-s%d-dense-r%.2f" % (seed, r), TB,
                     wB, r, s22.LAG22_A, film='B')
            _run("M-s%d-filmA-OLD-r1.40" % seed, OLD, wA, 1.40,
                 s22.LAG22_A, film='A')
            _run("M-s%d-dense-OLD-r1.40" % seed, OLD, wB, 1.40,
                 s22.LAG22_A, film='B')
    if mode in ('all', 'R'):
        print("--- R lag-step recovery (+2000 ms at t=120 s) ---")
        r0 = s26.run26("R0-step-OLD", OLD, worldA, 1.35,
                       s22.LAG22_A, film='A', dub_speed=1.5,
                       lag_step_ms=2000, **s29.spec29())
        r1 = s26.run26("R1-step-TB", TB, worldA, 1.35,
                       s22.LAG22_A, film='A', dub_speed=1.5,
                       lag_step_ms=2000, **s29.spec29())
        ok0 = s29.recovery_gate29("R0 OLD", r0[0])
        ok1 = s29.recovery_gate29("R1 TB", r1[0])
        print("RECOVERY35: OLD %s / TB %s"
              % ("PASS" if ok0 else "FAIL",
                 "PASS" if ok1 else "FAIL"))
        print()
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
