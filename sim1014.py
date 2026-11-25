#!/usr/bin/env python3
"""
DubLive v1.0.14 — simulation v4 (kept under ~/workspace/dub-live-moi/
because /tmp is wiped on VM replacement).

Device evidence to reproduce (05:08, v1.0.13, cswold ep-2):
  D 3,4 s FROZEN (identical to 04:17), last sample 0,6 s,
  111 samples/60 s, queue 1 slice, deferred 1421, dropped 0/0.

HARSH WORLD: fast drama dialogue (lines 3.5-4.5 s, pauses 300-800 ms;
VAD merges lines when the pause < 700 ms tail, so VAD utterances
last 10-30 s and utterance starts go stale); the model speaks
continuously (generation gaps Exp(mean 120 ms); bursts need >=500 ms,
turnComplete fires on gaps >=200 ms); dub is 1.3x the counted
speech, spoken in real time, lag grows within a line from L0=1.4 s;
one 30 s continuous stretch; key rotation (queue clear) at 300 s.

OLD tracker (v1.0.13) in this world reproduces the device almost
exactly (v2/v3 runs): D changed 4x in 7 min, last sample 0.5 s,
89 samples/60 s, queue 0, deferred 1571 (device 1421), dropped 0/0.

NEW design v4 (what the sim proved, step by step):
- NO forced 4 s cut (v2): a cut marker is NEWER than the content
  being translated (the model lags by L), so burst-marker
  underestimates the lag by up to L, and the cut corrupts
  turn_start_audio, which corrupts the burst re-anchor and every
  later slice pairing.
- NO reasonableness gate (v2): it selected FOR underestimated
  samples (rejected the true large lags after the 30 s stretch).
- Ratio with a PRIOR (v3 debug): the old gate returned 1.0 until
  5 s of dub, while the truth is ~0.77 -> every early anchor was
  ~700 ms off and the tail formula was wrong. Prior: dub ~1.3x
  speech, 5 s weight: ratio = (cumS+5000)/(cumD+6500).
- Burst pairing by MARKER SELECTION (v3 debug): popping the OLDEST
  marker is wrong when most turns have no burst (the oldest marker
  is several turns stale -> burst error median +4.2 s). Select the
  marker NEAREST to (burst - current lag estimate); discard markers
  older than the selected one (skipped turns). The selection uses
  the lag estimate only to choose; the D-independent slice backbone
  keeps D honest, so a wrong pick moves D by at most one slot.
- Turn-start anchor with the EXACT tail formula:
  tailDub = counted_prev/ratio - (cumDub_now - cumDub_prevStart);
  anchor_s = cumSpeech - tailDub*ratio.
- Slice pairing: ratio-compensated, anchored (burst-exact /
  turn-start tail-corrected / stall-exact); the paired slice's wall
  time is INTERPOLATED inside the slice (using the END wall biased
  every sample low by up to 500 ms). LatencyEma creep 250 ms/window.
  Anchors + clocks survive key rotation.
"""
import random
from collections import deque

random.seed(20261126)

SLICE_MS = 500
WINDOW_MS = 4000
MIN_SAMPLE_MS = 500
MAX_SAMPLE_MS = 20000
STALL_MS = 10000
MAX_SLICES = 40
DUB_RATIO = 1.3
L0_MS = 1400
CHUNK_MS = 100
KEY_ROTATE_AT_MS = 300_000
TOTAL_MS = 420_000


def build_world():
    vad, dub, completes = [], [], []
    t = 0
    line_id = 0
    dub_free_at = None
    vad_open = False
    vad_last_loud_end = 0
    pending_end_at = None

    def vad_close(end_time):
        nonlocal vad_open, pending_end_at
        if vad_open:
            vad.append((end_time, 'end', 0))
            vad_open = False
            pending_end_at = None

    while t < TOTAL_MS:
        if 180_000 <= t < 210_000:
            ulen, pause = 30_000, 0
        else:
            ulen = random.randint(3500, 4500)
            pause = random.randint(300, 800)
        ulen = min(ulen, TOTAL_MS - t)
        if ulen <= 0:
            break
        loud_ms = max(0, ulen - 700)
        counted_ms = loud_ms + 160
        if not vad_open:
            vad.append((t, 'start', 160))
            vad_open = True
        elif pending_end_at is not None and t >= pending_end_at:
            vad_close(pending_end_at)
            vad.append((t, 'start', 160))
            vad_open = True
        pending_end_at = None
        tt = t
        rem = loud_ms
        while rem > 0:
            step = min(20, rem)
            vad.append((tt, 'chunk', step))
            tt += step
            rem -= step
        vad_last_loud_end = t + loud_ms
        pending_end_at = vad_last_loud_end + 700
        dub_dur = int(counted_ms * DUB_RATIO)
        speak_start = t + L0_MS
        if dub_free_at is not None:
            speak_start = max(speak_start, dub_free_at)
        gap = 0
        if dub_free_at is not None:
            gap = speak_start - dub_free_at
        extra_gap = int(random.expovariate(1 / 120.0))
        speak_start += extra_gap
        gap += extra_gap
        if gap >= 200:
            completes.append(speak_start - extra_gap)
        u = 0
        while u < dub_dur:
            step = min(CHUNK_MS, dub_dur - u)
            arrival = speak_start + u + random.randint(-25, 25)
            src_pos = (u + step / 2) / DUB_RATIO
            src_wall = t + src_pos
            dub.append((arrival, step, arrival - src_wall, line_id))
            u += step
        dub_free_at = speak_start + dub_dur
        line_id += 1
        t = t + ulen + pause
        if pending_end_at is not None and t >= pending_end_at:
            vad_close(pending_end_at)
    if vad_open:
        vad_close(min(TOTAL_MS, vad_last_loud_end + 700))
    dub.sort(key=lambda x: x[0])
    vad.sort(key=lambda x: x[0])
    completes.sort()
    return vad, dub, completes


class LatencyEma:
    def __init__(self, creep=0):
        self.margin = 2000
        self.min_d = 3000
        self.max_d = 20000
        self.creep = creep
        self.ema = None

    def seed(self, lat):
        self.ema = max(0, min(lat, self.max_d - self.margin))

    def target(self):
        if self.ema is None:
            return None
        return max(self.min_d, min(self.ema + self.margin, self.max_d))

    def sample(self, lat, cur):
        s = max(0, min(lat, self.max_d - self.margin))
        self.ema = s if self.ema is None else (self.ema * 7 + s * 3) // 10
        new = max(self.min_d, min(self.ema + self.margin, self.max_d))
        if abs(new - cur) >= 500:
            return new
        if self.creep:
            tgt = self.target()
            if tgt < cur:
                return max(tgt, cur - self.creep)
            if tgt > cur:
                return min(tgt, cur + self.creep)
        return None


class BurstDetector:
    def __init__(self, gap=500):
        self.gap = gap
        self.last = -1

    def on_chunk(self, now):
        b = self.last < 0 or now - self.last >= self.gap
        self.last = now
        return b

    def reset(self):
        self.last = -1


class OldTracker:
    """v1.0.13, faithful port."""
    def __init__(self):
        self.ema = LatencyEma()
        self.burst = BurstDetector(500)
        self.delay = 5000
        self.hold = 0
        self.seeded = False
        self.slices = []
        self.speech_acc = 0
        self.last_slice_at = 0
        self.dub_acc = 0
        self.win = []
        self.win_start = 0
        self.last_sample = 0
        self.last_sample_at = 0
        self.samples60 = []
        self.deferred = 0
        self.dropped = 0
        self.dropped_slices = 0
        self.empty_pair = 0
        self.turn_pairs = 0
        self.turn_pair_times = []
        self.d_changes = 0

    def _set_delay(self, v):
        if v != self.delay:
            self.delay = v
            self.d_changes += 1

    def on_speech_ms(self, ms, now):
        if ms <= 0:
            return
        if self.last_slice_at and now - self.last_slice_at > STALL_MS:
            self.win.clear()
            self.win_start = now
            self.speech_acc = 0
        if not self.win_start:
            self.win_start = now
        self.speech_acc += ms
        while self.speech_acc >= SLICE_MS:
            self.speech_acc -= SLICE_MS
            self.slices.append(now)
            self.last_slice_at = now
            while len(self.slices) > MAX_SLICES:
                self.slices.pop(0)
                self.dropped_slices += 1

    def on_dub(self, now, dub_ms, utt_start, first_sent_at):
        hold = self.hold
        if not self.seeded and first_sent_at:
            self.seeded = True
            el = max(0, min(now - first_sent_at, 18000))
            self.ema.seed(el)
            nd = max(3000, min(el + 2000, 20000))
            self._set_delay(nd)
            hold = max(0, nd - el)
            self.hold = hold
        if self.burst.on_chunk(now):
            hold = self.on_burst(now, utt_start)
            self.hold = hold
        self.pair(dub_ms, now)
        self.flush(now)
        return hold

    def on_burst(self, now, utt_start):
        if utt_start and now - utt_start <= 20000:
            lat = now - utt_start
            upd = self.ema.sample(lat, self.delay)
            if upd is not None:
                self._set_delay(upd)
            self.turn_pairs += 1
            self.turn_pair_times.append(now)
            return max(0, self.delay - lat)
        return self.hold

    def pair(self, dub_ms, now):
        if dub_ms <= 0:
            return
        if not self.last_slice_at:
            self.dub_acc = 0
            return
        if now - self.last_slice_at > STALL_MS:
            self.slices.clear()
            self.dub_acc = 0
            self.win.clear()
            self.win_start = now
            return
        self.dub_acc += dub_ms
        while self.dub_acc >= SLICE_MS:
            if not self.slices:
                self.dub_acc -= SLICE_MS
                self.empty_pair += 1
                continue
            oldest = self.slices[0]
            sample = now - oldest
            if sample < MIN_SAMPLE_MS:
                self.deferred += 1
                break
            self.slices.pop(0)
            self.dub_acc -= SLICE_MS
            if sample > MAX_SAMPLE_MS:
                self.dropped += 1
                continue
            if not self.win_start:
                self.win_start = now
            self.win.append(sample)
            self.last_sample = sample
            self.last_sample_at = now
            self.samples60.append(now)
            while self.samples60 and now - self.samples60[0] > 60000:
                self.samples60.pop(0)

    def flush(self, now):
        if not self.win_start:
            self.win_start = now
            return
        if now - self.win_start < WINDOW_MS:
            return
        if self.win and now - self.last_sample_at <= STALL_MS:
            s = sorted(self.win)
            med = s[len(s)//2] if len(s) % 2 else (s[len(s)//2-1]+s[len(s)//2])//2
            upd = self.ema.sample(med, self.delay)
            if upd is not None:
                self._set_delay(upd)
        self.win.clear()
        self.win_start = now

    def reset_reconnect(self):
        self.burst.reset()
        self.slices.clear()
        self.speech_acc = 0
        self.dub_acc = 0
        self.win.clear()
        self.win_start = 0


class NewTracker:
    """v1.0.14 design v4."""
    def __init__(self):
        self.ema = LatencyEma(creep=250)
        self.burst = BurstDetector(500)
        self.delay = 5000
        self.hold = 0
        self.seeded = False
        # slices: (start_wall, start_audio, end_wall, end_audio)
        self.slices = []
        self.speech_acc = 0
        self.last_slice_at = 0
        self.cum_speech = 0
        self.cum_dub = 0
        self.anchor_s = 0
        self.anchor_u = 0
        self.paired_dub = 0
        self.dub_acc = 0
        self.win = []
        self.win_start = 0
        self.last_sample = 0
        self.last_sample_at = 0
        self.samples60 = []
        self.deferred = 0
        self.dropped = 0
        self.dropped_slices = 0
        self.empty_pair = 0
        # marker queue: (wall, audio) per VAD turn, oldest first
        self.markers = deque()
        self.turn_counted_ms = 0
        self.prev_counted_ms = 0
        self.prev_dub_start = 0
        self.cur_dub_start = 0
        self.turn_pairs = 0
        self.turn_pair_times = []
        self.pending_dub_turn = False
        self.sample_err = []
        self.burst_err = []
        self.d_changes = 0

    def _set_delay(self, v):
        if v != self.delay:
            self.delay = v
            self.d_changes += 1

    def ratio(self):
        # Prior: dub ~1.3x speech for this language pair, 5 s weight.
        return max(0.5, min((self.cum_speech + 5000.0) /
                            (self.cum_dub + 6500.0), 1.5))

    def on_turn_start(self, now):
        r = self.ratio()
        tail_audio = 0
        if self.prev_counted_ms > 0:
            total_dub = self.prev_counted_ms / r
            arrived = self.cum_dub - self.prev_dub_start
            tail_dub = max(0.0, total_dub - arrived)
            tail_audio = int(tail_dub * r)
        self.markers.append((now, self.cum_speech))
        self.anchor_s = max(0, self.cum_speech - tail_audio)
        self.anchor_u = self.cum_dub
        self.paired_dub = 0
        self.prev_dub_start = self.cur_dub_start
        self.cur_dub_start = self.cum_dub
        self.turn_counted_ms = 0

    def on_turn_end(self, now):
        self.prev_counted_ms = self.turn_counted_ms

    def on_speech_ms(self, ms, now):
        if ms <= 0:
            return
        if self.last_slice_at and now - self.last_slice_at > STALL_MS:
            self.win.clear()
            self.win_start = now
            self.speech_acc = 0
            self.cum_speech = 0
            self.cum_dub = 0
            self.anchor_s = 0
            self.anchor_u = 0
            self.paired_dub = 0
            self.markers.clear()
            self.prev_counted_ms = 0
            self.prev_dub_start = 0
            self.cur_dub_start = 0
            self.turn_counted_ms = 0
        if not self.win_start:
            self.win_start = now
        self.cum_speech += ms
        self.turn_counted_ms += ms
        self.speech_acc += ms
        while self.speech_acc >= SLICE_MS:
            self.speech_acc -= SLICE_MS
            self.slices.append((now - SLICE_MS,
                                self.cum_speech - SLICE_MS,
                                now, self.cum_speech))
            self.last_slice_at = now
            while len(self.slices) > MAX_SLICES:
                self.slices.pop(0)
                self.dropped_slices += 1

    def on_dub_turn_complete(self):
        self.pending_dub_turn = True

    def on_dub(self, now, dub_ms, true_lag):
        hold = self.hold
        self.cum_dub += dub_ms
        is_burst = self.burst.on_chunk(now)
        if self.pending_dub_turn:
            is_burst = True
            self.pending_dub_turn = False
        if is_burst:
            hold = self.on_burst(now, true_lag)
            self.hold = hold
        self.pair(dub_ms, now, true_lag)
        self.flush(now)
        return hold

    def on_burst(self, now, true_lag):
        if not self.markers:
            return self.hold
        # Pick the marker nearest to (burst - current lag estimate).
        lag_est = max(MIN_SAMPLE_MS, self.delay - 2000)
        target_wall = now - lag_est
        best_i = None
        best_d = None
        for i, (w, a) in enumerate(self.markers):
            lat = now - w
            if lat < MIN_SAMPLE_MS or lat > MAX_SAMPLE_MS:
                continue
            d = abs(w - target_wall)
            if best_d is None or d < best_d:
                best_d = d
                best_i = i
        if best_i is None:
            return self.hold
        for _ in range(best_i + 1):
            m_wall, m_audio = self.markers.popleft()
        lat = now - m_wall
        if lat < MIN_SAMPLE_MS or lat > MAX_SAMPLE_MS:
            return self.hold
        upd = self.ema.sample(lat, self.delay)
        if upd is not None:
            self._set_delay(upd)
        self.turn_pairs += 1
        self.turn_pair_times.append(now)
        self.burst_err.append(lat - true_lag)
        self.anchor_s = m_audio
        self.anchor_u = self.cum_dub
        self.paired_dub = 0
        return max(0, self.delay - lat)

    def pair(self, dub_ms, now, true_lag):
        if dub_ms <= 0:
            return
        if not self.last_slice_at:
            self.dub_acc = 0
            return
        if now - self.last_slice_at > STALL_MS:
            self.slices.clear()
            self.dub_acc = 0
            self.win.clear()
            self.win_start = now
            self.anchor_s = self.cum_speech
            self.anchor_u = self.cum_dub
            self.paired_dub = 0
            return
        r = self.ratio()
        self.dub_acc += dub_ms
        while self.dub_acc >= SLICE_MS:
            target = self.anchor_s + (self.paired_dub + SLICE_MS) * r
            idx = None
            for i, s in enumerate(self.slices):
                if s[3] >= target:
                    idx = i
                    break
            if idx is None:
                self.dub_acc -= SLICE_MS
                self.paired_dub += SLICE_MS
                self.empty_pair += 1
                continue
            sw, sa, ew, ea = self.slices[idx]
            span_a = max(1, ea - sa)
            span_w = max(1, ew - sw)
            wall = sw + (target - sa) * span_w // span_a
            sample = now - wall
            if sample < MIN_SAMPLE_MS:
                self.deferred += 1
                break
            while self.slices and self.slices[0][3] < target:
                self.slices.pop(0)
            self.dub_acc -= SLICE_MS
            self.paired_dub += SLICE_MS
            if sample > MAX_SAMPLE_MS:
                self.dropped += 1
                continue
            if not self.win_start:
                self.win_start = now
            self.win.append(sample)
            self.last_sample = sample
            self.last_sample_at = now
            self.samples60.append(now)
            self.sample_err.append(sample - true_lag)
            while self.samples60 and now - self.samples60[0] > 60000:
                self.samples60.pop(0)

    def flush(self, now):
        if not self.win_start:
            self.win_start = now
            return
        if now - self.win_start < WINDOW_MS:
            return
        if self.win and now - self.last_sample_at <= STALL_MS:
            s = sorted(self.win)
            med = s[len(s)//2] if len(s) % 2 else (s[len(s)//2-1]+s[len(s)//2])//2
            upd = self.ema.sample(med, self.delay)
            if upd is not None:
                self._set_delay(upd)
        self.win.clear()
        self.win_start = now

    def reset_reconnect(self):
        self.burst.reset()
        self.slices.clear()
        self.speech_acc = 0
        self.dub_acc = 0
        self.win.clear()
        self.win_start = 0


def run(tracker_cls, vad, dub, completes, **kw):
    tr = tracker_cls(**kw) if kw else tracker_cls()
    events = []
    for t, kind, payload in vad:
        events.append((t, 0, 'vad', (kind, payload)))
    for arrival, dms, true_lag, tid in dub:
        events.append((arrival, 1, 'dub', (dms, true_lag, tid)))
    for t in completes:
        events.append((t, 1, 'tc', ()))
    events.append((KEY_ROTATE_AT_MS, 2, 'rotate', ()))
    events.sort(key=lambda e: (e[0], e[1]))
    first_sent_at = 0
    utt_start = 0
    d_hist = []
    ideal_hist = []
    next_hist = 5000
    last_true_lag = L0_MS
    for t, _, kind, payload in events:
        if t > TOTAL_MS:
            break
        if kind == 'vad':
            k, p = payload
            if k == 'start':
                utt_start = t
                first_sent_at = first_sent_at or t
                if isinstance(tr, NewTracker):
                    tr.on_turn_start(t)
                tr.on_speech_ms(160, t)   # 160 ms head, sent to Live
            elif k == 'chunk':
                tr.on_speech_ms(p, t)
            elif k == 'end':
                utt_start = 0
                if isinstance(tr, NewTracker):
                    tr.on_turn_end(t)
        elif kind == 'dub':
            dms, true_lag, tid = payload
            last_true_lag = true_lag
            if isinstance(tr, OldTracker):
                tr.on_dub(t, dms, utt_start, first_sent_at)
            else:
                if not tr.seeded and first_sent_at:
                    tr.seeded = True
                    el = max(0, min(t - first_sent_at, 18000))
                    tr.ema.seed(el)
                    nd = max(3000, min(el + 2000, 20000))
                    tr._set_delay(nd)
                    tr.hold = max(0, nd - el)
                tr.on_dub(t, dms, true_lag)
        elif kind == 'tc':
            if isinstance(tr, NewTracker):
                tr.on_dub_turn_complete()
        elif kind == 'rotate':
            tr.reset_reconnect()
        while next_hist <= t:
            d_hist.append((next_hist, tr.delay))
            ideal_hist.append((next_hist, last_true_lag + 2000))
            next_hist += 5000
    end = TOTAL_MS
    s60 = [x for x in tr.samples60 if end - x <= 60000]
    tp60 = [x for x in tr.turn_pair_times if end - x <= 60000]
    return tr, d_hist, ideal_hist, len(s60), len(tp60)


def stats(errs):
    if not errs:
        return "n=0"
    e = sorted(errs)
    return ("median %+.0f ms, p10 %+.0f, p90 %+.0f, n=%d"
            % (e[len(e)//2], e[len(e)//10], e[int(len(e)*0.9)], len(e)))


def main():
    vad, dub, completes = build_world()
    n_vad_turns = sum(1 for _, k, _ in vad if k == 'start')
    print("world: %d vad events (%d vad turns), %d dub chunks, "
          "%d turnCompletes" % (len(vad), n_vad_turns, len(dub),
                                len(completes)))
    print("device 05:08: D 3.4 s FROZEN, last sample 0.6 s, "
          "111 samples/60 s, queue 1, deferred 1421, dropped 0/0.")
    print()

    old, dh, ih, s60, tp60 = run(OldTracker, vad, dub, completes)
    dvals = [d for _, d in dh]
    print("=== OLD tracker (v1.0.13) ===")
    print("D changes: %d   D range: %d..%d   D last 60 s: %s"
          % (old.d_changes, min(dvals), max(dvals), dvals[-12:]))
    print("last sample: %.1f s   samples/60s: %d   queue: %d"
          "   deferred: %d   dropped: %d/%d   empty: %d   turn pairs: %d"
          % (old.last_sample/1000.0, s60, len(old.slices), old.deferred,
             old.dropped_slices, old.dropped, old.empty_pair,
             old.turn_pairs))
    print()

    new, dh2, ih2, s60n, tp60n = run(NewTracker, vad, dub, completes)
    dvals2 = [d for _, d in dh2]
    derr = [d - i for (_, d), (_, i) in zip(dh2, ih2)]
    derr_s = sorted(derr)
    print("=== NEW tracker (v1.0.14 design v4) ===")
    print("D changes: %d   D range: %d..%d"
          % (new.d_changes, min(dvals2), max(dvals2)))
    print("D last 60 s: %s" % dvals2[-12:])
    print("D - ideal(true lag+2s): median %+.0f ms, p10 %+.0f, "
          "p90 %+.0f" % (derr_s[len(derr_s)//2], derr_s[len(derr_s)//10],
                         derr_s[int(len(derr_s)*0.9)]))
    print("last sample: %.1f s   samples/60s: %d   queue: %d"
          "   deferred: %d   dropped: %d/%d   empty: %d"
          % (new.last_sample/1000.0, s60n, len(new.slices),
             new.deferred, new.dropped_slices, new.dropped,
             new.empty_pair))
    print("turn pairs: total %d, last 60 s: %d"
          % (new.turn_pairs, tp60n))
    print("slice-sample err vs true lag:", stats(new.sample_err))
    print("burst-sample err vs true lag:", stats(new.burst_err))
    moves = sum(1 for a, b in zip(dvals2, dvals2[1:]) if a != b)
    print("D moved in %d of %d 5-s steps" % (moves, len(dvals2) - 1))


if __name__ == '__main__':
    main()
