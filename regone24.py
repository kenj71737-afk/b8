#!/usr/bin/env python3
"""Clean single regression measurement for the round-3 final
spec (BaSent+Bb, Rb+Rc2+Rf1, valve per argv). ONE reg24 call
per fresh process, global RNG seeded explicitly first, so OLD
and NEW share the same v6/sim1018 worlds (the in-process
reg24 calls in sim1024.main reuse a cached sim1018 OLD from
an earlier world — cross-world, not comparable)."""
import random
import sys
sys.path.insert(0, '.')
import sim1024 as s24

k = int(sys.argv[1])
tail = sys.argv[2] == 'on'
random.seed(20261127 + k)
s24.set24(boot=('BaSent', 'Bb'), bc_n=3)
r6, r18, d18, d18o = s24.reg24(
    s24.best24_kw(tail_valve=tail),
    "FINAL-BaSent+Bb-valve%s-seed%d" % ('ON' if tail else 'OFF', k))
print("REGONE k=%d valve=%s: v6=%s sim1018=%s droppedNEW=%.1f%%"
      " droppedOLD=%.1f%%"
      % (k, 'ON' if tail else 'OFF', r6, r18, d18 * 100, d18o * 100))
