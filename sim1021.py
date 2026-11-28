#!/usr/bin/env python3
"""sim1021.py — DubLive v1.0.21: marker scarcity + transcription-AI
anchor + recalibrated world + voice-lateness cap.

DEVICE EVIDENCE (v1.0.20, 28/11 07:07, 18dj.tv ep 3, video ENDED):
  D 3.1 s, last sample 0.8 s (7 s old), 5 samples/60 s, 1 pair/60 s,
  hold 1.0 s, VAD resting, dropped 16 slices/97 samples (small 55
  deferred / large 97), lệch hình +101 ms, phát ~340 ms (TB 363 ms,
  chờ 387 ms), giọng/gốc 1.36x, khung hình +97 ms, burst 75
  ok 28 no-mk 28 cons 19 fb 10, splits 4, markers 2 (newest 10.2 s
  old), split attempts 85/0, stall 4, stretch 1.25x, VAD turns
  2/60 s, fed speech 33300 ms/60 s, gate rejects 538 frames/60 s.
  Live errors: 0.
USER VERDICTS: 07:11 "Giọng hết đứng hình, môi chưa khớp giọng"
  (voice continuity FIXED, lips still off). 07:13 "Chậm hơn môi,
  tùy thời điểm lệnh khoảng vài s" — voice SLOWER than lips,
  offset VARIES, by a FEW SECONDS = DRIFT, not a fixed offset.
  At video end the queue drained, so the final +101 ms is small;
  mid-session, in dense dialogue, the voice fell seconds behind.

ROOT CAUSES (code-read, to be sim-proven here):
  (a) SPLIT IS DEAD BY CONSTRUCTION in v1.0.20: SPLIT_SILENCE_MS
      is 300 ms, but the gated VAD end rule ends a turn after 15
      non-speech-like below-START frames = 300 ms; a >=300 ms feed
      gap inside a turn ends the turn first. The split can only
      fire on feed-thread wall-clock hiccups. The 85 attempts are
      mostly turn-start heads: onSpeechMs checks the split BEFORE
      the current chunk's ms is added to the fresh marker
      (countedMs==0), and every turn start follows a >=300 ms gap.
      Fix (21c): count an attempt only when counted>0 (then
      attempts == splits by construction; the diagnostic becomes
      honest). Marker scarcity is NOT primarily a split problem —
      markers are plentiful (one per turn start); the problem is
      the anchor can't SELECT them (no-mk 28, cons 19).
  (b) ANCHOR DRIFT: tc never fires (tcRecv=0) so anchoring is
      bursts-only; burst accepts are sparse (28/75); the anchor
      coasts on the ratio between accepts; a ratio error of
      ~5-10% drifts the anchor; the drifted anchor either finds
      nothing (no-mk) or picks the WRONG neighbour marker, which
      the D-check then catches (cons 19). The D-based re-anchor
      exists but only runs on no-marker bursts. Fix (21a): D-based
      + monotone selection as PRIMARY for bursts (marker nearest
      now-dBasedLag among markers with seq > last accepted burst
      marker seq, residual <=1500 ms), then anchor-implied, then
      seed.
  (c) MID-TURN BURSTS: a burst for line 2/3/4 of a 16 s turn has
      lat = trueLag + in-turn offset (up to ~10 s) against the
      TURN marker — the D-check (|lat-dBased|<=1500) rejects it as
      cons. Fix (21b): the TRANSCRIPTION anchor gives LINE-level
      start walls: Gemini Live streams input transcription
      fragments DURING each line; the first fragment arrives at
      lineStart + fragDelay + asrLag; asrLag is calibrated per
      turn from (last fragment arrival - VAD turn end); so
      lineStart ~= firstFrag - asrLagEma - 500 ms. The tx anchor
      identifies the sentence by ORDER + TIME (the only
      cross-language alignment available without a translator:
      the k-th translated sentence is the k-th transcribed one,
      validated by the D-based lag window + monotone marker seq),
      then feeds a boundary sample against the LINE start and
      re-anchors to the interpolated speech position of that
      start. This works even when VAD turns stay giant.
  (d) VOICE LATENESS (the drift): ratio 1.36-1.51x vs stretch
      only 1.25x — in dense dialogue the voice falls behind
      faster than catch-up speed; backlog grows ~(speed-1.25)
      per second of dub; only quiet stretches let it catch up.
      Voice lag is bounded by (ratio - stretch), not by D.
      Fixes (21e, ablated): E1 level stretch cap 1.25 -> 1.5;
      E2 hold from the tx line latency (hold = D - latTx -
      pipeline for chunks after the tx anchor lands); E3
      staleness skip (a burst whose tx latency already exceeds
      D + 1500 ms is dropped — lost words vs lip-sync).
  (e) DIAGNOSTICS BLIND SPOT: lệch hình/khung hình only show
      the LAST burst-start error; the end-of-session photo hides
      mid-session drift. Fix (21d): track max/p90 of the sync
      error + per-burst END lateness (last slice-play error of
      each burst) and report them.

WORLD 21 (recalibrated to the device, NOT the old sim world):
  - 2 VAD turns/60 s, each ~16 s (continuous music fills the
    silences: music 80% of pauses, and music has a PER-PAUSE
    character — 50% "tonal" (level 300-800, gate pass 0.40),
    50% "atonal" (level 150-500, gate pass 0.05) — so gate
    behaviour clusters per pause and turns merge into ~16 s
    giants, exactly like the device.
  - Gate rejects ~538 frames/60 s (start-path + feed-path).
  - Ratio 1.51x true; generation speed fitted in {1.3,1.4,1.5}
    to reproduce the device signature (dropped slices ~16,
    drift of a few seconds).
  - tc OFF (device tcRecv=0).
  - The gate must pass ON THIS WORLD; the old sim1020 world is
    kept as a regression (pairs/samples must not fall below the
    v1.0.20 levels there), plus the v6 and sim1018 worlds.
"""
import heapq
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim1020 as s20
import sim1019 as s19
import sim1018 as s18
import sim1014_v6 as v6
from sim1020 import (CHUNK_MS, KEY_ROTATE_AT_MS, TOTAL_MS, VAD_LAG_MS,
                     SEED, max_unexplained_gap,
                     max_unexplained_render_gap, pct)

# --- world 21 constants (device-calibrated) ---
RATIO21 = 1.51
LAG021 = 4600  # 07:35 fresh restart: last sample 4.6 s, D 5.3 s
# — the TRUE first-response lag (Gemini Live + TTS + playout),
# not the 2.4 s assumed in sim1020. D seeds at ~5.3 s
# (4600 + margin) and pins at 8 s mid-session as the backlog
# grows (07:32 long session).
# Music: per-pause character (bimodal gate behaviour). Tonal
# passages pass the spectral gate for seconds at a time, so the
# gated VAD end rule never sees 15 consecutive non-speech-like
# frames and the turn merges across lines — the device's ~16 s
# turns. Atonal passages fail the gate and end turns quickly.
MUSIC_TONAL_P = 0.75  # 4 lines/turn ~14 s; giant-turn mode
# 0.85 gives ~6.7 lines/turn ~23 s (07:35: 2 turns/60 s,
# ~24.5 s per turn).
MUSIC_TONAL_LEVEL = (300.0, 800.0)
MUSIC_TONAL_GATE = 0.40
MUSIC_ATONAL_LEVEL = (150.0, 500.0)
MUSIC_ATONAL_GATE = 0.05
MUSIC_P21 = 0.8
# Transcription stream (Gemini Live input/output transcription):
# fragments stream DURING the line; first fragment ~300-700 ms
# into the line + asrLag; asrLag ~ N(600,200) ms per turn.
TX_ASR_LAG_MU = 600.0
TX_ASR_LAG_SD = 200.0
TX_FRAG_DELAY = (300, 700)      # first fragment delay into a line
TX_FRAG_STEP = (500, 900)       # between fragments of one line
TX_GAP_MS = 800                 # gap that closes a tx sentence
TX_FRAG_DELAY_MS = 500          # Kotlin constant: mean frag delay
TX_SKIP_P = 0.05                # line transcribed but not translated
TX_OUT_FRAG_DELAY = (0, 200)    # first out-fragment after burst
TX_OUT_FRAG_STEP = 150


def build_world21(ratio=RATIO21, speed=1.5, seed=SEED, block=False,
                  tonal_p=MUSIC_TONAL_P, soft_frac=0.8,
                  total_ms=TOTAL_MS):
    """Like build_world20, but music has a PER-PAUSE character
    (tonal/atonal) so gate behaviour clusters and VAD turns merge
    into ~16 s giants, and the 30 s block is OFF by default (the
    device session is dialogue, not a monologue). Returns
    (frames, line_start, line_ulen)."""
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
            gate = rng.random() < s20.GATE_SPEECH_P
            return rms * s20.LEVEL_SCALE * rng.uniform(0.9, 1.1), gate
        return fn

    def music_fn():
        tonal = rng.random() < tonal_p
        if tonal:
            level = rng.uniform(*MUSIC_TONAL_LEVEL)
            gp = MUSIC_TONAL_GATE
        else:
            level = rng.uniform(*MUSIC_ATONAL_LEVEL)
            gp = MUSIC_ATONAL_GATE

        def fn():
            gate = rng.random() < gp
            return level * s20.LEVEL_SCALE * rng.uniform(0.95, 1.05), gate
        return fn

    while t < total_ms:
        if block and 180_000 <= t < 210_000:
            ulen, pause = 30_000, 0
        else:
            ulen = rng.randint(1500, 3000)
            if rng.random() < 0.6:
                pause = rng.randint(100, 400)
            else:
                pause = rng.randint(1500, 3500)
        ulen = min(ulen, total_ms - t)
        if ulen <= 0:
            break
        line_start[line_id] = t
        line_ulen[line_id] = ulen
        add_frames(t, ulen, speech_fn(ulen), line_id)
        if pause > 0:
            if rng.random() < MUSIC_P21:
                add_frames(t + ulen, pause, music_fn(), -1)
            else:
                add_frames(t + ulen, pause,
                           lambda: (30.0, False), -1)
        line_id += 1
        t = t + ulen + pause
    frames.sort(key=lambda x: x[0])
    return frames, line_start, line_ulen


def run_vad21(frames, start_thr=600.0, end_thr=240.0,
              start_need=4, start_tol=2, warm_need=3,
              warm_ms=5000, warm_thr=None, total_ms=TOTAL_MS):
    """v1.0.20 VAD ('gated20' in run_vad20) + start-path gate
    rejects + turn records. The device's 'gate loại 538 khung/60 s'
    is dominated by START-path rejects: energetic frames that fail
    the spectral gate while no turn is open (music swells). The
    old sim only counted feedGate-mode rejects (0 in '240' mode).
    Returns (vad, sent, stats) where stats adds
    'start_gate_rejects' and 'turns60' (turns in the last 60 s)
    and 'fed60' (fed ms in the last 60 s)."""
    if warm_thr is None:
        warm_thr = end_thr
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
    start_gate_rejects = 0
    warm_until = -1
    turn_ends = []
    fed_events = []
    for (t, rms, lid, gate) in frames:
        if not in_speech:
            warm_active = warm_need and t < warm_until
            need_now = warm_need if warm_active else start_need
            thr_now = warm_thr if warm_active else start_thr
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
                # START-PATH gate reject: energetic but not
                # speech-like while no turn is open (music swell).
                if rms > thr_now and not gate:
                    start_gate_rejects += 1
                fail_streak += 1
                if fail_streak > start_tol:
                    loud = 0
                    fail_streak = 0
                    pending = None
        else:
            if rms >= end_thr:
                seg_sent_ms += 20
            feed = rms >= end_thr
            if feed:
                vad.append((t, 'chunk', 20))
                fed_ms += 20
                fed_events.append(t)
            else:
                # In-turn frame below END that fails the gate is
                # also a gate reject in the Kotlin service's
                # accounting (the spectral gate vetoes the frame).
                if not gate:
                    gate_rejects += 1
            if rms > end_thr:
                quiet = 0
            else:
                quiet += 1
            if rms <= end_thr:
                below_start += 1
            elif gate:
                below_start = 0
            else:
                below_start += 1
            end_now = below_start >= 15
            if quiet >= 35 or end_now:
                in_speech = False
                loud = 0
                fail_streak = 0
                quiet = 0
                below_start = 0
                warm_until = t + warm_ms
                vad.append((t, 'end', 0))
                turn_ends.append(t)
                if seg_start is not None:
                    sent.append((seg_start, t, seg_lid, seg_sent_ms))
                    sent_ms += seg_sent_ms
                    seg_start = None
    if in_speech and frames:
        vad.append((frames[-1][0] + 20, 'end', 0))
        turn_ends.append(frames[-1][0] + 20)
        if seg_start is not None:
            sent.append((seg_start, frames[-1][0] + 20, seg_lid,
                         seg_sent_ms))
            sent_ms += seg_sent_ms
    vad.sort(key=lambda x: x[0])
    # The 60 s window is [end-60s, end] (the old filter
    # `end - x <= 60000` also counted events AFTER end, so a
    # 600 s session reported 210 s of fed speech in "60 s").
    end = total_ms
    turns60 = len([x for x in turn_ends
                   if end - 60000 <= x <= end])
    fed60 = len([x for x in fed_events
                 if end - 60000 <= x <= end]) * 20
    return vad, sent, {'turns': len(turn_starts), 'fed_ms': fed_ms,
                       'gate_rejects': gate_rejects,
                       'start_gate_rejects': start_gate_rejects,
                       'sent_ms': sent_ms, 'turns60': turns60,
                       'fed60': fed60}


def _per_line_speech(frames, sent, line_start, line_ulen):
    """Fed SPEECH ms per line: frames in the line's interval whose
    lid is the line itself (real dialogue, not the background
    music pause that follows it), inside a VAD sent segment,
    with rms >= END (the feed gate). The fed stream inside a
    giant turn includes the music pauses between lines (sent
    134% of true speech in run3) — the model translates speech,
    not music, so the dub duration must use speech ms, or the
    serial model queue accumulates ~1.4 s of backlog and the
    true lag grows to ~6 s while the samples measure ~4.6 s
    (the voice ends up ~1.5 s late)."""
    fed = {lid: 0 for lid in line_start}
    # In-speech intervals from the sent segments.
    segs = sorted((s0, e0) for (s0, e0, lid, sms) in sent)

    def in_speech(t):
        for (a, b) in segs:
            if a <= t < b:
                return True
            if a > t:
                return False
        return False

    for (t, rms, lid, gate) in frames:
        if lid < 0 or rms < 240.0:
            continue
        if lid in fed and in_speech(t):
            fed[lid] += 20
    return fed


def build_dub21(frames, sent, line_start, line_ulen, ratio=RATIO21,
                lag0=LAG021, speed=1.5, seed=SEED, skip_p=TX_SKIP_P):
    """Dub generated PER LINE (not per VAD turn): the model speaks
    one sentence at a time, with a 200-400 ms inter-sentence
    pause — so bursts are per line even when the VAD merged the
    lines into one giant turn (device: 75 bursts with 16 s turns,
    258 in the long 07:32 session). A line is dubbed iff the VAD
    fed >=300 ms of SPEECH from it. 5% of lines are transcribed
    but NOT translated (the model stays silent) — returned as
    `skipped` (line ids); their input-tx fragments still arrive,
    which desyncs the tx source/translated counts. Returns
    (dub, skipped) where dub chunks are (arrival, step_ms,
    true_lag, line_id)."""
    rng = random.Random(seed + 777)
    fed = _per_line_speech(frames, sent, line_start, line_ulen)
    dub = []
    skipped = set()
    dub_free_at = None
    for lid in sorted(line_start):
        fms = fed.get(lid, 0)
        if fms < 300:
            continue
        if rng.random() < skip_p:
            skipped.add(lid)
            continue
        s0 = line_start[lid]
        dub_dur = int(fms * ratio)
        speak_start = s0 + lag0
        if dub_free_at is not None and speak_start <= dub_free_at:
            # Serialised: the model pauses 200-400 ms between
            # sentences (this gap is what splits bursts per
            # line; without it consecutive lines merge).
            speak_start = dub_free_at + rng.randint(200, 400)
        u = 0
        while u < dub_dur:
            step = min(CHUNK_MS, dub_dur - u)
            arrival = speak_start + u / speed + rng.randint(-25, 25)
            src_pos = (u + step / 2) / ratio
            src_wall = s0 + src_pos
            dub.append((arrival, step, arrival - src_wall, lid))
            u += step
        dub_free_at = speak_start + dub_dur / speed
    dub.sort(key=lambda x: x[0])
    return dub, skipped


def build_tx21(frames, sent, line_start, line_ulen, seed=SEED):
    """Input-transcription fragments PER LINE (streaming during
    the line). For each line with >=300 ms fed: 1-3 fragments,
    first at lineStart + U(300,700) + asrLag, later +U(500,900),
    asrLag ~ N(600,200) ms. 5% of lines produce no fragments
    (model did not understand). Lines the model transcribed but
    did not translate (build_dub21's `skipped`) DO produce
    fragments — that desync is what the tx anchor must survive.
    Returns (txin, txsrc): txin = [(arrival, turn_idx)] and
    txsrc = [(end_wall, turn_idx, start, end, sent_ms, lid)]
    turn records. The fragment's turn_idx is the VAD turn
    containing the line's start (for the asrLag calibration)."""
    rng = random.Random(seed + 31337)
    fed = _per_line_speech(frames, sent, line_start, line_ulen)

    def turn_of(t):
        for ti, (s0, e0, lid, sms) in enumerate(sent):
            if s0 <= t < e0:
                return ti
        return len(sent) - 1 if sent else 0

    txin = []
    txsrc = []
    for ti, (s0, e0, lid, sent_ms) in enumerate(sent):
        txsrc.append((e0, ti, s0, e0, sent_ms, lid))
    for lid in sorted(line_start):
        if fed.get(lid, 0) < 300:
            continue
        if rng.random() < TX_SKIP_P:
            continue
        ls = line_start[lid]
        le = ls + line_ulen[lid]
        asr_lag = max(200.0, min(1500.0,
                                 rng.gauss(TX_ASR_LAG_MU, TX_ASR_LAG_SD)))
        n_frag = rng.randint(1, 3)
        pos = ls + rng.randint(*TX_FRAG_DELAY)
        ti = turn_of(ls)
        for _ in range(n_frag):
            if pos > le + 200:
                break
            txin.append((int(pos + asr_lag), ti))
            pos += rng.randint(*TX_FRAG_STEP)
    txin.sort(key=lambda x: x[0])
    txsrc.sort(key=lambda x: x[0])
    return txin, txsrc


# ----------------------------------------------------------------
# Tracker v1.0.21 (Python mirror of the Kotlin fixes).
# ----------------------------------------------------------------
# Feature flags for ablation (all True = v1.0.21 as designed).
FLAGS21 = {'tx': True, 'tx_hold': True, 'stale_skip': True,
           'level_cap_15': True}

# D-based burst selection (21a): residual window (ms). The marker
# is picked as nearest (now - dBasedLag); 1500 ms is the same
# window the burst D-check uses, so a D-based pick can never fail
# the D-check for the wrong reason.
DBASED_RES_MS = 1500
# Tx anchor (21b): sentence must start within this window before
# the burst arrival; the tx latency must agree with the D-based
# lag within this window.
TX_WIN_MS = 10000
TX_DCHECK_MS = 2000
TX_RES_MS = 1200      # tx selection residual bound (21i)
TX_BOOT_WIN_MS = 6000  # tx D-check window while bootstrapping
# Tx anchor (21b): a burst whose tx latency already exceeds D +
# this is unrecoverable — drop its remaining audio (21e/E3).
STALE_BURST_MS = 1500


class NewTracker21(s20.NewTracker20):
    """v1.0.21 tracker changes (VAD/feed changes live in
    run_vad21, not here):
    (21a) D-BASED + MONOTONE burst selection PRIMARY: the
        anchor-implied path is self-referential when the anchor
        drifts (device: no-mk 28/75, cons 19/75) — the D-based lag
        is absolute. Marker nearest now-dBasedLag among markers
        with seq > last accepted marker seq, residual <=1500 ms;
        then anchor-implied, then seed.
    (21b) TRANSCRIPTION ANCHOR: Gemini Live streams input
        transcription fragments DURING each line; the first
        fragment arrives at lineStart + fragDelay + asrLag.
        asrLag is calibrated per short turn from (first post-end
        fragment arrival - turn end wall + 300 ms VAD end delay).
        Line start ~= firstFrag - asrLagEma - 500 ms. The anchor
        selects the tx sentence whose start is nearest
        (burstArrival - dBasedLag) — order + time, the only
        cross-language alignment available without a translator —
        then the VAD marker nearest that start, validated by the
        D-check and the monotone marker seq. It feeds a boundary
        sample against the LINE start (not the turn start) and
        re-anchors to the interpolated speech position of that
        start. Applied post-hoc when the first output-transcription
        fragment of a burst arrives (apply_tx_anchor).
    (21c) SPLIT-ATTEMPT COUNTING: count an attempt only when
        counted>0 (the Kotlin counted before the guard, so every
        turn start wasted one attempt: 85 attempts, 4 splits).
    (21d) Diagnostics: max/p90 sync error is computed in
        report21 from sync_errs / slice_play_errs.
    (21e) VOICE-LATENESS CAP: E1 level stretch cap 1.25 -> 1.5
        (LEVEL_CAP); E2 hold from the tx line latency for chunks
        after the tx anchor lands (TX_HOLD); E3 staleness skip
        (STALE_SKIP): a burst whose tx latency already exceeds
        D + 1500 ms has its remaining chunks dropped.
    """

    TX_ON = True
    LEVEL_CAP = 1.25
    TX_HOLD = False
    STALE_SKIP = False


    def __init__(self):
        super().__init__()
        self._last_accepted_seq = None
        self.d_based_sel = 0
        # (21c) split-attempt counting, both ways.
        self.split_attempts_old = 0
        self.split_attempts = 0
        # (21b) transcription anchor state.
        self.tx_src = {}           # turn_idx -> entry dict
        self._tx_pending_frags = {}  # turn_idx -> [arrivals]
        self.tx_groups = []        # tx sentences (open + closed)
        self._tx_last_frag_at = None
        self._tx_cur_group = None
        self.tx_asr_ema = 600.0
        self.tx_asr_samples = 0
        self._tx_calibrated_turns = set()
        self._tx_out_frags = {}    # burst_seq -> fragment count
        self.tx_ok = 0
        self.tx_no_mk = 0
        self.tx_cons = 0
        self.tx_agree = 0
        self.tx_groups_seen = 0
        self._tx_last_marker_seq = None
        self._tx_last_audio_pos = None
        self._tx_anchored_burst = None
        # (21h) reference lag: the MEDIAN of the accepted
        # boundary samples (tx + VAD burst + tc, most recent
        # 30). Independent of D (the samples are validated
        # against the reference, not against D) and immune to
        # the minority of +1-line errors that drag an EMA.
        # The seed is only the bootstrap (first 5 accepts).
        self._bnd_samples = []
        # (21j) bootstrap candidate pool: lat_tx of EVERY
        # in-window tx group per burst (see _ref_lag).
        self._seed_cand = []
        self.sel_lag_ema = None
        self._last_burst_arrival = None
        self._last_burst_seq = None
        self._last_burst_paired = False
        self._stale_bursts = set()

    # -- (21h) reference lag: median of accepted boundary
    # samples, independent of D --
    def _ref_lag(self):
        """Independent lag reference (21i). Once the tx anchor
        is active (>=5 boundary samples and tx_ok > 0): the
        centre of the DENSEST 1200 ms cluster of the accepted
        boundary samples — the correct samples cluster tightly
        around the true lag while the +1-line errors cluster
        ~3.4 s away, and the densest cluster wins even when
        the biased samples are the majority of the accepts
        (run9: median sat at ~8000 ms). Before that: the seed
        (bootstrap). On VAD-only worlds (tx_ok == 0) the
        parent's D-based lag — which is what works there."""
        if self.tx_ok > 0 and len(self._bnd_samples) >= 5:
            # (21n, reverts 21m) Combined pool: accepted samples
            # + bootstrap candidates. Dropping the frozen pool
            # made world21 worse (run13: inter-render gap +
            # combined unpaired FAIL). The growing-lag concern
            # is moot: on VAD-only worlds the tx anchor never
            # establishes, so _d_based_lag below never reads
            # this branch there.
            return self._cluster_centre(
                self._bnd_samples + self._seed_cand)
        # (21j) Bootstrap: the densest cluster of the
        # candidate pool (every in-window group per burst).
        # The correct candidate of each burst clusters at the
        # true lag; the wrong ones are spread out. This
        # recovers from a seed that is a whole line off
        # (run10: seed 7080 vs true lag 4600).
        if len(self._seed_cand) >= 8:
            return self._cluster_centre(self._seed_cand)
        if getattr(self, 'seed_lag', None) is not None:
            return self.seed_lag
        return super()._d_based_lag()

    @staticmethod
    def _cluster_centre(vals):
        """Centre (median) of the densest 1200 ms window."""
        s = sorted(vals)
        best_i, best_n = 0, 0
        for i, v in enumerate(s):
            n = sum(1 for u in s if v <= u <= v + 1200)
            if n > best_n:
                best_i, best_n = i, n
        cluster = [u for u in s
                   if s[best_i] <= u <= s[best_i] + 1200]
        return cluster[len(cluster) // 2]

    def _ref_established(self):
        return self.tx_ok > 0 and len(self._bnd_samples) >= 5

    def _d_based_lag(self):
        # (21n) The parent's VAD/tc/slice D-checks, ratio gate
        # and re-anchors read this. They get the cluster
        # reference ONLY once the tx anchor is established;
        # before that (including VAD-only worlds where the tx
        # anchor never accepts) they keep the parent's D-based
        # lag — the seed must never leak into the VAD path
        # (run9-13: sim1018 line-start 5379 vs OLD 1574 from
        # exactly that leak).
        # (21o) Switch on the FIRST tx accept: one accepted tx
        # sample proves the tx path works on this world. Before
        # that (including VAD-only worlds where it never
        # happens) keep the parent's D-based lag. The previous
        # switch (>=5 samples) left the VAD path on the
        # seed-inflated D-based reference through the whole
        # bootstrap, polluting _bnd_samples with ~7 s samples.
        if self.tx_ok > 0:
            return self._ref_lag()
        return super()._d_based_lag()

    def feed_sample(self, sample, now, true_lag, boundary=False,
                    **kw):
        # Record every accepted boundary sample for the median
        # reference (the parent still does the D-check/feeding).
        if boundary:
            self._bnd_samples.append(sample)
            if len(self._bnd_samples) > 30:
                self._bnd_samples.pop(0)
        return super().feed_sample(sample, now, true_lag,
                                   boundary=boundary, **kw)

    def _sel_lag(self):
        """Lag used to SELECT sentences/markers and to validate
        tx samples. The median of the accepted boundary samples
        (>=5; seed as bootstrap) — independent of D. D is
        deliberately NOT used here: D is fed by the samples
        these selections produce,
        so steering the selection by D is a feedback loop (D
        collapses -> later lines -> smaller samples -> D
        collapses further; the mirror image pins D at 8000)."""
        return self._ref_lag()

    def _sel_lag_update(self, lat):
        """Retired (21h): the median reference updates itself
        through feed_sample (every accepted boundary sample is
        recorded there). An EMA is the wrong accumulator here —
        it creeps toward a systematic bias (run5/run7); a
        median does not. Kept as a no-op for callers."""
        return

    # -- (21a) D-based + monotone selection --
    def _select_d_based(self, now, counted_correction):
        dlag = self._sel_lag()
        target = now - 200 - dlag if counted_correction else now - dlag
        best, best_res = None, None
        for m in self.markers:
            if counted_correction and (not m['done'] or
                                      m['counted'] <= 0):
                continue
            if (self._last_accepted_seq is not None and
                    m['seq'] <= self._last_accepted_seq):
                continue
            pos = m['wall'] + m['counted'] if counted_correction \
                else m['wall']
            res = abs(pos - target)
            if best_res is None or res < best_res:
                best, best_res = m, res
        if best is None or best_res > DBASED_RES_MS:
            return None
        return best

    def _select_boundary(self, now, counted_correction):
        # D-based first always (as in run3/run4). The part-6
        # anchor-first switch starved this path (0 picks in
        # run6): the tx anchor keeps last_boundary_at fresh, so
        # the anchor-implied path — which only knows turn starts
        # — kept selecting the wrong marker for mid-turn bursts.
        # The v6 world is protected by the _d_based_lag override
        # above (its target was the D-based value before).
        m = self._select_d_based(now, counted_correction)
        if m is not None:
            self.d_based_sel += 1
            return m, 'd-based', None
        return super()._select_boundary(now, counted_correction)

    def _note_accepted(self, seq):
        if seq is not None:
            if (self._last_accepted_seq is None or
                    seq > self._last_accepted_seq):
                self._last_accepted_seq = seq

    def on_dub_burst(self, now, true_lag, true_sample=None):
        ok_before = self.burst_ok
        super().on_dub_burst(now, true_lag, true_sample)
        self._last_burst_arrival = now
        self._last_burst_seq = getattr(self, 'burst_seq', None)
        self._last_burst_paired = self.burst_ok > ok_before
        if self._last_burst_marker is not None:
            self._note_accepted(self._last_burst_marker['seq'])

    def on_dub_turn_complete(self, now, true_lag, true_sample=None):
        super().on_dub_turn_complete(now, true_lag, true_sample)
        if self.last_tc is not None:
            self._note_accepted(self.last_tc[0])

    # -- (21c) split-attempt counting --
    def on_speech_ms(self, ms, now):
        # Count the attempt the Kotlin way (before the counted>0
        # guard) AND the fixed way (only counted>0). The parent
        # does the actual split with the counted>0 guard.
        if self.markers:
            last = self.markers[-1]
            if (not last['done'] and self._last_loud_at is not None
                    and now - self._last_loud_at >= 300):
                self.split_attempts_old += 1
                if last['counted'] > 0:
                    self.split_attempts += 1
        super().on_speech_ms(ms, now)

    # -- (21e/E1) level stretch cap --
    def note_level(self, level_ms, cap_ms, now):
        if cap_ms <= 0:
            return
        frac = level_ms / cap_ms
        if frac > 0.85:
            want = s19.MAX_STRETCH
        elif frac > 0.6:
            want = 1.20
        elif frac > 0.3:
            want = 1.10
        else:
            return
        # (21e/E1) the level-driven raise caps at LEVEL_CAP
        # (1.25 as shipped; 1.5 in the E1 variant).
        want = min(want, self.LEVEL_CAP)
        if want > self.target_speed:
            self.target_speed = want
            self.level_raise_at = now

    # -- (21b) transcription anchor --
    def note_tx_source(self, turn_idx, start, end, sent_ms, lid):
        entry = {'start': start, 'end': end, 'sent_ms': sent_ms,
                 'lid': lid, 'first_frag': None, 'last_frag': None,
                 'n_frag': 0}
        self.tx_src[turn_idx] = entry
        for arr in self._tx_pending_frags.pop(turn_idx, []):
            self._attach_tx_frag(entry, turn_idx, arr)

    def _attach_tx_frag(self, entry, turn_idx, arrival):
        if entry['first_frag'] is None:
            entry['first_frag'] = arrival
        entry['last_frag'] = arrival
        entry['n_frag'] += 1
        # asrLag calibration: the first fragment arriving AFTER
        # the turn ended is (approximately) the line's last
        # fragment: sample = arrival - turnEnd + 300 ms (the VAD
        # end rule fires ~300 ms after the last speech frame).
        # Only for short turns (a merged turn's end wall belongs
        # to its LAST line, not to this fragment's line).
        if (arrival > entry['end']
                and entry['end'] - entry['start'] <= 5000
                and turn_idx not in self._tx_calibrated_turns):
            sample = arrival - entry['end'] + 300
            if 0 <= sample <= 3000:
                self.tx_asr_ema = (0.7 * self.tx_asr_ema
                                   + 0.3 * sample)
                self.tx_asr_samples += 1
                self._tx_calibrated_turns.add(turn_idx)

    def note_tx_in_fragment(self, turn_idx, arrival):
        # Grouping: a new tx sentence starts when a fragment
        # arrives >=800 ms after the previous fragment (any
        # turn). Groups merge across short-pause lines — the
        # anchor's time-based selection handles that.
        if (self._tx_last_frag_at is not None and
                arrival - self._tx_last_frag_at >= TX_GAP_MS):
            self._close_tx_group()
        self._tx_last_frag_at = arrival
        if self._tx_cur_group is None:
            self._tx_cur_group = {'first': arrival, 'last': arrival,
                                  'turn_idx': turn_idx, 'closed': False}
            self.tx_groups.append(self._tx_cur_group)
            self.tx_groups_seen += 1
        else:
            self._tx_cur_group['last'] = arrival
        entry = self.tx_src.get(turn_idx)
        if entry is None:
            self._tx_pending_frags.setdefault(turn_idx, []).append(
                arrival)
        else:
            self._attach_tx_frag(entry, turn_idx, arrival)

    def _close_tx_group(self):
        if self._tx_cur_group is not None:
            self._tx_cur_group['closed'] = True
            self._tx_cur_group = None

    def note_tx_out_fragment(self, burst_seq, arrival):
        self._tx_out_frags[burst_seq] = \
            self._tx_out_frags.get(burst_seq, 0) + 1

    def _audio_at(self, wall):
        """Inverse of _wall_at: counted-speech position of a wall
        time, interpolated from the slices. None when no slice
        covers it."""
        for (sw, sa, ew, ea) in self.slices:
            if sw <= wall <= ew:
                span_w = max(1, ew - sw)
                span_a = max(1, ea - sa)
                return sa + (wall - sw) * span_a // span_w
        return None

    def _tx_sentence_start(self, group):
        """Estimated start wall of a tx sentence: the first
        fragment arrived at lineStart + fragDelay + asrLag."""
        return (group['first'] - self.tx_asr_ema
                - TX_FRAG_DELAY_MS)

    def apply_tx_anchor(self, now, burst_seq, true_lag,
                        true_sample=None):
        """Post-hoc tx anchor for a burst, called when the first
        output-transcription fragment of that burst arrives (the
        burst is confirmed to be a real translation). `now` is
        the fragment arrival; the burst arrival wall is the
        stored _last_burst_arrival.
        MARKER-FREE (v1.0.21, from the 07:35 device photo): with
        VAD turns ~24 s long, the only VAD markers are turn
        starts ~24 s apart — a mid-turn burst has NO marker
        within 2.5 s, so requiring a VAD marker makes the anchor
        fail exactly when it is needed most (device: burst 2,
        both no-mk, markers 2, newest 11.4 s old). The sample is
        therefore computed against the LINE start estimated from
        the transcription (firstFrag - asrLagEma - 500 ms), and
        the re-anchor uses the interpolated speech position of
        that start (_audio_at); a VAD marker is only a fallback
        for the re-anchor when no slice covers the start. The
        monotone check is on the audio position, not the marker
        seq. A merged tx group (several lines) can still give a
        +1-line error — the D-check and the EMA median absorb
        it (the anchor must be right more often than wrong)."""
        if not self.TX_ON:
            return
        if (self._last_burst_seq != burst_seq
                or self._last_burst_arrival is None):
            return
        # One application per burst: a burst has 1-2 output-tx
        # fragments, and each one calls this method — without
        # the guard a rescued burst counted 2 pairs / 2 tx_ok.
        if self._tx_anchored_burst == burst_seq:
            return
        burst_arrival = self._last_burst_arrival
        dlag = self._sel_lag()
        target = burst_arrival - dlag
        # Select the tx sentence whose estimated start is nearest
        # the selection-lag target (seed lag + slow tx EMA —
        # INDEPENDENT of D, see _sel_lag), inside the window.
        # (21j) During bootstrap, record EVERY in-window
        # candidate's lat into the pool: each burst contributes
        # one correct candidate (its own line) and a few wrong
        # ones spread out, so the densest cluster is the true
        # lag even when the seed is a whole line off.
        best, best_res = None, None
        for g in self.tx_groups:
            # (21k) CLOSED groups only: the next line's group is
            # still OPEN when this burst arrives (its first
            # fragment just arrived), while this burst's own line
            # group closed ~1.5 s ago. An open group is a future
            # line, never this burst's line.
            if not g.get('closed'):
                continue
            start_est = self._tx_sentence_start(g)
            if not (burst_arrival - TX_WIN_MS <= start_est
                    <= burst_arrival - 300):
                continue
            if not self._ref_established():
                self._seed_cand.append(burst_arrival - start_est)
                if len(self._seed_cand) > 200:
                    self._seed_cand.pop(0)
            res = abs(start_est - target)
            if best_res is None or res < best_res:
                best, best_res = g, res
        if best is None:
            self.tx_no_mk += 1
            return
        # (21i) Residual bound once the reference is
        # established: a mid-turn burst in a merged group is
        # ~3.4 s from its group's start — it cannot be
        # attributed to that start, so reject it instead of
        # feeding a +1-line sample. OFF during bootstrap (the
        # target is wrong while the seed is wrong).
        if (self._ref_established()
                and best_res is not None and best_res > TX_RES_MS):
            self.tx_no_mk += 1
            return
        start_est = self._tx_sentence_start(best)
        lat_tx = burst_arrival - start_est
        if not (0 <= lat_tx <= 20000):
            self.tx_cons += 1
            return
        # (21i) Wide window while bootstrapping (both clusters
        # get in, the clustering sorts them out); tight once
        # the cluster reference is established.
        tx_win = (TX_DCHECK_MS if self._ref_established()
                  else TX_BOOT_WIN_MS)
        if abs(lat_tx - dlag) > tx_win:
            self.tx_cons += 1
            return
        # The VAD path already paired this burst to the marker
        # whose wall is the same line start: agreement, no double
        # sample.
        if (self._last_burst_marker is not None
                and abs(self._last_burst_marker['wall']
                        - start_est) <= 500):
            self.tx_agree += 1
            self._tx_anchored_burst = burst_seq
            return
        # Re-anchor position: interpolated speech position of the
        # line start; nearest VAD marker only as a fallback.
        audio_pos = self._audio_at(start_est)
        m_best, m_res = None, None
        for m in self.markers:
            res = abs(m['wall'] - start_est)
            if m_res is None or res < m_res:
                m_best, m_res = m, res
        if audio_pos is None:
            if m_best is None or m_res > 2500:
                self.tx_no_mk += 1
                return
            audio_pos = m_best['audio']
        # Monotone in speech position (not marker seq): the
        # anchor must not jump backwards.
        if (self._tx_last_audio_pos is not None
                and audio_pos < self._tx_last_audio_pos):
            self.tx_cons += 1
            return
        self.last_boundary_sample = lat_tx
        self.last_boundary_at = now
        self.feed_sample(lat_tx, now, true_lag, boundary=True,
                         true_sample=true_sample)
        self._reanchor(audio_pos, now, True)
        if not self._last_burst_paired:
            self.turn_pairs += 1
            self.turn_pair_times.append(now)
        self.tx_ok += 1
        # The selection lag tracks the true lag: an accepted tx
        # sample is a (slow) update to it (tight window, see
        # _sel_lag_update).
        self._sel_lag_update(lat_tx)
        self._tx_anchored_burst = burst_seq
        self._tx_last_audio_pos = audio_pos
        if m_best is not None and m_res <= 2500:
            self._tx_last_marker_seq = m_best['seq']
            self._last_burst_marker = m_best
            self._note_accepted(m_best['seq'])
        self.burst_dcheck_streak = 0
        # (21e/E2) hold from the tx line latency for the chunks
        # still to come in this burst.
        if self.TX_HOLD:
            self.hold = max(0, self.delay - lat_tx
                            - self.pipeline_ms())
        # (21e/E3) unrecoverable: the burst is already more than
        # D + 1500 ms late — drop its remaining audio.
        if self.STALE_SKIP and lat_tx > self.delay + STALE_BURST_MS:
            self._stale_bursts.add(burst_seq)

    def is_burst_stale(self, burst_seq):
        return burst_seq in self._stale_bursts


# ----------------------------------------------------------------
# Event loop (run20 + tx events + per-burst end lateness + E3
# staleness skip).
# ----------------------------------------------------------------
def run21(tracker_cls, vad, dub, completes, line_start, txin, txsrc,
          min_buf=16384, line_counted=None, tx_on=True,
          level_cap=1.25, tx_hold=False, stale_skip=False,
          total_ms=TOTAL_MS):
    """Like s20.run20, plus: tx source/fragment events (precomputed
    from build_tx21) and lazy tx-out fragment events scheduled at
    each burst detection; slice_play events carry the burst seq so
    report21 can compute the per-burst END lateness (the last
    slice-play error of each burst — the voice lateness the user
    actually hears, not the burst-start error); E3 stale bursts
    have their remaining chunks dropped before the player."""
    tr = tracker_cls()
    if hasattr(tr, 'TX_ON'):
        tr.TX_ON = tx_on
        tr.LEVEL_CAP = level_cap
        tr.TX_HOLD = tx_hold
        tr.STALE_SKIP = stale_skip
    ema = getattr(tr, 'ema', None)
    if ema is not None and hasattr(ema, 'max_d'):
        ema.max_d = 8000
    is_new = isinstance(tr, s19.NewTracker19)
    is_new20 = isinstance(tr, s20.NewTracker20)
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
    # Tx source entries (finalised at the VAD turn's end wall)
    # and input fragments (streaming during the line).
    for (end_wall, ti, s0, e0, sent_ms, lid) in txsrc:
        push(end_wall, 1, 'txsrc', (ti, s0, e0, sent_ms, lid))
    for (arr, ti) in txin:
        push(arr, 1, 'txin', ti)
    push(KEY_ROTATE_AT_MS, 2, 'rotate', ())

    rng_tx = random.Random(SEED + 4242)
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
    tr.slice_play_bseq = []
    tr.sync_times = []
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
    old_style_resets = [0]
    burst_info = {}   # burst_seq -> (true_lag, true_sample)

    while heap:
        t, _, _, kind, payload = heapq.heappop(heap)
        if t > total_ms:
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
        elif kind == 'txsrc':
            ti, s0, e0, sent_ms, lid = payload
            if hasattr(tr, 'note_tx_source'):
                tr.note_tx_source(ti, s0, e0, sent_ms, lid)
        elif kind == 'txin':
            ti = payload
            if hasattr(tr, 'note_tx_in_fragment'):
                tr.note_tx_in_fragment(ti, t)
        elif kind == 'txout':
            bseq, true_lag, true_sample = payload
            if hasattr(tr, 'note_tx_out_fragment'):
                tr.note_tx_out_fragment(bseq, t)
            if hasattr(tr, 'apply_tx_anchor'):
                tr.apply_tx_anchor(t, bseq, true_lag, true_sample)
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
                true_burst = t - line_start[tid]
                burst_info[run_burst_seq] = (true_lag, true_burst)
                # Output-transcription fragments for this burst:
                # the first arrives 0-200 ms after the burst
                # starts (the model speaks and transcribes at the
                # same time), a second +150 ms later.
                n_out = rng_tx.randint(1, 2)
                base = t + rng_tx.randint(*TX_OUT_FRAG_DELAY)
                for j in range(n_out):
                    push(base + j * TX_OUT_FRAG_STEP, 1, 'txout',
                         (run_burst_seq, true_lag, true_burst))
            if not tr.seeded and first_sent_at:
                tr.seeded = True
                # (21g) Seed anchored to the FIRST MARKER wall
                # (first turn start ~= first line start — the
                # same reference every sample uses), NOT to the
                # first sent audio: the first sent audio can be
                # MUSIC before the first line (world21: first
                # line ~2.5 s in -> seed 7080 ms vs true lag
                # ~4600 ms; v6: seed 66 ms vs true lag ~1400 ms).
                # A wrong seed poisons every (21f) reference.
                ref = first_sent_at
                if tr.markers:
                    ref = tr.markers[0]['wall']
                el = max(0, min(t - ref, 18000))
                tr.seed_lag = el
                tr.apply_margin()
                tr.ema.seed(el)
                nd = max(3000, min(el + tr.dynamic_margin(), 8000))
                tr._set_delay(nd)
                tr.hold = max(0, nd - el - tr.pipeline_ms())
            log_before = len(tr.pairing_log)
            true_burst = t - line_start[tid] if is_burst else None
            hold = tr.on_dub(t, dms, true_lag, true_burst)
            # (21e/E3) stale burst: the tracker still advances
            # (cum_dub is what the model sent) but the chunk never
            # reaches the player — it is dropped like an overdue
            # hold-queue block.
            if hasattr(tr, 'is_burst_stale') \
                    and tr.is_burst_stale(run_burst_seq):
                tr.dropped_audio_ms += dms
                if t < 180_000 or t > 300_000:
                    tr.dropped_steady_ms += dms
                continue
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
                # (21l) Slice-play error vs the chunk's own LINE
                # start, not the turn marker: a mid-turn slice
                # is ~2.5 s after the turn start, so the old
                # metric's p90 was the in-turn offset, not
                # lateness.
                push(render, 3, 'slice_play',
                     (line_start[tid], bseq))
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
            tr.sync_times.append(t)
            tr.measured_pipelines.append(observed - due)
            tr.backlogs.append(backlog)
            burst_renders.append(render)
            tr.on_burst_played(seq, observed, observed - due, backlog)
            tr.speed_hist.append((render, tr.target_speed
                                  if is_new else 1.0))
        elif kind == 'slice_play':
            wall, bseq = payload
            tr.slice_play_errs.append((t - wall) - tr.delay)
            tr.slice_play_times.append(t)
            tr.slice_play_bseq.append(bseq)
        while next_hist <= t:
            d_hist.append((next_hist, tr.delay))
            next_hist += 5000
    end = total_ms
    s60 = [x for x in tr.samples60 if end - x <= 60000]
    tp60 = [x for x in tr.turn_pair_times if end - x <= 60000]
    tp_first60 = [x for x in tr.turn_pair_times if x <= 60000]
    d_pinned = [d for _, d in d_hist if d >= 7900]
    extra = {'lines_with_burst': lines_with_burst,
             'tp_first60': len(tp_first60),
             'first_sync_at': (min(tr.sync_times)
                             if tr.sync_times else None),
             'd_pinned_frac': (len(d_pinned) / max(1, len(d_hist))),
             'lines_with_render': lines_with_render,
             'render_times': render_times,
             'burst_renders': burst_renders,
             'old_style_resets': old_style_resets[0]}
    return tr, d_hist, len(s60), len(tp60), hold_saturations, \
        bursts_seen, extra


# ----------------------------------------------------------------
# Reporting + the v1.0.21 gate.
# ----------------------------------------------------------------
def _p90_abs(errs):
    if not errs:
        return None
    a = sorted(abs(x) for x in errs)
    return a[int(len(a) * 0.9)]


def _max_abs(errs):
    if not errs:
        return None
    return max(abs(x) for x in errs)


def per_burst_end_lateness(tr):
    """Per-burst END lateness: the last slice-play error of each
    burst (the voice lateness at the END of the sentence — what
    the user hears as drift). Returns the list of end errors."""
    last_by_burst = {}
    for (tt, e, bseq) in zip(tr.slice_play_times,
                            tr.slice_play_errs,
                            tr.slice_play_bseq):
        last_by_burst[bseq] = e
    return list(last_by_burst.values())


def report21(name, tr, dh, s60, tp60, holdsat, bursts, extra,
             line_start, line_ulen, vad_stats=None,
             ratio_true=1 / RATIO21):
    steady_ls, steady_sp, cov, gap = s20.report20(
        name, tr, dh, s60, tp60, holdsat, bursts, extra,
        line_start, line_ulen, vad_stats, ratio_true)
    # (21d) max/p90 sync error — the end-of-session photo hides
    # mid-session drift; these numbers do not.
    sync_all = tr.sync_errs
    end_late = per_burst_end_lateness(tr)
    print("SYNC ERROR max/p90 (21d): burst-start |err| max %s ms,"
          " p90 %s ms (n=%d); slice-play steady |err| max %s ms,"
          " p90 %s ms (n=%d); per-burst END lateness |err| max"
          " %s ms, p90 %s ms (n=%d bursts)"
          % (_max_abs(sync_all), _p90_abs(sync_all), len(sync_all),
             _max_abs(steady_sp), _p90_abs(steady_sp),
             len(steady_sp), _max_abs(end_late),
             _p90_abs(end_late), len(end_late)))
    if isinstance(tr, NewTracker21):
        print("D-based selection: %d picks (21a)   split attempts:"
              " Kotlin-count %d vs fixed-count %d (== splits %d)"
              " (21c)"
              % (tr.d_based_sel, tr.split_attempts_old,
                 tr.split_attempts, tr.split_markers))
        print("TRANSCRIPTION ANCHOR (21b): sentences seen %d"
              "   ok %d / no-mk %d / cons %d / agree %d   asrLag"
              " EMA %.0f ms (calibrated from %d short turns)"
              % (tr.tx_groups_seen, tr.tx_ok, tr.tx_no_mk,
                 tr.tx_cons, tr.tx_agree, tr.tx_asr_ema,
                 tr.tx_asr_samples))
        if tr.STALE_SKIP:
            print("staleness skip (21e/E3): %d bursts dropped"
                  % len(tr._stale_bursts))
    if vad_stats is not None:
        print("VAD/feed: turns started %d (%d in last 60 s)"
              "   tracker-fed speech %d ms (%d ms in last 60 s)"
              "   gate rejects: start-path %d + in-turn %d"
              % (vad_stats['turns'], vad_stats.get('turns60', -1),
                 vad_stats['fed_ms'], vad_stats.get('fed60', -1),
                 vad_stats.get('start_gate_rejects', -1),
                 vad_stats.get('gate_rejects', -1)))
    print("FIRST 60 s (restart gate): pairs %d, first sync error"
          " at %s ms, D pinned (>=7.9 s) %.0f%% of the session"
          % (extra.get('tp_first60', -1),
             extra.get('first_sync_at', 'never'),
             100.0 * extra.get('d_pinned_frac', -1)))
    print()
    return steady_ls, steady_sp, cov, gap, end_late


def gate21(name, tr, tp60, steady_ls, dmax, cov, gap,
           steady_sp=None, s60=0, rcov=None, rgap=None,
           end_late=None, tp_first60=None, first_sync_at=None,
           d_pinned_frac=None, bursts_seen=0):
    """The v1.0.21 gate: the v1.0.20 bars PLUS the measurement
    bars (pairs/samples must rise on the CALIBRATED world) PLUS
    the voice-lateness bars (the user's 'chậm hơn môi vài giây'
    verdict: the voice must not fall >~1 s behind mid-session)."""
    drop_steady = (100.0 * tr.dropped_steady_ms / tr.total_steady_ms
                   if getattr(tr, 'total_steady_ms', 0) else 0.0)
    sp_med = pct(steady_sp, 0.5) if steady_sp else 9999
    sp_p90 = _p90_abs(steady_sp) if steady_sp else 9999
    sp_max = _max_abs(steady_sp) if steady_sp else 9999
    el_p90 = _p90_abs(end_late) if end_late else 9999
    use_cov = rcov if rcov is not None else cov
    use_gap = rgap if rgap is not None else gap
    checks = {
        "lines with dub >= 90% (render-level)":
            use_cov is not None and use_cov >= 0.90,
        "max unexplained inter-render gap <= 3000 ms":
            use_gap is not None and use_gap <= 3000,
        "pairs/60s >= 5 (device was 1)": tp60 >= 5,
        "samples/60s >= 40 (device was 5)": s60 >= 40,
        "D max <= 8000": dmax <= 8000,
        "combined burst unpaired fraction < 0.35":
            (1.0 - tr.turn_pairs / max(1, bursts_seen)) < 0.35,
        "slice-play steady |err| median < 500": sp_med < 500,
        "slice-play steady |err| p90 < 1000 (lateness)":
            sp_p90 is not None and sp_p90 < 1000,
        "slice-play steady |err| max < 2000 (lateness)":
            sp_max is not None and sp_max < 2000,
        "per-burst END lateness p90 < 1000":
            el_p90 is not None and el_p90 < 1000,
        "line-start steady |err| median < 1200":
            pct(steady_ls, 0.5) < 1200,
        "dropped audio steady < 5%": drop_steady < 5.0,
        "pairs in first 60 s > 0 (restart)":
            tp_first60 is not None and tp_first60 > 0,
        "sync measurable in first 60 s (not '--')":
            first_sync_at is not None and first_sync_at <= 60000,
        "D pinned < 20% of a 10-min session":
            d_pinned_frac is not None and d_pinned_frac < 0.20,
    }
    print("GATE %s:" % name)
    for k, ok in checks.items():
        print("  %-46s %s" % (k, "PASS" if ok else "FAIL"))
    all_pass = all(checks.values())
    print("  GATE: %s" % ("PASS" if all_pass else "FAIL"))
    print()
    return all_pass


def kotlin_split_attempts(vad):
    """Kotlin split-attempt count from the VAD feed events: every
    fed chunk whose gap from the previous fed chunk is >=300 ms
    is one attempt (the Kotlin checks the split BEFORE the
    counted>0 guard, so turn starts waste one each)."""
    times = sorted(t for (t, k, p) in vad if k in ('start', 'chunk'))
    att = 0
    prev = None
    for t in times:
        if prev is not None and t - prev >= 300:
            att += 1
        prev = t
    return att


def device_signature_check(tag, tr, dh, s60, tp60, bursts, vs,
                           extra, vad, n_lines):
    """Compare a REPRO run against the three device photos.
    Session totals are compared where the photo gives them
    (bursts, splits, drops, stall); the last-60s window is
    compared where the photo is mid-session (07:32) and flagged
    as wind-down where the photo is end-of-video (07:07)."""
    sess_s = 600.0
    gate_rate = (vs.get('start_gate_rejects', 0)
                 + vs.get('gate_rejects', 0)) * 60.0 / sess_s
    print("device-signature check %s:" % tag)
    print("  bursts %d (07:07: 75, 07:32 long: 258) over %d"
          " lines; burst ok/no-mk/cons/fb %d/%d/%d/%d"
          " (07:07: 28/28/19/10; 07:32: 69/132/57/38)"
          % (bursts, n_lines, tr.burst_ok, tr.burst_no_marker,
             tr.burst_consist, tr.burst_fallback))
    print("  samples/60s %d (07:07: 5, 07:32: 43, 07:35: 89),"
          " pairs/60s %d (07:07: 1, 07:32: 2, 07:35: 0),"
          " pairs first 60 s %d (07:35: 0)"
          % (s60, tp60, extra.get('tp_first60', -1)))
    print("  D last %d ms (07:07: 3100 drained, 07:32: 8000"
          " pinned, 07:35: 5300 fresh), D pinned %.0f%% of"
          " session, last sample %.1f s"
          % (tr.delay, 100.0 * extra.get('d_pinned_frac', -1),
             tr.last_sample / 1000.0))
    print("  splits %d (07:07: 4, 07:32: 8), split attempts"
          " (Kotlin-count) %d (07:07: 85, 07:32: 200),"
          " dropped slices %d (07:07: 16, 07:32: 40), dropped"
          " samples %d (97 both), stall episodes %d (07:07: 4,"
          " 07:32: 16)"
          % (tr.split_markers, kotlin_split_attempts(vad),
             tr.dropped_slices, tr.dropped,
             getattr(tr, 'stall_episodes', -1)))
    print("  turns/60s %d (07:07: 2, 07:32: 11, 07:35: 2),"
          " fed/60s %d ms (07:07: 33300, 07:32: 37760, 07:35:"
          " 49120), gate rejects/60s ~%.0f (07:07: 538, 07:32:"
          " 662, 07:35: 123), ratio display %.2fx (07:07:"
          " 1.36x, 07:32: 1.37x, 07:35: 1.11x)"
          % (vs.get('turns60', -1), vs.get('fed60', -1),
             gate_rate, 1.0 / tr.ratio()))
    print()


def main():
    # Same speech-gate calibration as sim1020 (the 0.95 default
    # under-reproduces the device starvation).
    s20.GATE_SPEECH_P = 0.70
    s20.check_clock_fix()
    print("world21 v2 (device-calibrated, 28/11 07:07 + 07:32 +"
          " 07:35 v1.0.20): 10-MIN sessions, lines 1.5-3.0 s,"
          " soft syllables 80%, pauses 100-400 ms / 1.5-3.5 s,"
          " music 0.8 (tonal: level 300-800, gate 0.40; atonal:"
          " 150-500, gate 0.05), dub 1.51x, true lag 4.6 s"
          " (07:35 fresh restart: last sample 4.6 s, D 5.3 s),"
          " tc OFF, no 30 s block, model inter-sentence pause"
          " 200-400 ms")
    print("device signatures to reproduce:")
    print("  07:07 end-of-video: bursts 75, samples/60s 5,"
          " pairs/60s 1, D 3.1 s (drained), burst 28/28/19/10,"
          " splits 4, attempts 85, turns/60s 2, fed 33300,"
          " gate 538, stall 4, dropped slices 16, ratio 1.36x")
    print("  07:32 mid-session long watch: D 8.0 s PINNED,"
          " samples/60s 43, pairs/60s 2, lech +930 ms, khung"
          " +1086 ms, dropped 40 slices, burst 258:"
          " 69/132/57/38, splits 8, attempts 200, turns/60s"
          " 11, fed 37760, gate 662, stall 16, ratio 1.37x")
    print("  07:35 fresh restart: D 5.3 s, samples/60s 89,"
          " pairs/60s 0, lech/khung '--', dropped 0, burst 2:"
          " 0/2, splits 0, attempts 1, turns/60s 2 (~24.5 s"
          " each), fed 49120, gate 123, stall 0, ratio 1.11x")
    print()

    # ---- 1. REPRODUCTION: v1.0.20 as shipped, world21 v2,
    # 10-min sessions. Fit (speed, tonal) to the device
    # signature: burst rate, no-mk fraction, pairs/60s, D
    # pinned fraction, splits/attempts. ----
    LONG_MS = 600_000
    best = None
    repro_worlds = {}
    for speed in (1.4, 1.5):
        for tonal in (0.75, 0.85):
            frames, line_start, line_ulen = build_world21(
                speed=speed, tonal_p=tonal, total_ms=LONG_MS)
            vad21, sent, vs = run_vad21(frames, start_need=4,
                                        start_tol=2,
                                        total_ms=LONG_MS)
            dub, skipped = build_dub21(frames, sent, line_start,
                                       line_ulen, speed=speed)
            txin, txsrc = build_tx21(frames, sent, line_start, line_ulen)
            print("##### reproduction: speed=%.1f tonal=%.2f,"
                  " v1.0.20: %d vad events, %d VAD turns (%d"
                  " lines), %d dub chunks from %d sent stretches"
                  " #####" % (speed, tonal, len(vad21),
                              vs['turns'], len(line_start),
                              len(dub), len(sent)))
            old, dh, s60, tp60, holdsat, bursts, extra = run21(
                s20.NewTracker20, vad21, dub, [], line_start,
                txin, txsrc, min_buf=16384, total_ms=LONG_MS)
            steady = report21(
                "REPRO (v1.0.20), world21 v2, speed=%.1f"
                " tonal=%.2f" % (speed, tonal), old, dh, s60,
                tp60, holdsat, bursts, extra, line_start,
                line_ulen, vs)
            device_signature_check(
                "speed=%.1f tonal=%.2f" % (speed, tonal), old, dh,
                s60, tp60, bursts, vs, extra, vad21,
                len(line_start))
            # Fit score: burst rate per line (device ~0.7),
            # no-mk fraction (device 0.37-0.51), pairs/60s
            # (device 0-2), D pinned fraction (device high
            # mid-session), splits per session (4-8).
            burst_rate = bursts / max(1, len(line_start))
            nomk_frac = (old.burst_no_marker
                         / max(1, old.burst_recv))
            score = (abs(burst_rate - 0.7) * 10.0
                     + abs(nomk_frac - 0.45) * 10.0
                     + abs(tp60 - 1) + abs(s60 - 43) / 20.0
                     + abs(extra.get('d_pinned_frac', 0) - 0.5)
                     * 5.0)
            repro_worlds[(speed, tonal)] = (
                frames, line_start, line_ulen, vad21, sent, vs,
                dub, skipped, txin, txsrc)
            if best is None or score < best[0]:
                best = (score, speed, tonal)
    score, speed, tonal = best
    print("SPEED/TONAL FIT: best device match at speed=%.1f"
          " tonal=%.2f (score %.1f)" % (speed, tonal, score))
    print()
    (frames, line_start, line_ulen, vad21, sent, vs, dub, skipped,
     txin, txsrc) = repro_worlds[(speed, tonal)]

    # ---- 2. ABLATION + FIX on the calibrated 10-min world. ----
    print("##### fix world: %d VAD turns (%d lines), %d dub"
          " chunks, %d sent stretches, %d skipped (model silent)"
          " #####" % (vs['turns'], len(line_start), len(dub),
                      len(sent), len(skipped)))
    print()
    all_gate = True
    variants = [
        ("V21a only (D-based+monotone, tx OFF, cap 1.25)",
         dict(tx_on=False, level_cap=1.25, tx_hold=False,
              stale_skip=False)),
        ("V21a+tx (tx anchor ON, cap 1.25)",
         dict(tx_on=True, level_cap=1.25, tx_hold=False,
              stale_skip=False)),
        ("V21a+tx+E1 (level cap 1.5)",
         dict(tx_on=True, level_cap=1.5, tx_hold=False,
              stale_skip=False)),
        ("V21 FULL (tx + E1 + E2 tx-hold)",
         dict(tx_on=True, level_cap=1.5, tx_hold=True,
              stale_skip=False)),
        ("V21 FULL+E3 (tx + E1 + E2 + stale skip)",
         dict(tx_on=True, level_cap=1.5, tx_hold=True,
              stale_skip=True)),
    ]
    for label, kw in variants:
        new, dh, s60, tp60, holdsat, bursts, extra = run21(
            NewTracker21, vad21, dub, [], line_start, txin, txsrc,
            min_buf=16384, total_ms=LONG_MS, **kw)
        steady = report21("FIX %s, world21 v2, speed=%.1f"
                          " tonal=%.2f" % (label, speed, tonal),
                          new, dh, s60, tp60, holdsat, bursts,
                          extra, line_start, line_ulen, vs)
        ok = gate21("world21 v2 speed=%.1f tonal=%.2f %s"
                    % (speed, tonal, label),
                    new, tp60, steady[0],
                    max(d for _, d in dh), steady[2], steady[3],
                    steady[1], s60, rcov=extra.get('render_cov'),
                    rgap=extra.get('render_gap'),
                    end_late=steady[4],
                    bursts_seen=bursts,
                    tp_first60=extra.get('tp_first60'),
                    first_sync_at=extra.get('first_sync_at'),
                    d_pinned_frac=extra.get('d_pinned_frac'))
        all_gate &= ok

    # ---- 3. REGRESSION: v6 world + sim1018 device world (tc ON)
    # NewTracker21 (full) must not fall below NewTracker20. ----
    print("##### regression: v6 world (sim1017) #####")
    vad6, dub6, completes6, line_start6 = s20.s17.build_world()
    txin6, txsrc6 = build_tx21([], [], {}, {})
    old6, dh6, s606, tp606, holdsat6, bursts6, ex6 = run21(
        s20.NewTracker20, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384)
    steady_old6 = report21("OLD (v1.0.20), v6 world", old6, dh6,
                           s606, tp606, holdsat6, bursts6, ex6,
                           line_start6, {})
    new6, dh6b, s60n6, tp60n6, holdsatn6, burstsn6, ex6b = run21(
        NewTracker21, vad6, dub6, completes6, line_start6,
        txin6, txsrc6, min_buf=16384, tx_on=True, level_cap=1.5,
        tx_hold=True, stale_skip=True)
    steady_new6 = report21("NEW (v1.0.21), v6 world", new6, dh6b,
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
    old18, dh18, s6018, tp6018, holdsat18, bursts18, ex18 = run21(
        s19.NewTracker19, vad18, dub18, completes18, line_start18,
        txin6, txsrc6, min_buf=4096, line_counted=line_counted18)
    steady_old18 = report21("OLD (v1.0.19), sim1018 world, tc ON",
                            old18, dh18, s6018, tp6018, holdsat18,
                            bursts18, ex18, line_start18, {})
    new18, dh18b, s60n18, tp60n18, holdsatn18, burstsn18, ex18b = \
        run21(NewTracker21, vad18, dub18, completes18,
              line_start18, txin6, txsrc6, min_buf=4096,
              line_counted=line_counted18, tx_on=True,
              level_cap=1.5, tx_hold=True, stale_skip=True)
    steady_new18 = report21("NEW (v1.0.21), sim1018 world, tc ON",
                            new18, dh18b, s60n18, tp60n18,
                            holdsatn18, burstsn18, ex18b,
                            line_start18, {})
    drop_old18 = (old18.dropped_steady_ms /
                  max(1, old18.total_steady_ms))
    drop_new18 = (new18.dropped_steady_ms /
                  max(1, new18.total_steady_ms))
    reg18 = (tp60n18 >= tp6018
             and pct(steady_new18[0], 0.5)
             <= pct(steady_old18[0], 0.5)
             and drop_new18 <= drop_old18
             and new18.dropped_slices <= old18.dropped_slices + 2)
    print("REGRESSION (sim1018 world, re-scoped): pairs/60s OLD"
          " %d -> NEW %d; line-start steady median OLD %.0f ->"
          " NEW %.0f (need NEW <= OLD); dropped audio steady"
          " OLD %.1f%% -> NEW %.1f%% (need NEW <= OLD);"
          " dropped slices OLD %d -> NEW %d: %s"
          % (tp6018, tp60n18, pct(steady_old18[0], 0.5),
             pct(steady_new18[0], 0.5), drop_old18 * 100,
             drop_new18 * 100, old18.dropped_slices,
             new18.dropped_slices, "PASS" if reg18 else "FAIL"))
    print()
    print("OVERALL v1.0.21 GATE (calibrated 10-min world fix"
          " variants + regressions): %s"
          % ("PASS" if all_gate and reg6 and reg18 else "FAIL"))
    print("NOTE: the gate must pass on the DEVICE-CALIBRATED"
          " world21 v2 (speed=%.1f tonal=%.2f, 10-min session);"
          " the old sim1020 world is no longer sufficient on its"
          " own." % (speed, tonal))


if __name__ == '__main__':
    main()
