package com.andrerinas.headunitrevived.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.utils.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AapProtocolRecorderTest {

    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var recorder: AapProtocolRecorder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = Settings(context)
        recorder = AapProtocolRecorder(context, settings)
        SessionWriter.deleteAllSessions(context)
    }

    @After
    fun teardown() {
        SessionWriter.deleteAllSessions(context)
    }

    @Test
    fun `recordMessage is no-op when recording not started`() {
        val msg = makeControlMessage()
        recorder.recordMessage(msg, SessionFrame.Direction.FROM_CAR)
        // No crash, no files written
        assertThat(SessionWriter.listSessions(context)).isEmpty()
    }

    @Test
    fun `stopSession without error discards buffer`() {
        recorder.startSession()
        recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)
        recorder.stopSession()

        assertThat(SessionWriter.listSessions(context)).isEmpty()
    }

    @Test
    fun `onError flushes buffer to disk`() {
        recorder.startSession()
        recorder.connectionType = "USB"

        repeat(10) {
            recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)
        }

        recorder.onError("PROTOCOL_ERROR")
        recorder.stopSession()

        val sessions = SessionWriter.listSessions(context)
        assertThat(sessions).hasSize(1)
    }

    @Test
    fun `onManualFlush creates session file`() {
        recorder.startSession()

        repeat(5) {
            recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)
        }

        recorder.onManualFlush()
        recorder.stopSession()

        assertThat(SessionWriter.listSessions(context)).hasSize(1)
    }

    @Test
    fun `new session gets new ID`() {
        recorder.startSession()
        val first = recorder.lastSessionFile()

        recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)
        recorder.onError("TEST")
        recorder.stopSession()

        recorder.startSession()
        recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)
        recorder.onError("TEST")
        recorder.stopSession()

        val sessions = SessionWriter.listSessions(context)
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].name).isNotEqualTo(sessions[1].name)
    }

    @Test
    fun `mic channel messages are filtered out`() {
        recorder.startSession()

        // Record mic message — should be dropped by SkeletonFilter
        val micMsg = makeMessage(Channel.ID_MIC, 0)
        recorder.recordMessage(micMsg, SessionFrame.Direction.FROM_CAR)

        // Record control message — should be kept
        recorder.recordMessage(makeControlMessage(), SessionFrame.Direction.FROM_CAR)

        recorder.onError("TEST")
        recorder.stopSession()

        // Session should exist but mic frame should not be in it
        assertThat(SessionWriter.listSessions(context)).hasSize(1)
    }

    private fun makeControlMessage(): AapMessage {
        return makeMessage(Channel.ID_CTR, 1)
    }

    private fun makeMessage(channel: Int, type: Int): AapMessage {
        val payload = byteArrayOf(10, 20, 30, 40)
        val data = ByteArray(6 + payload.size)
        data[0] = channel.toByte()
        data[1] = 0x0b
        val payloadLen = payload.size + 2 // + MsgType.SIZE
        data[2] = (payloadLen shr 8).toByte()
        data[3] = (payloadLen and 0xFF).toByte()
        data[4] = (type shr 8).toByte()
        data[5] = (type and 0xFF).toByte()
        System.arraycopy(payload, 0, data, 6, payload.size)
        return AapMessage(channel, 0x0b.toByte(), type, 6, data.size, data)
    }
}
