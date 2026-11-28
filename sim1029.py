#!/usr/bin/env python3
"""sim1029.py — DubLive v1.0.24 VONG B (config B) SIM GATE.

Config B on the v1.0.22 base (Sa2+Sb3, Rb/Rc2/Rf1, D cap 8 s):
  * stretch ceiling 1.5 -> 1.35x (between the 1.25x the user did
    NOT report as distorted and the 1.5x sustained stretch the
    user DID report as distorted on 28/11 12:11 — the distortion
    threshold is unknown, 1.35x must be listened to on device).
  * STRONG tail valve (v1.0.23 spec, adapted to the adaptive
    base): thresh 1000 ms / keep 500 ms / cap 2000 ms per fire,
    speed gate REMOVED (arm on endLag > thresh alone — at the
    1.35x cap the old gate `target >= 1.49` would never fire).
  * SKIP-AHEAD at burst start (v1.0.23 STRONG spec): when a burst
    starts >1000 ms behind schedule (player-loop lateness at the
    burst's first chunk), drop head audio = min(late - 500,
    2000, 50% of the estimated dub length) so the remainder ends
    closer to on time. Estimate = previous burst's dub length
    (cum_dub delta between burst starts). Armed at most once per
    burst, in note_lateness (called only for a burst's first
    chunk, before its feed) — the drop is consumed from the
    2nd chunk onward via _tail_drop_remaining, exactly like the
    tail valve's head-drop.
  * SHORT PROMPT (v1.0.23 wording, reused): dub/source ratio
    r target 1.35, hard <= 1.40. In the sim the prompt is the
    `ratio` parameter (dub_dur = fed_speech * r). Main gate at
    r=1.35; sensitivity at r=1.40 / 1.45 (prompt under-delivers)
    and r=1.47 (prompt fails entirely — the ratio measured on
    the real device 28/11 12:00).
  * NOT carried from v1.0.23: fixed 40 s delay, snapshot JPEG,
    pre-roll overlay, fixed D, DubLiveSetDubbing gate. The real-
    speed measurement device (readback + playhead slope + HUD
    "that X" + CHENH! flag) is MEASUREMENT ONLY — it changes no
    sync behaviour, so it has no sim representation; it ships in
    Kotlin to close the ~1800 ms unexplained term.
  * FORBIDDEN: cap > 1.35x; D-chase; seed-only change.

Worlds (10-min sessions, exact final spec; same worlds as
sim1026/sim1027 so numbers compare directly):
  B1 filmA r1.35 lag4.6   B2 filmA r1.35 lag7.8 (pin waived)
  B3 dense r1.35 lag4.6   B4 choppy r1.35 lag4.5
  Sensitivity (same tracker, ratio only): r1.40 / r1.45 / r1.47
  on filmA + dense + choppy.
  Ablations at r1.35 (attribute the valve/skip): cap1.35 +
  OLD valve (2000/1500/500, speed gate ON, no skip) on filmA +
  dense + choppy; cap1.35 + STRONG valve WITHOUT skip on filmA
  + dense.
  Recovery: lag step +2000 ms at t=120 s, filmA r1.35 (ship)
  vs v1.0.22 baseline cap1.5 (reference).

GATE29 (v1.0.24):
  end-burst p90 TRUE t>60 s < 1000 ms; dropped audio steady
  < 2%; pairs > 0 in EVERY 60 s window; samples/60 s >= 40
  (min window); D pinned 0% steady (except lag 7.8 s: D max <=
  8000, pinning is the known 8 s-cap artifact); stretch max
  <= 1.35x (no distortion risk above the chosen cap);
  recovery: after the +2000 ms step at t=120 s, per-minute
  end-burst MEDIAN back < 500 ms within <= 5 min.
  Regression (regone29.py, separate): v6 + sim1018 must not
  fall below the v1.0.22 baseline (v6 1/3, sim1018 2/3, NEW <=
  OLD same seed).

Usage: python3 sim1029.py [ship|sens|abl|step|all] (default all)
Output: sim1029_ship_run.txt / sim1029_sens_run.txt /
        sim1029_abl_run.txt / sim1029_step_run.txt
"""
import sys
import time

sys.path.insert(0, '.')
import sim1026 as s26
import sim1025 as s25
import sim1022 as s22

CAP135 = 1.35


class NewTracker29B(s25.NewTracker25):
    """Config B ship tracker: cap 1.35x + STRONG valve
    (1000/500/2000, no speed gate) + skip-ahead at burst start
    (thresh 1000 ms, keep 500 ms, cap 2000 ms, max 50% of the
    estimated dub length)."""
    CAP135 = 1.35
    VALVE_NO_SPEED_GATE = True
    VALVE_THRESH_MS = 1000
    VALVE_KEEP_MS = 500
    VALVE_MAX_DROP_MS = 2000
    SKIP_THRESH_MS = 1000
    SKIP_KEEP_MS = 500
    SKIP_CAP_MS = 2000

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._last_burst_start_cum = None
        self._prev_burst_dub_ms = None
        self._skip_used_seq = None
        self.skip_fires = 0
        self.skip_ms_total = 0

    def _ratio_speed(self):
        if self.RATIO_ORACLE and self._ratio_oracle:
            return max(1.0, min(self.CAP135, 1.0 / self._ratio_oracle))
        return max(1.0, min(self.CAP135, 1.0 / self.ratio()))

    def _ratio_floor(self, now):
        if not self.RATIO_FLOOR:
            return 1.0
        if self.last_play_wall and now - self.last_play_wall > 5000:
            return 1.0
        f = self._ratio_speed()
        if self.CATCHUP and self._vb_end_lag > 0:
            f = f * (1.0 + self._vb_end_lag / self.CATCHUP_GAIN_MS)
        return max(1.0, min(self.CAP135, f))

    def _stretch_update(self, now):
        super()._stretch_update(now)
        if self.target_speed > self.CAP135:
            self.target_speed = self.CAP135

    def on_dub_burst(self, now, true_lag, true_sample=None):
        # Previous burst's dub length (cum_dub delta between
        # burst starts; cum_dub already includes this burst's
        # first chunk — ~100 ms of over-count, negligible for a
        # 50% cap estimate). Used only by the skip-ahead cap.
        if self._last_burst_start_cum is not None:
            self._prev_burst_dub_ms = (
                self.cum_dub - self._last_burst_start_cum)
        self._last_burst_start_cum = self.cum_dub
        super().on_dub_burst(now, true_lag, true_sample)

    def note_lateness(self, late_ms, now):
        if late_ms > 200 and self.target_speed < self.CAP135:
            self.target_speed = self.CAP135
            self.level_raise_at = now
        # Skip-ahead: this burst starts late vs its schedule
        # (loop lateness at the first chunk). Drop head audio
        # so the remainder ends closer to on time. Once per
        # burst; consumed from the 2nd chunk onward through
        # _tail_drop_remaining (same head-drop path as the tail
        # valve, so it counts in dropped_audio like the valve).
        if (self.TAIL_VALVE and late_ms > self.SKIP_THRESH_MS
                and getattr(self, 'burst_seq', None) is not None
                and self.burst_seq != self._skip_used_seq):
            drop = int(late_ms - self.SKIP_KEEP_MS)
            drop = min(drop, self.SKIP_CAP_MS)
            est = self._prev_burst_dub_ms
            if est is not None and est > 0:
                drop = min(drop, int(0.5 * est))
            if drop > 0:
                self._tail_drop_remaining += drop
                self.skip_fires += 1
                self.skip_ms_total += drop
                self._skip_used_seq = self.burst_seq


class NewTracker29Cap135OldValve(s25.NewTracker25):
    """Ablation: cap 1.35x + OLD valve (2000/1500/500, speed gate
    ON — at the 1.35x cap the gate never fires, so the valve is
    effectively dead) + NO skip-ahead. Isolates the STRONG valve
    + skip contribution."""
    CAP135 = 1.35
    VALVE_THRESH_MS = 2000
    VALVE_KEEP_MS = 1500
    VALVE_MAX_DROP_MS = 500

    def _ratio_speed(self):
        if self.RATIO_ORACLE and self._ratio_oracle:
            return max(1.0, min(self.CAP135, 1.0 / self._ratio_oracle))
        return max(1.0, min(self.CAP135, 1.0 / self.ratio()))

    def _ratio_floor(self, now):
        if not self.RATIO_FLOOR:
            return 1.0
        if self.last_play_wall and now - self.last_play_wall > 5000:
            return 1.0
        f = self._ratio_speed()
        if self.CATCHUP and self._vb_end_lag > 0:
            f = f * (1.0 + self._vb_end_lag / self.CATCHUP_GAIN_MS)
        return max(1.0, min(self.CAP135, f))

    def _stretch_update(self, now):
        super()._stretch_update(now)
        if self.target_speed > self.CAP135:
            self.target_speed = self.CAP135

    def note_lateness(self, late_ms, now):
        if late_ms > 200 and self.target_speed < self.CAP135:
            self.target_speed = self.CAP135
            self.level_raise_at = now


class NewTracker29StrongNoSkip(NewTracker29B):
    """Ablation: cap 1.35x + STRONG valve, skip-ahead DISABLED.
    Isolates the skip contribution (B minus skip)."""
    SKIP_THRESH_MS = float('inf')


def spec29():
    """v1.0.22 winner spec (Sa2+Sb3, Rb/Rc2/Rf1) — valve
    thresh/keep/cap live on the tracker classes themselves
    (STRONG 1000/500/2000 on NewTracker29B, OLD 2000/1500/500
    on the ablation), NOT in set25."""
    s25.set25(boot=('Bb',), seed25=('Sa2',), sb_on=True, sb_n=3,
              sb_drift_gate=False)
    return s25.best25_kw(tail_valve=True)


def _speed_max():
    tr = s26._TRACKER26_LAST[0]
    if tr is None:
        return None
    sp = list(getattr(tr, 'speed_used', []))
    return max(sp) if sp else None


def _skip_stats():
    tr = s26._TRACKER26_LAST[0]
    if tr is None:
        return (None, None)
    return (getattr(tr, 'skip_fires', None),
            getattr(tr, 'skip_ms_total', None))


def gate29(label, res):
    d = res.get('diag26') or {}
    end90 = d.get('end_p90_t60')
    drop = res.get('drop_steady')
    pairs = d.get('pairs_win') or []
    samps = d.get('samples_win') or []
    pinned = res.get('d_pinned_steady')
    dmax = res.get('d_max')
    smax = _speed_max()
    sk_f, sk_ms = _skip_stats()
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
        'stretch max <= 1.35x': smax is not None and smax <= 1.35 + 1e-9,
    }
    print("GATE29 %s:" % label)
    for k, v in checks.items():
        print("  %-38s %s" % (k, "PASS" if v else "FAIL"))
    ok = all(checks.values())
    print("  GATE29: %s" % ("PASS" if ok else "FAIL"))
    print("  (end-p90 t60 %.0f ms, drop %.1f%%, pairs min %d,"
          " samples min %d, valve %d ms / %d fires, skip %s ms /"
          " %s fires, speed max %s, D pinned steady %.0f%%, D max"
          " %.0f)"
          % (end90 or -1, drop if drop is not None else -1,
             min(pairs) if pairs else -1,
             min(samps) if samps else -1,
             d.get('valve_ms_total') or 0,
             d.get('valve_fires') or 0, sk_ms, sk_f,
             ("%.2f" % smax) if smax is not None else None,
             100.0 * pinned if pinned is not None else -1,
             dmax or -1))
    print()
    return ok


def gate29_pin_waived(label, res):
    """lag 7.8 s: D-pin check waived (known 8 s-cap artifact)."""
    d = res.get('diag26') or {}
    smax = _speed_max()
    sk_f, sk_ms = _skip_stats()
    print("GATE29 %s (lag 7.8 s — D-pin check WAIVED, known 8 s-cap"
          " artifact; D max must stay <= 8000):" % label)
    print("  end-p90 t60 %.0f ms, drop %.1f%%, pairs min %d,"
          " samples min %d, valve %d ms / %d fires, skip %s ms /"
          " %s fires, speed max %s, D max %.0f, D pinned steady"
          " %.0f%%"
          % (d.get('end_p90_t60') or -1,
             res.get('drop_steady', -1),
             min(d.get('pairs_win') or [0]),
             min(d.get('samples_win') or [0]),
             d.get('valve_ms_total') or 0,
             d.get('valve_fires') or 0, sk_ms, sk_f,
             ("%.2f" % smax) if smax is not None else None,
             res.get('d_max') or -1,
             100.0 * (res.get('d_pinned_steady') or 0)))
    ok = ((d.get('end_p90_t60') or 9999) < 1000
          and (res.get('drop_steady') or 99) < 2.0
          and min(d.get('pairs_win') or [0]) > 0
          and min(d.get('samples_win') or [0]) >= 40
          and (res.get('d_max') or 99999) <= 8000
          and (smax is not None and smax <= 1.35 + 1e-9))
    print("  GATE29 %s (pin waived): %s" % (label, "PASS" if ok else "FAIL"))
    print()
    return ok


def recovery_gate29(label, res, step_at_min=2, limit_min=5):
    d = res.get('diag26') or {}
    meds = d.get('end_med_min') or []
    first = None
    for m in range(step_at_min + 1, len(meds)):
        if meds[m] is not None and meds[m] < 500:
            first = m
            break
    delay = (first - step_at_min) if first is not None else None
    ok = delay is not None and delay <= limit_min
    print("RECOVERY-GATE29 %s: end-burst median by minute %s;"
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
    print("sim1029 v1.0.24 VONG B GATE: cap 1.35x + STRONG valve"
          " 1000/500/2000 (no speed gate) + skip-ahead 2000 ms +"
          " short prompt r=1.35. 10-min sessions.")
    print()
    worldC = s26._build_world26()
    s26.world_stats26("choppy r1.47 lag4.5", worldC)
    worldA = s22._build_world(**s22.WORLD22)
    worldB = s22._build_world(**s22.WORLD22_DENSE)
    kw = spec29()
    results = {}
    if mode in ('all', 'ship'):
        print("#### SHIP B: cap1.35 + STRONG valve + skip, r=1.35"
              " (prompt works), 4 worlds ####")
        results['B1'] = s26.run26(
            "B1-filmA-B r1.35 lag4.6", NewTracker29B,
            worldA, 1.35, s22.LAG22_A, film='A',
            dub_speed=1.5, **kw)
        results['B2'] = s26.run26(
            "B2-filmA-B r1.35 lag7.8", NewTracker29B,
            worldA, 1.35, s22.LAG22_B, film='A',
            dub_speed=1.5, **kw)
        results['B3'] = s26.run26(
            "B3-dense-B r1.35 lag4.6", NewTracker29B,
            worldB, 1.35, s22.LAG22_A, film='B',
            dub_speed=1.5, **kw)
        results['B4'] = s26.run26(
            "B4-choppy-B r1.35 lag4.5", NewTracker29B,
            worldC, 1.35, 4500, film='C', dub_speed=1.5, **kw)
        print("#### GATE29 verdicts (r=1.35) ####")
        allok = True
        for k in ('B1', 'B3', 'B4'):
            allok &= gate29(k, results[k][0])
        allok &= gate29_pin_waived('B2', results['B2'][0])
        print("GATE29-SHIP OVERALL (r=1.35): %s"
              % ("PASS" if allok else "FAIL"))
        print()
    if mode in ('all', 'sens'):
        print("#### SENSITIVITY: prompt under-delivers"
              " (r=1.40 / 1.45) or fails (r=1.47) ####")
        for r in (1.40, 1.45, 1.47):
            tag = "r%.2f" % r
            resA = s26.run26(
                "S-%s-filmA-B r%.2f lag4.6" % (tag, r), NewTracker29B,
                worldA, r, s22.LAG22_A, film='A',
                dub_speed=1.5, **kw)
            resD = s26.run26(
                "S-%s-dense-B r%.2f lag4.6" % (tag, r), NewTracker29B,
                worldB, r, s22.LAG22_A, film='B',
                dub_speed=1.5, **kw)
            resC = s26.run26(
                "S-%s-choppy-B r%.2f lag4.5" % (tag, r), NewTracker29B,
                worldC, r, 4500, film='C', dub_speed=1.5, **kw)
            print("#### GATE29 sensitivity %s ####" % tag)
            for lbl, rr in (("filmA", resA), ("dense", resD),
                            ("choppy", resC)):
                gate29("S-%s-%s" % (tag, lbl), rr[0])
    if mode in ('all', 'abl'):
        print("#### ABLATIONS at r=1.35 (attribute valve/skip) ####")
        for lbl, w, r, lag, film in (
                ("filmA", worldA, 1.35, s22.LAG22_A, 'A'),
                ("dense", worldB, 1.35, s22.LAG22_A, 'B'),
                ("choppy", worldC, 1.35, 4500, 'C')):
            s26.run26(
                "A-oldV-%s r1.35 lag%.1f" % (lbl, lag / 1000.0),
                NewTracker29Cap135OldValve,
                w, r, lag, film=film, dub_speed=1.5, **kw)
        for lbl, w, r, lag, film in (
                ("filmA", worldA, 1.35, s22.LAG22_A, 'A'),
                ("dense", worldB, 1.35, s22.LAG22_A, 'B')):
            s26.run26(
                "A-noSkip-%s r1.35 lag%.1f" % (lbl, lag / 1000.0),
                NewTracker29StrongNoSkip,
                w, r, lag, film=film, dub_speed=1.5, **kw)
    if mode in ('all', 'step'):
        print("#### RECOVERY: lag step +2000 ms at t=120 s ####")
        kw = spec29()
        r0 = s26.run26("R0-filmA-step2000-cap150 r1.35 lag4.6",
                       s25.NewTracker25,
                       worldA, 1.35, s22.LAG22_A, film='A',
                       dub_speed=1.5, lag_step_ms=2000, **kw)
        r1 = s26.run26("R1-filmA-step2000-cap135B r1.35 lag4.6",
                       NewTracker29B,
                       worldA, 1.35, s22.LAG22_A, film='A',
                       dub_speed=1.5, lag_step_ms=2000, **kw)
        ok0 = recovery_gate29("R0 cap1.5 (v1.0.22 baseline)", r0[0])
        ok1 = recovery_gate29("R1 cap1.35B (ship)", r1[0])
        print("RECOVERY-GATE29 OVERALL (ship R1): %s (R0 reference:"
              " %s)" % ("PASS" if ok1 else "FAIL",
                        "PASS" if ok0 else "FAIL"))
        print()
    s26.summary26()
    print("TOTAL runtime %.1f s" % (time.time() - t0))


if __name__ == '__main__':
    main()
