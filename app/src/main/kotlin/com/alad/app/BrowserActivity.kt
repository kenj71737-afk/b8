package com.alad.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.util.Base64
import android.widget.Toast
import com.alad.app.core.audio.BrowserAudioBridge
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alad.app.core.service.AudioDubbingForegroundService
import com.alad.app.data.repository.DubbingSessionRepository

/**
 * In-app browser for ALAD. For compatible HTML5 video elements, it keeps audio
 * playing live while painting buffered video frames with a configurable delay.
 * DRM/protected video surfaces may refuse frame capture; those remain undelayed.
 */
class BrowserActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var rootLayout: LinearLayout
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var customVideoContainer: FrameLayout? = null
    private lateinit var address: EditText
    private lateinit var delayLabel: TextView
    private var delayMs = 5000
    private var autoSyncEnabled = true
    private lateinit var modeButton: Button
    private lateinit var toolbarView: LinearLayout
    private lateinit var controlsView: LinearLayout
    private lateinit var fullscreenButton: Button
    private var fullscreenHud: LinearLayout? = null
    private var isFullscreen = false
    // FIX16: video seek (progress) controls. These move the video's own
    // currentTime and are separate from the dubbing-delay SeekBar above.
    private lateinit var videoSeekBack: Button
    private lateinit var videoSeekBar: SeekBar
    private lateinit var videoSeekFwd: Button
    private lateinit var videoSeekTime: TextView
    private lateinit var fsVideoSeekBack: Button
    private lateinit var fsVideoSeekBar: SeekBar
    private lateinit var fsVideoSeekFwd: Button
    private lateinit var fsVideoSeekTime: TextView
    private var seekScrubbing = false
    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekPoll = object : Runnable {
        override fun run() {
            refreshVideoProgress()
            seekHandler.postDelayed(this, 500)
        }
    }
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncPoll = object : Runnable {
        override fun run() {
            if (autoSyncEnabled) {
                val measured = DubbingSessionRepository.estimatedDubbingDelayMs.value
                if (measured != delayMs) {
                    delayMs = measured
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    updateDelayUiAndWebView()
                    updateFullscreenHud()
                }
            }
            syncHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        DubbingSessionRepository.setAutoSyncEnabled(true)
        DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
        val sharedUrl = extractIncomingUrl(intent)
        openUrl(sharedUrl ?: "https://www.youtube.com")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIncomingUrl(intent)?.let { openUrl(it) }
    }

    private fun extractIncomingUrl(source: android.content.Intent?): String? {
        if (source == null) return null
        val candidate = when (source.action) {
            android.content.Intent.ACTION_SEND ->
                source.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_VIEW -> source.dataString
            else -> null
        }?.trim()?.let { raw ->
            // Some share sheets append a title or description after the URL.
            Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value ?: raw
        }
        return candidate?.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
    }

    private fun buildUi() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val root = rootLayout

        toolbarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 22f
            setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() }
        }
        address = EditText(this).apply {
            hint = "Nhập địa chỉ website"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(35, 45, 62))
            setPadding(dp(10), 0, dp(10), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openUrl(text.toString())
                    true
                } else false
            }
        }
        val go = Button(this).apply {
            text = "Đi"
            setOnClickListener { openUrl(address.text.toString()) }
        }
        toolbarView.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbarView.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        toolbarView.addView(go, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(toolbarView)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    // Native website fullscreen detaches the video from the DOM/WebView.
                    // ALAD's delayed-frame canvas cannot follow that detached surface.
                    // Reject this transition so the HTML video stays in the WebView and
                    // the existing ALAD delay pipeline remains attached.
                    callback?.onCustomViewHidden()
                    runOnUiThread {
                        Toast.makeText(
                            this@BrowserActivity,
                            "Để giữ độ trễ, hãy dùng nút TOÀN MÀN HÌNH của ALAD, không dùng nút fullscreen của website.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onHideCustomView() {
                    exitCustomVideoView()
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun onPcm(base64Pcm: String) {
                    BrowserAudioBridge.onPcmBase64(base64Pcm)
                }
                @JavascriptInterface fun onAudioStatus(message: String) {
                    BrowserAudioBridge.onStatus(message)
                    runOnUiThread { Toast.makeText(this@BrowserActivity, message, Toast.LENGTH_SHORT).show() }
                }
            }, "ALADAudio")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    BrowserAudioBridge.onPageChanged()
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    address.setText(url ?: "")
                    injectDelayScript()
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        controlsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(5), dp(14), dp(8))
            setBackgroundColor(Color.rgb(20, 29, 46))
        }
        delayLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Độ trễ âm thanh + hình ảnh: 5 giây 0 ms"
        }
        val seek = SeekBar(this).apply {
            // FIX31: 1700 steps x 10 ms = selectable range 3,000–20,000 ms
            // (was 700 steps / 10,000 ms).
            max = 1700
            progress = 200 // 5,000 ms by default
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && autoSyncEnabled) {
                        autoSyncEnabled = false
                        DubbingSessionRepository.setAutoSyncEnabled(false)
                        modeButton.text = "Đồng bộ: THỦ CÔNG"
                    }
                    delayMs = 3000 + value * 10
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    val seconds = delayMs / 1000
                    val millis = delayMs % 1000
                    delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây ${millis} ms"
                    val delaySeconds = delayMs / 1000.0
                    webView.evaluateJavascript(
                        "if(window.__aladSetDelay){window.__aladSetDelay($delaySeconds)}", null
                    )
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 20).forEach { seconds ->
            presets.addView(Button(this).apply {
                text = "${seconds}s"
                isAllCaps = false
                setOnClickListener {
                    autoSyncEnabled = false
                    DubbingSessionRepository.setAutoSyncEnabled(false)
                    modeButton.text = "Đồng bộ: THỦ CÔNG"
                    delayMs = seconds * 1000
                    DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
                    seek.progress = (delayMs - 3000) / 10
                    delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây 0 ms"
                    webView.evaluateJavascript(
                        "if(window.__aladSetDelay){window.__aladSetDelay(${seconds}.0)}", null
                    )
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        modeButton = Button(this).apply {
            text = "Đồng bộ: TỰ ĐỘNG"
            isAllCaps = false
            setOnClickListener {
                autoSyncEnabled = !autoSyncEnabled
                DubbingSessionRepository.setAutoSyncEnabled(autoSyncEnabled)
                text = if (autoSyncEnabled) "Đồng bộ: TỰ ĐỘNG" else "Đồng bộ: THỦ CÔNG"
                if (autoSyncEnabled) {
                    delayMs = DubbingSessionRepository.estimatedDubbingDelayMs.value
                    seek.progress = ((delayMs - 3000) / 10).coerceIn(0, 1700)
                    updateDelayUiAndWebView()
                }
            }
        }
        val info = TextView(this).apply {
            text = "Cách dùng: bắt đầu dịch tại màn hình ALAD trước, sau đó dùng Chia sẻ từ YouTube/Facebook và chọn ALAD. Video DRM hoặc website chặn phát trong WebView có thể không hỗ trợ."
            setTextColor(Color.rgb(255, 214, 120))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        }
        controlsView.addView(info)
        controlsView.addView(modeButton)
        controlsView.addView(delayLabel)
        controlsView.addView(seek)
        controlsView.addView(presets)
        // FIX16: video seek row (tua video). This is the video's own
        // progress, not the dubbing delay: −10s / scrub bar / +10s.
        val videoSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        videoSeekBack = Button(this).apply {
            text = "−10s"
            isAllCaps = false
            setOnClickListener { seekVideoBy(-10.0) }
        }
        videoSeekBar = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(videoSeekBarListener())
        }
        videoSeekFwd = Button(this).apply {
            text = "+10s"
            isAllCaps = false
            setOnClickListener { seekVideoBy(10.0) }
        }
        videoSeekTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "— / —"
        }
        videoSeekRow.addView(videoSeekBack, LinearLayout.LayoutParams(dp(56), dp(42)))
        videoSeekRow.addView(videoSeekBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        videoSeekRow.addView(videoSeekFwd, LinearLayout.LayoutParams(dp(56), dp(42)))
        videoSeekRow.addView(videoSeekTime, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(6), 0, 0, 0) })
        controlsView.addView(videoSeekRow)
        fullscreenButton = Button(this).apply {
            text = "⛶ XEM TOÀN MÀN HÌNH"
            isAllCaps = false
            setOnClickListener { setFullscreen(true) }
        }
        controlsView.addView(fullscreenButton)
        root.addView(controlsView)
        setContentView(root)
        ensureFullscreenHud()
    }

    private fun ensureFullscreenHud() {
        if (fullscreenHud != null) return
        fun hudButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            minWidth = dp(42)
            setOnClickListener { action() }
        }
        // FIX40: the fullscreen HUD is a single thin row now — the delay
        // buttons (AUTO / THỦ CÔNG / − / +) and the delay label covered too
        // much of the picture, so they live only in the normal controls.
        // Fullscreen keeps the seek row plus one small THOÁT button.
        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(3), dp(5), dp(3))
            setBackgroundColor(Color.argb(210, 10, 15, 25))
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fsVideoSeekBack = Button(this).apply {
            text = "−10s"
            isAllCaps = false
            textSize = 11f
            minWidth = dp(42)
            setOnClickListener { seekVideoBy(-10.0) }
        }
        fsVideoSeekBar = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(videoSeekBarListener())
        }
        fsVideoSeekFwd = Button(this).apply {
            text = "+10s"
            isAllCaps = false
            textSize = 11f
            minWidth = dp(42)
            setOnClickListener { seekVideoBy(10.0) }
        }
        fsVideoSeekTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "— / —"
        }
        seekRow.addView(fsVideoSeekBack, LinearLayout.LayoutParams(dp(52), dp(40)))
        seekRow.addView(fsVideoSeekBar, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        })
        seekRow.addView(fsVideoSeekFwd, LinearLayout.LayoutParams(dp(52), dp(40)))
        seekRow.addView(fsVideoSeekTime, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(6), 0, 0, 0) })
        // FIX40: the only exit from fullscreen — a small THOÁT at the end
        // of the seek row.
        seekRow.addView(hudButton("THOÁT") { setFullscreen(false) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { setMargins(dp(6), 0, 0, 0) })
        hud.addView(seekRow)
        fullscreenHud = hud
        val decor = window.decorView as ViewGroup
        decor.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            bottomMargin = dp(24)
        })
        hud.visibility = View.GONE
        updateFullscreenHud()
    }

    private fun updateFullscreenHud() {
        // FIX40: no delay label in the fullscreen HUD any more — this only
        // shows/hides the (single-row) HUD.
        fullscreenHud?.let { hud ->
            hud.visibility = if (isFullscreen && customVideoView == null) View.VISIBLE else View.GONE
        }
    }

    // ---- FIX16: video seek (tua video) ----

    private fun videoSeekBarListener() = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
            if (fromUser) updateSeekTimeLabel(value, bar?.max ?: 0)
        }
        override fun onStartTrackingTouch(bar: SeekBar?) {
            seekScrubbing = true
        }
        override fun onStopTrackingTouch(bar: SeekBar?) {
            seekScrubbing = false
            seekVideoTo((bar?.progress ?: 0).toDouble())
        }
    }

    /** Relative seek (e.g. −10s / +10s) on the video element now playing. */
    private fun seekVideoBy(deltaSeconds: Double) {
        webView.evaluateJavascript(
            "window.__aladSeekBy ? window.__aladSeekBy($deltaSeconds) : -1"
        ) { result -> handleSeekResult(result) }
    }

    /** Absolute seek (scrub bar) to [seconds] on the video element now playing. */
    private fun seekVideoTo(seconds: Double) {
        webView.evaluateJavascript(
            "window.__aladSeekTo ? window.__aladSeekTo($seconds) : -1"
        ) { result -> handleSeekResult(result) }
    }

    private fun handleSeekResult(result: String?) {
        val target = result?.trim()?.trim('"')?.toDoubleOrNull() ?: -1.0
        if (target < 0) {
            Toast.makeText(
                this,
                "Không tìm thấy video để tua. Hãy phát video trước rồi thử lại.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        resyncAfterSeek()
        refreshVideoProgress()
    }

    /**
     * The video jumped to a new scene: every timing the dub pipeline holds
     * belongs to the old position. Clear the sync clock, the queued TVB
     * lines and the pending translated audio so nothing from the old scene
     * is spoken over the new picture. The WebAudio/frame delay re-syncs
     * itself in JS (see __aladSeekAt in injectDelayScript).
     */
    private fun resyncAfterSeek() {
        DubbingSessionRepository.clearSubtitle()
        if (DubbingSessionRepository.isDubbingRunning.value) {
            startService(Intent(this, AudioDubbingForegroundService::class.java).apply {
                action = AudioDubbingForegroundService.ACTION_RESYNC
            })
        }
        Toast.makeText(this, "Đã tua — đang đồng bộ lại lồng tiếng…", Toast.LENGTH_SHORT).show()
    }

    /** Ask the page for the video's currentTime/duration and refresh both seek rows. */
    private fun refreshVideoProgress() {
        if (seekScrubbing) return
        webView.evaluateJavascript(
            "window.__aladVideoProgress ? window.__aladVideoProgress() : ''"
        ) { result -> parseAndShowProgress(result) }
    }

    private fun parseAndShowProgress(result: String?) {
        // JS returns "current|duration|paused" or "" when no video is found.
        val raw = result?.trim()?.trim('"').orEmpty()
        val parts = raw.split("|")
        val current = parts.getOrNull(0)?.toDoubleOrNull()
        val duration = parts.getOrNull(1)?.toDoubleOrNull()
        val hasVideo = current != null && duration != null && duration > 0
        setSeekControlsEnabled(hasVideo)
        if (!hasVideo) {
            videoSeekTime.text = "— / —"
            fsVideoSeekTime.text = "— / —"
            return
        }
        val currentSec = current!!.toInt()
        val durationSec = duration!!.toInt()
        if (!seekScrubbing) {
            videoSeekBar.max = durationSec
            videoSeekBar.progress = currentSec
            fsVideoSeekBar.max = durationSec
            fsVideoSeekBar.progress = currentSec
        }
        updateSeekTimeLabel(currentSec, durationSec)
    }

    private fun updateSeekTimeLabel(currentSec: Int, durationSec: Int) {
        val label = "${formatTime(currentSec)} / ${formatTime(durationSec)}"
        videoSeekTime.text = label
        fsVideoSeekTime.text = label
    }

    private fun formatTime(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, secs)
        else "%d:%02d".format(java.util.Locale.US, minutes, secs)
    }

    private fun setSeekControlsEnabled(enabled: Boolean) {
        listOf(
            videoSeekBack, videoSeekFwd, videoSeekBar,
            fsVideoSeekBack, fsVideoSeekFwd, fsVideoSeekBar
        ).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (customVideoView != null) return
        if (enabled) {
            webView.evaluateJavascript(
                "(function(){return window.__aladEnterVideoFullscreen ? window.__aladEnterVideoFullscreen() : false;})()"
            ) { result ->
                if (result == "false" || result == "null") {
                    Toast.makeText(this, "Chưa tìm thấy video. Hãy mở và phát video trước rồi thử lại.", Toast.LENGTH_LONG).show()
                    isFullscreen = false
                    toolbarView.visibility = View.VISIBLE
                    controlsView.visibility = View.VISIBLE
                    setImmersiveMode(false)
                    updateFullscreenHud()
                }
            }
        } else {
            webView.evaluateJavascript("if(window.__aladExitVideoFullscreen) window.__aladExitVideoFullscreen()", null)
        }
        toolbarView.visibility = if (enabled) View.GONE else View.VISIBLE
        controlsView.visibility = if (enabled) View.GONE else View.VISIBLE
        setImmersiveMode(enabled)
        updateFullscreenHud()
        // Keep the existing WebView instance alive: no page reload and no
        // recreation of the HTMLMediaElement/WebAudio delay graph.
    }

    private fun setImmersiveMode(enabled: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            ) else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun exitCustomVideoView() {
        val view = customVideoView ?: return
        try {
            customVideoContainer?.let { rootLayout.removeView(it) }
            (view.parent as? ViewGroup)?.removeView(view)
            webView.visibility = View.VISIBLE
            toolbarView.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            controlsView.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            customVideoCallback?.onCustomViewHidden()
        } finally {
            customVideoView = null
            customVideoCallback = null
            customVideoContainer = null
            setImmersiveMode(isFullscreen)
            updateFullscreenHud()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersiveMode(isFullscreen || customVideoView != null)
    }

    override fun onResume() {
        super.onResume()
        syncHandler.removeCallbacks(syncPoll)
        syncHandler.post(syncPoll)
        seekHandler.removeCallbacks(seekPoll)
        seekHandler.post(seekPoll)
    }

    override fun onPause() {
        syncHandler.removeCallbacks(syncPoll)
        seekHandler.removeCallbacks(seekPoll)
        super.onPause()
    }

    private fun updateDelayUiAndWebView() {
        DubbingSessionRepository.setSelectedMediaDelayMs(delayMs)
        val seconds = delayMs / 1000
        val millis = delayMs % 1000
        delayLabel.text = "Độ trễ âm thanh + hình ảnh: ${seconds} giây ${millis} ms"
        webView.evaluateJavascript(
            "if(window.__aladSetDelay){window.__aladSetDelay(${delayMs / 1000.0})}", null
        )
        updateFullscreenHud()
    }

    private fun injectDelayScript() {
        val js = """
            (function(){
              if (window.__aladDelayInstalled) {
                if (window.__aladSetDelay) window.__aladSetDelay(${delayMs / 1000.0});
                return;
              }
              window.__aladDelayInstalled = true;
              window.__aladDelaySeconds = ${delayMs / 1000.0};
              // FIX16: performance.now() of the last video seek (0 = never).
              // The frame/audio delay rebuilds from 0 after a seek instead of
              // replaying the old scene for a full delay (see tick below).
              window.__aladSeekAt = 0;
              window.__aladSetDelay = function(v) {
                // FIX35: kẹp 10s -> 20s. FIX31 nâng trần trễ lên 20s ở mọi
                // chỗ nhưng sót chỗ này — hình + tiếng gốc chỉ trễ tối đa
                // 10s trong khi giọng dịch giữ theo trễ đầy đủ (tới 20s),
                // nên giọng trễ hơn hình (trễ - 10s): ở trễ 12,9s giọng
                // tới sau hình gần 3s, ở trễ 20s thì sau tới 10s — giống
                // như giọng bị mất dù phụ đề đã hiện. DelayNode đã 22s và
                // bộ đệm khung hình đã theo công thức FIX31 nên 20s chạy được.
                const n = Math.max(0, Math.min(20, Number(v) || 0));
                window.__aladDelaySeconds = n;
                for (const a of window.__aladAudioStates || []) {
                  try { a.delay.delayTime.setTargetAtTime(n, a.ctx.currentTime, 0.025); } catch(e) {}
                }
              };
              // ALAD video-only fullscreen: enlarge the currently playing video
              // player, hide recommendation/page siblings, but keep the same DOM
              // video element and WebAudio/canvas delay graph alive.
              // FIX16: shared video lookup for fullscreen + seek. Prefer the
              // playing video; fall back to the largest loaded video (a
              // paused video is still seekable and can go fullscreen).
              window.__aladFindVideo = function() {
                const all = Array.from(document.querySelectorAll('video'))
                  .filter(v => v.readyState >= 2 && isFinite(v.duration) && v.duration > 0);
                if (!all.length) return null;
                const playing = all.filter(v => !v.paused && !v.ended);
                const pool = playing.length ? playing : all;
                pool.sort((a,b) => {
                  const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
                  return (b.videoWidth*b.videoHeight + br.width*br.height) -
                         (a.videoWidth*a.videoHeight + ar.width*ar.height);
                });
                return pool[0];
              };
              // FIX16: video progress for the native seek bars.
              // Returns "currentTime|duration|paused" or "" when no video.
              window.__aladVideoProgress = function() {
                const v = window.__aladFindVideo();
                if (!v) return '';
                return v.currentTime + '|' + v.duration + '|' + (v.paused ? '1' : '0');
              };
              // FIX16: absolute seek. Returns the new currentTime, or -1 when
              // no video is found. Marks __aladSeekAt so the delay pipeline
              // drops its stale frames/audio and re-syncs (see tick below).
              window.__aladSeekTo = function(seconds) {
                const v = window.__aladFindVideo();
                if (!v) return -1;
                const target = Math.max(0, Math.min(v.duration - 0.05, Number(seconds) || 0));
                v.currentTime = target;
                window.__aladSeekAt = performance.now();
                return target;
              };
              // FIX16: relative seek (+10s / -10s). Returns the new
              // currentTime, or -1 when no video is found.
              window.__aladSeekBy = function(delta) {
                const v = window.__aladFindVideo();
                if (!v) return -1;
                return window.__aladSeekTo(v.currentTime + (Number(delta) || 0));
              };
              let aladVideoFs = null;
              window.__aladEnterVideoFullscreen = function() {
                const video = window.__aladFindVideo();
                if (!video) return false;
                let player = video.closest('.html5-video-player, .video-js, .jwplayer, .plyr, .player');
                if (!player) player = video.parentElement || video;
                const saved=[];
                const saveStyle=(el)=>saved.push([el,el.getAttribute('style')]);
                let node=player;
                while(node && node !== document.body && node.parentElement) {
                  const parent=node.parentElement;
                  Array.from(parent.children).forEach(sib=>{
                    if(sib!==node && !(sib.tagName==='CANVAS' && sib.style.zIndex==='2147483000')) {
                      saveStyle(sib);
                      sib.style.setProperty('display','none','important');
                    }
                  });
                  node=parent;
                }
                // FIX16: lock page scrolling while fullscreen, and force the
                // wrappers between the video and its player to fill the
                // player, so the video truly fills the window instead of
                // staying small inside a fixed-size inner container.
                saveStyle(document.documentElement);
                document.documentElement.style.setProperty('overflow','hidden','important');
                saveStyle(document.body);
                document.body.style.setProperty('overflow','hidden','important');
                let mid=video.parentElement;
                while(mid && mid!==player) {
                  saveStyle(mid);
                  mid.style.setProperty('width','100%','important');
                  mid.style.setProperty('height','100%','important');
                  mid.style.setProperty('max-width','none','important');
                  mid.style.setProperty('max-height','none','important');
                  mid=mid.parentElement;
                }
                saveStyle(player);
                player.style.setProperty('position','fixed','important');
                player.style.setProperty('inset','0','important');
                player.style.setProperty('width','100vw','important');
                player.style.setProperty('height','100vh','important');
                player.style.setProperty('max-width','none','important');
                player.style.setProperty('max-height','none','important');
                player.style.setProperty('margin','0','important');
                player.style.setProperty('z-index','2147482000','important');
                player.style.setProperty('background','#000','important');
                player.style.setProperty('overflow','hidden','important');
                saveStyle(video);
                video.style.setProperty('width','100%','important');
                video.style.setProperty('height','100%','important');
                video.style.setProperty('max-width','100%','important');
                video.style.setProperty('max-height','100%','important');
                video.style.setProperty('object-fit','contain','important');
                video.style.setProperty('object-position','center','important');
                video.style.setProperty('opacity','0.01','important');
                aladVideoFs={player:player,video:video,saved:saved};
                window.__aladVideoFullscreen=true;
                return true;
              };
              window.__aladExitVideoFullscreen = function() {
                if (!aladVideoFs) return;
                for(let i=aladVideoFs.saved.length-1;i>=0;i--) {
                  const [el,style]=aladVideoFs.saved[i];
                  if(style===null) el.removeAttribute('style'); else el.setAttribute('style',style);
                }
                aladVideoFs=null;
                window.__aladVideoFullscreen=false;
              };
              window.__aladAudioStates = [];
              const videoStates = new WeakMap();
              const audioStates = new WeakMap();
              const notify = (m) => { try { if(window.ALADAudio) ALADAudio.onAudioStatus(m); } catch(e){} };

              // HTMLMediaElement -> WebAudio. ScriptProcessor is used only as a
              // compatibility PCM tap; audio playback itself uses DelayNode.
              function attachAudio(v) {
                if (!v || audioStates.has(v) || !window.AudioContext && !window.webkitAudioContext) return;
                const AC = window.AudioContext || window.webkitAudioContext;
                let ctx, source, delay, gain, tap, silent;
                try {
                  ctx = new AC();
                  source = ctx.createMediaElementSource(v);
                  delay = ctx.createDelay(22.0); // FIX31: max 12s -> 22s (delay tối đa 20s + 2s dự phòng)
                  delay.delayTime.value = window.__aladDelaySeconds;
                  gain = ctx.createGain(); gain.gain.value = 1.0;
                  tap = ctx.createScriptProcessor(2048, 1, 1);
                  silent = ctx.createGain(); silent.gain.value = 0.0;
                  source.connect(delay); delay.connect(gain); gain.connect(ctx.destination);
                  source.connect(tap); tap.connect(silent); silent.connect(ctx.destination);
                  let lastStatus = 0, nonSilentBlocks = 0, failed = false;
                  tap.onaudioprocess = function(ev) {
                    if (failed) return;
                    const input = ev.inputBuffer.getChannelData(0);
                    let sum = 0;
                    for(let i=0;i<input.length;i+=4) sum += input[i]*input[i];
                    const rms = Math.sqrt(sum / Math.max(1, Math.ceil(input.length/4)));
                    if (rms < 0.00008) {
                      if (ctx.currentTime - lastStatus > 2.0 && nonSilentBlocks === 0) {
                        lastStatus = ctx.currentTime;
                      }
                      return;
                    }
                    nonSilentBlocks++;
                    // Resample browser context rate to Gemini's 16 kHz mono PCM16.
                    const ratio = ctx.sampleRate / 16000;
                    const outLen = Math.max(1, Math.floor(input.length / ratio));
                    const bytes = new Uint8Array(outLen * 2);
                    const view = new DataView(bytes.buffer);
                    for(let j=0;j<outLen;j++) {
                      const srcIndex = Math.min(input.length-1, Math.floor(j*ratio));
                      const x = Math.max(-1, Math.min(1, input[srcIndex]));
                      view.setInt16(j*2, x < 0 ? x*32768 : x*32767, true);
                    }
                    let binary='';
                    for(let k=0;k<bytes.length;k++) binary += String.fromCharCode(bytes[k]);
                    try { if(window.ALADAudio) ALADAudio.onPcm(btoa(binary)); } catch(e) {}
                  };
                  const state = {ctx:ctx, source:source, delay:delay, gain:gain, tap:tap,
                                 silent:silent, nonSilent:()=>nonSilentBlocks};
                  audioStates.set(v,state); window.__aladAudioStates.push(state);
                  let checkTimer = null;
                  const resume = () => {
                    if(ctx.state === 'suspended') ctx.resume().catch(()=>{});
                    if(checkTimer === null) checkTimer = setTimeout(() => {
                      if (nonSilentBlocks === 0 && !failed) {
                        failed = true;
                        try { source.disconnect(); source.connect(ctx.destination); } catch(e) {}
                        try { delay.disconnect(); gain.disconnect(); tap.disconnect(); silent.disconnect(); } catch(e) {}
                        window.__aladDelaySeconds = 0;
                        notify('Trang web chặn âm thanh WebAudio (CORS/DRM). Đã trả về phát gốc; đồng bộ trễ không khả dụng.');
                      } else if (!failed) {
                        notify('Đã nhận âm thanh HTML5: gửi Gemini tức thì và phát trễ.');
                      }
                    }, 2200);
                  };
                  v.addEventListener('play', resume); v.addEventListener('playing', resume);
                  if (!v.paused) resume();
                } catch(e) {
                  try { if(source) { source.disconnect(); source.connect(ctx.destination); } } catch(_) {}
                  try { if(ctx) ctx.close(); } catch(_) {}
                  notify('Không thể lấy âm thanh HTML5: ' + (e && e.message ? e.message : 'không hỗ trợ'));
                }
              }

              function attachVideo(v) {
                if (!v || videoStates.has(v) || v.readyState < 2) return;
                const s = {frames:[], last:0, lastSeekAt:0, canvas:document.createElement('canvas'), ctx:null, active:false};
                const c=s.canvas;
                c.style.cssText='position:fixed;display:none;pointer-events:none;z-index:2147483000;background:#000;object-fit:fill;';
                document.body.appendChild(c); s.ctx=c.getContext('2d',{alpha:false}); videoStates.set(v,s);
                function tick(now) {
                  if (!document.contains(v)) { c.remove(); videoStates.delete(v); return; }
                  const r=v.getBoundingClientRect();
                  // FIX16: after a seek, the buffered frames and the delayed
                  // audio belong to the old scene. Drop the stale frames and
                  // rebuild the delay from 0 as new-position content
                  // accumulates, so audio and video stay in sync through the
                  // ramp instead of replaying the old scene for a full delay.
                  if (s.lastSeekAt !== window.__aladSeekAt) {
                    s.lastSeekAt = window.__aladSeekAt;
                    s.frames.length = 0;
                  }
                  const sinceSeek = window.__aladSeekAt ? (now - window.__aladSeekAt) : 1e9;
                  const effDelay = window.__aladSeekAt
                    ? Math.min(window.__aladDelaySeconds, Math.max(0, sinceSeek) / 1000)
                    : window.__aladDelaySeconds;
                  const fs=(document.fullscreenElement && (document.fullscreenElement===v || document.fullscreenElement.contains(v))) ||
                    (document.webkitFullscreenElement && (document.webkitFullscreenElement===v || document.webkitFullscreenElement.contains(v))) ||
                    v.webkitDisplayingFullscreen===true;
                  // Keep the delayed canvas/audio pipeline active during DOM fullscreen.
                  // Native detached fullscreen is rejected by WebChromeClient above.
                  for(const a of window.__aladAudioStates||[]) { try { a.delay.delayTime.setTargetAtTime(effDelay,a.ctx.currentTime,0.04); } catch(_){} }
                  // FIX31: thưa dần tốc độ chụp khung hình khi trễ dài để
                  // bộ đệm khung hình luôn ~400 khung ở mọi độ trễ (trễ
                  // 20s mà chụp mỗi 30ms thì giữ ~730 canvas, dễ tràn RAM
                  // và sập WebView). Dưới ~10s vẫn chụp mỗi 30ms như cũ;
                  // ở 20s thì mỗi 55ms (~18 khung/giây, vẫn xem được).
                  const capMs = Math.max(30, (window.__aladDelaySeconds + 2) * 1000 / 400);
                  if(r.width>20 && r.height>20 && !v.paused && !v.ended && v.readyState>=2 && now-s.last>=capMs) {
                    s.last=now;
                    const w=Math.min(640,Math.max(2,Math.round(v.videoWidth||r.width)));
                    const h=Math.max(2,Math.round(w*((v.videoHeight||r.height)/Math.max(1,v.videoWidth||r.width))));
                    const f=document.createElement('canvas'); f.width=w; f.height=h;
                    try { f.getContext('2d').drawImage(v,0,0,w,h); s.frames.push({t:now,frame:f});
                      const keep=(window.__aladDelaySeconds+2)*1000; while(s.frames.length&&now-s.frames[0].t>keep)s.frames.shift();
                    } catch(e) {
                      if(!s.captureFailed) notify('Trang web chặn lấy khung hình video. Đã tắt độ trễ để tránh lệch âm thanh và hình ảnh.');
                      s.captureFailed=true;
                      window.__aladDelaySeconds=0;
                      for(const a of window.__aladAudioStates||[]) { try { a.delay.delayTime.setTargetAtTime(0,a.ctx.currentTime,0.02); } catch(_){} }
                    }
                  }
                  if(s.captureFailed) { c.style.display='none'; if(s.active)v.style.opacity=''; s.active=false; requestAnimationFrame(tick); return; }
                  const wanted=now-effDelay*1000; let chosen=null;
                  for(let i=s.frames.length-1;i>=0;i--) if(s.frames[i].t<=wanted){chosen=s.frames[i].frame;break;}
                  if(effDelay>0 && r.width>20 && r.height>20 && !v.paused && !v.ended) {
                    // FIX16: keep the delayed picture at the video's real
                    // aspect ratio instead of stretching it to the video
                    // element's box (distorted in fullscreen when the screen
                    // ratio differs from the video ratio).
                    const vw=v.videoWidth||r.width, vh=v.videoHeight||r.height;
                    const scale=Math.min(r.width/Math.max(1,vw), r.height/Math.max(1,vh));
                    const cw=Math.max(2, vw*scale), ch=Math.max(2, vh*scale);
                    const cx=Math.max(0, r.left+(r.width-cw)/2), cy=Math.max(0, r.top+(r.height-ch)/2);
                    c.style.display='block'; c.style.left=cx+'px'; c.style.top=cy+'px';
                    c.style.width=Math.min(cw,innerWidth)+'px'; c.style.height=Math.min(ch,innerHeight)+'px';
                    if(chosen) { c.width=chosen.width; c.height=chosen.height; s.ctx.drawImage(chosen,0,0); }
                    else { c.width=Math.max(2,Math.round(cw)); c.height=Math.max(2,Math.round(ch)); s.ctx.fillStyle='#000'; s.ctx.fillRect(0,0,c.width,c.height); }
                    v.style.opacity='0.01'; s.active=true;
                  } else { c.style.display='none'; if(s.active)v.style.opacity=''; s.active=false; }
                  requestAnimationFrame(tick);
                }
                requestAnimationFrame(tick);
              }
              function scan(){ document.querySelectorAll('video').forEach(v=>{attachAudio(v);attachVideo(v);}); }
              scan(); new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
              setInterval(scan,1200);
              document.addEventListener('visibilitychange',()=>{ if(!document.hidden) scan(); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun openUrl(raw: String) {
        var value = raw.trim()
        if (value.isEmpty()) return
        if (!value.contains("://")) {
            value = if (value.contains(".") && !value.contains(" ")) "https://$value"
                    else "https://www.google.com/search?q=" + Uri.encode(value)
        }
        webView.loadUrl(value)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (customVideoView != null) {
            exitCustomVideoView()
        } else if (isFullscreen) {
            setFullscreen(false)
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (customVideoView != null) exitCustomVideoView()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
