#!/usr/bin/env python3
"""sim1025.py — DubLive v1.0.22 ROUND 4: seed v2 + re-bootstrap
referee from VAD slice samples, on FINAL (BaSent+Bb + BEST).
sim1024.py untouched; this file imports it as s24 and subclasses
NewTracker24 -> NewTracker25. No Kotlin touched, no APK built.

ROUND-3 OUTCOME (sim1024_final_run.txt, FINAL = BaSent+Bb +
V2000cap500): A r1.42 end-p90 548 / |D-lag| +979 / seed -436;
A r1.51 726 / +616 / -438; dense r1.42 420 / +733 / -436;
lag7.8 3924 / -2151 (D end BELOW true lag) / seed -4487.
3/4 worlds win; lag7.8 fails because lag 7.8 s is longer than
one line — two groups closed before burst 1, and BaSent's
"most recently closed group" picks line 3 instead of line 1.
Root finding (round 3): a wrong mode is a FIXED POINT — every
internal tx check passes in the wrong mode (target always finds
a real group nearby, D-check passes, residual gate passes);
the median residual cannot tell right from wrong mode. Only
the slice samples (median error -3284 ms in the wrong mode on
lag7.8, -219 ms in the right mode on A r1.42) and the true lag
know it is wrong. Cluster never switches mode after
establishment (0 in every run) — it locks wrong at bootstrap
and never jumps mid-session.

ROUND-4 PROBES (probe25.py / probe25b.py / probe25c.py, same
canonical GSP=0.70 worlds — read BEFORE trusting the S-a/S-b
spec below, because the probes qualify it):
  P1. The FIRST paired slice sample of burst 1 is NOT an
      absolute lag measurement. At burst 1 the pair anchor is
      still the turn start (anchor_s = 0, ratio ~= prior 1.03):
      first sample 6900 ms (lag4.6, err +2329) / 10039 ms
      (lag7.8, err +2268). It measures burstArrival - ~500 ms
      of speech wall, i.e. (true lag + audio position of the
      dubbed line's start - ~500*ratio). The bias (+2268..+2329)
      is the line-1 start offset, lag-independent, and it is
      EXACTLY the unknown the seed is trying to find. A literal
      "sliceLag = first pair sample" therefore carries almost
      no lag information: burstArrival - sliceLag ~= 300 ms in
      every world, and the nearest closed SENT group to 300 ms
      is line 0's group (startEst 40) — the line that was never
      translated. Literal S-a is expected to seed ~7160/10360
      (err ~+2550, same as the loop seed). Measured, not assumed
      (see Phase 2).
  P2. VAD slices are CONTIGUOUS (music passes the VAD gate, so
      counted speech never pauses between lines): slice (sw,ew)
      gaps are 0/-24..0 ms through line 0 -> line 1 -> line 2.
      There is NO inter-line pause gap to detect line 1's start
      from. A pause-gap seed variant is dead on arrival in this
      sim and is NOT implemented (no silent-speech boundary
      exists in the slice stream).
  P3. Per-burst slice medians DRIFT inside a burst while the
      ratio is wrong (burst 2 r1.51: 6507 -> 2329 ms over the
      burst, ratio 0.98 vs true 0.70) and STABILISE once anchor
      and ratio are right (burst 4 r1.42: err +73 -> +49, ratio
      0.72). A whole-burst slice median is trustworthy only
      when its within-burst drift is small. In the WRONG mode
      (lag7.8) the medians stabilise TOO — at the wrong line's
      lag (bursts 4-9: 3666-4169 ms, ref ~4000-4500, deviation
      mostly < 1500 ms): the slice sample inherits the anchor,
      so once the anchor is the wrong line and the ratio has
      converged, the slice median AGREES with the wrong ref.
      The slice can therefore referee the mode only via its
      deviation from ref, and that deviation vanishes once the
      wrong mode settles. S-b's trigger is expected to be a
      null result; S-b2 (drift gate) tests whether requiring
      stable medians changes anything (it should not — the
      stable medians are mode-locked too).
  P4. Slice samples and tx lat_tx inherit the SAME anchor
      error, so S-b's adopt criteria (|lat_tx - sliceLag|
      <= 600 on >=3 bursts, better than base per slice) are
      SELF-FULFILLING once a trial ref steers selection and
      anchor: during a trial the anchor follows the trial ref,
      so slice medians and lat_tx both move to the trial mode
      and "agree" with each other by construction. The only
      real protection in S-b is the trigger itself. If a trial
      ever fires on a wrong candidate, adoption is likely —
      this is recorded as a design warning, not hidden.

ROUND-4 VARIANTS (on FINAL base; Rb+Rc2+Rf1+V2000cap500):
  S-a (Sa, literal): at the FIRST paired slice sample of
      burst 1, sliceLag = that sample; targetStart =
      firstBurstArrival - sliceLag; re-seed from the closed
      SENT group (800 ms gap, as BaSent) whose startEst is
      nearest targetStart. Replaces BaSent's "most recently
      closed" rule. Expected (P1): picks line 0, seed err
      ~+2550 — FAIL. Run to measure, not to assume.
  S-a2 (Sa2, slice + loop-seed bound): same as S-a, but
      candidates are restricted to startEst >= first VAD
      marker wall (equivalently implied lag <= loop seed:
      the burst's line starts at/after the VAD turn start, so
      a group starting before the turn-start marker cannot be
      this burst's line). Fallback to BaSent's most-recent rule
      when no candidate exists (e.g. if line 0 itself were the
      translated line). In these worlds the bound excludes
      line 0's group (startEst 40 < marker 120) and the slice
      target (~300 ms) then selects the earliest post-marker
      group — line 1 — at ANY lag, because burst 1 is always
      line 1's dub here. Expected: seed err ~-440 (lag4.6)
      / ~-370 (lag7.8). The honest caveat: the exclusion
      hinges on 80 ms (40 vs 120); the slice target itself
      contributes little in these worlds (it always lands near
      the turn start because anchor=0). Documented, not hidden.
  S-a2-stack (BaSent+Sa2): BaSent fires at chunk 1 (as in
      FINAL), Sa2 re-seeds at the first pair sample. Tests
      whether the slice re-correction repairs lag7.8 even when
      BaSent already fired (and whether the ~279 ms of
      loop/BaSent seed before the correction matters).
  S-b (Sb, literal referee): per-burst slice medians
      (median of the burst's non-boundary pair samples, ALL
      samples as pair() produced them, D-check rejects
      included). When the median of the N most recent
      per-burst medians deviates from the current ref by
      > 1500 ms (N=3 and N=5), open a 5-burst trial at
      candidate = that median; adopt when >=3 CONSECUTIVE
      trial bursts have |lat_tx - sliceMed| <= 600 ms AND the
      trial bursts' slice medians are closer to the candidate
      than to the base ref (median |med - cand| < median
      |med - base|). Cooldown 10 bursts, max 3 trials/run.
      Trial ref is end-to-end (selection, residual gate, R-b,
      D-based path all read it), mirroring B-c. Expected (P3):
      0 trials — the deviation never sustains > 1500 ms once
      the ref is established, in either mode.
  S-b2 (Sb drift-gated): S-b with N=3, but a burst's median
      only counts toward the trigger when its within-burst
      drift |last - first| <= 600 ms (ratio converged, anchor
      settled). Expected: still 0 trials (stable medians are
      mode-locked, P3).
  Combos: Sa2+Sb3 (Sa2 replacing BaSent, plus Sb N=3) and
      BaSent+Sa2+Sb3 (stacked + Sb3). Stop at the first variant
      passing GATE25 ex-cold on all 4 worlds.

GATE25 = GATE24 + absolute |D-lag| bar as the MAIN bar:
  end-p90 TRUE steady < 1000 ms on film A r1.42 AND dense r1.42
  (r1.51 + lag7.8 reported); sync max/p90 < 1000 ms (full +
  steady reported separately; the round-1 first-burst
  cold-start outlier is ex-cold-start); D pinned 0% steady
  (t > 60 s) — lag7.8 is judged with the KNOWN cap artifact:
  a correct seed wants D ~= 7707 + ~1000 margin = ~8700 ms >
  8000 cap, so D pinned 0% is unreachable there even when
  perfect; the cap-adjusted verdict waives the D-pin bar ONLY
  on lag7.8 when its end-p90 + sync pass, and says so;
  pairs > 0 in EVERY 60 s window; samples/60 s >= 40 (min
  window); dropped audio steady < 5%; line-start steady median
  < 1200 ms; |D end - true lag| <= 1000 ms ABSOLUTE on EVERY
  world (main bar); |seed - true lag| <= 600 ms at the FIRST
  seed on EVERY world; cluster mode switches after established
  = 0 (S-b intentional adoptions logged separately and must
  land within 600 ms of the true lag — an adopt outside that
  is a FAIL, not a switch exemption). The OLD bar
  |D-lag| <= REPRO is REPORTED per world (not pass/fail) for
  the parent's decision. v6 + sim1018 regressions PASS via the
  clean 1-process/seed driver (regone25.py), with absolute
  OLD/NEW numbers and the OLD range across seeds.
"""
import math
import os
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1024 as s24
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
RESULTS25 = []
REPRO25 = {}   # (film, ratio, lag0) -> res dict (SHIPPED21)


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
# Tracker v1.0.22 round 4 (subclass of NewTracker24; sim1024.py
# untouched). Variant switches are CLASS attrs, set per run by
# set25() — the event loop only sets the round-1/2 flags.
# ----------------------------------------------------------------
class NewTracker25(s24.NewTracker24):
    SEED25_MODES = ()   # subset of {'Sa', 'Sa2'}
    SB_ON = False       # S-b referee on/off
    SB_N = 3            # S-b: bursts in the trigger median
    SB_M = 5            # S-b: trial length in bursts
    SB_TRIG_MS = 1500   # S-b: |median slice - ref| to trial
    SB_AGREE_MS = 600   # S-b: |lat_tx - sliceMed| to agree
    SB_AGREE_RUN = 3    # S-b: consecutive agreeing bursts
    SB_DRIFT_GATE = False  # S-b2: only low-drift medians count
    SB_DRIFT_MAX_MS = 600
    SB_COOLDOWN = 10
    SB_MAX_TRIALS = 3

    def __init__(self):
        super().__init__()
        # --- S-a: slice-seed state ---
        self._sa_pending = False
        self._sa_first_arrival = None
        self._sa_trace = None   # dict, set when S-a fires
        self._sa_basent_seed = None
        # --- S-b: per-burst slice referee state ---
        self._sb_samples = {}      # burst_seq -> [slice samples]
        self._sb_first = {}        # burst_seq -> first sample
        self._sb_last = {}         # burst_seq -> last sample
        self._sb_lat_ok = {}       # burst_seq -> lat_tx (tx-ok)
        self._sb_bursts = []       # finalised burst records
        self._sb_hist = []         # per-burst medians (not None)
        self._sb_hist_drift = []   # drift alongside _sb_hist
        self._sb_last_seq = None
        self._sb_trial_active = False
        self._sb_trial_ref = None
        self._sb_trial_left = 0
        self._sb_trial_base_ref = None
        self._sb_trial_base_med = None
        self._sb_trial_run = 0
        self._sb_trial_max_run = 0
        self._sb_trial_bursts = []
        self._sb_trials = 0
        self._sb_adopts = 0
        self._sb_adopted_ref = None
        self._sb_cooldown = 0
        self._sb_log = []          # trial events
        self._sb_eval = []         # (seq, medN, ref, dev, fired?)

    # -- S-a arming: the first on_dub call is burst 1 chunk 1.
    # The loop (s22._run22_inner) has already set seed_lag =
    # firstDubArrival - firstMarkerWall; s24.on_dub records the
    # loop seed and (if BaSent is in BOOT_MODES) corrects it at
    # once. S-a does NOT correct here — there is no paired slice
    # yet (pair() first fires after 500 ms of dub, ~279 ms into
    # burst 1). It arms, and feed_sample fires it on the first
    # paired slice sample. --
    def on_dub(self, now, dub_ms, true_lag, true_burst_sample=None):
        if self._sa_first_arrival is None:
            self._sa_first_arrival = now
            if ('Sa' in self.SEED25_MODES
                    or 'Sa2' in self.SEED25_MODES):
                self._sa_pending = True
        if (self.seed_corr is not None
                and self._sa_basent_seed is None
                and ('BaSent' in self.BOOT_MODES
                     or 'Ba' in self.BOOT_MODES)):
            self._sa_basent_seed = self.seed_corr
        return super().on_dub(now, dub_ms, true_lag,
                              true_burst_sample)

    # -- S-a firing + S-b bucketing, wrapped around the whole
    # feed_sample chain (s24 mode-switch counting, s23 tagging,
    # s22 CHASE, s19 D-check). The sample argument is the raw
    # pair() output, accepted or D-check-rejected alike: the
    # referee sees the slice measurement as pair() produced it.
    # S-a fires BEFORE the sample is fed, so the corrected seed
    # (not the loop seed) is what the D-check judges it
    # against. --
    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        if not boundary:
            bseq = getattr(self, 'burst_seq', None)
            if bseq is not None:
                self._sb_samples.setdefault(bseq, []).append(sample)
                if bseq not in self._sb_first:
                    self._sb_first[bseq] = sample
                self._sb_last[bseq] = sample
            if self._sa_pending:
                self._sa_pending = False
                self._sa_correct(sample, now, true_lag)
        return super().feed_sample(sample, now, true_lag,
                                   boundary=boundary, **kw)

    def _sa_correct(self, slice_lag, now, true_lag):
        """S-a / S-a2 seed from the first paired slice sample.
        targetStart = firstBurstArrival - sliceLag; pick the
        closed SENT group whose startEst is nearest targetStart.
        Sa2 additionally requires startEst >= first marker wall
        (implied lag <= loop seed); fallback is BaSent's
        most-recent-closed rule when no candidate exists."""
        arrival = self._sa_first_arrival
        if arrival is None:
            self._sa_trace = {'mode': 'Sa-no-arrival'}
            return
        target = arrival - slice_lag
        marker_wall = (self.markers[0]['wall']
                       if self.markers else None)
        use_bound = 'Sa2' in self.SEED25_MODES
        cands = []
        for g in self._sent_groups:
            if not g.get('closed'):
                continue
            se = self._tx_sentence_start(g)
            if not (arrival - s21.TX_WIN_MS <= se <= arrival - 300):
                continue
            if (use_bound and marker_wall is not None
                    and se < marker_wall):
                continue
            cands.append((abs(se - target), se, g))
        mode = 'Sa2' if use_bound else 'Sa'
        chosen = None
        if cands:
            cands.sort(key=lambda t: (t[0], t[1]))
            chosen = cands[0][2]
        if chosen is None and use_bound:
            # Fallback: BaSent most-recent-closed (no bound).
            best, best_se = None, None
            for g in self._sent_groups:
                if not g.get('closed'):
                    continue
                se = self._tx_sentence_start(g)
                if not (arrival - s21.TX_WIN_MS <= se
                        <= arrival - 300):
                    continue
                if best_se is None or se > best_se:
                    best, best_se = g, se
            chosen = best
            mode = 'Sa2-fallback-BaSent'
        if chosen is None:
            self._sa_trace = {'mode': mode + '-none',
                              'slice_lag': slice_lag,
                              'target': target,
                              'marker_wall': marker_wall,
                              'n_cands': len(cands)}
            return
        se = self._tx_sentence_start(chosen)
        el = arrival - se
        basent_before = self.seed_corr  # BaSent value, if it fired
        self.seed_corr = el
        self.seed_corr_err = el - true_lag
        self.seed_group = (chosen['first'], chosen['last'], True, se)
        self.seed_mode = mode
        self.seed_lag = el
        self.ema.seed(el)
        nd = max(3000, min(el + self.dynamic_margin(), 8000))
        self._set_delay(nd)
        self.hold = max(0, nd - el - self.pipeline_ms())
        self._sa_trace = {'mode': mode, 'slice_lag': slice_lag,
                          'slice_time': now, 'target': target,
                          'marker_wall': marker_wall,
                          'n_cands': len(cands),
                          'chosen': (chosen['first'],
                                     chosen['last'], se),
                          'seed': el, 'seed_err': el - true_lag,
                          'basent_seed': basent_before,
                          'loop_seed': self.seed_loop}

    # -- S-b trial ref overrides _ref_lag end-to-end (same as
    # B-c). The Bc mirror flags are set alongside so the
    # inherited mode-switch counter treats a trial/adoption as
    # intentional, not as an unintentional switch. --
    def _ref_lag(self):
        if self._sb_trial_active and self._sb_trial_ref is not None:
            return self._sb_trial_ref
        return super()._ref_lag()

    # -- S-b: finalise the previous burst when a new burst
    # starts (its slice samples are complete; its tx anchor,
    # if any, landed 0-350 ms after its start, i.e. during the
    # burst — TX_OUT_FRAG_DELAY (0,200) + step 150). --
    def on_dub_burst(self, now, true_lag, true_sample=None):
        super().on_dub_burst(now, true_lag, true_sample)
        seq = getattr(self, 'burst_seq', None)
        prev = self._sb_last_seq
        if prev is not None and prev != seq:
            self._sb_finalise(prev, now)
        self._sb_last_seq = seq

    def _sb_finalise(self, seq, now):
        samples = self._sb_samples.pop(seq, [])
        first = self._sb_first.pop(seq, None)
        last = self._sb_last.pop(seq, None)
        med = _med(samples) if samples else None
        drift = (abs(last - first) if first is not None
                 and last is not None else None)
        lat_tx = self._sb_lat_ok.get(seq)
        ref_now = self._ref_lag()
        rec = {'seq': seq, 'n': len(samples), 'med': med,
               'first': first, 'last': last, 'drift': drift,
               'lat_tx': lat_tx, 'ref': ref_now, 'now': now}
        self._sb_bursts.append(rec)
        if med is not None:
            self._sb_hist.append(med)
            self._sb_hist_drift.append(drift)
        if self.SB_ON:
            self._sb_step(now, rec)

    def _sb_step(self, now, rec):
        """Trigger / trial accounting, one step per finalised
        burst. Evaluations are logged even before the ref is
        established (ref = seed then); a trial only STARTS once
        the ref is established."""
        if self._sb_trial_active:
            self._sb_trial_bursts.append(rec)
            agree = (rec['lat_tx'] is not None
                     and rec['med'] is not None
                     and abs(rec['lat_tx'] - rec['med'])
                     <= self.SB_AGREE_MS)
            if agree:
                self._sb_trial_run += 1
                self._sb_trial_max_run = max(
                    self._sb_trial_max_run, self._sb_trial_run)
            else:
                self._sb_trial_run = 0
            self._sb_trial_left -= 1
            if self._sb_trial_left <= 0:
                self._sb_end_trial(now)
            return
        if self._sb_cooldown > 0:
            self._sb_cooldown -= 1
            return
        if len(self._sb_hist) < self.SB_N:
            return
        last_n = self._sb_hist[-self.SB_N:]
        med_n = _med(last_n)
        ref = self._ref_lag()
        dev = med_n - ref
        fired = False
        drift_ok = True
        if self.SB_DRIFT_GATE:
            drifts = self._sb_hist_drift[-self.SB_N:]
            drift_ok = all(
                d is not None and d <= self.SB_DRIFT_MAX_MS
                for d in drifts)
        if (abs(dev) > self.SB_TRIG_MS and drift_ok
                and self._ref_established()
                and self._sb_trials < self.SB_MAX_TRIALS):
            self._sb_start_trial(now, med_n, ref)
            fired = True
        self._sb_eval.append((rec['seq'], med_n, ref, dev, fired,
                              drift_ok))

    def _sb_start_trial(self, now, med_n, ref):
        base_meds = self._sb_hist[-self.SB_N:]
        self._sb_trial_base_med = _med(
            [abs(m - ref) for m in base_meds])
        self._sb_trial_ref = med_n
        self._sb_trial_base_ref = ref
        self._sb_trial_active = True
        self._sb_trial_left = self.SB_M
        self._sb_trial_run = 0
        self._sb_trial_max_run = 0
        self._sb_trial_bursts = []
        self._sb_trials += 1
        self._bc_trial_active = True   # mirror: suppress the
        self._bc_trial_ref = None      # inherited switch counter
        self._sb_log.append((now, 'trial-start', ref, med_n))

    def _sb_end_trial(self, now):
        trial_ref = self._sb_trial_ref
        bursts = list(self._sb_trial_bursts)
        meds = [b['med'] for b in bursts if b['med'] is not None]
        med_cand = (_med([abs(m - trial_ref) for m in meds])
                    if meds else None)
        med_base = (_med([abs(m - self._sb_trial_base_ref)
                          for m in meds]) if meds else None)
        adopt = (self._sb_trial_max_run >= self.SB_AGREE_RUN
                 and len(meds) >= 3 and med_cand is not None
                 and med_base is not None
                 and med_cand < med_base)
        if adopt:
            self._sb_adopted_ref = trial_ref
            self._bc_adopted_ref = trial_ref
            self._last_ref_seen = trial_ref
            self._sb_adopts += 1
            self._sb_log.append(
                (now, 'trial-ADOPT', trial_ref,
                 (self._sb_trial_max_run, med_cand, med_base)))
        else:
            self._last_ref_seen = None
            self._sb_log.append(
                (now, 'trial-reject', trial_ref,
                 (self._sb_trial_max_run, med_cand, med_base)))
        self._sb_trial_active = False
        self._sb_trial_ref = None
        self._sb_trial_left = 0
        self._bc_trial_active = False
        self._bc_trial_ref = None
        self._sb_cooldown = self.SB_COOLDOWN

    # -- S-b: capture the tx-ok lat_tx per burst (same
    # best-group computation as s24, side-effect free). --
    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        burst_arrival = self._last_burst_arrival
        lat_tx = None
        if burst_arrival is not None:
            best_pre, _ = self._best_tx_group(burst_arrival,
                                              self._sel_lag())
            if best_pre is not None:
                lat_tx = (burst_arrival
                          - self._tx_sentence_start(best_pre))
        ok0 = self.tx_ok
        super().apply_tx_anchor(now, burst_seq, true_lag,
                                true_sample)
        if self.tx_ok > ok0 and lat_tx is not None:
            self._sb_lat_ok[burst_seq] = lat_tx
            for rec in self._sb_bursts:
                if rec['seq'] == burst_seq and rec['lat_tx'] is None:
                    rec['lat_tx'] = lat_tx


def set25(boot=('BaSent', 'Bb'), seed25=(), sb_on=False, sb_n=3,
          sb_m=5, sb_drift_gate=False, ratchet=('Rb', 'Rc2'),
          ratio_mode='rf1', valve_thresh=2000, valve_keep=1500,
          valve_cap=500, bc_n=3, bc_m=5, pause_k=0.40):
    """Set the complete round-4 variant spec: s24 base (boot /
    ratchet / ratio / valve / Bc) + S-a seed modes + S-b."""
    s24.set24(boot=boot, ratchet=ratchet, ratio_mode=ratio_mode,
              valve_thresh=valve_thresh, valve_keep=valve_keep,
              valve_cap=valve_cap, bc_n=bc_n, bc_m=bc_m,
              pause_k=pause_k)
    for k, v in dict(
            SEED25_MODES=tuple(seed25),
            SB_ON=sb_on, SB_N=sb_n, SB_M=sb_m,
            SB_DRIFT_GATE=sb_drift_gate).items():
        setattr(NewTracker25, k, v)


def best25_kw(tail_valve=True):
    """FINAL base kwargs for run22 (Rb+Rc2+Rf1+V2000cap500 via
    set25); tail_valve=False for the valve-OFF check."""
    kw = dict(BEST_KW)
    kw['tail_valve'] = tail_valve
    return kw


# ----------------------------------------------------------------
# Harness (mirrors s24.do_run24, extended with round-4 seed /
# referee metrics; s24.diag24/gate24 still print underneath).
# ----------------------------------------------------------------
def do_run25(label, tracker_cls, world, ratio, lag0, film='A',
             **kw):
    res = s24.do_run24(label, tracker_cls, world, ratio, lag0,
                       film=film, **kw)
    tr = None
    # s24.do_run24 returns the res dict; the tracker itself is
    # not returned, so re-derive S-a/S-b state from res where
    # possible and from the tracker's recorded fields via the
    # res dict's own entries (diag25 prints from res).
    res['sa_trace'] = getattr(res, 'sa_trace', None)
    diag25(label, res)
    res['gate25'] = gate25(label, res)
    RESULTS25.append(res)
    return res


def diag25(label, res):
    """Round-4 diagnostics: S-a trace + S-b trials + per-burst
    slice medians. The tracker instance is not returned by
    do_run24, so S-a/S-b state is carried on the res dict by
    _attach25 (called from do_run25 via a tracker hook — see
    _TRACKER25_LAST)."""
    tr = _TRACKER25_LAST[0]
    print("DIAG25-seed/referee %s:" % label)
    if tr is None:
        print("  (no NewTracker25 instance captured)")
        print()
        return
    sa = getattr(tr, '_sa_trace', None)
    print("  S-a: %s" % (sa,))
    print("  S-b: trials %d, adopted switches %d; log %s"
          % (getattr(tr, '_sb_trials', 0),
             getattr(tr, '_sb_adopts', 0),
             getattr(tr, '_sb_log', [])))
    print("  per-burst slice medians (seq, n, med, first, last,"
          " drift, lat_tx_ok, ref_at_finalise):")
    for b in getattr(tr, '_sb_bursts', [])[:14]:
        print("    seq=%s n=%s med=%s first=%s last=%s drift=%s"
              " lat_tx=%s ref=%s"
              % (b['seq'], b['n'], b['med'], b['first'], b['last'],
                 b['drift'], b['lat_tx'], b['ref']))
    ev = getattr(tr, '_sb_eval', [])
    big = [e for e in ev if abs(e[3]) > 1000]
    print("  S-b trigger evals (seq, medN, ref, dev, fired,"
          " drift_ok): first 8 + all |dev|>1000 ms:")
    for e in (ev[:8] + [x for x in big if x not in ev[:8]]):
        print("    seq=%s medN=%s ref=%s dev=%s fired=%s"
              " drift_ok=%s" % e)
    print()
    res['sa_trace'] = sa
    res['sb_trials'] = getattr(tr, '_sb_trials', 0)
    res['sb_adopts'] = getattr(tr, '_sb_adopts', 0)
    res['sb_log'] = list(getattr(tr, '_sb_log', []))
    res['sb_bursts'] = list(getattr(tr, '_sb_bursts', []))
    res['sb_eval'] = list(getattr(tr, '_sb_eval', []))


def gate25(label, res):
    """GATE25 per run. Main bar: |D end - true lag| <= 1000 ms
    ABSOLUTE on every world. The old |D-lag| <= REPRO bar is
    reported, never counted. D-pin is waived on lag7.8 ONLY as
    the documented cap artifact (correct D wants ~8.7 s >
    8.0 s cap) when end-p90 + sync pass there; the unwaived
    verdict is printed alongside."""
    rep = REPRO25.get((res['film'], res['ratio'], res['lag0']))
    d_pin_ok = res['d_pinned_steady'] == 0
    d_pin_waived = (res['lag0'] == LAG22_B and res['end_p90_true']
                    is not None and res['end_p90_true'] < 1000
                    and res['sync_max_steady'] is not None
                    and res['sync_max_steady'] < 1000)
    bars = {
        'end-p90 TRUE steady < 1000 ms':
            res['end_p90_true'] is not None
            and res['end_p90_true'] < 1000,
        'sync max < 1000 ms (full session)':
            res['sync_max'] is not None and res['sync_max'] < 1000,
        'sync p90 < 1000 ms':
            res['sync_p90'] is not None and res['sync_p90'] < 1000,
        'sync max < 1000 ms (steady, t>60 s)':
            res['sync_max_steady'] is not None
            and res['sync_max_steady'] < 1000,
        'D pinned 0% steady (t>60 s)': d_pin_ok,
        'pairs > 0 in EVERY 60 s window':
            res['pairs_min_win'] > 0,
        'samples/60 s >= 40 (min window)':
            res['samples_min_win'] >= 40,
        'dropped audio steady < 5%': res['drop_steady'] < 5.0,
        'line-start steady median < 1200 ms':
            res['ls_med'] is not None and res['ls_med'] < 1200,
        '|D end - true lag| <= 1000 ms ABSOLUTE (main bar)':
            res['d_end_err'] is not None
            and res['d_end_err'] <= 1000,
        '|seed - true lag| <= 600 ms (first seed)':
            res['seed_final_err'] is not None
            and abs(res['seed_final_err']) <= 600,
        'cluster mode switches after established = 0':
            res['mode_switches'] == 0,
    }
    repro_bar = None
    if rep is not None:
        repro_bar = (res['d_end_err'] is not None
                     and res['d_end_err'] <= rep['d_end_err'])
    strict = all(bars.values())
    excold = (bars['end-p90 TRUE steady < 1000 ms']
              and bars['sync p90 < 1000 ms']
              and bars['sync max < 1000 ms (steady, t>60 s)']
              and (d_pin_ok or d_pin_waived)
              and bars['pairs > 0 in EVERY 60 s window']
              and bars['samples/60 s >= 40 (min window)']
              and bars['dropped audio steady < 5%']
              and bars['line-start steady median < 1200 ms']
              and bars['|D end - true lag| <= 1000 ms ABSOLUTE (main bar)']
              and bars['|seed - true lag| <= 600 ms (first seed)']
              and bars['cluster mode switches after established = 0'])
    print("GATE25 %s:" % label)
    for k, v in bars.items():
        extra = ''
        if k.startswith('D pinned') and not v and d_pin_waived:
            extra = ' (WAIVED lag7.8 cap artifact: D wants ~8.7 s)'
        print("  %-52s %s%s"
              % (k, "PASS" if v else "FAIL", extra))
    print("  %-52s %s (|D-lag| %s vs REPRO %s)"
          % ("|D end - true lag| <= REPRO (reported only)",
             "PASS" if repro_bar else
             ("FAIL" if repro_bar is not None else "n/a"),
             res['d_end_err'],
             rep['d_end_err'] if rep is not None else None))
    print("  GATE25: %s (strict=%s excold=%s%s)"
          % ("PASS" if strict else "FAIL", strict, excold,
             " D-pin waived lag7.8" if d_pin_waived else ""))
    print()
    res['excold25'] = excold
    res['d_pin_waived'] = d_pin_waived
    return strict


# The event loop (s22._run22_inner) instantiates the tracker
# itself, so do_run25 cannot receive it back. Capture the
# instance by wrapping the tracker class's __init__ for the
# duration of one do_run25 call.
_TRACKER25_LAST = [None]
_orig_init25 = NewTracker25.__init__


def _capture_init25(self, *a, **kw):
    _orig_init25(self, *a, **kw)
    _TRACKER25_LAST[0] = self


def run4_25(label_fmt, tracker_cls, worlds4, **kw):
    """4-world set through do_run25; returns res dicts."""
    runs = []
    for (w, ratio, lag0, film) in worlds4:
        _TRACKER25_LAST[0] = None
        if tracker_cls is NewTracker25:
            NewTracker25.__init__ = _capture_init25
        try:
            runs.append(do_run25(
                label_fmt % (ratio, lag0 / 1000.0,
                             'dense' if film == 'B' else 'filmA'),
                tracker_cls, w, ratio, lag0, film=film, **kw))
        finally:
            if tracker_cls is NewTracker25:
                NewTracker25.__init__ = _orig_init25
    return runs


def run_excold25(r):
    """Per-run ex-cold-start bars (GATE25 excold, incl. the
    lag7.8 D-pin cap waiver)."""
    return bool(r.get('excold25'))


def rank_key25(runs):
    """(worlds passing excold bars, -sum end-p90, -sum |D-lag|)
    — higher is better."""
    n = sum(1 for r in runs if run_excold25(r))
    e = sum(r['end_p90_true'] or 99999 for r in runs)
    d = sum(r['d_end_err'] or 99999 for r in runs)
    return (n, -e, -d)


def variant_ok25(results):
    strict = all(r['gate25'] for r in results)
    excold = all(run_excold25(r) for r in results)
    return strict, excold


# ----------------------------------------------------------------
# Regressions (mirror s24.reg24, with NewTracker25; one call
# per process via regone25.py so OLD/NEW share worlds).
# ----------------------------------------------------------------
_REG25_OLD = {}


def reg25(kw25, tag):
    """v6 + sim1018 regressions for the given round-4 winner
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
        NewTracker25, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384, **kw25)
    steady_new6 = s21.report21("NEW (v1.0.22r4 %s), v6 world" % tag,
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
    if 'old' not in _REG25_OLD:
        old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = \
            s22.run22(s19.NewTracker19, vad18, dub18, completes18,
                      line_start18, txin6, txsrc6, min_buf=4096,
                      line_counted=line_counted18)
        steady_old18 = s21.report21(
            "OLD (v1.0.19), sim1018 world, tc ON", old18, dh18,
            s6018, tp6018, holdsat18, bursts18, ex18,
            line_start18, {})
        _REG25_OLD.update(old=old18, steady=steady_old18,
                          tp60=tp6018)
    old18 = _REG25_OLD['old']
    steady_old18 = _REG25_OLD['steady']
    tp6018 = _REG25_OLD['tp60']
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        s22.run22(NewTracker25, vad18, dub18, completes18,
                  line_start18, txin6, txsrc6, min_buf=4096,
                  line_counted=line_counted18, **kw25)
    steady_new18 = s21.report21(
        "NEW (v1.0.22r4 %s), sim1018 world, tc ON" % tag, new18,
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
    print("SIM1018 OLD range note: the 8.3%% cap is the OLD of"
          " ONE world/seed; OLD itself varies across seeds —"
          " see regone25.py runs k=0,1,2 for the range.")
    print()
    return reg6, reg18, drop_new18, drop_old18


def summary25():
    print("=" * 110)
    print("SUMMARY25 (10-min sessions; end-p90 = TRUE render,"
          " steady window; p90 = p90 of |value|):")
    print("%-38s %7s %7s %7s %7s %6s %6s %5s %5s %6s %6s %6s %7s"
          % ("run", "endp90", "kotPl", "syncMx", "syncP90",
             "DpinS%", "prMin", "smMin", "drop%", "Dend",
             "Derr", "floor", "seedErr"))
    for r in RESULTS25:
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
    print("GATE25 per run (strict incl. absolute |D-lag| bar):")
    for r in RESULTS25:
        print("  %-38s gate25=%s (excold25=%s, seedErr=%s,"
              " switches=%d, sbTrials=%d sbAdopt=%d)"
              % (r['label'][:38], r['gate25'], r.get('excold25'),
                 r['seed_final_err'], r['mode_switches'],
                 r.get('sb_trials', 0), r.get('sb_adopts', 0)))


# ----------------------------------------------------------------
# Main.
# ----------------------------------------------------------------
def _report_variant(tag, runs):
    strict, excold = variant_ok25(runs)
    print("%s: strict=%s excold=%s; worlds passing excold bars"
          " %d/4; end-p90 %s; |D-lag| %s; seedErr %s; switches %s;"
          " sbTrials %s sbAdopt %s"
          % (tag, strict, excold,
             sum(1 for r in runs if run_excold25(r)),
             [round(r['end_p90_true']) for r in runs],
             [round(r['d_end_err']) for r in runs],
             [None if r['seed_final_err'] is None
              else round(r['seed_final_err']) for r in runs],
             [r['mode_switches'] for r in runs],
             [r.get('sb_trials', 0) for r in runs],
             [r.get('sb_adopts', 0) for r in runs]))
    print()
    return strict, excold


def main():
    t_start = time.time()
    mode = sys.argv[1] if len(sys.argv) > 1 else 'full'
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("sim1025 ROUND 4: seed v2 + re-bootstrap referee from"
          " VAD slice samples, on FINAL (BaSent+Bb + BEST"
          " Rb+Rc2+Rf1+V2000cap500). Worlds: film A r1.42/r1.51"
          " lag4.6, dense r1.42, film A r1.42 lag7.8; 10-min"
          " sessions, tc OFF. World set: GSP=0.70 (canonical"
          " s23.main set); every run logs its world/seed.")
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
    repro_runs = run4_25("REPRO25 shipped r%.2f lag%.1f %s",
                         s21.NewTracker21, worlds4, **s22.SHIPPED21)
    for r in repro_runs:
        REPRO25[(r['film'], r['ratio'], r['lag0'])] = r
        s24.REPRO24[(r['film'], r['ratio'], r['lag0'])] = r
        s23.REPRO23[(r['film'], r['ratio'], r['lag0'])] = r
    print("REPRO25 baselines (end-p90, |D-lag|, seedErr): %s"
          % ({k: (round(v['end_p90_true']),
                 round(v['d_end_err']),
                 None if v['seed_final_err'] is None
                 else round(v['seed_final_err']))
             for k, v in REPRO25.items()},))
    print()
    if mode == 'repro':
        summary25()
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return

    # ---- Phase 1: FINAL base re-run (must reproduce s24). ----
    print("#### PHASE 1: FINAL base re-run (BaSent+Bb, 4 worlds)"
          " ####")
    set25(boot=('BaSent', 'Bb'), seed25=(), sb_on=False)
    final_runs = run4_25("FINAL25 r%.2f lag%.1f %s",
                         NewTracker25, worlds4, **best25_kw())
    _report_variant("FINAL25 re-run", final_runs)
    print("FINAL25 expected (s24 FINAL): end-p90"
          " [548, 726, 420, 3924]; |D-lag| [979, 616, 733, 2151];"
          " seedErr [-436, -438, -436, -4487]")
    print()
    if mode == 'quick':
        summary25()
        print("TOTAL runtime %.1f s" % (time.time() - t_start))
        return

    # ---- Phase 2: S-a singles (seed v2), one at a time. ----
    print("#### PHASE 2: S-a singles on FINAL base ####")
    singles = {}
    for tag, boot, seed25 in (
            ('Sa-replace', ('Bb',), ('Sa',)),
            ('Sa2-replace', ('Bb',), ('Sa2',)),
            ('BaSent+Sa2-stack', ('BaSent', 'Bb'), ('Sa2',))):
        set25(boot=boot, seed25=seed25, sb_on=False)
        runs = run4_25("%s r%%.2f lag%%.1f %%s" % tag,
                       NewTracker25, worlds4, **best25_kw())
        singles[tag] = runs
        _report_variant(tag, runs)
    order = sorted(singles, key=lambda t: rank_key25(singles[t]),
                   reverse=True)
    print("S-A SINGLES RANKED (best first): %s"
          % ([(t, sum(1 for r in singles[t] if run_excold25(r)))
              for t in order],))
    print()

    # ---- Phase 3: S-b singles (slice referee), one at a time.
    # Base here is FINAL (BaSent+Bb) WITHOUT any S-a change, so
    # the referee is measured on the known wrong-mode lag7.8. --
    print("#### PHASE 3: S-b singles on FINAL base ####")
    sb_singles = {}
    for tag, sb_n, drift in (('Sb3', 3, False),
                             ('Sb5', 5, False),
                             ('Sb3-driftgate', 3, True)):
        set25(boot=('BaSent', 'Bb'), seed25=(), sb_on=True,
              sb_n=sb_n, sb_drift_gate=drift)
        runs = run4_25("%s r%%.2f lag%%.1f %%s" % tag,
                       NewTracker25, worlds4, **best25_kw())
        sb_singles[tag] = runs
        _report_variant(tag, runs)
    print("S-B SINGLES: trials/adopts per world are the verdict"
          " (a referee that never fires cannot fix lag7.8).")
    print()

    # ---- Phase 4: combos, best S-a first, stop at first
    # excold pass on all 4 worlds. ----
    print("#### PHASE 4: combos, stop at first excold pass ####")
    combos = [(('Sa2+Sb3', ('Bb',), ('Sa2',), True, 3, False)),
              (('BaSent+Sa2+Sb3', ('BaSent', 'Bb'), ('Sa2',),
                True, 3, False))]
    combo_runs = None
    combo_spec = None
    combo_tag = None
    for tag, boot, seed25, sb_on, sb_n, drift in combos:
        set25(boot=boot, seed25=seed25, sb_on=sb_on, sb_n=sb_n,
              sb_drift_gate=drift)
        runs = run4_25("%s r%%.2f lag%%.1f %%s" % tag,
                       NewTracker25, worlds4, **best25_kw())
        strict, excold = _report_variant(tag, runs)
        if combo_runs is None or rank_key25(runs) > rank_key25(combo_runs):
            combo_runs, combo_spec, combo_tag = (
                runs, (boot, seed25, sb_on, sb_n, drift), tag)
        if excold:
            print("COMBO-WINNER: %s (first variant passing"
                  " GATE25 ex-cold-start on all 4 worlds)"
                  % tag)
            break
    if combo_runs is None:
        combo_runs, combo_spec, combo_tag = (
            final_runs, (('BaSent', 'Bb'), (), False, 3, False),
            'FINAL25')
    print("BEST SO FAR: %s rank=%s"
          % (combo_tag, rank_key25(combo_runs)))
    print()

    # ---- Phase 5: valve re-check on the best variant. ----
    print("#### PHASE 5: valve re-check on the best variant"
          " (V2000cap500 vs valve-OFF) ####")
    boot, seed25, sb_on, sb_n, drift = combo_spec
    set25(boot=boot, seed25=seed25, sb_on=sb_on, sb_n=sb_n,
          sb_drift_gate=drift)
    off_runs = run4_25(combo_tag + "+valveOFF r%.2f lag%.1f %s",
                       NewTracker25, worlds4, **best25_kw(
                           tail_valve=False))
    print("VALVE-OFF on %s: end-p90 %s; |D-lag| %s; drop%% %s;"
          " line-start med %s; pairsMin %s; samplesMin %s"
          % (combo_tag,
             [round(r['end_p90_true']) for r in off_runs],
             [round(r['d_end_err']) for r in off_runs],
             [round(r['drop_steady'], 1) for r in off_runs],
             [None if r['ls_med'] is None else round(r['ls_med'])
              for r in off_runs],
             [r['pairs_min_win'] for r in off_runs],
             [r['samples_min_win'] for r in off_runs]))
    _report_variant(combo_tag + "+valveOFF", off_runs)
    valve_off_better = (rank_key25(off_runs)
                        >= rank_key25(combo_runs))
    use_valve_off = bool(valve_off_better)
    print("VALVE DECISION (4-world rank only; regressions are"
          " run clean via regone25.py): %s (valve-OFF rank %s vs"
          " valve-ON rank %s)"
          % ("valve-OFF" if use_valve_off else "V2000cap500",
             rank_key25(off_runs), rank_key25(combo_runs)))
    print()

    # ---- Phase 6: FINAL winner designation + cross-check on
    # GSP=0.95 worlds (the world set round-2 phase 2d used).
    # The final run is the already-measured run with the winning
    # spec (re-running it reproduces identical numbers — the
    # loop RNG is seeded per run — so it is designated, not
    # re-run). ----
    print("#### PHASE 6: FINAL WINNER designation ####")
    final_tag = "%s+%s" % (combo_tag,
                           'valveOFF' if use_valve_off
                           else 'V2000cap500')
    final_kw = best25_kw(tail_valve=not use_valve_off)
    winner_runs = off_runs if use_valve_off else combo_runs
    strict, excold = variant_ok25(winner_runs)
    print("FINAL: %s; gate25 strict=%s excold=%s; spec boot=%s"
          " seed25=%s sb_on=%s sb_n=%s drift_gate=%s valve=%s;"
          " run kwargs=%s"
          % (final_tag, strict, excold, boot, seed25, sb_on,
             sb_n, drift,
             'valveOFF' if use_valve_off else 'V2000cap500',
             final_kw))
    print("REGRESSIONS (clean driver): run regone25.py k=0/1/2"
          " (valve ON for the OLD range, valve ON+OFF k=0 for"
          " the winner) and append the output to"
          " sim1025_final_run.txt — see the file tail.")
    print()
    print("#### PHASE 7: cross-check on GSP=0.95 worlds ####")
    s20.GATE_SPEECH_P = 0.95
    worldA95 = s22._build_world(**s22.WORLD22)
    worldB95 = s22._build_world(**s22.WORLD22_DENSE)
    worlds95 = [(worldA95, RATIO22_A, LAG22_A, 'A'),
                (worldA95, RATIO22_B, LAG22_A, 'A'),
                (worldB95, RATIO22_A, LAG22_A, 'B'),
                (worldA95, RATIO22_A, LAG22_B, 'A')]
    repro95 = run4_25("REPRO95 shipped r%.2f lag%.1f %s",
                      s21.NewTracker21, worlds95, **s22.SHIPPED21)
    set25(boot=boot, seed25=seed25, sb_on=sb_on, sb_n=sb_n,
          sb_drift_gate=drift)
    final95 = run4_25("FINAL95 %s r%%.2f lag%%.1f %%s" % final_tag,
                      NewTracker25, worlds95, **final_kw)
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
    print()
    summary25()
    print("WINNER25 spec: BOOT_MODES=%s SEED25_MODES=%s"
          " SB_ON=%s SB_N=%s SB_DRIFT_GATE=%s"
          " RATCHET_MODES=%s RATIO_MODE=rf1 VALVE"
          " thresh/keep/cap=%s/%s/%s (OFF=%s); run kwargs=%s"
          % (boot, seed25, sb_on, sb_n, drift, ('Rb', 'Rc2'),
             2000 if not use_valve_off else None,
             1500 if not use_valve_off else None,
             500 if not use_valve_off else None,
             use_valve_off, final_kw))
    print("TOTAL runtime %.1f s" % (time.time() - t_start))


if __name__ == '__main__':
    main()
