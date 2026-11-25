package com.dublive.live

/**
 * Video-delay engine injected into the WebView.
 *
 * WHY: the dubbed Vietnamese voice always arrives 2-5 s after the picture,
 * so the picture must wait for the voice or the lips never match.
 *
 * HOW (written fresh for DubLive): every animation frame, each playing
 * <video> is snapshotted into a small off-screen canvas and pushed onto a
 * timestamped queue. A display canvas layered exactly over the video draws
 * the oldest frame whose time has come (now - delayMs). The original video
 * keeps playing (and sounding) underneath at low volume; only its picture
 * is hidden while delay is on. Works for ordinary web films and for the
 * YouTube mobile site, because both render through a plain <video> tag.
 *
 * LIMIT: this delays the PICTURE. The original audio still plays in real
 * time (quiet, 15%), so it runs ahead of the delayed picture by the delay
 * amount. The Vietnamese voice itself is synced to the delayed picture.
 */
object DelayJs {
    const val INJECT = """
(function () {
  if (window.__dubLiveInstalled) return;
  window.__dubLiveInstalled = true;
  window.DubLiveDelayMs = 5000;
  window.DubLiveDelayOn = true;
  window.DubLiveOriginalVolume = 0.15;

  window.DubLiveSetDelay = function (ms) { window.DubLiveDelayMs = ms; };
  window.DubLiveSetEnabled = function (on) {
    window.DubLiveDelayOn = on;
    document.querySelectorAll('video').forEach(function (v) {
      var wrap = v.__dubLiveWrap;
      if (!wrap) return;
      // v1.0.6 — khi bật lại trễ (vd thoát toàn màn hình), xóa các khung
      // cũ trong bộ đệm: chúng là hình từ TRƯỚC lúc tắt trễ, tick sẽ ăn
      // hết chúng một lượt và canvas hiện hình cũ sai thời điểm. Video
      // giữ opacity 1 cho tới khi tick vẽ khung trễ đầu tiên (tick tự
      // đặt opacity 0 lúc đó), nên không có khoảng màn đen ~5 s chờ đệm
      // đầy lại.
      if (on) wrap.frames.length = 0;
      v.style.opacity = '1';
      wrap.canvas.style.display = on ? 'block' : 'none';
    });
  };
  window.DubLiveSetVolume = function (vol) {
    window.DubLiveOriginalVolume = vol;
    document.querySelectorAll('video').forEach(function (v) { v.volume = vol; });
  };

  function setupVideo(v) {
    if (v.__dubLiveWrap) return;
    var parent = v.parentElement;
    if (!parent) return;
    if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
    var canvas = document.createElement('canvas');
    canvas.style.position = 'absolute';
    canvas.style.left = '0'; canvas.style.top = '0';
    canvas.style.width = '100%'; canvas.style.height = '100%';
    canvas.style.zIndex = '5';
    canvas.style.pointerEvents = 'none';
    canvas.style.display = 'none';
    parent.appendChild(canvas);
    var scratch = document.createElement('canvas');
    v.__dubLiveWrap = { canvas: canvas, scratch: scratch, frames: [] };
    v.volume = window.DubLiveOriginalVolume;
  }

  function snapshot(v, wrap) {
    var w = Math.min(v.videoWidth || 640, 960);
    var h = Math.min(v.videoHeight || 360, 540);
    if (!w || !h) return;
    if (wrap.scratch.width !== w || wrap.scratch.height !== h) {
      wrap.scratch.width = w; wrap.scratch.height = h;
    }
    try { wrap.scratch.getContext('2d').drawImage(v, 0, 0, w, h); } catch (e) { return; }
    var copy = document.createElement('canvas');
    copy.width = w; copy.height = h;
    copy.getContext('2d').drawImage(wrap.scratch, 0, 0);
    wrap.frames.push({ t: performance.now(), c: copy });
    var cap = Math.ceil((window.DubLiveDelayMs + 2000) / 33) + 5;
    while (wrap.frames.length > cap) wrap.frames.shift();
  }

  function tick() {
    var now = performance.now();
    document.querySelectorAll('video').forEach(function (v) {
      setupVideo(v);
      var wrap = v.__dubLiveWrap;
      if (!wrap) return;
      if (!window.DubLiveDelayOn) return;
      if (!v.paused && !v.ended && v.readyState >= 2) snapshot(v, wrap);
      var due = null;
      while (wrap.frames.length > 0 && wrap.frames[0].t <= now - window.DubLiveDelayMs) {
        due = wrap.frames.shift();
      }
      if (due) {
        var cv = wrap.canvas;
        var r = v.getBoundingClientRect();
        var pr = v.parentElement.getBoundingClientRect();
        cv.style.left = (r.left - pr.left) + 'px';
        cv.style.top = (r.top - pr.top) + 'px';
        cv.style.width = r.width + 'px';
        cv.style.height = r.height + 'px';
        if (cv.width !== due.c.width || cv.height !== due.c.height) {
          cv.width = due.c.width; cv.height = due.c.height;
        }
        v.style.opacity = '0';
        cv.style.display = 'block';
        try { cv.getContext('2d').drawImage(due.c, 0, 0); } catch (e) {}
      }
    });
    requestAnimationFrame(tick);
  }

  new MutationObserver(function () {
    document.querySelectorAll('video').forEach(setupVideo);
  }).observe(document.documentElement, { childList: true, subtree: true });

  requestAnimationFrame(tick);
})();
"""
}
