#!/usr/bin/env python3
"""sim1029d.py — VONG B last checks: OLD valve at lag7.8 r=1.35
(is the B2 drop failure valve-caused?) and B tracker at r=1.25
(does a stronger prompt rescue the config? filmA + dense +
lag7.8 + choppy)."""
import sys, time
sys.path.insert(0, '.')
import sim1026 as s26
import sim1022 as s22
import sim1029 as s29
import sim1029b as s29b

t0 = time.time()
s26.s20.GATE_SPEECH_P = 0.70
s26.s20.check_clock_fix()
worldC = s26._build_world26()
worldA = s22._build_world(**s22.WORLD22)
worldB = s22._build_world(**s22.WORLD22_DENSE)
kw = s29.spec29()
print("#### OLD valve (dead) at lag7.8, r=1.35 ####")
s29b.run29b("D-oldV-filmA r1.35 lag7.8", s29.NewTracker29Cap135OldValve,
            worldA, 1.35, s22.LAG22_B, film='A', **kw)
print("#### B tracker at r=1.25 (stronger prompt) ####")
for lbl, w, lag, film in (("filmA", worldA, s22.LAG22_A, 'A'),
                          ("dense", worldB, s22.LAG22_A, 'B'),
                          ("choppy", worldC, 4500, 'C')):
    s29b.run29b("D-B-%s r1.25" % lbl, s29.NewTracker29B,
                w, 1.25, lag, film=film, **kw)
s29b.run29b("D-B-filmA r1.25 lag7.8", s29.NewTracker29B,
            worldA, 1.25, s22.LAG22_B, film='A', **kw)
print("TOTAL runtime %.1f s" % (time.time() - t0))
