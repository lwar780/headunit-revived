package com.andrerinas.headunitrevived.testing

import android.content.Context
import com.andrerinas.headunitrevived.utils.AppLog
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32

/**
 * Writes [SessionFrame] lists to .aapsession files with metadata sidecars.
 * Manages file rotation (500MB cap, oldest-first eviction).
 */
object SessionWriter {

    private const val MAGIC = "AAPS"
    private const val FORMAT_VERSION: Short = 1
    private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024 // 500MB
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    fun sessionsDir(context: Context): File {
        val dir = context.getExternalFilesDir("sessions")
            ?: File(context.filesDir, "sessions") // fallback
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Write a session to disk. Returns the session file, or null on failure.
     */
    fun writeSession(
        context: Context,
        sessionId: UUID,
        frames: List<SessionFrame>,
        metadata: SessionMetadata
    ): File? {
        if (frames.isEmpty()) return null

        val dir = sessionsDir(context)
        rotateIfNeeded(dir)

        val sessionFile = File(dir, "$sessionId.aapsession")
        val metaFile = File(dir, "$sessionId.meta.json")

        return try {
            // Write session frames
            val crc = CRC32()
            DataOutputStream(BufferedOutputStream(sessionFile.outputStream(), 8192)).use { out ->
                // Header (32 bytes)
                out.writeBytes(MAGIC)                           // 4B
                out.writeShort(FORMAT_VERSION.toInt())           // 2B
                out.writeShort(metadata.flags)                  // 2B
                out.writeLong(sessionId.mostSignificantBits)    // 8B
                out.writeLong(sessionId.leastSignificantBits)   // 8B
                out.writeLong(metadata.createdMs)                // 8B

                // Frames
                for (frame in frames) {
                    val frameBytes = frameToBytes(frame)
                    crc.update(frameBytes)
                    out.write(frameBytes)
                }

                // Footer (24 bytes)
                out.writeInt(frames.size)                               // 4B frameCount
                out.writeLong(metadata.durationMs)                      // 8B duration
                out.writeLong(metadata.errorFrameIndex)                 // 8B errorOffset
                out.writeInt(crc.value.toInt())                         // 4B checksum
            }

            // Write metadata sidecar
            metaFile.writeText(metadata.toJson(sessionId, frames.size, sessionFile.length()))

            AppLog.i("SessionWriter: Wrote ${frames.size} frames to $sessionFile (${sessionFile.length()} bytes)")
            sessionFile
        } catch (e: Exception) {
            AppLog.e("SessionWriter: Failed to write session", e)
            sessionFile.delete()
            metaFile.delete()
            null
        }
    }

    private fun frameToBytes(frame: SessionFrame): ByteArray {
        val baos = java.io.ByteArrayOutputStream(SessionFrame.HEADER_SIZE + frame.payload.size)
        DataOutputStream(baos).use { frame.writeTo(it) }
        return baos.toByteArray()
    }

    private fun rotateIfNeeded(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".aapsession") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return

        // Delete files older than 7 days
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val expired = files.filter { it.lastModified() < cutoff }
        for (f in expired) {
            deleteSessionFiles(f)
            files.remove(f)
        }

        // Delete oldest files until under 500MB
        var totalSize = files.sumOf { it.length() }
        while (totalSize > MAX_TOTAL_BYTES && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalSize -= oldest.length()
            deleteSessionFiles(oldest)
        }
    }

    private fun deleteSessionFiles(sessionFile: File) {
        val metaFile = File(sessionFile.parent, sessionFile.nameWithoutExtension + ".meta.json")
        sessionFile.delete()
        metaFile.delete()
    }

    /** List all sessions in the sessions directory. */
    fun listSessions(context: Context): List<File> {
        return sessionsDir(context)
            .listFiles { f -> f.name.endsWith(".aapsession") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Total storage used by all session files + metadata. */
    fun totalStorageBytes(context: Context): Long {
        return sessionsDir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Delete a single session and its metadata. */
    fun deleteSession(sessionFile: File) {
        deleteSessionFiles(sessionFile)
    }

    /** Delete all sessions. */
    fun deleteAllSessions(context: Context) {
        sessionsDir(context).listFiles()?.forEach { it.delete() }
    }
}

/**
 * Session metadata for the .meta.json sidecar.
 */
data class SessionMetadata(
    val appVersion: String,
    val androidApi: Int,
    val connectionType: String,
    val protocolFingerprint: String,
    val carFingerprint: String,
    val hasError: Boolean,
    val errorType: String = "",
    val errorFrameIndex: Long = -1,
    val durationMs: Long,
    val createdMs: Long = System.currentTimeMillis(),
    val flags: Int = 0
) {
    fun toJson(sessionId: UUID, frameCount: Int, fileSizeBytes: Long): String {
        return JSONObject().apply {
            put("sessionId", sessionId.toString())
            put("version", 1)
            put("appVersion", appVersion)
            put("androidApi", androidApi)
            put("connectionType", connectionType)
            put("protocolFingerprint", protocolFingerprint)
            put("carFingerprint", carFingerprint)
            put("hasError", hasError)
            if (hasError) {
                put("errorType", errorType)
                put("errorFrameIndex", errorFrameIndex)
            }
            put("frameCount", frameCount)
            put("durationMs", durationMs)
            put("fileSizeBytes", fileSizeBytes)
            put("created", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(createdMs)))
        }.toString(2)
    }
}
