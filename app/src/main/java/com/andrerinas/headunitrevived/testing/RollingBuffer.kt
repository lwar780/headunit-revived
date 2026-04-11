package com.andrerinas.headunitrevived.testing

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe rolling ring buffer that retains the most recent N minutes of
 * [SessionFrame]s, evicting oldest frames when the byte budget is exceeded.
 *
 * Design: lock-free ConcurrentLinkedDeque with atomic size tracking.
 * Write path (HandlerThread) adds to tail; eviction removes from head.
 * Read path (flush) drains the entire deque atomically.
 *
 * @param maxBytes Maximum buffer size in bytes. Default 1MB (≈8 min at 2.1 KB/s).
 * @param maxAgeMs Maximum frame age in milliseconds. Default 5 min (300,000 ms).
 */
class RollingBuffer(
    private val maxBytes: Int = 1_048_576,
    private val maxAgeMs: Long = 300_000L
) {
    private val frames = ConcurrentLinkedDeque<SessionFrame>()
    private val currentSizeBytes = AtomicInteger(0)
    private val frameCount = AtomicLong(0)

    /**
     * Add a frame to the buffer. Evicts oldest frames if size or age exceeded.
     */
    fun write(frame: SessionFrame) {
        frames.addLast(frame)
        currentSizeBytes.addAndGet(frame.estimatedSize)
        frameCount.incrementAndGet()

        // Evict by age
        val cutoff = frame.timestampMs - maxAgeMs
        while (true) {
            val oldest = frames.peekFirst() ?: break
            if (oldest.timestampMs < cutoff) {
                if (frames.pollFirst() != null) {
                    currentSizeBytes.addAndGet(-oldest.estimatedSize)
                }
            } else {
                break
            }
        }

        // Evict by size
        while (currentSizeBytes.get() > maxBytes) {
            val removed = frames.pollFirst() ?: break
            currentSizeBytes.addAndGet(-removed.estimatedSize)
        }
    }

    /**
     * Drain all buffered frames in order. Resets the buffer.
     * Returns frames sorted by timestamp (insertion order).
     */
    fun flush(): List<SessionFrame> {
        val result = mutableListOf<SessionFrame>()
        while (true) {
            val frame = frames.pollFirst() ?: break
            result.add(frame)
        }
        currentSizeBytes.set(0)
        return result
    }

    /** Current estimated memory usage in bytes. */
    fun estimatedSizeBytes(): Int = currentSizeBytes.get().coerceAtLeast(0)

    /** Number of frames currently in the buffer. */
    fun size(): Int = frames.size

    /** True if no frames are buffered. */
    fun isEmpty(): Boolean = frames.isEmpty()

    /** Discard all frames without returning them. */
    fun clear() {
        frames.clear()
        currentSizeBytes.set(0)
    }
}
