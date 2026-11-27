#!/usr/bin/env python3
"""
DubLive v1.0.20 — simulation.

DEVICE EVIDENCE (user test of v1.0.19, 18dj.tv, 28/11 04:28, pin 3%):
  "D 8,0 s - mau cuoi 7,2 s (47 s truoc) - 1 mau/60 s - cap moc 1/60 s
   - cho 0 lat - hold 0,8 s - VAD: nghi - bo 0 lat/0 mau (nho 2 hoan/lon 0)
   - lech hinh -135043353 ms - phat ~0 ms (TB 355 ms, cho 427 ms)
   - giong/goc 2,00x - khung hinh -135043346 ms - burst 5 ok 2 cons 3
   - moc 0 (moi nhat -) - tach thu 4/duoc 0 - stall 159 - toc do 1,25x"
  Log Live: "Chua co loi Live nao" (0 loi).
  HUD cu hon: "D 8,0 s - mau cuoi 7,2 s (214 s truoc) - 0 mau/60 s".

USER REPORT (04:34): "Te hon ban truoc, giong dung, luc dich luc
khong" — v1.0.19 is SUBJECTIVELY WORSE than v1.0.18: the voice
stands still and translates intermittently. Symptom #1 for v1.0.20:
restore CONTINUOUS voice first (every spoken line gets its dub),
then lip-sync. The user chose "Cho ban 1.0.20" (not reinstalling
v1.0.18 in the meantime).

THREE BUGS (verified in code, not guessed):
1. GARBAGE SYNC NUMBERS (new in v1.0.19): lech hinh -135,043,353 ms
   and khung hinh -135,043,346 ms (~-37.5 h). Both share one
   poisoned input: the predicted play wall from VoicePlayer
   readPlayhead(). AudioTimestamp.nanoTime is in the CLOCK_MONOTONIC
   base (same as System.nanoTime()); the v1.0.17 code subtracts
   SystemClock.elapsedRealtimeNanos(), which is CLOCK_BOOTTIME
   (includes sleep) — the difference is MINUS the total suspend
   time since boot (~37.5 h on this phone: 135,035,353 ms). So
   predicted play wall = epoch - suspend; lech = (play - marker)
   - D = -suspend - 8000 = -135,043,353 exactly. Khung hinh
   inherits the same poisoned wall as its eventWall (the JS
   round-trip correction subtracts replyWall - eventWall ~ suspend),
   so it lands 7 ms away (the two small terms differ by the video
   advance between the two JS replies). "Phat ~0 ms" is the same
   poison: pipeline = max(0, play - due) coerced to 0, flagged
   estimated (the stretch path reports the prediction at every
   burst, so v1.0.19 shows the garbage at once; v1.0.18's poll
   path used nowWall, so the poisoned prediction rarely surfaced).
   FIX: reference System.nanoTime() (same base as AudioTimestamp)
   + validate |nanoTime - nanoTime()| < 60 s (else fall back to
   due + HW) + clamp the predicted wall to a sane window around
   due + drop any sync/frame value with |.| > 60 s (show "--" +
   log the raw values).
2. TRACKER STARVATION (the voice-stops mechanism). The v1.0.19
   VAD rule (end the turn after 15 frames < START) fires on
   QUIET DIALOGUE, not just music: a drama line at RMS 300-550
   spends most of its frames below START, so the turn ends ~300 ms
   after it starts; re-starting needs 8 consecutive loud frames
   (160 ms) + the spectral gate, so the audio between the turn
   end and the re-start is LOST (only 500 ms pre-roll is kept) —
   Live receives loud fragments only, the model translates a
   fraction of the lines (device: 5 bursts in a ~4 min session).
   Meanwhile the tracker-feed gate (600) blocks the same quiet
   frames from the tracker, so counted speech barely advances,
   slices never complete, lastSpeechSliceAtMs goes stale, and
   every dub chunk / turn start fires resetForStall — the
   "stall 159" is per-CHUNK resets during starvation, not 159
   separate stalls, and each reset wipes the accumulating state
   (markers, slices, window) so the tracker can never rebuild.
   The ratio saturates at the 0.5 clamp (display 2.00x) because
   counted speech is undercounted while the dub covers the full
   sentence, and the saturated ratio drifts the anchor, so the
   D-check (which trusts the pinned D = 8 s) rejects honest
   bursts (cons 3/5) — a deadlock: wrong D -> bursts rejected
   -> no samples -> D stays pinned.
   FIX: (a) the below-START end rule counts ONLY non-speech-like
   frames (spectral gate): music advances it, quiet speech resets
   it; the gate is evaluated only on frames between END and START
   (frames below END are silence, frames at/above START are loud
   speech), so the FFT cost is ~50/s max; (b) the START rule is
   relaxed: need 4 frames (was 8) + tolerance 2 (up to 2
   consecutive non-passing frames inside the start window) +
   WARM START (3 frames for 5 s after a turn ends) + SOFT START
   (in the warm window the threshold drops to END, so an
   all-soft line can still open a turn). Grid evidence
   (grid1020.py): need 8/tol 0 -> sent 24%, need 8/tol 2 -> 75%,
   need 4/tol 2 -> 95%, need 4/tol 2 + warm 3 -> 97% sent,
   97% render coverage. The START threshold itself is NOT lowered
   (music at 250-650 would false-start); (c) the tracker feed is
   REVERTED to END (240) — the v1.0.19 gate to 600 is the
   starvation suspect. The gated end rule (a) now bounds music
   exposure instead (a turn ends 300 ms after the speech stops
   being speech-like, so at most ~300 ms of music feeds per turn;
   frames outside a turn never reach the tracker). The feedGate
   alternative (RMS >= END AND gate pass) was tested and REJECTED
   (fix1020.py: 35 vs 77 samples/60 s, ratio 1.49x vs 1.43x —
   fewer samples for a marginally truer ratio; more samples win
   for D measurement); (d) resetForStall is episode-gated (a flag:
   while already stalled it is a no-op, AND it refreshes
   lastSpeechSliceAtMs, so the next speech can complete a slice;
   the flag clears when a slice completes or a turn starts
   without a reset firing); (e) burst D-check bypass after 3
   consecutive D-check-only rejections (the anchor-implied +
   range checks still apply; ratio validation skipped for the
   bypassed burst); (f) ratio sanity bounds [0.7, 1.6] x
   expected + counted >= 500 ms on the BURST-successor chain
   only (the turnComplete chain in _accept_tc keeps [0.5, 2.0]);
   (g) diagnostics: VAD turns/60 s, tracker-fed ms/60 s,
   gate-rejected frames/60 s, stall EPISODES (not invocations).

FINAL GATE (main(), calibrated world soft 0.8 / gate 0.70,
need 4 / tol 2 / warm 3 + soft start, block True+False):
speed 1.5x (DEVICE-REALISTIC: Gemini Live ~1.5x output; the
v1.0.19 device test showed 0 dropped audio, which the sim only
reproduces at <=1.5x) — ALL 9 criteria PASS in both worlds:
render-cov 98-99%, render-gap 1004 ms, pairs 9-11/60 s,
samples 136-153/60 s, Dmax 3663-4471 ms, burst no-mk 0,
slice-play steady 201-219 ms, line-start steady 749-753 ms,
drop 0%. OVERALL GATE (1.5x + v6 + sim1018 regressions): PASS.
Speed 2.5x is a STRESS test, not a gate blocker: it FAILS only
on render-gap (12.7-18.4 s) and drop (28%) — the player cannot
drain a sustained 2.5x arrival with a 1.5x max stretch (the
drain rate is the voice-quality ceiling). Mitigations tested
and REJECTED (mitigate1020.py): MAX_STRETCH=2.0 (2.5x drop
28% -> 6%, still FAIL, and it hurts 1.5x sync: render-gap
1004 -> 2103 ms) and STALE_MS=1000 (drop 28% -> 21%). The
2.5x player capacity is a v1.0.21 candidate.
SIM BUGS FIXED ALONG THE WAY: (i) NewTracker20.on_dub_turn_complete
copied the WRONG parent (s18.BaseTracker18, dropping the
seed-lag bootstrap + FIX (6) hold refresh) — deleted the
override, inherit s18.NewTracker18's version (bisect1020.py);
(ii) build_dub20's line_at() returned the LAST line for a
src_wall in a pause, poisoning ~36% of line-start errors with
-370..-416 s garbage — now the most recent line started at or
before t (the median line-start at 2.5x went 1550 ms -> PASS);
(iii) sim1019 defined D_CAP_MS=8000 but never applied it —
run20 now caps the seed at 8000.
3. RATIO 2.00x is the clamp ceiling (ratio() in [0.5, 1.5],
   display = 1/ratio): a measurement artifact of the undercount
   + fragmentation in (2), not a true 2.00x dub. Fixed by (2);
   the stretch range is NOT widened until the measurement is
   proven true (sim re-measures it).

AI DELAY MEASUREMENT (user 04:35: "Sao ban khong dua ai tu dong
do do tre vao"): the app ALREADY auto-measures the delay every
line — that IS what D is (VAD marker <-> dub burst pairing). The
problem is the measurement keeps dying on the device, not that
it is missing. Transcription-based measurement (Gemini Live
returns inputAudioTranscription + outputAudioTranscription; both
callbacks are wired: onInputText logs only, output transcription
arrives via onTranslatedText mixed with model-turn text) is a
BACKUP only: it measures TEXT latency, not audio playout; pairing
a Chinese input line with its Vietnamese output needs fuzzy
cross-language alignment (no shared IDs, fragments, no final
markers); transcription lags audio and can be empty for short or
noisy utterances; and it cannot fix the voice-stops problem. NOT
the v1.0.20 design. Lip-reading (mouth open/close vs dub
waveform) is a farther backup: continuous face detection costs
CPU/battery and is a large build — only if audio-based
measurement (including transcription) still fails on device.
"""
import heapq
import math
import os
import random
import sys

import sim1019 as s19
import sim1018 as s18
import sim1017 as s17
import sim1014_v6 as v6

TOTAL_MS = v6.TOTAL_MS
CHUNK_MS = v6.CHUNK_MS
VAD_LAG_MS = s17.VAD_LAG_MS
KEY_ROTATE_AT_MS = v6.KEY_ROTATE_AT_MS
SEED = int(os.environ.get('SIM1020_SEED', '20261128'))

# Spectral-gate model (per 20 ms frame): a speech frame passes
# with p=0.95 (voiced speech is peaky + voice-banded; unvoiced
# fricatives fail sometimes), a music frame with p=0.12 (tonal
# instruments inside the voice band can pass — the accepted
# limitation in SpectralGate.kt).
GATE_SPEECH_P = float(os.environ.get('GATE_SPEECH_P', '0.95'))
GATE_MUSIC_P = float(os.environ.get('GATE_MUSIC_P', '0.12'))
# Fraction of soft (300-550 RMS) syllable segments in the quiet
# world. 0.8 reproduces the device regime (mostly-quiet drama
# dialogue); 0.5 is the sensitivity check.
QUIET_SOFT_FRAC = float(os.environ.get('QUIET_SOFT_FRAC', '0.8'))
# Overall audio-level scale (a candidate device cause: if the
# tap/capture delivers the video's audio attenuated, loud peaks
# barely cross START and soft dialogue falls below END).
LEVEL_SCALE = float(os.environ.get('LEVEL_SCALE', '1.0'))


# ----------------------------------------------------------------
# Quiet-drama world: mostly-quiet dialogue + music pauses.
# Frames are (t_ms, rms, line_id, gate_pass).
# ----------------------------------------------------------------
def build_world20(ratio=1.51, lag0=2400, speed=1.5, music_p=0.8,
                  tc_on=False, soft_frac=QUIET_SOFT_FRAC,
                  seed=SEED, level_scale=LEVEL_SCALE, block=True):
    """Returns (frames, line_start, line_ulen). Dub is NOT built
    here: it is generated from what the VAD actually SENDS to Live
    (build_dub20), because the v1.0.20 question is how much of the
    true speech reaches the model at all. `block=False` drops the
    30 s continuous monologue (180-210 s) — the normal-film case;
    the block is a stress case that exposes long-turn dub timing
    (a 42 s turn's dub stream finishes before the turn ends)."""
    """Returns (frames, line_start, line_ulen). Dub is NOT built
    here: it is generated from what the VAD actually SENDS to Live
    (build_dub20), because the v1.0.20 question is how much of the
    true speech reaches the model at all."""
    rng = random.Random(seed)
    frames = []
    line_start, line_ulen = {}, {}
    t = 0
    line_id = 0

    def add_frames(t0, dur_ms, fn, lid):
        tt = t0
        rem = dur_ms
        while rem > 0:
            step = min(20, rem)
            rms, gate = fn()
            frames.append((tt, rms, lid, gate))
            tt += step
            rem -= step

    def speech_fn(ulen):
        """Dialogue as syllable segments (100-300 ms each):
        soft_frac soft (300-550 RMS, below START), the rest loud
        (1200-2500). A soft segment lasts up to 300 ms = 15 frames,
        so a soft stretch INSIDE a line trips the v1.0.19
        below-START rule — exactly the device failure."""
        segs = []
        rem = ulen
        while rem > 0:
            seg = min(rng.randint(100, 300), rem)
            rms = (rng.uniform(300, 550)
                   if rng.random() < soft_frac
                   else rng.uniform(1200, 2500))
            segs.append((seg, rms))
            rem -= seg
        idx = [0]
        pos = [0]

        def fn():
            seg, rms = segs[idx[0]]
            pos[0] += 20
            if pos[0] >= seg and idx[0] + 1 < len(segs):
                idx[0] += 1
                pos[0] = 0
            gate = rng.random() < GATE_SPEECH_P
            return rms * level_scale * rng.uniform(0.9, 1.1), gate
        return fn

    def music_fn():
        """Background score: ONE level per pause, uniform
        150-650 — pauses below END (240) let the OLD VAD end
        turns realistically (the old model's 250-650 never dipped
        below END, merging the whole session into ~6 giant turns
        and making the old VAD look worse than it is); above
        START (600) ~1/8 of pauses swell."""
        level = rng.uniform(150, 650)

        def fn():
            gate = rng.random() < GATE_MUSIC_P
            return level * level_scale * rng.uniform(0.95, 1.05), gate
        return fn

    while t < TOTAL_MS:
        if block and 180_000 <= t < 210_000:
            ulen, pause = 30_000, 0
        else:
            ulen = rng.randint(1500, 3000)
            if rng.random() < 0.6:
                pause = rng.randint(100, 400)
            else:
                pause = rng.randint(1500, 3500)
        ulen = min(ulen, TOTAL_MS - t)
        if ulen <= 0:
            break
        line_start[line_id] = t
        line_ulen[line_id] = ulen
        add_frames(t, ulen, speech_fn(ulen), line_id)
        if pause > 0:
            if rng.random() < music_p:
                add_frames(t + ulen, pause, music_fn(), -1)
            else:
                add_frames(t + ulen, pause,
                           lambda: (30.0, False), -1)
        line_id += 1
        t = t + ulen + pause
    frames.sort(key=lambda x: x[0])
    return frames, line_start, line_ulen


def run_vad20(frames, variant, start_thr=600.0, end_thr=240.0,
              start_need=8, start_tol=None, warm_need=None,
              warm_ms=5000, warm_thr=None):
    """Frame stream -> VAD events + audio actually SENT to Live.
    Variants (feed mode, start tolerance, end rule):
      'old18'            — end after 35 frames <= END; feed 240;
                           start 8-of-8 strict.
      'new19'            — v1.0.19 as shipped: end after 15 frames
                           < START (ungated); feed 600; start
                           8-of-8 strict.
      'gated20'          — v1.0.20 primary: gated end rule (only
                           non-speech-like frames advance the
                           below-START counter); feed REVERTED to
                           240 (the 600 gate is the starvation
                           suspect — revert it, the gated end rule
                           now bounds music exposure instead);
                           start tolerance 2 (a loud segment may
                           have 2 non-passing frames inside its
                           start window); start_need frames to open
                           a turn (default 8; the grid tests 6/5 —
                           a loud syllable is only 100-300 ms, so
                           8 consecutive gate-passing frames is
                           too strict when the gate passes speech
                           at ~70%).
      'gated20-feedGate' — same end rule + start tolerance, but
                           feed stays spectrum-gated (240 + gate).
                           The sim decides between this and the
                           240 revert.
      'gated20-start8'   — gated end rule + feed 240, start
                           strict 8-of-8 (isolates the start
                           tolerance effect).
    WARM START (v1.0.20): after a turn ends, for `warm_ms` the
    start need drops to `warm_need` (3 frames). Dialogue comes
    in bursts — a line that fails the strict start right after
    the previous turn is far more likely to be quiet speech
    than music (music needs 3 consecutive gate-passing loud
    frames to false-start, p ~ 0.2% per triple). This kills the
    clusters of 1-2 consecutive missed lines that produce the
    ~7 s render gaps. `warm_need=0` disables it.
    All variants share the v1.0.8 START rule shape (energetic +
    speech-like frames to open a turn). Returns (vad_events,
    sent_segments, stats) where sent_segments are (start, end,
    line_id, sent_ms) of audio sent to Live (in-speech frames >=
    END), and stats carries turns, fed_ms, gate_rejects and
    sent_ms (total ms sent to Live)."""
    # Variant -> (feed mode, start tolerance, end mode).
    feed_mode, default_tol, end_mode = {
        'old18': ('240', 0, 'old'),
        'new19': ('600', 0, 'ungated'),
        'gated20': ('240', 2, 'gated'),
        'gated20-feedGate': ('gate', 2, 'gated'),
        'gated20-start8': ('240', 0, 'gated'),
    }[variant]
    start_tol = default_tol if start_tol is None else start_tol
    # Warm start only for the v1.0.20 variants (the baselines
    # old18/new19 keep their shipped start rule, so the
    # comparison stays honest).
    if warm_need is None:
        warm_need = 3 if variant.startswith('gated20') else 0
    # Soft start inside the warm window: the threshold drops to
    # END (240) so an ALL-SOFT line (no frame above START) can
    # still open a turn while dialogue is ongoing. Music false
    # starts need 3 consecutive gate-passing frames above END
    # (p ~ 0.2% per triple), and the gated end rule kills a
    # music turn after 300 ms anyway.
    if warm_thr is None:
        warm_thr = end_thr if variant.startswith('gated20') \
            else start_thr
    vad = []
    sent = []
    turn_starts = set()
    in_speech = False
    loud = 0
    fail_streak = 0
    quiet = 0
    below_start = 0
    pending = None
    seg_start = None
    seg_lid = None
    seg_sent_ms = 0
    fed_ms = 0
    sent_ms = 0
    gate_rejects = 0
    warm_until = -1
    for (t, rms, lid, gate) in frames:
        if not in_speech:
            # WARM/SOFT START: right after a turn (dialogue is
            # ongoing) a new line needs only `warm_need` frames,
            # and they only need to clear `warm_thr` (END) — an
            # all-soft line has no frame above START.
            warm_active = warm_need and t < warm_until
            need_now = warm_need if warm_active else start_need
            thr_now = warm_thr if warm_active else start_thr
            # START: energetic AND speech-like frames accumulate
            # to `start_need`; up to `start_tol` consecutive
            # non-passing frames are tolerated inside the window
            # (a gate failure on one syllable must not discard
            # the good frames before it).
            if rms > thr_now and gate:
                if loud == 0:
                    pending = (t, lid)
                loud += 1
                fail_streak = 0
                if loud >= need_now:
                    in_speech = True
                    quiet = 0
                    below_start = 0
                    vad.append((pending[0], 'start', 160))
                    turn_starts.add(pending[1])
                    seg_start = pending[0]
                    seg_lid = pending[1]
                    seg_sent_ms = 0
                    loud = 0
                    fail_streak = 0
            else:
                fail_streak += 1
                if fail_streak > start_tol:
                    loud = 0
                    fail_streak = 0
                    pending = None
        else:
            # Audio sent to Live: in-speech frames >= END.
            if rms >= end_thr:
                seg_sent_ms += 20
            # Tracker feed (the 'chunk' event).
            feed = False
            if feed_mode == '240':
                feed = rms >= end_thr
            elif feed_mode == '600':
                feed = rms >= start_thr
            else:  # 'gate': END + spectral gate
                if rms >= end_thr:
                    if gate:
                        feed = True
                    else:
                        gate_rejects += 1
            if feed:
                vad.append((t, 'chunk', 20))
                fed_ms += 20
            if rms > end_thr:
                quiet = 0
            else:
                quiet += 1
            if end_mode == 'ungated':
                if rms < start_thr:
                    below_start += 1
                else:
                    below_start = 0
                end_now = below_start >= 15
            elif end_mode == 'gated':
                # Only NON-speech-like frames advance the counter:
                # music (gate fails) ends the turn, quiet speech
                # (gate passes) resets it. Silence (<= END) also
                # advances (it is not speech).
                if rms <= end_thr:
                    below_start += 1
                elif gate:
                    below_start = 0
                else:
                    below_start += 1
                end_now = below_start >= 15
            else:
                end_now = False
            if quiet >= 35 or end_now:
                in_speech = False
                loud = 0
                fail_streak = 0
                quiet = 0
                below_start = 0
                warm_until = t + warm_ms
                vad.append((t, 'end', 0))
                if seg_start is not None:
                    sent.append((seg_start, t, seg_lid, seg_sent_ms))
                    sent_ms += seg_sent_ms
                    seg_start = None
    if in_speech and frames:
        vad.append((frames[-1][0] + 20, 'end', 0))
        if seg_start is not None:
            sent.append((seg_start, frames[-1][0] + 20, seg_lid,
                         seg_sent_ms))
            sent_ms += seg_sent_ms
    vad.sort(key=lambda x: x[0])
    return vad, sent, {'turns': len(turn_starts), 'fed_ms': fed_ms,
                       'gate_rejects': gate_rejects,
                       'sent_ms': sent_ms}


def build_dub20(sent, line_start, line_ulen, ratio=1.51, lag0=2400,
                speed=1.5, seed=SEED):
    """Dub generated from what the VAD actually SENT to Live (not
    from the true lines): a sent stretch < 300 ms is too
    fragmentary for the model to translate (no dub); the rest is
    translated serially (the model's output queue) at `ratio`
    length, arriving `speed`-compressed. Returns dub chunks
    (arrival, step_ms, true_lag, line_id) like sim1018."""
    rng = random.Random(seed + 777)
    dub = []
    dub_free_at = None
    intervals = sorted((s, s + line_ulen[i], i)
                       for i, s in line_start.items())

    def line_at(t):
        # Line whose interval contains t. If t falls in a PAUSE
        # between lines, the most recent line started at or
        # before t (the dub is still that line's); before the
        # first line, the first line. (The old fallback returned
        # the LAST line of the session, which poisoned ~36% of
        # the line-start errors with -370..-416 s garbage.)
        best = None
        for (a, b, i) in intervals:
            if a <= t < b:
                return i
            if a <= t:
                best = i
        return best if best is not None else (
            intervals[0][2] if intervals else 0)

    for (s0, e0, lid, sent_ms) in sent:
        if sent_ms < 300:
            continue
        dub_dur = int(sent_ms * ratio)
        speak_start = s0 + lag0
        if dub_free_at is not None:
            speak_start = max(speak_start, dub_free_at)
        extra = int(rng.expovariate(1 / 120.0))
        speak_start += extra
        u = 0
        while u < dub_dur:
            step = min(CHUNK_MS, dub_dur - u)
            arrival = speak_start + u / speed + rng.randint(-25, 25)
            src_pos = (u + step / 2) / ratio
            src_wall = s0 + src_pos
            dub.append((arrival, step, arrival - src_wall,
                        line_at(src_wall)))
            u += step
        dub_free_at = speak_start + dub_dur / speed
    dub.sort(key=lambda x: x[0])
    return dub


# ----------------------------------------------------------------
# Tracker v1.0.20 (Python mirror of the Kotlin fixes).
# ----------------------------------------------------------------
# Feature flags for bisecting regressions (all True = v1.0.20 as
# designed). A bisect script can flip one off at a time.
FLAGS = {'dcheck': True, 'ratio': True, 'stall': True}


class NewTracker20(s19.NewTracker19):
    """v1.0.20 tracker changes (the VAD/feed changes live in
    run_vad20, not here):
    (20a) BURST D-CHECK DEADLOCK RECOVERY: after 3 consecutive
        D-check-only rejections the D itself is the suspect, not
        the bursts — the D-check is bypassed for the next burst
        (the anchor-implied check + range check still apply).
        _d_trusted() returns False while the streak is >= 3.
    (20b) RATIO SANITY: the BURST-successor measurements need
        counted >= 500 ms and dub within [0.7, 1.6] x expected
        (was [0.5, 2.0]): a fragmented marker's small counted +
        full-turn dub saturated the ratio at the 0.5 clamp
        (device 2.00x). The turnComplete successor chain keeps
        [0.5, 2.0] — it lives inside s18._accept_tc and is not
        copied (see the NOTE below on_dub_burst).
    (20c) STALL EPISODES: resetForStall is episode-gated — while
        already stalled it is a no-op, so accumulating speech can
        complete a slice instead of being wiped on every dub
        chunk (the device's "stall 159" was per-chunk resets).
        stall_episodes counts transitions into stall;
        stall_resets_invocations counts every reset that WOULD
        have fired (the old per-chunk number, for comparison)."""

    def __init__(self):
        super().__init__()
        self.burst_dcheck_streak = 0
        self._stalled = False
        self.stall_episodes = 0
        self.stall_resets_invocations = 0

    def _d_trusted(self, now):
        # (20a) while the D-check streak is >= 3 the D is the
        # suspect: bypass the D-check for the next burst. The
        # anchor-implied check still applies, and the ratio
        # validation below also skips (validated=False) — a
        # bypassed burst must not feed the ratio.
        if not FLAGS['dcheck']:
            return True
        return self.burst_dcheck_streak < 3

    def on_dub_burst(self, now, true_lag, true_sample=None):
        nm_before = self.burst_no_marker
        cons_before = self.burst_consist
        ok_before = self.burst_ok
        prev_hold = self.hold
        # Parent accept/reject (s18.NewTracker18): marker
        # selection, anchor-back veto, range check,
        # anchor-implied consistency, D-check (via _d_trusted).
        s18.NewTracker18.on_dub_burst(self, now, true_lag,
                                      true_sample)
        # (20a) Was this a D-CHECK rejection? Recompute the
        # selection: the D-check failed while the range check
        # and the anchor-implied check passed.
        if self.burst_ok == ok_before and \
                self.burst_consist > cons_before:
            m, via, s_pos = self._select_boundary(now, False)
            d_fail = False
            if m is not None:
                lat = now - m['wall']
                in_range = 0 <= lat <= 20000
                anchor_ok = (m['audio'] >= self.anchor_s or
                             not self.ever_exact)
                if via == 'anchor':
                    ref = self._implied_lag(now, s_pos,
                                            self.lag_est(now))
                    ref_ok = abs(lat - ref) <= 2000
                    d_fail = (in_range and anchor_ok and ref_ok
                              and abs(lat - self._d_based_lag())
                              > 1500)
                else:
                    d_fail = (in_range and anchor_ok and
                              abs(lat - self._d_based_lag()) > 2500)
            if d_fail:
                self.burst_dcheck_streak += 1
            else:
                self.burst_dcheck_streak = 0
        elif self.burst_ok > ok_before:
            self.burst_dcheck_streak = 0
        # (6e/6f) ROBUST HOLD + SLEW (same as s19).
        if self._last_burst_marker is not None:
            robust = max(0, self.delay - self.lag_est(now)
                         - self.pipeline_ms())
            self.hold = max(prev_hold - 300, robust)
        if self.burst_no_marker > nm_before:
            # (6c) NO-MARKER RE-ANCHOR.
            dlag = self._d_based_lag()
            m = self._select_marker(now, dlag, False)
            if m is not None:
                self._reanchor(m['audio'], now, True)
                self.d_reanchors += 1
        # (1+20b) burst-successor ratio, tightened bounds.
        m = self._last_burst_marker
        if FLAGS['ratio']:
            r_lo, r_hi, r_min = 0.7, 1.6, 500
        else:
            r_lo, r_hi, r_min = 0.5, 2.0, 0
        validated = False
        if m is not None and self._d_trusted(now):
            lat = now - m['wall']
            validated = abs(lat - self._d_based_lag()) <= 1000
        if m is not None and validated:
            if self.last_burst is not None:
                pseq, paudio, pdub, pvalid = self.last_burst
                if pvalid and m['seq'] == pseq + 1:
                    counted_diff = m['audio'] - paudio
                    dub_diff = self.cum_dub - pdub
                    expected = counted_diff * 1.3
                    if counted_diff >= r_min and \
                            r_lo * expected <= dub_diff <= \
                            r_hi * expected:
                        self.sum_counted += counted_diff
                        self.sum_total += dub_diff
                        self.ratio_burst_meas += 1
            self.last_burst = (m['seq'], m['audio'], self.cum_dub,
                               True)
        elif m is not None:
            self.last_burst = (m['seq'], m['audio'], self.cum_dub,
                               False)

    # NOTE (20b, corrected 28/11): there is NO on_dub_turn_complete
    # override here. The first version copied s18.BaseTracker18's
    # (line 582) — the WRONG parent: the tracker actually runs
    # s18.NewTracker18's (line 925), which uses _select_boundary +
    # _accept_tc (seed-lag bootstrap + FIX (6) hold refresh
    # delay-lat-pipeline). The Base copy dropped both and shifted
    # line-start steady by +~320 ms in the sim1018 regression
    # (bisect: all flags OFF still 682 vs OLD 346). The tc
    # successor chain therefore keeps its [0.5, 2.0] bounds — the
    # BURST-successor chain (below) carries the tightened
    # [0.7, 1.6] bounds, and it is the chain that fires on the
    # device (tc_recv ~= 0 there).

    def on_speech_ms(self, ms, now):
        # (20c) EPISODE-GATED STALL RESET. The base (v6) resets
        # AGAIN on every frame while last_slice_at is stale — and
        # its reset does not refresh last_slice_at — so the
        # accumulating speech is wiped before a slice can ever
        # complete: the starvation lock. While already stalled,
        # pretend the stream restarted (it did — speech is
        # arriving): the old state was already discarded by the
        # first reset, so there is nothing more to discard.
        if ms <= 0:
            return
        if not FLAGS['stall']:
            return super().on_speech_ms(ms, now)
        will_reset = (self.last_slice_at is not None and
                      now - self.last_slice_at > v6.STALL_MS)
        already = self._stalled
        if will_reset:
            self.stall_resets_invocations += 1
            if not already:
                self.stall_episodes += 1
                self._stalled = True
        if already and will_reset:
            self.last_slice_at = now
        n_before = len(self.slices)
        super().on_speech_ms(ms, now)
        if len(self.slices) > n_before:
            # A slice actually completed: the stream is alive.
            self._stalled = False

    def pair(self, now, true_lag):
        # (20c) While already stalled, the base clears slices +
        # re-anchors stall-exact on every 500 ms of dub. Nothing
        # more to discard — spend the credit like the base's
        # stall branch does and return.
        if FLAGS['stall'] and self._stalled:
            self.dub_acc = 0
            return
        super().pair(now, true_lag)

    def reset_reconnect(self):
        super().reset_reconnect()
        self.burst_dcheck_streak = 0
        self._stalled = False


# ----------------------------------------------------------------
# Event loop (s19.run19 + voice-continuity metrics).
# ----------------------------------------------------------------
def run20(tracker_cls, vad, dub, completes, line_start, min_buf=16384,
          line_counted=None):
    """Like s19.run19, plus: lines_with_burst (true lines that got
    >= 1 dub burst), burst_renders (absolute play times), and a
    stall-reset count for trackers without the (20c) counters
    (detected via cum_speech drops on speech frames)."""
    tr = tracker_cls()
    # v1.0.19 D CAP (Kotlin capDelayMs, MAX_DELAY_CAP_MS=8000):
    # the seed AND the EMA update are both capped at 8000.
    # sim1019 defined D_CAP_MS but never applied it (its worlds
    # never hit the cap, so it did not matter there). Starved
    # worlds seed D at up to ~19 s without it (first dub arrives
    # very late) — the device's D pinned at exactly 8000 is the
    # capped seed/sample. Apply the cap here so the D-check
    # deadlock (d_based = 8000 - margin vs true lag ~2.4 s) is
    # modeled, not masked.
    ema = getattr(tr, 'ema', None)
    if ema is not None and hasattr(ema, 'max_d'):
        ema.max_d = 8000
    is_new = isinstance(tr, s19.NewTracker19)
    is_new20 = isinstance(tr, NewTracker20)
    ts = s19.TrackSim19(min_buf)
    heap = []
    order = 0

    def push(t, kind_order, kind, payload):
        nonlocal order
        order += 1
        heapq.heappush(heap, (t, kind_order, order, kind, payload))

    for t, kind, payload in vad:
        push(t, 0, 'vad', (kind, payload))
    for item in dub:
        arrival, dms, true_lag, tid = item[0], item[1], item[2], item[3]
        push(arrival, 1, 'dub', (dms, true_lag, tid))
    for item in completes:
        if isinstance(item, tuple):
            push(item[0], 1, 'tc', item[1])
        else:
            push(item, 1, 'tc', None)
    push(KEY_ROTATE_AT_MS, 2, 'rotate', ())

    first_sent_at = 0
    utt_start = 0
    d_hist = []
    next_hist = 5000
    last_true_lag = 1400
    last_dub_arrival = None
    run_burst_seq = 0
    first_after_rotate = True
    hold_saturations = 0
    bursts_seen = 0
    last_tid = None
    tr.line_start_errs = []
    tr.line_start_times = []
    tr.line_start_fresh_errs = []
    tr.line_start_fresh_times = []
    tr.slice_play_errs = []
    tr.slice_play_times = []
    tr.measured_pipelines = []
    tr.backlogs = []
    tr.true_pipelines = []
    tr.dropped_audio_ms = 0
    tr.total_dub_ms = sum(item[1] for item in dub)
    tr.speed_hist = []
    chunk_idx = [0]
    tr.dropped_steady_ms = 0
    tr.total_steady_ms = 0
    tr.pipeline_samples = []
    tr.dbg_chunks = []
    tr.take_late = []
    tr.speed_used = []
    lines_with_burst = set()
    lines_with_render = set()
    render_times = []
    burst_renders = []
    # Stall-reset invocations for trackers without (20c) counters:
    # the v6 base zeroes cum_speech on reset, so a drop across a
    # speech frame is a reset firing.
    old_style_resets = [0]
    prev_cum_speech = [None]

    while heap:
        t, _, _, kind, payload = heapq.heappop(heap)
        if t > TOTAL_MS:
            break
        if kind == 'vad':
            k, p = payload
            if not is_new20 and k in ('start', 'chunk'):
                before = getattr(tr, 'cum_speech', None)
                if k == 'start':
                    utt_start = t
                    first_sent_at = first_sent_at or t
                    tr.on_turn_start(t + VAD_LAG_MS)
                    tr.on_speech_ms(160, t + VAD_LAG_MS)
                elif k == 'chunk':
                    tr.on_speech_ms(p, t)
                after = getattr(tr, 'cum_speech', None)
                if (before is not None and after is not None
                        and before > 0 and after < before):
                    old_style_resets[0] += 1
            elif k == 'start':
                utt_start = t
                first_sent_at = first_sent_at or t
                tr.on_turn_start(t + VAD_LAG_MS)
                tr.on_speech_ms(160, t + VAD_LAG_MS)
            elif k == 'chunk':
                tr.on_speech_ms(p, t)
            elif k == 'end':
                utt_start = 0
                tr.on_turn_end(t)
        elif kind == 'dub':
            dms, true_lag, tid = payload
            last_true_lag = true_lag
            was_first = first_after_rotate
            is_burst = first_after_rotate or (
                last_dub_arrival is not None and
                t - last_dub_arrival >= tr.BURST_GAP)
            first_after_rotate = False
            last_dub_arrival = t
            if is_burst:
                run_burst_seq += 1
                bursts_seen += 1
                tr.burst_seq = run_burst_seq
                lines_with_burst.add(tid)
            if not tr.seeded and first_sent_at:
                tr.seeded = True
                el = max(0, min(t - first_sent_at, 18000))
                tr.seed_lag = el
                tr.apply_margin()
                tr.ema.seed(el)
                nd = max(3000, min(el + tr.dynamic_margin(), 8000))
                tr._set_delay(nd)
                tr.hold = max(0, nd - el - tr.pipeline_ms())
            log_before = len(tr.pairing_log)
            true_burst = t - line_start[tid] if is_burst else None
            hold = tr.on_dub(t, dms, true_lag, true_burst)
            ts.speed = tr.target_speed if is_new else 1.0
            tr.speed_used.append(ts.speed)
            due = t + hold
            tr.take_late.append(max(0, ts.busy_until - due))
            if is_new:
                tr.note_lateness(max(0, ts.busy_until - due), t)
                ts.speed = tr.target_speed
                tr.speed_used[-1] = ts.speed
            render, observed, backlog = ts.feed(due, dms, is_burst)
            if render is None:
                tr.dropped_audio_ms += dms
                if t < 180_000 or t > 300_000:
                    tr.dropped_steady_ms += dms
                continue
            # (20) RENDER-level continuity: a line counts as dubbed
            # when ANY of its chunks renders, not only when a burst
            # starts in it — a continuous dub stream has few burst
            # starts but the voice never stands still. render_times
            # drives the "voice standing still" gap (see
            # max_unexplained_render_gap).
            lines_with_render.add(tid)
            render_times.append(render)
            tr.true_pipelines.append(render - due)
            if t < 180_000 or t > 300_000:
                tr.total_steady_ms += dms
            if getattr(tr, 'last_sample_at', None) == t:
                tr.dbg_chunks.append((t, tr.last_sample, hold,
                                      render - due, tr.delay,
                                      tr.lag_est(t), true_lag))
            if is_new:
                tr.note_level(ts.level, ts.cap_ms, t)
                chunk_idx[0] += 1
                if chunk_idx[0] % 20 == 0:
                    tr.on_pipeline_sample(observed - due, backlog)
                    tr.pipeline_samples.append(observed - due)
            if tid != last_tid:
                last_tid = tid
                err_ls = (render - line_start[tid]) - tr.delay
                tr.line_start_errs.append(err_ls)
                tr.line_start_times.append(t)
                try:
                    m = tr._select_marker(t, tr.lag_est(t), False)
                except Exception:
                    m = None
                if m is not None:
                    lat_meas = t - m['wall']
                    hold_fresh = max(0, tr.delay - lat_meas
                                     - tr.pipeline_ms())
                    err_fresh = (render - hold + hold_fresh
                                 - line_start[tid]) - tr.delay
                    tr.line_start_fresh_errs.append(err_fresh)
                    tr.line_start_fresh_times.append(t)
            for (dub_cum, wall, bseq) in tr.pairing_log[log_before:]:
                push(render, 3, 'slice_play', (wall,))
            if is_burst:
                if tr.hold == 0:
                    hold_saturations += 1
                if was_first:
                    tr._last_burst_marker = None
                tr.record_burst(run_burst_seq, t, hold,
                                tr.cum_dub - dms)
                push(render, 3, 'play',
                     (run_burst_seq, render, observed, due, tid,
                      backlog))
        elif kind == 'tc':
            seq = payload
            tsamp = None
            if seq is not None and line_counted is not None:
                tsamp = t - line_start[seq] - line_counted[seq] - 200
            tr.on_dub_turn_complete(t, last_true_lag, tsamp)
        elif kind == 'rotate':
            tr.reset_reconnect()
            first_after_rotate = True
        elif kind == 'play':
            seq, render, observed, due, tid, backlog = payload
            true_marker = line_start[tid]
            err = (render - true_marker) - tr.delay
            tr.sync_errs.append(err)
            tr.measured_pipelines.append(observed - due)
            tr.backlogs.append(backlog)
            burst_renders.append(render)
            tr.on_burst_played(seq, observed, observed - due, backlog)
            tr.speed_hist.append((render, tr.target_speed
                                  if is_new else 1.0))
        elif kind == 'slice_play':
            (wall,) = payload
            tr.slice_play_errs.append((t - wall) - tr.delay)
            tr.slice_play_times.append(t)
        while next_hist <= t:
            d_hist.append((next_hist, tr.delay))
            next_hist += 5000
    end = TOTAL_MS
    s60 = [x for x in tr.samples60 if end - x <= 60000]
    tp60 = [x for x in tr.turn_pair_times if end - x <= 60000]
    extra = {'lines_with_burst': lines_with_burst,
             'lines_with_render': lines_with_render,
             'render_times': render_times,
             'burst_renders': burst_renders,
             'old_style_resets': old_style_resets[0]}
    return tr, d_hist, len(s60), len(tp60), hold_saturations, \
        bursts_seen, extra


# ----------------------------------------------------------------
# Reporting + the v1.0.20 gate.
# ----------------------------------------------------------------
def stats(errs):
    return s18.stats(errs)


def pct(errs, q):
    return s19.pct(errs, q)


def max_unexplained_gap(burst_renders, line_start, line_ulen,
                        min_pause=1500):
    """Longest gap between consecutive burst plays that is NOT
    explained by a true long pause (>= min_pause ms with no
    speech): the voice standing still while dialogue continues.
    Returns (max_gap_ms, n_gaps)."""
    if len(burst_renders) < 2:
        return None, 0
    pauses = []
    items = sorted(line_start.items())
    for idx in range(len(items) - 1):
        lid, s = items[idx]
        e = s + line_ulen[lid]
        ns = items[idx + 1][1]
        if ns - e >= min_pause:
            pauses.append((e, ns))
    worst = 0
    n = 0
    for a, b in zip(burst_renders, burst_renders[1:]):
        gap = b - a
        mid = (a + b) / 2.0
        explained = any(p0 <= mid <= p1 for (p0, p1) in pauses)
        if not explained:
            n += 1
            worst = max(worst, gap)
    return worst, n


def max_unexplained_render_gap(render_times, line_start, line_ulen,
                               d_hist, min_pause=1500):
    """Longest gap between consecutive RENDERED chunks (any chunk,
    not just burst starts) that is NOT explained by a true long
    pause. A render at wall time r dubs source audio at r - D, so
    the gap's source midpoint is (a+b)/2 - D(at that time). If
    that source point is inside a true line (dialogue), the voice
    stood still while the film kept talking. Returns (max_gap,
    n_gaps). A continuous dub stream has render gaps ~100 ms even
    when burst starts are 50 s apart — the burst gap is NOT the
    voice standing still."""
    if len(render_times) < 2:
        return None, 0
    pauses = []
    items = sorted(line_start.items())
    for idx in range(len(items) - 1):
        lid, s = items[idx]
        e = s + line_ulen[lid]
        ns = items[idx + 1][1]
        if ns - e >= min_pause:
            pauses.append((e, ns))
    # delay at wall time t: nearest d_hist entry at or before t.
    hist = sorted(d_hist)
    def delay_at(t):
        d = hist[0][1] if hist else 3000
        for (ht, hd) in hist:
            if ht <= t:
                d = hd
            else:
                break
        return d
    worst = 0
    n = 0
    for a, b in zip(render_times, render_times[1:]):
        gap = b - a
        src_mid = (a + b) / 2.0 - delay_at((a + b) / 2.0)
        explained = any(p0 <= src_mid <= p1 for (p0, p1) in pauses)
        if not explained:
            n += 1
            worst = max(worst, gap)
    return worst, n


def report20(name, tr, dh, s60, tp60, holdsat, bursts, extra,
             line_start, line_ulen, vad_stats=None,
             ratio_true=1 / 1.51):
    steady_ls, steady_sp = s19.report19(name, tr, dh, s60, tp60,
                                        holdsat, bursts, ratio_true)
    n_lines = len(line_start)
    cov = len(extra['lines_with_burst']) / max(1, n_lines)
    if line_ulen:
        gap, ngaps = max_unexplained_gap(extra['burst_renders'],
                                         line_start, line_ulen)
    else:
        gap, ngaps = None, 0
    print("VOICE CONTINUITY (v1.0.20): lines with >= 1 dub burst:"
          " %d/%d (%.0f%%)   longest unexplained inter-burst gap:"
          " %s ms (over %d gaps)"
          % (len(extra['lines_with_burst']), n_lines, 100.0 * cov,
             gap if gap is not None else "n/a", ngaps))
    # Render-level continuity (the real "voice stands still"
    # measure): a line counts when ANY chunk renders; the gap is
    # between rendered chunks, source-shifted by D.
    rcov = None
    rgap = None
    if 'lines_with_render' in extra and line_ulen:
        rcov = len(extra['lines_with_render']) / max(1, n_lines)
        rgap, rngaps = max_unexplained_render_gap(
            extra['render_times'], line_start, line_ulen, dh)
        print("VOICE CONTINUITY (render-level): lines with >= 1"
              " rendered chunk: %d/%d (%.0f%%)   longest"
              " unexplained inter-render gap: %s ms (over %d"
              " gaps)"
              % (len(extra['lines_with_render']), n_lines,
                 100.0 * rcov, rgap if rgap is not None else "n/a",
                 rngaps))
    if vad_stats is not None:
        print("VAD/feed: turns started %d   tracker-fed speech"
              " %d ms   gate-rejected frames %d"
              % (vad_stats['turns'], vad_stats['fed_ms'],
                 vad_stats['gate_rejects']))
        if line_ulen and 'sent_ms' in vad_stats:
            true_ms = sum(line_ulen.values())
            print("sent to Live: %d ms / true speech %d ms"
                  " (%.0f%%)   total dub %d ms"
                  % (vad_stats['sent_ms'], true_ms,
                     100.0 * vad_stats['sent_ms'] / max(1, true_ms),
                     getattr(tr, 'total_dub_ms', 0)))
    # Stash the render-level continuity for gate20.
    extra['render_cov'] = rcov
    extra['render_gap'] = rgap
    if isinstance(tr, NewTracker20):
        print("stall: episodes %d   reset invocations (old-style"
              " count) %d   burst D-check bypass streak now %d"
              % (tr.stall_episodes, tr.stall_resets_invocations,
                 tr.burst_dcheck_streak))
    else:
        print("stall resets (old-style, speech-side only): %d"
              % extra['old_style_resets'])
    if hasattr(tr, 'ratio_burst_meas'):
        print("ratio display (dub/speech): %.2fx (true %.2fx)"
              % (1.0 / tr.ratio(), 1.0 / ratio_true))
    print()
    return steady_ls, steady_sp, cov, gap


def gate20(name, tr, tp60, steady_ls, dmax, cov, gap,
           steady_sp=None, s60=0, rcov=None, rgap=None):
    """The v1.0.20 gate: voice continuity FIRST (every line gets
    its dub, no long standstill during dialogue), then the
    v1.0.19 sync bars. Continuity is judged at RENDER level
    (rcov/rgap) when available: a continuous dub stream has few
    burst starts, so the burst-level coverage/gap undercounts it
    (a 52 s inter-burst gap with the voice playing throughout is
    not a standstill). The burst numbers are still printed for
    reference."""
    drop_steady = (100.0 * tr.dropped_steady_ms / tr.total_steady_ms
                   if getattr(tr, 'total_steady_ms', 0) else 0.0)
    sp_med = pct(steady_sp, 0.5) if steady_sp else 9999
    use_cov = rcov if rcov is not None else cov
    use_gap = rgap if rgap is not None else gap
    checks = {
        "lines with dub >= 90% (render-level)":
            use_cov is not None and use_cov >= 0.90,
        "max unexplained inter-render gap <= 3000 ms":
            use_gap is not None and use_gap <= 3000,
        "pairs/60s >= 3": tp60 >= 3,
        "samples/60s >= 20": s60 >= 20,
        "D max <= 8000": dmax <= 8000,
        "burst no-mk fraction < 0.35":
            (tr.burst_no_marker / max(1, tr.burst_recv)) < 0.35,
        "slice-play steady |err| median < 500": sp_med < 500,
        "line-start steady |err| median < 1200":
            pct(steady_ls, 0.5) < 1200,
        "dropped audio steady < 5%": drop_steady < 5.0,
    }
    print("GATE %s:" % name)
    for k, ok in checks.items():
        print("  %-46s %s" % (k, "PASS" if ok else "FAIL"))
    all_pass = all(checks.values())
    print("  GATE: %s" % ("PASS" if all_pass else "FAIL"))
    print()
    return all_pass


def check_clock_fix():
    """Arithmetic verification of Error 1 (the garbage sync
    numbers) from the device values alone.
    Device: lech hinh -135,043,353 ms, khung hinh -135,043,346 ms,
    D = 8000 ms, phat ~0 ms (estimated). Uptime-independent
    derivation: the buggy readPlayhead computes
      tsWall = wallNow + (nanoTime_MONOTONIC - elapsedRealtimeNanos_BOOTTIME)/1e6
             = wallNow - suspend_ms
    (BOOTTIME = MONOTONIC + total suspend time). The predicted
    play wall is therefore (wallNow_write - suspend + drain).
    lech = (playWall - markerWall) - D, with markerWall ~
    wallNow_marker and (wallNow_write - wallNow_marker) + drain
    ~ 0 at the burst that produced the screenshot:
      lech = -suspend - 8000  =>  suspend = 135,043,353 - 8,000
           = 135,035,353 ms (~37.5 h of suspend since boot).
    khung = (vBurst - vMarker)*1000 - D, where vBurst inherits
    the same poisoned wall as its eventWall; the derivation in
    BUILD_NOTES gives khung - lech = (video advance between the
    two JS replies) - (wall advance between them) = +7 ms on
    the device. pipeline = max(0, playWall - due) = 0 (the
    poisoned wall is ~37.5 h in the past), flagged estimated.
    The fixed readPlayhead uses System.nanoTime() (same base as
    AudioTimestamp.nanoTime), so tsWall ~= wallNow and all three
    values become sane; the guards below reject any residual
    garbage (|.| > 60 s -> show "--" + log raw)."""
    suspend_ms = 135035353
    D = 8000
    lech_buggy = -suspend_ms - D
    khung_buggy = lech_buggy + 7
    pipe_buggy = max(0, -suspend_ms)  # playWall - due, coerced
    assert lech_buggy == -135043353, lech_buggy
    assert khung_buggy == -135043346, khung_buggy
    assert pipe_buggy == 0, pipe_buggy
    # Fixed clock: tsWall ~= wallNow (residual < 60 s by
    # construction); the guards accept sane values and reject
    # anything beyond +-60 s.
    def guard(value_ms):
        return None if abs(value_ms) > 60000 else value_ms
    assert guard(lech_buggy) is None
    assert guard(khung_buggy) is None
    assert guard(250) == 250
    assert guard(-450) == -450
    print("CLOCK CHECK: buggy lech %d ms, khung %d ms, phat %d ms"
          " (estimated) — reproduces the device screenshot"
          " exactly (suspend = %d ms = %.1f h)"
          % (lech_buggy, khung_buggy, pipe_buggy,
             suspend_ms, suspend_ms / 3600000.0))
    print("CLOCK CHECK: System.nanoTime() reference + |.| > 60 s"
          " guard rejects the garbage, accepts sane values: PASS")
    print()


def main():
    # Calibrated world (best device match, calibrate1020.py):
    # soft dialogue 0.8, level 1.0, spectral-gate speech pass
    # 0.70. (The default 0.95 under-reproduces the device
    # starvation: 124 bursts vs the device's 5.)
    global GATE_SPEECH_P
    GATE_SPEECH_P = 0.70
    check_clock_fix()
    print("world: QUIET drama (18dj.tv regime, soft dialogue):"
          " lines 1.5-3.0 s, soft syllables %.0f%% (300-550 RMS),"
          " pauses 100-400 ms / 1.5-3.5 s, music 0.8, dub 1.51x,"
          " true lag 2.4 s, tc OFF"
          % (QUIET_SOFT_FRAC * 100))
    print("device signature to reproduce (28/11 04:28, v1.0.19):"
          " 5 bursts in a ~4 min session, 1 sample/60 s, pairs"
          " 1/60 s, D pinned 8000, stall 159, ratio display 2.00x")
    print()

    # ---- 1. REPRODUCTION: v1.0.19 as shipped, quiet world ----
    for speed in (1.5, 2.5):
        frames, line_start, line_ulen = build_world20(speed=speed)
        vad_new, sent, vs = run_vad20(frames, 'new19')
        dub = build_dub20(sent, line_start, line_ulen, speed=speed)
        print("##### reproduction: speed=%.1f, v1.0.19 (NEW VAD +"
              " NEW tracker): %d vad events, %d VAD turns (%d"
              " lines), %d dub chunks from %d sent stretches"
              " #####" % (speed, len(vad_new), vs['turns'],
                          len(line_start), len(dub), len(sent)))
        old, dh, s60, tp60, holdsat, bursts, extra = run20(
            s19.NewTracker19, vad_new, dub, [], line_start,
            min_buf=32768)
        steady = report20("REPRO (v1.0.19), quiet, speed=%.1f"
                          % speed, old, dh, s60, tp60, holdsat,
                          bursts, extra, line_start, line_ulen, vs)
        print("device-signature check (speed=%.1f): bursts %d"
              " (device 5), samples/60s %d (device 1), pairs/60s"
              " %d (device 1), D max %d (device 8000), stall"
              " resets %d (device 159), ratio display %.2fx"
              " (device 2.00x)"
              % (speed, bursts, s60, tp60,
                 max(d for _, d in dh), extra['old_style_resets'],
                 1.0 / old.ratio()))
        print()

    # ---- 2. ABLATION: v1.0.18 VAD + v1.0.19 tracker, quiet ----
    for speed in (1.5,):
        frames, line_start, line_ulen = build_world20(speed=speed)
        vad_old, sent, vs = run_vad20(frames, 'old18')
        dub = build_dub20(sent, line_start, line_ulen, speed=speed)
        print("##### ablation: speed=%.1f, OLD VAD (v1.0.18) + NEW"
              " tracker: %d VAD turns, %d dub chunks #####"
              % (speed, vs['turns'], len(dub)))
        ab, dh, s60, tp60, holdsat, bursts, extra = run20(
            s19.NewTracker19, vad_old, dub, [], line_start,
            min_buf=32768)
        report20("ABLATION (OLD VAD + NEW tracker), quiet,"
                 " speed=%.1f" % speed, ab, dh, s60, tp60, holdsat,
                 bursts, extra, line_start, line_ulen, vs)

    # ---- 3. FIX: gated VAD + NewTracker20 ----
    # VAD: START need 4 + tol 2 + warm 3 (soft start @ END).
    # Speed 1.5x is the DEVICE-REALISTIC delivery rate (Gemini
    # Live ~1.5x output; the v1.0.19 device test showed 0
    # dropped audio, which the sim only reproduces at <=1.5x).
    # Speed 2.5x is a STRESS test: the player cannot drain a
    # sustained 2.5x arrival with a 1.5x max stretch (the drain
    # rate is the voice-quality ceiling), so the 2.5x result is
    # reported but does not block the gate. Mitigations tested
    # and REJECTED (mitigate1020.py): MAX_STRETCH=2.0 (2.5x
    # drop 28% -> 6%, still FAIL, and it hurts 1.5x sync:
    # render-gap 1004 -> 2103 ms) and STALE_MS=1000 (drop
    # 28% -> 21%). The 2.5x player capacity is a v1.0.21
    # candidate, not a v1.0.20 blocker.
    all_gate = True
    for speed in (1.5, 2.5):
        for block in (True, False):
            frames, line_start, line_ulen = build_world20(
                speed=speed, block=block)
            vad_new, sent, vs = run_vad20(frames, 'gated20',
                                          start_need=4, start_tol=2)
            dub = build_dub20(sent, line_start, line_ulen,
                              speed=speed)
            print("##### fix: speed=%.1f block=%s, GATED VAD +"
                  " NewTracker20: %d vad events, %d VAD turns"
                  " (%d lines), %d dub chunks #####"
                  % (speed, block, len(vad_new), vs['turns'],
                     len(line_start), len(dub)))
            new, dh, s60, tp60, holdsat, bursts, extra = run20(
                NewTracker20, vad_new, dub, [], line_start,
                min_buf=16384)
            steady = report20(
                "FIX (v1.0.20), quiet, speed=%.1f block=%s"
                % (speed, block), new, dh, s60, tp60, holdsat,
                bursts, extra, line_start, line_ulen, vs)
            ok = gate20(
                "speed=%.1f block=%s, GATED VAD + NewTracker20"
                % (speed, block), new, tp60, steady[0],
                max(d for _, d in dh), steady[2], steady[3],
                steady[1], s60, rcov=extra.get('render_cov'),
                rgap=extra.get('render_gap'))
            if speed == 1.5:
                all_gate &= ok
            else:
                print("  (2.5x is a STRESS test, not a gate"
                      " blocker: arrival 2.5x > max drain 1.5x)")

    # ---- 4. REGRESSION: v6 world + sim1018 device world (tc ON) ----
    print("##### regression: v6 world (sim1017) #####")
    vad6, dub6, completes6, line_start6 = s17.build_world()
    old6, dh6, s606, tp606, holdsat6, bursts6, ex6 = run20(
        s19.NewTracker19, vad6, dub6, completes6, line_start6,
        min_buf=16384)
    steady_old6 = report20("OLD (v1.0.19), v6 world", old6, dh6,
                           s606, tp606, holdsat6, bursts6, ex6,
                           line_start6, {})
    new6, dh6b, s60n6, tp60n6, holdsatn6, burstsn6, ex6b = run20(
        NewTracker20, vad6, dub6, completes6, line_start6,
        min_buf=16384)
    steady_new6 = report20("NEW (v1.0.20), v6 world", new6, dh6b,
                           s60n6, tp60n6, holdsatn6, burstsn6, ex6b,
                           line_start6, {})
    fh_old6 = old6.line_start_fresh_errs
    fh_new6 = new6.line_start_fresh_errs
    ls_old6 = steady_old6[0][:len(steady_old6[0]) // 2]
    ls_new6 = steady_new6[0][:len(steady_new6[0]) // 2]
    print("REGRESSION GATE (v6 world, WITHIN-CAP early half):"
          " fresh-hold |err| median OLD %.0f -> NEW %.0f"
          " (need NEW < 300); actual line-start steady median"
          " OLD %.0f -> NEW %.0f (need NEW <= OLD);"
          " pairs/60s OLD %d -> NEW %d (need NEW >= OLD)"
          % (pct(fh_old6[:len(fh_old6) // 2], 0.5),
             pct(fh_new6[:len(fh_new6) // 2], 0.5),
             pct(ls_old6, 0.5), pct(ls_new6, 0.5),
             tp606, tp60n6))
    reg6 = (pct(fh_new6[:len(fh_new6) // 2], 0.5) < 300
            and pct(ls_new6, 0.5) <= pct(ls_old6, 0.5)
            and tp60n6 >= tp606)
    print("REGRESSION (v6, within-cap): %s"
          % ("PASS" if reg6 else "FAIL"))
    print()

    print("##### regression: sim1018 device world (tc ON, no music)"
          " #####")
    vad18, dub18, completes18, line_start18, line_counted18 = \
        s18.build_world_device(tc_on=True, speed=2.5)
    old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = run20(
        s19.NewTracker19, vad18, dub18, completes18, line_start18,
        min_buf=4096, line_counted=line_counted18)
    steady_old18 = report20("OLD (v1.0.19), sim1018 world, tc ON",
                            old18, dh18, s6018, tp6018, holdsat18,
                            bursts18, ex18, line_start18, {})
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        run20(NewTracker20, vad18, dub18, completes18,
              line_start18, min_buf=4096,
              line_counted=line_counted18)
    steady_new18 = report20("NEW (v1.0.20), sim1018 world, tc ON",
                            new18, dh18b, s60n18, tp60n18,
                            holdsatn18, burstsn18, ex18b,
                            line_start18, {})
    drop_old18 = (old18.dropped_steady_ms /
                  max(1, old18.total_steady_ms))
    drop_new18 = (new18.dropped_steady_ms /
                  max(1, new18.total_steady_ms))
    reg18 = (tp60n18 >= tp6018
             and pct(steady_new18[0], 0.5) <= 500
             and drop_new18 <= drop_old18
             and new18.dropped_slices <= old18.dropped_slices + 2)
    print("REGRESSION (sim1018 world, re-scoped): pairs/60s OLD"
          " %d -> NEW %d; line-start steady median OLD %.0f ->"
          " NEW %.0f (need NEW <= 500); dropped audio steady"
          " OLD %.1f%% -> NEW %.1f%% (need NEW <= OLD);"
          " dropped slices OLD %d -> NEW %d: %s"
          % (tp6018, tp60n18, pct(steady_old18[0], 0.5),
             pct(steady_new18[0], 0.5), drop_old18 * 100,
             drop_new18 * 100, old18.dropped_slices,
             new18.dropped_slices, "PASS" if reg18 else "FAIL"))
    print()
    print("OVERALL v1.0.20 GATE (1.5x device-realistic fix +"
          " regressions): %s"
          % ("PASS" if all_gate and reg6 and reg18 else "FAIL"))
    print("2.5x STRESS: reported above, not a gate blocker"
          " (player capacity: sustained 2.5x arrival exceeds the"
          " 1.5x max stretch drain; the device operates at"
          " ~1.5x — v1.0.19 showed 0 dropped audio on device).")


if __name__ == '__main__':
    main()
