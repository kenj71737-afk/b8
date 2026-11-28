#!/usr/bin/env python3
"""sim1032.py — v1.0.24 STEP-4 GATE for the fixed tracker
(sim1031.NewTracker31ABCD: cap 1.35 + fixes A/B/C/D + prompt
r=1.35). The ship configuration.

Gate (same bars as sim1029 GATE29):
  end-burst p90 TRUE steady < 1000 ms, drop audio steady <= 2%,
  stretch <= 1.35x, pairs/samples healthy every 60 s window,
  D pinned 0% steady (lag 7.8 waived: known 8 s-cap artifact).
Sections:
  G main gate seed 28: filmA/dense/choppy r=1.35 lag 4.6/4.5
    + filmA r=1.35 lag 7.8 (B2 failed here: drop 4.2% — valve).
  S r sensitivity seed 28: filmA/dense/choppy x r=1.40/1.45
    (r=1.35 in G). The r=1.40 explosion must be GONE, not
    dodged; r=1.45 must not newly fail.
  M multi-seed: seeds 29 + 30 (NEW) x filmA/dense x r=1.35/1.40.
    Seed 29 long-turn worlds broke the v1.0.22 baseline itself
    (3111/3382 ms) — ABCD must be no worse than Old on the
    same seed, and pass where Old passes.
  R lag-step recovery: filmA r=1.35 lag 4.6, +2000 ms at
    t=120 s. ABCD vs Old (cap-1.35 v1.0.22 logic). Fix B's
    +250/sample D up-slew must not slow step recovery past
    the 5-min limit. recovery_gate29 + drop comparison.
Regressions (v6/sim1018) run separately: regone31.py k=0/1/2.

Usage: python3 sim1032.py [G|S|M|R|all] (default all)
Output: sim1032_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1029 as s29
import sim1029b as s29b
import sim1031 as s31


def _run(label, cls, world, ratio, lag0, film='A', **extra):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **dict(s29.spec29(), **extra))
    tr = s26._TRACKER26_LAST[0]
    d = d26 or {}
    print("32 %-26s endp90t60=%6.0f drop=%5.1f%% valve=%3d/%6d"
          " ms Dmax=%5.0f holdMax=%5.0f pairs=%4.1f smpl=%5.1f"
          " sbT=%d/%d"
          % (label, d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_d_max31', -1),
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
    print("sim1032 STEP-4 GATE: ABCD cap 1.35 + prompt r=1.35"
          " (fixes A/B/C/D). Bars: end-p90t60 < 1000 ms,"
          " drop <= 2%, valve drop <= Old.")
    print()
    ABCD = s31.NewTracker31ABCD
    OLD = s29.NewTracker29Cap135OldValve
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    if mode in ('all', 'G'):
        print("--- G main gate, seed 28, r=1.35 ---")
        _run("G-filmA-135-lag46", ABCD, worldA, 1.35,
             s22.LAG22_A, film='A')
        _run("G-dense-135-lag46", ABCD, worldB, 1.35,
             s22.LAG22_A, film='B')
        _run("G-choppy-135-lag45", ABCD, worldC, 1.35, 4500,
             film='C')
        _run("G-filmA-135-lag78", ABCD, worldA, 1.35,
             s22.LAG22_B, film='A')
    if mode in ('all', 'S'):
        print("--- S r sensitivity, seed 28 ---")
        for r in (1.40, 1.45):
            _run("S-filmA-r%.2f" % r, ABCD, worldA, r,
                 s22.LAG22_A, film='A')
            _run("S-dense-r%.2f" % r, ABCD, worldB, r,
                 s22.LAG22_A, film='B')
            _run("S-choppy-r%.2f" % r, ABCD, worldC, r, 4500,
                 film='C')
    if mode in ('all', 'M'):
        print("--- M multi-seed (29 + 30 NEW) ---")
        for seed in (20261129, 20261130):
            wA = s29b.build_world_seed(seed, **s22.WORLD22)
            wB = s29b.build_world_seed(seed,
                                       **s22.WORLD22_DENSE)
            for r in (1.35, 1.40):
                _run("M-s%d-filmA-r%.2f" % (seed, r), ABCD,
                     wA, r, s22.LAG22_A, film='A')
                _run("M-s%d-dense-r%.2f" % (seed, r), ABCD,
                     wB, r, s22.LAG22_A, film='B')
            # Old baseline on the same seed/r for comparison
            _run("M-s%d-filmA-OLD-r1.40" % seed, OLD, wA, 1.40,
                 s22.LAG22_A, film='A')
            _run("M-s%d-dense-OLD-r1.40" % seed, OLD, wB, 1.40,
                 s22.LAG22_A, film='B')
    if mode in ('all', 'R'):
        print("--- R lag-step recovery (+2000 ms at t=120 s,"
              " filmA r=1.35) ---")
        r0 = s26.run26("R0-filmA-step2000-OLD r1.35 lag4.6",
                       OLD, worldA, 1.35, s22.LAG22_A, film='A',
                       dub_speed=1.5, lag_step_ms=2000,
                       **s29.spec29())
        r1 = s26.run26("R1-filmA-step2000-ABCD r1.35 lag4.6",
                       ABCD, worldA, 1.35, s22.LAG22_A,
                       film='A', dub_speed=1.5,
                       lag_step_ms=2000, **s29.spec29())
        ok0 = s29.recovery_gate29("R0 OLD cap1.35", r0[0])
        ok1 = s29.recovery_gate29("R1 ABCD cap1.35", r1[0])
        print("RECOVERY-GATE32 OVERALL (ABCD R1): %s (OLD R0:"
              " %s)" % ("PASS" if ok1 else "FAIL",
                        "PASS" if ok0 else "FAIL"))
        print()
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
