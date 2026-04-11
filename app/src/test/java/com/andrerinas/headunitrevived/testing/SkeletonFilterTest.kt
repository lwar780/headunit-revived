package com.andrerinas.headunitrevived.testing

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.MsgType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkeletonFilterTest {

    private val testTimestamp = 1234567890L

    @Test
    fun `control channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(10, 20, 30, 40)
        val message = makeMessage(Channel.ID_CTR, 0x1234, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.timestampMs).isEqualTo(testTimestamp)
        assertThat(frame.direction).isEqualTo(SessionFrame.Direction.FROM_CAR)
        assertThat(frame.channel).isEqualTo(Channel.ID_CTR)
        assertThat(frame.type).isEqualTo(0x1234)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `sensor channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val message = makeMessage(Channel.ID_SEN, 0x5678, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_SEN)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `input channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(7, 8, 9)
        val message = makeMessage(Channel.ID_INP, 0xABCD, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_INP)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `video channel returns HEADER_ONLY`() {
        val payload = ByteArray(1000) { it.toByte() }
        val message = makeMessage(Channel.ID_VID, 0x2222, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_VID)
        assertThat(frame.type).isEqualTo(0x2222)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun `audio channel AU1 returns HEADER_ONLY`() {
        val payload = ByteArray(500) { 0xFF.toByte() }
        val message = makeMessage(Channel.ID_AU1, 0x3333, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_AU1)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun `audio channel AU2 returns HEADER_ONLY`() {
        val payload = ByteArray(500) { 0xAA.toByte() }
        val message = makeMessage(Channel.ID_AU2, 0x4444, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_AU2)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun `audio channel AUD returns HEADER_ONLY`() {
        val payload = ByteArray(500) { 0xBB.toByte() }
        val message = makeMessage(Channel.ID_AUD, 0x5555, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_AUD)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun `mic channel returns null (DROP)`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val message = makeMessage(Channel.ID_MIC, 0x7777, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNull()
    }

    @Test
    fun `HEADER_ONLY preserves timestamp direction channel and type`() {
        val message = makeMessage(Channel.ID_VID, 0x9999, ByteArray(100))

        val frame = SkeletonFilter.filter(message, 9876543210L, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.timestampMs).isEqualTo(9876543210L)
        assertThat(frame.direction).isEqualTo(SessionFrame.Direction.TO_CAR)
        assertThat(frame.channel).isEqualTo(Channel.ID_VID)
        assertThat(frame.type).isEqualTo(0x9999)
        assertThat(frame.payload).isEmpty()
    }

    @Test
    fun `FULL_PAYLOAD copies payload correctly`() {
        val payload = byteArrayOf(100, 101, 102, 103, 104)
        val message = makeMessage(Channel.ID_CTR, 0x8888, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.payload).isEqualTo(payload)
        // Verify it's a copy, not the same array
        assertThat(frame.payload).isNotSameInstanceAs(payload)
    }

    @Test
    fun `bluetooth channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(1, 2, 3)
        val message = makeMessage(Channel.ID_BTH, 0x1111, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_BTH)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `media playback channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(10, 20)
        val message = makeMessage(Channel.ID_MPB, 0x2222, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_MPB)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `navigation channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(30, 40, 50)
        val message = makeMessage(Channel.ID_NAV, 0x3333, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_NAV)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `phone channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(60, 70)
        val message = makeMessage(Channel.ID_PHONE, 0x4444, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.TO_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_PHONE)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `wifi channel returns FULL_PAYLOAD`() {
        val payload = byteArrayOf(80, 90)
        val message = makeMessage(Channel.ID_WIFI, 0x5555, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(Channel.ID_WIFI)
        assertThat(frame.payload).isEqualTo(payload)
    }

    @Test
    fun `unknown channel defaults to HEADER_ONLY`() {
        val unknownChannel = 99
        val payload = byteArrayOf(1, 2, 3)
        val message = makeMessage(unknownChannel, 0x6666, payload)

        val frame = SkeletonFilter.filter(message, testTimestamp, SessionFrame.Direction.FROM_CAR)

        assertThat(frame).isNotNull()
        assertThat(frame!!.channel).isEqualTo(unknownChannel)
        assertThat(frame.payload).isEmpty()
    }

    // --- Helper ---

    private fun makeMessage(channel: Int, type: Int, payload: ByteArray): AapMessage {
        val headerSize = AapMessage.HEADER_SIZE
        val msgTypeSize = MsgType.SIZE
        val data = ByteArray(headerSize + msgTypeSize + payload.size)

        // Byte 0: channel
        data[0] = channel.toByte()
        // Byte 1: flags
        data[1] = 0x0b
        // Bytes 2-3: payload length (big-endian)
        val payloadLen = msgTypeSize + payload.size
        data[2] = (payloadLen shr 8).toByte()
        data[3] = (payloadLen and 0xFF).toByte()
        // Bytes 4-5: message type (big-endian)
        data[4] = (type shr 8).toByte()
        data[5] = (type and 0xFF).toByte()
        // Bytes 6+: payload
        System.arraycopy(payload, 0, data, headerSize + msgTypeSize, payload.size)

        return AapMessage(channel, 0x0b.toByte(), type, headerSize + msgTypeSize, data.size, data)
    }
}
