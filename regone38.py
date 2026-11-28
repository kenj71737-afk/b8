#!/usr/bin/env python3
"""regone38.py — CAP CONTROL: the pure v1.0.22 winner + cap 1.35
only (s29.NewTracker29Cap135OldValve = winner with _ratio_speed
/_ratio_floor capped at 1.35 + OLD valve, NO fixes A/B/C/D/TB).
If this drops ~16% like every fix variant, the sim1018 drop
doubling is a CAP effect (r in that world exceeds 1.35), NOT a
fix regression. Usage: regone38.py <k> (k=0/1/2).
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25
import sim1029 as s29

k = int(sys.argv[1])
s25.NewTracker25 = s29.NewTracker29Cap135OldValve
random.seed(20261128 + k)
s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
          sb_drift_gate=False)
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=True),
    "cap135OLDVALVE-valveON-seed%d" % k)
print("REGONE38 k=%d: v6=%s sim1018=%s droppedNEW=%.1f%%"
      " droppedOLD=%.1f%%" % (k, r6, r18, d18 * 100,
                              d18o * 100))
