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
 * with crossOrigin='anonymous' attach normally.
 *
 * PROBE (ALDA): 2.2 s after the AudioContext is RUNNING, a video that
 * produced zero non-silent blocks is declared blocked (CORS without
 * headers / DRM): the graph is bypassed (source -> destination
 * direct) and onTapFailed fires -> the service falls back to capture.
 * The probe NEVER runs while the context is suspended (autoplay
 * without a gesture delivers no audio and would be a false positive);
 * it is deferred until a gesture resumes the context.
 *
 * DUCK: DubLiveTapSetDuck(vol) drives the gain AFTER the DelayNode,
 * so the ducked original audio stays in step with the held dub voice.
 * D: DubLiveTapSetDelay(seconds) drives the DelayNode live (same D as
 * the canvas). DubLiveTapStop() bypasses the delay (source ->
 * destination direct) and keeps the context alive — closing the
 * context would leave the element's audio routing uncertain, so the
 * graph is bypassed, never closed.
 */
object TapJs {
    const val INJECT = """
(function () {
  if (window.__dubLiveTapInstalled) return;
  window.__dubLiveTapInstalled = true;
  window.DubLiveTapDelaySec = 5;
  window.__dubLiveTapActive = false;
  window.__dubLiveTapStates = [];

  function findVideo() {
    var all = Array.from(document.querySelectorAll('video'))
      .filter(function (v) { return v.readyState >= 2 && isFinite(v.duration) && v.duration > 0; });
    if (!all.length) return null;
    var playing = all.filter(function (v) { return !v.paused && !v.ended; });
    var pool = playing.length ? playing : all;
    pool.sort(function (a, b) {
      var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return (b.videoWidth * b.videoHeight + br.width * br.height) -
             (a.videoWidth * a.videoHeight + ar.width * ar.height);
    });
    return pool[0];
  }

  function notify(m) { try { if (window.DubLiveAudio) DubLiveAudio.onTapStatus(m); } catch (e) {} }

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
      gain = ctx.createGain(); gain.gain.value = 1.0;
      tap = ctx.createScriptProcessor(2048, 1, 1);
      silent = ctx.createGain(); silent.gain.value = 0.0;
      source.connect(delay); delay.connect(gain); gain.connect(ctx.destination);
      source.connect(tap); tap.connect(silent); silent.connect(ctx.destination);
      var st = { v: v, ctx: ctx, source: source, delay: delay, gain: gain, tap: tap,
                 silent: silent, nonSilentBlocks: 0, failed: false, checkTimer: null,
                 aliveNotified: false, bypassed: false };
      v.__dubLiveTapState = st;
      window.__dubLiveTapStates.push(st);
      tap.onaudioprocess = function (ev) {
        if (st.failed || st.bypassed) return;
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
        st.checkTimer = setTimeout(function () {
          st.checkTimer = null;
          if (st.failed || st.bypassed) return;
          if (ctx.state === 'suspended') return; // re-arm on next resume
          if (st.nonSilentBlocks === 0) {
            st.failed = true;
            try { source.disconnect(); source.connect(ctx.destination); } catch (e) {}
            try { delay.disconnect(); gain.disconnect(); tap.disconnect(); silent.disconnect(); } catch (e) {}
            try { if (window.DubLiveAudio) DubLiveAudio.onTapFailed('Trang chặn âm thanh WebAudio (CORS/DRM)'); } catch (e) {}
          } else {
            notify('Đã nhận âm thanh trong trang.');
          }
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
        window.__dubLiveTapStates.splice(i, 1);
      }
    }
    var v = findVideo();
    if (v) attachAudio(v);
  }

  window.DubLiveTapStart = function () {
    window.__dubLiveTapActive = true;
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
    window.__dubLiveTapStates.forEach(function (s) {
      if (s.failed || s.blocked) return;
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
