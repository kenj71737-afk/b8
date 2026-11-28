#!/usr/bin/env python3
"""regone34nv.py — v1.0.24 regression (v6 + sim1018) for the SHIP
tracker: sim1034.NewTracker34BCD_TB (cap 1.35x + fixes B/C/D +
trial budget TB, NO Fix A — Fix A breaks step recovery, see
sim1033/sim1034 notes; valve trigger fixed, OLD strength).

Mirrors regone29.py exactly, except the NEW tracker class is
the TB variant. ONE reg call per fresh process, RNG seeded
20261128 + k, so OLD and NEW share the same v6/sim1018 worlds.

Usage: regone34nv.py <k>   (k = 0, 1, 2)
Baselines: v1.0.22 (regone25): v6 1/3, sim1018 2/3 (NEW <= OLD
same seed). Config B (regone29): v6 1/3, sim1018 0/3 (drop
25.7/18.5/27.9% vs OLD 8.3/7.7/10.3%).
Gate: no regression vs v1.0.22 baseline — v6 >= 1/3 AND
sim1018 >= 2/3 AND drop NEW <= OLD + small margin.
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25
import sim1029 as s29
import sim1034 as s31

k = int(sys.argv[1])
s25.NewTracker25 = s31.NewTracker34BCD_TB
random.seed(20261128 + k)
s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
          sb_drift_gate=False)
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=False),
    "cap135ABCD-valveON-seed%d" % k)
print("REGONE34NV k=%d ABCD: v6=%s sim1018=%s droppedNEW=%.1f%%"
      " droppedOLD=%.1f%%" % (k, r6, r18, d18 * 100,
                              d18o * 100))
