"""Grid round 2: relax the START rule further (need 4, tol 3).

Round 1: need 5/tol 2 -> sent 91%, render-cov 90%, render gap
~9 s (clusters of 1-2 missed lines). Turns (56) << true lines
(115), so the VAD under-starts — there is headroom to relax.
Also measures music sent to Live (sent ms falling outside true
lines) to bound the false-start cost of relaxing.
"""
import sys
sys.path.insert(0, '/home/hatch/workspace/dub-live-moi')
import sim1020 as s20

s20.GATE_SPEECH_P = 0.70


def music_sent_ms(sent, ls, lu):
    """Ms of sent audio that falls inside a true pause (music /
    silence), i.e. the false-start cost."""
    total = 0
    for (s0, e0, lid, sms) in sent:
        # approximate: the segment's sent ms spread uniformly over
        # its wall span; count the part outside any true line.
        if e0 <= s0:
            continue
        span = e0 - s0
        inside = 0
        for i, st in ls.items():
            en = st + lu[i]
            ov = max(0, min(e0, en) - max(s0, st))
            inside += ov
        total += sms * max(0.0, 1.0 - inside / span)
    return total


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
        'grid2', tr, dh, s60, tp60, holdsat, bursts, ex, ls, lu,
        vad_stats=stats)
    true_ms = sum(lu.values())
    print('need %d tol %d speed %.1f | turns %3d sent %3.0f%%'
          ' music-sent %4.0f ms (%.0f%% of sent) | render-cov'
          ' %.0f%% render-gap %6.0f ms | pairs %d samples %d'
          ' Dmax %.0f stall-ep %d ratio %.2fx burst ok %d cons %d'
          ' no-mk %d'
          % (need, tol, speed, stats['turns'],
             100.0 * stats['sent_ms'] / max(1, true_ms),
             music_sent_ms(sent, ls, lu),
             100.0 * music_sent_ms(sent, ls, lu)
             / max(1, stats['sent_ms']),
             100 * (ex.get('render_cov') or 0),
             ex.get('render_gap') or -1, tp60, s60, dmax,
             tr.stall_episodes, 1.0 / tr.ratio(),
             tr.burst_ok, tr.burst_consist, tr.burst_no_marker))


print('=== start grid round 2 (gated20, soft 0.8, gate 0.70) ===')
for need, tol in ((5, 2), (4, 2), (5, 3), (4, 3), (3, 2)):
    one(need, tol, 1.5)
print('--- speed 2.5 ---')
for need, tol in ((4, 2), (4, 3)):
    one(need, tol, 2.5)
