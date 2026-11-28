#!/usr/bin/env python3
"""sim1028.py — DubLive v1.0.23: FIXED-DELAY SCHEDULER (user proposal
28/11 12:36, corrected 12:40 — "Lay thua cung duoc de xem moi phim
deu co the moi khop voi giong").

IDEA (user): impose a FIXED delay L (video is held L seconds),
translate in that window, then play the Vietnamese voice exactly
at its video timestamp (sourceStart + L) — like downloading a
video, dubbing it offline, and playing it back lip-synced.
This REPLACES the adaptive-D tracker (Sa2/Sb3/Rb/Rc2/Rf1/valve
of v1.0.22): there is NO D to estimate, NO D-below-lag ratchet,
NO seed error — the D error term is ZERO BY CONSTRUCTION.

DESIGN (implemented here, event-driven per burst, NOT per chunk):
  * Burst = one translated line (build_dub21 per-line dub, reused
    verbatim from sim1021; worlds reused from sim1022 film A /
    dense + sim1026 choppy). For each burst:
      srcStart/srcEnd = source timeline of the line (from the
        dub chunks' own true_lag mapping: src_wall = arrival -
        true_lag, so no oracle beyond what the dub generator
        already records);
      dub_dur = sum of chunk steps (= fed speech * ratio);
      ready R = last chunk arrival = when the whole line's
        Vietnamese audio exists (translation lag + generation
        at 1.5x, serialised across lines by build_dub21).
  * Scheduler: scheduled play start S = srcStart + L.
    Actual start A = max(S, R, prev_end) — it cannot start
    before the audio exists, nor before the previous burst
    finished playing (single audio output = serial queue).
    PLAYBACK SPEED (corrected 28/11 after the first sweep):
    the dub must END at the line's video end, srcEnd + L —
    the pause after the line stays SILENT (as in the
    original audio). Target speed = dub_dur / (srcEnd+L - A),
    clamped to [1.0, cap]: at speed == ratio the dub ends
    exactly at the line end; at the cap it overruns by
    (ratio/cap - 1) x line length, spilling into the pause.
    (The first sweep instead filled the whole window to the
    NEXT burst's start — that maximised end-lateness by
    construction, playing voice over silence for the full
    pause. The end-at-line-end policy below replaces it;
    the first sweep's numbers are superseded and are NOT
    quoted in the report.)
    If A is late (previous spill / late readiness) the
    available time shrinks and the target rises — catch-up
    inside the burst, bounded by the cap. Applied speed =
    target, EXCEPT the diagnostic variant applied='stuck1.0'
    (setPlaybackParams throws once -> the player is stuck at
    1.0x forever, HUD still shows the target — the leading
    hypothesis for the unexplained ~1800 ms within-burst
    growth on the device at 12:00).
  * End-burst lateness (TRUE): end_err = actual_end -
    (srcEnd + L), where actual_end = A + eff_dur/applied.
    Start lateness start_err = A - S (>= 0 by construction).
    Lip-sync is judged at BOTH ends: a burst that starts on
    time but is still playing 3 s after its line ended is
    visibly out of sync at the next line.
  * Valve (tail/head drop, counted as dropped audio):
    - OFF:   never drops; spill propagates to the next burst.
    - OLD (v1.0.22 V2000cap500 analogue): at burst end, if
      excess = max(spill, end_err) > 2000 ms, drop
      min(excess - 1500, 500) ms of tail.
    - STRONG: excess > 1000 ms -> drop min(excess - 500,
      2000) ms of tail; PLUS skip-ahead at burst start: if
      start_late > 1000 ms, drop min(start_late - 500, 2000,
      50% of dub_dur) ms of HEAD audio so the remaining audio
      ends closer to on time (cost: first words of the line).
  * Pairs/markers: with a fixed delay every burst IS a marker
    pair by construction (video timestamp known exactly).
    pairs/60s here = bursts with start_err <= 500 ms in each
    60 s window of scheduled time; min-window is the gate.
    "Samples" do not exist in this design (no D estimation,
    no slice samples) — reported as n/a, not faked.

KEY STRUCTURAL FACT (check the sweep for it, do not assume):
  L shifts EVERY scheduled start by the same amount, so the
  window W = (next_srcStart - srcStart) is INDEPENDENT of L.
  L only buys READINESS slack (R <= S iff
  L >= lag + dub_dur/1.5 for that burst). Once L covers
  readiness, a longer L buys NOTHING for steady-state
  spill — spill is decided by ratio x duty vs the stretch
  cap (dense: need ~ r*duty = 1.47*0.96 = 1.41x mean). If
  the sweep plateaus in L, delay alone cannot fix the
  hardest world and the report must say what else is needed
  (shorter prompt / lower cap is NOT the fix for spill —
  a lower cap makes spill WORSE; the fixes are lower ratio
  r and/or a stronger valve).

WORLDS (10-min sessions, GATE_SPEECH_P=0.70 canonical):
  FA46 film-A timing, lag 4.6 s; FA78 film-A, lag 7.8 s;
  DE46 dense timing (96% duty), lag 4.6 s;
  DE78 dense, lag 7.8 s = *** HARDEST *** (dense + r1.47 +
    long lines + lag 7.8 s — the gate is judged on this one);
  CH45 choppy timing (55% duty), lag 4.5 s (device 12:00).
  Ratio is a dub parameter swept by ablation (world nominal
  r is NOT baked into the timing worlds).

ABLATIONS (cumulative chain, + one isolation row):
  (a) delay only:        r=1.47, cap 1.50, valve OLD
  (b) + short prompt:    r=1.35, cap 1.50, valve OLD
  (c) + cap 1.25:        r=1.35, cap 1.25, valve OLD
  (c0) cap alone:        r=1.47, cap 1.25, valve OLD (isolation)
  (d) + strong valve:    r=1.35, cap 1.50, valve STRONG
  (e) all:               r=1.35, cap 1.25, valve STRONG
  (e2) all + r=1.25:     r=1.25, cap 1.25, valve STRONG
  L sweep: 15 / 20 / 25 / 30 / 40 / 60 s (30/40 emphasised
  per the 12:40 correction; 60 s is the worst-case probe —
  if 40 s still fails the hardest world, 60 s tells us
  whether ANY delay fixes it or the failure is structural).

GATE28 (per run; ship needs ALL on the HARDEST world DE78):
  end-burst p90 TRUE steady (content t>=60 s) < 1000 ms;
  start-burst p90 steady < 1000 ms;
  dropped audio steady <= 2%;
  stretch cap <= 1.25 (ship bar — 1.50x sustained DISTORTS
    per the user verdict 12:11; 1.35x is UNPROVEN, needs a
    listening test, so cap-1.35 runs are CONDITIONAL only);
  pairs (start_err<=500 ms) > 0 in EVERY 60 s window;
  applied speed never exceeds the chosen cap (by construct).
  Cold start (first 60 s) is reported separately, never
  mixed into the steady gate.

REGRESSION (reg28): the ship config on the v6 world and the
  sim1018 device world (same seeds k=0,1,2 as regone25,
  RNG 20261128+k), vs the v1.0.22 OLD baselines in
  regone25_base_k*.txt (v6 line-start OLD med 650/460/689 ms,
  pairs OLD 3/4/9; sim1018 dropped OLD 8.3/7.7/10.3%,
  line-start OLD 1574/408/2348 ms, pairs OLD 26/23/9).
  Bars: line-start median NEW <= OLD, pairs/60s NEW >= OLD,
  dropped NEW <= OLD (sim1018). Must NOT fall below the
  v1.0.22 baseline (v6 1/3, sim1018 2/3 in regone25).

COSTS (startup + memory, assumptions stated in the code):
  startup wait = L (video held L s before the first synced
  frame; if the video starts immediately instead, the first
  L s play silent/undubbed — same L either way).
  memory ~= L * (video 720p ~2.0 Mbps = 0.25 MB/s + dub
  audio ~32 kbps = 0.004 MB/s + source-audio buffer) ≈
  L * 0.26 MB/s → 30 s ≈ 7.8 MB, 40 s ≈ 10.4 MB,
  60 s ≈ 15.6 MB (1080p 5 Mbps would be ~2.5x).

Usage: python3 sim1028.py [main|diag|reg|all] (default all)
Outputs: sim1028_main_run.txt / sim1028_diag_run.txt /
  sim1028_reg_run.txt (stdout redirected by the caller).
"""
import math
import os
import random
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1026 as s26
import sim1022 as s22
import sim1021 as s21
import sim1020 as s20
import sim1018 as s18
import sim1017 as s17
from sim1020 import pct

LONG_MS = 600_000
RESULTS28 = []

VALVE_OFF = dict(name='OFF', thresh=float('inf'), keep=0, cap=0,
                 skip_thresh=float('inf'), skip_cap=0)
VALVE_OLD = dict(name='OLD2000cap500', thresh=2000, keep=1500,
                 cap=500, skip_thresh=float('inf'), skip_cap=0)
VALVE_STRONG = dict(name='STRONG1000cap2000+skip', thresh=1000,
                    keep=500, cap=2000, skip_thresh=1000,
                    skip_cap=2000)

ABLATIONS = [
    ('a',  'delay-only',        1.47, 1.50, VALVE_OLD),
    ('b',  '+prompt-r1.35',     1.35, 1.50, VALVE_OLD),
    ('c',  '+cap1.25',          1.35, 1.25, VALVE_OLD),
    ('c0', 'cap1.25-alone',     1.47, 1.25, VALVE_OLD),
    ('d',  '+valve-strong',     1.35, 1.50, VALVE_STRONG),
    ('e',  'all-r1.35',         1.35, 1.25, VALVE_STRONG),
    ('e2', 'all-r1.25',         1.25, 1.25, VALVE_STRONG),
    # Extra rows added after the long-line world (LO78) showed
    # the (e)/(e2) trade-off (end-p90 ok, dropped 5-6%): what
    # ELSE would close LO78? cap 1.35 (distortion UNPROVEN —
    # needs a listening test; these rows can only PASS the
    # sync-only gate, never the ship gate) and r=1.15 (a much
    # shorter prompt — probably infeasible, quantified here).
    ('f',  'cap1.35-r1.35',     1.35, 1.35, VALVE_OLD),
    ('f2', 'cap1.35-r1.35-str', 1.35, 1.35, VALVE_STRONG),
    ('g',  'cap1.35-r1.47',     1.47, 1.35, VALVE_OLD),
    ('e3', 'all-r1.15',         1.15, 1.25, VALVE_STRONG),
]
LS = [15, 20, 25, 30, 40, 60]


# ----------------------------------------------------------------
# Bursts from a dub list (uniform for every world, incl. v6 /
# sim1018 whose dub tuples may carry a 5th pipeline element).
# ----------------------------------------------------------------
def bursts_from_dub(dub):
    """Group dub chunks by line id. Returns list of dicts sorted
    by srcStart: srcStart/srcEnd from the chunks' own true_lag
    mapping (src_wall = arrival - true_lag); dub_dur = sum of
    steps; ready = last chunk arrival."""
    by = {}
    for ch in dub:
        arr, step, tl, lid = ch[0], ch[1], ch[2], ch[3]
        src = arr - tl
        b = by.setdefault(lid, {'src_lo': src, 'src_hi': src,
                                'dub_dur': 0, 'ready': arr})
        b['src_lo'] = min(b['src_lo'], src)
        b['src_hi'] = max(b['src_hi'], src)
        b['dub_dur'] += step
        b['ready'] = max(b['ready'], arr)
    out = []
    for lid, b in by.items():
        # src_hi is the MIDPOINT of the last chunk's source
        # span; add half a chunk's source span (~35 ms at
        # ratio 1.47) to reach the line's true source end.
        out.append({'lid': lid,
                    'src_start': b['src_lo'],
                    'src_end': b['src_hi'] + 35,
                    'dub_dur': b['dub_dur'],
                    'ready': b['ready']})
    out.sort(key=lambda b: b['src_start'])
    return out


def burst_len_stats(bursts, lag0):
    """Dub-duration percentiles + the readiness requirement
    L_ready = lag + dub_dur/1.5 (generation at 1.5x)."""
    durs = sorted(b['dub_dur'] for b in bursts)
    srcs = sorted(b['src_end'] - b['src_start'] for b in bursts)

    def _p(a, q):
        if not a:
            return None
        return a[min(len(a) - 1, int(q * len(a)))]
    need = sorted(lag0 + d / 1.5 for d in durs)
    return {
        'n': len(bursts),
        'dub_p50': _p(durs, 0.50), 'dub_p90': _p(durs, 0.90),
        'dub_p99': _p(durs, 0.99), 'dub_max': durs[-1] if durs else 0,
        'src_p50': _p(srcs, 0.50), 'src_p99': _p(srcs, 0.99),
        'src_max': srcs[-1] if srcs else 0,
        'ready_p50': _p(need, 0.50), 'ready_p90': _p(need, 0.90),
        'ready_p99': _p(need, 0.99), 'ready_max': need[-1] if need else 0,
    }


# ----------------------------------------------------------------
# Fixed-delay scheduler (one run).
# ----------------------------------------------------------------
def run_fixed(bursts, label, L_s, ratio, cap, valve,
              applied_mode='target', lag0=None):
    """L_s in seconds. Returns a result dict (also appended to
    RESULTS28 and printed)."""
    L = L_s * 1000.0
    n = len(bursts)
    prev_end = None
    tot_dub = tot_drop = 0
    tot_dub_st = tot_drop_st = 0
    speeds, start_errs, end_errs = [], [], []
    start_errs_st, end_errs_st = [], []
    end_errs_cold, start_errs_cold = [], []
    drop_cold_ms = dub_cold_ms = 0.0
    ready_late, ready_late_st = [], []
    pairs_win = [0] * 10
    bursts_win = [0] * 10
    valve_ms = valve_fires = skip_ms = 0
    max_start = max_end = max_spill = 0.0
    end_by_min = {}
    for i, b in enumerate(bursts):
        S = b['src_start'] + L
        src = b['src_start']
        nxt_S = (bursts[i + 1]['src_start'] + L
                 if i + 1 < n else None)
        R = b['ready']
        A0 = S
        if R > A0:
            A0 = R
        if prev_end is not None and prev_end > A0:
            A0 = prev_end
        start_late = A0 - S
        # skip-ahead (strong valve only): drop head audio so
        # the REMAINDER ends closer to on time.
        drop_head = 0
        if start_late > valve['skip_thresh']:
            drop_head = min(start_late - 500, valve['skip_cap'],
                            0.5 * b['dub_dur'])
            drop_head = max(0.0, drop_head)
        eff_dur = b['dub_dur'] - drop_head
        # Speed to END at the line's video end (srcEnd + L):
        # target = eff_dur / time-left-to-line-end, clamped
        # to [1.0, cap]. The pause after the line stays
        # silent. (NOT window-filling to next_S: that plays
        # voice over the whole pause and maximises end_err.)
        src_end_sched = b['src_end'] + L
        avail = src_end_sched - A0
        need = eff_dur / avail if avail > 0 else cap
        target = max(1.0, min(cap, need))
        applied = 1.0 if applied_mode == 'stuck1.0' else target
        end0 = A0 + eff_dur / applied
        end_err0 = end0 - src_end_sched
        spill = (end0 - nxt_S) if nxt_S is not None else 0.0
        excess = max(spill, end_err0)
        # tail valve: drop tail audio to cut the excess.
        drop_tail = 0
        if excess > valve['thresh'] and valve['cap'] > 0:
            drop_tail = min(excess - valve['keep'], valve['cap'])
            drop_tail = max(0.0, min(drop_tail, eff_dur * 0.9))
        end = end0 - drop_tail / applied if drop_tail else end0
        end_err = end - src_end_sched
        start_err = A0 - S
        prev_end = end
        # ---- metrics (steady / windows are in VIDEO time,
        # i.e. src_start, so they are L-independent; the first
        # 60 s of CONTENT is cold start) ----
        steady = src >= 60000
        tot_dub += b['dub_dur']
        tot_drop += drop_head + drop_tail
        if steady:
            tot_dub_st += b['dub_dur']
            tot_drop_st += drop_head + drop_tail
        speeds.append(applied)
        start_errs.append(start_err)
        end_errs.append(end_err)
        if steady:
            start_errs_st.append(start_err)
            end_errs_st.append(end_err)
            ready_late_st.append(max(0.0, R - S))
        else:
            start_errs_cold.append(start_err)
            end_errs_cold.append(end_err)
            drop_cold_ms += drop_head + drop_tail
            dub_cold_ms += b['dub_dur']
        ready_late.append(max(0.0, R - S))
        w = int(src // 60000)
        if 0 <= w < 10:
            bursts_win[w] += 1
            if start_err <= 500:
                pairs_win[w] += 1
        if drop_tail > 0 or drop_head > 0:
            valve_fires += 1
        valve_ms += drop_head + drop_tail
        skip_ms += drop_head
        max_start = max(max_start, start_err)
        max_end = max(max_end, abs(end_err))
        max_spill = max(max_spill, spill)
        end_by_min.setdefault(int(src // 60000), []).append(end_err)
    end_med_min = [None if not end_by_min.get(m) else
                   round(pct(end_by_min[m], 0.5)) for m in range(10)]
    backlog_end = max(0.0, (prev_end or 0) - (LONG_MS + L))
    # pairs_min counts only windows that actually contain
    # bursts: the v6/sim1018 regression worlds end after
    # ~7 min of content, so windows 7-9 are EMPTY (no bursts),
    # not failed windows. (Main worlds span the full 600 s.)
    nonempty = [pairs_win[w] for w in range(10)
                if bursts_win[w] > 0]
    pairs_min = min(nonempty) if nonempty else 0
    res = {
        'label': label, 'L': L_s, 'ratio': ratio, 'cap': cap,
        'valve': valve['name'], 'applied_mode': applied_mode,
        'n_bursts': n,
        'end_p90': pct(end_errs_st, 0.9),
        'end_med': pct(end_errs_st, 0.5),
        'end_max': max_end,
        'end_p90_cold': pct(end_errs_cold, 0.9),
        'end_med_cold': pct(end_errs_cold, 0.5),
        'drop_cold': (100.0 * drop_cold_ms / dub_cold_ms
                      if dub_cold_ms else 0.0),
        'start_p90_cold': pct(start_errs_cold, 0.9),
        'start_p90': pct(start_errs_st, 0.9),
        'start_med': pct(start_errs_st, 0.5),
        'start_max': max_start,
        'drop_steady': (100.0 * tot_drop_st / tot_dub_st
                        if tot_dub_st else 0.0),
        'drop_all': (100.0 * tot_drop / tot_dub
                     if tot_dub else 0.0),
        'speed_mean': (sum(speeds) / len(speeds)
                       if speeds else None),
        'speed_max': max(speeds) if speeds else None,
        'frac_at_cap': (sum(1 for s in speeds if s >= cap - 0.001)
                        / len(speeds) if speeds else 0.0),
        'pairs_win': pairs_win, 'pairs_min': pairs_min,
        'pairs_mean': sum(pairs_win) / max(1, len(nonempty)),
        'bursts_win': bursts_win,
        'ready_late_p90': pct(ready_late_st, 0.9),
        'ready_late_n': sum(1 for v in ready_late if v > 0),
        'ready_late_max': max(ready_late) if ready_late else 0,
        'valve_ms': valve_ms, 'valve_fires': valve_fires,
        'skip_ms': skip_ms, 'max_spill': max_spill,
        'backlog_end': backlog_end,
        'end_med_min': end_med_min,
        'tot_dub_st': tot_dub_st,
    }
    res['gate'] = gate28(label, res)
    RESULTS28.append(res)
    print("RUN28 %-42s L=%2ds r=%.2f cap=%.2f valve=%-22s %s"
          % (label, L_s, ratio, cap, valve['name'], applied_mode))
    print("  end-p90 %s / med %s / max %.0f ms; start-p90 %s /"
          " med %s / max %.0f ms; drop steady %.2f%% (all %.2f%%);"
          " speed mean %.2f max %.2f frac@cap %.2f"
          % (res['end_p90'], res['end_med'], res['end_max'],
             res['start_p90'], res['start_med'], res['start_max'],
             res['drop_steady'], res['drop_all'],
             res['speed_mean'], res['speed_max'],
             res['frac_at_cap']))
    print("  pairs/60s %s (min %d, mean %.1f); ready-late p90"
          " %.0f ms (n=%d late, max %.0f); valve %d ms / %d"
          " fires (skip-head %d ms); max spill %.0f ms;"
          " backlog@session-end %.0f ms"
          % (pairs_win, res['pairs_min'], res['pairs_mean'],
             res['ready_late_p90'], res['ready_late_n'],
             res['ready_late_max'], valve_ms, valve_fires,
             skip_ms, max_spill, backlog_end))
    print("  end-burst median by minute %s; COLD (content <60 s):"
          " end-p90 %s / med %s, start-p90 %s, drop %.2f%%"
          % (end_med_min, res['end_p90_cold'], res['end_med_cold'],
             res['start_p90_cold'], res['drop_cold']))
    print()
    return res


def gate28(label, res):
    """GATE28. Ship bar needs cap<=1.25 (1.50 distorts per
    user 12:11; 1.35 unproven). Returns dict of bars."""
    bars = {
        'end-p90 TRUE steady < 1000 ms':
            res['end_p90'] is not None and res['end_p90'] < 1000,
        'start-p90 steady < 1000 ms':
            res['start_p90'] is not None
            and res['start_p90'] < 1000,
        'dropped audio steady <= 2%':
            res['drop_steady'] <= 2.0,
        'stretch cap <= 1.25 (ship; 1.35 unproven, 1.5 distorts)':
            res['cap'] <= 1.25,
        'pairs > 0 in EVERY 60 s window':
            res['pairs_min'] > 0,
        'backlog @ session end < 1000 ms':
            res['backlog_end'] < 1000,
    }
    ok = all(bars.values())
    sync_bars = {k: v for k, v in bars.items()
                 if not k.startswith('stretch cap')}
    sync_ok = all(sync_bars.values())
    print("GATE28 %s: %s (sync-only, ignoring the cap bar: %s)"
          % (label, "PASS" if ok else "FAIL",
             "PASS" if sync_ok else "FAIL"))
    for k, v in bars.items():
        print("  %-52s %s" % (k, "PASS" if v else "FAIL"))
    print()
    res['gate_bars'] = bars
    res['gate_ok'] = ok
    res['sync_ok'] = sync_ok
    return ok


def summary28():
    print("=" * 118)
    print("SUMMARY28 (end-p90/start-p90 = TRUE, steady = content"
          " t>=60 s; drop = steady %; speed = applied):")
    print("%-44s %4s %7s %7s %6s %6s %6s %5s %7s %6s"
          % ("run", "L", "endp90", "start90", "drop%",
             "spdMn", "spdMx", "prMin", "valveMs",
             "backlog"))
    for r in RESULTS28:
        def _f(v):
            return -1 if v is None else v
        print("%-44s %4d %7.0f %7.0f %6.2f %6.2f %6.2f %5d %7.0f"
              " %6.0f %s"
              % (r['label'][:44], r['L'], _f(r['end_p90']),
                 _f(r['start_p90']), r['drop_steady'],
                 _f(r['speed_mean']), _f(r['speed_max']),
                 r['pairs_min'], r['valve_ms'], r['backlog_end'],
                 "GATE-PASS" if r.get('gate_ok') else ""))
    print()


# ----------------------------------------------------------------
# Worlds (timing only; ratio/lag applied when building dub).
# ----------------------------------------------------------------
_DUB_CACHE = {}


def get_bursts(world_key, world, ratio, lag0):
    """Build dub at (ratio, lag0) once per key; return bursts."""
    key = (world_key, ratio, lag0)
    if key in _DUB_CACHE:
        return _DUB_CACHE[key]
    (frames, ls, lu, vad, sent, vs, txin, txsrc) = world
    dub, skipped = s21.build_dub21(
        frames, sent, ls, lu, ratio=ratio, lag0=lag0, speed=1.5,
        seed=20261128, skip_p=s21.TX_SKIP_P)
    bursts = bursts_from_dub(dub)
    _DUB_CACHE[key] = (bursts, len(skipped), burst_len_stats(bursts, lag0))
    return _DUB_CACHE[key]


def world_stats28(tag, world, ratio, lag0):
    bursts, nskip, st = get_bursts(tag, world, ratio, lag0)
    (frames, ls, lu, vad, sent, vs, txin, txsrc) = world
    speech_ms = sum(lu.values())
    print("WORLD28 %s r=%.2f lag=%.1f s: %d lines, %d bursts"
          " (%d lines skipped/untranslated), speech %d ms/600 s"
          " (%.0f%% true-speech duty; VAD-fed %.0f%% — the"
          " '96%% duty' figure in sim1022/26 is the FED duty,"
          " speech + music-filled pauses)"
          % (tag, ratio, lag0 / 1000.0, len(ls), len(bursts),
             nskip, speech_ms, 100.0 * speech_ms / LONG_MS,
             100.0 * vs.get('fed60', 0) / 60000.0))
    print("  dub dur ms: p50 %.0f p90 %.0f p99 %.0f max %.0f;"
          " source len p50 %.0f p99 %.0f max %.0f"
          % (st['dub_p50'], st['dub_p90'], st['dub_p99'],
             st['dub_max'], st['src_p50'], st['src_p99'],
             st['src_max']))
    print("  READINESS L_min = lag + dub_dur/1.5: p50 %.0f p90"
          " %.0f p99 %.0f max %.0f ms  (L must exceed this"
          " for the burst to be ready at its scheduled start)"
          % (st['ready_p50'], st['ready_p90'], st['ready_p99'],
             st['ready_max']))
    print()
    return bursts, st


def cost_table():
    print("COST OF DELAY (assumptions: video held L s before the"
          " first synced frame; buffer = 720p video ~2.0 Mbps"
          " (0.25 MB/s) + dub audio ~32 kbps + source-audio"
          " buffer ~0.01 MB/s, total ~0.26 MB/s; 1080p 5 Mbps"
          " would be ~2.5x the video part):")
    for L in (15, 20, 25, 30, 40, 60):
        print("  L=%2d s: startup wait %2d s; buffer ~%.1f MB"
              " (720p) / ~%.1f MB (1080p)"
              % (L, L, L * 0.26, L * 0.26 + L * 0.375))
    print("  NOTE: in-sim, extra L beyond readiness buys no"
          " sync (windows are L-independent); its only cost is"
          " the wait + buffer above, its only benefit is"
          " readiness slack for unusually long/late bursts.")
    print()


# ----------------------------------------------------------------
# Main sweep.
# ----------------------------------------------------------------
def main():
    t0 = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'all'
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("sim1028 FIXED-DELAY SCHEDULER. User 12:36: fixed"
          " delay, dub back to the delayed video position."
          " Correction 12:40: take EXTRA delay if needed —"
          " lip-sync on EVERY film beats saving delay."
          " Gate judged on the HARDEST world (dense 96%%,"
          " r1.47, lag 7.8 s). 10-min sessions.")
    print()

    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    worldC = s26._build_world26()
    # Long-line stress world: lines 3-8 s (real films have
    # sentences far longer than the 1.5-3 s drama lines of the
    # canonical worlds), pauses 0.4-1.5 s silence, lag 7.8 s.
    # This is the world that tests whether L=15 s really covers
    # readiness, or only the short-line worlds do.
    worldL = s26._build_world26(ulen_lo=3000, ulen_hi=8000,
                                pause_lo=400, pause_hi=1500,
                                music_p=0.0)
    worlds = [
        ('FA46', worldA, 4600, 'film-A timing, lag 4.6 s'),
        ('FA78', worldA, 7800, 'film-A timing, lag 7.8 s'),
        ('DE46', worldB, 4600, 'dense timing 96% FED duty, lag 4.6 s'),
        ('DE78', worldB, 7800,
         'dense timing 96% FED duty, lag 7.8 s *** HARDEST ***'),
        ('CH45', worldC, 4500, 'choppy timing 55% duty, lag 4.5 s'),
        ('LO78', worldL, 7800,
         'LONG-LINE timing (3-8 s lines), lag 7.8 s'),
    ]

    if mode in ('all', 'main'):
        print("#### WORLD STATS + READINESS (r=1.47) ####")
        for key, w, lag0, desc in worlds:
            print("WORLD %s: %s" % (key, desc))
            world_stats28(key, w, 1.47, lag0)
        cost_table()

        print("#### ABLATION SWEEP: L x ablation, all worlds ####")
        for key, w, lag0, desc in worlds:
            for (aid, aname, r, cap, valve) in ABLATIONS:
                bursts, _, _ = get_bursts(key, w, r, lag0)
                for L in LS:
                    run_fixed(bursts,
                              "%s-%s L%d" % (key, aid, L),
                              L, r, cap, valve)
        print("#### ABLATION SUMMARY (end-p90 steady / drop%%) ####")
        print("%-8s %-16s %s" % ("world", "ablation",
                                 " ".join("L%-2d" % L for L in LS)))
        for key, w, lag0, desc in worlds:
            for (aid, aname, r, cap, valve) in ABLATIONS:
                row = [x for x in RESULTS28
                       if x['label'].startswith("%s-%s L" % (key, aid))]
                print("%-8s %-16s %s" % (
                    key, aid,
                    " ".join("%d/%.1f" % (x['end_p90'],
                                          x['drop_steady'])
                             for x in sorted(row, key=lambda z: z['L']))))
        print("  (each cell: end-p90 ms / dropped steady %)")
        print()
        print("#### GATE TABLES (GATE28 ship / sync-only) ####")
        for key, w, lag0, desc in worlds:
            print("  %s (%s):" % (key, desc))
            for (aid, aname, r, cap, valve) in ABLATIONS:
                row = [x for x in RESULTS28
                       if x['label'].startswith("%s-%s L" % (key, aid))]
                print("    %s-%s (r=%.2f cap=%.2f %s): %s" % (
                    key, aid, r, cap, valve['name'],
                    ", ".join("L%d:%s%s" % (
                        x['L'],
                        "PASS" if x.get('gate_ok') else "fail",
                        "" if x.get('gate_ok') == x.get('sync_ok')
                        else ("(syncPASS)" if x.get('sync_ok')
                              else "(syncFAIL)"))
                        for x in sorted(row, key=lambda z: z['L']))))
        print()
        summary28()

    if mode in ('all', 'probe'):
        print("#### PROBE: L below the 15 s sweep floor — find"
              " the true readiness threshold ####")
        print("The main sweep is L-invariant down to 15 s"
              " (readiness max is ~10.7 s on short-line worlds),"
              " so probe L = 6/8/10/11/12/13/14 s on the hardest"
              " world (DE78, configs c/e/e2), the long-line world"
              " (LO78, config e), and film-A delay-only (FA46-a).")
        for key, w, lag0, cfgs in (
                ('DE78', worldB, 7800, ('c', 'e', 'e2')),
                ('LO78', worldL, 7800, ('e',)),
                ('FA46', worldA, 4600, ('a',))):
            for aid in cfgs:
                _, _, r, cap, valve = [a for a in ABLATIONS
                                       if a[0] == aid][0]
                bursts, _, _ = get_bursts(key, w, r, lag0)
                for L in (6, 8, 10, 11, 12, 13, 14):
                    run_fixed(bursts, "PROBE-%s-%s L%d" % (key, aid, L),
                              L, r, cap, valve)
        summary28()

    if mode in ('all', 'diag'):
        print("#### DIAGNOSTIC: applied speed stuck at 1.0x"
              " (setPlaybackParams throws once -> player stuck"
              " at 1.0x, HUD still shows target) ####")
        print("Hypothesis for the unexplained ~1800 ms"
              " within-burst growth on the device at 12:00"
              " (v1.0.22, target 1.50x, end-p90 3446 ms): if the"
              " APPLIED speed is 1.0x while the target is 1.5x,"
              " a burst of dub_dur D grows (1 - 1/1.5)*D inside"
              " the burst and spills into every following burst.")
        for key, w, lag0 in (('FA46', worldA, 4600),
                             ('DE78', worldB, 7800)):
            # (a) delay-only, cap 1.5, L=40 — the device-like
            # config; (e) all, cap 1.25, L=40 — the ship config.
            for (aid, r, cap, valve) in (
                    ('a', 1.47, 1.50, VALVE_OLD),
                    ('e', 1.35, 1.25, VALVE_STRONG)):
                bursts, _, _ = get_bursts(key, w, r, lag0)
                run_fixed(bursts, "DIAG-%s-%s-target L40" % (key, aid),
                          40, r, cap, valve, applied_mode='target')
                run_fixed(bursts, "DIAG-%s-%s-stuck1.0 L40" % (key, aid),
                          40, r, cap, valve, applied_mode='stuck1.0')
        print("DIAG verdict: compare each target/stuck1.0 pair —"
              " if stuck1.0 inflates end-p90 by ~seconds and"
              " backlog grows without bound while target stays"
              " bounded, the applied<target hypothesis explains"
              " the device's ~1800 ms term (and the fixed-delay"
              " design MUST read back the applied speed, not"
              " trust the target).")
        print()
        summary28()

    if mode in ('all', 'reg'):
        print("#### REGRESSION vs v1.0.22 baseline (regone25) ####")
        print("Ship config under test: (e) all, r=1.35, cap"
              " 1.25, valve STRONG, L=40 s (also L=30 s)."
              " OLD baselines (regone25_base_k0/k1/k2.txt):"
              " v6 line-start OLD med 650/460/689 ms, pairs"
              " OLD 3/4/9; sim1018 dropped OLD 8.3/7.7/10.3%,"
              " line-start OLD 1574/408/2348 ms, pairs OLD"
              " 26/23/9. v1.0.22 baseline: v6 1/3, sim1018 2/3.")
        reg28()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


# ----------------------------------------------------------------
# Regression (v6 + sim1018 worlds, seeds k=0,1,2).
# ----------------------------------------------------------------
def reg28():
    """Run the ship config on the v6 and sim1018 worlds built
    with RNG seeded 20261128+k (same as regone25), and compare
    against the OLD numbers quoted in main(). For these worlds
    the dub list comes from the world builders directly; the
    ratio/lag are the worlds' own (v6: whatever build_world
    uses; sim1018: r1.46, lag 2.4 s)."""
    for k in (0, 1, 2):
        random.seed(20261128 + k)
        vad6, dub6, completes6, line_start6 = s17.build_world()
        bursts6 = bursts_from_dub(dub6)
        print("REG28 v6 k=%d: %d bursts, dub p90 %.0f ms"
              % (k, len(bursts6),
                 sorted(b['dub_dur'] for b in bursts6)[
                     int(0.9 * len(bursts6))]))
        for L in (30, 40):
            r = run_fixed(bursts6, "REG-v6-k%d-L%d" % (k, L), L,
                          1.35, 1.25, VALVE_STRONG)
            print("  REG-v6 k=%d L=%d: line-start(start) median"
                  " %.0f ms (OLD 650/460/689 by k); pairs/60s"
                  " mean %.1f min %d (OLD 3/4/9 by k); drop"
                  " %.2f%%"
                  % (k, L, r['start_med'], r['pairs_mean'],
                     r['pairs_min'], r['drop_steady']))
        random.seed(20261128 + k)
        vad18, dub18, completes18, line_start18, counted18 = \
            s18.build_world_device(tc_on=True, speed=2.5)
        bursts18 = bursts_from_dub(dub18)
        print("REG28 sim1018 k=%d: %d bursts, dub p90 %.0f ms"
              % (k, len(bursts18),
                 sorted(b['dub_dur'] for b in bursts18)[
                     int(0.9 * len(bursts18))]))
        for L in (30, 40):
            r = run_fixed(bursts18, "REG-1018-k%d-L%d" % (k, L), L,
                          1.35, 1.25, VALVE_STRONG)
            print("  REG-1018 k=%d L=%d: line-start(start) median"
                  " %.0f ms (OLD 1574/408/2348 by k); pairs/60s"
                  " mean %.1f min %d (OLD 26/23/9 by k); dropped"
                  " %.2f%% (OLD 8.3/7.7/10.3%% by k)"
                  % (k, L, r['start_med'], r['pairs_mean'],
                     r['pairs_min'], r['drop_steady']))
        print()


if __name__ == '__main__':
    main()
