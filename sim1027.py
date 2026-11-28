#!/usr/bin/env python3
"""sim1027.py — DubLive v1.0.23 SHIP GATE.

Ship variant = sim1026.NewTracker26Cap125Valve: stretch ceiling
1.5 -> 1.25 (MAX_STRETCH_SPEED) + tail valve with the speed gate
REMOVED (arm on endLag > 2000 ms alone, keep 1500 ms, cap 500
ms/fire, max 1 arm/burst). Sa2 + Sb3 + Rb/Rc2/Rf1 + D cap 8 s
unchanged (winner spec sa2sb3 from sim1026).

Worlds (10-min sessions, exact final spec):
  S1 filmA r1.42 lag4.6   S2 filmA r1.51 lag4.6
  S3 dense r1.42 lag4.6   S4 filmA r1.42 lag7.8
  S5 choppy r1.47 lag4.5
  R1 filmA r1.42 lag4.6 + lag step +2000 ms at t=120 s (recovery)
  R0 same step at the 1.5x baseline (reference only)

GATE23 (v1.0.23):
  end-burst p90 TRUE t>60 s < 1000 ms; dropped audio steady < 2%;
  pairs > 0 in EVERY 60 s window; samples/60 s >= 40 (min window);
  D pinned 0% steady (except lag 7.8 s: D max <= 8000, pinning is
  the known 8 s-cap artifact); recovery: after the +2000 ms step
  at t=120 s, per-minute end-burst MEDIAN back < 500 ms within
  <= 5 min (sim ~4 min at 1.25x).

Usage: python3 sim1027.py [ship|step|all]  (default all)
Output: sim1027_ship_run.txt / sim1027_step_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22

SHIP = s26.NewTracker26Cap125Valve


def gate23(label, res):
    d = res.get('diag26') or {}
    end90 = d.get('end_p90_t60')
    drop = res.get('drop_steady')
    pairs = d.get('pairs_win') or []
    samps = d.get('samples_win') or []
    pinned = res.get('d_pinned_steady')
    dmax = res.get('d_max')
    checks = {
        'end-p90 TRUE t>60s < 1000 ms': end90 is not None
        and end90 < 1000,
        'dropped audio steady < 2%': drop is not None and drop < 2.0,
        'pairs > 0 every 60 s window': bool(pairs)
        and min(pairs) > 0,
        'samples/60s >= 40 (min window)': bool(samps)
        and min(samps) >= 40,
        'D pinned 0% steady (t>60s)': pinned == 0,
        'D max <= 8000': dmax is not None and dmax <= 8000,
    }
    print("GATE27-SHIP %s:" % label)
    for k, v in checks.items():
        print("  %-38s %s" % (k, "PASS" if v else "FAIL"))
    ok = all(checks.values())
    print("  GATE27-SHIP: %s" % ("PASS" if ok else "FAIL"))
    print("  (end-p90 t60 %.0f ms, drop %.1f%%, pairs min %d,"
          " samples min %d, valve %d ms / %d fires, D pinned"
          " steady %.0f%%, D max %.0f)"
          % (end90 or -1, drop if drop is not None else -1,
             min(pairs) if pairs else -1,
             min(samps) if samps else -1,
             d.get('valve_ms_total') or 0,
             d.get('valve_fires') or 0,
             100.0 * pinned if pinned is not None else -1,
             dmax or -1))
    print()
    return ok


def recovery_gate(label, res, step_at_min=2, limit_min=5):
    """First minute AFTER the step whose end-burst median < 500
    ms, and how many minutes after the step that is."""
    d = res.get('diag26') or {}
    meds = d.get('end_med_min') or []
    first = None
    for m in range(step_at_min + 1, len(meds)):
        if meds[m] is not None and meds[m] < 500:
            first = m
            break
    delay = (first - step_at_min) if first is not None else None
    ok = delay is not None and delay <= limit_min
    print("RECOVERY-GATE %s: end-burst median by minute %s;"
          " step at minute %d; first median <500 ms at minute %s"
          " (%s min after step, limit <= %d min): %s"
          % (label, meds, step_at_min, first,
             delay if delay is not None else "never",
             limit_min, "PASS" if ok else "FAIL"))
    print()
    return ok


def main():
    t0 = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'all'
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1027 v1.0.23 SHIP GATE: cap 1.25x + valve no speed"
          " gate (Sa2+Sb3, Rb/Rc2/Rf1, D cap 8 s). 10-min sessions.")
    print()
    worldC = s26._build_world26()
    s26.world_stats26("choppy r1.47 lag4.5", worldC)
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    kw = s26.winner_spec26()
    results = {}
    if mode in ('all', 'ship'):
        print("#### SHIP: cap125 + valve-live, 5 worlds ####")
        results['S1'] = s26.run26(
            "S1-filmA-cap125V r1.42 lag4.6", SHIP,
            worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
            dub_speed=1.5, **kw)
        results['S2'] = s26.run26(
            "S2-filmA-cap125V r1.51 lag4.6", SHIP,
            worldA, s22.RATIO22_B, s22.LAG22_A, film='A',
            dub_speed=1.5, **kw)
        results['S3'] = s26.run26(
            "S3-dense-cap125V r1.42 lag4.6", SHIP,
            worldB, s22.RATIO22_A, s22.LAG22_A, film='B',
            dub_speed=1.5, **kw)
        results['S4'] = s26.run26(
            "S4-filmA-cap125V r1.42 lag7.8", SHIP,
            worldA, s22.RATIO22_A, s22.LAG22_B, film='A',
            dub_speed=1.5, **kw)
        results['S5'] = s26.run26(
            "S5-choppy-cap125V r1.47 lag4.5", SHIP,
            worldC, 1.47, 4500, film='C', dub_speed=1.5, **kw)
        print("#### GATE27-SHIP verdicts ####")
        allok = True
        for k in ('S1', 'S2', 'S3', 'S5'):
            allok &= gate23(k, results[k][0])
        # S4 lag 7.8: D pinning is the known 8 s-cap artifact;
        # judge it on everything except the pinned check.
        r4 = results['S4'][0]
        d4 = r4.get('diag26') or {}
        print("GATE27-SHIP S4 (lag 7.8 s — D-pin check WAIVED,"
              " known 8 s-cap artifact; D max must stay <= 8000):")
        print("  end-p90 t60 %.0f ms, drop %.1f%%, pairs min %d,"
              " samples min %d, valve %d ms, D max %.0f, D pinned"
              " steady %.0f%%"
              % (d4.get('end_p90_t60') or -1,
                 r4.get('drop_steady', -1),
                 min(d4.get('pairs_win') or [0]),
                 min(d4.get('samples_win') or [0]),
                 d4.get('valve_ms_total') or 0,
                 r4.get('d_max') or -1,
                 100.0 * (r4.get('d_pinned_steady') or 0)))
        s4ok = (d4.get('end_p90_t60') or 9999) < 1000 \
            and (r4.get('drop_steady') or 99) < 2.0 \
            and min(d4.get('pairs_win') or [0]) > 0 \
            and min(d4.get('samples_win') or [0]) >= 40 \
            and (r4.get('d_max') or 99999) <= 8000
        print("  GATE27-SHIP S4 (pin waived): %s"
              % ("PASS" if s4ok else "FAIL"))
        print()
        allok &= s4ok
        print("GATE27-SHIP OVERALL: %s"
              % ("PASS" if allok else "FAIL"))
        print()
    if mode in ('all', 'step'):
        print("#### RECOVERY: lag step +2000 ms at t=120 s ####")
        kw = s26.winner_spec26()
        r0 = s26.run26("R0-filmA-step2000-cap150 r1.42 lag4.6",
                       s25.NewTracker25,
                       worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
                       dub_speed=1.5, lag_step_ms=2000, **kw)
        r1 = s26.run26("R1-filmA-step2000-cap125V r1.42 lag4.6",
                       SHIP,
                       worldA, s22.RATIO22_A, s22.LAG22_A, film='A',
                       dub_speed=1.5, lag_step_ms=2000, **kw)
        ok0 = recovery_gate("R0 cap1.5 (reference)", r0[0])
        ok1 = recovery_gate("R1 cap1.25V (ship)", r1[0])
        print("RECOVERY-GATE OVERALL (ship R1): %s (R0 reference:"
              " %s)" % ("PASS" if ok1 else "FAIL",
                        "PASS" if ok0 else "FAIL"))
        print()
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
