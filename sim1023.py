#!/usr/bin/env python3
"""sim1023.py — DubLive v1.0.22 ROUND 2: fix the three round-1
blockers, in priority order, stopping at the first variant that
passes GATE23 (no blind stacking). sim1022.py untouched; this
file imports it as s22 and subclasses NewTracker22.

ROUND-1 OUTCOME (sim1022_final_run.txt):
  WINNER = V-c + T-a + T-b + T-d + RATIO_TX on SHIPPED21
  (V-a floor clamp(1/ratio(),1,1.5); V-b catch-up floor
  x(1+endLag/5000); V-c tail valve endLag>1500 ms at 1.5x cap,
  drop (endLag-1000) ms; T-a tx sub-split gap 300 ms; T-b
  MAX_SLICES 40->120; T-d audioAt 1:1 extrapolation; RATIO_TX
  ratio from consecutive tx-ok line positions).
  Film A r1.42: WINNER TRUE end-p90 638 ms (REPRO shipped
  1055), sync p90 493, pairs min 11/60 s, samples min 98,
  dropped audio 1.3%, D end 5424 vs true lag 4507 (+917).
  BUT WINNER is WORSE than shipped on dense film + r1.51 +
  lag 7.8 s — must NOT ship as-is: dense r1.42 end-p90 2515
  (REPRO 1667), D end 7704 vs true lag 4505 (+3199!), D pinned
  11%, dropped 9.9%; film A r1.51 end-p90 2180 (REPRO 1178),
  D 7087 (+2512); lag7.8 end-p90 3560.
  sim1018 regression FAIL: dropped audio steady 8.3% -> 12.1%
  (valve fires too often).
  Root causes (round 1): (1) D-RATCHET from biased tx samples:
  lat_tx = burst_arrival - startEst with startEst = FIRST line
  of a merged group while the burst is a LATER line -> lat_tx
  biased high by up to a whole line (1.5-3 s); the 1200 ms
  residual gate only blocks while D is still right; once D
  creeps up, a +1 s biased sample passes the gate (measured
  against the current D) -> cluster reference rises -> D rises
  -> ratchet. (2) Ratio measurement biased high: floor measured
  only 1.09 (dense) / 1.23-1.27 (film A), display 1.05-1.15x
  instead of 1.42x; probe RATIO_TX: speech/dub median 0.830
  vs true 0.704 (counted speech runs during music + spans
  several lines containing silence). Oracle proves floor =
  true ratio makes V-a work (end-p90 855) but does NOT save
  dense/r1.51/lag7.8 — the error there is the D-ratchet.
  CHASE measured and REJECTED (dense end-p90 3120 < REPRO
  1667) — NOT retried. Hold-decay (21p) and BURST_GAP 150 ms
  (21r) NOT retried. T-c (force-close 6 s) not needed (winner
  has no empty window). The 12 s device sample is TRUE lag
  (playout backlog); the fix is SKIP-AHEAD (V-a+V-c), not CHASE.

ROUND-2 VARIANTS (in order; stop at the first GATE23 pass):
  R-a: startEst from the group's LAST fragment's line
      (group['last'] - asrEma - 500) instead of the first
      (group['first'] - asrEma - 500). One-line override of
      _tx_sentence_start; selection, residual, lat_tx,
      seed_cand and the re-anchor audio position all move
      together. Caveat, checked by the diagnostics: under
      T-a (300 ms sub-split) most groups hold ~1 fragment, so
      first ~= last and R-a may be a near-no-op — the bias
      then lives elsewhere (see the per-source boundary-sample
      diagnostics).
  R-b: asymmetric tx gate once the reference is established:
      accept a tx sample only if lat_tx <= dlag + 600 ms
      upward (downward keeps the old bound). Implemented as a
      pre-check in apply_tx_anchor: if the group that would
      otherwise pass the 1200 ms residual has lat_tx more
      than 600 ms above dlag, count tx_cons and return without
      feeding the sample. Bootstrap untouched (gate off until
      ref_established) so the seed-candidate cluster can form.
  R-c: reference lag = PLAIN MEDIAN of the accepted boundary
      samples (last 30, _bnd_samples) instead of the densest-
      cluster centre of (_bnd_samples + _seed_cand). The frozen
      bootstrap pool (_seed_cand, incl. every wrong in-window
      candidate) stops dragging the reference after
      establishment.
  R-c2: cluster centre of _bnd_samples ONLY (no _seed_cand).
      Same idea as R-c, keeps the densest-window robustness.
  Rf1: ratio measurement with the pause-music counted speech
      subtracted: counted_corr = counted_diff - K * P, where P
      = sum over inter-fragment gaps in the interval of
      max(0, gap - 900 ms) (within-line fragment steps are
      500-900 ms, so only inter-line gaps contribute) and K is
      the music feed-through fraction (diagnostic-fitted,
      default 0.40 = tonal-music VAD gate pass rate).
  Rf2: ratio measured ONLY on clean pairs: the two tx-ok
      groups are consecutive closed groups, their start_ests
      are <= 4500 ms apart, and the pause estimate P <= 600 ms.
      No coefficient, just selection; fewer but cleaner
      measurements.
  Valve variants (on top of the R+Rf winner): V2000 (threshold
      endLag > 2000 ms, keep 1000), V2000cap500 (threshold 2000,
      keep 1500, drop capped at 500 ms/fire), V1500cap500
      (threshold 1500, keep 1000, drop capped at 500 ms/fire).
      Goal: sim1018 dropped audio steady <= 8.3% (old value)
      while film A end-p90 stays < 1000 ms.

GATE23 (10-min sessions; worlds = film A r1.42 / film A r1.51 /
dense r1.42 / film A r1.42 lag7.8; lag0 4.6 s / 7.8 s):
  end-burst p90 TRUE (render, steady) < 1000 ms on film A r1.42
  AND dense r1.42; sync (burst-start) max/p90 < 1000 ms; D
  pinned 0% steady-state (t > 60 s); pairs > 0 in EVERY 60 s
  window; samples/60 s >= 40 (min window); dropped audio
  steady < 5%; line-start steady median < 1200 ms; regressions
  v6 + sim1018 PASS (sim1018 dropped audio <= 8.3%); NEW
  anti-ratchet: |D end - true lag| <= 1000 ms on EVERY world;
  NEW not-worse-than-REPRO: end-p90 AND |D-lag| both <= the
  SHIPPED21 REPRO on the same world. Sync max is reported both
  full-session and steady (t > 60 s): the round-1 first-burst
  cold-start outlier (2571 ms) is called out as an artifact
  when it is the only breach.

DIAGNOSTICS (recorded on every run, printed by diag23):
  per-tx-ok lat_tx error vs the burst's TRUE lag (median/p90,
  fraction > +600 ms); boundary-sample errors split by source
  (tx anchor vs VAD burst/tc); tx group size/span stats;
  per-ratio-pair counted/dub vs TRUE-fed/dub vs corrected/dub
  (true fed from the world, diagnostic only — the tracker never
  sees it); D and reference-lag trajectory per minute.
"""
import math
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1022 as s22
import sim1021 as s21
import sim1020 as s20
import sim1019 as s19
import sim1018 as s18
import sim1014_v6 as v6
from sim1020 import pct

LONG_MS = s22.LONG_MS
RATIO22_A = s22.RATIO22_A
RATIO22_B = s22.RATIO22_B
LAG22_A = s22.LAG22_A
LAG22_B = s22.LAG22_B
WINNER_KW = dict(s22.SHIPPED21, ratio_floor=True, catchup=True,
                 tail_valve=True, ratio_tx=True, tx_subsplit=True,
                 max_slices=120, tx_audioat_extrap=True)
RESULTS23 = []
REPRO23 = {}   # (film, ratio, lag0) -> res dict (SHIPPED21)


def _med(vals):
    return s22._med(vals)


def _p90(vals):
    return s22._p90(vals)


def _mx(vals):
    return s22._mx(vals)


def _smed(vals):
    """SIGNED median (pct() in this sim family is |.| based)."""
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


def _spct(vals, q):
    if not vals:
        return None
    a = sorted(vals)
    return a[min(len(a) - 1, int(len(a) * q))]


# ----------------------------------------------------------------
# Tracker v1.0.22 round 2 (subclass of NewTracker22; sim1022.py
# untouched). Variant switches are CLASS attrs, set per run by
# set23() — the event loop only sets the round-1 flags.
# ----------------------------------------------------------------
class NewTracker23(s22.NewTracker22):
    RATCHET_MODES = ()    # subset of {'Ra','Rb','Rc','Rc2'}
    RB_UP_MS = 600        # R-b: max lat_tx above dlag, once
                          # the reference is established
    RATIO_MODE = 'tx'     # 'tx' = round-1 RATIO_TX (s22);
                          # 'rf1' = pause-subtracted; 'rf2' =
                          # clean-pairs-only (consec groups);
                          # 'rf2b' = clean-pairs-only, interval
                          # based (wall<=4500 + pause<=600, no
                          # consec requirement); 'off' = no ratio
    RATIO_PAUSE_K = 0.40  # Rf1: music feed-through fraction
    RATIO_PAUSE_GAP_MS = 900  # Rf1/Rf2: within-line frag step max
    RF2_MAX_SPAN_MS = 4500
    RF2_MAX_PAUSE_MS = 600
    VALVE_MAX_DROP_MS = None  # None = uncapped (round-1 V-c)
    VALVE_NO_SPEED_GATE = False  # diagnostic only: arm the
                          # valve whenever endLag > threshold,
                          # even if target_speed < 1.49 (round-1
                          # V-c only arms at the 1.5x cap)

    def __init__(self):
        super().__init__()
        # --- diagnostics (never feed the tracker) ---
        self.tx_lat_errs = []     # (now, lat_tx-true_lag, outcome)
        self.tx_lat_blocked = []  # R-b blocks: (now, lat_tx-dlag)
        self.bnd_errs_tx = []     # boundary-sample err, tx source
        self.bnd_errs_vad = []    # boundary-sample err, VAD/tc
        self._in_tx_anchor = False
        self.ref_hist = []        # (now, ref_lag) at each accept
        self.ratio_pairs = []     # per tx-ok pair diagnostics
        self._diag_prev = None    # (pos, cum_dub, start_est, group)
        self._frag_arrivals = []  # every tx-in fragment arrival
        self._cur_frags = []      # frags of the open group
        self.rf_meas = 0
        self.rf_skip = 0
        self.rb_blocks = 0

    # -- R-a: sentence start from the group's LAST fragment --
    def _tx_sentence_start(self, group):
        if 'Ra' in self.RATCHET_MODES:
            return (group['last'] - self.tx_asr_ema
                    - s21.TX_FRAG_DELAY_MS)
        return super()._tx_sentence_start(group)

    # -- R-c / R-c2: reference lag without the frozen seed pool --
    def _ref_lag(self):
        if self.tx_ok > 0 and len(self._bnd_samples) >= 5:
            if 'Rc' in self.RATCHET_MODES:
                s = sorted(self._bnd_samples)
                return s[len(s) // 2]
            if 'Rc2' in self.RATCHET_MODES:
                return self._cluster_centre(
                    list(self._bnd_samples))
        return super()._ref_lag()

    # -- shared helper: the group the parent's apply_tx_anchor
    # would select (same loop, same window, same residual inputs
    # — but WITHOUT the seed_cand side effect, so calling it for
    # diagnostics never perturbs the bootstrap pool). --
    def _best_tx_group(self, burst_arrival, dlag):
        best, best_res = None, None
        for g in self.tx_groups:
            if not g.get('closed'):
                continue
            start_est = self._tx_sentence_start(g)
            if not (burst_arrival - s21.TX_WIN_MS <= start_est
                    <= burst_arrival - 300):
                continue
            res = abs(start_est - (burst_arrival - dlag))
            if best_res is None or res < best_res:
                best, best_res = g, res
        return best, best_res

    def _pause_estimate(self, first_frag, last_frag):
        """Rf1/Rf2: sum of max(0, gap-900) over consecutive tx
        fragment arrivals in (first_frag, last_frag]. Within a
        line fragments step 500-900 ms, so only inter-line gaps
        (line tail + pause + next line's frag delay) contribute.
        The counted speech fed during those gaps is music, not
        dialogue."""
        p = 0
        prev = None
        for a in self._frag_arrivals:
            if a <= first_frag:
                prev = a
                continue
            if a > last_frag:
                break
            if prev is not None:
                p += max(0, a - prev - self.RATIO_PAUSE_GAP_MS)
            prev = a
        return p

    # -- fragment recording (group sizes for the diagnostics;
    # the parent's grouping logic is untouched) --
    def note_tx_in_fragment(self, turn_idx, arrival):
        self._frag_arrivals.append(arrival)
        cur_before = self._tx_cur_group
        super().note_tx_in_fragment(turn_idx, arrival)
        cur_after = self._tx_cur_group
        if cur_before is not None and cur_before is not cur_after:
            cur_before['nfrag23'] = len(self._cur_frags)
            cur_before['frags23'] = list(self._cur_frags)
            self._cur_frags = []
        self._cur_frags.append(arrival)

    # -- boundary-sample source tagging (tx vs VAD/tc) --
    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        if boundary:
            err = sample - true_lag
            if self._in_tx_anchor:
                self.bnd_errs_tx.append(err)
            else:
                self.bnd_errs_vad.append(err)
            self.ref_hist.append((now, self._ref_lag()))
        return super().feed_sample(sample, now, true_lag,
                                   boundary=boundary, **kw)

    # -- R-b pre-check + diagnostics + Rf ratio measurement --
    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        burst_arrival = self._last_burst_arrival
        dlag = self._sel_lag()
        best_pre, res_pre = (None, None)
        if burst_arrival is not None:
            best_pre, res_pre = self._best_tx_group(burst_arrival,
                                                     dlag)
        # R-b: once established, a sample more than RB_UP_MS
        # above dlag is a +1-line error even if it would pass
        # the 1200 ms residual — block it before it feeds D.
        # The VAD-agree path (same line, no sample) is exempt.
        if ('Rb' in self.RATCHET_MODES and self._ref_established()
                and best_pre is not None and res_pre is not None
                and res_pre <= s21.TX_RES_MS):
            start_est = self._tx_sentence_start(best_pre)
            lat_tx = burst_arrival - start_est
            agree = (self._last_burst_marker is not None
                     and abs(self._last_burst_marker['wall']
                             - start_est) <= 500)
            if not agree and lat_tx > dlag + self.RB_UP_MS:
                self.tx_cons += 1
                self.rb_blocks += 1
                self.tx_lat_blocked.append(
                    (now, lat_tx - dlag, lat_tx - true_lag))
                self.tx_lat_errs.append(
                    (now, lat_tx - true_lag, 'rb-block'))
                return
        ok_before = self.tx_ok
        self._in_tx_anchor = True
        try:
            super().apply_tx_anchor(now, burst_seq, true_lag,
                                    true_sample)
        finally:
            self._in_tx_anchor = False
        # Diagnostics for every tx-anchor call that resolved.
        if best_pre is not None and burst_arrival is not None:
            start_est = self._tx_sentence_start(best_pre)
            lat_tx = burst_arrival - start_est
            if self.tx_ok > ok_before:
                oc = 'ok'
            elif self.tx_ok == ok_before:
                oc = 'no-mk/cons/agree'
            else:
                oc = 'ok'
            self.tx_lat_errs.append((now, lat_tx - true_lag, oc))
        # Ratio-pair diagnostics + Rf1/Rf2 measurement, on every
        # accepted tx sample (same prev-tracking as s22).
        if self.tx_ok > ok_before:
            pos = self._tx_last_audio_pos
            if pos is not None and best_pre is not None:
                start_est = self._tx_sentence_start(best_pre)
                gidx = (self.tx_groups.index(best_pre)
                        if best_pre in self.tx_groups else -1)
                if self._diag_prev is not None:
                    ppos, pdub, pse, pgroup, pgidx = self._diag_prev
                    counted_diff = pos - ppos
                    dub_diff = self.cum_dub - pdub
                    pause = self._pause_estimate(pgroup['first'],
                                                 best_pre['last'])
                    wall_span = start_est - pse
                    self.ratio_pairs.append({
                        'counted': counted_diff,
                        'dub': dub_diff, 'pause': pause,
                        'wall': wall_span,
                        'consec': (gidx == pgidx + 1
                                   if gidx >= 0 and pgidx >= 0
                                   else False)})
                    if self.RATIO_MODE in ('rf1', 'rf2'):
                        counted_use = counted_diff
                        if self.RATIO_MODE == 'rf1':
                            counted_use = (counted_diff
                                           - self.RATIO_PAUSE_K
                                           * pause)
                        else:  # rf2: clean pairs only
                            if not (self.ratio_pairs[-1]['consec']
                                    and wall_span
                                    <= self.RF2_MAX_SPAN_MS
                                    and pause
                                    <= self.RF2_MAX_PAUSE_MS):
                                self.rf_skip += 1
                                counted_use = None
                        if counted_use is not None:
                            expected = counted_use * 1.3
                            if (counted_use >= 500 and 0.7 * expected
                                    <= dub_diff <= 1.6 * expected):
                                self.sum_counted += counted_use
                                self.sum_total += dub_diff
                                self.ratio_tx_meas += 1
                                self.rf_meas += 1
                self._diag_prev = (pos, self.cum_dub, start_est,
                                   best_pre, gidx)

    # -- valve cap: drop at most VALVE_MAX_DROP_MS per fire --
    def on_dub_burst(self, now, true_lag, true_sample=None):
        if self.VALVE_NO_SPEED_GATE:
            # Skip s22's arming (it requires target_speed >=
            # 1.49) and arm directly: same threshold/keep, no
            # speed gate, then the cap. s21's on_dub_burst has
            # no valve logic, so calling it via the grandparent
            # keeps the rest identical.
            super(s22.NewTracker22, self).on_dub_burst(
                now, true_lag, true_sample)
            if (self.TAIL_VALVE
                    and self._vb_play_seq is not None
                    and self._vb_play_seq
                    != self._vb_valve_used_seq
                    and self._vb_end_lag > self.VALVE_THRESH_MS):
                drop = int(self._vb_end_lag - self.VALVE_KEEP_MS)
                cap = self.VALVE_MAX_DROP_MS
                if cap is not None:
                    drop = min(drop, cap)
                if drop > 0:
                    self._tail_drop_remaining += drop
                    self.tail_valve_fires += 1
                    self.tail_valve_drop_ms += drop
                    self._vb_valve_used_seq = self._vb_play_seq
            return
        rem0 = getattr(self, '_tail_drop_remaining', 0)
        super().on_dub_burst(now, true_lag, true_sample)
        cap = self.VALVE_MAX_DROP_MS
        if cap is not None:
            added = (getattr(self, '_tail_drop_remaining', 0)
                     - rem0)
            if added > cap:
                excess = added - cap
                self._tail_drop_remaining -= excess
                self.tail_valve_drop_ms -= excess


def set23(**attrs):
    """Set round-2 class attrs on NewTracker23 for the next run.
    Unspecified attrs keep their previous value — callers pass
    the full variant spec each time."""
    for k, v in attrs.items():
        setattr(NewTracker23, k, v)


def winner23_kw(ratio_tx=True):
    """WINNER base kwargs for run22. ratio_tx=False when the
    round-2 ratio mode (Rf1/Rf2) replaces s22's RATIO_TX."""
    kw = dict(WINNER_KW)
    kw['ratio_tx'] = ratio_tx
    return kw


# ----------------------------------------------------------------
# Harness (mirrors s22.do_run, extended with round-2 metrics).
# ----------------------------------------------------------------
def do_run23(label, tracker_cls, world, ratio, lag0, film='A',
             **kw):
    (frames, ls, lu, vad, sent, vs, txin, txsrc) = world
    dub, skipped = s21.build_dub21(frames, sent, ls, lu,
                                   ratio=ratio, lag0=lag0, speed=1.5)
    print("##### %s: %d vad events, %d VAD turns (%d lines),"
          " %d dub chunks, %d tx-in fragments #####"
          % (label, len(vad), vs['turns'], len(ls), len(dub),
             len(txin)))
    tr, dh, s60, tp60, holdsat, bursts, extra = s22.run22(
        tracker_cls, vad, dub, [], ls, txin, txsrc, min_buf=16384,
        total_ms=LONG_MS, **kw)
    steady, kot, perwin, lag = s22.report22(
        label, tr, dh, s60, tp60, holdsat, bursts, extra, ls, lu,
        dub, vs, 1.0 / ratio)
    s22.gate22(label, tr, tp60, steady[0],
               max(d for _, d in dh), steady[2], steady[3],
               steady[1], s60, rcov=extra.get('render_cov'),
               rgap=extra.get('render_gap'), end_late=steady[4],
               bursts_seen=bursts,
               tp_first60=extra.get('tp_first60'),
               first_sync_at=extra.get('first_sync_at'),
               d_pinned_frac=extra.get('d_pinned_frac'),
               perwin=perwin, lag=lag, sync_errs=tr.sync_errs,
               end_late_true=kot['true_end_steady'],
               sp_true=kot['true_all_steady'])
    s22.device_signature_check22(label, tr, dh, s60, tp60, bursts,
                                 vs, extra, vad, len(ls), lag, kot,
                                 film)
    pairs_win = [w['pairs'] for w in perwin]
    samp_win = [w['samples'] for w in perwin]
    drop_steady = (100.0 * tr.dropped_steady_ms
                   / max(1, tr.total_steady_ms))
    steady_pin = [d for (t, d) in dh if t > 60000 and d >= 7900]
    steady_pin_frac = len(steady_pin) / max(
        1, len([1 for (t, d) in dh if t > 60000]))
    sync_steady = [e for (e, t) in zip(tr.sync_errs, tr.sync_times)
                   if t > 60000]
    res = {'label': label, 'film': film, 'ratio': ratio,
           'lag0': lag0, 'gate22': None,
           'end_p90_true': _p90(kot['true_end_steady']),
           'end_p90_true_full': _p90(kot['true_end']),
           'end_p90_kot_play': _p90(kot['kot_play']),
           'sync_max': _mx(tr.sync_errs),
           'sync_p90': _p90(tr.sync_errs),
           'sync_max_steady': _mx(sync_steady),
           'sync_p90_steady': _p90(sync_steady),
           'sp_p90': _p90(kot['true_all_steady']),
           'sp_med': pct(kot['true_all_steady'], 0.5)
                       if kot['true_all_steady'] else None,
           'ls_med': pct(steady[0], 0.5) if steady[0] else None,
           'd_pinned': extra.get('d_pinned_frac', -1),
           'd_pinned_steady': steady_pin_frac,
           'd_max': max(d for _, d in dh),
           'd_end': lag['d_end'],
           'true_med': lag['true_med'],
           'true_max': lag['true_max'],
           'd_end_err': abs(lag['d_end'] - lag['true_med'])
                        if lag['true_med'] is not None else None,
           'pairs_min_win': min(pairs_win) if pairs_win else 0,
           'pairs_last_win': tp60,
           'pairs_first60': extra.get('tp_first60', -1),
           'samples_min_win': min(samp_win) if samp_win else 0,
           'samples_last': s60,
           'drop_steady': drop_steady,
           'unpaired_frac': (1.0 - tr.turn_pairs
                             / max(1, bursts)),
           'd_gap': lag['d_gap'],
           'dropped_large': lag['dropped_large'],
           'tx_ok': getattr(tr, 'tx_ok', 0),
           'tx_no_mk': getattr(tr, 'tx_no_mk', 0),
           'tx_cons': getattr(tr, 'tx_cons', 0),
           'tx_agree': getattr(tr, 'tx_agree', 0),
           'tx_reasons': dict(getattr(tr, 'tx_no_mk_reasons', {})),
           'chase_accepts': lag['chase_accepts'],
           'valve_fires': getattr(tr, 'tail_valve_fires', 0),
           'valve_dropped_ms': getattr(tr, 'tail_dropped_ms', 0),
           'ratio_tx_meas': lag['ratio_tx_meas'],
           'ratio_burst_meas': lag['ratio_burst_meas'],
           'floor_mean': lag['floor_mean'],
           'floor_first60': lag['floor_first60'],
           'floor_steady': lag['floor_steady'],
           'speed_mean': lag['speed_mean'],
           'd_hist': list(dh),
           'perwin': perwin,
           'rb_blocks': getattr(tr, 'rb_blocks', 0),
           'rf_meas': getattr(tr, 'rf_meas', 0),
           'rf_skip': getattr(tr, 'rf_skip', 0),
           'seek_dropped': getattr(tr, 'seek_frame_dropped', 0),
           'seek_checked': getattr(tr, 'seek_frame_checked', 0),
           'seek_shown': len(getattr(tr, 'frame_residuals', []))}
    res['stretch_pass'] = (
        res['end_p90_true'] is not None
        and res['end_p90_true'] < 1000
        and res['sync_max'] is not None and res['sync_max'] < 1000
        and res['sync_p90'] is not None and res['sync_p90'] < 1000
        and res['sp_p90'] is not None and res['sp_p90'] < 1000
        and res['d_pinned_steady'] == 0
        and res['drop_steady'] < 5.0
        and res['ls_med'] is not None and res['ls_med'] < 1200
        and res['d_gap'] is not None and res['d_gap'] < 3000
        and res['true_max'] is not None and res['true_max'] < 20000
        and res['d_max'] <= 8000)
    diag23(label, tr, frames, sent, ls, lu, dub, res)
    res['gate23'] = gate23(label, res)
    RESULTS23.append(res)
    return res


def diag23(label, tr, frames, sent, ls, lu, dub, res):
    """Round-2 diagnostics: where the tx lat bias lives, what
    the boundary samples feed D, group structure, ratio-pair
    truth (world-fed speech, diagnostic only)."""
    print("DIAG23 %s:" % label)
    # --- tx lat_tx errors (accepted + R-b blocked) ---
    oks = [e for (_, e, o) in getattr(tr, 'tx_lat_errs', [])
           if o == 'ok']
    blk = [e for (_, e, o) in getattr(tr, 'tx_lat_errs', [])
           if o == 'rb-block']
    if oks:
        print("  tx lat_tx err vs TRUE lag (ok, n=%d): median %.0f"
              " (signed) / p90|.| %.0f / max %.0f ms; frac >+600"
              " ms %.2f; frac <-600 ms %.2f"
              % (len(oks), _smed(oks), _p90(oks), max(oks),
                 len([e for e in oks if e > 600]) / len(oks),
                 len([e for e in oks if e < -600]) / len(oks)))
    else:
        print("  tx lat_tx err (ok): n=0")
    if blk:
        print("  tx lat_tx err (R-b blocked, n=%d): median %.0f"
              " (signed) / p90|.| %.0f ms"
              % (len(blk), _smed(blk), _p90(blk)))
    # --- boundary samples by source (what feeds D's window) ---
    for nm, arr in (('tx', getattr(tr, 'bnd_errs_tx', [])),
                    ('vad', getattr(tr, 'bnd_errs_vad', []))):
        if arr:
            print("  boundary-sample err vs TRUE lag (%s, n=%d):"
                  " median %.0f (signed) / p10 %.0f / p90 %.0f /"
                  " max %.0f; frac |err|>1500 %.2f"
                  % (nm, len(arr), _smed(arr), _spct(arr, 0.1),
                     _spct(arr, 0.9), max(arr),
                     len([e for e in arr if abs(e) > 1500])
                     / len(arr)))
        else:
            print("  boundary-sample err (%s): n=0" % nm)
    # --- D / reference trajectory per minute vs true lag ---
    rh = getattr(tr, 'ref_hist', [])
    dh = res['d_hist']
    per_min = []
    for i in range(10):
        a, b = i * 60000, (i + 1) * 60000
        ds = [d for (t, d) in dh if a <= t < b]
        rs = [r for (t, r) in rh if a <= t < b]
        tl = [item[2] for item in dub if a <= item[0] < b]
        per_min.append((round(_med(ds)) if ds else None,
                        round(_med(rs)) if rs else None,
                        round(_med(tl)) if tl else None))
    print("  per-minute (D med / refLag med / true-lag med): %s"
          % (per_min,))
    print("  D end %d vs true lag med %.0f -> |err| %s ms;"
          " D pinned steady %.0f%%; R-b blocks %d"
          % (res['d_end'], res['true_med'], res['d_end_err'],
             100.0 * res['d_pinned_steady'],
             getattr(tr, 'rb_blocks', 0)))
    # --- tx group structure ---
    nfrags = [g.get('nfrag23') for g in tr.tx_groups
              if g.get('nfrag23') is not None]
    if getattr(tr, '_cur_frags', None) and tr._tx_cur_group is not None:
        nfrags = nfrags + [len(tr._cur_frags)]
    if nfrags:
        spans = []
        for g in tr.tx_groups:
            if 'frags23' in g and len(g['frags23']) >= 2:
                spans.append(g['frags23'][-1] - g['frags23'][0])
        print("  tx groups: %d seen, frags/group median %s "
              "(1-frag %.0f%%, >=3-frag %.0f%%)%s"
              % (tr.tx_groups_seen, _med(nfrags),
                 100.0 * len([n for n in nfrags if n <= 1])
                 / len(nfrags),
                 100.0 * len([n for n in nfrags if n >= 3])
                 / len(nfrags),
                 ("; multi-frag group span median %d ms"
                  % _med(spans)) if spans else "; no multi-frag"
                 " groups"))
    # --- ratio pairs: counted vs true-fed vs corrected ---
    pairs = getattr(tr, 'ratio_pairs', [])
    if pairs:
        fed = s21._per_line_speech(frames, sent, ls, lu)
        counted_ratios, corr_ratios = [], []
        pause_k = getattr(tr, 'RATIO_PAUSE_K', 0.40)
        # Report counted/dub and pause-corrected/dub (tracker-
        # side numbers) against the true world fed/dub total.
        # Per-pair true fed would need the pair's start_ests;
        # the totals give the same bias verdict.
        for pr in pairs:
            if pr['dub'] > 0 and pr['counted'] > 0:
                counted_ratios.append(pr['counted'] / pr['dub'])
                corr = (pr['counted'] - pause_k * pr['pause'])
                if corr > 0:
                    corr_ratios.append(corr / pr['dub'])
        tot_fed = sum(fed.values())
        tot_dub = sum(item[1] for item in dub)
        print("  ratio pairs n=%d (consec %.0f%%): counted/dub"
              " median %.3f (true world fed/dub %.3f);"
              " pause-corrected/dub (K=%.2f) median %.3f;"
              " pause ms/pair median %.0f; floor median %.2f"
              " (steady %.2f); ratio display %.2fx (true %.2fx)"
              % (len(pairs),
                 100.0 * len([p for p in pairs if p['consec']])
                 / len(pairs),
                 _med(counted_ratios) if counted_ratios else -1,
                 tot_fed / tot_dub if tot_dub else -1,
                 pause_k,
                 _med(corr_ratios) if corr_ratios else -1,
                 _med([p['pause'] for p in pairs]) if pairs else -1,
                 res['floor_mean'] or -1, res['floor_steady'] or -1,
                 1.0 / tr.ratio(), 1.0 / (tot_fed / tot_dub)
                 if tot_fed else -1))
        print("  ratio display check: tracker ratio() %.3f vs"
              " true fed/dub %.3f; tx-ok ratio measurements %d"
              " (rf meas %d / skipped %d)"
              % (tr.ratio(), tot_fed / tot_dub if tot_dub else -1,
                 res['ratio_tx_meas'], res['rf_meas'],
                 res['rf_skip']))
    else:
        print("  ratio pairs: n=0; floor median %.2f (steady"
              " %.2f); ratio display %.2fx"
              % (res['floor_mean'] or -1, res['floor_steady'] or -1,
                 1.0 / tr.ratio()))
    print()


def gate23(label, res):
    """GATE23 for one run. The REPRO comparison needs the
    SHIPPED21 REPRO on the same (film, ratio, lag0); if it is
    not in REPRO23 yet, those two checks print PENDING."""
    rep = REPRO23.get((res['film'], res['ratio'], res['lag0']))
    checks = {
        "end-p90 TRUE steady < 1000 ms":
            res['end_p90_true'] is not None
            and res['end_p90_true'] < 1000,
        "sync max < 1000 ms (full session)":
            res['sync_max'] is not None and res['sync_max'] < 1000,
        "sync p90 < 1000 ms":
            res['sync_p90'] is not None and res['sync_p90'] < 1000,
        "sync max < 1000 ms (steady, t>60 s)":
            res['sync_max_steady'] is not None
            and res['sync_max_steady'] < 1000,
        "D pinned 0% steady (t>60 s)":
            res['d_pinned_steady'] == 0,
        "pairs > 0 in EVERY 60 s window":
            res['pairs_min_win'] > 0,
        "samples/60 s >= 40 (min window)":
            res['samples_min_win'] >= 40,
        "dropped audio steady < 5%":
            res['drop_steady'] < 5.0,
        "line-start steady median < 1200 ms":
            res['ls_med'] is not None and res['ls_med'] < 1200,
        "|D end - true lag| <= 1000 ms (anti-ratchet)":
            res['d_end_err'] is not None
            and res['d_end_err'] <= 1000,
        "D tracks last sample (gap < 3000 ms)":
            res['d_gap'] is not None and res['d_gap'] < 3000,
        "no true lag ever > 20000 ms":
            res['true_max'] is not None and res['true_max'] < 20000,
    }
    if rep is not None:
        checks["end-p90 <= REPRO shipped (same world)"] = (
            res['end_p90_true'] <= rep['end_p90_true'])
        checks["|D-lag| <= REPRO shipped (same world)"] = (
            res['d_end_err'] <= rep['d_end_err'])
    print("GATE23 %s:" % label)
    for k, ok in checks.items():
        print("  %-46s %s" % (k, "PASS" if ok else "FAIL"))
    if rep is None:
        print("  %-46s %s" % ("end-p90/|D-lag| vs REPRO",
                              "PENDING (no REPRO yet)"))
    strict = all(checks.values())
    # ex-cold-start verdict: sync-max full-session is the only
    # known round-1 artifact (one first-burst sample, 2571 ms);
    # report it separately so a variant is not hidden behind it.
    nocold = dict(checks)
    nocold.pop("sync max < 1000 ms (full session)", None)
    excold = all(nocold.values())
    print("  GATE23: %s (strict) / %s (ex-cold-start sync max)"
          % ("PASS" if strict else "FAIL",
             "PASS" if excold else "FAIL"))
    print()
    return strict


# ----------------------------------------------------------------
# Regressions (mirror sim1022 §5, with the round-2 winner flags).
# ----------------------------------------------------------------
_REG18_OLD = {}


def reg23(kw23, tag):
    """v6 + sim1018 regressions for the given round-2 winner
    kwargs. Returns (reg6, reg18, drop18_new, drop18_old)."""
    print("##### regression: v6 world (sim1017) — %s #####" % tag)
    vad6, dub6, completes6, line_start6 = s20.s17.build_world()
    txin6, txsrc6 = s21.build_tx21([], [], {}, {})
    old6, dh6, s606, tp606, holdsat6, bursts6, ex6 = s22.run22(
        s20.NewTracker20, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384)
    steady_old6 = s21.report21("OLD (v1.0.20), v6 world", old6, dh6,
                               s606, tp606, holdsat6, bursts6, ex6,
                               line_start6, {})
    new6, dh6b, s60n6, tp60n6, holdsatn6, burstsn6, ex6b = s22.run22(
        NewTracker23, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384, **kw23)
    steady_new6 = s21.report21("NEW (v1.0.22r2 %s), v6 world" % tag,
                               new6, dh6b, s60n6, tp60n6, holdsatn6,
                               burstsn6, ex6b, line_start6, {})
    fh_old6 = old6.line_start_fresh_errs
    fh_new6 = new6.line_start_fresh_errs
    ls_old6 = steady_old6[0][:len(steady_old6[0]) // 2]
    ls_new6 = steady_new6[0][:len(steady_new6[0]) // 2]
    print("REGRESSION GATE (v6 world, WITHIN-CAP early half):"
          " fresh-hold |err| median OLD %.0f -> NEW %.0f"
          " (need NEW < 300); actual line-start steady median"
          " OLD %.0f -> NEW %.0f (need NEW <= OLD);"
          " pairs/60s OLD %d -> NEW %d (need NEW >= OLD)"
          % (pct(fh_old6[:len(fh_old6) // 2], 0.5),
             pct(fh_new6[:len(fh_new6) // 2], 0.5),
             pct(ls_old6, 0.5), pct(ls_new6, 0.5),
             tp606, tp60n6))
    reg6 = (pct(fh_new6[:len(fh_new6) // 2], 0.5) < 300
            and pct(ls_new6, 0.5) <= pct(ls_old6, 0.5)
            and tp60n6 >= tp606)
    print("REGRESSION (v6, within-cap): %s"
          % ("PASS" if reg6 else "FAIL"))
    print()

    print("##### regression: sim1018 device world (tc ON, no music)"
          " — %s #####" % tag)
    vad18, dub18, completes18, line_start18, line_counted18 = \
        s18.build_world_device(tc_on=True, speed=2.5)
    if 'old' not in _REG18_OLD:
        old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = \
            s22.run22(s19.NewTracker19, vad18, dub18, completes18,
                      line_start18, txin6, txsrc6, min_buf=4096,
                      line_counted=line_counted18)
        steady_old18 = s21.report21(
            "OLD (v1.0.19), sim1018 world, tc ON", old18, dh18,
            s6018, tp6018, holdsat18, bursts18, ex18,
            line_start18, {})
        _REG18_OLD.update(old=old18, steady=steady_old18,
                          tp60=tp6018)
    old18 = _REG18_OLD['old']
    steady_old18 = _REG18_OLD['steady']
    tp6018 = _REG18_OLD['tp60']
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        s22.run22(NewTracker23, vad18, dub18, completes18,
                  line_start18, txin6, txsrc6, min_buf=4096,
                  line_counted=line_counted18, **kw23)
    steady_new18 = s21.report21(
        "NEW (v1.0.22r2 %s), sim1018 world, tc ON" % tag, new18,
        dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b,
        line_start18, {})
    drop_old18 = (old18.dropped_steady_ms /
                  max(1, old18.total_steady_ms))
    drop_new18 = (new18.dropped_steady_ms /
                  max(1, new18.total_steady_ms))
    reg18 = (tp60n18 >= tp6018
             and pct(steady_new18[0], 0.5)
             <= pct(steady_old18[0], 0.5)
             and drop_new18 <= drop_old18
             and new18.dropped_slices <= old18.dropped_slices + 2)
    print("REGRESSION (sim1018 world, re-scoped): pairs/60s OLD"
          " %d -> NEW %d; line-start steady median OLD %.0f ->"
          " NEW %.0f (need NEW <= OLD); dropped audio steady"
          " OLD %.1f%% -> NEW %.1f%% (need NEW <= OLD, round-2"
          " cap 8.3%%); dropped slices OLD %d -> NEW %d: %s"
          % (tp6018, tp60n18, pct(steady_old18[0], 0.5),
             pct(steady_new18[0], 0.5), drop_old18 * 100,
             drop_new18 * 100, old18.dropped_slices,
             new18.dropped_slices, "PASS" if reg18 else "FAIL"))
    print("SIM1018 dropped-audio cap check: NEW %.1f%% vs cap"
          " 8.3%% -> %s"
          % (drop_new18 * 100,
             "PASS" if drop_new18 <= 0.083 else "FAIL"))
    print()
    return reg6, reg18, drop_new18, drop_old18


def spec23(ratchet=(), ratio_mode='tx', valve_thresh=1500,
           valve_keep=1000, valve_cap=None, pause_k=0.40,
           valve_no_speed_gate=False):
    """One call sets the complete round-2 variant spec."""
    set23(RATCHET_MODES=tuple(ratchet), RATIO_MODE=ratio_mode,
          VALVE_THRESH_MS=valve_thresh, VALVE_KEEP_MS=valve_keep,
          VALVE_MAX_DROP_MS=valve_cap, RATIO_PAUSE_K=pause_k,
          VALVE_NO_SPEED_GATE=valve_no_speed_gate)


def variant_ok(results):
    """(strict_all, excold_all) over a list of run res dicts."""
    strict = all(r['gate23'] for r in results)
    excold = True
    for r in results:
        # ex-cold-start = strict minus the full-session sync-max
        # bar (the round-1 first-burst artifact).
        if not (r['end_p90_true'] < 1000
                and r['sync_p90'] < 1000
                and r['sync_max_steady'] < 1000
                and r['d_pinned_steady'] == 0
                and r['pairs_min_win'] > 0
                and r['samples_min_win'] >= 40
                and r['drop_steady'] < 5.0
                and r['ls_med'] is not None and r['ls_med'] < 1200
                and r['d_end_err'] is not None
                and r['d_end_err'] <= 1000
                and r['d_gap'] is not None and r['d_gap'] < 3000
                and r['true_max'] is not None
                and r['true_max'] < 20000):
            excold = False
        rep = REPRO23.get((r['film'], r['ratio'], r['lag0']))
        if rep is not None and not (
                r['end_p90_true'] <= rep['end_p90_true']
                and r['d_end_err'] <= rep['d_end_err']):
            excold = False
    return strict, excold


def summary23():
    print("=" * 110)
    print("SUMMARY23 (10-min sessions; end-p90 = TRUE render,"
          " steady window; p90 = p90 of |value|):")
    print("%-38s %7s %7s %7s %7s %6s %6s %5s %5s %6s %6s %6s"
          % ("run", "endp90", "kotPl", "syncMx", "syncP90",
             "DpinS%", "prMin", "smMin", "drop%", "Dend",
             "Derr", "floor"))
    for r in RESULTS23:
        print("%-38s %7.0f %7.0f %7.0f %7.0f %6.0f %6d %5d %5.1f"
              " %6.0f %6.0f %6.2f"
              % (r['label'][:38], r['end_p90_true'] or -1,
                 r['end_p90_kot_play'] or -1, r['sync_max'] or -1,
                 r['sync_p90'] or -1, 100.0 * r['d_pinned_steady'],
                 r['pairs_min_win'], r['samples_min_win'],
                 r['drop_steady'], r['d_end'],
                 r['d_end_err'] if r['d_end_err'] is not None
                 else -1, r['floor_steady'] or -1))
    print()
    print("GATE23 per run (strict / ex-cold-start sync max):")
    for r in RESULTS23:
        print("  %-38s strict=%s" % (r['label'][:38], r['gate23']))


def main():
    import time
    t_start = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'full'
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("sim1023 ROUND 2: fix D-ratchet (R-a/R-b/R-c), ratio"
          " estimator (Rf1/Rf2), tail valve (V2000/caps)."
          " Worlds: film A r1.42/r1.51 lag4.6, dense r1.42,"
          " film A r1.42 lag7.8; 10-min sessions, tc OFF.")
    print()
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    for tag, w in (("film A", worldA), ("film B dense", worldB)):
        (frames, ls, lu, vad, sent, vs, txin, txsrc) = w
        lens = sorted((e - s) / 1000.0
                      for (s, e, lid, sms) in sent)
        print("WORLD %s: %d lines, %d VAD turns (median %.1f s,"
              " max %.1f s), turns60=%d, fed60=%d ms, gate"
              " rejects/60 s ~%.0f, tx-in fragments %d"
              % (tag, len(ls), vs['turns'],
                 lens[len(lens) // 2] if lens else 0,
                 lens[-1] if lens else 0, vs['turns60'],
                 vs['fed60'],
                 (vs['start_gate_rejects'] + vs['gate_rejects'])
                 * 60.0 / 600.0, len(txin)))
    print()
    worlds4 = [(worldA, RATIO22_A, LAG22_A, 'A'),
               (worldA, RATIO22_B, LAG22_A, 'A'),
               (worldB, RATIO22_A, LAG22_A, 'B'),
               (worldA, RATIO22_A, LAG22_B, 'A')]

    # ---- Phase 0: REPRO SHIPPED21 on the 4 worlds. ----
    print("#### PHASE 0: REPRO SHIPPED21 (4 worlds) ####")
    for (w, ratio, lag0, film) in worlds4:
        r = do_run23("REPRO23 shipped r%.2f lag%.1f %s"
                     % (ratio, lag0 / 1000.0,
                        'dense' if film == 'B' else 'filmA'),
                     s21.NewTracker21, w, ratio, lag0, film=film,
                     **s22.SHIPPED21)
        REPRO23[(film, ratio, lag0)] = r
    print("REPRO23 baselines: %s"
          % ({k: (round(v['end_p90_true']),
                 round(v['d_end_err']))
             for k, v in REPRO23.items()},))
    print()

    # ---- Phase DIAG: round-1 WINNER on the 4 worlds. ----
    print("#### PHASE DIAG: round-1 WINNER (4 worlds) ####")
    spec23(ratchet=(), ratio_mode='tx', valve_thresh=1500,
           valve_keep=1000, valve_cap=None)
    winner_runs = []
    for (w, ratio, lag0, film) in worlds4:
        winner_runs.append(do_run23(
            "WINNER23 r1 r%.2f lag%.1f %s"
            % (ratio, lag0 / 1000.0,
               'dense' if film == 'B' else 'filmA'),
            NewTracker23, w, ratio, lag0, film=film,
            **winner23_kw(ratio_tx=True)))
    print("WINNER23 (round-1 combo, re-run): %s"
          % ([(round(r['end_p90_true']), round(r['d_end_err']))
              for r in winner_runs],))
    print()
    if mode == 'diag':
        summary23()
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return

    # ---- Phase 1: R-ladder, one mode at a time on WINNER. ----
    print("#### PHASE 1: R-ladder on WINNER base ####")
    r_winner = None
    r_winner_modes = None
    for modes in [('Ra',), ('Rb',), ('Rc',), ('Rc2',),
                  ('Ra', 'Rb'), ('Ra', 'Rc'), ('Rb', 'Rc')]:
        spec23(ratchet=modes, ratio_mode='tx', valve_thresh=1500,
               valve_keep=1000, valve_cap=None)
        runs = [do_run23(
            "R%s r%.2f lag%.1f %s"
            % ('+'.join(modes), ratio, lag0 / 1000.0,
               'dense' if film == 'B' else 'filmA'),
            NewTracker23, w, ratio, lag0, film=film,
            **winner23_kw(ratio_tx=True))
            for (w, ratio, lag0, film) in worlds4]
        strict, excold = variant_ok(runs)
        print("R-LADDER %s: strict=%s excold=%s (end-p90 %s;"
              " |D-lag| %s)"
              % ('+'.join(modes), strict, excold,
                 [round(r['end_p90_true']) for r in runs],
                 [round(r['d_end_err']) for r in runs]))
        print()
        if excold:
            r_winner, r_winner_modes = runs, modes
            print("R-WINNER: %s (first variant passing GATE23"
                  " ex-cold-start on all 4 worlds)"
                  % ('+'.join(modes),))
            break
        print("R %s does NOT pass GATE23 on all 4 worlds —"
              " trying the next mode." % ('+'.join(modes),))
    if r_winner is None:
        print("NO single/combo R mode passed GATE23 ex-cold-start"
              " on all 4 worlds — continuing to the ratio phase"
              " on the least-bad R mode (best |D-lag|).")
        # (The least-bad pick is made after the ratio phase;
        # keep the R-c runs as the fallback base.)

    # ---- Phase 2: ratio estimator, on the R-winner base. ----
    print("#### PHASE 2: ratio estimator (Rf1 / Rf2) ####")
    base_modes = r_winner_modes if r_winner_modes else ('Rc',)
    rf_winner = None
    rf_winner_mode = None
    for rfmode in ('rf1', 'rf2'):
        spec23(ratchet=base_modes, ratio_mode=rfmode,
               valve_thresh=1500, valve_keep=1000, valve_cap=None)
        runs = [do_run23(
            "R%s+%s r%.2f lag%.1f %s"
            % ('+'.join(base_modes), rfmode, ratio, lag0 / 1000.0,
               'dense' if film == 'B' else 'filmA'),
            NewTracker23, w, ratio, lag0, film=film,
            **winner23_kw(ratio_tx=False))
            for (w, ratio, lag0, film) in worlds4]
        strict, excold = variant_ok(runs)
        print("RATIO %s on R%s: strict=%s excold=%s; floor"
              " steady %s (target film A r1.42 ~1.35-1.45,"
              " r1.51 ~1.45-1.55); end-p90 %s"
              % (rfmode, '+'.join(base_modes), strict, excold,
                 [round(r['floor_steady'], 2)
                  if r['floor_steady'] else None for r in runs],
                 [round(r['end_p90_true']) for r in runs]))
        print()
        if excold and rf_winner is None:
            rf_winner, rf_winner_mode = runs, rfmode
            print("RF-WINNER: %s" % rfmode)
    final_modes = base_modes
    final_ratio_mode = rf_winner_mode if rf_winner_mode else 'tx'
    final_kw = winner23_kw(ratio_tx=(final_ratio_mode == 'tx'))

    # ---- Phase 3: valve variants, on the R+Rf winner. ----
    print("#### PHASE 3: tail-valve variants (sim1018 cap) ####")
    valve_winner = None
    valve_winner_spec = None
    for (vtag, vth, vkeep, vcap) in (
            ('V2000', 2000, 1000, None),
            ('V2000cap500', 2000, 1500, 500),
            ('V1500cap500', 1500, 1000, 500)):
        spec23(ratchet=final_modes, ratio_mode=final_ratio_mode,
               valve_thresh=vth, valve_keep=vkeep, valve_cap=vcap)
        rA = do_run23("R%s+%s+%s filmA r1.42"
                      % ('+'.join(final_modes), final_ratio_mode,
                         vtag),
                      NewTracker23, worldA, RATIO22_A, LAG22_A,
                      film='A', **final_kw)
        reg6, reg18, drop18, drop18_old = reg23(final_kw,
                                                "%s+%s"
                                                % (vtag,
                                                   final_ratio_mode))
        print("VALVE %s: film A end-p90 %.0f ms; sim1018 dropped"
              " %.1f%% (OLD %.1f%%, cap 8.3%%); v6 %s, sim1018"
              " %s" % (vtag, rA['end_p90_true'], drop18 * 100,
                       drop18_old * 100,
                       "PASS" if reg6 else "FAIL",
                       "PASS" if reg18 else "FAIL"))
        print()
        if (rA['end_p90_true'] < 1000 and drop18 <= 0.083
                and reg6 and reg18 and valve_winner is None):
            valve_winner = (vth, vkeep, vcap)
            valve_winner_spec = vtag
            print("VALVE-WINNER: %s" % vtag)
    if valve_winner is None:
        print("NO valve variant met film-A end-p90 < 1000 AND"
              " sim1018 dropped <= 8.3%% with both regressions"
              " passing — keeping the round-1 valve (1500/1000/"
              "uncapped) for the final runs and reporting the"
              " trade-off.")
        valve_winner = (1500, 1000, None)
        valve_winner_spec = 'V1500-orig'

    # ---- Phase 4: final winner on the 4 worlds + regressions.
    print("#### PHASE 4: FINAL WINNER (4 worlds) ####")
    spec23(ratchet=final_modes, ratio_mode=final_ratio_mode,
           valve_thresh=valve_winner[0], valve_keep=valve_winner[1],
           valve_cap=valve_winner[2])
    final_runs = [do_run23(
        "FINAL R%s+%s+%s r%.2f lag%.1f %s"
        % ('+'.join(final_modes), final_ratio_mode,
           valve_winner_spec, ratio, lag0 / 1000.0,
           'dense' if film == 'B' else 'filmA'),
        NewTracker23, w, ratio, lag0, film=film, **final_kw)
        for (w, ratio, lag0, film) in worlds4]
    strict, excold = variant_ok(final_runs)
    print("FINAL: strict=%s excold=%s" % (strict, excold))
    reg6, reg18, drop18, drop18_old = reg23(
        final_kw, "FINAL R%s+%s+%s"
        % ('+'.join(final_modes), final_ratio_mode,
           valve_winner_spec))
    print("FINAL VERDICT: R=%s ratio=%s valve=%s; gate23"
          " strict=%s excold=%s; regressions v6=%s sim1018=%s"
          " (dropped %.1f%% vs OLD %.1f%%, cap 8.3%%)"
          % ('+'.join(final_modes), final_ratio_mode,
             valve_winner_spec, strict, excold,
             "PASS" if reg6 else "FAIL",
             "PASS" if reg18 else "FAIL", drop18 * 100,
             drop18_old * 100))
    print()
    summary23()
    print("WINNER23 spec: RATCHET_MODES=%s RATIO_MODE=%s"
          " VALVE thresh/keep/cap=%s/%s/%s; run kwargs=%s"
          % (final_modes, final_ratio_mode, valve_winner[0],
             valve_winner[1], valve_winner[2], final_kw))
    print("REGRESSIONS: v6 %s, sim1018 %s"
          % ("PASS" if reg6 else "FAIL",
             "PASS" if reg18 else "FAIL"))
    print("TOTAL runtime %.1f s" % (time.time() - t_start))


if __name__ == '__main__':
    main()
