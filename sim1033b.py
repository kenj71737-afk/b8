#!/usr/bin/env python3
"""sim1033b.py — diagnose why Fix A3 does NOT restore step
recovery. Step run (filmA r=1.35, +2000 ms at t=120 s) with
the A3 tracker: per-minute D / lagEst / ref / trial-active /
trial-ref / tx_med (last-8 tx median) / tx_hist length, plus
the _sb_log (trial start/reject/adopt) and the trial
corroboration decisions.
"""
import sys
sys.path.insert(0, '.')
import sim1026 as s26
import sim1022 as s22
import sim1029 as s29
import sim1033 as s33


class DiagA3(s33.NewTracker33A3):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.diag = []

    def _ref_lag(self):
        r = super()._ref_lag()
        return r

    def on_dub_burst(self, now, true_lag, true_sample=None):
        super().on_dub_burst(now, true_lag, true_sample)
        tx_med = s33._med(self._a3_tx_hist) \
            if len(self._a3_tx_hist) >= 3 else None
        self.diag.append({
            'now': now,
            'D': self.delay,
            'lag_est': self.lag_est(now),
            'ref': self._ref_lag(),
            'trial': self._sb_trial_active,
            'trial_ref': self._sb_trial_ref,
            'bc_trial': self._bc_trial_active,
            'bc_ref': self._bc_trial_ref,
            'tx_med': tx_med,
            'n_tx': len(self._a3_tx_hist),
            'tx_hist': list(self._a3_tx_hist),
            'hold': self.hold,
        })


def _med(vals):
    if not vals:
        return None
    a = sorted(vals)
    return a[len(a) // 2]


def main():
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    worldA = s22._build_world(**s22.WORLD22)
    res, d26 = s26.run26("DiagA3-step", DiagA3, worldA, 1.35,
                         s22.LAG22_A, film='A', dub_speed=1.5,
                         lag_step_ms=2000, **s29.spec29())
    tr = s26._TRACKER26_LAST[0]
    print("=== _sb_log ===")
    for e in tr._sb_log:
        print(" ", e)
    print("=== per-minute medians (min | D | lagEst | ref |"
          " trial%% | trialRef | txMed | nTx | hold) ===")
    for m in range(10):
        rows = [r for r in tr.diag if int(r['now'] // 60000) == m]
        if not rows:
            continue
        print("  %3d | %5.0f | %6.0f | %5.0f | %5.1f | %8s |"
              " %6s | %3.0f | %5.0f"
              % (m, _med([r['D'] for r in rows]),
                 _med([r['lag_est'] for r in rows]),
                 _med([r['ref'] for r in rows]),
                 100.0 * sum(1 for r in rows if r['trial'])
                 / len(rows),
                 str(_med([r['trial_ref'] for r in rows
                           if r['trial_ref'] is not None])),
                 str(_med([r['tx_med'] for r in rows
                           if r['tx_med'] is not None])),
                 _med([r['n_tx'] for r in rows]),
                 _med([r['hold'] for r in rows])))
    print("=== tx_hist at minute ends (last-8 lat_tx) ===")
    for m in range(10):
        rows = [r for r in tr.diag if int(r['now'] // 60000) == m]
        if rows:
            print("  min %d: %s" % (m, rows[-1]['tx_hist']))
    print("sb trials=%d adopts=%d; D range: see run header"
          % (tr._sb_trials, tr._sb_adopts))


if __name__ == '__main__':
    main()
