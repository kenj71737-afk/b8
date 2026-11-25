package com.dublive.live

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
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
 *
 * Audio (v1.0.11): the film sound reaches the service through a tap
 * processor on the player's audio pipeline (LocalTapProcessor), so the
 * dub voice can play on the MEDIA stream with no re-capture echo — no
 * system capture runs. If the tap yields no PCM while playing, the
 * service falls back to system capture (GAME only) or the mic.
 */
class LocalPlayerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "DubLive-Local"
    }

    private lateinit var textureView: TextureView
    private lateinit var delayedFrameView: ImageView
    private lateinit var subtitleView: TextView
    private lateinit var hudView: TextView
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var dubbing = false
    private var delayOn = true
    private var currentDelayMs = 5000L
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingMicAfterPermission = false
    /** v1.0.11 — the tap processor on the player's audio pipeline. */
    private lateinit var tapProcessor: LocalTapProcessor
    /** v1.0.11 — true while the session takes the film sound from the
     * tap processor instead of system capture. */
    private var tapMode = false
    /** v1.0.11 — the tap died and the projection dialog is out. */
    private var fallbackPending = false

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
            if (wantMic) startMicService() else startLocalTapDub()
        } else {
            Toast.makeText(this, R.string.need_mic_permission, Toast.LENGTH_LONG).show()
            if (!wantMic) {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
                startMicService()
            }
        }
    }

    private val frameQueue = ArrayDeque<Pair<Long, Bitmap>>()
    private val pumpHandler = Handler(Looper.getMainLooper())
    private var lastSnapshotAt = 0L
    private var shownBitmap: Bitmap? = null

    /**
     * v1.0.11 — the service's local-tap watchdog needs to know whether
     * the player is actually playing: no PCM while playing for 10 s
     * means the tap processor is dead (exotic codec / a renderer that
     * bypasses it) and the service falls back to system capture.
     */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            try {
                val intent = Intent(this@LocalPlayerActivity, DubbingService::class.java).apply {
                    action = DubbingService.ACTION_LOCAL_PLAYING
                    putExtra(DubbingService.EXTRA_PLAYING, isPlaying)
                }
                startService(intent)
            } catch (_: Exception) { }
        }
    }

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
                // v1.0.11 — this token is a tap -> capture fallback:
                // the service switches in place (keeps Live + D) and
                // matches GAME only.
                putExtra(DubbingService.EXTRA_FALLBACK, fallbackPending)
                putExtra(DubbingService.EXTRA_SOURCE, DubbingService.SOURCE_LOCAL)
            }
            fallbackPending = false
            if (tapMode) {
                // The capture path replays the original audio itself,
                // so the tap stops — and the player must be audible
                // again BEFORE the capture starts, or it records
                // silence (the v1.0.2 lesson).
                tapMode = false
                tapProcessor.enabled = false
                player?.volume = 1.0f
            }
            startProjectionService(intent, isRetry = false)
        } else {
            // v1.0.11 — the user denied the capture dialog during a
            // tap fallback: dub through the microphone instead of
            // dying. The player goes audible again so the mic hears it.
            if (fallbackPending) {
                fallbackPending = false
                tapMode = false
                tapProcessor.enabled = false
                player?.volume = 1.0f
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
                        // v1.0.11 — the session is over: stop the tap
                        // and give the player its direct audio back.
                        tapMode = false
                        tapProcessor.enabled = false
                        subtitleView.visibility = View.GONE
                        hudView.visibility = View.GONE
                        delayedFrameView.visibility = View.GONE
                        player?.volume = 1.0f
                    }
                }
                DubbingService.TYPE_AUDIO_ROUTE -> {
                    // v1.0.11 — the dub moved streams. On "call" (the
                    // capture fallback) the live player must be audible
                    // again: the capture records the GAME stream, and
                    // a muted player (volume 0) records silence (the
                    // v1.0.2 lesson). On "mic" the same: the microphone
                    // needs to hear the speakers.
                    val mode = intent.getStringExtra(DubbingService.EXTRA_AUDIO_MODE).orEmpty()
                    if (mode == DubbingService.AUDIO_MODE_CALL ||
                        mode == DubbingService.AUDIO_MODE_MIC
                    ) {
                        if (tapMode) {
                            tapMode = false
                            tapProcessor.enabled = false
                        }
                        player?.volume = 1.0f
                    }
                    val label = intent.getStringExtra(DubbingService.EXTRA_MESSAGE).orEmpty()
                    if (label.isNotBlank()) {
                        Toast.makeText(this@LocalPlayerActivity, label, Toast.LENGTH_SHORT).show()
                    }
                }
                DubbingService.TYPE_NEED_PROJECTION -> {
                    // v1.0.11 — the local tap produced no PCM while
                    // playing: ask for the screen-capture token so the
                    // service can fall back to system capture (GAME
                    // only). No user tap needed to start it.
                    fallbackPending = true
                    showShareTipThenProjection()
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

        // v1.0.11 — the tap processor sits on the player's audio
        // pipeline (it copies the normalised 48 kHz stereo PCM to the
        // service BEFORE the track volume, so player.volume = 0 silences
        // the speakers without starving the tap — the v1.0.2 lesson).
        // The player itself plays on USAGE_GAME: the dub voice +
        // delayed original play on MEDIA, and the capture fallback
        // matches GAME only, so it never re-captures the dub (no echo).
        tapProcessor = LocalTapProcessor()
        // v1.0.11 — the processor's output goes straight into the
        // service's tap queue (STEREO48K); without this line the
        // local tap delivers nothing.
        tapProcessor.onPcm = { bytes -> TapAudioQueue.offerStereo48k(bytes) }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setAudioProcessors(arrayOf<AudioProcessor>(tapProcessor))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_GAME)
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        player?.addListener(playerListener)
        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            pickFileLauncher.launch("video/*")
        }
        findViewById<Button>(R.id.localStartDubButton).setOnClickListener { startDub() }
        findViewById<Button>(R.id.localStopDubButton).setOnClickListener { stopDub() }
        findViewById<Button>(R.id.localMicDubButton).setOnClickListener { startMicDub() }
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

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
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
        if (!hasRecordAudio()) {
            pendingMicAfterPermission = false
            permissionLauncher.launch(requiredPermissions())
            return
        }
        startLocalTapDub()
    }

    /**
     * v1.0.11 — tap-first start for local files: NO screen-capture
     * dialog. The service starts in TAP_LOCAL mode; the film sound
     * reaches it through the tap processor, and the dub voice +
     * delayed original play on the MEDIA stream. If the tap yields no
     * PCM while playing, the service broadcasts TYPE_NEED_PROJECTION
     * and this activity launches the capture dialog as a fallback.
     */
    private fun startLocalTapDub() {
        val intent = Intent(this, DubbingService::class.java).apply {
            action = DubbingService.ACTION_START_LOCAL_TAP
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService (local tap) failed, retrying with startService", e)
            try {
                startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startService (local tap) also failed", e2)
                Toast.makeText(this, R.string.service_failed, Toast.LENGTH_LONG).show()
                return
            }
        }
        dubbing = true
        tapMode = true
        tapProcessor.enabled = true
        // The live player audio goes silent — its sound reaches the
        // service through the tap processor instead, and comes back D
        // seconds late on the media stream. The duck drives the delayed
        // background player directly (service-side), never this volume.
        player?.volume = 0f
        hudView.visibility = View.VISIBLE
    }

    private fun startMicDub() {
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
        // v1.0.2: full volume at start; the service ducks only after the
        // first dubbed speech actually plays (ducking early starves VAD).
        player?.volume = 1.0f
        hudView.visibility = View.VISIBLE
    }

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

    private fun startProjectionService(intent: Intent, isRetry: Boolean) {
        try {
            startForegroundService(intent)
            dubbing = true
            // v1.0.2: full volume at start (see startMicService).
            player?.volume = 1.0f
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
        // v1.0.11 — stop the tap at once and give the player its
        // direct audio back (the TYPE_STATE branch does the same when
        // the service's stopped broadcast arrives).
        tapMode = false
        tapProcessor.enabled = false
        player?.volume = 1.0f
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
