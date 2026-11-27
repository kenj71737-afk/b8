"""Grid: start_need x start_tol in the calibrated world.

The fix (gated20, tol 2, need 8) got sent 75% / render coverage
77% / render gap ~10 s — still short of the gate (90% / 3 s).
The remaining bottleneck is the START rule: a loud syllable is
only 100-300 ms and the gate passes speech at ~70%, so 8
consecutive passing frames is too strict. Grid over start_need
(8/6/5) x tol (0/2), gated20 VAD + NewTracker20, both speeds.
Also reports music false starts (turns whose sent_ms is tiny /
turns started on a music pause) via the turn count vs the true
line count (115 lines; turns >> lines = false starts).
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1020 as s20

s20.GATE_SPEECH_P = 0.70


def one(need, tol, speed):
    frames, ls, lu = s20.build_world20(
        speed=speed, soft_frac=0.8, level_scale=1.0)
    vad, sent, stats = s20.run_vad20(
        frames, 'gated20', start_need=need, start_tol=tol)
    dub = s20.build_dub20(sent, ls, lu, speed=speed)
    tr, dh, s60, tp60, holdsat, bursts, ex = s20.run20(
        s20.NewTracker20, vad, dub, [], ls, min_buf=16384,
        line_counted=None)
    dmax = max(d for _, d in dh)
    steady_ls, steady_sp, cov, gap = s20.report20(
        'grid', tr, dh, s60, tp60, holdsat, bursts, ex, ls, lu,
        vad_stats=stats)
    print('need %d tol %d speed %.1f | turns %3d sent %3.0f%% |'
          ' render-cov %.0f%% render-gap %6.0f ms | pairs %d'
          ' samples %d Dmax %.0f stall-ep %d ratio %.2fx'
          ' burst ok %d cons %d no-mk %d'
          % (need, tol, speed, stats['turns'],
             100.0 * stats['sent_ms'] / max(1, sum(lu.values())),
             100 * (ex.get('render_cov') or 0),
             ex.get('render_gap') or -1, tp60, s60, dmax,
             tr.stall_episodes, 1.0 / tr.ratio(),
             tr.burst_ok, tr.burst_consist, tr.burst_no_marker))
    return ex.get('render_cov') or 0, ex.get('render_gap')


print('=== start_need x start_tol grid (gated20, soft 0.8,'
      ' gate 0.70) ===')
for need in (8, 6, 5):
    for tol in (0, 2):
        one(need, tol, 1.5)
print('--- speed 2.5 (best two combos) ---')
one(6, 2, 2.5)
one(5, 2, 2.5)
