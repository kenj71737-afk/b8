#!/usr/bin/env python3
"""sim1029c.py — VONG B control: does the v1.0.22 BASELINE also fail
on the seed-20261129 worlds where config B blew up?

Q2b. Baseline = s25.NewTracker25, winner spec sa2sb3,
valve V2000cap500 (OLD), cap 1.5x — the shipped v1.0.22 — on
seed-29 filmA + dense at its native ratio r=1.42 and at r=1.35.
If the baseline ALSO fails on seed 29 (anchor error ~seconds,
D pinned, end-p90 seconds), then seed 29 exposes an
ARCHITECTURE fragility (long turns -> wrong-mode fixed point),
not a config-B-specific failure. If the baseline PASSES on
seed 29, then cap 1.35 itself breaks the anchor there.

Also: cap1.35 + OLD valve (dead) on seed 29 at r=1.35 — separates
cap from STRONG valve on the new seed.

Usage: python3 sim1029c.py
Output: sim1029c_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1029 as s29
import sim1029b as s29b


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1029c VONG B control: v1.0.22 BASELINE on seed-29"
          " worlds (where B blew up at r=1.35).")
    print()
    worldA29 = s29b.build_world_seed(20261129, **s22.WORLD22)
    worldB29 = s29b.build_world_seed(20261129, **s22.WORLD22_DENSE)
    s26.world_stats26("filmA seed29", worldA29)
    s26.world_stats26("dense seed29", worldB29)
    print()
    kw = s29.spec29()  # winner spec sa2sb3 (valve params live on
    # the tracker classes; baseline NewTracker25 gets OLD
    # V2000cap500 from set25 defaults via s24/s23)

    print("#### BASELINE v1.0.22 (cap1.5, OLD valve) on seed 29 ####")
    s29b.run29b("C-base-filmA-seed29 r1.42", s25.NewTracker25,
                worldA29, 1.42, s22.LAG22_A, film='A', **kw)
    s29b.run29b("C-base-dense-seed29 r1.42", s25.NewTracker25,
                worldB29, 1.42, s22.LAG22_A, film='B', **kw)
    s29b.run29b("C-base-filmA-seed29 r1.35", s25.NewTracker25,
                worldA29, 1.35, s22.LAG22_A, film='A', **kw)
    s29b.run29b("C-base-dense-seed29 r1.35", s25.NewTracker25,
                worldB29, 1.35, s22.LAG22_A, film='B', **kw)

    print("#### cap1.35 + OLD valve (dead) on seed 29, r=1.35 ####")
    s29b.run29b("C-oldV-filmA-seed29 r1.35",
                s29.NewTracker29Cap135OldValve,
                worldA29, 1.35, s22.LAG22_A, film='A', **kw)
    s29b.run29b("C-oldV-dense-seed29 r1.35",
                s29.NewTracker29Cap135OldValve,
                worldB29, 1.35, s22.LAG22_A, film='B', **kw)

    print("#### BASELINE on seed 28 (sanity: must reproduce"
          " sim1025 winner [501/726/420/252]) ####")
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    s29b.run29b("C-base-filmA-seed28 r1.42", s25.NewTracker25,
                worldA, 1.42, s22.LAG22_A, film='A', **kw)
    s29b.run29b("C-base-dense-seed28 r1.42", s25.NewTracker25,
                worldB, 1.42, s22.LAG22_A, film='B', **kw)
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
