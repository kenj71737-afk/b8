package com.dublive.live

import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector for 16 kHz mono PCM.
 *
 * - Speech counts only after >= 150 ms of continuous energy above threshold.
 * - Keeps a ~500 ms pre-roll ring buffer so the start of a sentence is not cut.
 * - An utterance ends after ~700 ms of continuous silence.
 * This is a fresh implementation written for DubLive.
 */
class VadDetector(
    private val sampleRate: Int = 16000,
    /** v1.0.5: configurable. Default 600 (ALDA's value). Was a hard-coded
     * 250, far below a loud scene's RMS (1632 in the user's screenshot),
     * so background music alone kept the VAD "in speech" forever. */
    private val energyThreshold: Double = 600.0,
    private val listener: Listener
) {
    interface Listener {
        /** A new utterance started; [preRollAndHead] contains pre-roll + first audio. */
        fun onSpeechStart(preRollAndHead: ShortArray)
        fun onSpeechChunk(samples: ShortArray)
        fun onSpeechEnd()
    }

    private val frameSamples = sampleRate / 50 // 20 ms frames
    private val speechFramesNeeded = 8 // 8 x 20 ms = 160 ms >= 150 ms
    private val silenceFramesToEnd = 35 // 35 x 20 ms = 700 ms

    /** RMS of the most recent 20 ms frame (0 when silent). Read by the
     * service's level ticker so the UI can show a live input-level meter. */
    @Volatile var lastRms: Double = 0.0
        private set

    private val preRoll = ArrayDeque<Short>()
    private val preRollMax = sampleRate / 2 // 500 ms
    private var inSpeech = false
    private var loudFrames = 0
    private var quietFrames = 0
    private var pendingHead = mutableListOf<Short>()

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size)
    }

    /** Feed raw PCM. May be called with any chunk size; internally framed at 20 ms. */
    fun feed(pcm: ShortArray, length: Int) {
        var offset = 0
        while (offset + frameSamples <= length) {
            val frame = pcm.copyOfRange(offset, offset + frameSamples)
            offset += frameSamples
            processFrame(frame)
        }
        if (offset < length) {
            // Keep the tail in the pre-roll so no audio is lost between calls.
            for (i in offset until length) pushPreRoll(pcm[i])
        }
    }

    private fun pushPreRoll(s: Short) {
        preRoll.addLast(s)
        while (preRoll.size > preRollMax) preRoll.removeFirst()
    }

    private fun processFrame(frame: ShortArray) {
        val frameRms = rms(frame)
        lastRms = frameRms
        val loud = frameRms > energyThreshold
        if (!inSpeech) {
            if (loud) {
                loudFrames++
                pendingHead.addAll(frame.toList())
                if (loudFrames >= speechFramesNeeded) {
                    inSpeech = true
                    quietFrames = 0
                    val head = ShortArray(preRoll.size + pendingHead.size)
                    var idx = 0
                    for (s in preRoll) head[idx++] = s
                    for (s in pendingHead) head[idx++] = s
                    pendingHead.clear()
                    preRoll.clear()
                    listener.onSpeechStart(head)
                }
            } else {
                loudFrames = 0
                pendingHead.clear()
                for (s in frame) pushPreRoll(s)
            }
        } else {
            if (loud) {
                quietFrames = 0
                listener.onSpeechChunk(frame)
            } else {
                quietFrames++
                listener.onSpeechChunk(frame)
                if (quietFrames >= silenceFramesToEnd) {
                    inSpeech = false
                    loudFrames = 0
                    quietFrames = 0
                    listener.onSpeechEnd()
                }
            }
        }
    }

    fun reset() {
        inSpeech = false
        loudFrames = 0
        quietFrames = 0
        pendingHead.clear()
        preRoll.clear()
    }
}
