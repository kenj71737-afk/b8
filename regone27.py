#!/usr/bin/env python3
"""regone27.py — v1.0.23 regression (v6 + sim1018) for the SHIP
variant: sim1026.NewTracker26Cap125Valve (cap 1.25x + valve
without speed gate), winner spec sa2sb3, valve ON.

Mirrors regone25.py exactly, except the NEW tracker class is
monkeypatched to the ship variant before s25.reg25 runs (reg25
looks up s25.NewTracker25 as a module global for the NEW runs;
set25 class attributes set on the parent propagate to the
subclass by inheritance). ONE reg call per fresh process, RNG
seeded 20261128 + k, so OLD and NEW share the same v6/sim1018
worlds.

Usage: regone27.py <k>   (k = 0, 1, 2)
Baseline v1.0.22 (regone25, sa2sb3, valve ON): v6 1/3, sim1018
2/3 (NEW <= OLD same seed). v1.0.23 must not fall below that.
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25
import sim1026 as s26

k = int(sys.argv[1])
s25.NewTracker25 = s26.NewTracker26Cap125Valve
random.seed(20261128 + k)
s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
          sb_drift_gate=False)
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=True),
    "cap125V-valveON-seed%d" % k)
print("REGONE27 k=%d ship-cap125V valve=ON: v6=%s sim1018=%s"
      " droppedNEW=%.1f%% droppedOLD=%.1f%%"
      % (k, r6, r18, d18 * 100, d18o * 100))
