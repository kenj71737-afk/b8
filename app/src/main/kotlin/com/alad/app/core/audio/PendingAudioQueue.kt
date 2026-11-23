package com.alad.app.core.audio

/**
 * FIX36: ordered send queue for source audio, so a reconnect never eats a
 * sentence.
 *
 * Before FIX36 the socket kept only 2 packets (20 ms) while a new session
 * set up — every key rotation (every 5-6 minutes) discarded 1-3 s of source
 * speech, so the sentence spanning the gap was never translated. The
 * quiet-moment rotation of FIX34 could not avoid it: drama dialogue rarely
 * leaves a 3 s gap with no speech AND no output, so most rotations were
 * forced at the 6-minute deadline, mid-sentence.
 *
 * This queue holds up to [maxBlocks] blocks (6 s at 10 ms/block) and drains
 * oldest-first at [drainPerTick] blocks per input tick (2x real-time), so a
 * reconnect backlog clears in half its length without flooding the socket
 * queue (a full flush would trip the 64 KB congestion drop and lose audio
 * anyway). If the socket is congested, the drain stops and the blocks stay
 * queued for the next tick instead of being dropped.
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class PendingAudioQueue(
    private val maxBlocks: Int = 600,
    private val drainPerTick: Int = 2
) {
    private val queue = ArrayDeque<String>()

    fun size(): Int = queue.size

    fun add(block: String) {
        queue.addLast(block)
        while (queue.size > maxBlocks) queue.removeFirst()
    }

    fun clear() = queue.clear()

    /**
     * Send up to [drainPerTick] blocks, oldest first. [send] returns false
     * when the socket is congested — the drain stops and the unsent block
     * stays queued. Returns the number of blocks sent.
     */
    fun drain(send: (String) -> Boolean): Int {
        var sent = 0
        while (sent < drainPerTick && queue.isNotEmpty()) {
            if (!send(queue.first())) break
            queue.removeFirst()
            sent++
        }
        return sent
    }
}
