"""Find the FIRST divergence between OLD (v1.0.19) and NewTracker20
with all flags OFF on the same world.

Logs, at every burst and turnComplete, (t, hold, delay, lag_est,
pipeline_ema, anchor_s, burst_ok, tc_ok) and prints the first few
lines where the two runs differ.
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1018 as s18
import sim1019 as s19
import sim1020 as s20

LOG = []


def wrap(cls, name):
    orig = getattr(cls, name)

    def f(self, now, *a, **kw):
        before = (self.hold, self.delay, self.lag_est(now),
                  self.pipeline_ema, self.anchor_s,
                  self.burst_ok, self.tc_ok)
        r = orig(self, now, *a, **kw)
        after = (self.hold, self.delay, self.lag_est(now),
                 self.pipeline_ema, self.anchor_s,
                 self.burst_ok, self.tc_ok)
        LOG.append((name, now, before, after))
        return r
    setattr(cls, name, f)


def run(cls, world, flags=None):
    if flags is not None:
        for k, v in flags.items():
            s20.FLAGS[k] = v
    global LOG
    LOG = []
    vad, dub, completes, line_start, line_counted = world
    s20.run20(cls, vad, dub, completes, line_start, min_buf=4096,
              line_counted=line_counted)
    return list(LOG)


world = s18.build_world_device(tc_on=True, speed=2.5)
wrap(s19.NewTracker19, 'on_dub_burst')
wrap(s19.NewTracker19, 'on_dub_turn_complete')
log_old = run(s19.NewTracker19, world)
wrap(s20.NewTracker20, 'on_dub_burst')
wrap(s20.NewTracker20, 'on_dub_turn_complete')
log_new = run(s20.NewTracker20, world,
              {'dcheck': False, 'ratio': False, 'tc': False,
               'stall': False})

print('events OLD %d NEW %d' % (len(log_old), len(log_new)))
n = 0
for i, (o, w) in enumerate(zip(log_old, log_new)):
    if o != w:
        print('--- first divergence at event %d ---' % i)
        for j in range(max(0, i - 2), min(len(log_old), i + 3)):
            print('OLD', log_old[j])
            print('NEW', log_new[j])
        n += 1
        if n >= 1:
            break
if n == 0:
    print('no divergence in the zipped prefix')
