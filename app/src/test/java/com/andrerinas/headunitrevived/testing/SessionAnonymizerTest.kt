package com.andrerinas.headunitrevived.testing

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.MsgType
import com.andrerinas.headunitrevived.aap.protocol.proto.MediaPlayback
import com.andrerinas.headunitrevived.aap.protocol.proto.NavigationStatus
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.Test

class SessionAnonymizerTest {

    private val testSalt = ByteArray(16) { it.toByte() }

    @Test
    fun `sanitize sensor message zeroes GPS coordinates`() {
        // Build a sensor message with GPS data
        val location = Sensors.SensorBatch.LocationData.newBuilder()
            .setTimestamp(1000L)
            .setLatitude(324567890)
            .setLongitude(345678901)
            .setAccuracy(100)
            .setAltitude(500)
            .setSpeed(25)
            .setBearing(45)
            .build()

        val sensorBatch = Sensors.SensorBatch.newBuilder()
            .addLocationData(location)
            .build()

        val message = AapMessage(Channel.ID_SEN, 0x1234, sensorBatch)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        // Parse the sanitized message
        val sanitizedBatch = sanitized.parse(Sensors.SensorBatch.newBuilder()).build()

        assertThat(sanitizedBatch.locationDataCount).isEqualTo(1)
        assertThat(sanitizedBatch.getLocationData(0).latitude).isEqualTo(0)
        assertThat(sanitizedBatch.getLocationData(0).longitude).isEqualTo(0)
        assertThat(sanitizedBatch.getLocationData(0).accuracy).isEqualTo(0)
        assertThat(sanitizedBatch.getLocationData(0).hasAltitude()).isFalse()
        assertThat(sanitizedBatch.getLocationData(0).hasSpeed()).isFalse()
        assertThat(sanitizedBatch.getLocationData(0).hasBearing()).isFalse()
    }

    @Test
    fun `sanitize control message hashes string content`() {
        // Build a valid protobuf payload with a string field (field 1, wire type 2)
        val stringBytes = "MyPhoneModel123".toByteArray()
        val protoPayload = byteArrayOf(
            0x0A, // field 1, wire type 2 (length-delimited)
            stringBytes.size.toByte(),
            *stringBytes
        )
        val message = makeRawMessage(Channel.ID_CTR, 0x1234, protoPayload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        // Verify message structure preserved
        assertThat(sanitized.channel).isEqualTo(Channel.ID_CTR)
        assertThat(sanitized.type).isEqualTo(0x1234)
        assertThat(sanitized.flags).isEqualTo(message.flags)
        assertThat(sanitized.size).isEqualTo(message.size)

        // Verify content changed (hashed)
        val originalPayload = message.data.copyOfRange(message.dataOffset, message.size)
        val sanitizedPayload = sanitized.data.copyOfRange(sanitized.dataOffset, sanitized.size)
        assertThat(sanitizedPayload).isNotEqualTo(originalPayload)
    }

    @Test
    fun `sanitize wireless message strips content`() {
        // Build a valid protobuf payload with a string field (field 1, wire type 2)
        val stringBytes = "MyWiFiPassword123".toByteArray()
        val protoPayload = byteArrayOf(
            0x0A, // field 1, wire type 2 (length-delimited)
            stringBytes.size.toByte(),
            *stringBytes
        )
        val message = makeRawMessage(Channel.ID_WIFI, 0x5678, protoPayload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        // Structure preserved
        assertThat(sanitized.channel).isEqualTo(Channel.ID_WIFI)
        assertThat(sanitized.type).isEqualTo(0x5678)

        // Content changed
        val originalPayload = message.data.copyOfRange(message.dataOffset, message.size)
        val sanitizedPayload = sanitized.data.copyOfRange(sanitized.dataOffset, sanitized.size)
        assertThat(sanitizedPayload).isNotEqualTo(originalPayload)
    }

    @Test
    fun `sanitize input message passes through unchanged`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val message = makeRawMessage(Channel.ID_INP, 0x1111, payload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        assertThat(sanitized.channel).isEqualTo(message.channel)
        assertThat(sanitized.type).isEqualTo(message.type)
        assertThat(sanitized.flags).isEqualTo(message.flags)
        assertThat(sanitized.data).isEqualTo(message.data)
    }

    @Test
    fun `sanitize video message passes through unchanged`() {
        val payload = byteArrayOf(10, 20, 30, 40)
        val message = makeRawMessage(Channel.ID_VID, 0x2222, payload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        assertThat(sanitized.channel).isEqualTo(message.channel)
        assertThat(sanitized.type).isEqualTo(message.type)
        assertThat(sanitized.data).isEqualTo(message.data)
    }

    @Test
    fun `sanitize media playback strips albumart`() {
        val albumart = ByteArray(1000) { 0xFF.toByte() }
        val metadata = MediaPlayback.MediaMetaData.newBuilder()
            .setSong("Song Title")
            .setArtist("Artist Name")
            .setAlbum("Album Name")
            .setAlbumart(ByteString.copyFrom(albumart))
            .build()

        val message = AapMessage(Channel.ID_MPB, 0x3333, metadata)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        val sanitizedMeta = sanitized.parse(MediaPlayback.MediaMetaData.newBuilder()).build()

        assertThat(sanitizedMeta.song).isEqualTo("Song Title")
        assertThat(sanitizedMeta.artist).isEqualTo("Artist Name")
        assertThat(sanitizedMeta.album).isEqualTo("Album Name")
        assertThat(sanitizedMeta.albumart.size()).isEqualTo(0)
    }

    @Test
    fun `sanitize navigation redacts road name`() {
        val turnDetail = NavigationStatus.NextTurnDetail.newBuilder()
            .setRoad("Main Street")
            .setSide(NavigationStatus.NextTurnDetail.Side.LEFT)
            .setNextturn(NavigationStatus.NextTurnDetail.NextEvent.TURN)
            .build()

        val message = AapMessage(Channel.ID_NAV, 0x4444, turnDetail)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        val sanitizedDetail = sanitized.parse(NavigationStatus.NextTurnDetail.newBuilder()).build()

        assertThat(sanitizedDetail.road).isEqualTo("[REDACTED]")
        assertThat(sanitizedDetail.nextturn).isEqualTo(NavigationStatus.NextTurnDetail.NextEvent.TURN)
    }

    @Test
    fun `sanitize unknown channel zeros payload`() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val unknownChannel = 99
        val message = makeRawMessage(unknownChannel, 0x9999, payload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        assertThat(sanitized.channel).isEqualTo(unknownChannel)
        assertThat(sanitized.type).isEqualTo(0x9999)
        assertThat(sanitized.flags).isEqualTo(message.flags)

        // Payload should be zeroed (header preserved)
        val sanitizedPayload = sanitized.data.copyOfRange(sanitized.dataOffset, sanitized.size)
        assertThat(sanitizedPayload).isEqualTo(ByteArray(sanitizedPayload.size))
    }

    @Test
    fun `sanitize is idempotent`() {
        val location = Sensors.SensorBatch.LocationData.newBuilder()
            .setTimestamp(1000L)
            .setLatitude(324567890)
            .setLongitude(345678901)
            .setAccuracy(50)
            .build()
        val sensorBatch = Sensors.SensorBatch.newBuilder()
            .addLocationData(location)
            .build()

        val message = AapMessage(Channel.ID_SEN, 0x1234, sensorBatch)
        val sanitized1 = SessionAnonymizer.sanitize(message, testSalt)
        val sanitized2 = SessionAnonymizer.sanitize(sanitized1, testSalt)

        assertThat(sanitized2).isEqualTo(sanitized1)
    }

    @Test
    fun `sanitize preserves message structure`() {
        val message = makeRawMessage(Channel.ID_CTR, 0xABCD, "TestString".toByteArray())
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        assertThat(sanitized.channel).isEqualTo(message.channel)
        assertThat(sanitized.type).isEqualTo(message.type)
        assertThat(sanitized.flags).isEqualTo(message.flags)
        assertThat(sanitized.dataOffset).isEqualTo(AapMessage.HEADER_SIZE + MsgType.SIZE)
    }

    @Test
    fun `sanitize mic channel passes through`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val message = makeRawMessage(Channel.ID_MIC, 0x7777, payload)
        val sanitized = SessionAnonymizer.sanitize(message, testSalt)

        // MIC is a no-op in sanitizer (DROP happens in SkeletonFilter)
        assertThat(sanitized.channel).isEqualTo(message.channel)
        assertThat(sanitized.data).isEqualTo(message.data)
    }

    // --- Helpers ---

    private fun makeRawMessage(channel: Int, type: Int, payload: ByteArray): AapMessage {
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
