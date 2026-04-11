package com.andrerinas.headunitrevived.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionWriterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SessionWriter.deleteAllSessions(context)
    }

    @After
    fun teardown() {
        SessionWriter.deleteAllSessions(context)
    }

    @Test
    fun `writeSession creates aapsession and meta json files`() {
        val sessionId = UUID.randomUUID()
        val frames = listOf(
            SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1, 2, 3)),
            SessionFrame(100L, SessionFrame.Direction.TO_CAR, 0, 2, byteArrayOf(4, 5))
        )
        val metadata = testMetadata()

        val file = SessionWriter.writeSession(context, sessionId, frames, metadata)

        assertThat(file).isNotNull()
        assertThat(file!!.exists()).isTrue()
        assertThat(file.name).endsWith(".aapsession")

        val metaFile = java.io.File(file.parent, "$sessionId.meta.json")
        assertThat(metaFile.exists()).isTrue()
    }

    @Test
    fun `aapsession file has correct magic header`() {
        val sessionId = UUID.randomUUID()
        val frames = listOf(
            SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1, 2, 3))
        )
        val file = SessionWriter.writeSession(context, sessionId, frames, testMetadata())!!

        DataInputStream(file.inputStream()).use { input ->
            val magic = ByteArray(4)
            input.readFully(magic)
            assertThat(String(magic)).isEqualTo("AAPS")

            val version = input.readShort()
            assertThat(version).isEqualTo(1)
        }
    }

    @Test
    fun `writeSession with empty frames returns null`() {
        val result = SessionWriter.writeSession(context, UUID.randomUUID(), emptyList(), testMetadata())
        assertThat(result).isNull()
    }

    @Test
    fun `listSessions returns sessions sorted by date descending`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val frames = listOf(SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1)))

        SessionWriter.writeSession(context, id1, frames, testMetadata())
        Thread.sleep(50)
        SessionWriter.writeSession(context, id2, frames, testMetadata())

        val sessions = SessionWriter.listSessions(context)
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].name).contains(id2.toString())
    }

    @Test
    fun `deleteAllSessions clears directory`() {
        val frames = listOf(SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1)))
        SessionWriter.writeSession(context, UUID.randomUUID(), frames, testMetadata())
        SessionWriter.writeSession(context, UUID.randomUUID(), frames, testMetadata())

        assertThat(SessionWriter.listSessions(context)).hasSize(2)
        SessionWriter.deleteAllSessions(context)
        assertThat(SessionWriter.listSessions(context)).isEmpty()
    }

    @Test
    fun `totalStorageBytes returns accurate count`() {
        val frames = listOf(SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, ByteArray(100)))
        SessionWriter.writeSession(context, UUID.randomUUID(), frames, testMetadata())

        assertThat(SessionWriter.totalStorageBytes(context)).isGreaterThan(0L)
    }

    @Test
    fun `meta json is valid and contains required fields`() {
        val sessionId = UUID.randomUUID()
        val frames = listOf(SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1)))
        SessionWriter.writeSession(context, sessionId, frames, testMetadata(hasError = true, errorType = "PROTOCOL_ERROR"))

        val metaFile = java.io.File(SessionWriter.sessionsDir(context), "$sessionId.meta.json")
        val json = org.json.JSONObject(metaFile.readText())

        assertThat(json.getString("sessionId")).isEqualTo(sessionId.toString())
        assertThat(json.getInt("version")).isEqualTo(1)
        assertThat(json.getString("appVersion")).isNotEmpty()
        assertThat(json.getBoolean("hasError")).isTrue()
        assertThat(json.getString("errorType")).isEqualTo("PROTOCOL_ERROR")
    }

    @Test
    fun `sessions dir created automatically if missing`() {
        val dir = SessionWriter.sessionsDir(context)
        dir.deleteRecursively()
        assertThat(dir.exists()).isFalse()

        val frames = listOf(SessionFrame(0L, SessionFrame.Direction.FROM_CAR, 0, 1, byteArrayOf(1)))
        val file = SessionWriter.writeSession(context, UUID.randomUUID(), frames, testMetadata())

        assertThat(file).isNotNull()
        assertThat(dir.exists()).isTrue()
    }

    private fun testMetadata(hasError: Boolean = false, errorType: String = "") = SessionMetadata(
        appVersion = "2.2.0-beta3",
        androidApi = 28,
        connectionType = "USB",
        protocolFingerprint = "abc123",
        carFingerprint = "def456",
        hasError = hasError,
        errorType = errorType,
        durationMs = 1000L
    )
}
