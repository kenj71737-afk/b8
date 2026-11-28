#!/usr/bin/env python3
"""sim1029b.py — VONG B follow-up: isolate the r=1.40 blow-up.

Questions left open by sim1029.py:
  Q1. At r=1.40 the STRONG valve fires 41x / drops 46.8 s and
      end-p90 is STILL 1928 ms (filmA). Is that the CAP (1.35 <
      1.40 -> chronic deficit) or the VALVE (firing on noise,
      drops corrupting the anchor/D loop -> runaway)?
      Test: cap1.35 + OLD valve (effectively dead: speed gate
      never fires at 1.35) at r=1.40 filmA + dense. If OLD is
      fine, the STRONG valve is actively harmful; if OLD is
      also bad, the cap itself cannot handle r=1.40.
  Q2. Is the r=1.40 blow-up reproducible, or one unlucky jitter
      stream? Test: B tracker at r=1.40 filmA + dense on a
      different world seed (20261129). Same for r=1.35 (control:
      B must stay good on the new seed too).
  Q3. If the STRONG valve is the problem but SOME valve is
      needed, does a gentler MEDIUM valve (thresh 1500 / keep
      1000 / cap 1000, no speed gate) keep r=1.35 performance
      without the r=1.40 blow-up and the lag7.8 over-drop?
      Test: MEDIUM at r=1.35 + r=1.40 (filmA + dense) and
      r=1.35 lag7.8.

Worlds are rebuilt with an explicit seed (default 20261128 =
the sim1026/1027/1029 worlds; 20261129 = fresh world).
Usage: python3 sim1029b.py
Output: sim1029b_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1021 as s21
import sim1029 as s29


class NewTracker29Medium(s29.NewTracker29B):
    """MEDIUM valve: thresh 1500 / keep 1000 / cap 1000 per fire,
    no speed gate + same skip-ahead as B."""
    VALVE_THRESH_MS = 1500
    VALVE_KEEP_MS = 1000
    VALVE_MAX_DROP_MS = 1000


def build_world_seed(seed, **w):
    frames, ls, lu = s22.build_world22(seed=seed, **w)
    vad, sent, vs = s21.run_vad21(frames, start_need=4, start_tol=2,
                                  total_ms=s22.LONG_MS)
    txin, txsrc = s21.build_tx21(frames, sent, ls, lu)
    return (frames, ls, lu, vad, sent, vs, txin, txsrc)


def run29b(label, cls, world, ratio, lag0, film='C', **kw):
    res, d = s26.run26(label, cls, world, ratio, lag0, film=film,
                       dub_speed=1.5, **kw)
    tr = s26._TRACKER26_LAST[0]
    sk = (getattr(tr, 'skip_fires', None),
          getattr(tr, 'skip_ms_total', None)) if tr else (None, None)
    sp = list(getattr(tr, 'speed_used', [])) if tr else []
    print("SUM29B %-34s end90t60 %6.0f  drop %4.1f%%  valve %5d ms /"
          " %2d fires  skip %s ms / %s  speedMax %.2f  pairsMin %d"
          "  Dmax %.0f"
          % (label, (d or {}).get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             (d or {}).get('valve_ms_total') or 0,
             (d or {}).get('valve_fires') or 0,
             sk[1], sk[0], max(sp) if sp else -1,
             min((d or {}).get('pairs_win') or [0]),
             res.get('d_max') or -1))
    print()
    return res, d


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1029b VONG B follow-up: Q1 cap-vs-valve at r=1.40;"
          " Q2 seed-robustness of the r=1.40 blow-up;"
          " Q3 MEDIUM valve (1500/1000/1000).")
    print()
    worldC = s26._build_world26()
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldA29 = build_world_seed(20261129, **s22.WORLD22)
    worldB29 = build_world_seed(20261129, **s22.WORLD22_DENSE)
    kw = s29.spec29()

    print("#### Q1: OLD valve (dead) at r=1.40 — cap or valve? ####")
    run29b("Q1-oldV-filmA r1.40", s29.NewTracker29Cap135OldValve,
           worldA, 1.40, s22.LAG22_A, film='A', **kw)
    run29b("Q1-oldV-dense r1.40", s29.NewTracker29Cap135OldValve,
           worldB, 1.40, s22.LAG22_A, film='B', **kw)
    run29b("Q1-oldV-filmA r1.47", s29.NewTracker29Cap135OldValve,
           worldA, 1.47, s22.LAG22_A, film='A', **kw)
    run29b("Q1-oldV-dense r1.47", s29.NewTracker29Cap135OldValve,
           worldB, 1.47, s22.LAG22_A, film='B', **kw)

    print("#### Q2: B tracker at r=1.40 / r=1.35 on seed 20261129 ####")
    run29b("Q2-B-filmA-seed29 r1.40", s29.NewTracker29B,
           worldA29, 1.40, s22.LAG22_A, film='A', **kw)
    run29b("Q2-B-dense-seed29 r1.40", s29.NewTracker29B,
           worldB29, 1.40, s22.LAG22_A, film='B', **kw)
    run29b("Q2-B-filmA-seed29 r1.35", s29.NewTracker29B,
           worldA29, 1.35, s22.LAG22_A, film='A', **kw)
    run29b("Q2-B-dense-seed29 r1.35", s29.NewTracker29B,
           worldB29, 1.35, s22.LAG22_A, film='B', **kw)

    print("#### Q3: MEDIUM valve (1500/1000/1000) ####")
    run29b("Q3-med-filmA r1.35", NewTracker29Medium,
           worldA, 1.35, s22.LAG22_A, film='A', **kw)
    run29b("Q3-med-dense r1.35", NewTracker29Medium,
           worldB, 1.35, s22.LAG22_A, film='B', **kw)
    run29b("Q3-med-filmA r1.40", NewTracker29Medium,
           worldA, 1.40, s22.LAG22_A, film='A', **kw)
    run29b("Q3-med-dense r1.40", NewTracker29Medium,
           worldB, 1.40, s22.LAG22_A, film='B', **kw)
    run29b("Q3-med-filmA r1.35 lag7.8", NewTracker29Medium,
           worldA, 1.35, s22.LAG22_B, film='A', **kw)
    run29b("Q3-med-choppy r1.40", NewTracker29Medium,
           worldC, 1.40, 4500, film='C', **kw)
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
