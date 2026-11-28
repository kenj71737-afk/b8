#!/usr/bin/env python3
"""sim1024.py — DubLive v1.0.22 ROUND 3: fix the BOOTSTRAP.
sim1023.py untouched; this file imports it as s23 and subclasses
NewTracker23 -> NewTracker24. No Kotlin touched, no APK built.

ROUND-2 OUTCOME (sim1023_final_run.txt, phase 2d BEST):
  BEST = WINNER + Rb + Rc2 + Rf1 + V2000cap500.
  A r1.42: end-p90 481 / |D-lag| +833; A r1.51: 2428 / +3315;
  dense r1.42: 2704 / +834; lag7.8: 297 / +293.
  Wins 2/4 worlds on the main bar; FAILs dense + r1.51 +
  sim1018 (9.5% vs OLD 8.3%). Rf1 HIT (floor 1.32-1.45,
  speech/dub 0.704 on target). R-a proven no-op (99-100% of
  groups hold 1 fragment under T-a). R-b loses (only stops drift
  FROM a correct reference). R-c pure median WRECKS film A
  (2450/3357) — never use. The valve is the main sim1018
  regression cause (valve-OFF: 8.6% but fails line-start 2083 +
  pairs 16); even ORACLE floor only reaches 2554 on dense
  (1.5x stretch cap + burst-edge valve cannot drain a 96%
  speech film).

ROUND-2 ROOT FINDING (trace of the first 22 tx-anchor
decisions): the ratchet lives in BOOTSTRAP, not steady state.
The seed is wrong by +2480 ms from the start in EVERY world
(seed_lag 7080 vs true lag 4600; 10280 vs 7800) because line 0
is transcribed but NOT translated (skip 5%), the first
translated line is line 1 (starts 2480 ms after the turn
start), and the seed is computed from the turn start. Kotlin
already notes this trap in DubDelayTracker.kt:1403-1420
(comment 21g). Cluster race: each burst contributes ~8 seed
candidates (every group in the 10 s window, +-2 lines);
clusterCentre (densest 1200 ms window) picks the wrong mode,
then the 1200 ms residual gate PROTECTS the wrong mode — the
right candidate has residual 2500-3000 ms so it is no-mk
forever. Trace r1.51: first ok err +2178 ms (t=11.7 s);
cluster jumps 7991->6742->7744->4415->6742 then locks;
lag7.8: cluster collapses to 4697 at candidate 22, the right
samples are no-mk with res 2859-3030.

BASELINE CORRECTION (found while re-running BEST, before
any round-3 variant): the round-2 BEST numbers quoted in the
handoff ([481, 2428, 2704, 297] / [833, 3315, 834, 293])
were measured on DIFFERENT worlds than the round-2 REPRO.
Phase 2d's worlds have 29525 vad events / 6291 dub chunks
(dense: 31 turns) vs phase 0's 29508 / 6113 (dense: 32 turns)
— the 2d driver rebuilt the worlds with the DEFAULT
GATE_SPEECH_P=0.95 instead of the canonical 0.70 set by
s23.main/sim1022.main (verified: GSP 0.95 reproduces the 2d
world counts exactly; 0.70 reproduces phase 0). Re-running
BEST with s23's own code on the canonical GSP=0.70 worlds
gives [478, 2501, 1625, 287] / [999, 3202, 767, 293] — the
subclass is faithful (identical numbers), the worlds differ.
ALL round-3 comparisons therefore use ONE world set
(GSP=0.70, the s23.main canonical set), REPRO re-run in the
same process; the 2d numbers are quoted only as history.
Phase 6 cross-checks the final winner on the GSP=0.95
worlds as well.

ROUND-3 VARIANTS (on BEST base; Rb+Rc2+Rf1+V2000cap500):
  B-a (Ba): seed lag = firstDubArrival - txSentenceStart of
      the most recently CLOSED group before the first burst
      (tracker's own groups, T-a 300 ms gap) instead of the
      turn start. NOTE from the probe (probe24.py): under T-a
      every fragment is its own group, so the nearest closed
      group is a fragment of line 2, NOT line 1 — Ba is
      expected to give seed err -2336 ms (lag4.6) / -5349 ms
      (lag7.8). Measured, not assumed.
  B-a-sent (BaSent): same rule, but on SENTENCE groups
      regrouped with the Kotlin gap (800 ms, TX_GAP_MS in
      DubDelayTracker.kt:392 — the sim's T-a 300 ms subsplit
      is NOT in Kotlin). Probe (probe24b.py): seed err -436 /
      -438 / -436 ms on the three lag4.6 worlds (PASS), but
      -4487 ms on lag7.8 (two more lines' groups close before
      the first dub arrives at lag 7.8 s — "nearest closed"
      is lag-dependent by construction).
  B-b (Bb): during bootstrap each burst contributes ONE seed
      candidate — the group whose startEst is nearest the
      CURRENT reference (_ref_lag(): seed, then cluster of
      the filtered pool). Reduces +-2-line noise in the pool.
  B-b2 (Bb2): same, but the reference is the cluster centre
      of the RAW full pool (every in-window candidate, kept
      separately) — self-correcting once >=8 raw candidates
      exist, instead of trusting the (possibly wrong) seed.
  B-c (Bc): re-bootstrap second-opinion. When N consecutive
      no-mk bursts have best_res in [2000, 3500] ms (signature
      of a mode one line off), open bootstrap again: take the
      second cluster mode (densest 1200 ms window whose centre
      is >1200 ms from the current mode, max count, tie-break
      farthest) as a trial reference for M bursts; if the trial
      mode's accepted samples have median |residual| <= 600 ms
      AND >=500 ms better than the pre-trial baseline median,
      with >=3 accepts, adopt it (log the switch); else revert.
      N in {3, 5}, M = 5, cooldown 10 bursts, max 3 trials/run.

GATE24 = GATE23 + bootstrap bars:
  |seed_final - true lag| <= 600 ms at the FIRST seed, on
  EVERY world; unintentional cluster mode switches after
  establishment = 0 (B-c intentional adoptions logged
  separately and must land within 600 ms of the true lag).
  Everything else as GATE23 (end-p90 TRUE steady < 1000 on
  film A r1.42 AND dense r1.42; sync max/p90 < 1000; D pinned
  0% steady; pairs > 0 every 60 s window; samples/60 s >= 40;
  dropped audio steady < 5%; line-start steady median < 1200;
  v6 + sim1018 PASS, sim1018 dropped <= 8.3%; |D end - true
  lag| <= 1000 on EVERY world; not worse than REPRO shipped
  on any world, end-p90 AND |D-lag|). The round-1 first-burst
  cold-start sync-max outlier (2571 ms) is still reported
  separately as ex-cold-start.
  KNOWN STRUCTURAL CAVEAT (checked, not assumed): lag7.8 +
  ~1000 ms dynamic margin wants D ~8700 > 8000 cap, so D
  pinned 0% steady may be unreachable on lag7.8 even with a
  perfect bootstrap — judged as a cap artifact when sync
  passes, with the evidence printed.
"""
import math
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1023 as s23
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
BEST_KW = dict(s23.WINNER_KW, ratio_tx=False)
RESULTS24 = []
REPRO24 = {}   # (film, ratio, lag0) -> res dict (SHIPPED21)


def _med(vals):
    return s22._med(vals)


def _p90(vals):
    return s22._p90(vals)


def _mx(vals):
    return s22._mx(vals)


def _smed(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


# ----------------------------------------------------------------
# Tracker v1.0.22 round 3 (subclass of NewTracker23; sim1023.py
# untouched). Variant switches are CLASS attrs, set per run by
# set24() — the event loop only sets the round-1/2 flags.
# ----------------------------------------------------------------
class NewTracker24(s23.NewTracker23):
    BOOT_MODES = ()    # subset of {'Ba','BaSent','Bb','Bb2','Bc'}
    BC_N = 3           # B-c: consecutive qualifying no-mk to trial
    BC_M = 5           # B-c: trial length in bursts
    BC_RES_LO = 2000   # B-c: qualifying best_res band, low end
    BC_RES_HI = 3500   # B-c: qualifying best_res band, high end
    BC_COOLDOWN = 10   # B-c: bursts to wait after a trial ends
    BC_MAX_TRIALS = 3  # B-c: max trials per run
    BA_SENT_GAP_MS = 800  # BaSent: Kotlin TX_GAP_MS grouping

    def __init__(self):
        super().__init__()
        # --- seed diagnostics (never feed the tracker except
        # through the documented B-a correction) ---
        self.seed_loop = None      # loop-assigned seed_lag
        self.seed_loop_err = None  # vs first chunk's true lag
        self.seed_corr = None      # B-a corrected seed
        self.seed_corr_err = None
        self.seed_group = None     # (first, last, closed, startEst)
        self.seed_mode = None      # 'Ba' | 'BaSent' | 'Ba-none'
        self._ba_done = False
        # --- BaSent: parallel sentence grouping (800 ms gap) ---
        self._sent_groups = []
        self._sent_last_frag = None
        self._sent_cur = None
        # --- B-b: raw candidate pool (all in-window lats) ---
        self._bb_raw_pool = []
        # --- B-c: re-bootstrap trial state ---
        self._bc_streak = 0
        self._bc_cooldown = 0
        self._bc_trials = 0
        self._bc_trial_active = False
        self._bc_trial_ref = None
        self._bc_trial_left = 0
        self._bc_trial_ok = []
        self._bc_base_ref = None
        self._bc_base_res_med = None
        self._bc_adopted_ref = None
        self._bc_switches = 0
        self._bc_log = []          # (now, event, ref, detail)
        self._bc_seen_seq = None
        # --- mode-switch counting (unintentional only) ---
        self.mode_switches = 0
        self.mode_switch_log = []
        self._last_ref_seen = None
        # --- first-decision trace ---
        self._tx_trace24 = []

    # -- B-a / BaSent: correct the loop seed at the first dub
    # chunk, BEFORE the parent's on_dub uses delay/hold. The loop
    # (s22._run22_inner, mirroring Kotlin onDubAudioArrival at
    # DubDelayTracker.kt:1403-1420) has already set seed_lag =
    # firstDubArrival - firstMarkerWall, ema.seed(el),
    # delay = el + dynamicMargin (capped 8000) and hold. We
    # recompute all four from the corrected seed with the exact
    # same formulas, so the first chunk already plays with the
    # corrected D/hold. --
    def on_dub(self, now, dub_ms, true_lag, true_burst_sample=None):
        if not self._ba_done:
            self._ba_done = True
            self.seed_loop = getattr(self, 'seed_lag', None)
            if self.seed_loop is not None:
                self.seed_loop_err = self.seed_loop - true_lag
            if ('Ba' in self.BOOT_MODES
                    or 'BaSent' in self.BOOT_MODES):
                self._ba_correct_seed(now, true_lag)
        return super().on_dub(now, dub_ms, true_lag,
                              true_burst_sample)

    def _ba_correct_seed(self, now, true_lag):
        use_sent = 'BaSent' in self.BOOT_MODES
        groups = (self._sent_groups if use_sent
                  else self.tx_groups)
        best, best_se = None, None
        for g in groups:
            if not g.get('closed'):
                continue
            se = self._tx_sentence_start(g)
            if not (now - s21.TX_WIN_MS <= se <= now - 300):
                continue
            if best_se is None or se > best_se:
                best, best_se = g, se
        if best is None:
            self.seed_mode = 'Ba-none'
            return
        el = now - best_se
        self.seed_corr = el
        self.seed_corr_err = el - true_lag
        self.seed_group = (best['first'], best['last'], True,
                           best_se)
        self.seed_mode = 'BaSent' if use_sent else 'Ba'
        self.seed_lag = el
        self.ema.seed(el)
        nd = max(3000, min(el + self.dynamic_margin(), 8000))
        self._set_delay(nd)
        self.hold = max(0, nd - el - self.pipeline_ms())

    # -- BaSent: maintain the Kotlin-gap (800 ms) sentence
    # grouping alongside the tracker's own (T-a 300 ms) groups.
    # Pure bookkeeping; the tracker's grouping is untouched. --
    def note_tx_in_fragment(self, turn_idx, arrival):
        super().note_tx_in_fragment(turn_idx, arrival)
        if (self._sent_last_frag is not None
                and arrival - self._sent_last_frag
                >= self.BA_SENT_GAP_MS):
            if self._sent_cur is not None:
                self._sent_cur['closed'] = True
                self._sent_cur['closed_at'] = arrival
            self._sent_cur = None
        self._sent_last_frag = arrival
        if self._sent_cur is None:
            self._sent_cur = {'first': arrival, 'last': arrival,
                              'closed': False, 'closed_at': None}
            self._sent_groups.append(self._sent_cur)
        else:
            self._sent_cur['last'] = arrival

    # -- B-c: trial / adopted reference overrides _ref_lag.
    # During a trial every consumer (selection, residual gate,
    # R-b, D-based path) reads the trial reference — the trial
    # is end-to-end. After adoption the reference is the median
    # of accepted boundary samples within 1200 ms of the adopted
    # mode (they accumulate there once the mode is right),
    # falling back to the adopted value itself. --
    def _ref_lag(self):
        if self._bc_trial_active and self._bc_trial_ref is not None:
            return self._bc_trial_ref
        if self._bc_adopted_ref is not None:
            near = sorted(s for s in self._bnd_samples
                          if abs(s - self._bc_adopted_ref) <= 1200)
            if len(near) >= 3:
                return near[len(near) // 2]
            return self._bc_adopted_ref
        return super()._ref_lag()

    # -- unintentional mode-switch counting: a >1500 ms jump of
    # the established reference between consecutive accepted
    # boundary samples, outside trials and outside an adoption.
    # (The adoption itself sets _last_ref_seen, so the post-trial
    # feed never counts as a switch.) --
    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        r = super().feed_sample(sample, now, true_lag,
                                boundary=boundary, **kw)
        if boundary and self._ref_established():
            cur = self._ref_lag()
            if (self._last_ref_seen is not None
                    and not self._bc_trial_active
                    and self._bc_adopted_ref is None
                    and abs(cur - self._last_ref_seen) > 1500):
                self.mode_switches += 1
                self.mode_switch_log.append(
                    (now, self._last_ref_seen, cur))
            self._last_ref_seen = cur
        return r

    # -- B-b pool filter + B-c streak/trial accounting, wrapped
    # around s23.apply_tx_anchor (which does the R-b pre-check,
    # the s21 anchor, the diagnostics and the Rf ratio
    # measurement). The wrap is side-effect free for the anchor
    # itself except for the _seed_cand trim (B-b) and the
    # _ref_lag override (B-c trial/adoption). --
    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        first_call = (burst_seq != self._bc_seen_seq)
        if first_call:
            self._bc_seen_seq = burst_seq
        burst_arrival = self._last_burst_arrival
        dlag = self._sel_lag()
        best_pre, res_pre = (None, None)
        if burst_arrival is not None and first_call:
            best_pre, res_pre = self._best_tx_group(burst_arrival,
                                                     dlag)
        # --- B-b: which single candidate this burst contributes.
        bb_on = (('Bb' in self.BOOT_MODES
                  or 'Bb2' in self.BOOT_MODES)
                 and not self._ref_established())
        bb_before_len = len(self._seed_cand)
        bb_expected = []
        bb_chosen_lat = None
        if bb_on and burst_arrival is not None:
            for g in self.tx_groups:
                if not g.get('closed'):
                    continue
                se = self._tx_sentence_start(g)
                if not (burst_arrival - s21.TX_WIN_MS <= se
                        <= burst_arrival - 300):
                    continue
                lat = burst_arrival - se
                bb_expected.append(lat)
                self._bb_raw_pool.append(lat)
            if len(self._bb_raw_pool) > 300:
                del self._bb_raw_pool[
                    :len(self._bb_raw_pool) - 300]
            if bb_expected:
                if ('Bb2' in self.BOOT_MODES
                        and len(self._bb_raw_pool) >= 8):
                    ref = self._cluster_centre(
                        list(self._bb_raw_pool))
                else:
                    ref = self._ref_lag()
                bb_chosen_lat = min(
                    bb_expected, key=lambda v: abs(v - ref))
        ok0, nm0 = self.tx_ok, self.tx_no_mk
        super().apply_tx_anchor(now, burst_seq, true_lag,
                                true_sample)
        # --- B-b trim: keep only the chosen candidate of the
        # candidates s21 just appended (none appended when the
        # R-b pre-check returned early — then there is nothing
        # to trim and the pool is left untouched). --
        if (bb_on and bb_chosen_lat is not None
                and len(self._seed_cand) > bb_before_len):
            tail = self._seed_cand[bb_before_len:]
            kept, kept_chosen = [], False
            for v in tail:
                if v == bb_chosen_lat and not kept_chosen:
                    kept.append(v)
                    kept_chosen = True
            if not kept_chosen and tail:
                kept = [min(tail,
                            key=lambda v: abs(v - bb_chosen_lat))]
            self._seed_cand = self._seed_cand[:bb_before_len] + kept
        # --- outcome + first-decision trace ---
        ok = self.tx_ok > ok0
        nomk = self.tx_no_mk > nm0
        outcome = 'ok' if ok else ('no-mk' if nomk else 'cons/agree')
        lat_tx = None
        if best_pre is not None and burst_arrival is not None:
            lat_tx = (burst_arrival
                      - self._tx_sentence_start(best_pre))
        if first_call and len(self._tx_trace24) < 40:
            self._tx_trace24.append(
                (now, burst_seq, round(dlag),
                 res_pre, lat_tx, outcome))
        if not first_call or 'Bc' not in self.BOOT_MODES:
            return
        # --- B-c accounting (one step per burst) ---
        if self._bc_trial_active:
            if ok and lat_tx is not None:
                self._bc_trial_ok.append(lat_tx)
            self._bc_trial_left -= 1
            if self._bc_trial_left <= 0:
                self._bc_end_trial(now)
            return
        if self._bc_cooldown > 0:
            self._bc_cooldown -= 1
            self._bc_streak = 0
            return
        if (nomk and res_pre is not None
                and self.BC_RES_LO <= res_pre <= self.BC_RES_HI
                and self._ref_established()):
            self._bc_streak += 1
            if self._bc_streak >= self.BC_N:
                self._bc_start_trial(now)
        else:
            self._bc_streak = 0

    def _bc_second_mode(self):
        """Densest 1200 ms window whose centre is >1200 ms from
        the current mode (max count, tie-break farthest). None
        when the pool holds a single mode."""
        pool = sorted(self._bnd_samples + self._seed_cand)
        if len(pool) < 3:
            return None
        c0 = self._ref_lag()
        best_key, best_centre = None, None
        for i, v in enumerate(pool):
            window = [u for u in pool if v <= u <= v + 1200]
            if not window:
                continue
            centre = window[len(window) // 2]
            if abs(centre - c0) <= 1200:
                continue
            key = (len(window), abs(centre - c0))
            if best_key is None or key > best_key:
                best_key, best_centre = key, centre
        return best_centre

    def _bc_start_trial(self, now):
        if self._bc_trials >= self.BC_MAX_TRIALS:
            self._bc_log.append((now, 'trial-cap-hit', None, None))
            self._bc_streak = 0
            return
        centre2 = self._bc_second_mode()
        if centre2 is None:
            self._bc_log.append(
                (now, 'trial-no-second-mode', self._ref_lag(), None))
            self._bc_streak = 0
            self._bc_cooldown = self.BC_COOLDOWN
            return
        base = sorted(self._bnd_samples)
        self._bc_base_ref = self._ref_lag()
        self._bc_base_res_med = (
            _med([abs(s - self._bc_base_ref) for s in base])
            if base else None)
        self._bc_trial_ref = centre2
        self._bc_trial_active = True
        self._bc_trial_left = self.BC_M
        self._bc_trial_ok = []
        self._bc_trials += 1
        self._bc_streak = 0
        self._bc_log.append((now, 'trial-start', self._bc_base_ref,
                             centre2))

    def _bc_end_trial(self, now):
        trial_ref = self._bc_trial_ref
        oks = list(self._bc_trial_ok)
        med_res = (_med([abs(v - trial_ref) for v in oks])
                   if oks else None)
        base_med = self._bc_base_res_med
        adopt = (len(oks) >= 3 and med_res is not None
                 and med_res <= 600
                 and (base_med is None
                      or med_res <= base_med - 500))
        if adopt:
            self._bc_adopted_ref = trial_ref
            self._bc_switches += 1
            self._last_ref_seen = trial_ref
            self._bc_log.append(
                (now, 'trial-ADOPT', trial_ref,
                 (len(oks), med_res, base_med)))
        else:
            self._last_ref_seen = None
            self._bc_log.append(
                (now, 'trial-reject', trial_ref,
                 (len(oks), med_res, base_med)))
        self._bc_trial_active = False
        self._bc_trial_ref = None
        self._bc_trial_left = 0
        self._bc_cooldown = self.BC_COOLDOWN


def set24(boot=(), ratchet=('Rb', 'Rc2'), ratio_mode='rf1',
          valve_thresh=2000, valve_keep=1500, valve_cap=500,
          bc_n=3, bc_m=5, pause_k=0.40):
    """Set the complete round-3 variant spec on NewTracker24."""
    for k, v in dict(
            BOOT_MODES=tuple(boot),
            RATCHET_MODES=tuple(ratchet),
            RATIO_MODE=ratio_mode,
            VALVE_THRESH_MS=valve_thresh,
            VALVE_KEEP_MS=valve_keep,
            VALVE_MAX_DROP_MS=valve_cap,
            RATIO_PAUSE_K=pause_k,
            BC_N=bc_n, BC_M=bc_m).items():
        setattr(NewTracker24, k, v)


def best24_kw(tail_valve=True):
    """BEST base kwargs for run22 (Rb+Rc2+Rf1+V2000cap500 via
    set24); tail_valve=False for the valve-OFF check."""
    kw = dict(BEST_KW)
    kw['tail_valve'] = tail_valve
    return kw


# ----------------------------------------------------------------
# Harness (mirrors s23.do_run23, extended with round-3
# bootstrap metrics; s23.diag23/gate23 reused verbatim).
# ----------------------------------------------------------------
def do_run24(label, tracker_cls, world, ratio, lag0, film='A',
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
    # --- round-3 bootstrap metrics (from the tracker) ---
    res['seed_loop'] = getattr(tr, 'seed_loop', None)
    if res['seed_loop'] is None:
        res['seed_loop'] = getattr(tr, 'seed_lag', None)
    res['seed_loop_err'] = getattr(tr, 'seed_loop_err', None)
    res['seed_corr'] = getattr(tr, 'seed_corr', None)
    res['seed_corr_err'] = getattr(tr, 'seed_corr_err', None)
    res['seed_mode'] = getattr(tr, 'seed_mode', None)
    res['seed_group'] = getattr(tr, 'seed_group', None)
    res['seed_final_err'] = (
        res['seed_corr_err'] if res['seed_corr_err'] is not None
        else res['seed_loop_err'])
    # REPRO / non-24 trackers record no per-chunk seed diag:
    # derive the loop-seed error from the first dub chunk's
    # true lag (seed_lag is set once by the loop, never moved).
    if res['seed_loop_err'] is None and res['seed_loop'] is not None \
            and dub:
        res['seed_loop_err'] = res['seed_loop'] - dub[0][2]
        if res['seed_final_err'] is None:
            res['seed_final_err'] = res['seed_loop_err']
    res['mode_switches'] = getattr(tr, 'mode_switches', 0)
    res['mode_switch_log'] = list(getattr(tr, 'mode_switch_log', []))
    res['bc_trials'] = getattr(tr, '_bc_trials', 0)
    res['bc_switches'] = getattr(tr, '_bc_switches', 0)
    res['bc_log'] = list(getattr(tr, '_bc_log', []))
    res['tx_trace24'] = list(getattr(tr, '_tx_trace24', []))
    s23.diag23(label, tr, frames, sent, ls, lu, dub, res)
    diag24(label, tr, res)
    res['gate23'] = s23.gate23(label, res)
    res['gate24'] = gate24(label, res)
    RESULTS24.append(res)
    return res


def diag24(label, tr, res):
    """Round-3 bootstrap diagnostics: first seed, cluster mode
    switches, B-c trial log, first tx-anchor decisions."""
    print("DIAG24-bootstrap %s:" % label)
    print("  seed: loop=%s err=%s ms; corrected=%s err=%s ms"
          " (mode %s, group first/last/closed/startEst=%s);"
          " true lag med %.0f ms"
          % (res['seed_loop'], res['seed_loop_err'],
             res['seed_corr'], res['seed_corr_err'],
             res['seed_mode'], res['seed_group'],
             res['true_med'] if res['true_med'] is not None
             else -1))
    print("  cluster mode switches (unintentional, >1500 ms"
          " jumps of the established ref): %d %s"
          % (res['mode_switches'],
             res['mode_switch_log'][:5]
             if res['mode_switch_log'] else ""))
    print("  B-c: trials %d, adopted switches %d; log %s"
          % (res['bc_trials'], res['bc_switches'], res['bc_log']))
    print("  first tx-anchor decisions (now, seq, dlag,"
          " best_res, lat_tx, outcome):")
    for row in res['tx_trace24'][:12]:
        print("    t=%.0f seq=%s dlag=%s best_res=%s lat_tx=%s %s"
              % (row[0], row[1], row[2], row[3], row[4], row[5]))
    print()


def gate24(label, res):
    """GATE24 = s23.gate23 (printed by the caller via gate23)
    PLUS the round-3 bootstrap bars. Returns the combined
    strict verdict (bootstrap bars AND gate23 strict)."""
    boot_seed = (res['seed_final_err'] is not None
                 and abs(res['seed_final_err']) <= 600)
    boot_switch = res['mode_switches'] == 0
    print("GATE24-bootstrap %s:" % label)
    print("  %-46s %s (|seed err| %s ms)"
          % ("|seed - true lag| <= 600 ms (first seed)",
             "PASS" if boot_seed else "FAIL",
             res['seed_final_err']))
    print("  %-46s %s (unintentional %d, B-c adopted %d)"
          % ("cluster mode switches after established = 0",
             "PASS" if boot_switch else "FAIL",
             res['mode_switches'], res['bc_switches']))
    strict24 = bool(res['gate23']) and boot_seed and boot_switch
    print("  GATE24: %s (gate23 strict=%s)"
          % ("PASS" if strict24 else "FAIL", res['gate23']))
    print()
    return strict24


def variant_ok24(results):
    """(strict_all, excold_all) over a list of run res dicts.
    excold = GATE23 ex-cold-start + bootstrap bars + the
    not-worse-than-REPRO checks. strict adds the full-session
    sync-max bar."""
    strict = all(r['gate24'] for r in results)
    excold = True
    for r in results:
        if not (r['end_p90_true'] is not None
                and r['end_p90_true'] < 1000
                and r['sync_p90'] is not None
                and r['sync_p90'] < 1000
                and r['sync_max_steady'] is not None
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
                and r['true_max'] < 20000
                and r['seed_final_err'] is not None
                and abs(r['seed_final_err']) <= 600
                and r['mode_switches'] == 0):
            excold = False
        rep = REPRO24.get((r['film'], r['ratio'], r['lag0']))
        if rep is not None and not (
                r['end_p90_true'] <= rep['end_p90_true']
                and r['d_end_err'] <= rep['d_end_err']):
            excold = False
    return strict, excold


# ----------------------------------------------------------------
# Regressions (mirror s23.reg23, with NewTracker24).
# ----------------------------------------------------------------
_REG24_OLD = {}


def reg24(kw24, tag):
    """v6 + sim1018 regressions for the given round-3 winner
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
        NewTracker24, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384, **kw24)
    steady_new6 = s21.report21("NEW (v1.0.22r3 %s), v6 world" % tag,
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
    if 'old' not in _REG24_OLD:
        old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = \
            s22.run22(s19.NewTracker19, vad18, dub18, completes18,
                      line_start18, txin6, txsrc6, min_buf=4096,
                      line_counted=line_counted18)
        steady_old18 = s21.report21(
            "OLD (v1.0.19), sim1018 world, tc ON", old18, dh18,
            s6018, tp6018, holdsat18, bursts18, ex18,
            line_start18, {})
        _REG24_OLD.update(old=old18, steady=steady_old18,
                          tp60=tp6018)
    old18 = _REG24_OLD['old']
    steady_old18 = _REG24_OLD['steady']
    tp6018 = _REG24_OLD['tp60']
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        s22.run22(NewTracker24, vad18, dub18, completes18,
                  line_start18, txin6, txsrc6, min_buf=4096,
                  line_counted=line_counted18, **kw24)
    steady_new18 = s21.report21(
        "NEW (v1.0.22r3 %s), sim1018 world, tc ON" % tag, new18,
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


def summary24():
    print("=" * 110)
    print("SUMMARY24 (10-min sessions; end-p90 = TRUE render,"
          " steady window; p90 = p90 of |value|):")
    print("%-38s %7s %7s %7s %7s %6s %6s %5s %5s %6s %6s %6s %7s"
          % ("run", "endp90", "kotPl", "syncMx", "syncP90",
             "DpinS%", "prMin", "smMin", "drop%", "Dend",
             "Derr", "floor", "seedErr"))
    for r in RESULTS24:
        print("%-38s %7.0f %7.0f %7.0f %7.0f %6.0f %6d %5d %5.1f"
              " %6.0f %6.0f %6.2f %7.0f"
              % (r['label'][:38], r['end_p90_true'] or -1,
                 r['end_p90_kot_play'] or -1, r['sync_max'] or -1,
                 r['sync_p90'] or -1, 100.0 * r['d_pinned_steady'],
                 r['pairs_min_win'], r['samples_min_win'],
                 r['drop_steady'], r['d_end'],
                 r['d_end_err'] if r['d_end_err'] is not None
                 else -1, r['floor_steady'] or -1,
                 r['seed_final_err']
                 if r['seed_final_err'] is not None else -9999))
    print()
    print("GATE24 per run (strict incl. bootstrap bars):")
    for r in RESULTS24:
        print("  %-38s gate24=%s (gate23=%s, seedErr=%s,"
              " switches=%d, bcAdopt=%d)"
              % (r['label'][:38], r['gate24'], r['gate23'],
                 r['seed_final_err'], r['mode_switches'],
                 r['bc_switches']))


# ----------------------------------------------------------------
# Main.
# ----------------------------------------------------------------
def run4(label_fmt, tracker_cls, worlds4, **kw):
    """4-world set; returns the list of res dicts."""
    runs = []
    for (w, ratio, lag0, film) in worlds4:
        runs.append(do_run24(
            label_fmt % (ratio, lag0 / 1000.0,
                         'dense' if film == 'B' else 'filmA'),
            tracker_cls, w, ratio, lag0, film=film, **kw))
    return runs


def run_excold(r):
    """Per-run ex-cold-start bars (GATE23 ex-cold + bootstrap
    bars + not-worse-than-REPRO), for ranking variants."""
    ok = (r['end_p90_true'] is not None and r['end_p90_true'] < 1000
          and r['sync_p90'] is not None and r['sync_p90'] < 1000
          and r['sync_max_steady'] is not None
          and r['sync_max_steady'] < 1000
          and r['d_pinned_steady'] == 0
          and r['pairs_min_win'] > 0
          and r['samples_min_win'] >= 40
          and r['drop_steady'] < 5.0
          and r['ls_med'] is not None and r['ls_med'] < 1200
          and r['d_end_err'] is not None and r['d_end_err'] <= 1000
          and r['d_gap'] is not None and r['d_gap'] < 3000
          and r['true_max'] is not None and r['true_max'] < 20000
          and r['seed_final_err'] is not None
          and abs(r['seed_final_err']) <= 600
          and r['mode_switches'] == 0)
    rep = REPRO24.get((r['film'], r['ratio'], r['lag0']))
    if rep is not None and not (
            r['end_p90_true'] <= rep['end_p90_true']
            and r['d_end_err'] <= rep['d_end_err']):
        ok = False
    return ok


def rank_key(runs):
    """(worlds passing excold bars, -sum end-p90, -sum |D-lag|)
    — higher is better."""
    n = sum(1 for r in runs if run_excold(r))
    e = sum(r['end_p90_true'] or 99999 for r in runs)
    d = sum(r['d_end_err'] or 99999 for r in runs)
    return (n, -e, -d)


def main():
    t_start = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'full'
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("sim1024 ROUND 3: fix the BOOTSTRAP (B-a/B-b/B-c) on"
          " BEST (Rb+Rc2+Rf1+V2000cap500). Worlds: film A r1.42/"
          "r1.51 lag4.6, dense r1.42, film A r1.42 lag7.8;"
          " 10-min sessions, tc OFF.")
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
    repro_runs = run4("REPRO24 shipped r%.2f lag%.1f %s",
                      s21.NewTracker21, worlds4, **s22.SHIPPED21)
    for r in repro_runs:
        REPRO24[(r['film'], r['ratio'], r['lag0'])] = r
        s23.REPRO23[(r['film'], r['ratio'], r['lag0'])] = r
    print("REPRO24 baselines (end-p90, |D-lag|, seedErr): %s"
          % ({k: (round(v['end_p90_true']),
                 round(v['d_end_err']),
                 None if v['seed_final_err'] is None
                 else round(v['seed_final_err']))
             for k, v in REPRO24.items()},))
    print()

    # ---- Phase 1: BEST baseline re-run (must reproduce s23). --
    print("#### PHASE 1: BEST baseline re-run (4 worlds) ####")
    set24(boot=())
    best_runs = run4("BEST24 r%.2f lag%.1f %s",
                     NewTracker24, worlds4, **best24_kw())
    strict, excold = variant_ok24(best_runs)
    print("BEST24 re-run: strict=%s excold=%s (end-p90 %s;"
          " |D-lag| %s; seedErr %s; switches %s)"
          % (strict, excold,
             [round(r['end_p90_true']) for r in best_runs],
             [round(r['d_end_err']) for r in best_runs],
             [None if r['seed_final_err'] is None
              else round(r['seed_final_err']) for r in best_runs],
             [r['mode_switches'] for r in best_runs]))
    print("BEST24 expected (s23 phase 2d): end-p90"
          " [481, 2428, 2704, 297]; |D-lag| [833, 3315, 834, 293]")
    print()
    if mode == 'quick':
        summary24()
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return
    if mode == 'repro':
        summary24()
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return

    # ---- Phase 2: singles, one bootstrap fix at a time. ----
    print("#### PHASE 2: singles on BEST base ####")
    singles = {}
    for tag, boot, bc_n in (('Ba', ('Ba',), 3),
                            ('BaSent', ('BaSent',), 3),
                            ('Bb', ('Bb',), 3),
                            ('Bb2', ('Bb2',), 3),
                            ('Bc3', ('Bc',), 3),
                            ('Bc5', ('Bc',), 5)):
        set24(boot=boot, bc_n=bc_n)
        runs = run4("%s r%%.2f lag%%.1f %%s" % tag,
                    NewTracker24, worlds4, **best24_kw())
        strict, excold = variant_ok24(runs)
        singles[tag] = runs
        print("%s: strict=%s excold=%s; worlds passing excold"
              " bars %d/4; end-p90 %s; |D-lag| %s; seedErr %s;"
              " switches %s; bcTrials %s bcAdopt %s"
              % (tag, strict, excold,
                 sum(1 for r in runs if run_excold(r)),
                 [round(r['end_p90_true']) for r in runs],
                 [round(r['d_end_err']) for r in runs],
                 [None if r['seed_final_err'] is None
                  else round(r['seed_final_err']) for r in runs],
                 [r['mode_switches'] for r in runs],
                 [r['bc_trials'] for r in runs],
                 [r['bc_switches'] for r in runs]))
        print()
    order = sorted(singles, key=lambda t: rank_key(singles[t]),
                   reverse=True)
    print("SINGLES RANKED (best first): %s"
          % ([(t, sum(1 for r in singles[t] if run_excold(r)))
              for t in order],))
    print()

    # ---- Phase 3: accumulate in order of effectiveness. ----
    print("#### PHASE 3: combos, best-first, stop at first"
          " excold pass ####")
    combos = [(('BaSent', 'Bb'), 3),
              (('BaSent', 'Bb', 'Bc'), 3),
              (('BaSent', 'Bc'), 3),
              (('BaSent', 'Bb2'), 3),
              (('BaSent', 'Bb2', 'Bc'), 3),
              (('Bb', 'Bc'), 3),
              (('Ba', 'Bb'), 3)]
    combo_runs = None
    combo_spec = None
    for boot, bc_n in combos:
        set24(boot=boot, bc_n=bc_n)
        runs = run4("%s r%%.2f lag%%.1f %%s" % '+'.join(boot),
                    NewTracker24, worlds4, **best24_kw())
        strict, excold = variant_ok24(runs)
        print("COMBO %s: strict=%s excold=%s; worlds passing"
              " excold bars %d/4; end-p90 %s; |D-lag| %s; seedErr"
              " %s; switches %s; bcTrials %s bcAdopt %s"
              % ('+'.join(boot), strict, excold,
                 sum(1 for r in runs if run_excold(r)),
                 [round(r['end_p90_true']) for r in runs],
                 [round(r['d_end_err']) for r in runs],
                 [None if r['seed_final_err'] is None
                  else round(r['seed_final_err']) for r in runs],
                 [r['mode_switches'] for r in runs],
                 [r['bc_trials'] for r in runs],
                 [r['bc_switches'] for r in runs]))
        print()
        if combo_runs is None or rank_key(runs) > rank_key(combo_runs):
            combo_runs, combo_spec = runs, (boot, bc_n)
        if excold:
            print("COMBO-WINNER: %s (first variant passing"
                  " GATE24 ex-cold-start on all 4 worlds)"
                  % ('+'.join(boot),))
            break
    if combo_runs is None:
        combo_runs, combo_spec = best_runs, ((), 3)
    print("BEST SO FAR: %s rank=%s"
          % (combo_spec, rank_key(combo_runs)))
    print()

    # ---- Phase 4: valve re-check on the best variant. ----
    print("#### PHASE 4: valve re-check on the best variant"
          " (V2000cap500 vs valve-OFF) ####")
    boot, bc_n = combo_spec
    set24(boot=boot, bc_n=bc_n)
    off_prefix = ('+'.join(boot) if boot else 'BEST') + '+valveOFF'
    off_runs = run4(off_prefix + " r%.2f lag%.1f %s",
                    NewTracker24, worlds4, **best24_kw(
                        tail_valve=False))
    print("VALVE-OFF on %s: end-p90 %s; |D-lag| %s; drop%% %s;"
          " line-start med %s; pairsMin %s"
          % ('+'.join(boot) if boot else 'BEST',
             [round(r['end_p90_true']) for r in off_runs],
             [round(r['d_end_err']) for r in off_runs],
             [round(r['drop_steady'], 1) for r in off_runs],
             [None if r['ls_med'] is None else round(r['ls_med'])
              for r in off_runs],
             [r['pairs_min_win'] for r in off_runs]))
    reg6_off, reg18_off, drop18_off, drop18_old = reg24(
        best24_kw(tail_valve=False),
        "%s+valveOFF" % ('+'.join(boot) if boot else 'BEST'))
    print("VALVE-OFF regressions: v6 %s, sim1018 %s (dropped"
          " %.1f%% vs OLD %.1f%%, cap 8.3%%)"
          % ("PASS" if reg6_off else "FAIL",
             "PASS" if reg18_off else "FAIL",
             drop18_off * 100, drop18_old * 100))
    # Valve-ON regressions for the same variant (only if the
    # valve-ON final has not been measured yet this run).
    reg6_on, reg18_on, drop18_on, _ = reg24(
        best24_kw(tail_valve=True),
        "%s+V2000cap500" % ('+'.join(boot) if boot else 'BEST'))
    print("VALVE-ON regressions: v6 %s, sim1018 %s (dropped"
          " %.1f%% vs OLD %.1f%%, cap 8.3%%)"
          % ("PASS" if reg6_on else "FAIL",
             "PASS" if reg18_on else "FAIL",
             drop18_on * 100, drop18_old * 100))
    valve_off_better = (rank_key(off_runs) >= rank_key(combo_runs)
                        and reg6_off and reg18_off)
    use_valve_off = bool(valve_off_better)
    print("VALVE DECISION: %s (valve-OFF rank %s vs valve-ON"
          " rank %s; valve-OFF regs v6=%s sim1018=%s)"
          % ("valve-OFF" if use_valve_off else "V2000cap500",
             rank_key(off_runs), rank_key(combo_runs),
             "PASS" if reg6_off else "FAIL",
             "PASS" if reg18_off else "FAIL"))
    print()

    # ---- Phase 5: final winner (4 worlds + regressions). ----
    print("#### PHASE 5: FINAL WINNER (4 worlds) ####")
    set24(boot=boot, bc_n=bc_n)
    final_kw = best24_kw(tail_valve=not use_valve_off)
    final_tag = "%s+%s" % ('+'.join(boot) if boot else 'BEST',
                           'valveOFF' if use_valve_off
                           else 'V2000cap500')
    final_runs = run4("FINAL %s r%%.2f lag%%.1f %%s" % final_tag,
                      NewTracker24, worlds4, **final_kw)
    strict, excold = variant_ok24(final_runs)
    print("FINAL: strict=%s excold=%s" % (strict, excold))
    reg6, reg18, drop18, drop18_old = reg24(final_kw,
                                             "FINAL " + final_tag)
    print("FINAL VERDICT: boot=%s valve=%s; gate24 strict=%s"
          " excold=%s; regressions v6=%s sim1018=%s (dropped"
          " %.1f%% vs OLD %.1f%%, cap 8.3%%)"
          % ('+'.join(boot) if boot else '(none)',
             'valveOFF' if use_valve_off else 'V2000cap500',
             strict, excold,
             "PASS" if reg6 else "FAIL",
             "PASS" if reg18 else "FAIL", drop18 * 100,
             drop18_old * 100))
    print()
    summary24()
    print("WINNER24 spec: BOOT_MODES=%s RATCHET_MODES=%s"
          " RATIO_MODE=rf1 VALVE thresh/keep/cap=%s/%s/%s"
          " (OFF=%s); run kwargs=%s"
          % (boot, ('Rb', 'Rc2'),
             2000 if not use_valve_off else None,
             1500 if not use_valve_off else None,
             500 if not use_valve_off else None,
             use_valve_off, final_kw))
    print("REGRESSIONS: v6 %s, sim1018 %s"
          % ("PASS" if reg6 else "FAIL",
             "PASS" if reg18 else "FAIL"))
    print()

    # ---- Phase 6: cross-check the final winner on the
    # GSP=0.95 worlds (the world set round-2 phase 2d
    # accidentally used). REPRO is re-run there too; the
    # GATE23 lines printed inside do_run24 compare against
    # the GSP=0.70 REPRO and are NOT applicable here — the
    # manual table below is the correct comparison. ----
    print("#### PHASE 6: cross-check on GSP=0.95 worlds"
          " (round-2 phase-2d world set) ####")
    s20.GATE_SPEECH_P = 0.95
    worldA95 = s22._build_world(**s22.WORLD22)
    worldB95 = s22._build_world(**s22.WORLD22_DENSE)
    worlds95 = [(worldA95, RATIO22_A, LAG22_A, 'A'),
                (worldA95, RATIO22_B, LAG22_A, 'A'),
                (worldB95, RATIO22_A, LAG22_A, 'B'),
                (worldA95, RATIO22_A, LAG22_B, 'A')]
    repro95 = run4("REPRO95 shipped r%.2f lag%.1f %s",
                   s21.NewTracker21, worlds95, **s22.SHIPPED21)
    set24(boot=boot, bc_n=bc_n)
    final95 = run4("FINAL95 %s r%%.2f lag%%.1f %%s" % final_tag,
                   NewTracker24, worlds95, **final_kw)
    print("CROSS-CHECK GSP=0.95 (end-p90 / |D-lag| / seedErr):")
    for a, b in zip(repro95, final95):
        print("  %-28s REPRO (%4.0f, %4.0f, %5.0f)  FINAL (%4.0f,"
              " %4.0f, %5.0f)  end-p90 %s, |D-lag| %s"
              % (a['label'][8:], a['end_p90_true'], a['d_end_err'],
                 a['seed_final_err'] or -1, b['end_p90_true'],
                 b['d_end_err'], b['seed_final_err'] or -1,
                 "BETTER" if b['end_p90_true'] <= a['end_p90_true']
                 else "worse",
                 "BETTER" if b['d_end_err'] <= a['d_end_err']
                 else "worse"))
    s20.GATE_SPEECH_P = 0.70
    print("TOTAL runtime %.1f s" % (time.time() - t_start))


if __name__ == '__main__':
    main()
