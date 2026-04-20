package com.andrerinas.headunitrevived.aap

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Common
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
                context: Context,
                isSelfMode: Boolean = false) : this(
            aapTransport,
            AapControlService(aapTransport, aapAudio, settings, context, micRecorder, isSelfMode),
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
            else -> {
                // Route all other channels (ID_BTH=8, ID_MPB=9, ID_NAV=10, etc.) to the
                // control service. This is critical: ChannelOpen requests arrive on the
                // channel being opened (msgType in 0..31), so a ChannelOpen for BTH/MPB/NAV
                // must reach controlService.channelOpenRequest() to get a ChannelOpenResponse.
                // Without this, the phone sends ChannelOpen, gets no response, and stalls —
                // the root cause of every session hanging after ServiceDiscovery.
                AppLog.d("AapControlGateway: routing ch=${message.channel} type=${message.type} to controlService")
                return controlService.execute(message)
            }
        }
    }
}

internal class AapControlService(
        private val aapTransport: AapTransport,
        private val aapAudio: AapAudio,
        private val settings: Settings,
        private val context: Context,
        private val micRecorder: MicRecorder,
        private val isSelfMode: Boolean = false): AapControl {

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
        AppLog.i("═══ STEP 4/4: ServiceDiscovery from phone=${request.phoneName} — sending response")

        val msg = ServiceDiscoveryResponse(context, isSelfMode)
        aapTransport.send(msg)

        // Watchdog: phone must send ChannelOpen within CHANNEL_OPEN_WATCHDOG_MS.
        // Silent hang here (phone received SD but won't open channels) means it rejected
        // the session — most likely due to missing BT service. Fail fast, allow retry.
        aapTransport.startChannelOpenWatchdog()
        AppLog.i("Watchdog armed — phone has ${AapTransport.CHANNEL_OPEN_WATCHDOG_MS / 1000}s to send ChannelOpen")

        return 0
    }

    private fun channelOpenRequest(request: Control.ChannelOpenRequest, channel: Int): Int {
        aapTransport.cancelChannelOpenWatchdog()
        AppLog.i("═══ CHANNEL OPEN ch=$channel — AA projection is starting")

        val response = Control.ChannelOpenResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
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
            AppLog.d("DEBUG: voiceSessionNotification() called. status=%s",
                request.status)
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

        val focusResponse = when(notification.request) {
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN -> Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT -> Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK -> Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE -> Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS
            else -> null
        }

        if (focusResponse != null) {
            val response = Control.AudioFocusNotification.newBuilder()
                .setFocusState(focusResponse)
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
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val response = Media.Config.newBuilder()
                        .setStatus(Media.Config.ConfigStatus.HEADUNIT)
                        .setMaxUnacked(1)
                        .build()
                val msg = AapMessage(message.channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, response)
                aapTransport.send(msg)
            }
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                if (focusRequest.mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE) {
                    AppLog.i("Video Focus NATIVE received. User likely clicked Exit. Stopping transport.")
                    aapTransport.wasUserExit = true
                    aapTransport.quit(clean = true)
                } else {
                    val response = Media.VideoFocusNotification.newBuilder()
                            .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                            .setUnsolicited(false)
                            .build()
                    val msg = AapMessage(message.channel, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, response)
                    aapTransport.send(msg)
                }
            }
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> {
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
        val response = Input.BindingResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build()
        val msg = AapMessage(channel, Input.MsgType.BINDINGRESPONSE_VALUE, response)
        aapTransport.send(msg)
        return 0
    }
}

internal class AapControlSensor(private val aapTransport: AapTransport, private val context: Context) : AapControl {
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorRequest.newBuilder()).build()
                val response = Sensors.SensorResponse.newBuilder()
                        .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                        .build()
                val msg = AapMessage(message.channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, response)
                aapTransport.send(msg)
                aapTransport.startSensor(request.type.number)
            }
        }
        return 0
    }
}
