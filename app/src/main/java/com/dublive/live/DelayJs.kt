package com.dublive.live

/**
 * Video-delay engine injected into the WebView.
 *
 * v1.0.16 — TOUCH PASSTHROUGH + AUTO-RESUME (the 06:48 YouTube
 * report: "Bấm vào là dừng lại không chạy đuoc video", video stuck
 * at 0:03/3:19). Read the code + the built APK before fixing: the
 * canvas ALREADY had pointer-events:none in source AND in the
 * v1.0.15 dex (verified by strings on classes3.dex), no other DOM
 * overlay exists, and no code ever pauses the video — so the canvas
 * was NOT swallowing the tap by hit-testing. The real stop was the
 * TapJs silence probe (see TapJs.kt): a silent intro misread as a
 * dead tap fires the capture-dialog cascade, the activity pauses,
 * and YouTube pauses the video on visibilitychange. Two defences
 * here anyway: (a) the canvas is hidden + suppressed while a touch
 * is active, so taps/drags (play/pause, seek) always reach
 * YouTube's native controls even if an OEM WebView hit-tests a
 * fixed overlay oddly; pointer-events:none is re-asserted every
 * time the canvas is shown; (b) DubLiveResumeVideo /
 * DubLiveIsVideoPlaying let MainActivity resume the video after
 * the capture dialog returns (see launchProjection).
 *
 * WHY: the dubbed Vietnamese voice always arrives 2-5 s after the picture,
 * so the picture must wait for the voice or the lips never match.
 *
 * HOW (v1.0.13 — rewritten to mirror the proven web-dub Tampermonkey
 * script v1.9.1, which delays the picture correctly on this exact
 * cswold page): ONE fixed-position canvas (z-index max) layered over
 * the MAIN video only. Snapshots are taken at most every 30 ms
 * (~30 fps, max 640 px wide) into a timestamped queue; the canvas
 * draws the newest frame whose time has come (now - delayMs). The
 * original video keeps playing underneath; while the buffer is still
 * filling (or after a seek) the canvas stays hidden and the user
 * sees the live video — never a black or frozen frame.
 *
 * v1.0.13 — why the old engine failed (04:17 screenshot: "hình
 * không bị trễ, xem lúc đứng hình k chạy luôn", D 3.4 s live):
 *  1. The old cap was COUNT-based, `ceil((delayMs + 2000) / 33) + 5`,
 *     i.e. it assumed 30 fps. requestAnimationFrame runs at the
 *     display refresh (60 Hz, 144 Hz on the user's iQOO Z9 Turbo),
 *     so at D = 3.4 s the queue held 169 frames spanning only ~2.8 s
 *     (60 Hz) / ~1.2 s (144 Hz) — the oldest frame could NEVER be
 *     3.4 s old, `due` was always null, the canvas never drew and
 *     the picture stayed live. The fix: the buffer is trimmed by
 *     TIME (drop frames older than delay + 2 s), never by count, so
 *     it holds a D-old frame at any frame rate.
 *  2. The old engine snapshotted EVERY rAF tick for EVERY video
 *     (main + corner ad), at up to 960x540, allocating a fresh
 *     canvas + 2 drawImages per tick per video — enough churn to
 *     jank rAF irregularly. When rAF dipped, a due frame appeared,
 *     the canvas drew and the video went opacity 0; when rAF
 *     recovered, no more due frames arrived and the canvas FROZE on
 *     the last frame with the video hidden. The fix: ~30 fps
 *     snapshots, 640 px max, one canvas, one video.
 *  3. The old engine delayed EVERY video (the corner ad got a canvas
 *     too) and used opacity 0 (some Chromium builds stop feeding
 *     fresh frames to drawImage for a fully-transparent video). The
 *     fix: only the MAIN video (same visible-area scoring as TapJs)
 *     gets the canvas, and it is hidden with opacity 0.01 so the
 *     compositor keeps decoding frames for capture.
 *  4. Seek/loadstart now clear the buffer and the effective delay
 *     ramps 0 -> D (no stale frames after a seek), and a drawImage
 *     failure (page blocking frame capture) disables the delay
 *     gracefully — original video shown, audio delay set live by
 *     the service — and reports it via DubLiveAudio.onDelayFailed.
 *
 * LIMIT (kept from v1.0.9): in site fullscreen the delay is off —
 * the browser lifts the VIDEO (opacity 0.01) onto the fullscreen
 * surface and the canvas stays behind in the page, so MainActivity
 * sets the tap + voice delay to 0 there and Toasts the user. The
 * app's own "Phóng to" is the in-sync fullscreen mode.
 * The video element volume stays 1.0 in delayed mode (the capture
 * needs the full signal in capture mode, and the tap graph handles
 * loudness in tap mode); ducking is applied to the delayed player
 * / tap gain, not here. In mic mode the service sets the volume
 * explicitly via DubLiveSetVolume.
 */
object DelayJs {
    const val INJECT = """
(function () {
  if (window.__dubLiveInstalled) return;
  window.__dubLiveInstalled = true;
  window.DubLiveDelayMs = 5000;
  window.DubLiveDelayOn = true;
  window.DubLiveOriginalVolume = 1.0;

  window.DubLiveSetDelay = function (ms) { window.DubLiveDelayMs = ms; };
  window.DubLiveSetEnabled = function (on) {
    window.DubLiveDelayOn = on;
    // v1.0.13 — re-enabling clears stale frames and ramps the
    // effective delay 0 -> D, so the canvas never shows a frame
    // from before the delay was off.
    if (on) { frames.length = 0; seekAt = performance.now(); }
    hideCanvas();
  };
  window.DubLiveSetVolume = function (vol) {
    window.DubLiveOriginalVolume = vol;
    document.querySelectorAll('video').forEach(function (v) { v.volume = vol; });
  };

  var frames = [];
  var lastSnapshot = 0;
  var lastBestCheck = 0;
  var best = null;
  var seekAt = 0;
  var captureFailed = false;
  var delayFailedNotified = false;
  var active = false;
  var seekWired = new WeakSet();
  // v1.0.16 — touch passthrough: while a touch is active the canvas
  // stays hidden so taps/drags always reach the page's own controls
  // (play/pause, seek bar). performance.now() timestamp until which
  // tick() must not show the canvas.
  var touchActiveUntil = 0;

  // v1.0.16 — let the app resume the video after the system capture
  // dialog returns (the dialog pauses the activity and YouTube pauses
  // the video on visibilitychange; see MainActivity.launchProjection)
  // and ask whether it is playing (to resume only when it was).
  window.DubLiveIsVideoPlaying = function () {
    try { return !!(best && !best.paused && best.currentTime > 0); } catch (e) { return false; }
  };
  window.DubLiveResumeVideo = function () {
    try { if (best && best.paused) best.play(); } catch (e) {}
  };
  // v1.0.16 — hide the canvas the moment a touch starts (and keep it
  // hidden ~700 ms, covering the tap + the start of a seek drag), so
  // the gesture lands on the page's controls, never on the overlay.
  // The canvas already has pointer-events:none (verified in the
  // v1.0.15 dex); this is defence in depth for OEM WebViews whose
  // hit-testing treats a fixed overlay oddly.
  document.addEventListener('touchstart', function () {
    touchActiveUntil = performance.now() + 700;
    hideCanvas();
  }, { passive: true });
  document.addEventListener('pointerdown', function () {
    touchActiveUntil = performance.now() + 700;
    hideCanvas();
  }, { passive: true });

  // v1.0.13 — ONE canvas, fixed over the viewport, max z-index
  // (same as the userscript): immune to parent overflow/transform
  // quirks of the player container.
  var canvas = document.createElement('canvas');
  canvas.style.cssText = 'position:fixed;display:none;pointer-events:none;z-index:2147483000;background:#000;';
  document.documentElement.appendChild(canvas);
  var ctx2d = canvas.getContext('2d', { alpha: false });

  // Same MAIN-video discovery as TapJs (visible size wins).
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

  function findBestVideo() {
    var all = [];
    collectVideos(document, all);
    var b = null, bs = -1;
    for (var i = 0; i < all.length; i++) {
      var s = scoreVideo(all[i]);
      if (s > bs) { bs = s; b = all[i]; }
    }
    return b;
  }

  function clearFrames() {
    frames.length = 0;
    seekAt = performance.now();
  }

  function wireSeek(v) {
    if (!v || seekWired.has(v)) return;
    seekWired.add(v);
    v.addEventListener('seeking', clearFrames);
    v.addEventListener('loadstart', clearFrames);
    v.addEventListener('emptied', clearFrames);
  }

  function hideCanvas() {
    canvas.style.display = 'none';
    if (active && best) { try { best.style.opacity = ''; } catch (e) {} }
    active = false;
  }

  function switchBest(nb) {
    if (best === nb) return;
    if (best) { try { best.style.opacity = ''; } catch (e) {} }
    best = nb;
    frames.length = 0;
    seekAt = 0;
    captureFailed = false;
    hideCanvas();
    if (best) wireSeek(best);
  }

  function tick(now) {
    if (best && !document.contains(best)) switchBest(null);
    if (!best || now - lastBestCheck > 500) {
      lastBestCheck = now;
      switchBest(findBestVideo());
    }
    var v = best;
    if (!window.DubLiveDelayOn || !v || captureFailed) {
      hideCanvas();
      requestAnimationFrame(tick);
      return;
    }
    // Site fullscreen: the video is lifted onto its own surface and
    // the canvas cannot follow — delay is off here by contract
    // (MainActivity sets the audio/voice delay to 0 and Toasts).
    if (document.fullscreenElement) {
      hideCanvas();
      requestAnimationFrame(tick);
      return;
    }
    var r = v.getBoundingClientRect();
    if (r.width <= 20 || r.height <= 20 || v.paused || v.ended || v.readyState < 2) {
      hideCanvas();
      requestAnimationFrame(tick);
      return;
    }
    // v1.0.16 — a touch is active (tap / seek drag): keep the canvas
    // hidden so the gesture reaches the page's controls.
    if (now < touchActiveUntil) {
      hideCanvas();
      requestAnimationFrame(tick);
      return;
    }
    // Snapshot at most every 30 ms (~30 fps): one frame per tick at
    // 60-144 Hz is pure churn and is what janked the old engine.
    if (now - lastSnapshot >= 30) {
      lastSnapshot = now;
      var w = Math.min(640, Math.max(2, Math.round(v.videoWidth || r.width)));
      var h = Math.max(2, Math.round(w * ((v.videoHeight || r.height) / Math.max(1, v.videoWidth || r.width))));
      var f = document.createElement('canvas');
      f.width = w; f.height = h;
      try {
        f.getContext('2d').drawImage(v, 0, 0, w, h);
        frames.push({ t: now, c: f });
        // v1.0.13 — trim by TIME, never by count: keep every frame
        // that could still be the due one (delay + 2 s of history),
        // so a D-old frame always exists at any frame rate.
        var keep = window.DubLiveDelayMs + 2000;
        while (frames.length && now - frames[0].t > keep) frames.shift();
      } catch (e) {
        captureFailed = true;
        hideCanvas();
        if (!delayFailedNotified) {
          delayFailedNotified = true;
          try { if (window.DubLiveAudio) DubLiveAudio.onDelayFailed('Web chặn lấy khung hình video nên đã tắt trễ hình (giọng dịch phát ngay khi về).'); } catch (e2) {}
        }
        requestAnimationFrame(tick);
        return;
      }
    }
    // After a seek the effective delay ramps 0 -> D, so the picture
    // never replays stale pre-seek frames.
    var effDelay = seekAt ? Math.min(window.DubLiveDelayMs, Math.max(0, now - seekAt)) : window.DubLiveDelayMs;
    if (effDelay <= 0) {
      hideCanvas();
      requestAnimationFrame(tick);
      return;
    }
    var wanted = now - effDelay, chosen = null;
    for (var i = frames.length - 1; i >= 0; i--) {
      if (frames[i].t <= wanted) { chosen = frames[i].c; break; }
    }
    if (chosen) {
      var vw = v.videoWidth || r.width, vh = v.videoHeight || r.height;
      var scale = Math.min(r.width / Math.max(1, vw), r.height / Math.max(1, vh));
      var cw = Math.max(2, vw * scale), ch = Math.max(2, vh * scale);
      var cx = Math.max(0, r.left + (r.width - cw) / 2), cy = Math.max(0, r.top + (r.height - ch) / 2);
      // v1.0.16 — re-assert pointer-events:none every time the canvas
      // is shown, so no later style write can ever make it eat taps.
      canvas.style.pointerEvents = 'none';
      canvas.style.display = 'block';
      canvas.style.left = cx + 'px'; canvas.style.top = cy + 'px';
      canvas.style.width = Math.min(cw, window.innerWidth) + 'px';
      canvas.style.height = Math.min(ch, window.innerHeight) + 'px';
      if (canvas.width !== chosen.width || canvas.height !== chosen.height) {
        canvas.width = chosen.width; canvas.height = chosen.height;
      }
      ctx2d.drawImage(chosen, 0, 0);
      // 0.01, not 0: a fully-transparent video can stop feeding
      // fresh frames to drawImage on some Chromium builds.
      v.style.opacity = '0.01';
      active = true;
    } else {
      // Buffer still filling: show the ORIGINAL video, never a
      // black or frozen canvas.
      hideCanvas();
    }
    requestAnimationFrame(tick);
  }

  new MutationObserver(function () {
    // Best video is re-evaluated in tick() every 500 ms; the
    // observer just makes sure a fresh <video> is picked up fast.
    lastBestCheck = 0;
  }).observe(document.documentElement, { childList: true, subtree: true });

  requestAnimationFrame(tick);
})();
"""
}
