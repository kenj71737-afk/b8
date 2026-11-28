#!/usr/bin/env python3
"""sim1036.py — Fix C3: symmetric hold slew, replaces C1+C2.

Why C1+C2 must go (proven by regone34 vs regone34nv):
the valve is NOT the regression cause (valve OFF: drop
15.9/16.0/19.0% vs valve ON 16.0/16.2/19.0% — identical).
The extra ~8% drop in the sim1018 regression world comes
from Fix C itself:
  - C1 (hard clamp hold <= dynamic_margin() ~1000 ms) and
  - C2 (decay-only on no-marker bursts: hold never rises
    without a marker)
  In a cap-marginal world (sim1018: OLD line-start up to
  2348 ms, OLD itself drops 7.7-10.3%) the GENUINE hold
  needs to be ~2 s (backlog within turns). C1/C2 keep hold
  at ~1000 ms -> due = arrival + hold is too early -> the
  player takes chunks >700 ms past due -> STALE drops.
  Hold is a lateness-vs-drop tradeoff knob: Old's large hold
  buys drop protection; C1/C2 buy lateness at the cost of
  words. In the sim1018 world that trade loses ~8% audio.

Fix C3 (symmetric slew, no clamp, no decay-only):
on EVERY burst, hold moves toward robust = D - lag_est -
pipe by at most +-300 ms:
    hold = min(prev + 300, max(prev - 300, robust))
  - Explosion (sim1030): marker burst robust 2399,
    prev 562 -> hold 862 (same as Fix A/C result, no jack).
  - sim1018 world: hold CAN rise to the genuine ~2 s,
    at +300/burst (~17 s to rise 1500 ms) -> no clamp,
    no extra stale drops.
  - Burst-3 transient (robust 1566-1988, no marker):
    hold rises at most +300 -> bounded, harmless.
This replaces BOTH the original marker slew (unbounded
rise, the jack path) AND C1+C2.

Tracker: NewTracker36BC3DTB = B (D up-slew +250/sample)
+ C3 + D (valve: median of 3 completed TRUE end-bursts +
hold gate, OLD strength) + TB (trial budget +-500 ms).
NO Fix A (breaks step recovery), NO C1/C2.

Note on the valve hold-gate ordering: ABCD's valve check
reads hold after AC's C1/C2 clamp (always <= margin), so
the gate never blocks in this chain. The valve's own cost
was measured as ~0 in the regression (valve OFF == valve
ON), so this is a minor wart, not a driver. If the
regression still fails on drop with C3, the next ablation
is B (D up-slew).

Sections: G main gate seed 28 (r=1.35 + lag 7.8), S r
sensitivity seed 28 (1.40/1.45), M multi-seed 29/30, R step
recovery. Regressions: regone36.py k=0/1/2 (valve ON) and
regone36nv.py (valve OFF, control).

Usage: python3 sim1036.py [G|S|M|R|all] (default all)
Output: sim1036_run.txt
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


class NewTracker36BC3DTB(s34.NewTracker34BCD_TB):
    """B + C3 + D + TB. C3 overrides AC's C1+C2 hold logic
    with a symmetric +-300 ms/burst slew toward robust."""

    def on_dub_burst(self, now, true_lag, true_sample=None):
        prev_hold = self.hold
        super().on_dub_burst(now, true_lag, true_sample)
        # Fix C3: symmetric slew. The chain above already ran
        # the original marker slew + AC's C1/C2; overwrite its
        # result with the slew from prev_hold toward robust.
        robust = max(0, self.delay - self.lag_est(now)
                     - self.pipeline_ms())
        self.hold = min(prev_hold + 300,
                        max(prev_hold - 300, robust))
        if self.hold > getattr(self, '_hold_max36', 0):
            self._hold_max36 = self.hold


def _run(label, cls, world, ratio, lag0, film='A', **extra):
    res, d26 = s26.run26(label, cls, world, ratio, lag0,
                         film=film, dub_speed=1.5,
                         **dict(s29.spec29(), **extra))
    tr = s26._TRACKER26_LAST[0]
    d = d26 or {}
    print("36 %-26s endp90t60=%6.0f drop=%5.1f%% valve=%3d/%6d"
          " ms holdMax=%5.0f pairs=%4.1f smpl=%5.1f sbT=%d/%d"
          % (label, d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             tr.tail_valve_fires, tr.tail_valve_drop_ms,
             getattr(tr, '_hold_max36', -1),
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
    print("sim1036 GATE: B+C3+D+TB (valve ON), no A, no C1/C2.")
    print()
    TB = NewTracker36BC3DTB
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
    if mode in ('all', 'R'):
        print("--- R lag-step recovery (+2000 ms at t=120 s) ---")
        r0 = s26.run26("R0-step-OLD", OLD, worldA, 1.35,
                       s22.LAG22_A, film='A', dub_speed=1.5,
                       lag_step_ms=2000, **s29.spec29())
        r1 = s26.run26("R1-step-C3", TB, worldA, 1.35,
                       s22.LAG22_A, film='A', dub_speed=1.5,
                       lag_step_ms=2000, **s29.spec29())
        ok0 = s29.recovery_gate29("R0 OLD", r0[0])
        ok1 = s29.recovery_gate29("R1 C3", r1[0])
        print("RECOVERY36: OLD %s / C3 %s"
              % ("PASS" if ok0 else "FAIL",
                 "PASS" if ok1 else "FAIL"))
        print()
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
