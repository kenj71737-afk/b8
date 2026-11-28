#!/usr/bin/env python3
"""regone37.py — regression ablation for sim1037 classes.
Usage: regone37.py <mB|mC3|mTB|C3> <k>   (k = 0, 1, 2)
Mirrors regone34.py exactly, only the NEW tracker class
changes. Prints REGONE37 <cls> k=<k>: v6 sim1018 dropNEW/OLD.
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25
import sim1037 as s37

cls_name = sys.argv[1]
k = int(sys.argv[2])
s25.NewTracker25 = s37.CLASSES[cls_name]
random.seed(20261128 + k)
s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
          sb_drift_gate=False)
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=True),
    "cap135%s-valveON-seed%d" % (cls_name, k))
print("REGONE37 %s k=%d: v6=%s sim1018=%s droppedNEW=%.1f%%"
      " droppedOLD=%.1f%%" % (cls_name, k, r6, r18, d18 * 100,
                              d18o * 100))
