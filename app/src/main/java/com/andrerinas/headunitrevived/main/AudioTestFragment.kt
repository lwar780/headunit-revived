package com.andrerinas.headunitrevived.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTestFragment : Fragment() {

    private lateinit var micSourceSpinner: Spinner
    private lateinit var rmsMeter: View
    private lateinit var micStatus: TextView
    private lateinit var recordButton: Button
    private lateinit var playMicButton: Button
    private lateinit var openSettingsButton: Button
    private lateinit var outputDeviceText: TextView
    private lateinit var playToneButton: Button
    private lateinit var speakerStatus: TextView

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordedBuffer: ByteArray? = null
    private var isRecording = false
    private var isPlaying = false
    
    private val sampleRate: Int
        get() = App.provide(requireContext()).settings.micSampleRate

    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    
    private val sourceValues = intArrayOf(
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        100 // Custom BT_SCO
    )

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            updateMicStatus(getString(R.string.audio_test_tap), false)
            openSettingsButton.visibility = View.GONE
        } else {
            showPermissionError()
        }
    }

    private val audioDeviceCallback = if (PlatformGuard.hasAudioDeviceCallback) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateOutputDevice() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateOutputDevice() }
        }
    } else null

    private var scoReceiver: BroadcastReceiver? = null
    private var scoWaitingJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_audio_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        micSourceSpinner = view.findViewById(R.id.mic_source_spinner)
        rmsMeter = view.findViewById(R.id.rms_meter)
        micStatus = view.findViewById(R.id.mic_status)
        recordButton = view.findViewById(R.id.record_button)
        playMicButton = view.findViewById(R.id.play_mic_button)
        openSettingsButton = view.findViewById(R.id.open_settings_button)
        outputDeviceText = view.findViewById(R.id.output_device_text)
        playToneButton = view.findViewById(R.id.play_tone_button)
        speakerStatus = view.findViewById(R.id.speaker_status)

        setupSourceSpinner()
        setupListeners()
        updateOutputDevice()

        if (PlatformGuard.hasAudioDeviceCallback) {
            val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showPermissionError()
        }
    }

    private fun setupSourceSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.mic_input_sources,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        micSourceSpinner.adapter = adapter

        val currentSource = App.provide(requireContext()).settings.micInputSource
        val index = sourceValues.indexOf(currentSource).coerceAtLeast(0)
        micSourceSpinner.setSelection(index)

        view?.findViewById<Button>(R.id.save_source_button)?.setOnClickListener {
            val selectedSource = sourceValues[micSourceSpinner.selectedItemPosition]
            App.provide(requireContext()).settings.micInputSource = selectedSource
            Toast.makeText(requireContext(), "Saved as default mic source", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        recordButton.setOnClickListener {
            if (isRecording) stopRecording() else attemptStartRecording()
        }

        playMicButton.setOnClickListener {
            playRecordedAudio()
        }

        playToneButton.setOnClickListener {
            playTestTone()
        }

        openSettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun showPermissionError() {
        updateMicStatus(getString(R.string.audio_test_permission_needed), true)
        openSettingsButton.visibility = View.VISIBLE
        openSettingsButton.text = getString(R.string.open_settings)
    }

    private fun attemptStartRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val selectedSource = sourceValues[micSourceSpinner.selectedItemPosition]
        if (selectedSource == 100) {
            startScoAndRecord()
        } else {
            startRecording(selectedSource)
        }
    }

    private fun startScoAndRecord() {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        updateMicStatus("Starting Bluetooth SCO...", false)
        
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    scoWaitingJob?.cancel()
                    cleanupScoReceiver()
                    startRecording(MediaRecorder.AudioSource.MIC)
                }
            }
        }
        requireContext().registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true

        scoWaitingJob = lifecycleScope.launch {
            delay(5000)
            if (isActive) {
                cleanupScoReceiver()
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                updateMicStatus(getString(R.string.audio_test_bt_timeout), true)
            }
        }
    }

    private fun cleanupScoReceiver() {
        try {
            scoReceiver?.let { requireContext().unregisterReceiver(it) }
        } catch (e: Exception) {}
        scoReceiver = null
    }

    private fun startRecording(source: Int) {
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        if (minBufSize <= 0) {
            updateMicStatus(getString(R.string.audio_test_source_unavailable, "Hardware"), true)
            return
        }

        try {
            audioRecord = AudioRecord(source, sampleRate, channelIn, encoding, minBufSize * 2)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                updateMicStatus(getString(R.string.audio_test_source_unavailable, micSourceSpinner.selectedItem.toString()), true)
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            recordButton.text = "Stop"
            playMicButton.isEnabled = false
            updateMicStatus(getString(R.string.audio_test_recording), false)

            lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1024)
                val totalPcm = mutableListOf<Short>()
                var silentTimeMs = 0L
                var lastUpdateMs = 0L
                val startTime = System.currentTimeMillis()

                while (isRecording && (System.currentTimeMillis() - startTime) < 5000) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        for (i in 0 until read) totalPcm.add(buffer[i])
                        
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i] * buffer[i]
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms / 32768.0) else -96.0
                        val clampedDb = db.coerceIn(-96.0, 0.0)

                        if (clampedDb <= -95.0) silentTimeMs += 50 else silentTimeMs = 0

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 200) {
                            lastUpdateMs = now
                            withContext(Dispatchers.Main) {
                                updateRmsMeter(clampedDb)
                                if (silentTimeMs >= 2000) {
                                    updateMicStatus(getString(R.string.audio_test_mic_silent), true)
                                } else {
                                    updateMicStatus(getString(R.string.audio_test_recording), false)
                                }
                            }
                        }
                    }
                    delay(50)
                }

                stopRecording()
                
                val pcmData = ByteArray(totalPcm.size * 2)
                for (i in totalPcm.indices) {
                    val s = totalPcm[i]
                    pcmData[i * 2] = (s.toInt() and 0xFF).toByte()
                    pcmData[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                recordedBuffer = pcmData

                withContext(Dispatchers.Main) {
                    playMicButton.isEnabled = true
                    updateMicStatus(getString(R.string.audio_test_play_prompt), false)
                }
            }
        } catch (e: Exception) {
            updateMicStatus("Error: ${e.message}", true)
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            } catch (e: Exception) {}
        }
        audioRecord = null
        
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        
        lifecycleScope.launch(Dispatchers.Main) {
            recordButton.text = getString(R.string.audio_test_record)
        }
    }

    private fun updateMicStatus(text: String, isError: Boolean) {
        micStatus.text = text
        micStatus.setTextColor(if (isError) Color.RED else ContextCompat.getColor(requireContext(), R.color.text_primary))
    }

    private fun updateRmsMeter(db: Double) {
        val color = when {
            db > -30.0 -> ContextCompat.getColor(requireContext(), R.color.status_connected)
            db > -50.0 -> ContextCompat.getColor(requireContext(), R.color.status_searching)
            else -> ContextCompat.getColor(requireContext(), R.color.glass_error)
        }
        rmsMeter.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        val progress = ((db + 96) / 96.0).coerceIn(0.0, 1.0)
        rmsMeter.scaleX = progress.toFloat().coerceAtLeast(0.01f)
    }

    private fun playRecordedAudio() {
        val buffer = recordedBuffer ?: return
        isPlaying = true
        updateMicStatus(getString(R.string.audio_test_playing), false)
        playMicButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, channelOut, encoding,
                    buffer.size, AudioTrack.MODE_STATIC
                )
                audioTrack?.write(buffer, 0, buffer.size)
                audioTrack?.play()
                
                val durationMs = (buffer.size / 2.0 / sampleRate * 1000).toLong()
                delay(durationMs + 500)
            } finally {
                withContext(Dispatchers.Main) {
                    audioTrack?.release()
                    audioTrack = null
                    isPlaying = false
                    playMicButton.isEnabled = true
                    updateMicStatus(getString(R.string.audio_test_play_prompt), false)
                }
            }
        }
    }

    private fun playTestTone() {
        if (isPlaying) return
        isPlaying = true
        speakerStatus.text = getString(R.string.audio_test_playing)
        speakerStatus.visibility = View.VISIBLE
        playToneButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sr = 44100
                val duration = 2
                val numSamples = duration * sr
                val samples = ShortArray(numSamples * 2)
                for (i in 0 until numSamples) {
                    val s = (sin(2.0 * PI * 1000.0 * i / sr) * 16384.0).toInt().toShort()
                    samples[i * 2] = s
                    samples[i * 2 + 1] = s
                }

                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_STEREO, encoding,
                    samples.size * 2, AudioTrack.MODE_STATIC
                )
                audioTrack?.write(samples, 0, samples.size)
                audioTrack?.play()
                delay(2500)
            } finally {
                withContext(Dispatchers.Main) {
                    audioTrack?.release()
                    audioTrack = null
                    isPlaying = false
                    playToneButton.isEnabled = true
                    speakerStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun updateOutputDevice() {
        if (PlatformGuard.hasAudioDeviceCallback) {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            
            val active = devices.firstOrNull { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            
            val name = when (active?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                else -> active?.productName?.toString() ?: getString(R.string.audio_test_output_default)
            }
            outputDeviceText.text = getString(R.string.audio_test_output_device, name)
        } else {
            outputDeviceText.text = getString(R.string.audio_test_output_device, getString(R.string.audio_test_output_default))
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
        isPlaying = false
        audioTrack?.release()
        audioTrack = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (PlatformGuard.hasAudioDeviceCallback) {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        cleanupScoReceiver()
    }
}
