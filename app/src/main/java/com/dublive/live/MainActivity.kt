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
 * Audio is captured by DubbingService through the system (MediaProjection),
 * never through WebAudio, so cross-origin video without CORS still dubs.
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
    private lateinit var delayToggleButton: Button
    private var delayOn = true
    private var dubbing = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    /** True when the pending permission request was started by the mic button. */
    private var pendingMicAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.RECORD_AUDIO] == true || hasRecordAudio()
        val wantMic = pendingMicAfterPermission
        pendingMicAfterPermission = false
        if (granted) {
            if (wantMic) startMicService() else showShareTipThenProjection()
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
            }
            startProjectionService(intent, isRetry = false)
        } else {
            Toast.makeText(this, "Cần cho phép ghi màn hình để lấy tiếng hệ thống", Toast.LENGTH_LONG).show()
        }
    }

    private val dubReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DubbingService.ACTION_DUB_EVENT) return
            when (intent.getStringExtra(DubbingService.EXTRA_TYPE)) {
                DubbingService.TYPE_SUBTITLE -> {
                    val text = intent.getStringExtra(DubbingService.EXTRA_TEXT).orEmpty()
                    if (text.isBlank()) {
                        subtitleView.visibility = View.GONE
                    } else {
                        subtitleView.text = text
                        subtitleView.visibility = View.VISIBLE
                    }
                }
                DubbingService.TYPE_HUD -> {
                    val ms = intent.getLongExtra(DubbingService.EXTRA_DELAY_MS, 0L)
                    hudView.text = "Trễ hình: %.1f s".format(ms / 1000.0)
                    hudView.visibility = View.VISIBLE
                }
                DubbingService.TYPE_STATE -> {
                    if (intent.getStringExtra(DubbingService.EXTRA_STATE) == DubbingService.STATE_STOPPED) {
                        dubbing = false
                        subtitleView.visibility = View.GONE
                        hudView.visibility = View.GONE
                        setOriginalVolume(1.0f)
                    }
                }
                DubbingService.TYPE_ERROR -> {
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                DubbingService.TYPE_FALLBACK -> {
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                DubbingService.TYPE_DUCK -> {
                    intent.getStringExtra(DubbingService.EXTRA_MESSAGE)?.toFloatOrNull()?.let {
                        setOriginalVolume(it)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        subtitleView = findViewById(R.id.subtitleView)
        hudView = findViewById(R.id.hudView)
        delayToggleButton = findViewById(R.id.delayToggleButton)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.loadWithOverviewMode = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(DelayJs.INJECT, null)
                view?.evaluateJavascript("window.DubLiveSetDelay(5000)", null)
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
                (window.decorView as ViewGroup).addView(
                    view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                hudView.visibility = if (dubbing) View.VISIBLE else View.GONE
            }

            override fun onHideCustomView() {
                (window.decorView as ViewGroup).removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        findViewById<Button>(R.id.openButton).setOnClickListener { openUrl() }
        findViewById<Button>(R.id.startDubButton).setOnClickListener { startDub() }
        findViewById<Button>(R.id.stopDubButton).setOnClickListener { stopDub() }
        findViewById<Button>(R.id.micDubButton).setOnClickListener { startMicDub() }
        findViewById<Button>(R.id.localFileButton).setOnClickListener {
            startActivity(Intent(this, LocalPlayerActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        delayToggleButton.setOnClickListener {
            delayOn = !delayOn
            webView.evaluateJavascript("window.DubLiveSetEnabled($delayOn)", null)
            delayToggleButton.setText(
                if (delayOn) R.string.delay_off else R.string.delay_on
            )
        }
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
        showShareTipThenProjection()
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
        setOriginalVolume(Prefs.originalVolume(this))
        hudView.visibility = View.VISIBLE
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
            setOriginalVolume(Prefs.originalVolume(this))
            hudView.visibility = View.VISIBLE
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
    }

    private fun setOriginalVolume(vol: Float) {
        try {
            webView.evaluateJavascript("window.DubLiveSetVolume($vol)", null)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
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
