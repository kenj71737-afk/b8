"""Test the v1.0.20 fix variants in the calibrated starved world.

Calibrated world (from calibrate1020.py): soft 0.8, level 1.0,
gate_speech 0.70 — reproduces the device signature under new19
(trk-burst ~4, samples ~0, pairs ~0, D ~5000, stall ~24 speech-side,
lines-dub ~16%). Variants:
  new19            — v1.0.19 as shipped (reproduction)
  old18            — v1.0.18 VAD + new tracker (ablation)
  gated20          — PRIMARY: gated end rule, feed 240 (revert),
                     start tolerance 2
  gated20-feedGate — gated end rule, feed spectrum-gated, tol 2
  gated20-start8   — gated end rule, feed 240, start strict
Each at speeds 1.5x and 2.5x. Prints the gate20 verdict per run.
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1019 as s19
import sim1020 as s20

s20.GATE_SPEECH_P = 0.70


def one(vad_variant, tracker_cls, speed, label):
    frames, ls, lu = s20.build_world20(
        speed=speed, soft_frac=0.8, level_scale=1.0)
    vad, sent, stats = s20.run_vad20(frames, vad_variant)
    dub = s20.build_dub20(sent, ls, lu, speed=speed)
    tr, dh, s60, tp60, holdsat, bursts, ex = s20.run20(
        tracker_cls, vad, dub, [], ls, min_buf=16384,
        line_counted=None)
    dmax = max(d for _, d in dh)
    steady_ls, steady_sp, cov, gap = s20.report20(
        label, tr, dh, s60, tp60, holdsat, bursts, ex, ls, lu,
        vad_stats=stats)
    ok = s20.gate20(label, tr, tp60, steady_ls, dmax, cov, gap,
                    steady_sp=steady_sp, s60=s60,
                    rcov=ex.get('render_cov'),
                    rgap=ex.get('render_gap'))
    print('SUMMARY %s speed %.1f: lines-dub %.0f%% (render %.0f%%)'
          ' gap %s (render %s) pairs %d'
          ' samples %d Dmax %.0f trk-burst %d (ok %d cons %d no-mk'
          ' %d) stall-ep %s ratio %.2fx sent %.0f%% -> %s'
          % (label, speed, 100 * cov,
             100 * (ex.get('render_cov') or 0), gap,
             ex.get('render_gap'), tp60, s60, dmax,
             tr.burst_recv, tr.burst_ok, tr.burst_consist,
             tr.burst_no_marker,
             getattr(tr, 'stall_episodes', ex['old_style_resets']),
             1.0 / tr.ratio(),
             100.0 * stats['sent_ms'] / max(1, sum(lu.values())),
             'PASS' if ok else 'FAIL'))
    print()
    return ok


print('=== fix variants, calibrated world (soft 0.8, gate 0.70) ===')
for speed in (1.5, 2.5):
    one('new19', s19.NewTracker19, speed, 'new19 (repro)')
    one('old18', s19.NewTracker19, speed, 'old18 (ablation)')
    one('gated20', s20.NewTracker20, speed, 'gated20 (primary)')
    one('gated20-feedGate', s20.NewTracker20, speed,
        'gated20-feedGate')
    one('gated20-start8', s20.NewTracker20, speed,
        'gated20-start8')
