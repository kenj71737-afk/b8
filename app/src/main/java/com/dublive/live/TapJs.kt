package com.dublive.live

/**
 * v1.0.11 — WebAudio tap: take the film sound STRAIGHT FROM THE PAGE
 * (ALDA's mechanism) instead of system capture, so the dubbed voice
 * can play on the MEDIA stream (USAGE_MEDIA) without being re-captured
 * into an echo loop.
 *
 * WHY (the user asked 21:55 "Qua âm thanh máy không được à" ->
 * "Âm thanh phương tiện"): DubLive until v1.0.10 took the film sound
 * with AudioPlaybackCapture, which matches USAGE_MEDIA/GAME — so a
 * dub voice on USAGE_MEDIA would be captured again and re-translated
 * (echo). ALDA avoids that by never running the capture: the sound
 * comes from inside the page through a WebAudio graph, and the dub
 * voice on USAGE_MEDIA is never an input.
 *
 * GRAPH (per video, mirrors ALDA FIX42/FIX31):
 *   MediaElementSource -> DelayNode(D, max 22 s) -> GainNode (duck)
 *     -> destination            (original audio, delayed by D, MEDIA)
 *   MediaElementSource -> ScriptProcessor(2048) -> silent Gain(0)
 *     -> destination            (PCM tap for Live, LIVE not delayed)
 * The ScriptProcessor resamples to 16 kHz mono PCM16 and sends it to
 * the app through the DubLiveAudio JavascriptInterface (onPcm, base64).
 * Only non-silent blocks are sent (RMS >= 0.00008, same threshold as
 * ALDA).
 *
 * CORS/DRM (ALDA FIX42): a cross-origin video whose element has NO
 * crossOrigin attribute is TAINTED for Web Audio — createMediaElement-
 * Source would route it through the graph and the graph would output
 * silence FOREVER (even the direct fallback stays silent, because the
 * source itself is tainted). Such videos are SKIPPED entirely (no
 * graph): the element keeps playing normally and the service falls
 * back to system capture (onTapBlocked). Same-origin video and video
 * with crossOrigin='anonymous' attach normally. (MSE/blob: URLs
 * inherit the page origin, so hls.js playback passes this check.)
 *
 * PROBE (ALDA): 2.2 s after the AudioContext is RUNNING, a video that
 * produced zero non-silent blocks is declared blocked (CORS without
 * headers / DRM): the graph is bypassed (source -> destination
 * direct) and onTapFailed fires -> the service falls back to capture.
 * The probe NEVER runs while the context is suspended (autoplay
 * without a gesture delivers no audio and would be a false positive);
 * it is deferred until a gesture resumes the context.
 *
 * v1.0.16 — the probe budget is PLAYING time, not wall time. The
 * old single 2.2 s window failed any video whose intro is silent
 * (the 06:48 YouTube report: video stuck at 0:03, tap misread as
 * dead, capture-dialog cascade paused the activity and YouTube
 * paused the video). Now the probe only fails after ~8 s of actual
 * playback (currentTime advancing) with zero audible blocks, and
 * never arms while the video is paused.
 *
 * DUCK: DubLiveTapSetDuck(vol) drives the gain AFTER the DelayNode,
 * so the ducked original audio stays in step with the held dub voice.
 * D: DubLiveTapSetDelay(seconds) drives the DelayNode live (same D as
 * the canvas). DubLiveTapStop() bypasses the delay (source ->
 * destination direct) and keeps the context alive — closing the
 * context would leave the element's audio routing uncertain, so the
 * graph is bypassed, never closed.
 *
 * v1.0.13 — ONE video only (the 04:17 screenshot: tap alive, level
 * 884, D 3.4 s, but the live original still overlapped the delayed
 * one). Root cause, read from the cswold page itself: the page runs
 * xgplayer in #cyberPlayer AND a small floating corner ad video
 * ("广告", bottom-left). The old findVideo() sorted by INTRINSIC
 * resolution (videoWidth x videoHeight) + rect area, so a high-res
 * small ad could outrank the main video — and scan() only ever
 * attached to pool[0]. The tap then sat on the AD (level meter
 * showing the ad's audio) while the MAIN video played live, straight
 * to the speakers, next to the ad's delayed audio. Fix (mirrors the
 * proven web-dub userscript v1.9.1 on this exact page):
 *  - findVideo scores by VISIBLE rect area (x10 while playing),
 *    min 100x60, across the document + shadow roots + same-origin
 *    iframes; the main video always wins over a corner ad.
 *  - Only the CURRENT best video sends PCM (onaudioprocess gates on
 *    __dubLiveTapBest) and only its delay gain is live; a demoted
 *    video's gain goes to 0, and a demoted SMALL video is muted
 *    outright (its live audio must never leak beside the delayed
 *    main audio). Promotion unmutes + restores the duck gain.
 *  - The 2.2 s silence probe only ever fails the BEST video (a muted
 *    demoted video is silent by design and must not trip fallback).
 *  - No eligible video at all for 8 s while the tap is active ->
 *    onTapFailed once, so the session falls back to capture instead
 *    of sitting silent forever (e.g. a cross-origin iframe player
 *    the top frame cannot reach).
 */
object TapJs {
    const val INJECT = """
(function () {
  if (window.__dubLiveTapInstalled) return;
  window.__dubLiveTapInstalled = true;
  window.DubLiveTapDelaySec = 5;
  window.DubLiveTapDuckVol = 1.0;
  window.__dubLiveTapActive = false;
  window.__dubLiveTapStates = [];
  window.__dubLiveTapBest = null;
  window.__dubLiveTapNoVideoSince = 0;
  window.__dubLiveTapNoVideoFired = false;

  // v1.0.13 — find the MAIN video the way the proven web-dub
  // userscript does: visible size wins (x10 while playing), across
  // the document, shadow roots and same-origin iframes. Intrinsic
  // resolution is deliberately NOT scored: a 1080p corner ad must
  // never outrank the film the user is watching.
  function collectVideos(root, out) {
    if (!root || !root.querySelectorAll) return;
    try { root.querySelectorAll('video').forEach(function (v) { out.push(v); }); } catch (e) {}
    try {
      root.querySelectorAll('*').forEach(function (el) {
        if (el.shadowRoot) collectVideos(el.shadowRoot, out);
      });
    } catch (e) {}
    try {
      root.querySelectorAll('iframe').forEach(function (f) {
        try { if (f.contentDocument) collectVideos(f.contentDocument, out); } catch (e) {}
      });
    } catch (e) {}
  }

  function scoreVideo(v) {
    var r;
    try { r = v.getBoundingClientRect(); } catch (e) { return -1; }
    if (!r || r.width < 100 || r.height < 60) return -1;
    if (v.readyState < 2 || !isFinite(v.duration) || v.duration <= 0) return -1;
    var score = r.width * r.height;
    if (!v.paused && !v.ended) score *= 10;
    return score;
  }

  function findVideo() {
    var all = [];
    collectVideos(document, all);
    var best = null, bestScore = -1;
    for (var i = 0; i < all.length; i++) {
      var s = scoreVideo(all[i]);
      if (s > bestScore) { bestScore = s; best = all[i]; }
    }
    return best;
  }

  function isSmall(v) {
    try {
      var r = v.getBoundingClientRect();
      return (r.width * r.height) < 0.15 * window.innerWidth * window.innerHeight;
    } catch (e) { return false; }
  }

  function notify(m) { try { if (window.DubLiveAudio) DubLiveAudio.onTapStatus(m); } catch (e) {} }

  // v1.0.13 — one audio path only. Demote the old best (its delayed
  // audio goes silent, a small one is muted outright so its live
  // sound never overlaps the delayed main audio) and promote the
  // new best (unmuted, duck gain restored).
  function updateBest(v) {
    var old = window.__dubLiveTapBest;
    if (old === v) return;
    if (old) {
      var os = old.__dubLiveTapState;
      if (os && !os.failed && !os.blocked) {
        try { os.gain.gain.setTargetAtTime(0, os.ctx.currentTime, 0.05); } catch (e) {}
      }
      if (isSmall(old)) { try { old.muted = true; } catch (e) {} }
    }
    window.__dubLiveTapBest = v;
    try { v.muted = false; } catch (e) {}
    var ns = v.__dubLiveTapState;
    if (ns && !ns.failed && !ns.blocked) {
      try { ns.gain.gain.setTargetAtTime(window.DubLiveTapDuckVol, ns.ctx.currentTime, 0.05); } catch (e) {}
      if (ns.startCheck) ns.startCheck();
    }
  }

  function attachAudio(v) {
    if (!v || v.__dubLiveTapState || (!window.AudioContext && !window.webkitAudioContext)) return;
    // ALDA FIX42: tainted cross-origin source -> skip the graph entirely.
    var src = v.currentSrc || v.src;
    if (src && !v.crossOrigin) {
      try {
        if (new URL(src, location.href).origin !== location.origin) {
          v.__dubLiveTapState = { blocked: true };
          try { if (window.DubLiveAudio) DubLiveAudio.onTapBlocked('Trang chặn lấy tiếng (CORS)'); } catch (e) {}
          return;
        }
      } catch (e) {}
    }
    var AC = window.AudioContext || window.webkitAudioContext;
    var ctx, source, delay, gain, tap, silent;
    try {
      ctx = new AC();
      source = ctx.createMediaElementSource(v);
      delay = ctx.createDelay(22.0);
      delay.delayTime.value = window.DubLiveTapDelaySec;
      gain = ctx.createGain(); gain.gain.value = window.DubLiveTapDuckVol;
      tap = ctx.createScriptProcessor(2048, 1, 1);
      silent = ctx.createGain(); silent.gain.value = 0.0;
      source.connect(delay); delay.connect(gain); gain.connect(ctx.destination);
      source.connect(tap); tap.connect(silent); silent.connect(ctx.destination);
      var st = { v: v, ctx: ctx, source: source, delay: delay, gain: gain, tap: tap,
                 silent: silent, nonSilentBlocks: 0, failed: false, checkTimer: null,
                 aliveNotified: false, bypassed: false,
                 probePlayedMs: 0, probeStartTime: 0 };
      v.__dubLiveTapState = st;
      window.__dubLiveTapStates.push(st);
      tap.onaudioprocess = function (ev) {
        // v1.0.13 — only the current best video feeds Live. A second
        // attached video (corner ad) must never mix its PCM into the
        // translation, or the dub translates two soundtracks at once.
        if (st.failed || st.bypassed || st.v !== window.__dubLiveTapBest) return;
        var input = ev.inputBuffer.getChannelData(0);
        var sum = 0;
        for (var i = 0; i < input.length; i += 4) sum += input[i] * input[i];
        var rms = Math.sqrt(sum / Math.max(1, Math.ceil(input.length / 4)));
        if (rms < 0.00008) return;
        st.nonSilentBlocks++;
        if (!st.aliveNotified) {
          st.aliveNotified = true;
          try { if (window.DubLiveAudio) DubLiveAudio.onTapAlive(); } catch (e) {}
        }
        var ratio = ctx.sampleRate / 16000;
        var outLen = Math.max(1, Math.floor(input.length / ratio));
        var bytes = new Uint8Array(outLen * 2);
        var view = new DataView(bytes.buffer);
        for (var j = 0; j < outLen; j++) {
          var idx = Math.min(input.length - 1, Math.floor(j * ratio));
          var x = Math.max(-1, Math.min(1, input[idx]));
          view.setInt16(j * 2, x < 0 ? x * 32768 : x * 32767, true);
        }
        var binary = '';
        for (var k = 0; k < bytes.length; k++) binary += String.fromCharCode(bytes[k]);
        try { if (window.DubLiveAudio) DubLiveAudio.onPcm(btoa(binary)); } catch (e) {}
      };
      var startCheck = function () {
        if (st.checkTimer !== null || st.failed || st.bypassed) return;
        // v1.0.13 — the silence probe only judges the BEST video. A
        // demoted video is muted / gain-0 by design, so its silence
        // must never trip the capture fallback.
        if (st.v !== window.__dubLiveTapBest) return;
        // v1.0.16 — do not arm while paused: a paused video produces
        // no blocks by design. The 'play'/'playing' listeners re-arm
        // when playback starts.
        if (st.v.paused) return;
        st.probeStartTime = st.v.currentTime || 0;
        st.checkTimer = setTimeout(function () {
          st.checkTimer = null;
          if (st.failed || st.bypassed) return;
          if (st.v !== window.__dubLiveTapBest) return;
          if (ctx.state === 'suspended') return; // re-arm on next resume
          if (st.nonSilentBlocks > 0) {
            notify('Đã nhận âm thanh trong trang.');
            return;
          }
          // v1.0.16 — only time the video REALLY played counts
          // against the probe. The old code failed the tap after a
          // single 2.2 s window of silence — a silent intro (the
          // 06:48 YouTube case: video stuck at 0:03) was misread as
          // a dead tap, fired the capture-dialog cascade, the
          // activity paused and YouTube paused the video. Fail only
          // after ~8 s of actual playback with zero audible blocks:
          // a CORS/DRM-blocked graph stays silent forever, a
          // silent intro does not. A paused video (or one whose
          // clock did not advance) just re-arms.
          var played = 0;
          try { played = Math.max(0, (st.v.currentTime || 0) - (st.probeStartTime || 0)) * 1000; } catch (e) {}
          if (st.v.paused || played < 1000) { startCheck(); return; }
          st.probePlayedMs += played;
          if (st.probePlayedMs < 8000) { startCheck(); return; }
          st.failed = true;
          try { source.disconnect(); source.connect(ctx.destination); } catch (e) {}
          try { delay.disconnect(); gain.disconnect(); tap.disconnect(); silent.disconnect(); } catch (e) {}
          try { if (window.DubLiveAudio) DubLiveAudio.onTapFailed('Trang chặn âm thanh WebAudio (CORS/DRM)'); } catch (e) {}
        }, 2200);
      };
      st.startCheck = startCheck;
      var resume = function () {
        if (ctx.state === 'suspended') {
          ctx.resume().then(function () { if (ctx.state === 'running') startCheck(); }).catch(function () {});
          return;
        }
        startCheck();
      };
      v.addEventListener('play', resume);
      v.addEventListener('playing', resume);
      if (!v.paused) resume();
    } catch (e) {
      try { if (source) { source.disconnect(); source.connect(ctx.destination); } } catch (_) {}
      try { if (ctx) ctx.close(); } catch (_) {}
      v.__dubLiveTapState = { blocked: true };
      try { if (window.DubLiveAudio) DubLiveAudio.onTapFailed('Không lấy được âm thanh trong trang: ' + (e && e.message ? e.message : 'không hỗ trợ')); } catch (e2) {}
    }
  }

  function scan() {
    if (!window.__dubLiveTapActive) return;
    for (var i = window.__dubLiveTapStates.length - 1; i >= 0; i--) {
      var s = window.__dubLiveTapStates[i];
      if (s.v && !document.contains(s.v)) {
        try { if (s.ctx) s.ctx.close(); } catch (e) {}
        try { if (s.v) s.v.__dubLiveTapState = null; } catch (e) {}
        if (window.__dubLiveTapBest === s.v) window.__dubLiveTapBest = null;
        window.__dubLiveTapStates.splice(i, 1);
      }
    }
    var v = findVideo();
    if (!v) {
      // v1.0.13 — no main video reachable at all (e.g. a
      // cross-origin iframe player the top frame cannot see):
      // fall back to capture after 8 s instead of sitting
      // silent forever. Fires once per activation.
      if (!window.__dubLiveTapNoVideoSince) window.__dubLiveTapNoVideoSince = Date.now();
      if (!window.__dubLiveTapNoVideoFired &&
          Date.now() - window.__dubLiveTapNoVideoSince > 8000) {
        window.__dubLiveTapNoVideoFired = true;
        try { if (window.DubLiveAudio) DubLiveAudio.onTapFailed('Không tìm thấy video chính trên trang — đang chuyển sang đường cuộc gọi…'); } catch (e) {}
      }
      return;
    }
    window.__dubLiveTapNoVideoSince = 0;
    window.__dubLiveTapNoVideoFired = false;
    updateBest(v);
    attachAudio(v);
  }

  window.DubLiveTapStart = function () {
    window.__dubLiveTapActive = true;
    window.__dubLiveTapNoVideoSince = 0;
    window.__dubLiveTapNoVideoFired = false;
    scan();
  };
  // Bypass the delay (direct sound again) but keep the context alive:
  // closing it would leave the element's audio routing uncertain.
  // A later DubLiveTapStart re-connects the graph on the same state.
  window.DubLiveTapStop = function () {
    window.__dubLiveTapActive = false;
    window.__dubLiveTapStates.forEach(function (s) {
      if (s.failed || s.blocked || s.bypassed) return;
      s.bypassed = true;
      try { s.source.disconnect(); s.source.connect(s.ctx.destination); } catch (e) {}
      try { s.delay.disconnect(); s.gain.disconnect(); s.tap.disconnect(); s.silent.disconnect(); } catch (e) {}
    });
  };
  window.DubLiveTapResume = function () {
    window.__dubLiveTapActive = true;
    window.__dubLiveTapStates.forEach(function (s) {
      if (s.failed || s.blocked || !s.bypassed) return;
      s.bypassed = false;
      try { s.source.disconnect(); } catch (e) {}
      try { s.source.connect(s.delay); s.delay.connect(s.gain); s.gain.connect(s.ctx.destination); } catch (e) {}
      try { s.source.connect(s.tap); s.tap.connect(s.silent); s.silent.connect(s.ctx.destination); } catch (e) {}
      try { if (s.ctx.state === 'suspended') s.ctx.resume(); } catch (e) {}
      if (s.startCheck) s.startCheck();
    });
    scan();
  };
  window.DubLiveTapSetDelay = function (sec) {
    var n = Math.max(0, Math.min(20, Number(sec) || 0));
    window.DubLiveTapDelaySec = n;
    window.__dubLiveTapStates.forEach(function (s) {
      if (s.failed || s.blocked) return;
      try { s.delay.delayTime.setTargetAtTime(n, s.ctx.currentTime, 0.025); } catch (e) {}
    });
  };
  window.DubLiveTapSetDuck = function (vol) {
    var n = Math.max(0, Math.min(1, Number(vol) || 0));
    window.DubLiveTapDuckVol = n;
    window.__dubLiveTapStates.forEach(function (s) {
      if (s.failed || s.blocked) return;
      // v1.0.13 — only the best video's delayed audio is audible.
      if (s.v !== window.__dubLiveTapBest) return;
      try { s.gain.gain.setTargetAtTime(n, s.ctx.currentTime, 0.05); } catch (e) {}
    });
  };

  new MutationObserver(function () { scan(); }).observe(document.documentElement, { childList: true, subtree: true });
  setInterval(scan, 1200);
  function resumeAll() {
    window.__dubLiveTapStates.forEach(function (s) {
      try {
        if (s.ctx && s.ctx.state === 'suspended') {
          s.ctx.resume().then(function () { if (s.ctx.state === 'running' && s.startCheck) s.startCheck(); }).catch(function () {});
        } else if (s.ctx && s.ctx.state === 'running' && s.startCheck) {
          s.startCheck();
        }
      } catch (e) {}
    });
  }
  document.addEventListener('click', resumeAll);
  document.addEventListener('touchstart', resumeAll);
})();
"""
}
