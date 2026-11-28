#!/usr/bin/env python3
"""sim1037.py — regression ablation: which fix causes the
sim1018 drop doubling (NEW 16-20% vs OLD 8-10%)?

Candidates (B/C3/D/TB on top of the v1.0.22 winner):
  - D (valve): EXONERATED — regone34nv (valve OFF) gave the
    identical drop (15.9/16.0/19.0 vs 16.0/16.2/19.0).
  - C (hold clamp/decay C1+C2): EXONERATED — C3 (symmetric
    slew, no clamp) gave the identical drop (16.2/16.0/20.5).
  Remaining suspects: B (D up-slew +250/sample) and TB
  (trial budget +-500 ms). Mechanism hypothesis for B: in a
  cap-marginal world the true lag GROWS monotonically, so a
  rate-limited D permanently trails the true lag by a few
  hundred ms -> hold = D - lag_est - pipe is permanently
  too small -> due = arrival + hold too early -> more
  STALE drops (take - due > 700 ms). TB: during trials D
  is frozen while the lag rises -> same effect, transient.

Classes (all cap 1.35 in the sim1026 gate; the regression
harness sets its own level_cap):
  NewTracker37mB  = C3 + D + TB, NO B (parent EMA).
  NewTracker37mC3 = B + D + TB, NO C (original hold logic:
                    A's on_dub_burst = original marker slew;
                    valve bookkeeping skipped — D already
                    exonerated, and this class is only for
                    the regression ablation).
  NewTracker37mTB = B + C3 + D, NO TB (plain _set_delay).
Each is run through regone37.py <class> <k> (k=0/1/2).
"""
import sys

sys.path.insert(0, '.')
import sim1018 as s18
import sim1031 as s31
import sim1034 as s34
import sim1036 as s36


class NewTracker37mB(s36.NewTracker36BC3DTB):
    """C3 + D + TB, NO Fix B: restore the parent EMA
    (instant jump when |new-cur| >= 500)."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.ema = s18.LatencyEma18(creep=250)


class NewTracker37mC3(s34.NewTracker34BCD_TB):
    """B + D + TB, NO Fix C at all (no C1/C2, no C3):
    on_dub_burst runs A's version = the original marker
    slew only. (ABCD's valve bookkeeping is skipped;
    D is already exonerated as a drop cause.)"""

    def on_dub_burst(self, now, true_lag, true_sample=None):
        s31.NewTracker31A.on_dub_burst(self, now, true_lag,
                                       true_sample)


class NewTracker37mTB(s36.NewTracker36BC3DTB):
    """B + C3 + D, NO trial budget: plain _set_delay
    (A's version, no +-500 clamp during trials)."""

    def _set_delay(self, v):
        s31.NewTracker31A._set_delay(self, v)


CLASSES = {
    'mB': NewTracker37mB,
    'mC3': NewTracker37mC3,
    'mTB': NewTracker37mTB,
    'C3': s36.NewTracker36BC3DTB,
}


class NewTracker37mBmC3(NewTracker37mC3):
    """D + TB only (no B, no C): parent EMA + original hold.
    Tests the B+C3 interaction hypothesis: both rate-limit
    the rise, so removing one alone leaves the other lagging
    a growing lag -> stale drops persist."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.ema = s18.LatencyEma18(creep=250)


class NewTracker37mBmTB(NewTracker37mTB):
    """C3 + D only (no B, no TB)."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.ema = s18.LatencyEma18(creep=250)


class NewTracker37mC3mTB(NewTracker37mC3):
    """B + D only (no C3, no TB)."""

    def _set_delay(self, v):
        s31.NewTracker31A._set_delay(self, v)


CLASSES.update({
    'mBmC3': NewTracker37mBmC3,
    'mBmTB': NewTracker37mBmTB,
    'mC3mTB': NewTracker37mC3mTB,
})
