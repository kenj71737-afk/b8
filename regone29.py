#!/usr/bin/env python3
"""regone29.py — v1.0.24 VONG B regression (v6 + sim1018) for the
SHIP variant: sim1029.NewTracker29B (cap 1.35x + STRONG valve
1000/500/2000 no speed gate + skip-ahead), winner spec sa2sb3,
valve ON.

Mirrors regone27.py exactly, except the NEW tracker class is
monkeypatched to the B variant before s25.reg25 runs (reg25
looks up s25.NewTracker25 as a module global for the NEW runs;
set25 class attributes set on the parent propagate to the
subclass by inheritance; the valve thresh/keep/cap live on the
B class itself). ONE reg call per fresh process, RNG seeded
20261128 + k, so OLD and NEW share the same v6/sim1018 worlds.

Usage: regone29.py <k>   (k = 0, 1, 2)
Baseline v1.0.22 (regone25, sa2sb3, valve ON): v6 1/3, sim1018
2/3 (NEW <= OLD same seed). v1.0.23 (regone27, cap125V): v6 1/3,
sim1018 0/3 (dropped audio 25-29% vs OLD 8-10%).
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25
import sim1029 as s29

k = int(sys.argv[1])
s25.NewTracker25 = s29.NewTracker29B
random.seed(20261128 + k)
s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
          sb_drift_gate=False)
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=True),
    "cap135B-valveON-seed%d" % k)
print("REGONE29 k=%d ship-cap135B valve=ON: v6=%s sim1018=%s"
      " droppedNEW=%.1f%% droppedOLD=%.1f%%"
      % (k, r6, r18, d18 * 100, d18o * 100))
