package com.dublive.live

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity

/**
 * Main screen: address bar + WebView for web films and YouTube, dub controls,
 * Vietnamese subtitle overlay and a one-line delay HUD.
 *
 * Audio is captured by DubbingService through the system (MediaProjection),
 * never through WebAudio, so cross-origin video without CORS still dubs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var subtitleView: TextView
    private lateinit var hudView: TextView
    private lateinit var delayToggleButton: Button
    private var delayOn = true
    private var dubbing = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, DubbingService::class.java).apply {
                action = DubbingService.ACTION_START
                putExtra(DubbingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(DubbingService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            dubbing = true
            setOriginalVolume(Prefs.originalVolume(this))
            hudView.visibility = View.VISIBLE
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

    private fun startDub() {
        if (Prefs.geminiKeys(this).isEmpty()) {
            Toast.makeText(this, R.string.need_key_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (dubbing) return
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun stopDub() {
        val intent = Intent(this, DubbingService::class.java).apply {
            action = DubbingService.ACTION_STOP
        }
        startService(intent)
        dubbing = false
    }

    private fun setOriginalVolume(vol: Float) {
        webView.evaluateJavascript("window.DubLiveSetVolume($vol)", null)
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
        try { webView.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
