"""Bisect the sim1018 regression (line-start steady 275 -> 772 ms).

FIXED: build the world ONCE and run every variant on the SAME world
(the first version rebuilt the world per variant, so the numbers
were world noise, not flag effects).
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1018 as s18
import sim1019 as s19
import sim1020 as s20


def one_run(tracker_cls, world, flags=None):
    if flags is not None:
        for k, v in flags.items():
            s20.FLAGS[k] = v
    vad, dub, completes, line_start, line_counted = world
    tr, dh, s60, tp60, holdsat, bursts, ex = s20.run20(
        tracker_cls, vad, dub, completes, line_start,
        min_buf=4096, line_counted=line_counted)
    steady = s20.report20('variant', tr, dh, s60, tp60, holdsat,
                          bursts, ex, line_start, {})
    drop = tr.dropped_steady_ms / max(1, tr.total_steady_ms)
    return (s20.pct(steady[0], 0.5), drop, tp60, tr.tc_ok,
            tr.tc_no_marker, tr.tc_consist, tr.burst_ok,
            tr.burst_consist, tr.pipeline_ema or -1)


print('=== sim1018 world, ONE shared world, flag bisect ===')
world = s18.build_world_device(tc_on=True, speed=2.5)
r = one_run(s19.NewTracker19, world)
print('OLD (v1.0.19)      -> line-start %.0f ms | drop %.1f%% | pairs'
      '/60s %d | tc ok %d no-mk %d cons %d | burst ok %d cons %d |'
      ' EMA %.0f' % r)
for name, flags in [
        ('ALL ON ', {'dcheck': True, 'ratio': True, 'stall': True}),
        ('dcheck OFF', {'dcheck': False, 'ratio': True, 'stall': True}),
        ('ratio OFF ', {'dcheck': True, 'ratio': False, 'stall': True}),
        ('stall OFF ', {'dcheck': True, 'ratio': True, 'stall': False}),
        ('ALL OFF ', {'dcheck': False, 'ratio': False, 'stall': False})]:
    r = one_run(s20.NewTracker20, world, flags)
    print(('%s -> line-start %.0f ms | drop %.1f%% | pairs/60s %d | tc'
           ' ok %d no-mk %d cons %d | burst ok %d cons %d | EMA %.0f')
          % ((name,) + r))
