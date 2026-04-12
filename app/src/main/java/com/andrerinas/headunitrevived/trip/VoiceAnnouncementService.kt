package com.andrerinas.headunitrevived.trip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.FeatureFlags
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.*

/**
 * Service that handles voice announcements using Text-to-Speech (TTS).
 * Subscribes to [TripEventBus] for trip events and uses a priority queue for playback.
 */
class VoiceAnnouncementService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val featureFlags by lazy { FeatureFlags(this) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    
    private val announcementQueue = PriorityQueue<Announcement>(compareByDescending { it.priority })
    private var isPlaying = false

    enum class Priority {
        INFORMATIONAL,
        CRITICAL
    }

    data class Announcement(
        val text: String,
        val priority: Priority,
        val id: String = UUID.randomUUID().toString()
    )

    override fun onCreate() {
        super.onCreate()
        if (!featureFlags.voiceAnnouncements) {
            AppLog.i("VoiceAnnouncementService: Feature disabled, stopping.")
            stopSelf()
            return
        }

        AppLog.i("VoiceAnnouncementService: Starting...")
        
        tts = TextToSpeech(this, this)
        
        observeEvents()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            AppLog.i("VoiceAnnouncementService: TTS initialized.")
            ttsInitialized = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    isPlaying = false
                    processQueue()
                }
                override fun onError(utteranceId: String?) {
                    isPlaying = false
                    processQueue()
                }
            })
            processQueue()
        } else {
            AppLog.e("VoiceAnnouncementService: TTS initialization failed.")
        }
    }

    private fun observeEvents() {
        serviceScope.launch {
            TripEventBus.getInstance().events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: TripEvent) {
        when (event) {
            is TripEvent.IgnitionDetected -> {
                if (event.shouldResumeNav) {
                    queueAnnouncement("Resume navigation?", Priority.CRITICAL)
                }
            }
            else -> { /* Ignore other events for now */ }
        }
    }

    private fun queueAnnouncement(text: String, priority: Priority) {
        announcementQueue.add(Announcement(text, priority))
        processQueue()
    }

    private fun processQueue() {
        if (!ttsInitialized || isPlaying) return
        
        val announcement = announcementQueue.poll() ?: return
        speak(announcement)
    }

    private fun speak(announcement: Announcement) {
        isPlaying = true
        
        val result = requestAudioFocus()
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLog.i("VoiceAnnouncementService: Speaking: ${announcement.text}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, announcement.id)
                tts?.speak(announcement.text, TextToSpeech.QUEUE_FLUSH, params, announcement.id)
            } else {
                @Suppress("DEPRECATION")
                val params = HashMap<String, String>()
                params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = announcement.id
                tts?.speak(announcement.text, TextToSpeech.QUEUE_FLUSH, params)
            }
        } else {
            AppLog.w("VoiceAnnouncementService: Could not gain audio focus.")
            isPlaying = false
            processQueue()
        }
    }

    private fun requestAudioFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        AppLog.i("VoiceAnnouncementService: Stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun intent(context: Context): Intent = Intent(context, VoiceAnnouncementService::class.java)
    }
}
