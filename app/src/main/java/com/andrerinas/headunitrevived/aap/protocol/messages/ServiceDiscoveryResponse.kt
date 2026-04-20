package com.andrerinas.headunitrevived.aap.protocol.messages

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.io.File
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.KeyCode
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.google.protobuf.Message

class ServiceDiscoveryResponse(private val context: Context, isSelfMode: Boolean = false)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(context, isSelfMode)) {

    companion object {
        private fun makeProto(context: Context, isSelfMode: Boolean = false): Message {
            val settings = App.provide(context).settings

            // Initialize HeadUnitScreenConfig with actual physical screen dimensions
            HeadUnitScreenConfig.init(context, context.resources.displayMetrics, settings)

            val services = mutableListOf<Control.Service>()

            val sensors = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { sources ->
                    sources.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    if (settings.useGpsForNavigation) {
                        sources.addSensors(makeSensorType(Sensors.SensorType.LOCATION))
                    }
                    
                    // Always announce Night sensor, as we control it via NightModeManager
                    sources.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                    AppLog.i("[ServiceDiscovery] Announcing NIGHT sensor support. Strategy: ${settings.nightMode}")
                    
                }.build()
            }.build()

            services.add(sensors)

            val video = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { mediaSinkServiceBuilder ->
                    val codecToRequest = when (settings.videoCodec) {
                        "H.265" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                        "Auto" -> if (com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported()) {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                        } else {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                        }
                        else -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    }

                    // Use HeadUnitScreenConfig for negotiated resolution and margins
                    val negotiatedResolution = HeadUnitScreenConfig.negotiatedResolutionType
                    val phoneWidthMargin = HeadUnitScreenConfig.getWidthMargin()
                    val phoneHeightMargin = HeadUnitScreenConfig.getHeightMargin()

                    // Enforce H.265 for 1440p resolution as required by Android Auto
                    val effectiveCodec = if (negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440 ||
                        negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560) {
                        AppLog.i("Resolution is 1440p -> Enforcing H.265 codec")
                        Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                    } else {
                        codecToRequest
                    }

                    mediaSinkServiceBuilder.availableType = effectiveCodec
                    mediaSinkServiceBuilder.audioType = Media.AudioStreamType.NONE
                    mediaSinkServiceBuilder.availableWhileInCall = true

                    AppLog.i("[ServiceDiscovery] NegotiatedResolution is: ${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}")
                    AppLog.i("[ServiceDiscovery] Margins are: ${phoneWidthMargin}x${phoneHeightMargin}")

                    mediaSinkServiceBuilder.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = negotiatedResolution
                        frameRate = when (settings.fpsLimit) {
                            30 -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            else -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                        }
                        setDensity(HeadUnitScreenConfig.getDensityDpi()) // Use actual densityDpi
                        setMarginWidth(phoneWidthMargin)
                        setMarginHeight(phoneHeightMargin)
                        setVideoCodecType(effectiveCodec)
                    }.build())
                }.build()
            }.build()

            services.add(video)

            val input = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
                    it.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(HeadUnitScreenConfig.getNegotiatedWidth()) // Use negotiated width
                        setHeight(HeadUnitScreenConfig.getNegotiatedHeight()) // Use negotiated height
                    }.build()
                    
                    if (settings.enableRotary) {
                        AppLog.i("[ServiceDiscovery] Announcing Rotary/Touchpad support")
                        it.touchpad = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            setWidth(HeadUnitScreenConfig.getNegotiatedWidth())
                            setHeight(HeadUnitScreenConfig.getNegotiatedHeight())
                        }.build()
                    }
                    
                    it.addAllKeycodesSupported(KeyCode.supported)
                }.build()
            }.build()

            services.add(input)

            val audioType = if (settings.useAacAudio) Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC else Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM

            // Always add Audio2 (System Sounds) to keep connection alive
            val audio2 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = audioType
                    it.audioType = Media.AudioStreamType.SYSTEM
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU2))
                }.build()
            }.build()
            services.add(audio2)

            if (settings.enableAudioSink) {
                if (settings.forceWirelessAudio || !isSelfMode) {
                    val audio1 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AU1
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.SPEECH
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU1))
                        }.build()
                    }.build()
                    services.add(audio1)
                }

                if (settings.forceWirelessAudio || !isSelfMode) {
                    val audio0 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AUD
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.MEDIA
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AUD))
                        }.build()
                    }.build()
                    services.add(audio0)
                }
            }

            // Microphone Service (Channel 7) - Always required for AA connection (Assistant)
            val mic = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also {
                    it.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    it.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build()
            services.add(mic)

            // Bluetooth Service
            // Resolution order (first non-null wins):
            //   1. User-configured address in settings (explicit, always reliable)
            //   2. BluetoothAdapter.getAddress() — works when LOCAL_MAC_ADDRESS is granted
            //      (auto-granted on privileged/system-app installs common on aftermarket HUs)
            //   3. /sys/class/bluetooth/hci0/address — Linux sysfs, no permissions required,
            //      world-readable on Android 8-10 MediaTek/Chinese ROMs where the framework
            //      API is privacy-masked but the driver file is still accessible
            val effectiveBtAddress = settings.bluetoothAddress.ifEmpty { resolveBluetoothMac(context) }
            if (!effectiveBtAddress.isNullOrEmpty()) {
                val source = if (settings.bluetoothAddress.isNotEmpty()) "user-set" else "auto-read"
                AppLog.i("BT MAC resolved via $source: ${effectiveBtAddress.take(8)}**")
                val bluetooth = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_BTH
                    service.bluetoothService = Control.Service.BluetoothService.newBuilder().also {
                        it.carAddress = effectiveBtAddress
                        it.addAllSupportedPairingMethods(
                                listOf(Control.BluetoothPairingMethod.A2DP,
                                        Control.BluetoothPairingMethod.HFP)
                        )
                    }.build()
                }.build()
                services.add(bluetooth)
            } else {
                AppLog.w("BT MAC: all resolution methods failed — BT service omitted from ServiceDiscovery. " +
                        "Set BT MAC manually in Settings if AA Wireless fails to connect.")
            }

            val mediaPlaybackStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MPB
                service.mediaPlaybackService = Control.Service.MediaPlaybackStatusService.newBuilder().build()
            }.build()
            services.add(mediaPlaybackStatus)

            // Navigation Status Service — head unit receives turn-by-turn data from any AA nav app
            val navigationStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_NAV
                service.navigationStatusService = Control.Service.NavigationStatusService.newBuilder()
                    .setMinimumIntervalMs(1000)
                    .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)
                    .build()
            }.build()
            services.add(navigationStatus)

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = settings.vehicleMake
                model = settings.vehicleModel
                year = settings.vehicleYear
                vehicleId = settings.vehicleId
                headUnitModel = settings.headUnitModel
                headUnitMake = settings.headUnitMake
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = if (settings.rightHandDrive) Control.DriverPosition.DRIVER_POSITION_RIGHT else Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName(settings.vehicleDisplayName)

                setHeadunitInfo(com.andrerinas.headunitrevived.aap.protocol.proto.Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake(settings.headUnitMake)
                    setHeadUnitModel(settings.headUnitModel)
                    setMake(settings.vehicleMake)
                    setModel(settings.vehicleModel)
                    setYear(settings.vehicleYear)
                    setVehicleId(settings.vehicleId)
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())

                addAllServices(services)
            }.build()
        }

        /**
         * Resolves the local Bluetooth adapter MAC address using a 2-tier fallback:
         *
         * **Tier 1 — Framework API** (`BluetoothAdapter.getAddress()`):
         * Returns the real MAC when `LOCAL_MAC_ADDRESS` (signature permission) is granted.
         * This is automatically granted on aftermarket HU ROMs that install the app as a
         * privileged system app. Filters out `02:00:00:00:00:00` (Android privacy dummy).
         *
         * **Tier 2 — Linux sysfs** (`/sys/class/bluetooth/hci0/address`):
         * The BT driver writes the hardware MAC here at adapter init. On Android 8–10
         * MediaTek/Chinese ROM devices the file is world-readable regardless of Android
         * permission model (SELinux untrusted_app policy does not block it on these builds).
         * Returns null on Android 11+ where sysfs access is tightened — fails gracefully.
         *
         * Returns null if both tiers fail; caller falls back to manual Settings entry.
         */
        private fun resolveBluetoothMac(context: Context): String? {
            val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

            // Tier 1: Framework API — works when LOCAL_MAC_ADDRESS is granted
            try {
                val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                } else {
                    @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
                }
                val address = adapter?.takeIf { it.isEnabled }?.address
                if (!address.isNullOrEmpty() && address != "02:00:00:00:00:00" && address.matches(macRegex)) {
                    AppLog.d("BT MAC: resolved via framework API")
                    return address.uppercase()
                }
            } catch (e: Exception) {
                AppLog.d("BT MAC: framework API unavailable (${e.message})")
            }

            // Tier 2: Linux sysfs — world-readable on Android 8-10 MediaTek/Chinese ROMs
            // hci0 is the primary BT controller on single-radio devices
            try {
                val address = File("/sys/class/bluetooth/hci0/address").readText().trim()
                if (address.isNotEmpty() && address != "02:00:00:00:00:00" && address.matches(macRegex)) {
                    AppLog.d("BT MAC: resolved via sysfs")
                    return address.uppercase()
                }
            } catch (e: Exception) {
                AppLog.d("BT MAC: sysfs unavailable (${e.message})")
            }

            AppLog.w("BT MAC: could not resolve automatically (set manually in Settings if needed)")
            return null
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor {
            return Control.Service.SensorSourceService.Sensor.newBuilder()
                    .setType(type).build()
        }
    }
}
