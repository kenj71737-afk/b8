"""Calibrate the quiet world to the device signature (28/11 04:28).

Device (v1.0.19, 18dj.tv ep 3): tracker bursts ~5, samples/60s ~1,
pairs/60s ~1, D pinned at 8000, stall ~159, ratio display 2.00x,
lines with dub very low, voice stands still / intermittent.
Run 1's world (soft 0.8, level 1.0, gate 0.95) gave 124 physical
bursts — far too much dub. Grid over the three candidate causes:
soft_frac, overall level scale, spectral-gate pass rate on speech.
One line per combo; the combo closest to the device signature
becomes the calibrated world for the fix gate.
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1019 as s19
import sim1020 as s20


def one(soft, level, gate_p, speed=1.5):
    s20.GATE_SPEECH_P = gate_p
    frames, ls, lu = s20.build_world20(
        speed=speed, soft_frac=soft, level_scale=level)
    vad, sent, stats = s20.run_vad20(frames, 'new19')
    dub = s20.build_dub20(sent, ls, lu, speed=speed)
    tr, dh, s60, tp60, holdsat, bursts, ex = s20.run20(
        s19.NewTracker19, vad, dub, [], ls, min_buf=16384,
        line_counted=None)
    dmax = max(d for _, d in dh)
    true_ms = sum(lu.values())
    print('soft %.1f level %.2f gate %.2f | turns %3d sent %5.0f%%'
          ' | phys-burst %3d trk-burst %3d (ok %d cons %d no-mk %d)'
          ' | samples/60s %3d pairs/60s %2d | Dmax %4.0f | stall'
          ' %3d | ratio %.2fx | lines-dub %d/%d'
          % (soft, level, gate_p, stats['turns'],
             100.0 * stats['sent_ms'] / max(1, true_ms),
             bursts, tr.burst_recv, tr.burst_ok, tr.burst_consist,
             tr.burst_no_marker, s60, tp60, dmax,
             ex['old_style_resets'], 1.0 / tr.ratio(),
             len(ex['lines_with_burst']), len(ls)))
    return (bursts, tr.burst_recv, s60, tp60, dmax,
            ex['old_style_resets'], 1.0 / tr.ratio())


print('=== calibration grid (new19, speed 1.5) ===')
print('device target: trk-burst ~5, samples/60s ~1, pairs ~1,'
      ' Dmax 8000, stall ~159, ratio 2.00x')
for soft in (0.8, 0.9):
    for level in (1.0, 0.6, 0.5):
        for gate_p in (0.95, 0.7, 0.6):
            one(soft, level, gate_p)
print('--- soft 0.5 sensitivity (less-quiet dialogue) ---')
for level in (1.0, 0.6):
    for gate_p in (0.95, 0.6):
        one(0.5, level, gate_p)
