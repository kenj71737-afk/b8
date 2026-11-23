package com.alad.app.core.audio

/**
 * FIX37: timestamped delay queue for background audio.
 *
 * Blocks are enqueued with their arrival time; a block is due once
 * (now - arrival) >= the current delay. The delay is read live on every
 * poll, so a delay change takes effect on queued blocks immediately.
 * Blocks overdue by more than [staleMs] are dropped instead of played,
 * so the queue catches up after a delay decrease instead of trailing
 * behind forever. Bounded: the oldest blocks are dropped past [maxBlocks]
 * (26 s at 10 ms/block).
 *
 * Pure Kotlin, no Android dependencies, unit-testable. All methods are
 * synchronized (feed runs on the capture thread, the playback loop on a
 * coroutine).
 */
class DelayedAudioQueue(
    private val maxBlocks: Int = 2600,
    private val staleMs: Long = 500L
) {
    private data class Block(val arrivalMs: Long, val data: ByteArray)

    private val queue = ArrayDeque<Block>()

    @Synchronized
    fun add(arrivalMs: Long, data: ByteArray) {
        queue.addLast(Block(arrivalMs, data))
        while (queue.size > maxBlocks) queue.removeFirst()
    }

    /**
     * Drops blocks overdue by more than [staleMs], then returns the
     * oldest due block, or null when nothing is due yet.
     */
    @Synchronized
    fun pollDue(nowMs: Long, delayMs: Long): ByteArray? {
        while (true) {
            val head = queue.firstOrNull() ?: return null
            val overdue = nowMs - head.arrivalMs - delayMs
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
