package com.andrerinas.headunitrevived.testing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Single recorded AAP protocol frame with timing and direction metadata.
 * Binary format: 8B timestamp + 1B direction + 1B channel + 2B type + 4B payloadLen + payload
 */
data class SessionFrame(
    val timestampMs: Long,
    val direction: Direction,
    val channel: Int,
    val type: Int,
    val payload: ByteArray
) {
    enum class Direction(val value: Byte) {
        FROM_CAR(0),
        TO_CAR(1);

        companion object {
            fun fromByte(b: Byte) = if (b == 0.toByte()) FROM_CAR else TO_CAR
        }
    }

    /** Estimated memory footprint in bytes (header + payload). */
    val estimatedSize: Int get() = HEADER_SIZE + payload.size

    fun writeTo(out: DataOutputStream) {
        out.writeLong(timestampMs)
        out.writeByte(direction.value.toInt())
        out.writeByte(channel)
        out.writeShort(type)
        out.writeInt(payload.size)
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionFrame) return false
        return timestampMs == other.timestampMs &&
            direction == other.direction &&
            channel == other.channel &&
            type == other.type &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + channel
        result = 31 * result + type
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** Per-frame header: 8 + 1 + 1 + 2 + 4 = 16 bytes */
        const val HEADER_SIZE = 16

        fun readFrom(input: DataInputStream): SessionFrame {
            val ts = input.readLong()
            val dir = Direction.fromByte(input.readByte())
            val ch = input.readUnsignedByte()
            val type = input.readUnsignedShort()
            val len = input.readInt()
            val payload = if (len > 0) {
                ByteArray(len).also { input.readFully(it) }
            } else {
                ByteArray(0)
            }
            return SessionFrame(ts, dir, ch, type, payload)
        }
    }
}
