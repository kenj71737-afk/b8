package com.dublive.live

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Main screen: address bar + WebView for web films and YouTube, dub controls,
 * Vietnamese subtitle overlay and a one-line delay HUD.
 *
 * Audio (v1.0.11): the film sound is taken straight from the page
 * through a WebAudio tap (TapJs) when the page allows it, so the dub
 * voice can play on the MEDIA stream (loud) with no re-capture echo.
 * Pages that block the tap (CORS/DRM — NetShort) fall back to system
 * capture (MediaProjection) with the dub on the call stream, as before.
 *
 * v1.0.1 crash fixes (target device: iQOO Z9 Turbo, OriginOS 6, Android 16):
 *  - RECORD_AUDIO (+ POST_NOTIFICATIONS on Android 13+) is requested at
 *    runtime BEFORE the screen-capture dialog. AudioPlaybackCapture throws
 *    SecurityException without RECORD_AUDIO; v1.0.0 never asked for it.
 *  - startForegroundService() is wrapped in try/catch with one delayed
 *    retry: on Android 16 the ActivityResult callback can run while the
 *    activity is not resumed yet, and the call throws
 *    ForegroundServiceStartNotAllowedException. Any failure falls back to
 *    mic mode instead of crashing the app.
 *  - A tip dialog is shown before the system dialog: Android 16 defaults
 *    the capture scope to "share a single app", and picking DubLive itself
 *    yields no film audio (and killed v1.0.0 on the user's phone).
 *  - A "mic dub" button starts the service without any MediaProjection
 *    token (ACTION_START_MIC), so dubbing still works when screen capture
 *    is blocked by the OS/OEM.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DubLive-Main"
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var subtitleView: TextView
    private lateinit var hudView: TextView
    /** v1.0.7 — the HUD row: delay text + "Trễ −1s / +1s" buttons. */
    private lateinit var hudRow: View
    /** v1.0.10 — delay diagnostics line under the HUD delay number. */
    private lateinit var diagView: TextView
    private lateinit var delayMinusButton: Button
    private lateinit var delayPlusButton: Button
    private lateinit var fsDelayMinusButton: Button
    private lateinit var fsDelayPlusButton: Button
    private lateinit var statusView: TextView
    private lateinit var volumeHintView: TextView
    private lateinit var levelBar: android.widget.ProgressBar
    private lateinit var levelValue: TextView
    private lateinit var delayToggleButton: Button
    private lateinit var subtitleToggleButton: Button
    private lateinit var expandButton: Button
    private lateinit var collapseButton: Button
    private lateinit var fsDelayToggleButton: Button
    private lateinit var fullscreenBar: View
    private lateinit var urlRow: View
    private lateinit var dubRow: View
    private lateinit var fileRow: View
    private lateinit var micRow: View
    private lateinit var errorsRow: View
    private lateinit var statusRow: View
    /** v1.0.6 — true while the app's own fullscreen ("Phóng to") is on:
     * only the WebView + subtitle + HUD + fullscreen bar stay visible,
     * the delay canvas keeps running inside the WebView, so picture and
     * dub voice stay in sync (unlike the web page's own fullscreen). */
    private var appFullscreen = false
    private var delayOn = true
    /** v1.0.6 — delay (ms) last pushed to the WebView canvas via
     * DubLiveSetDelay. v1.0.23: TRỄ CỐ ĐỊNH 40 s (was 5000 ms seed);
     * TYPE_HUD updates it whenever the measured delay moves > 200 ms
     * (now only the manual ±1 s nudge moves it). */
    private var lastAppliedDelayMs = 40000L
    /** v1.0.5 — user can hide subtitles and listen to the dub only. */
    private var subtitlesVisible = true
    private var dubbing = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    /** v1.0.6 — fullscreen: saved parents so subtitleView/hudView can be
     * moved onto decorView while fullscreen and restored on hide (see
     * moveOverlayToDecor/restoreOverlay below). */
    private var subtitleSavedParent: Triple<ViewGroup, Int, ViewGroup.LayoutParams>? = null
    private var hudSavedParent: Triple<ViewGroup, Int, ViewGroup.LayoutParams>? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    /** v1.0.4 — full Live error history (last 8), shown by "Xem lỗi Live". */
    private var liveErrorHistoryText = ""
    /** v1.0.10 — last delay-diagnostics line (also shown in that dialog). */
    private var lastDiagText = ""

    /** True when the pending permission request was started by the mic button. */
    private var pendingMicAfterPermission = false
    /**
     * v1.0.11 — WebAudio tap mode: true while the session takes the film
     * sound from the page (TapJs) instead of system capture. While it
     * is on, the original-audio volume goes to the tap's DelayNode gain
     * (DubLiveTapSetDuck) instead of the canvas (DubLiveSetVolume), and
     * the delay goes to both the canvas and the tap (DubLiveTapSetDelay).
     */
    private var tapMode = false
    /** v1.0.11 — the tap has attached and sent PCM at least once. */
    private var tapActive = false
    /** v1.0.11 — startDub was pressed but we are waiting for the
     * service to report running before starting the page tap. */
    private var pendingTapStart = false
    /** v1.0.11 — the tap died and the projection dialog is out: the
     * result is a tap -> capture fallback, not a fresh start. */
    private var fallbackPending = false
    /** v1.0.11 — last audio-route label from the service, shown on the
     * status line next to the status text. */
    private var lastAudioRouteLabel = ""
    /** v1.0.11 — "media" | "call" | "mic" (see DubbingService). */
    private var currentAudioMode = "" 

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true || hasRecordAudio()
        val wantMic = pendingMicAfterPermission
        pendingMicAfterPermission = false
        if (granted) {
            // v1.0.11 — the normal start is tap-first now (no capture
            // dialog); the capture dialog only appears as a fallback
            // when the tap dies.
            if (wantMic) startMicService() else startTapDub()
        } else {
            // Mic is the only capture path left, and it needs the same
            // permission — but the app must NOT crash here. Tell the user
            // clearly, then try mic mode: the service checks the permission
            // itself, reports the missing permission, and stops cleanly.
            Toast.makeText(this, R.string.need_mic_permission, Toast.LENGTH_LONG).show()
            if (!wantMic) {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
                startMicService()
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, DubbingService::class.java).apply {
                action = DubbingService.ACTION_START
                putExtra(DubbingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(DubbingService.EXTRA_RESULT_DATA, result.data)
                // v1.0.11 — this token is a tap -> capture fallback:
                // the service switches in place (keeps Live + D).
                putExtra(DubbingService.EXTRA_FALLBACK, fallbackPending)
                putExtra(DubbingService.EXTRA_SOURCE, DubbingService.SOURCE_WEB)
            }
            fallbackPending = false
            if (tapMode) {
                // The capture path replays the original audio itself
                // (DelayedBackgroundPlayer), so the tap's delayed audio
                // must go quiet — bypass its DelayNode at once.
                try {
                    webView.evaluateJavascript("window.DubLiveTapStop()", null)
                } catch (_: Exception) {
                }
                tapMode = false
                tapActive = false
            }
            startProjectionService(intent, isRetry = false)
        } else {
            // v1.0.11 — the user denied the capture dialog during a
            // tap fallback: dub through the microphone instead of
            // dying. The tap's delayed audio goes quiet first (the
            // phone's speakers now play the film directly).
            if (fallbackPending) {
                fallbackPending = false
                try {
                    webView.evaluateJavascript("window.DubLiveTapStop()", null)
                } catch (_: Exception) {
                }
                tapMode = false
                tapActive = false
                Toast.makeText(this, "Không ghi được màn hình — chuyển sang nghe bằng micro, để điện thoại gần loa", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(this, DubbingService::class.java).apply {
                        action = DubbingService.ACTION_FALLBACK_MIC
                    }
                    startService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "ACTION_FALLBACK_MIC failed", e)
                }
            } else {
                Toast.makeText(this, "Cần cho phép ghi màn hình để lấy tiếng hệ thống", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * v1.0.11 — the page-side end of the WebAudio tap (TapJs). The JS
     * calls these on the WebView thread, so every call just forwards to
     * the service (or the queue) and returns immediately — no work is
     * done here. onPcm decodes the base64 PCM16 and offers it to
     * TapAudioQueue; the service's tap feeder drains it into the VAD.
     */
    private inner class DubLiveAudioBridge {
        @JavascriptInterface
        fun onPcm(base64: String?) {
            if (base64.isNullOrEmpty()) return
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (bytes.isNotEmpty()) {
                    TapAudioQueue.offerMono16k(bytes)
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onTapAlive() {
            tapActive = true
            try {
                val intent = Intent(this@MainActivity, DubbingService::class.java).apply {
                    action = DubbingService.ACTION_TAP_ALIVE
                }
                startService(intent)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onTapFailed(reason: String?) {
            tapActive = false
            try {
                val intent = Intent(this@MainActivity, DubbingService::class.java).apply {
                    action = DubbingService.ACTION_TAP_FAILED
                    putExtra(DubbingService.EXTRA_MESSAGE, reason.orEmpty())
                }
                startService(intent)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onTapBlocked(reason: String?) {
            tapActive = false
            try {
                val intent = Intent(this@MainActivity, DubbingService::class.java).apply {
                    action = DubbingService.ACTION_TAP_BLOCKED
                    putExtra(DubbingService.EXTRA_MESSAGE, reason.orEmpty())
                }
                startService(intent)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onTapStatus(status: String?) {
            if (status.isNullOrBlank()) return
            Log.i(TAG, "tap status: $status")
        }

        /** v1.0.13 — the delay canvas could not capture frames
         * (page blocks frame capture): the picture delay is off,
         * the original video is shown instead, so tell the user
         * plainly instead of leaving a live picture unexplained. */
        @JavascriptInterface
        fun onDelayFailed(reason: String?) {
            if (reason.isNullOrBlank()) return
            try {
                runOnUiThread {
                    statusView.text = "Trạng thái: $reason"
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
            }
        }
    }

    private val dubReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DubbingService.ACTION_DUB_EVENT) return
            when (intent.getStringExtra(DubbingService.EXTRA_TYPE)) {
                DubbingService.TYPE_SUBTITLE -> {
                    val text = intent.getStringExtra(DubbingService.EXTRA_TEXT).orEmpty()
                    if (text.isBlank() || !subtitlesVisible) {
                        subtitleView.visibility = View.GONE
                    } else {
                        subtitleView.text = text
                        subtitleView.visibility = View.VISIBLE
                    }
                }
                DubbingService.TYPE_HUD -> {
                    val ms = intent.getLongExtra(DubbingService.EXTRA_DELAY_MS, 0L)
                    hudView.text = "Trễ hình: %.1f s".format(ms / 1000.0)
                    hudRow.visibility = View.VISIBLE
                    // v1.0.6 — the main bug: until now this branch only
                    // updated the HUD TEXT and never told the WebView the
                    // new delay, so the canvas always delayed exactly the
                    // 40000 ms fixed delay from onPageFinished while the
                    // HUD showed 7 s / 12 s / 19,4 s — the dub voice (7–15 s
                    // behind the real speech) always lagged the picture.
                    // Apply the measured delay to the canvas whenever it
                    // moved more than 200 ms (avoids spamming JS on every
                    // tick). v1.0.23: D is fixed, so this branch now only
                    // fires on the manual ±1 s nudge.
                    if (ms > 0 && kotlin.math.abs(ms - lastAppliedDelayMs) > 200) {
                        lastAppliedDelayMs = ms
                        try {
                            webView.evaluateJavascript(
                                "window.DubLiveSetDelay($ms)", null
                            )
                        } catch (_: Exception) {
                        }
                        // v1.0.11 — the same D drives the tap's DelayNode
                        // (the delayed original audio), so the sound
                        // follows the picture whenever D moves.
                        if (tapMode) {
                            try {
                                webView.evaluateJavascript(
                                    "window.DubLiveTapSetDelay(${ms / 1000.0})", null
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                DubbingService.TYPE_AUDIO_ROUTE -> {
                    // v1.0.11 — which stream the dub voice is on now.
                    // Shown on the status line so the user always knows
                    // whether the dub is loud (media) or on the call
                    // stream (a page that blocks the tap).
                    val mode = intent.getStringExtra(DubbingService.EXTRA_AUDIO_MODE).orEmpty()
                    val label = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (mode.isNotBlank()) currentAudioMode = mode
                    if (label.isNotBlank()) lastAudioRouteLabel = label
                    updateStatusLine()
                }
                DubbingService.TYPE_NEED_PROJECTION -> {
                    // v1.0.11 — the tap died (CORS/DRM probe or a
                    // blocked video): ask for the screen-capture token
                    // so the service can fall back to system capture.
                    // No user tap needed to START the fallback — the
                    // dialog itself is the only thing Android requires.
                    val reason = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    Log.i(TAG, "tap needs projection fallback: $reason")
                    statusView.text = "Trạng thái: Trang chặn lấy tiếng, đang xin quyền ghi màn hình để chuyển sang đường cuộc gọi…"
                    fallbackPending = true
                    showShareTipThenProjection()
                }
                DubbingService.TYPE_DIAG -> {
                    // v1.0.10 — delay diagnostics, refreshed every
                    // second by the service: D, last sample + age,
                    // samples/60 s, queued slices, voice hold, VAD
                    // state. Lives under the HUD delay number (inside
                    // hudRow, so it follows it to decorView in
                    // fullscreen) and is kept for the "Xem lỗi Live"
                    // dialog below.
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) {
                        lastDiagText = msg
                        diagView.text = msg
                    }
                }
                DubbingService.TYPE_VIDEO_TIME_REQUEST -> {
                    // v1.0.17 — frame check: the service wants the
                    // MAIN video's currentTime belonging to one event
                    // wall (a turn marker or a burst play). Read it
                    // from the page (TapJs best video, else the
                    // largest visible video) and answer the service
                    // with ACTION_VIDEO_TIME. The JS returns
                    // "seconds|paused(0/1)"; "-1|0" means no video.
                    val purpose = intent.getStringExtra(DubbingService.EXTRA_VIDEO_PURPOSE).orEmpty()
                    val seq = intent.getLongExtra(DubbingService.EXTRA_VIDEO_SEQ, 0L)
                    val eventWall = intent.getLongExtra(DubbingService.EXTRA_EVENT_WALL_MS, 0L)
                    try {
                        webView.evaluateJavascript(
                            """(function(){var v=window.__dubLiveTapBest;if(!v||!document.contains(v)){var best=null,bs=-1;document.querySelectorAll('video').forEach(function(x){try{var r=x.getBoundingClientRect();if(r.width<100||r.height<60)return;if(x.readyState<2||!isFinite(x.duration)||x.duration<=0)return;var s=r.width*r.height;if(!x.paused&&!x.ended)s*=10;if(s>bs){bs=s;best=x;}}catch(e){}});v=best;}if(!v)return '-1|0';return v.currentTime+'|'+(v.paused?1:0);})()"""
                        ) { value ->
                            try {
                                val raw = value?.trim()?.trim('"')?.replace("\\\"", "\"") ?: "-1|0"
                                val parts = raw.split("|")
                                val timeSec = parts.getOrNull(0)?.toDoubleOrNull() ?: -1.0
                                val paused = parts.getOrNull(1) == "1"
                                val reply = Intent(this@MainActivity, DubbingService::class.java).apply {
                                    action = DubbingService.ACTION_VIDEO_TIME
                                    putExtra(DubbingService.EXTRA_VIDEO_TIME_SEC, timeSec)
                                    putExtra(DubbingService.EXTRA_VIDEO_PAUSED, paused)
                                    putExtra(DubbingService.EXTRA_VIDEO_PURPOSE, purpose)
                                    putExtra(DubbingService.EXTRA_VIDEO_SEQ, seq)
                                    putExtra(DubbingService.EXTRA_EVENT_WALL_MS, eventWall)
                                }
                                startService(reply)
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                DubbingService.TYPE_STATE -> {
                    val state = intent.getStringExtra(DubbingService.EXTRA_STATE)
                    if (state == DubbingService.STATE_RUNNING) {
                        // v1.0.11 — the service is up: start the page
                        // tap now (it needs the service's VAD running
                        // before the first PCM arrives).
                        if (pendingTapStart) {
                            pendingTapStart = false
                            try {
                                webView.evaluateJavascript("window.DubLiveTapResume()", null)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (state == DubbingService.STATE_STOPPED) {
                        dubbing = false
                        pendingTapStart = false
                        fallbackPending = false
                        // v1.0.11 — bypass the tap delay so the video's
                        // own audio returns to normal at once, and
                        // forget the route (next session re-detects it).
                        try {
                            webView.evaluateJavascript("window.DubLiveTapStop()", null)
                        } catch (_: Exception) {
                        }
                        tapMode = false
                        tapActive = false
                        currentAudioMode = ""
                        lastAudioRouteLabel = ""
                        updateVolumeHint()
                        subtitleView.visibility = View.GONE
                        hudRow.visibility = View.GONE
                        diagView.text = ""
                        levelBar.progress = 0
                        levelValue.text = "0"
                        setOriginalVolume(1.0f)
                    }
                }
                DubbingService.TYPE_STATUS -> {
                    // Persistent status line: the last error/status stays
                    // on screen (v1.0.1 only Toasted errors, so the user
                    // saw nothing after the Toast vanished).
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) statusView.text = "Trạng thái: $msg"
                    // v1.0.11 — keep the route label beside it.
                    updateStatusLine()
                }
                DubbingService.TYPE_LEVEL -> {
                    val level = intent.getIntExtra(DubbingService.EXTRA_LEVEL, 0)
                    // v1.0.4 — log scale (0..1000) + raw RMS beside it, so a
                    // loud scene no longer reads as a flat 1000 with no
                    // clue how loud it really was.
                    val rms = intent.getDoubleExtra(DubbingService.EXTRA_RMS, 0.0)
                    levelBar.progress = level
                    levelValue.text = "$level (RMS ${rms.toInt()})"
                }
                DubbingService.TYPE_LIVE_ERRORS -> {
                    // v1.0.4 — keep the full error history for the dialog.
                    // The status line only shows the LAST error; the first
                    // failure is usually the one that matters.
                    liveErrorHistoryText =
                        intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                }
                DubbingService.TYPE_ERROR -> {
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) {
                        statusView.text = "Lỗi: $msg"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                DubbingService.TYPE_FALLBACK -> {
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) {
                        statusView.text = "Trạng thái: $msg"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                DubbingService.TYPE_DUCK -> {
                    intent.getStringExtra(DubbingService.EXTRA_MESSAGE)?.toFloatOrNull()?.let {
                        setOriginalVolume(it)
                    }
                }
            }
        }
    }

    /**
     * v1.0.12 — the volume hint under the buttons must name the stream
     * the dub voice is REALLY on: MEDIA while the tap works (the
     * phone's volume buttons then move dub + original together, and
     * the Settings slider sets their ratio), CALL/MIC after a
     * capture/mic fallback (the buttons move the dub voice alone).
     * The v1.0.11 build left the static call-stream hint on screen
     * even in tap mode, telling the user to raise the call volume
     * for a voice that was on media.
     */
    private fun updateVolumeHint() {
        try {
            volumeHintView.setText(
                if (currentAudioMode == DubbingService.AUDIO_MODE_MEDIA) {
                    R.string.media_volume_hint
                } else {
                    R.string.call_volume_hint
                }
            )
        } catch (_: Exception) { }
    }

    /**
     * v1.0.11 — the status line carries the audio route next to the
     * status text, e.g. "Trạng thái: … | Âm thanh: phương tiện", so the
     * user can see at a glance whether the dub is on the loud media
     * stream or fell back to the call stream.
     */
    private fun updateStatusLine() {
        if (lastAudioRouteLabel.isBlank()) return
        val cur = statusView.text.toString()
        val base = cur.substringBefore(" | Âm thanh:").ifBlank { cur }
        statusView.text = "$base | $lastAudioRouteLabel"
        updateVolumeHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        subtitleView = findViewById(R.id.subtitleView)
        hudView = findViewById(R.id.hudView)
        diagView = findViewById(R.id.diagView)
        hudRow = findViewById(R.id.hudRow)
        delayMinusButton = findViewById(R.id.delayMinusButton)
        delayPlusButton = findViewById(R.id.delayPlusButton)
        fsDelayMinusButton = findViewById(R.id.fsDelayMinusButton)
        fsDelayPlusButton = findViewById(R.id.fsDelayPlusButton)
        // v1.0.7 — the canvas seed is the Settings base delay, not a
        // hard-coded 5000 ms any more.
        lastAppliedDelayMs = try { Prefs.baseDelayMs(this) } catch (_: Exception) { 5000L }
        statusView = findViewById(R.id.statusView)
        volumeHintView = findViewById(R.id.callVolumeHint)
        levelBar = findViewById(R.id.levelBar)
        levelValue = findViewById(R.id.levelValue)
        delayToggleButton = findViewById(R.id.delayToggleButton)
        subtitleToggleButton = findViewById(R.id.subtitleToggleButton)
        expandButton = findViewById(R.id.expandButton)
        collapseButton = findViewById(R.id.collapseButton)
        fsDelayToggleButton = findViewById(R.id.fsDelayToggleButton)
        fullscreenBar = findViewById(R.id.fullscreenBar)
        urlRow = findViewById(R.id.urlRow)
        dubRow = findViewById(R.id.dubRow)
        fileRow = findViewById(R.id.fileRow)
        micRow = findViewById(R.id.micRow)
        errorsRow = findViewById(R.id.errorsRow)
        statusRow = findViewById(R.id.statusRow)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadWithOverviewMode = true
        // v1.0.11 — the page-side end of the WebAudio tap. Must be
        // added before any page loads, so TapJs can call back from
        // the first video it attaches to.
        webView.addJavascriptInterface(DubLiveAudioBridge(), "DubLiveAudio")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(DelayJs.INJECT, null)
                // v1.0.11 — inject the tap too. A fresh page means a
                // fresh AudioContext + video element; the tap's own
                // scan re-attaches automatically, so nothing else is
                // needed here (the service keeps running through a
                // reload — only the page side restarts).
                view?.evaluateJavascript(TapJs.INJECT, null)
                // v1.0.11 — if dubbing already runs in tap mode (e.g.
                // the user started it on a blank page and only now
                // opened a film URL), wake the tap on the new page:
                // without this the scan never runs and the session
                // sits silent with no PCM and no fallback.
                if (dubbing && tapMode) {
                    view?.evaluateJavascript("window.DubLiveTapResume()", null)
                }
                applyBaseDelayToWebView()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) view?.loadUrl(url)
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                // v1.0.6 (bổ sung) — LÝ DO THẬT khiến "không xem full màn
                // hình được": khi trễ hình bật, DelayJs đặt thẻ video thật
                // opacity 0 và chỉ hiện canvas trễ đè lên TRONG TRANG.
                // Bấm toàn màn hình thì trình duyệt đưa THẺ VIDEO (đang
                // opacity 0) lên surface toàn màn hình, canvas ở lại
                // trong trang — toàn màn hình chỉ thấy MÀN ĐEN. Vì vậy
                // TRƯỚC khi hiện toàn màn hình phải TẮT trễ hình để
                // video trở lại opacity 1 (canvas ẩn): video xem được
                // bình thường, chỉ là lúc này giọng dịch đi sau hình
                // (đã có Toast cảnh báo bên dưới). delayOn KHÔNG đổi ở
                // đây — nó là lựa chọn của user, để onHide bật lại đúng
                // theo nó.
                try {
                    webView.evaluateJavascript(
                        "window.DubLiveSetEnabled(false)", null
                    )
                } catch (_: Exception) {
                }
                // v1.0.9 — site fullscreen shows the LIVE video: the
                // delayed original audio and the voice hold must go
                // live too (restored on exit, see onHideCustomView).
                setServiceDelayEnabled(false)
                // v1.0.11 — the tap's DelayNode follows the picture:
                // live picture => live original audio (delay 0).
                if (tapMode) {
                    try {
                        webView.evaluateJavascript("window.DubLiveTapSetDelay(0)" /* seconds */, null)
                    } catch (_: Exception) {
                    }
                }
                (window.decorView as ViewGroup).addView(
                    view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                hudRow.visibility = if (dubbing) View.VISIBLE else View.GONE
                // v1.0.6 — fullscreen: the custom view is added to
                // decorView, ON TOP of the activity layout, so the delay
                // canvas stays behind in the page and the Android subtitle
                // + HUD (children of the activity layout) would be covered
                // — bringToFront() alone cannot help, it only reorders
                // inside their own parent. Move both overlays onto
                // decorView itself (subtitle at the bottom, HUD at the
                // top) so they stay visible over the fullscreen video,
                // and warn plainly that the video delay does NOT run in
                // fullscreen: the dub voice will lag the picture there.
                // (No canvas is drawn into fullscreen in this version.)
                subtitleSavedParent = moveOverlayToDecor(
                    subtitleView, android.view.Gravity.BOTTOM
                )
                // v1.0.7 — the whole HUD row (delay text + ±1s buttons)
                // moves together, so the manual buttons stay usable over
                // the web page's fullscreen too.
                hudSavedParent = moveOverlayToDecor(
                    hudRow, android.view.Gravity.TOP
                )
                subtitleView.bringToFront()
                hudRow.bringToFront()
                Toast.makeText(
                    this@MainActivity,
                    "Xem toàn màn hình thì trễ hình không chạy, giọng dịch sẽ đi sau hình — xem ở chế độ thường để hình khớp giọng. Muốn hình khớp giọng ở màn hình lớn, bấm nút Phóng to của app thay vì nút toàn màn hình của trang web",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onHideCustomView() {
                (window.decorView as ViewGroup).removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                // v1.0.6 — put the overlays back where they belong.
                restoreOverlay(subtitleSavedParent, subtitleView)
                subtitleSavedParent = null
                restoreOverlay(hudSavedParent, hudRow)
                hudSavedParent = null
                // v1.0.6 (bổ sung) — bật trễ hình TRỞ LẠI sau khi thoát
                // toàn màn hình, nhưng CHỈ khi user chưa tự tắt nó bằng
                // nút "Tắt trễ hình" (delayOn vẫn là lựa chọn của user,
                // không bị toàn màn hình làm lệch). Đồng bộ lại chữ nút
                // cho đúng trạng thái thật.
                try {
                    webView.evaluateJavascript(
                        "window.DubLiveSetEnabled($delayOn)", null
                    )
                } catch (_: Exception) {
                }
                // v1.0.9 — delay back on (if the user did not turn it
                // off): the delayed original audio and the voice hold
                // resume with it.
                setServiceDelayEnabled(delayOn)
                // v1.0.11 — the tap's DelayNode follows: back to D
                // (or 0 when the user turned the delay off).
                if (tapMode) {
                    val ms = if (delayOn) lastAppliedDelayMs else 0L
                    try {
                        webView.evaluateJavascript("window.DubLiveTapSetDelay(${ms / 1000.0})", null)
                    } catch (_: Exception) {
                    }
                }
                updateDelayButtons()
            }
        }

        findViewById<Button>(R.id.openButton).setOnClickListener { openUrl() }
        findViewById<Button>(R.id.startDubButton).setOnClickListener { startDub() }
        findViewById<Button>(R.id.stopDubButton).setOnClickListener { stopDub() }
        findViewById<Button>(R.id.micDubButton).setOnClickListener { startMicDub() }
        findViewById<Button>(R.id.liveErrorsButton).setOnClickListener { showLiveErrors() }
        findViewById<Button>(R.id.localFileButton).setOnClickListener {
            startActivity(Intent(this, LocalPlayerActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        delayToggleButton.setOnClickListener { toggleDelay() }
        fsDelayToggleButton.setOnClickListener { toggleDelay() }
        expandButton.setOnClickListener { enterAppFullscreen() }
        collapseButton.setOnClickListener { exitAppFullscreen() }
        // v1.0.7 — manual delay nudge, normal screen and app fullscreen.
        delayMinusButton.setOnClickListener { adjustDelay(-1000L) }
        delayPlusButton.setOnClickListener { adjustDelay(1000L) }
        fsDelayMinusButton.setOnClickListener { adjustDelay(-1000L) }
        fsDelayPlusButton.setOnClickListener { adjustDelay(1000L) }
        // v1.0.5 — hide/show subtitles; the service keeps sending them,
        // the view just stays hidden while the flag is off.
        subtitleToggleButton.setOnClickListener {
            subtitlesVisible = !subtitlesVisible
            if (!subtitlesVisible) subtitleView.visibility = View.GONE
            subtitleToggleButton.setText(
                if (subtitlesVisible) R.string.subtitle_hide else R.string.subtitle_show
            )
        }
    }

    /** v1.0.6 — one delay toggle shared by the normal "Tắt trễ hình"
     * button and the fullscreen bar's copy; both labels stay in sync. */
    private fun toggleDelay() {
        delayOn = !delayOn
        webView.evaluateJavascript("window.DubLiveSetEnabled($delayOn)", null)
        // v1.0.9 — the service must follow: picture live => sound
        // live too (delayed original audio + voice hold both to 0),
        // or the sound would lag the live picture by D.
        setServiceDelayEnabled(delayOn)
        // v1.0.11 — the tap's DelayNode follows the same switch.
        if (tapMode) {
            val ms = if (delayOn) lastAppliedDelayMs else 0L
            try {
                webView.evaluateJavascript("window.DubLiveTapSetDelay(${ms / 1000.0})", null)
            } catch (_: Exception) {
            }
        }
        updateDelayButtons()
    }

    /**
     * v1.0.9 — tell the service the picture delay went on/off, so the
     * delayed original audio and the voice hold follow: when the
     * picture is live (delay off / site fullscreen), the sound must
     * be live too, or it would lag the live picture by D.
     */
    private fun setServiceDelayEnabled(enabled: Boolean) {
        try {
            val intent = Intent(this, DubbingService::class.java).apply {
                action = DubbingService.ACTION_SET_DELAY_ENABLED
                putExtra(DubbingService.EXTRA_ENABLED, enabled)
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "setServiceDelayEnabled failed", e)
        }
    }

    private fun updateDelayButtons() {
        val res = if (delayOn) R.string.delay_off else R.string.delay_on
        delayToggleButton.setText(res)
        fsDelayToggleButton.setText(res)
    }

    /**
     * v1.0.7 — push the Settings base delay ("Trễ hình cơ bản") into
     * the WebView canvas. Used on page load and when dubbing starts, so
     * the canvas never sits on a stale seed while the tracker is still
     * waiting for its first FIFO sample.
     */
    private fun applyBaseDelayToWebView() {
        val seed = try { Prefs.baseDelayMs(this) } catch (_: Exception) { 40000L }
        lastAppliedDelayMs = seed
        hudView.text = "Trễ hình: %.1f s".format(seed / 1000.0)
        try {
            webView.evaluateJavascript("window.DubLiveSetDelay($seed)", null)
        } catch (_: Exception) {
        }
        // v1.0.11 — the same seed drives the tap's DelayNode, so the
        // delayed original audio matches the canvas from the start.
        if (tapMode) {
            try {
                webView.evaluateJavascript("window.DubLiveTapSetDelay(${seed / 1000.0})", null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v1.0.7 — manual delay nudge from the "Trễ −1s / +1s" buttons.
     * While dubbing: the service's tracker shifts its EMA by the delta
     * and broadcasts TYPE_HUD — the HUD branch forwards it to the
     * canvas, and later automatic samples keep EMA-ing from this manual
     * value instead of pulling it back. While NOT dubbing: nudge the
     * base seed itself (Settings "Trễ hình cơ bản") and apply it to the
     * canvas at once, so the next session starts from the tuned value.
     */
    private fun adjustDelay(deltaMs: Long) {
        if (dubbing) {
            try {
                val intent = Intent(this, DubbingService::class.java).apply {
                    action = DubbingService.ACTION_ADJUST_DELAY
                    putExtra(DubbingService.EXTRA_DELAY_DELTA_MS, deltaMs)
                }
                startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "adjustDelay failed", e)
            }
        } else {
            // v1.0.23 — TRỄ CỐ ĐỊNH 40 s: nudge tay trong 35–45 s.
            val sec = ((Prefs.baseDelayMs(this) / 1000).toInt() +
                (deltaMs / 1000).toInt()).coerceIn(
                    Prefs.FIXED_DELAY_MIN_SEC, Prefs.FIXED_DELAY_MAX_SEC
                )
            Prefs.setBaseDelaySec(this, sec)
            val ms = sec * 1000L
            lastAppliedDelayMs = ms
            try {
                webView.evaluateJavascript("window.DubLiveSetDelay($ms)", null)
            } catch (_: Exception) {
            }
            if (tapMode) {
                try {
                    webView.evaluateJavascript("window.DubLiveTapSetDelay(${ms / 1000.0})", null)
                } catch (_: Exception) {
                }
            }
            hudView.text = "Trễ hình: %.1f s".format(ms / 1000.0)
            hudRow.visibility = View.VISIBLE
            Toast.makeText(this, getString(R.string.base_delay_set, sec), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * v1.0.6 — toàn màn hình CỦA APP ("Phóng to"). The web page's own
     * fullscreen button moves the <video> onto a native surface and
     * leaves the delay canvas behind in the page, so picture and dub
     * can NEVER match there — an Android WebView limit, not fixable by
     * drawing more. This mode instead hides only the app's chrome
     * (address bar, buttons, status, level meter); the WebView stays
     * in the page, so its delay canvas keeps running and the picture
     * stays in sync with the dub voice. Immersive sticky hides the
     * system status/navigation bars too.
     */
    private fun enterAppFullscreen() {
        if (appFullscreen || customView != null) return
        appFullscreen = true
        urlRow.visibility = View.GONE
        dubRow.visibility = View.GONE
        fileRow.visibility = View.GONE
        micRow.visibility = View.GONE
        errorsRow.visibility = View.GONE
        statusRow.visibility = View.GONE
        fullscreenBar.visibility = View.VISIBLE
        try { supportActionBar?.hide() } catch (_: Exception) { }
        applyImmersiveFlags()
    }

    /** v1.0.6 — leave the app's fullscreen: chrome comes back, the
     * video delay is untouched (it kept running the whole time). */
    private fun exitAppFullscreen() {
        if (!appFullscreen) return
        appFullscreen = false
        urlRow.visibility = View.VISIBLE
        dubRow.visibility = View.VISIBLE
        fileRow.visibility = View.VISIBLE
        micRow.visibility = View.VISIBLE
        errorsRow.visibility = View.VISIBLE
        statusRow.visibility = View.VISIBLE
        fullscreenBar.visibility = View.GONE
        try { supportActionBar?.show() } catch (_: Exception) { }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun applyImmersiveFlags() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun openUrl() {
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        webView.loadUrl(url)
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /** RECORD_AUDIO is mandatory; POST_NOTIFICATIONS (Android 13+) is best-effort. */
    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    /**
     * v1.0.23 — tell the page a dub session started / stopped, so the
     * delay canvas + pre-roll overlay only run while dubbing. Before
     * the session the page shows its video live (a 40 s delayed
     * picture + live audio while merely browsing would be 40 s out
     * of sync). Starting also restarts the 40 s pre-roll (the JS
     * clears stale frames and ramps the effective delay 0 -> D).
     */
    private fun setWebDubbingActive(active: Boolean) {
        try {
            webView.evaluateJavascript("window.DubLiveSetDubbing($active)", null)
        } catch (_: Exception) {
        }
    }

    /**
     * v1.0.11 — tap-first start: NO screen-capture dialog. The service
     * starts in TAP_WEB mode (the film sound comes from the page, the
     * dub voice plays on the MEDIA stream — loud, no echo because no
     * capture runs). If the page blocks the tap (CORS/DRM), the service
     * broadcasts TYPE_NEED_PROJECTION and this activity launches the
     * capture dialog as a fallback (see projectionLauncher).
     * RECORD_AUDIO is still requested first: the mic fallback needs it.
     */
    private fun startDub() {
        if (Prefs.geminiKeys(this).isEmpty()) {
            Toast.makeText(this, R.string.need_key_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (dubbing) return
        if (!hasRecordAudio()) {
            pendingMicAfterPermission = false
            permissionLauncher.launch(requiredPermissions())
            return
        }
        startTapDub()
    }

    /** v1.0.11 — start the service in WebAudio-tap mode. */
    private fun startTapDub() {
        val intent = Intent(this, DubbingService::class.java).apply {
            action = DubbingService.ACTION_START_TAP
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService (tap) failed, retrying with startService", e)
            try {
                startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startService (tap) also failed", e2)
                Toast.makeText(this, R.string.service_failed, Toast.LENGTH_LONG).show()
                return
            }
        }
        dubbing = true
        tapMode = true
        tapActive = false
        pendingTapStart = true
        lastAudioRouteLabel = ""
        currentAudioMode = DubbingService.AUDIO_MODE_MEDIA
        updateVolumeHint()
        statusView.text = "Trạng thái: Đang thử lấy tiếng trong trang…"
        levelBar.progress = 0
        levelValue.text = "0"
        // Full volume at start (see startMicService): the service ducks
        // the tap gain only after the first dubbed speech plays.
        setOriginalVolume(1.0f)
        hudRow.visibility = View.VISIBLE
        // Push the base-delay seed into the canvas AND the tap now, so
        // the picture and the delayed original audio wait the right
        // amount from the first second.
        applyBaseDelayToWebView()
        // v1.0.23 — start the 40 s pre-roll on the page (canvas +
        // wait overlay); before this the page showed live video.
        setWebDubbingActive(true)
    }

    /** Mic mode: no screen-capture dialog, no MediaProjection token. */
    private fun startMicDub() {
        if (Prefs.geminiKeys(this).isEmpty()) {
            Toast.makeText(this, R.string.need_key_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (dubbing) return
        if (!hasRecordAudio()) {
            pendingMicAfterPermission = true
            permissionLauncher.launch(requiredPermissions())
            return
        }
        startMicService()
        Toast.makeText(this, R.string.mic_hint, Toast.LENGTH_LONG).show()
    }

    private fun startMicService() {
        val intent = Intent(this, DubbingService::class.java).apply {
            action = DubbingService.ACTION_START_MIC
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService (mic) failed, retrying with startService", e)
            try {
                startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startService (mic) also failed", e2)
                Toast.makeText(this, R.string.service_failed, Toast.LENGTH_LONG).show()
                return
            }
        }
        dubbing = true
        currentAudioMode = DubbingService.AUDIO_MODE_CALL
        updateVolumeHint()
        statusView.text = "Trạng thái: Đang nối Gemini Live…"
        levelBar.progress = 0
        levelValue.text = "0"
        // v1.0.2: keep the original at FULL volume at start. The old code
        // ducked it to 15% here, which starved the VAD (the capture
        // records the ducked sound). The service ducks it only after the
        // first dubbed speech actually plays.
        setOriginalVolume(1.0f)
        hudRow.visibility = View.VISIBLE
        // v1.0.7 — push the base-delay seed into the canvas now, so the
        // picture waits the right amount from the first second instead
        // of sitting on the page-load seed until the first sample.
        applyBaseDelayToWebView()
        // v1.0.23 — start the 40 s pre-roll on the page.
        setWebDubbingActive(true)
    }

    /**
     * Android 16 (and OriginOS) defaults the capture scope to "share a single
     * app". There is no API to force whole-screen sharing, so tell the user
     * in words first — once only, remembered in Prefs.
     */
    private fun showShareTipThenProjection() {
        if (Prefs.shareTipShown(this)) {
            launchProjection()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.share_tip_title)
            .setMessage(R.string.share_tip_message)
            .setPositiveButton(R.string.share_tip_ok) { _, _ ->
                Prefs.setShareTipShown(this)
                launchProjection()
            }
            .setNegativeButton(R.string.share_tip_cancel, null)
            .setCancelable(true)
            .show()
    }

    private fun launchProjection() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch screen-capture intent", e)
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
            startMicService()
        }
    }

    /**
     * On Android 16 the projection result can arrive while this activity is
     * not resumed yet, so startForegroundService() may throw
     * ForegroundServiceStartNotAllowedException even though the user just
     * granted capture. Retry once after a short delay; if that also fails,
     * fall back to mic mode instead of crashing.
     */
    private fun startProjectionService(intent: Intent, isRetry: Boolean) {
        try {
            startForegroundService(intent)
            dubbing = true
            currentAudioMode = DubbingService.AUDIO_MODE_CALL
            updateVolumeHint()
            statusView.text = "Trạng thái: Đang nối Gemini Live…"
            levelBar.progress = 0
            levelValue.text = "0"
            // v1.0.2: full volume at start (see startMicService).
            setOriginalVolume(1.0f)
            hudRow.visibility = View.VISIBLE
            // v1.0.7 — push the base-delay seed into the canvas now.
            applyBaseDelayToWebView()
            // v1.0.23 — start the 40 s pre-roll on the page.
            setWebDubbingActive(true)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService (projection) failed, retry=$isRetry", e)
            if (!isRetry) {
                uiHandler.postDelayed({ startProjectionService(intent, isRetry = true) }, 600)
            } else {
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
                startMicService()
            }
        }
    }

    private fun stopDub() {
        try {
            val intent = Intent(this, DubbingService::class.java).apply {
                action = DubbingService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "stopDub failed", e)
        }
        dubbing = false
        // v1.0.23 — back to live video on the page (canvas + wait
        // overlay off).
        setWebDubbingActive(false)
        pendingTapStart = false
        fallbackPending = false
        // v1.0.11 — bypass the tap delay so the video's own audio
        // returns to normal at once (the service also stops, but the
        // page side must be told directly).
        if (tapMode) {
            try {
                webView.evaluateJavascript("window.DubLiveTapStop()", null)
            } catch (_: Exception) {
            }
            tapMode = false
            tapActive = false
        }
        currentAudioMode = ""
        lastAudioRouteLabel = ""
        updateVolumeHint()
    }

    /**
     * v1.0.4 — "Xem lỗi Live": a scrollable dialog with the full history
     * (up to 8 errors: time, model, key number, full message). The user
     * screenshots this instead of the one-line status, so the FIRST error
     * (usually translate-preview key 1) is visible instead of only the
     * last one. Works even when the service is not running — the last
     * received history stays on screen.
     */
    private fun showLiveErrors() {
        // v1.0.10 — the delay diagnostics ride along: the same
        // numbers as under the HUD delay number, so a screenshot of
        // this dialog shows whether the delay is actually updating.
        val diagPart = if (lastDiagText.isBlank()) {
            ""
        } else {
            "\n\n— Chẩn đoán trễ —\n$lastDiagText"
        }
        val textView = TextView(this).apply {
            text = if (liveErrorHistoryText.isBlank()) {
                getString(R.string.live_errors_empty) + diagPart
            } else {
                liveErrorHistoryText + diagPart
            }
            setPadding(32, 24, 32, 24)
            textSize = 13f
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.live_errors_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * v1.0.6 — move an overlay (subtitle / HUD) from the activity layout
     * onto decorView so it renders above a fullscreen custom view, which
     * lives on decorView itself. Returns the saved parent/index/params
     * for restoreOverlay(), or null when the view has no parent.
     */
    private fun moveOverlayToDecor(
        view: View, gravity: Int
    ): Triple<ViewGroup, Int, ViewGroup.LayoutParams>? {
        val parent = view.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(view)
        val params = view.layoutParams
        parent.removeView(view)
        val decor = window.decorView as ViewGroup
        val margin = (16 * resources.displayMetrics.density).toInt()
        val lp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity
        ).apply { setMargins(margin, margin, margin, margin) }
        decor.addView(view, lp)
        return Triple(parent, index, params)
    }

    /** v1.0.6 — put an overlay moved by moveOverlayToDecor back. */
    private fun restoreOverlay(
        saved: Triple<ViewGroup, Int, ViewGroup.LayoutParams>?, view: View
    ) {
        if (saved == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        try {
            saved.first.addView(view, saved.second, saved.third)
        } catch (_: Exception) {
            try { saved.first.addView(view) } catch (_: Exception) { }
        }
    }

    /**
     * v1.0.11 — duck the original audio. In tap mode the sound the user
     * hears comes from the tap's DelayNode, so the duck drives its gain
     * (DubLiveTapSetDuck); in capture mode it comes from the canvas
     * (DubLiveSetVolume) as before. The service's duck ticker sends
     * TYPE_DUCK in both modes — this is where the path splits.
     */
    private fun setOriginalVolume(vol: Float) {
        try {
            if (tapMode) {
                webView.evaluateJavascript("window.DubLiveTapSetDuck($vol)", null)
            } else {
                webView.evaluateJavascript("window.DubLiveSetVolume($vol)", null)
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        // v1.0.6 — system UI flags are reset when the activity pauses
        // (Home, notification shade); re-apply them if still fullscreen.
        if (appFullscreen) applyImmersiveFlags()
        val filter = IntentFilter(DubbingService.ACTION_DUB_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dubReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dubReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(dubReceiver) } catch (_: Exception) { }
    }

    override fun onBackPressed() {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            return
        }
        // v1.0.6 — Back leaves the app's fullscreen first.
        if (appFullscreen) {
            exitAppFullscreen()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        try { uiHandler.removeCallbacksAndMessages(null) } catch (_: Exception) { }
        try { webView.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
