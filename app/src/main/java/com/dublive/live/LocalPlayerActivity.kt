package com.dublive.live

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Local-file player (ExoPlayer) with the same dub pipeline as the WebView.
 *
 * Video delay here is a frame queue: ExoPlayer renders into a TextureView,
 * each new frame is snapshotted (~15 fps) with a timestamp, and an overlay
 * ImageView shows the oldest frame whose time has come (now - delayMs).
 * When delay is off, the overlay hides and the TextureView shows directly.
 * Frame snapshots are capped at 960 px wide by the TextureView size; this
 * costs CPU, so v1 snapshots at 15 fps on purpose.
 */
class LocalPlayerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var delayedFrameView: ImageView
    private lateinit var subtitleView: TextView
    private lateinit var hudView: TextView
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var dubbing = false
    private var delayOn = true
    private var currentDelayMs = 5000L

    private val frameQueue = ArrayDeque<Pair<Long, Bitmap>>()
    private val pumpHandler = Handler(Looper.getMainLooper())
    private var lastSnapshotAt = 0L
    private var shownBitmap: Bitmap? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) openVideo(uri)
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
            startForegroundService(intent)
            dubbing = true
            player?.volume = Prefs.originalVolume(this)
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
                    if (text.isBlank()) subtitleView.visibility = View.GONE
                    else {
                        subtitleView.text = text
                        subtitleView.visibility = View.VISIBLE
                    }
                }
                DubbingService.TYPE_HUD -> {
                    currentDelayMs = intent.getLongExtra(DubbingService.EXTRA_DELAY_MS, currentDelayMs)
                    hudView.text = "Trễ hình: %.1f s".format(currentDelayMs / 1000.0)
                    hudView.visibility = View.VISIBLE
                }
                DubbingService.TYPE_STATE -> {
                    if (intent.getStringExtra(DubbingService.EXTRA_STATE) == DubbingService.STATE_STOPPED) {
                        dubbing = false
                        subtitleView.visibility = View.GONE
                        hudView.visibility = View.GONE
                        delayedFrameView.visibility = View.GONE
                        player?.volume = 1.0f
                    }
                }
                DubbingService.TYPE_ERROR, DubbingService.TYPE_FALLBACK -> {
                    val msg = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (msg.isNotBlank()) Toast.makeText(this@LocalPlayerActivity, msg, Toast.LENGTH_LONG).show()
                }
                DubbingService.TYPE_DUCK -> {
                    intent.getStringExtra(DubbingService.EXTRA_MESSAGE)?.toFloatOrNull()?.let {
                        player?.volume = it
                    }
                }
            }
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            pumpFrame()
            pumpHandler.postDelayed(this, 66L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_player)
        textureView = findViewById(R.id.textureView)
        delayedFrameView = findViewById(R.id.delayedFrameView)
        subtitleView = findViewById(R.id.localSubtitleView)
        hudView = findViewById(R.id.localHudView)
        textureView.surfaceTextureListener = this

        player = ExoPlayer.Builder(this).build()
        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            pickFileLauncher.launch("video/*")
        }
        findViewById<Button>(R.id.localStartDubButton).setOnClickListener { startDub() }
        findViewById<Button>(R.id.localStopDubButton).setOnClickListener { stopDub() }
        pumpHandler.post(pump)
    }

    private fun openVideo(uri: Uri) {
        currentUri = uri
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        val item = MediaItem.fromUri(uri)
        player?.setMediaItem(item)
        player?.prepare()
        player?.play()
    }

    private fun startDub() {
        if (currentUri == null) {
            Toast.makeText(this, "Chọn file video trước", Toast.LENGTH_SHORT).show()
            return
        }
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

    // ---- TextureView frame delay ----
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        player?.setVideoTextureView(textureView)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        player?.setVideoTextureView(null)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!dubbing || !delayOn) return
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < 66L) return
        lastSnapshotAt = now
        val bmp = textureView.bitmap ?: return
        synchronized(frameQueue) {
            frameQueue.addLast(now to bmp)
            val cap = (currentDelayMs / 66L).toInt() + 20
            while (frameQueue.size > cap) frameQueue.removeFirst().second.recycle()
        }
    }

    private fun pumpFrame() {
        if (!dubbing || !delayOn) {
            if (delayedFrameView.visibility != View.GONE) delayedFrameView.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        var due: Bitmap? = null
        synchronized(frameQueue) {
            while (frameQueue.isNotEmpty() && frameQueue.first().first <= now - currentDelayMs) {
                due?.recycle()
                due = frameQueue.removeFirst().second
            }
        }
        if (due != null) {
            shownBitmap?.recycle()
            shownBitmap = due
            delayedFrameView.setImageBitmap(due)
            delayedFrameView.visibility = View.VISIBLE
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

    override fun onDestroy() {
        pumpHandler.removeCallbacks(pump)
        synchronized(frameQueue) {
            frameQueue.forEach { it.second.recycle() }
            frameQueue.clear()
        }
        shownBitmap?.recycle()
        player?.release()
        player = null
        super.onDestroy()
    }
}
