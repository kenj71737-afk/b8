#!/usr/bin/env python3
"""sim1030.py — TRACE the r=1.40 blow-up minute by minute.

Open question from sim1029b (BUILD_NOTES "VONG B GATE FAIL"):
  r=1.40 filmA: end-burst p90 1928 ms / drop 6.2% FAIL (valve
  41x / 46.8 s); r=1.45/1.47 PASS (514/483 ms). NON-monotonic
  in r. The r=1.40 blow-up is CAP-caused (OLD dead valve gives
  the same 1940/1598 ms with 0% drop), NOT valve-caused — but
  WHY does a 0.05 drain deficit (r 1.40 vs cap 1.35) explode in
  minutes 1-5 and self-recover in minutes 7-8, while a 0.12
  deficit (r=1.47) does not explode at all?

Candidate mechanisms to separate (log what moves FIRST):
  H1 D-chase timing: slow drift at r=1.40 stays inside the
     D-check (+-1500 ms) so D creeps and the backlog grows;
     at r=1.47 the lag jumps fast, the 6-reject chase fires
     early and D jumps to the new regime. Signature: chase
     accepts / D jumps per minute differ; D stays low at
     r=1.40 while true lag climbs.
  H2 Rb ratchet: the ref locks LOW; tx anchors that would
     correct it are blocked (lat_tx > dlag + 600). Signature:
     lat_tx - true_lag stays large while ref - true_lag stays
     negative; tx_ok stalls.
  H3 Seed branch: Sa2 picks a different group at r=1.40 vs
     r=1.47 (first paired slice differs with dub duration).
     Signature: _sa_trace seed_err differs between the runs.
     (Prior evidence AGAINST: sim1029 ship runs show the same
     seed err -416 ms at r=1.35 lag4.6 AND lag7.8 — the seed
     does not depend on lag; check it depends on r too.)
  H4 Ratio-floor measurement: ratio() converges differently,
     so the floor sits below the cap longer at r=1.40.
     Signature: measured ratio() / floor / speed-mean per
     minute differ.
  H5 Stale-drop as auto-valve: at r=1.47 more chunks go stale
     (>700 ms overdue) and drop, bounding the queue; at
     r=1.40 chunks stay just under the stale line so the
     backlog accumulates. Signature: stale drops per minute
     vs take_late (busy_until - due) per minute.

Trace hooks (subclass of the DEAD-valve tracker, so the valve
cannot confound the explosion; one STRONG-valve run shows the
valve interaction per minute):
  * on_burst_played: after super() (which computes _vb_end_lag
    from the PARTIAL pairing log at play time), snapshot
    _vb_end_lag / _vb_end_lag_kot / D / hold / target_speed /
    ratio() / _ref_lag() / _d_based_lag() / backlog / pipeline /
    chase_accepts / chase_rejects_seen / mode_switches / tx_ok /
    dropped_audio_ms / dropped_steady_ms / sb trials+adopts.
  * on_dub_burst: after super(), snapshot valve fires/ms + D.
  * After the run: TRUE end-burst per burst from chunk_raw
    (render - true_src_wall - D at the last chunk by offset,
    same as diag26); true lag per burst from the first chunk
    (arrival - true_src_wall); anchor error per burst =
    S-b median sample - true lag; tx anchor error =
    lat_tx - true lag.

Per-minute table (minute = play_wall // 60000): D med, hold
med, speed mean, ratio() med, TRUE end-burst med/p90,
_vb_end_lag med/p90, valve-input error (_vb_end_lag -
TRUE end) med and p90 of |error|, backlog med, chase
accepts/rejects deltas, mode-switch deltas, tx_ok deltas,
stale+valve drop deltas, valve fires/ms deltas, anchor-err
med, tx-err med.

Runs (all 10-min, seed 20261128, dead valve unless noted):
  T1 filmA r=1.35 (control: flat ~200 ms)
  T2 filmA r=1.40 (the explosion)
  T3 filmA r=1.47 (the paradox: no explosion)
  T4 dense r=1.40 (explosion in dense too)
  T5 filmA r=1.40 STRONG valve (valve interaction)
  T6 choppy r=1.40 (control: passes)

Usage: python3 sim1030.py [trace|all] (default all)
Output: sim1030_trace_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22
import sim1021 as s21
import sim1029 as s29


def _med(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


def _p90(vals):
    if not vals:
        return None
    a = sorted(abs(v) for v in vals)
    return a[min(len(a) - 1, int(0.9 * len(a)))]


class TraceTracker(s29.NewTracker29Cap135OldValve):
    """Dead-valve tracker + per-burst trace hooks."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.trace_play = []
        self.trace_burst = []

    def on_burst_played(self, seq, play_wall, pipeline, backlog=0):
        super().on_burst_played(seq, play_wall, pipeline, backlog)
        self.trace_play.append({
            'play': play_wall, 'seq': seq,
            'vb': self._vb_end_lag, 'vb_kot': self._vb_end_lag_kot,
            'D': self.delay, 'hold': self.hold,
            'speed': self.target_speed,
            'ratio_meas': self.ratio(),
            'ref': self._ref_lag(), 'dlag': self._d_based_lag(),
            'backlog': backlog, 'pipeline': pipeline,
            'chase_acc': self.chase_accepts,
            'chase_rej': self.chase_rejects_seen,
            'mode_sw': self.mode_switches,
            'tx_ok': self.tx_ok,
            'dropped': self.dropped_audio_ms,
            'dropped_steady': self.dropped_steady_ms,
            'sb_trials': self._sb_trials,
            'sb_adopts': self._sb_adopts,
        })

    def on_dub_burst(self, now, true_lag, true_sample=None):
        prev_hold = self.hold
        super().on_dub_burst(now, true_lag, true_sample)
        le = self.lag_est(now)
        db = self._d_based_lag()
        mg = self.dynamic_margin()
        pp = self.pipeline_ms()
        rs = getattr(self, 'recent_samples', [])
        self.trace_burst.append({
            'now': now, 'seq': getattr(self, 'burst_seq', None),
            'valve_fires': self.tail_valve_fires,
            'valve_ms': self.tail_valve_drop_ms,
            'D': self.delay, 'hold': self.hold,
            'prev_hold': prev_hold,
            'lag_est': le, 'dlag': db, 'margin': mg, 'pipe': pp,
            'robust': max(0, self.delay - le - pp),
            'n_samples': len(rs),
            'newest_age': (now - rs[-1][0]) if rs else -1,
        })


class TraceTrackerB(s29.NewTracker29B):
    """STRONG-valve tracker + the same trace hooks."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.trace_play = []
        self.trace_burst = []

    def on_burst_played(self, seq, play_wall, pipeline, backlog=0):
        super().on_burst_played(seq, play_wall, pipeline, backlog)
        self.trace_play.append({
            'play': play_wall, 'seq': seq,
            'vb': self._vb_end_lag, 'vb_kot': self._vb_end_lag_kot,
            'D': self.delay, 'hold': self.hold,
            'speed': self.target_speed,
            'ratio_meas': self.ratio(),
            'ref': self._ref_lag(), 'dlag': self._d_based_lag(),
            'backlog': backlog, 'pipeline': pipeline,
            'chase_acc': self.chase_accepts,
            'chase_rej': self.chase_rejects_seen,
            'mode_sw': self.mode_switches,
            'tx_ok': self.tx_ok,
            'dropped': self.dropped_audio_ms,
            'dropped_steady': self.dropped_steady_ms,
            'sb_trials': self._sb_trials,
            'sb_adopts': self._sb_adopts,
        })

    def on_dub_burst(self, now, true_lag, true_sample=None):
        prev_hold = self.hold
        super().on_dub_burst(now, true_lag, true_sample)
        le = self.lag_est(now)
        db = self._d_based_lag()
        mg = self.dynamic_margin()
        pp = self.pipeline_ms()
        rs = getattr(self, 'recent_samples', [])
        self.trace_burst.append({
            'now': now, 'seq': getattr(self, 'burst_seq', None),
            'valve_fires': self.tail_valve_fires,
            'valve_ms': self.tail_valve_drop_ms,
            'D': self.delay, 'hold': self.hold,
            'prev_hold': prev_hold,
            'lag_est': le, 'dlag': db, 'margin': mg, 'pipe': pp,
            'robust': max(0, self.delay - le - pp),
            'n_samples': len(rs),
            'newest_age': (now - rs[-1][0]) if rs else -1,
        })


def _true_end_per_burst(tr):
    """TRUE end-burst per burst from chunk_raw (diag26 logic):
    last chunk by offset -> (render - true_src_wall) - D."""
    by_burst = {}
    for (render, arr, tsw, off, bseq, d_at, spd) in \
            getattr(tr, 'chunk_raw', []):
        if off is None:
            continue
        by_burst.setdefault(bseq, []).append(
            (render, arr, tsw, off, d_at))
    out = {}
    for bseq, entries in by_burst.items():
        first = min(entries, key=lambda e: e[3])
        last = max(entries, key=lambda e: e[3])
        # true lag of the burst = arrival - true_src_wall of the
        # first chunk (the dub generator's own mapping).
        true_lag = first[1] - first[2]
        out[bseq] = {
            'true_end': (last[0] - last[2]) - last[4],
            'true_lag': true_lag,
            'first_render': first[0],
            'last_render': last[0],
        }
    return out


def trace_run(label, cls, world, ratio, lag0, film='C', **kw):
    res, d26 = s26.run26(label, cls, world, ratio, lag0, film=film,
                         dub_speed=1.5, **kw)
    tr = s26._TRACKER26_LAST[0]
    true_end = _true_end_per_burst(tr)
    # Join trace_play (by seq) with TRUE end + S-b medians.
    sb_by_seq = {b['seq']: b for b in getattr(tr, '_sb_bursts', [])}
    joined = []
    for rec in tr.trace_play:
        seq = rec['seq']
        te = true_end.get(seq, {})
        sb = sb_by_seq.get(seq, {})
        tlag = te.get('true_lag')
        joined.append(dict(rec,
                           true_end=te.get('true_end'),
                           true_lag=tlag,
                           vb_err=(rec['vb'] - te['true_end']
                                   if te.get('true_end') is not None
                                   else None),
                           sb_med=sb.get('med'),
                           sb_ref=sb.get('ref'),
                           sb_lat_tx=sb.get('lat_tx'),
                           anchor_err=(sb['med'] - tlag
                                       if sb.get('med') is not None
                                       and tlag is not None else None),
                           tx_err=(sb['lat_tx'] - tlag
                                   if sb.get('lat_tx') is not None
                                   and tlag is not None else None)))
    # ---- per-minute table ----
    print("TRACE30 %s (r=%.2f, %s): seed %s; D end %.0f;"
          " true lag med %.0f"
          % (label, ratio, cls.__name__, tr._sa_trace,
             res.get('d_end') or -1,
             (d26 or {}).get('true_lag_med') or -1))
    print("  min |   Dmed | holdmed | spdmean | ratiomed |"
          " trueEnd med/p90 | vb med/p90 | vb-true med |"
          " |vb-true| p90 | backlog med | chAcc d | chRej d |"
          " modeSw d | txOk d | drop d ms | valve f/ms d |"
          " anchorErr med | txErr med")
    prev = None
    for m in range(10):
        rows = [j for j in joined if int(j['play'] // 60000) == m]
        brows = [b for b in tr.trace_burst
                 if int(b['now'] // 60000) == m]
        if not rows and not brows:
            continue

        def _d(field, first_rows):
            if not first_rows:
                return 0
            return (first_rows[-1][field] - first_rows[0][field])
        ch_acc = _d('chase_acc', rows)
        ch_rej = _d('chase_rej', rows)
        msw = _d('mode_sw', rows)
        txd = _d('tx_ok', rows)
        drp = _d('dropped', rows)
        vf = _d('valve_fires', brows)
        vm = _d('valve_ms', brows)
        te = [j['true_end'] for j in rows
              if j['true_end'] is not None]
        vb = [j['vb'] for j in rows]
        err = [j['vb_err'] for j in rows
               if j['vb_err'] is not None]
        anch = [j['anchor_err'] for j in rows
                if j['anchor_err'] is not None]
        txe = [j['tx_err'] for j in rows
               if j['tx_err'] is not None]
        print("  %3d | %6.0f | %7.0f | %7.2f | %8.3f |"
              " %7.0f/%6.0f | %6.0f/%6.0f | %9.0f |"
              " %10.0f | %11.0f | %7d | %7d |"
              " %8d | %6d | %9d | %5d/%6d |"
              " %13.0f | %9.0f"
              % (m,
                 _med([j['D'] for j in rows]) or -1,
                 _med([j['hold'] for j in rows]) or -1,
                 (sum(j['speed'] for j in rows) / len(rows))
                 if rows else -1,
                 _med([j['ratio_meas'] for j in rows]) or -1,
                 _med(te) or -1, _p90(te) or -1,
                 _med(vb) or -1, _p90(vb) or -1,
                 _med(err) or -1, _p90(err) or -1,
                 _med([j['backlog'] for j in rows]) or -1,
                 ch_acc, ch_rej, msw, txd, drp, vf, vm,
                 _med(anch) if anch else -9999,
                 _med(txe) if txe else -9999))
    # ---- HOLD-DECOMP table (burst-START values, where hold is set) ----
    print("  HOLD-DECOMP (at burst start): min | Dstart med |"
          " lagEst med | dBased med | margin med | pipe med |"
          " robust med | prevHold med | hold med | nSamp med |"
          " newestAge med | robust MAX | lagEst MIN")
    for m in range(10):
        brows = [b for b in tr.trace_burst
                 if int(b['now'] // 60000) == m]
        if not brows:
            continue
        print("  %3d | %10.0f | %10.0f | %10.0f | %10.0f |"
              " %8.0f | %10.0f | %12.0f | %8.0f | %9.0f |"
              " %11.0f | %10.0f | %10.0f"
              % (m,
                 _med([b['D'] for b in brows]) or -1,
                 _med([b['lag_est'] for b in brows]) or -1,
                 _med([b['dlag'] for b in brows]) or -1,
                 _med([b['margin'] for b in brows]) or -1,
                 _med([b['pipe'] for b in brows]) or -1,
                 _med([b['robust'] for b in brows]) or -1,
                 _med([b['prev_hold'] for b in brows]) or -1,
                 _med([b['hold'] for b in brows]) or -1,
                 _med([b['n_samples'] for b in brows]) or -1,
                 _med([b['newest_age'] for b in brows]) or -1,
                 max(b['robust'] for b in brows),
                 min(b['lag_est'] for b in brows)))
    # ---- the jack bursts: robust > 1500 (the single-burst events
    # that lift hold; hold then decays only 300 ms per MARKER
    # burst, and ~89% of bursts are no-marker) ----
    print("  JACK bursts (robust>1500): now | seq | D | lagEst |"
          " dBased | margin | pipe | robust | prevHold -> hold |"
          " nSamp | newestAge")
    for b in tr.trace_burst:
        if b['robust'] > 1500:
            print("  %8.0f | %3s | %5.0f | %6.0f | %6.0f |"
                  " %6.0f | %4.0f | %6.0f | %7.0f -> %4.0f |"
                  " %5.0f | %9.0f"
                  % (b['now'], b['seq'], b['D'], b['lag_est'],
                     b['dlag'], b['margin'], b['pipe'], b['robust'],
                     b['prev_hold'], b['hold'], b['n_samples'],
                     b['newest_age']))
    print()
    return res, d26, joined


def main():
    t0 = time.time()
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    print("sim1030 TRACE r=1.40 blow-up: per-minute D / hold /"
          " speed / ratio / TRUE end-burst / _vb_end_lag /"
          " valve-input error / backlog / chase / Rb-tx / drops.")
    print()
    worldC = s26._build_world26()
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    kw = s29.spec29()
    runs = {}
    runs['T1'] = trace_run("T1-filmA r1.35 deadV", TraceTracker,
                           worldA, 1.35, s22.LAG22_A, film='A', **kw)
    runs['T2'] = trace_run("T2-filmA r1.40 deadV", TraceTracker,
                           worldA, 1.40, s22.LAG22_A, film='A', **kw)
    runs['T3'] = trace_run("T3-filmA r1.47 deadV", TraceTracker,
                           worldA, 1.47, s22.LAG22_A, film='A', **kw)
    runs['T4'] = trace_run("T4-dense r1.40 deadV", TraceTracker,
                           worldB, 1.40, s22.LAG22_A, film='B', **kw)
    runs['T5'] = trace_run("T5-filmA r1.40 STRONG", TraceTrackerB,
                           worldA, 1.40, s22.LAG22_A, film='A', **kw)
    runs['T6'] = trace_run("T6-choppy r1.40 deadV", TraceTracker,
                           worldC, 1.40, 4500, film='C', **kw)
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
