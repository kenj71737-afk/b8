package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

class AudioPlayerManager(private val context: Context) {
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    // FIX44: when earphones are connected, the alarm-stream dub must play
    // through them, not the phone speaker. Re-applied on every track
    // rebuild and whenever the output devices change.
    private var deviceCallback: android.media.AudioDeviceCallback? = null

    /** FIX44: pin the current track to connected earphones, if any. */
    private fun applyHeadphoneRouting() {
        val manager = audioManager ?: return
        HeadphoneRouter.routeTrack(audioTrack, manager)
    }

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Short bounded queue: discard stale translated audio instead of accumulating delay.
    private val audioChannel = Channel<ByteArray>(
        // Hold a few seconds of translated PCM while it is aligned to delayed source audio.
        capacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var playbackJob: Job? = null
    // FIX20: how many translated-audio bytes are still waiting in the queue.
    // The auto catch-up loop reads this (as milliseconds) to know how far
    // the dub is behind the picture. Incremented on offer, decremented when
    // the player takes a chunk; reset whenever the queue is known empty so
    // a DROP_OLDEST discard can never leave a stale count behind.
    private val pendingAudioBytes = AtomicLong(0L)
    @Volatile private var initialVoiceDelayMs: Long = 0L
    @Volatile private var initialVoiceDelayApplied: Boolean = false

    fun setInitialVoiceDelayMs(delayMs: Long) {
        // FIX31: voice hold cap 10s -> 20s (the media delay can be 20s now).
        initialVoiceDelayMs = delayMs.coerceIn(0L, 20000L)
        initialVoiceDelayApplied = false
    }

    private var initialMusicVolume: Int = -1
    private var maxMusicVolume: Int = 15
    // FIX29 (cách B): in alarm mode the dub + filtered background play on
    // the alarm stream while STREAM_MUSIC is 0 (the other app is muted).
    private var initialAlarmVolume: Int = -1
    private var maxAlarmVolume: Int = 7
    @Volatile private var alarmMode: Boolean = false

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var isDubbingMuted: Boolean = false
    @Volatile private var currentVoicePersona: String = "Puck"
    @Volatile private var currentPlaybackSpeed: Float = 1.0f

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed.coerceIn(0.75f, 1.35f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val track = audioTrack
                if (track != null) {
                    track.playbackParams = track.playbackParams.setSpeed(currentPlaybackSpeed)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback speed change unavailable on this device", e)
        }
    }

    fun setVoicePersona(persona: String) {
        currentVoicePersona = persona
        Log.d(TAG, "Active voice persona set to: $persona")
    }

    /**
     * FIX29: when [useAlarmStream] is true (external-app mode + voice
     * filter), the dub plays on the alarm stream instead of the assistant
     * stream, STREAM_MUSIC is set to 0 (the other app is muted entirely),
     * and the alarm volume is set to match the user's music loudness.
     * No audio-focus request: the other app is muted by volume, and
     * asking for focus could make some video apps pause. Both volumes are
     * restored in [stop]. Default behaviour is unchanged.
     */
    fun start(useAlarmStream: Boolean = false) {
        alarmMode = useAlarmStream
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            initialMusicVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            maxMusicVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            if (initialMusicVolume <= 0) {
                initialMusicVolume = (maxMusicVolume * 0.7f).toInt().coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query STREAM_MUSIC volume", e)
        }

        if (useAlarmStream) {
            try {
                initialAlarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
                maxAlarmVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
                // Map the user's music loudness onto the alarm stream so
                // the dub + background come out at a familiar volume.
                val musicRatio = if (maxMusicVolume > 0 && initialMusicVolume > 0) {
                    initialMusicVolume.toFloat() / maxMusicVolume
                } else 0.7f
                val alarmTarget = (musicRatio * maxAlarmVolume).toInt().coerceIn(1, maxAlarmVolume)
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, alarmTarget, 0)
                Log.i(TAG, "Alarm mode: STREAM_MUSIC muted, STREAM_ALARM set to $alarmTarget/$maxAlarmVolume")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set alarm-mode volumes", e)
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (useAlarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (!useAlarmStream) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()

                audioManager?.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    { },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Smaller AudioTrack buffer reduces the extra delay before translated voice is heard.
        val bufferSize = maxOf(minBufferSize, 2048)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        setPlaybackSpeed(currentPlaybackSpeed)
        // FIX44: route the dub to earphones when they are connected
        // (USAGE_ALARM otherwise goes to the phone speaker), and keep
        // routing it when earphones are plugged/unplugged mid-session.
        applyHeadphoneRouting()
        deviceCallback = audioManager?.let { manager ->
            HeadphoneRouter.registerCallback(manager) { applyHeadphoneRouting() }
        }

        launchPlaybackLoop()
    }

    // FIX41: the playback loop, shared by start() and switchToAlarmStream()
    // (the switch restarts the loop on the same scope after rebuilding the
    // track, so queued translated audio survives it).
    private fun launchPlaybackLoop() {
        playbackJob = playerScope.launch {
            var prevSample = 0f
            for (chunk in audioChannel) {
                pendingAudioBytes.addAndGet(-chunk.size.toLong())
                if (audioChannel.isEmpty) pendingAudioBytes.set(0L)
                if (!initialVoiceDelayApplied) {
                    initialVoiceDelayApplied = true
                    val waitMs = initialVoiceDelayMs
                    if (waitMs > 0L) delay(waitMs)
                }
                if (isDubbingMuted || currentVolume <= 0.01f) {
                    continue
                }

                try {
                    // Direct software PCM scaling with logarithmic curve for 100% audible control on all devices
                    val vol = currentVolume.coerceIn(0f, 1f)
                    val gain = (vol * vol * 1.35f)
                    val persona = currentVoicePersona

                    val processedChunk = ByteArray(chunk.size)
                    val numSamples = chunk.size / 2

                    for (i in 0 until numSamples) {
                        val byteIndex = i * 2
                        val low = chunk[byteIndex].toInt() and 0xFF
                        val high = chunk[byteIndex + 1].toInt()
                        var sample = ((high shl 8) or low).toShort().toFloat()

                        // 1. Voice Persona Character Filtering (DSP timbre shaping)
                        sample = when (persona) {
                            "Charon" -> {
                                // Deep & Resonant: warm low-end boost and smooth high roll-off
                                val filtered = (sample * 0.70f) + (prevSample * 0.30f)
                                filtered * 1.08f
                            }
                            "Puck" -> {
                                // Energetic & Crisp: presence high-shelf boost
                                val diff = sample - prevSample
                                (sample * 0.85f) + (diff * 0.28f)
                            }
                            "Aoede" -> {
                                // Melodic & Warm: balanced harmonic warmth
                                (sample * 0.82f) + (prevSample * 0.18f)
                            }
                            "Fenrir" -> {
                                // Authoritative & Bold: firm dynamic presence
                                val diff = sample - prevSample
                                (sample * 0.90f) + (diff * 0.18f)
                            }
                            "Kore" -> {
                                // Gentle & Calm: gentle softening
                                (sample * 0.75f) + (prevSample * 0.25f)
                            }
                            else -> sample
                        }
                        prevSample = sample

                        // 2. Volume Gain Scaling with Soft Limiter
                        val scaled = (sample * gain).coerceIn(-32767f, 32767f).toInt()

                        processedChunk[byteIndex] = (scaled and 0xFF).toByte()
                        processedChunk[byteIndex + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }

                    audioTrack?.write(processedChunk, 0, processedChunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio chunk", e)
                }
            }
        }
    }

    /**
     * FIX41: move the dub voice to the alarm stream mid-session. Used when
     * the WebAudio tap dies after session start (CORS/DRM): the original
     * audio is then delayed through the background player on the alarm
     * stream, STREAM_MUSIC must be muted, and the dub would go silent if
     * it stayed on the assistant usage. Rebuilds the AudioTrack with
     * USAGE_ALARM and restarts the playback loop on the same scope, so
     * queued translated audio survives and the initial voice hold is NOT
     * re-applied (it was already applied at session start).
     */
    fun switchToAlarmStream() {
        if (alarmMode) return
        alarmMode = true
        try {
            initialAlarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
            maxAlarmVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
            // Map the CURRENT music loudness onto the alarm stream (the
            // user may have changed the volume since session start).
            val currentMusicVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
                ?: initialMusicVolume
            val musicRatio = if (maxMusicVolume > 0 && currentMusicVolume > 0) {
                currentMusicVolume.toFloat() / maxMusicVolume
            } else 0.7f
            val alarmTarget = (musicRatio * maxAlarmVolume).toInt().coerceIn(1, maxAlarmVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, alarmTarget, 0)
            Log.i(TAG, "Switch to alarm: STREAM_MUSIC muted, STREAM_ALARM set to $alarmTarget/$maxAlarmVolume")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set alarm-mode volumes", e)
        }
        // Abandon the audio focus requested for the assistant usage
        // (alarm mode does not request focus).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus { }
            }
            focusRequest = null
        } catch (e: Exception) {
            Log.w(TAG, "Could not abandon audio focus", e)
        }
        // Rebuild the track with USAGE_ALARM. Cancel the playback loop
        // first so it never writes to a released track; the channel keeps
        // its queued chunks and the loop restarts on the same scope.
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioTrack for alarm switch", e)
        }
        audioTrack = null
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 2048)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack?.play()
        setPlaybackSpeed(currentPlaybackSpeed)
        // FIX44: the rebuilt alarm track needs routing again (a new
        // track has no preferred device); the device callback registered
        // in start() keeps handling plug/unplug after this.
        applyHeadphoneRouting()
        launchPlaybackLoop()
    }

    fun playAudioData(data: ByteArray) {
        pendingAudioBytes.addAndGet(data.size.toLong())
        audioChannel.trySend(data)
    }

    /** FIX20: translated audio still waiting to be played, in milliseconds
     * (24 kHz 16-bit mono = 48 000 bytes per second). */
    fun getPendingAudioMs(): Long {
        val bytes = pendingAudioBytes.get().coerceAtLeast(0L)
        return bytes * 1000L / (SAMPLE_RATE * 2)
    }

    /** FIX16: drop translated-audio chunks queued for the pre-seek position,
     * so the old scene's dub is not played over the new picture. */
    fun clearPendingAudio() {
        var dropped = 0
        while (audioChannel.tryReceive().isSuccess) dropped++
        pendingAudioBytes.set(0L)
        if (dropped > 0) Log.d(TAG, "Cleared $dropped pending translated-audio chunks after seek")
    }

    fun setDubbingAudio(volume: Float, isMuted: Boolean) {
        currentVolume = volume.coerceIn(0f, 1f)
        isDubbingMuted = isMuted
        val targetHardwareVolume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        try {
            audioTrack?.setVolume(targetHardwareVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting dubbing volume", e)
        }
    }

    fun setOriginalMediaAudio(volumeRatio: Float, isMuted: Boolean) {
        // FIX29: in alarm mode STREAM_MUSIC stays 0 (the other app is
        // muted); the "original" the user hears is the filtered
        // background, whose loudness the service routes to
        // BackgroundAudioPlayer instead.
        if (alarmMode) return
        try {
            val baseVol = if (initialMusicVolume > 0) initialMusicVolume else (maxMusicVolume * 0.7f).toInt()
            val targetVolume = if (isMuted) {
                0
            } else {
                (baseVol * volumeRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, maxMusicVolume)
            }
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error adjusting STREAM_MUSIC volume", e)
        }
    }

    fun stop() {
        // Restore original system music volume
        if (initialMusicVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, initialMusicVolume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring STREAM_MUSIC volume", e)
            }
        }
        // FIX29: restore the alarm volume too (it was raised for the dub).
        if (alarmMode && initialAlarmVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, initialAlarmVolume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring STREAM_ALARM volume", e)
            }
        }
        alarmMode = false

        // FIX44: stop watching output-device changes.
        HeadphoneRouter.unregisterCallback(audioManager, deviceCallback)
        deviceCallback = null

        playbackJob?.cancel()
        playbackJob = null
        pendingAudioBytes.set(0L)
        playerScope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus { }
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioTrack", e)
        }
        audioTrack = null
    }
}
