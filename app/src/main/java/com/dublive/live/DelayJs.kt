package com.dublive.live

/**
 * Video-delay engine injected into the WebView.
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
 * v1.0.23 — TRỄ CỐ ĐỊNH 40 s + KHUNG HÌNH NÉN JPEG (sim1028 GATE
 * PASS, ship config (e)):
 *  - D is FIXED at 40 s (window.DubLiveDelayMs = 40000). No adaptive
 *    D any more — the tracker never moves it (see DubDelayTracker).
 *  - Snapshots are JPEG, not live canvases: a raw 640x360 canvas is
 *    ~0,92 MB, so 40 s at 30 fps would hold ~1400 frames ≈ 1,29 GB
 *    in the renderer (OOM). JPEG q0,7 @640 px @15 fps is ~630 frames
 *    × 40–60 KB ≈ 25–38 MB. Resolution stays 640 px (no downgrade),
 *    only the snapshot rate drops 30 → 15 fps.
 *  - Pre-roll: while the 40 s buffer fills (session start, episode
 *    change, seek) the live video is COVERED by a wait overlay
 *    ("Đang chuẩn bị lồng tiếng… 0:12 / 0:40") — the video keeps
 *    playing underneath (its sound feeds Live), so nothing is lost;
 *    the film is only shifted 40 s. The old behaviour (show the
 *    live video while filling) would mean 40 s of unsynced watching
 *    at D = 40 s. Seeking / changing episode clears the buffer and
 *    the 40 s wait starts again — the price of a fixed delay.
 *  - Dubbing gate: the canvas + overlay only run while a dub session
 *    is active (MainActivity calls DubLiveSetDubbing(true) on start,
 *    false on stop). Before that the page shows its video live: with
 *    D = 40 s a delayed canvas + live audio while merely browsing
 *    would be 40 s out of sync.
 *  - Tainted-canvas fallback: toBlob throws / returns null when the
 *    canvas was drawn from a cross-origin video without CORS (reading
 *    pixels is blocked; drawImage itself is not). Same-origin and
 *    MSE/blob video (18dj.tv hls.js, YouTube) are NOT tainted and use
 *    JPEG. A tainted video falls back to raw snapshot canvases like
 *    v1.0.22 — functional, but the 40 s raw buffer can OOM the
 *    renderer; that path is a known limitation, not the main path.
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
  // v1.0.23 — TRỄ CỐ ĐỊNH 40 s (sim1028 ship config (e)).
  window.DubLiveDelayMs = 40000;
  window.DubLiveDelayOn = true;
  // v1.0.23 — true only while a dub session runs (MainActivity
  // calls DubLiveSetDubbing(true) on start, false on stop).
  window.DubLiveDubbing = false;
  window.DubLiveOriginalVolume = 1.0;

  window.DubLiveSetDelay = function (ms) { window.DubLiveDelayMs = ms; };
  window.DubLiveSetEnabled = function (on) {
    window.DubLiveDelayOn = on;
    // v1.0.13 — re-enabling clears stale frames and ramps the
    // effective delay 0 -> D, so the canvas never shows a frame
    // from before the delay was off.
    if (on) { dropAllFrames(); seekAt = performance.now(); canvasActive = false; }
    hideCanvas();
    if (!on) hideWait();
  };
  // v1.0.23 — dubbing gate + pre-roll restart: starting (re)starts
  // the 40 s buffer fill (stale frames cleared, effective delay
  // ramps 0 -> D from now); stopping shows the live video again.
  window.DubLiveSetDubbing = function (on) {
    window.DubLiveDubbing = !!on;
    if (on) { dropAllFrames(); seekAt = performance.now(); canvasActive = false; }
    else { hideCanvas(); hideWait(); }
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
  var canvasActive = false;
  var seekWired = new WeakSet();

  // v1.0.13 — ONE canvas, fixed over the viewport, max z-index
  // (same as the userscript): immune to parent overflow/transform
  // quirks of the player container.
  var canvas = document.createElement('canvas');
  canvas.style.cssText = 'position:fixed;display:none;pointer-events:none;z-index:2147483000;background:#000;';
  document.documentElement.appendChild(canvas);
  var ctx2d = canvas.getContext('2d', { alpha: false });

  // v1.0.23 — pre-roll overlay: covers the LIVE video while the
  // 40 s buffer fills. pointer-events:none so the user can still
  // tap the player controls underneath during the wait.
  var waitDiv = document.createElement('div');
  waitDiv.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;display:none;align-items:center;justify-content:center;z-index:2147483001;background:rgba(0,0,0,0.92);color:#fff;font-size:20px;text-align:center;pointer-events:none;';
  waitDiv.textContent = 'Đang chuẩn bị lồng tiếng…';
  document.documentElement.appendChild(waitDiv);
  function fmtWait(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    var total = Math.max(1, Math.floor(window.DubLiveDelayMs / 1000));
    return 'Đang chuẩn bị lồng tiếng… 0:' + (s < 10 ? '0' : '') + s + ' / 0:' + (total < 10 ? '0' : '') + total;
  }
  function showWait(elapsedMs) {
    waitDiv.textContent = fmtWait(elapsedMs);
    if (waitDiv.style.display !== 'flex') waitDiv.style.display = 'flex';
  }
  function hideWait() {
    if (waitDiv.style.display !== 'none') waitDiv.style.display = 'none';
  }

  // v1.0.23 — JPEG frame buffer. ONE reusable snapshot canvas;
  // each snapshot is encoded to JPEG (q0,7) and decoded to an
  // ImageBitmap (or an Image fallback). Raw canvases at 40 s
  // would hold ~1,29 GB; JPEG holds ~25–38 MB. toBlob is async:
  // snapBusy serialises snapshots so a slow encode never
  // overlaps the next draw. jpegFailed latches on the first
  // tainted-canvas failure (cross-origin video without CORS):
  // those frames fall back to raw canvases like v1.0.22.
  var snap = document.createElement('canvas');
  var snapBusy = false;
  var jpegFailed = false;
  function dropFrame(f) {
    try { if (f.c && f.c.close) f.c.close(); } catch (e) {}
    try { if (f.url) URL.revokeObjectURL(f.url); } catch (e) {}
  }
  function dropAllFrames() {
    for (var i = 0; i < frames.length; i++) dropFrame(frames[i]);
    frames.length = 0;
  }
  function trimFrames(now) {
    var keep = window.DubLiveDelayMs + 2000;
    while (frames.length && now - frames[0].t > keep) dropFrame(frames.shift());
  }
  function addFrame(t, bmp, url) {
    var now = performance.now();
    var keep = window.DubLiveDelayMs + 2000;
    if (now - t > keep) { dropFrame({ c: bmp, url: url }); return; }
    frames.push({ t: t, c: bmp, url: url || null });
    trimFrames(now);
  }
  function pushRawFrame(t) {
    // Tainted canvas (cross-origin video): keep a live canvas
    // like v1.0.22 did. Same-origin / MSE / blob video
    // (18dj.tv, YouTube) never takes this path.
    var f = document.createElement('canvas');
    f.width = snap.width; f.height = snap.height;
    try { f.getContext('2d').drawImage(snap, 0, 0); } catch (e) { return; }
    addFrame(t, f, null);
  }
  function takeSnapshot(v, now, r) {
    if (snapBusy) return;
    var w = Math.min(640, Math.max(2, Math.round(v.videoWidth || r.width)));
    var h = Math.max(2, Math.round(w * ((v.videoHeight || r.height) / Math.max(1, v.videoWidth || r.width))));
    if (snap.width !== w || snap.height !== h) { snap.width = w; snap.height = h; }
    try {
      snap.getContext('2d').drawImage(v, 0, 0, w, h);
    } catch (e) {
      captureFailed = true;
      hideCanvas();
      if (!delayFailedNotified) {
        delayFailedNotified = true;
        try { if (window.DubLiveAudio) DubLiveAudio.onDelayFailed('Web chặn lấy khung hình video nên đã tắt trễ hình (giọng dịch phát ngay khi về).'); } catch (e2) {}
      }
      return;
    }
    if (jpegFailed) { pushRawFrame(now); return; }
    snapBusy = true;
    var t = now;
    try {
      snap.toBlob(function (blob) {
        snapBusy = false;
        if (!blob) { jpegFailed = true; pushRawFrame(t); return; }
        if (window.createImageBitmap) {
          window.createImageBitmap(blob).then(function (bmp) { addFrame(t, bmp, null); }).catch(function () {});
        } else {
          var url = URL.createObjectURL(blob);
          var img = new Image();
          img.onload = function () { addFrame(t, img, url); };
          img.onerror = function () { try { URL.revokeObjectURL(url); } catch (e) {} };
          img.src = url;
        }
      }, 'image/jpeg', 0.7);
    } catch (e) {
      snapBusy = false;
      jpegFailed = true;
      pushRawFrame(t);
    }
  }

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
    dropAllFrames();
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
    canvasActive = false;
  }

  function switchBest(nb) {
    if (best === nb) return;
    if (best) { try { best.style.opacity = ''; } catch (e) {} }
    best = nb;
    dropAllFrames();
    // v1.0.23 — a new best video restarts the pre-roll ramp
    // (0 -> D), like a seek: the counter counts up honestly
    // instead of sitting at 0:40 while the buffer refills.
    seekAt = performance.now();
    canvasActive = false;
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
    // v1.0.23 — no dubbing session (or delay toggled off, or
    // capture failed): show the live video, no overlay.
    if (!window.DubLiveDubbing || !window.DubLiveDelayOn || !v || captureFailed) {
      hideCanvas();
      hideWait();
      requestAnimationFrame(tick);
      return;
    }
    // Site fullscreen: the video is lifted onto its own surface and
    // the canvas cannot follow — delay is off here by contract
    // (MainActivity sets the audio/voice delay to 0 and Toasts).
    if (document.fullscreenElement) {
      hideCanvas();
      hideWait();
      requestAnimationFrame(tick);
      return;
    }
    var r = v.getBoundingClientRect();
    // After a seek the effective delay ramps 0 -> D, so the picture
    // never replays stale pre-seek frames.
    var effDelay = seekAt ? Math.min(window.DubLiveDelayMs, Math.max(0, now - seekAt)) : window.DubLiveDelayMs;
    if (r.width <= 20 || r.height <= 20 || v.paused || v.ended || v.readyState < 2) {
      hideCanvas();
      // Paused/ended mid-pre-roll: keep the wait screen up until
      // the buffer is full (the video resumes into sync).
      if (effDelay < window.DubLiveDelayMs) showWait(effDelay);
      else hideWait();
      requestAnimationFrame(tick);
      return;
    }
    // Snapshot at most every 66 ms (~15 fps, v1.0.23 JPEG):
    // one frame per tick at 60-144 Hz is pure churn and is what
    // janked the old engine.
    if (now - lastSnapshot >= 66) {
      lastSnapshot = now;
      takeSnapshot(v, now, r);
    }
    if (effDelay <= 0) {
      hideCanvas();
      showWait(0);
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
      canvasActive = true;
      hideWait();
    } else {
      // Buffer still filling (v1.0.23 pre-roll): show the WAIT
      // overlay, never the live video — at D = 40 s the old
      // behaviour meant 40 s of unsynced watching before the
      // delayed picture arrived.
      hideCanvas();
      showWait(Math.min(effDelay, window.DubLiveDelayMs));
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
