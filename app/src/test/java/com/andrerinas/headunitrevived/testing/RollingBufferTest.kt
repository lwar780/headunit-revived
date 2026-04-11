package com.andrerinas.headunitrevived.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RollingBufferTest {

    @Test
    fun `write then flush returns the frame`() {
        val buffer = RollingBuffer(maxBytes = 1000, maxAgeMs = 10000)
        val frame = makeFrame(timestamp = 1000L, payload = byteArrayOf(1, 2, 3))

        buffer.write(frame)
        val flushed = buffer.flush()

        assertThat(flushed).hasSize(1)
        assertThat(flushed[0]).isEqualTo(frame)
    }

    @Test
    fun `write multiple frames flush returns all in order`() {
        val buffer = RollingBuffer(maxBytes = 10000, maxAgeMs = 10000)
        val frame1 = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        val frame2 = makeFrame(timestamp = 2000L, payload = byteArrayOf(2))
        val frame3 = makeFrame(timestamp = 3000L, payload = byteArrayOf(3))

        buffer.write(frame1)
        buffer.write(frame2)
        buffer.write(frame3)

        val flushed = buffer.flush()

        assertThat(flushed).hasSize(3)
        assertThat(flushed[0]).isEqualTo(frame1)
        assertThat(flushed[1]).isEqualTo(frame2)
        assertThat(flushed[2]).isEqualTo(frame3)
    }

    @Test
    fun `frames older than maxAgeMs are evicted`() {
        val buffer = RollingBuffer(maxBytes = 100000, maxAgeMs = 5000)

        val oldFrame = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        val recentFrame = makeFrame(timestamp = 7000L, payload = byteArrayOf(2))

        buffer.write(oldFrame)
        buffer.write(recentFrame)

        val flushed = buffer.flush()

        // oldFrame is 6000ms older than recentFrame, exceeds maxAgeMs of 5000
        assertThat(flushed).hasSize(1)
        assertThat(flushed[0]).isEqualTo(recentFrame)
    }

    @Test
    fun `buffer over maxBytes evicts oldest`() {
        val buffer = RollingBuffer(maxBytes = 100, maxAgeMs = 100000)

        // Each frame: 16 bytes header + payload size
        val frame1 = makeFrame(timestamp = 1000L, payload = ByteArray(30)) // ~46 bytes
        val frame2 = makeFrame(timestamp = 2000L, payload = ByteArray(30)) // ~46 bytes
        val frame3 = makeFrame(timestamp = 3000L, payload = ByteArray(30)) // ~46 bytes

        buffer.write(frame1)
        buffer.write(frame2)
        buffer.write(frame3) // Should evict frame1 and frame2 to stay under 100 bytes

        val flushed = buffer.flush()

        // Only the most recent frames should remain
        assertThat(flushed.size).isAtMost(2)
        assertThat(flushed).doesNotContain(frame1)
    }

    @Test
    fun `flush returns empty list on empty buffer`() {
        val buffer = RollingBuffer()

        val flushed = buffer.flush()

        assertThat(flushed).isEmpty()
    }

    @Test
    fun `flush clears the buffer`() {
        val buffer = RollingBuffer()
        val frame = makeFrame(timestamp = 1000L, payload = byteArrayOf(1, 2, 3))

        buffer.write(frame)
        buffer.flush()

        val secondFlush = buffer.flush()

        assertThat(secondFlush).isEmpty()
        assertThat(buffer.isEmpty()).isTrue()
    }

    @Test
    fun `estimatedSizeBytes tracks correctly`() {
        val buffer = RollingBuffer()
        val frame1 = makeFrame(timestamp = 1000L, payload = ByteArray(10))
        val frame2 = makeFrame(timestamp = 2000L, payload = ByteArray(20))

        assertThat(buffer.estimatedSizeBytes()).isEqualTo(0)

        buffer.write(frame1)
        val size1 = buffer.estimatedSizeBytes()
        assertThat(size1).isEqualTo(frame1.estimatedSize)

        buffer.write(frame2)
        val size2 = buffer.estimatedSizeBytes()
        assertThat(size2).isEqualTo(frame1.estimatedSize + frame2.estimatedSize)

        buffer.flush()
        assertThat(buffer.estimatedSizeBytes()).isEqualTo(0)
    }

    @Test
    fun `isEmpty returns true on empty false on non-empty`() {
        val buffer = RollingBuffer()

        assertThat(buffer.isEmpty()).isTrue()

        val frame = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        buffer.write(frame)

        assertThat(buffer.isEmpty()).isFalse()

        buffer.flush()

        assertThat(buffer.isEmpty()).isTrue()
    }

    @Test
    fun `clear discards all frames without returning them`() {
        val buffer = RollingBuffer()
        val frame1 = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        val frame2 = makeFrame(timestamp = 2000L, payload = byteArrayOf(2))

        buffer.write(frame1)
        buffer.write(frame2)

        assertThat(buffer.isEmpty()).isFalse()
        assertThat(buffer.estimatedSizeBytes()).isGreaterThan(0)

        buffer.clear()

        assertThat(buffer.isEmpty()).isTrue()
        assertThat(buffer.estimatedSizeBytes()).isEqualTo(0)

        val flushed = buffer.flush()
        assertThat(flushed).isEmpty()
    }

    @Test
    fun `age eviction happens on write not flush`() {
        val buffer = RollingBuffer(maxBytes = 100000, maxAgeMs = 1000)

        val frame1 = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        val frame2 = makeFrame(timestamp = 1500L, payload = byteArrayOf(2))
        val frame3 = makeFrame(timestamp = 3000L, payload = byteArrayOf(3)) // Will evict frame1 and frame2

        buffer.write(frame1)
        buffer.write(frame2)
        buffer.write(frame3)

        val flushed = buffer.flush()

        assertThat(flushed).hasSize(1)
        assertThat(flushed[0]).isEqualTo(frame3)
    }

    @Test
    fun `size eviction happens on write not flush`() {
        val buffer = RollingBuffer(maxBytes = 50, maxAgeMs = 100000)

        val frame1 = makeFrame(timestamp = 1000L, payload = ByteArray(20)) // ~36 bytes
        val frame2 = makeFrame(timestamp = 2000L, payload = ByteArray(20)) // ~36 bytes
        // Writing frame2 will exceed 50 bytes and evict frame1

        buffer.write(frame1)
        assertThat(buffer.size()).isEqualTo(1)

        buffer.write(frame2)
        // frame1 should be evicted
        assertThat(buffer.estimatedSizeBytes()).isLessThan(60)

        val flushed = buffer.flush()
        assertThat(flushed.size).isAtMost(1)
    }

    @Test
    fun `frames within age limit are retained`() {
        val buffer = RollingBuffer(maxBytes = 100000, maxAgeMs = 5000)

        val frame1 = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        val frame2 = makeFrame(timestamp = 3000L, payload = byteArrayOf(2))
        val frame3 = makeFrame(timestamp = 5000L, payload = byteArrayOf(3))

        buffer.write(frame1)
        buffer.write(frame2)
        buffer.write(frame3)

        val flushed = buffer.flush()

        // All frames within 5000ms of the latest frame
        assertThat(flushed).hasSize(3)
    }

    @Test
    fun `multiple writes and flushes work correctly`() {
        val buffer = RollingBuffer()

        val frame1 = makeFrame(timestamp = 1000L, payload = byteArrayOf(1))
        buffer.write(frame1)
        val flush1 = buffer.flush()
        assertThat(flush1).hasSize(1)

        val frame2 = makeFrame(timestamp = 2000L, payload = byteArrayOf(2))
        buffer.write(frame2)
        val flush2 = buffer.flush()
        assertThat(flush2).hasSize(1)
        assertThat(flush2[0]).isEqualTo(frame2)
    }

    // --- Helper ---

    private fun makeFrame(
        timestamp: Long,
        direction: SessionFrame.Direction = SessionFrame.Direction.FROM_CAR,
        channel: Int = 0,
        type: Int = 0,
        payload: ByteArray
    ): SessionFrame {
        return SessionFrame(timestamp, direction, channel, type, payload)
    }
}
