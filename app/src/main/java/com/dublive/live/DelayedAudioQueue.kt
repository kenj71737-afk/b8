package com.dublive.live

/**
 * v1.0.9 — port of ALDA FIX37's DelayedAudioQueue
 * (core/audio/DelayedAudioQueue.kt), with one addition for the dubbed
 * voice hold: a block can carry its own fixed due time.
 *
 * Two modes:
 *  - LIVE DELAY ([add] + [pollDue] with a delay): a block is due once
 *    (now − arrival) >= the current delay. The delay is read live on
 *    every poll, so a delay change takes effect on queued blocks
 *    immediately. Used by [DelayedBackgroundPlayer] for the original
 *    audio: when D moves, the already-captured audio follows at once.
 *  - FIXED HOLD ([add] with holdMs): the block is due at
 *    arrival + holdMs, whatever the delay is when it is polled. Used
 *    by [VoicePlayer] for the dubbed voice: each burst's hold is
 *    computed ONCE at its burst start (hold = D − latency of that
 *    burst), and every chunk of that burst waits exactly that long —
 *    a later burst's different hold must not retime chunks that are
 *    already queued.
 *
 * In both modes a block overdue by more than [staleMs] is dropped
 * instead of played, so the queue catches up after a delay decrease
 * instead of trailing behind forever. Bounded: the oldest blocks are
 * dropped past [maxBlocks] (26 s at 10 ms/block).
 *
 * Pure Kotlin, no Android dependencies, unit-testable. All methods are
 * synchronized (feed runs on the capture/socket thread, the playback
 * loop on another thread).
 */
class DelayedAudioQueue(
    private val maxBlocks: Int = 2600,
    private val staleMs: Long = 500L
) {
    private data class Block(
        val arrivalMs: Long,
        /** Fixed due time (fixed-hold mode), or null (live-delay mode). */
        val dueMs: Long?,
        val data: ByteArray
    )

    private val queue = ArrayDeque<Block>()

    /** Live-delay mode: due once (now − arrival) >= delayMs at poll time. */
    @Synchronized
    fun add(arrivalMs: Long, data: ByteArray) {
        queue.addLast(Block(arrivalMs, null, data))
        while (queue.size > maxBlocks) queue.removeFirst()
    }

    /** Fixed-hold mode: due at arrivalMs + holdMs, whatever the live
     * delay is when the block is polled. */
    @Synchronized
    fun add(arrivalMs: Long, data: ByteArray, holdMs: Long) {
        queue.addLast(Block(arrivalMs, arrivalMs + holdMs.coerceAtLeast(0L), data))
        while (queue.size > maxBlocks) queue.removeFirst()
    }

    /**
     * Drops blocks overdue by more than [staleMs], then returns the
     * oldest due block, or null when nothing is due yet. For a
     * live-delay block the due time is arrival + [delayMs]; for a
     * fixed-hold block it is the due time stored at [add] time.
     */
    @Synchronized
    fun pollDue(nowMs: Long, delayMs: Long): ByteArray? {
        while (true) {
            val head = queue.firstOrNull() ?: return null
            val dueAt = head.dueMs ?: (head.arrivalMs + delayMs)
            val overdue = nowMs - dueAt
            if (overdue > staleMs) {
                queue.removeFirst()
                continue
            }
            if (overdue < 0) return null
            queue.removeFirst()
            return head.data
        }
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() = queue.clear()
}
