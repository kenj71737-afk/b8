"""Dump marker state at the diverging turnComplete (t=6408.8).

Which marker does each tracker select, and what does its marker
list look like at that moment?
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1018 as s18
import sim1019 as s19
import sim1020 as s20


def run(cls, world, flags=None, label=''):
    if flags is not None:
        for k, v in flags.items():
            s20.FLAGS[k] = v
    orig_tc = cls.on_dub_turn_complete

    def tc(self, now, *a, **kw):
        if abs(now - 6408.8) < 1:
            print('=== %s tc at %.1f ===' % (label, now))
            print('lag_est %.1f anchor_s %s ratio %.5f'
                  % (self.lag_est(now), self.anchor_s,
                     self.ratio()))
            print('markers (%d):' % len(self.markers))
            for m in self.markers:
                print('  seq %d wall %.0f audio %.0f counted %.0f'
                      ' done %s' % (m['seq'], m['wall'], m['audio'],
                                    m['counted'], m['done']))
            m = self._select_marker(now, self.lag_est(now), True)
            print('selected:', None if m is None else
                  (m['seq'], m['wall'], m['counted'],
                   now - m['wall'] - m['counted'] - 200))
        return orig_tc(self, now, *a, **kw)
    cls.on_dub_turn_complete = tc
    vad, dub, completes, line_start, line_counted = world
    s20.run20(cls, vad, dub, completes, line_start, min_buf=4096,
              line_counted=line_counted)
    cls.on_dub_turn_complete = orig_tc


world = s18.build_world_device(tc_on=True, speed=2.5)
run(s19.NewTracker19, world, label='OLD')
run(s20.NewTracker20, world,
    {'dcheck': False, 'ratio': False, 'tc': False, 'stall': False},
    label='NEW-ALL-OFF')
