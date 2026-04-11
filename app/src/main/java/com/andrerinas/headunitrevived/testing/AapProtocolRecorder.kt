package com.andrerinas.headunitrevived.testing

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates AAP session recording: sanitize → skeleton → rolling buffer.
 *
 * Recording modes:
 * - **Normal**: Frames go into 1MB rolling buffer (≈8 min). On stop, buffer is discarded.
 * - **Error capture**: On error signal, buffer flushes to disk + 10 min forward capture starts.
 * - **Manual capture**: On shake/user trigger, same as error capture.
 *
 * Thread safety: recordMessage() is called from HandlerThread (THREAD_PRIORITY_AUDIO).
 * Error/flush may be called from any thread. All state is atomic or synchronized.
 *
 * Recording is gated by:
 * - API 26+ (testing features require Android 8+)
 * - BuildConfig.DEBUG → auto-enabled
 * - Release builds → settings.sessionRecordingEnabled must be true
 */
class AapProtocolRecorder(
    private val context: Context,
    private val settings: Settings
) {
    private val sessionId = AtomicReference(UUID.randomUUID())
    private val sessionSalt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val buffer = RollingBuffer()
    private val recording = AtomicBoolean(false)
    private val errorCapturing = AtomicBoolean(false)
    private val sessionStartMs = AtomicReference(0L)
    private val errorFrameIndex = AtomicReference(-1L)
    private val errorType = AtomicReference("")
    private val frameCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val messageTypesExchanged = mutableSetOf<Int>()

    // Forward capture: after error, capture this many more ms
    private val forwardCaptureMs = 10L * 60 * 1000 // 10 minutes
    private val errorTimestamp = AtomicReference(0L)

    /** Connection type for metadata (set by AapService). */
    var connectionType: String = "unknown"

    /** Car fingerprint hash (set by AapService during handshake). */
    var carFingerprint: String = ""

    /** Active mic source name (set by AapService for metadata). */
    var activeMicSource: String = "unknown"

    /**
     * Check if recording should be active based on API level and settings.
     */
    fun isRecordingEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false // API 26+ only
        return if (BuildConfig.DEBUG) true else settings.sessionRecordingEnabled
    }

    /**
     * Start recording a new session. Call when AAP connection is established.
     */
    fun startSession() {
        if (!isRecordingEnabled()) return
        sessionId.set(UUID.randomUUID())
        SecureRandom().nextBytes(sessionSalt)
        buffer.clear()
        recording.set(true)
        errorCapturing.set(false)
        sessionStartMs.set(SystemClock.elapsedRealtime())
        errorFrameIndex.set(-1)
        errorType.set("")
        frameCounter.set(0)
        messageTypesExchanged.clear()
        AppLog.i("AapProtocolRecorder: Session started (${sessionId.get()})")
    }

    /**
     * Record a single AAP message. Called from HandlerThread.
     * Pipeline: sanitize → skeleton → buffer.write
     */
    fun recordMessage(message: AapMessage, direction: SessionFrame.Direction) {
        if (!recording.get()) return

        // Check forward capture timeout
        if (errorCapturing.get()) {
            val elapsed = SystemClock.elapsedRealtime() - errorTimestamp.get()
            if (elapsed > forwardCaptureMs) {
                flushToDisk()
                return
            }
        }

        // Track message types for protocol fingerprint
        synchronized(messageTypesExchanged) {
            messageTypesExchanged.add(message.channel * 10000 + message.type)
        }

        val timestampMs = SystemClock.elapsedRealtime() - sessionStartMs.get()

        // Pipeline: sanitize → skeleton → buffer
        val sanitized = SessionAnonymizer.sanitize(message, sessionSalt)
        val frame = SkeletonFilter.filter(sanitized, timestampMs, direction) ?: return
        buffer.write(frame)
        frameCounter.incrementAndGet()
    }

    /**
     * Signal that an error occurred. Flushes the rolling buffer to disk
     * and starts 10-minute forward capture.
     */
    fun onError(type: String) {
        if (!recording.get()) return
        if (errorCapturing.get()) return // Already capturing

        errorFrameIndex.set(frameCounter.get())
        errorType.set(type)
        errorTimestamp.set(SystemClock.elapsedRealtime())
        errorCapturing.set(true)
        AppLog.i("AapProtocolRecorder: Error captured ($type), starting forward capture")
    }

    /**
     * Manual flush triggered by user (shake, QS tile, notification).
     * Same behavior as error: flush buffer + 10 min forward capture.
     */
    fun onManualFlush() {
        if (!recording.get()) return
        onError("USER_TRIGGERED")
    }

    /**
     * Stop recording and discard buffer (normal disconnect, no error).
     */
    fun stopSession() {
        if (!recording.get()) return

        if (errorCapturing.get()) {
            // Error was captured — flush whatever we have
            flushToDisk()
        } else {
            // Normal stop — discard buffer
            buffer.clear()
            AppLog.i("AapProtocolRecorder: Session discarded (no error)")
        }
        recording.set(false)
        errorCapturing.set(false)
    }

    private fun flushToDisk() {
        val frames = buffer.flush()
        if (frames.isEmpty()) {
            AppLog.i("AapProtocolRecorder: Nothing to flush")
            recording.set(false)
            return
        }

        val durationMs = if (frames.size >= 2) {
            frames.last().timestampMs - frames.first().timestampMs
        } else 0L

        val metadata = SessionMetadata(
            appVersion = BuildConfig.VERSION_NAME,
            androidApi = Build.VERSION.SDK_INT,
            connectionType = connectionType,
            protocolFingerprint = computeProtocolFingerprint(),
            carFingerprint = carFingerprint,
            hasError = errorType.get().isNotEmpty() && errorType.get() != "USER_TRIGGERED",
            errorType = errorType.get(),
            errorFrameIndex = errorFrameIndex.get(),
            durationMs = durationMs,
            flags = if (errorType.get().isNotEmpty()) 0x01 else 0x00,
            activeMicSource = activeMicSource
        )

        val file = SessionWriter.writeSession(context, sessionId.get(), frames, metadata)
        if (file != null) {
            AppLog.i("AapProtocolRecorder: Session flushed to ${file.name}")
        }

        recording.set(false)
        errorCapturing.set(false)
    }

    private fun computeProtocolFingerprint(): String {
        val types = synchronized(messageTypesExchanged) {
            messageTypesExchanged.sorted()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (t in types) {
            digest.update(t.toString().toByteArray())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Get the most recent session file (for share after manual flush). */
    fun lastSessionFile(): java.io.File? {
        return SessionWriter.listSessions(context).firstOrNull()
    }
}
