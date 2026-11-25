package com.dublive.live

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v1.0.11 — in-process PCM queue from the page / local player to the
 * dub service. The service and both activities run in the same process,
 * so the WebAudio tap (JavascriptInterface) and the ExoPlayer tap
 * processor push PCM here and the service's tap feeder thread drains it
 * into the VAD (+ the delayed background player for stereo).
 *
 * Two shapes, both already normalised by their producer:
 *  - MONO16K: WebAudio ScriptProcessor tap, resampled in JS to 16 kHz
 *    mono PCM16 (the Live/VAD input format, straight into vad.feed).
 *  - STEREO48K: ExoPlayer tap processor, normalised to 48 kHz stereo
 *    PCM16 (the delayed-background input; the service downmixes it to
 *    mono 16 kHz for the VAD, exactly like the stereo capture path).
 *
 * Bounded (64 chunks): a slow consumer drops the OLDEST audio instead
 * of growing without bound — a 2 s stall must not replay stale film
 * audio. offer() never blocks (the JS bridge and the ExoPlayer audio
 * thread must never wait on the service).
 */
object TapAudioQueue {

    enum class Kind { MONO16K, STEREO48K }

    data class Chunk(val kind: Kind, val bytes: ByteArray)

    private const val CAPACITY = 64
    private val queue = ArrayBlockingQueue<Chunk>(CAPACITY)

    fun offerMono16k(bytes: ByteArray): Boolean =
        queue.offer(Chunk(Kind.MONO16K, bytes))

    fun offerStereo48k(bytes: ByteArray): Boolean =
        queue.offer(Chunk(Kind.STEREO48K, bytes))

    fun poll(timeoutMs: Long): Chunk? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        null
    }

    fun clear() {
        queue.clear()
    }

    fun size(): Int = queue.size
}
