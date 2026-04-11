package com.andrerinas.headunitrevived.testing

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel

/**
 * Channel-aware depth filter that reduces session size by recording only headers
 * for high-bandwidth channels (audio/video) while keeping full payloads for
 * protocol-significant channels (control, sensor, input).
 *
 * Verified data rates (active driving + nav + music):
 * - Control: <1 msg/s, 10-60B payload → FULL (~50 B/s)
 * - Sensor: 10-25 msg/s, 24-128B payload → FULL (~1.5 KB/s)
 * - Input: event-driven 0-60Hz, 12-30B → FULL (~200 B/s peak)
 * - Video: 30-60 pkt/s → HEADER_ONLY (4B each = ~240 B/s)
 * - Audio: 50-100 pkt/s → HEADER_ONLY (4B each = ~400 B/s)
 * - Mic: ALWAYS DROPPED (privacy — never record microphone audio)
 *
 * Total: ~2.1 KB/s → 1MB buffer ≈ 8 minutes retention.
 */
object SkeletonFilter {

    enum class Depth { FULL_PAYLOAD, HEADER_ONLY, DROP }

    private val channelPolicy = mapOf(
        Channel.ID_CTR to Depth.FULL_PAYLOAD,   // Control: protocol state, errors
        Channel.ID_SEN to Depth.FULL_PAYLOAD,   // Sensor: GPS, vehicle data (sanitized)
        Channel.ID_INP to Depth.FULL_PAYLOAD,   // Input: touch, keys (no PII)
        Channel.ID_VID to Depth.HEADER_ONLY,    // Video: timing only, skip pixels
        Channel.ID_AU1 to Depth.HEADER_ONLY,    // Speech audio: timing only
        Channel.ID_AU2 to Depth.HEADER_ONLY,    // Nav audio: timing only
        Channel.ID_AUD to Depth.HEADER_ONLY,    // Media audio: timing only
        Channel.ID_MIC to Depth.DROP,            // Mic: NEVER record
        Channel.ID_BTH to Depth.FULL_PAYLOAD,   // Bluetooth: protocol state
        Channel.ID_MPB to Depth.FULL_PAYLOAD,   // Media playback: metadata (sanitized)
        Channel.ID_NAV to Depth.FULL_PAYLOAD,   // Navigation: turn-by-turn (sanitized)
        Channel.ID_PHONE to Depth.FULL_PAYLOAD, // Phone status (sanitized)
        Channel.ID_WIFI to Depth.FULL_PAYLOAD   // WiFi setup (sanitized)
    )

    /**
     * Filter a message to a SessionFrame based on channel policy.
     * Returns null for DROP channels (mic).
     */
    fun filter(
        message: AapMessage,
        timestampMs: Long,
        direction: SessionFrame.Direction
    ): SessionFrame? {
        val depth = channelPolicy[message.channel] ?: Depth.HEADER_ONLY

        return when (depth) {
            Depth.DROP -> null
            Depth.HEADER_ONLY -> SessionFrame(
                timestampMs = timestampMs,
                direction = direction,
                channel = message.channel,
                type = message.type,
                payload = ByteArray(0)
            )
            Depth.FULL_PAYLOAD -> SessionFrame(
                timestampMs = timestampMs,
                direction = direction,
                channel = message.channel,
                type = message.type,
                payload = if (message.size > message.dataOffset) {
                    message.data.copyOfRange(message.dataOffset, message.size)
                } else {
                    ByteArray(0)
                }
            )
        }
    }

    /** Get the policy for a channel (for testing). */
    fun policyFor(channel: Int): Depth = channelPolicy[channel] ?: Depth.HEADER_ONLY
}
