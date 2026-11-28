#!/usr/bin/env python3
"""sim1030c.py — nail the D spike in T2 (filmA r=1.40).

Questions left open by sim1030:
  (a) which accepted samples drag the EMA/D up to ~6758 in
      minute 1 (74-78 s)?  (b) D per burst around the spike.
Hooks: feed_sample logs boundary samples > 5000 ms (and any
sample > 5400); _set_delay logs every D transition with the
last seen wall clock; on_dub_burst logs D per burst seq 15-25.
Run: T2 only (filmA r=1.40 dead valve). Output: stdout.
"""
import sys
sys.path.insert(0, '.')
import sim1026 as s26
import sim1022 as s22
import sim1029 as s29
import sim1030 as s30


class SpikeTracker(s30.TraceTracker):
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._last_now = 0
        self.d_trans = []      # (now, oldD, newD)
        self.high_samples = []  # (now, sample, boundary, true_lag, D)

    def on_dub(self, now, dub_ms, true_lag, true_burst_sample=None):
        self._last_now = now
        return super().on_dub(now, dub_ms, true_lag,
                              true_burst_sample)

    def _set_delay(self, v):
        if v != self.delay:
            self.d_trans.append((self._last_now, self.delay, v))
        return super()._set_delay(v)

    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        if sample > 5000:
            self.high_samples.append(
                (now, sample, boundary, true_lag, self.delay))
        return super().feed_sample(sample, now, true_lag, boundary,
                                   **kw)


def main():
    s26.s20.GATE_SPEECH_P = 0.70
    s26.s20.check_clock_fix()
    worldA = s22._build_world(**s22.WORLD22)
    kw = s29.spec29()
    res, d26, joined = s30.trace_run(
        "T2c-filmA r1.40 deadV", SpikeTracker, worldA, 1.40,
        s22.LAG22_A, film='A', **kw)
    tr = s26._TRACKER26_LAST[0]
    print("=== D transitions 55-95 s (now | old -> new) ===")
    for (t, old, new) in tr.d_trans:
        if 55000 <= t <= 95000:
            print("  %8.0f | %5.0f -> %5.0f (%+d)" % (t, old, new,
                                                       new - old))
    print("=== high samples >5000 ms, 55-95 s"
          " (now | sample | bnd | true_lag | D) ===")
    for (t, s, b, tl, d) in tr.high_samples:
        if 55000 <= t <= 95000:
            print("  %8.0f | %6.0f | %s | %6.0f | %5.0f"
                  % (t, s, 'B' if b else 's', tl, d))
    print("=== D per burst seq 14-26 (seq | now | D | hold) ===")
    for b in tr.trace_burst:
        if b['seq'] is not None and 14 <= b['seq'] <= 26:
            print("  %3s | %8.0f | %5.0f | %5.0f"
                  % (b['seq'], b['now'], b['D'], b['hold']))
    print("=== high-sample counts: total %d, boundary %d ==="
          % (len(tr.high_samples),
             sum(1 for h in tr.high_samples if h[2])))


if __name__ == '__main__':
    main()
