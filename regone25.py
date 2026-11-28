#!/usr/bin/env python3
"""Clean single regression measurement for the round-4 winner
spec. ONE reg25 call per fresh process, global RNG seeded
explicitly first, so OLD and NEW share the same v6/sim1018
worlds (the in-process reg calls in sim1024.main reuse a cached
sim1018 OLD from an earlier world — cross-world, not
comparable).

Usage: regone25.py <k> <valve on|off> <spec>
  k     : seed index (0, 1, 2, ...); RNG seeded 20261128 + k.
  valve : 'on' = V2000cap500, 'off' = valve-OFF.
  spec  : winner spec key — one of:
            final    = BaSent+Bb (round-3 FINAL, no S-a/S-b)
            sa2      = Sa2-replace (Bb + Sa2, no BaSent)
            sa2stack = BaSent+Bb + Sa2 stacked
            sa2sb3   = Sa2-replace + Sb N=3
            sa2stacksb3 = BaSent+Bb + Sa2 stacked + Sb N=3
            sb3      = BaSent+Bb + Sb N=3
  The spec's 4-world numbers live in sim1025_final_run.txt;
  this file only measures the two regressions, cleanly.
"""
import random
import sys
sys.path.insert(0, '.')
import sim1025 as s25

SPECS = {
    'final': dict(boot=('BaSent', 'Bb'), seed25=(), sb_on=False),
    'sa2': dict(boot=('Bb',), seed25=('Sa2',), sb_on=False),
    'sa2stack': dict(boot=('BaSent', 'Bb'), seed25=('Sa2',),
                     sb_on=False),
    'sa2sb3': dict(boot=('Bb',), seed25=('Sa2',), sb_on=True,
                   sb_n=3),
    'sa2stacksb3': dict(boot=('BaSent', 'Bb'), seed25=('Sa2',),
                        sb_on=True, sb_n=3),
    'sb3': dict(boot=('BaSent', 'Bb'), seed25=(), sb_on=True,
                sb_n=3),
}

k = int(sys.argv[1])
tail = sys.argv[2] == 'on'
spec = sys.argv[3] if len(sys.argv) > 3 else 'sa2'
random.seed(20261128 + k)
s25.set25(**SPECS[spec])
r6, r18, d18, d18o = s25.reg25(
    s25.best25_kw(tail_valve=tail),
    "%s-valve%s-seed%d" % (spec, 'ON' if tail else 'OFF', k))
print("REGONE25 k=%d valve=%s spec=%s: v6=%s sim1018=%s"
      " droppedNEW=%.1f%% droppedOLD=%.1f%%"
      % (k, 'ON' if tail else 'OFF', spec, r6, r18,
         d18 * 100, d18o * 100))
