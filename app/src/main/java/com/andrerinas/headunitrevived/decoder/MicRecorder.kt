package com.andrerinas.headunitrevived.decoder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import kotlin.math.log10
import kotlin.math.sqrt

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private val settings = Settings(context)

    private val micBufferSize: Int
    private var micAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    /** Human-readable name of the audio source that actually initialized. */
    var activeSourceName: String = "None"
        private set

    init {
        val minSize = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            AppLog.w("MicRecorder: getMinBufferSize returned $minSize, mic recording unavailable")
            micBufferSize = 0
            micAudioBuf = ByteArray(0)
            isAvailable = false
        } else {
            micBufferSize = minSize
            micAudioBuf = ByteArray(minSize)
            isAvailable = true
        }
    }

    private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null
    var micStatusListener: MicStatusListener? = null

    // Tracks whether this instance started Bluetooth SCO so we can clean it up
    private var bluetoothScoStarted = false
    private var scoReceiver: BroadcastReceiver? = null

    // Throttle for status callbacks and periodic logging
    private var lastStatusUpdateMs = 0L
    private var lastLogMs = 0L
    private var framesRead = 0L

    companion object {
        const val SOURCE_BLUETOOTH_SCO = 100
        private const val STATUS_INTERVAL_MS = 200L
        private const val LOG_INTERVAL_MS = 5000L

        fun sourceNameFor(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "Default"
            MediaRecorder.AudioSource.MIC -> "Built-in Mic"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Voice Recognition"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Voice Comm"
            SOURCE_BLUETOOTH_SCO -> "Bluetooth SCO"
            else -> "Source($source)"
        }
    }

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    interface MicStatusListener {
        fun onMicStatus(sourceName: String, rmsDb: Float, isActive: Boolean)
    }

    fun stop() {
        AppLog.i("MicRecorder: Stopping. Active: $threadMicAudioActive")
        
        threadMicAudioActive = false
        threadMicAudio?.interrupt()
        threadMicAudio = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                AppLog.e("MicRecorder: Error releasing AudioRecord", e)
            }
        }
        audioRecord = null

        if (bluetoothScoStarted) {
            cleanupSco()
        }
    }

    private fun cleanupSco() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {}
        scoReceiver = null
        
        audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        bluetoothScoStarted = false
        AppLog.i("MicRecorder: Bluetooth SCO stopped")
    }

    private fun micAudioRead(aud_buf: ByteArray, max_len: Int): Int {
        val currentAudioRecord = audioRecord ?: return 0
        val currentListener = listener ?: return 0

        val len = currentAudioRecord.read(aud_buf, 0, max_len)
        if (len <= 0) {
            if (len == AudioRecord.ERROR_INVALID_OPERATION && threadMicAudioActive) {
                AppLog.e("MicRecorder: Unexpected interruption error: $len")
            }
            return len
        }

        framesRead++

        // Compute RMS on PCM 16-bit LE samples already in hand
        val sampleCount = len / 2
        if (sampleCount > 0) {
            var sum = 0L
            for (i in 0 until len step 2) {
                val sample = (aud_buf[i + 1].toInt() shl 8) or (aud_buf[i].toInt() and 0xFF)
                sum += sample.toLong() * sample
            }
            val rms = sqrt(sum.toDouble() / sampleCount).toFloat()
            val rmsDb = if (rms > 0) 20f * log10(rms / 32768f) else -96f

            val now = SystemClock.elapsedRealtime()

            // Status callback throttled to 200ms
            if (now - lastStatusUpdateMs > STATUS_INTERVAL_MS) {
                lastStatusUpdateMs = now
                micStatusListener?.onMicStatus(activeSourceName, rmsDb, true)
            }

            // Periodic logging every 5s
            if (now - lastLogMs > LOG_INTERVAL_MS) {
                lastLogMs = now
                AppLog.i("MicRecorder: source=%s rmsDb=%.1f frames=%d", activeSourceName, rmsDb, framesRead)
            }
        }

        currentListener.onMicDataAvailable(aud_buf, len)
        return len
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }

        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PermissionChecker.PERMISSION_GRANTED) {
            AppLog.e("MicRecorder: No RECORD_AUDIO permission")
            return -3
        }

        framesRead = 0
        lastStatusUpdateMs = 0
        lastLogMs = 0

        val configuredSource = settings.micInputSource

        if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            // BT SCO uses async flow — if it fails, onScoFallback tries the chain
            startScoAndRecord()
        } else {
            startWithFallback(configuredSource)
        }

        return 0
    }

    /**
     * Try the preferred source first, then fall through a priority chain.
     * Each source is tested with AudioRecord.STATE_INITIALIZED before use.
     */
    private fun startWithFallback(preferredSource: Int) {
        val fallbackChain = listOf(
            preferredSource,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        ).distinct() // Remove duplicates if preferred == MIC or DEFAULT

        for (source in fallbackChain) {
            if (tryInitSource(source)) {
                AppLog.i("MicRecorder: Using source %s (preferred was %s)",
                    sourceNameFor(source), sourceNameFor(preferredSource))
                activeSourceName = sourceNameFor(source)
                audioRecord?.startRecording()
                threadMicAudioActive = true
                threadMicAudio = Thread({
                    while (threadMicAudioActive) {
                        micAudioRead(micAudioBuf, micBufferSize)
                    }
                    // Notify inactive when thread ends
                    micStatusListener?.onMicStatus(activeSourceName, -96f, false)
                }, "mic_audio").apply { start() }
                return
            }
        }

        AppLog.e("MicRecorder: All sources failed to initialize")
        activeSourceName = "None (all failed)"
        micStatusListener?.onMicStatus(activeSourceName, -96f, false)
    }

    /** Try to create and initialize an AudioRecord for the given source. */
    private fun tryInitSource(source: Int): Boolean {
        return try {
            val record = AudioRecord(source, micSampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = record
                true
            } else {
                AppLog.w("MicRecorder: Source %s failed to initialize", sourceNameFor(source))
                record.release()
                false
            }
        } catch (e: Exception) {
            AppLog.w("MicRecorder: Source %s threw: %s", sourceNameFor(source), e.message)
            false
        }
    }

    private fun startScoAndRecord() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                AppLog.d("MicRecorder: SCO State change: $state")

                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    AppLog.i("MicRecorder: SCO Connected. Starting AudioRecord via BT.")
                    activeSourceName = "Bluetooth SCO"
                    startWithFallback(MediaRecorder.AudioSource.MIC)
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && bluetoothScoStarted) {
                    AppLog.w("MicRecorder: SCO Disconnected. Falling back to non-BT source.")
                    cleanupSco()
                    // Fall back to non-BT sources instead of stopping
                    if (audioRecord == null) {
                        startWithFallback(MediaRecorder.AudioSource.MIC)
                    }
                }
            }
        }

        ContextCompat.registerReceiver(context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)

        AppLog.i("MicRecorder: Starting Bluetooth SCO...")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        bluetoothScoStarted = true
    }
}
