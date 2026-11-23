package com.alad.app.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log

class AudioCaptureManager {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        const val CHUNK_SIZE = 320 // 10ms of 16kHz 16-bit mono PCM (160 samples x 2 bytes)
        // FIX29: stereo capture for the voice-filter mode. 48 kHz stereo
        // is what the vocal remover needs; the same chunks are downmixed
        // to 16 kHz mono for Gemini, so translation input is unchanged.
        const val STEREO_SAMPLE_RATE = 48000
        const val STEREO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val STEREO_CHUNK_SIZE = 1920 // 10ms of 48kHz 16-bit stereo PCM (480 frames x 2 ch x 2 bytes)
    }

    @SuppressLint("MissingPermission")
    fun startCapture(mediaProjection: MediaProjection, appUid: Int, onAudioData: (ByteArray) -> Unit) {
        if (isRecording) return

        try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                
            // Do not exclude ALAD's own UID: BrowserActivity plays the source video
            // under the same UID. Excluding it prevents browser audio reaching Gemini.
            // Translated output uses USAGE_ASSISTANT, not the MEDIA/GAME/UNKNOWN usages
            // captured above, avoiding feedback from the Vietnamese voice.
            val config = configBuilder.build()

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val bufferSize = maxOf(MIN_BUFFER_SIZE, CHUNK_SIZE * 4)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize! State: ${audioRecord?.state}")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "AudioPlaybackCapture started successfully. Recording state: ${audioRecord?.recordingState}")
            
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(CHUNK_SIZE)
                var chunksRead = 0
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        chunksRead++
                        if (chunksRead % 100 == 0) {
                            Log.d(TAG, "Captured 100 chunks of size $read ($CHUNK_SIZE expected)")
                        }
                        onAudioData(buffer.copyOf(read))
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read failed: $read")
                        break
                    }
                }
            }, "AudioCaptureThread").start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
        }
    }

    /**
     * FIX29: stereo capture for the voice-filter mode. Each 10 ms stereo
     * chunk goes two ways: raw to [onStereoAudioData] (background pipeline:
     * vocal removal + alarm-stream playback) and downmixed to 16 kHz mono
     * for [onMonoAudioData] (Gemini input — byte-identical in shape to what
     * [startCapture] delivers, so the translation path needs no changes).
     * The mono path is deliberately kept separate from [startCapture]:
     * the old method is untouched, so browser mode behaves exactly as
     * before.
     */
    @SuppressLint("MissingPermission")
    fun startCaptureStereo(
        mediaProjection: MediaProjection,
        appUid: Int,
        onMonoAudioData: (ByteArray) -> Unit,
        onStereoAudioData: (ByteArray) -> Unit
    ) {
        if (isRecording) return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            // NOTE: USAGE_ALARM is deliberately NOT matched — the filtered
            // background plays on the alarm stream, so it must not be
            // re-captured (that would feed the background back into itself).

            val format = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(STEREO_SAMPLE_RATE)
                .setChannelMask(STEREO_CHANNEL_CONFIG)
                .build()

            val minStereoBuffer = AudioRecord.getMinBufferSize(STEREO_SAMPLE_RATE, STEREO_CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minStereoBuffer, STEREO_CHUNK_SIZE * 8) // ~80 ms absorbs write stalls

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Stereo AudioRecord failed to initialize! State: ${audioRecord?.state}")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d(TAG, "Stereo AudioPlaybackCapture started (48kHz)")

            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ByteArray(STEREO_CHUNK_SIZE)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Floor to whole stereo frames (defensive; reads are
                        // normally frame-aligned).
                        val aligned = read / 4 * 4
                        if (aligned > 0) {
                            onStereoAudioData(buffer.copyOf(aligned))
                            val mono = VoiceFilterDsp.stereo48kToMono16k(buffer, aligned)
                            if (mono.isNotEmpty()) onMonoAudioData(mono)
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "Stereo AudioRecord read failed: $read")
                        break
                    }
                }
            }, "AudioCaptureThreadStereo").start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stereo capture", e)
        }
    }

    fun stopCapture() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioRecord", e)
        }
        audioRecord = null
    }
}
