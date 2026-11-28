#!/usr/bin/env python3
"""sim1030d.py — did the ~6.5 s slice samples PASS the D-check?

For every sample > 5000 ms offered to feed_sample in 55-95 s,
log the D-check inputs at that moment: sample, boundary?,
dlag=_d_based_lag(), _ref_lag(), tx_ok, n_bnd, and whether
s19's rule would accept it (boundary OR |sample-dlag|<=1500).
Also dump the _bnd_samples and _seed_cand pools at 60 s and
75 s (their densest-1200 ms cluster centre = _ref_lag).
T2 only (filmA r=1.40 dead valve).
"""
import sys
sys.path.insert(0, '.')
import sim1026 as s26
import sim1022 as s22
import sim1029 as s29
import sim1030 as s30


class CheckTracker(s30.TraceTracker):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.rows = []

    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        if sample > 5000:
            dlag = self._d_based_lag()
            ref = self._ref_lag()
            acc = boundary or abs(sample - dlag) <= 1500
            self.rows.append((now, sample, boundary, true_lag,
                              dlag, ref, self.tx_ok,
                              len(getattr(self, '_bnd_samples', [])),
                              acc, self.delay))
        return super().feed_sample(sample, now, true_lag, boundary,
                                   **kw)


def main():
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    worldA = s22._build_world(**s22.WORLD22)
    kw = s29.spec29()
    res, d26, joined = s30.trace_run(
        "T2d-filmA r1.40 deadV", CheckTracker, worldA, 1.40,
        s22.LAG22_A, film='A', **kw)
    tr = s26._TRACKER26_LAST[0]
    print("=== high samples 55-95 s: now | sample | bnd | true_lag"
          " | dlag | ref | tx_ok | n_bnd | ACCEPT? | D ===")
    for r in tr.rows:
        if 55000 <= r[0] <= 95000:
            print("  %8.0f | %6.0f | %s | %6.0f | %6.0f | %6.0f |"
                  " %5d | %5d | %s | %5.0f"
                  % (r[0], r[1], 'B' if r[2] else 's', r[3], r[4],
                     r[5], r[6], r[7], 'ACC' if r[8] else 'rej',
                     r[9]))
    print("=== pools at end: _bnd_samples (n=%d) ==="
          % len(tr._bnd_samples))
    print("  ", sorted(tr._bnd_samples))
    print("=== _seed_cand (n=%d), densest-1200 centre=%s ==="
          % (len(tr._seed_cand), tr._ref_lag()))
    print("  ", sorted(tr._seed_cand)[:60], "...")


if __name__ == '__main__':
    main()
