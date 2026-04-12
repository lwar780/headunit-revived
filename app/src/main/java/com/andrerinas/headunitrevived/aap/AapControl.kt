package com.andrerinas.headunitrevived.aap

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Input
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.BuildConfig
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat

interface AapControl {
    fun execute(message: AapMessage): Int
    fun stop() {}
}

internal class AapControlGateway(
        private val aapTransport: AapTransport,
        private val controlService: AapControlService,
        private val mediaControl: AapControlMedia,
        private val touchControl: AapControlTouch,
        private val sensorControl: AapControlSensor) : AapControl {

    constructor(aapTransport: AapTransport,
                micRecorder: MicRecorder,
                aapAudio: AapAudio,
                settings: Settings,
                context: Context) : this(
            aapTransport,
            AapControlService(aapTransport, aapAudio, settings, context, micRecorder),
            AapControlMedia(aapTransport, micRecorder, aapAudio),
            AapControlTouch(aapTransport),
            AapControlSensor(aapTransport, context))

    override fun stop() {
        controlService.stop()
        mediaControl.stop()
        touchControl.stop()
        sensorControl.stop()
    }

    override fun execute(message: AapMessage): Int {

        when (message.channel) {
            Channel.ID_CTR -> return controlService.execute(message)
            Channel.ID_INP -> return touchControl.execute(message)
            Channel.ID_SEN -> return sensorControl.execute(message)
            Channel.ID_VID, Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_MIC -> return mediaControl.execute(message)
        }
        return 0
    }
}

internal class AapControlService(
        private val aapTransport: AapTransport,
        private val aapAudio: AapAudio,
        private val settings: Settings,
        private val context: Context,
        private val micRecorder: MicRecorder): AapControl {

    private val retryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLog.i("Voice Session: Retry requested by user")
            val result = micRecorder.start()
            AppLog.i("Voice Session: Retry result=%d", result)
            if (result != 0) {
                // Broadcast failure again
                context.sendBroadcast(Intent("com.andrerinas.headunitrevived.MIC_FAILED").apply {
                    putExtra("error_code", result)
                    putExtra("error_message", when (result) {
                        -3 -> "Microphone permission denied"
                        -4 -> "Microphone not available on device"
                        else -> "Microphone initialization failed"
                    })
                })
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, retryReceiver,
            IntentFilter("com.andrerinas.headunitrevived.RETRY_MIC"),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun stop() {
        try { context.unregisterReceiver(retryReceiver) } catch (e: Exception) {}
    }

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> {
                val request = message.parse(Control.ServiceDiscoveryRequest.newBuilder()).build()
                return serviceDiscoveryRequest(request)
            }
            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE -> {
                val request = message.parse(Control.ChannelOpenRequest.newBuilder()).build()
                return channelOpenRequest(request, message.channel)
            }
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> {
                val request = message.parse(Control.PingRequest.newBuilder()).build()
                return pingRequest(request)
            }
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> {
                val request = message.parse(Control.AudioFocusRequestNotification.newBuilder()).build()
                return audioFocusRequest(request, message.channel)
            }
            Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> {
                val request = message.parse(Control.VoiceSessionNotification.newBuilder()).build()
                return voiceSessionNotification(request)
            }
            Control.ControlMsgType.MESSAGE_VERSION_RESPONSE_VALUE -> {
                // Already handled in AapTransport handshake.
            }
            else -> {
                AppLog.w("Control: Unhandled message type: ${message.type}")
            }
        }
        return 0
    }


    private fun serviceDiscoveryRequest(request: Control.ServiceDiscoveryRequest): Int {
        AppLog.i("Service Discovery Request: %s", request.phoneName)

        val msg = ServiceDiscoveryResponse(context)
        aapTransport.send(msg)

        return 0
    }

    private fun channelOpenRequest(request: Control.ChannelOpenRequest, channel: Int): Int {
        AppLog.i("Channel Open Request: %d", channel)

        val response = Control.ChannelOpenResponse.newBuilder()
                .setStatus(Control.Status.STATUS_SUCCESS)
                .build()
        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, response)
        aapTransport.send(msg)

        if (channel == Channel.ID_SEN) {
            aapTransport.send(DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED))
        }
        return 0
    }

    private fun pingRequest(request: Control.PingRequest): Int {
        val response = Control.PingResponse.newBuilder()
                .setTimestamp(request.timestamp)
                .build()
        val msg = AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, response)
        aapTransport.send(msg)
        return 0
    }

    private fun voiceSessionNotification(request: Control.VoiceSessionNotification): Int {
        if (BuildConfig.DEBUG) {
            AppLog.d("DEBUG: voiceSessionNotification() called. status=%s, thread=%s",
                request.status, Thread.currentThread().name)
        }

        if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START) {
            AppLog.i("Voice Session Notification: START")
            if (BuildConfig.DEBUG) {
                AppLog.d("DEBUG: Calling micRecorder.start(). isAvailable=%b, activeSource=%s",
                    micRecorder.isAvailable, micRecorder.activeSourceName)
            }
            val result = micRecorder.start()
            AppLog.i("Voice Session: micRecorder.start() returned %d", result)

            if (result != 0) {
                AppLog.e("Voice Session: Mic failed to start (code %d). Broadcasting failure to UI.", result)
                val intent = Intent("com.andrerinas.headunitrevived.MIC_FAILED").apply {
                    putExtra("error_code", result)
                    putExtra("error_message", when (result) {
                        -3 -> "Microphone permission denied"
                        -4 -> "Microphone not available on device"
                        else -> "Microphone initialization failed"
                    })
                }
                context.sendBroadcast(intent)
                return -1
            }
        } else if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_STOP) {
            AppLog.i("Voice Session Notification: STOP")
            micRecorder.stop()
        }
        return 0
    }

    private fun audioFocusRequest(notification: Control.AudioFocusRequestNotification, channel: Int): Int {
        AppLog.i("Audio Focus Request: ${notification.request}")

        val focusResponse = mapOf(
                Control.AudioFocusRequest.AUDIO_FOCUS_GAIN to Control.AudioFocusState.AUDIO_FOCUS_STATE_GAIN,
                Control.AudioFocusRequest.AUDIO_FOCUS_GAIN_TRANSIENT to Control.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
                Control.AudioFocusRequest.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK to Control.AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_MAY_DUCK,
                Control.AudioFocusRequest.AUDIO_FOCUS_RELEASE to Control.AudioFocusState.AUDIO_FOCUS_STATE_LOSS
        )

        val mappedState = focusResponse[notification.request]
        if (mappedState != null) {
            val response = Control.AudioFocusNotification.newBuilder()
                .setFocusState(mappedState)
                .build()
            val msg = AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, response)
            aapTransport.send(msg)
        }
        return 0
    }
}

internal class AapControlMedia(private val aapTransport: AapTransport, private val micRecorder: MicRecorder, private val aapAudio: AapAudio) : AapControl {
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Media.MediaMsgType.MESSAGE_MEDIA_CHANNEL_INDICATOR_VALUE -> {
                val notification = message.parse(Media.MediaChannelIndicator.newBuilder()).build()
                aapTransport.setSessionId(message.channel, notification.sessionId)
            }
            Media.MediaMsgType.MESSAGE_MEDIA_SETUP_REQUEST_VALUE -> {
                val response = Media.MediaSetupResponse.newBuilder()
                        .setStatus(Control.Status.STATUS_SUCCESS)
                        .build()
                val msg = AapMessage(message.channel, Media.MediaMsgType.MESSAGE_MEDIA_SETUP_RESPONSE_VALUE, response)
                aapTransport.send(msg)
            }
            Media.MediaMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequest.newBuilder()).build()
                if (focusRequest.mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE) {
                    AppLog.i("Video Focus NATIVE received. User likely clicked Exit. Stopping transport.")
                    aapTransport.wasUserExit = true
                    aapTransport.quit(clean = true)
                } else {
                    val response = Media.VideoFocusResponse.newBuilder()
                            .setFocusStatus(Media.VideoFocusStatus.VIDEO_FOCUS_PROJECTED)
                            .build()
                    val msg = AapMessage(message.channel, Media.MediaMsgType.MESSAGE_VIDEO_FOCUS_RESPONSE_VALUE, response)
                    aapTransport.send(msg)
                }
            }
            Media.MediaMsgType.MESSAGE_MICROPHONE_REQUEST_VALUE -> {
                val micRequest = message.parse(Media.MicrophoneRequest.newBuilder()).build()
                if (micRequest.open) {
                    micRecorder.start()
                } else {
                    micRecorder.stop()
                }
            }
        }
        return 0
    }
}

internal class AapControlTouch(private val aapTransport: AapTransport) : AapControl {
    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Input.MsgType.BINDINGREQUEST_VALUE -> {
                val request = message.parse(Input.KeyBindingRequest.newBuilder()).build()
                return inputBinding(request, message.channel)
            }
        }
        return 0
    }

    private fun inputBinding(request: Input.KeyBindingRequest, channel: Int): Int {
        val response = Input.KeyBindingResponse.newBuilder()
                .setStatus(Control.Status.STATUS_SUCCESS)
                .build()
        val msg = AapMessage(channel, Input.MsgType.BINDINGRESPONSE_VALUE, response)
        aapTransport.send(msg)
        return 0
    }
}

internal class AapControlSensor(private val aapTransport: AapTransport, private val context: Context) : AapControl {
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Sensors.MsgType.SENSOR_START_REQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorStartRequest.newBuilder()).build()
                val response = Sensors.SensorStartResponse.newBuilder()
                        .setStatus(Control.Status.STATUS_SUCCESS)
                        .build()
                val msg = AapMessage(message.channel, Sensors.MsgType.SENSOR_START_RESPONSE_VALUE, response)
                aapTransport.send(msg)
                aapTransport.startSensor(request.sensorType)
            }
        }
        return 0
    }
}
