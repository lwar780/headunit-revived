package com.andrerinas.headunitrevived.aap

import android.app.Notification
import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.app.BootCompleteReceiver
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.connection.NetworkDiscovery
import com.andrerinas.headunitrevived.connection.WifiDirectManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.LogExporter
import com.andrerinas.headunitrevived.utils.NightModeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager
import com.andrerinas.headunitrevived.app.UsbAttachedActivity
import android.media.AudioManager
import com.andrerinas.headunitrevived.utils.HotspotManager
import com.andrerinas.headunitrevived.utils.VpnControl
import com.andrerinas.headunitrevived.utils.SilentAudioPlayer
import com.andrerinas.headunitrevived.connection.CarKeyReceiver
import com.andrerinas.headunitrevived.connection.NativeAaHandshakeManager
import com.andrerinas.headunitrevived.connection.NearbyManager
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.PlatformGuard
import com.andrerinas.headunitrevived.utils.FeatureFlags
import com.andrerinas.headunitrevived.trip.TripIntelligenceService
import com.andrerinas.headunitrevived.trip.OfflineUiService
import com.andrerinas.headunitrevived.trip.TripJournalService
import com.andrerinas.headunitrevived.trip.VoiceAnnouncementService
import java.net.ServerSocket
import java.net.Socket

/**
 * Top-level foreground service that manages the Android Auto connection lifecycle.
 */
class AapService : Service(), UsbReceiver.Listener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var uiModeManager: UiModeManager
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wifiDirectManager: WifiDirectManager? = null
    private var nativeAaHandshakeManager: NativeAaHandshakeManager? = null
    private var nearbyManager: NearbyManager? = null
    private var carKeyReceiver: CarKeyReceiver? = null
    private var silentAudioPlayer: SilentAudioPlayer? = null
    private var wirelessServer: WirelessServer? = null
    private var networkDiscovery: NetworkDiscovery? = null
    private var mediaSession: MediaSessionCompat? = null
    private var permanentFocusRequest: android.media.AudioFocusRequest? = null
    private var lastMediaButtonClickTime = 0L

    private var isDestroying = false
    private var hasEverConnected = false
    private var accessoryHandshakeFailures = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var wifiReadyCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiReadyTimeoutJob: Job? = null
    private var wifiModeInitialized = false

    private var activeWifiMode = -1
    private var activeHelperStrategy = -1

    private var bootWakeLock: PowerManager.WakeLock? = null

    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
                AppLog.i("Runtime MEDIA_BUTTON receiver fired")
                mediaSession?.let {
                    MediaButtonReceiver.handleIntent(it, intent)
                }
            }
        }
    }

    private val isSwitchingToAccessory = AtomicBoolean(false)

    @Volatile
    private var userExitedAA = false

    private val commManager get() = App.provide(this).commManager

    fun updateMediaSessionState(isPlaying: Boolean) {
        var actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        var state: Int = if (isPlaying) {
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
            PlaybackStateCompat.STATE_PLAYING
        } else {
            actions = actions or PlaybackStateCompat.ACTION_PLAY
            PlaybackStateCompat.STATE_STOPPED
        }

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, 0, 1.0f)
                .setActions(actions)
                .build()
        )
    }

    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private var screenOffTimestamp = 0L
    private var lastWakeHandledTimestamp = 0L

    private val wakeDetectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> screenOffTimestamp = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    val now = SystemClock.elapsedRealtime()
                    val offDuration = if (screenOffTimestamp > 0) now - screenOffTimestamp else -1L
                    screenOffTimestamp = 0
                    val settings = App.provide(this@AapService).settings
                    if (settings.autoStartOnScreenOn) onScreenOnAutoStart()
                    else if (offDuration > HIBERNATE_WAKE_THRESHOLD_MS) onHibernateWake("SCREEN_ON")
                }
                Intent.ACTION_POWER_CONNECTED -> onPossibleWake("POWER_CONNECTED")
                else -> onHibernateWake(action)
            }
        }
    }

    private fun onHibernateWake(trigger: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 10_000) return
        lastWakeHandledTimestamp = now
        if (commManager.isConnected || isSwitchingToAccessory.get()) return
        val settings = App.provide(this).settings
        if (settings.autoStartOnBoot) launchMainActivityOnBoot()
        if (settings.autoStartOnUsb) checkAlreadyConnectedUsb(force = true)
    }

    private fun onPossibleWake(trigger: String) {
        if (commManager.isConnected || isSwitchingToAccessory.get()) return
        if (App.provide(this).settings.autoStartOnUsb) checkAlreadyConnectedUsb(force = true)
    }

    private fun onScreenOnAutoStart() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeHandledTimestamp < 5_000) return
        lastWakeHandledTimestamp = now
        acquireBootWakeLock()
        if (commManager.isConnected) {
            try {
                startActivity(AapProjectionActivity.intent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
            } catch (e: Exception) {}
            return
        }
        launchMainActivityOnBoot()
        if (App.provide(this).settings.autoStartOnUsb) checkAlreadyConnectedUsb(force = true)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...")

        val notification = createNotification()
        if (PlatformGuard.hasServiceTypes) {
            startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        
        setupCarMode()
        setupNightMode()
        observeConnectionState()
        registerReceivers()

        // Check mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val permIntent = Intent(this, MainActivity::class.java).apply { action = "REQUEST_MIC_PERMISSION"; flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            val permPendingIntent = PendingIntent.getActivity(this, 0, permIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val micNotification = NotificationCompat.Builder(this, "service_channel")
                .setContentTitle("Microphone Permission Needed").setContentText("Voice commands require microphone access")
                .setSmallIcon(R.drawable.ic_network_wifi_white).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(permPendingIntent).setAutoCancel(true).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9001, micNotification)
        }

        if (mediaSession == null) setupMediaSession()
        mediaSession?.isActive = true
        updateMediaSessionState(false)

        LogExporter.startCapture(this, LogExporter.LogLevel.DEBUG)
        startService(GpsLocationService.intent(this))

        nativeAaHandshakeManager = NativeAaHandshakeManager(this, serviceScope)
        wifiDirectManager = WifiDirectManager(this)
        
        if (PlatformGuard.hasLollipop) {
            try {
                nearbyManager = NearbyManager(this) { socket -> serviceScope.launch { commManager.connect(socket) } }
            } catch (e: Exception) {}
        }
        
        initWifiModeWithOptionalWait()
        wifiDirectManager?.setCredentialsListener { ssid, psk, ip, bssid ->
            nativeAaHandshakeManager?.updateWifiCredentials(ssid, psk, ip, bssid)
        }

        if (App.provide(this).settings.wifiConnectionMode == 3) nativeAaHandshakeManager?.start()

        carKeyReceiver = CarKeyReceiver()
        silentAudioPlayer = SilentAudioPlayer(this)

        initWifiMode()
        checkAlreadyConnectedUsb()
        registerNetworkMonitor()

        // Start Trip services
        val flags = FeatureFlags(this)
        if (flags.tripIntelligence) startService(TripIntelligenceService.intent(this))
        if (flags.voiceAnnouncements) startService(Intent(this, VoiceAnnouncementService::class.java))
        if (flags.offlineGracefulDegradation) startService(OfflineUiService.intent(this))
        if (flags.tripJournal) startService(TripJournalService.intent(this))

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (PlatformGuard.hasOreo) {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            permanentFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange -> AppLog.d("Permanent audio focus changed: $focusChange") }.build()
            audioManager.requestAudioFocus(permanentFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus({ focusChange -> AppLog.d("Permanent audio focus changed: $focusChange") }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun setupCarMode() {
        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.enableCarMode(0)
    }

    private fun setupNightMode() {
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            commManager.send(NightModeEvent(isNight))
            sendBroadcast(Intent(ACTION_NIGHT_MODE_CHANGED).apply { setPackage(packageName); putExtra("isNight", isNight) })
        }
        nightModeManager?.start()
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            commManager.connectionState.collect { state ->
                when (state) {
                    is CommManager.ConnectionState.Connected -> onConnected()
                    is CommManager.ConnectionState.TransportStarted -> {
                        hasEverConnected = true
                        accessoryHandshakeFailures = 0
                        sendBroadcast(Intent(ACTION_REQUEST_NIGHT_MODE_UPDATE).apply { setPackage(packageName) })
                    }
                    is CommManager.ConnectionState.Disconnected -> if (hasEverConnected) onDisconnected(state)
                    else -> {}
                }
            }
        }
    }

    private fun onConnected() {
        isSwitchingToAccessory.set(false)
        updateNotification()
        acquireWifiLock()
        silentAudioPlayer?.start()
        val filter = IntentFilter().apply { priority = 1000; CarKeyReceiver.ACTIONS.forEach { addAction(it) } }
        try { ContextCompat.registerReceiver(this, carKeyReceiver, filter, ContextCompat.RECEIVER_EXPORTED) } catch (e: Exception) {}
        mediaSession?.isActive = true
        updateMediaSessionState(true)
        commManager.onAudioFocusStateChanged = { isPlaying -> updateMediaSessionState(isPlaying) }
        serviceScope.launch { commManager.startHandshake() }
        startActivity(AapProjectionActivity.intent(this).apply { putExtra(AapProjectionActivity.EXTRA_FOCUS, true); addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) })
    }

    private fun setupMediaSession() {
        val mbr = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "HeadunitRevived", mbr, null).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java) }
                    if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        val now = System.currentTimeMillis()
                        if (now - lastMediaButtonClickTime < 300) return true
                        lastMediaButtonClickTime = now
                        commManager.send(keyEvent.keyCode, true)
                        commManager.send(keyEvent.keyCode, false)
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPause() { commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, true); commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, false) }
                override fun onPlay() { commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, true); commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, false) }
                override fun onSkipToNext() { commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, true); commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, false) }
                override fun onSkipToPrevious() { commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, true); commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, false) }
                override fun onStop() { commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_STOP, true); commManager.send(android.view.KeyEvent.KEYCODE_MEDIA_STOP, false) }
            })
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Android Auto").putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Connected").build())
        }
    }

    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        isSwitchingToAccessory.set(false)
        releaseWifiLock()
        silentAudioPlayer?.stop()
        try { carKeyReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        if (!isDestroying) updateNotification()
        mediaSession?.isActive = false
        updateMediaSessionState(false)
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop()
            App.provide(this@AapService).videoDecoder.stop("AapService::onDisconnect")
        }
        scheduleReconnectIfNeeded(state)
    }

    private fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) {
        if (selfMode) { selfMode = false; stopWirelessServer(); return }
        if (wirelessServer != null) { serviceScope.launch { delay(2000); if (!commManager.isConnected) startDiscovery() }; return }
        val settings = App.provide(this).settings
        val lastType = settings.lastConnectionType
        if (lastType == Settings.CONNECTION_TYPE_USB && (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice)) {
            if (state.isUserExit && !(settings.autoStartOnUsb && settings.reopenOnReconnection)) { userExitedAA = true; return }
            serviceScope.launch { delay(USB_RECONNECT_DELAY_MS); if (!commManager.isConnected) checkAlreadyConnectedUsb(force = true) }
        }
        if (!state.isClean && settings.wifiConnectionMode == 1 && lastType != Settings.CONNECTION_TYPE_USB) {
            serviceScope.launch { delay(2000); if (!commManager.isConnected) startDiscovery(oneShot = true) }
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(LocaleHelper.wrapContext(newBase)) }

    private fun registerReceivers() {
        usbReceiver = UsbReceiver(this)
        ContextCompat.registerReceiver(this, nightModeUpdateReceiver, IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbReceiver, UsbReceiver.createFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, mediaButtonReceiver, IntentFilter(Intent.ACTION_MEDIA_BUTTON), ContextCompat.RECEIVER_EXPORTED)
        val wakeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_BOOT_COMPLETED); addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            addAction("android.intent.action.QUICKBOOT_POWERON"); addAction("com.htc.intent.action.QUICKBOOT_POWERON")
            addAction("com.mediatek.intent.action.QUICKBOOT_POWERON"); addAction("com.mediatek.intent.action.BOOT_IPO")
            addAction("com.fyt.boot.ACCON"); addAction("com.glsx.boot.ACCON"); addAction("android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT")
            addAction("com.cayboy.action.ACC_ON"); addAction("com.carboy.action.ACC_ON")
        }
        ContextCompat.registerReceiver(this, wakeDetectReceiver, wakeFilter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun registerNetworkMonitor() {
        if (!PlatformGuard.hasLollipop) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { AppLog.i("NetworkMonitor: Network available: $network") }
            override fun onLost(network: Network) { AppLog.w("NetworkMonitor: Network lost: $network") }
        }
        networkCallback = callback
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    private fun unregisterNetworkMonitor() {
        if (!PlatformGuard.hasLollipop) return
        networkCallback?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (e: Exception) {} }
        networkCallback = null
    }

    private fun initWifiModeWithOptionalWait() {
        val settings = App.provide(this).settings
        if (settings.wifiConnectionMode != 2 || !settings.waitForWifiBeforeWifiDirect) { initWifiMode(); return }
        wifiModeInitialized = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWifiConnected = if (PlatformGuard.hasMarshmallow) {
            val activeNetwork = cm.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }
        if (isWifiConnected || !PlatformGuard.hasLollipop) { wifiModeInitialized = true; initWifiMode(); return }
        val timeoutSec = settings.waitForWifiTimeout.toLong()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { serviceScope.launch { completeWifiWait("WiFi connected") } }
        }
        wifiReadyCallback = callback
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        wifiReadyTimeoutJob = serviceScope.launch { delay(timeoutSec * 1000); completeWifiWait("timeout") }
    }

    private fun completeWifiWait(reason: String) {
        if (wifiModeInitialized || isDestroying) return
        wifiModeInitialized = true
        wifiReadyTimeoutJob?.cancel(); wifiReadyTimeoutJob = null
        wifiReadyCallback?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        wifiReadyCallback = null
        initWifiMode()
    }

    private fun initWifiMode(force: Boolean = false) {
        val settings = App.provide(this).settings
        val mode = settings.wifiConnectionMode
        val strategy = settings.helperConnectionStrategy
        if (!force && mode == activeWifiMode && strategy == activeHelperStrategy) return
        stopWirelessServer(); networkDiscovery?.stop(); nearbyManager?.stop()
        if (mode in 1..3) {
            startWirelessServer()
            nearbyManager?.start()
            if (mode == 1) startDiscovery(oneShot = false)
            if (mode == 2) {
                when (strategy) {
                    0 -> startDiscovery(oneShot = false)
                    1 -> if ((applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled) wifiDirectManager?.makeVisible()
                }
                if (settings.autoEnableHotspot) Thread { HotspotManager.setHotspotEnabled(this, true) }.start()
            }
            if (mode == 3 && (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled) wifiDirectManager?.startNativeAaQuietHost()
        }
        activeWifiMode = mode; activeHelperStrategy = strategy
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HeadunitRevived:Connection")
        if (wifiLock?.isHeld == false) { wifiLock?.acquire(); AppLog.i("WifiLock acquired") }
    }

    private fun releaseWifiLock() { if (wifiLock?.isHeld == true) { wifiLock?.release(); AppLog.i("WifiLock released") } }

    private fun acquireBootWakeLock() {
        if (bootWakeLock?.isHeld == true) return
        bootWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadunitRevived::BootAutoStart").apply { acquire(10 * 60 * 1000L) }
        if (PlatformGuard.hasMarshmallow) AppLog.i("Battery exempt: ${(getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)}")
    }

    private fun releaseBootWakeLock() { if (bootWakeLock?.isHeld == true) bootWakeLock?.release(); bootWakeLock = null }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { ContextCompat.startForegroundService(this, Intent(this, AapService::class.java)) } catch (e: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isDestroying = true
        nativeAaHandshakeManager?.stop(); releaseBootWakeLock()
        if (App.provide(this).settings.autoEnableHotspot) HotspotManager.setHotspotEnabled(this, false)
        wifiReadyTimeoutJob?.cancel(); wifiReadyTimeoutJob = null
        wifiReadyCallback?.let { try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        releaseWifiLock(); unregisterNetworkMonitor(); stopForeground(true); stopWirelessServer(); wifiDirectManager?.stop(); nearbyManager?.stop()
        mediaSession?.isActive = false; mediaSession?.release(); mediaSession = null; commManager.destroy(); nightModeManager?.stop()
        try { unregisterReceiver(nightModeUpdateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeDetectReceiver) } catch (_: Exception) {}
        uiModeManager.disableCarMode(0)
        stopService(TripIntelligenceService.intent(this))
        stopService(Intent(this, VoiceAnnouncementService::class.java))
        stopService(OfflineUiService.intent(this))
        stopService(TripJournalService.intent(this))
        serviceScope.cancel(); LogExporter.stopCapture(); super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) { isDestroying = true; if (commManager.isConnected) commManager.disconnect(); stopForeground(true); stopSelf(); return START_NOT_STICKY }
        mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
        val notification = createNotification()
        if (PlatformGuard.hasServiceTypes) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, notification)
        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true || intent?.action == ACTION_CHECK_USB) acquireBootWakeLock()
        if (intent?.getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false) == true) { lastWakeHandledTimestamp = SystemClock.elapsedRealtime(); launchMainActivityOnBoot() }
        when (intent?.action) {
            ACTION_START_SELF_MODE -> startSelfMode()
            ACTION_START_WIRELESS -> initWifiMode()
            ACTION_START_WIRELESS_SCAN -> {
                val settings = App.provide(this).settings
                if (settings.wifiConnectionMode == 2 && settings.helperConnectionStrategy == 2) nearbyManager?.start()
                else if (settings.wifiConnectionMode != 3) startDiscovery(oneShot = (settings.wifiConnectionMode != 2))
            }
            ACTION_STOP_WIRELESS -> stopWirelessServer()
            ACTION_NATIVE_AA_POKE -> intent.getStringExtra(EXTRA_MAC)?.let { initWifiMode(); nativeAaHandshakeManager?.manualPoke(it) }
            ACTION_NEARBY_CONNECT -> intent.getStringExtra(EXTRA_ENDPOINT_ID)?.let { nearbyManager?.connectToEndpoint(it) }
            ACTION_DISCONNECT -> if (commManager.isConnected) commManager.disconnect()
            ACTION_CHECK_USB -> checkAlreadyConnectedUsb(force = true)
            else -> if (intent?.action == null || intent.action == Intent.ACTION_MAIN) checkAlreadyConnectedUsb()
        }
        return START_STICKY
    }

    override fun onUsbAttach(device: UsbDevice) { userExitedAA = false; if (UsbDeviceCompat.isInAccessoryMode(device)) checkAlreadyConnectedUsb(force = true) else serviceScope.launch { delay(USB_ATTACH_FALLBACK_DELAY_MS); if (!commManager.isConnected && !isSwitchingToAccessory.get()) checkAlreadyConnectedUsb(force = true) } }
    override fun onUsbDetach(device: UsbDevice) { userExitedAA = false; if (commManager.isConnectedToUsbDevice(device)) commManager.disconnect(sendByeBye = false) }
    override fun onUsbAccessoryDetach() { userExitedAA = false; if (commManager.isConnected) commManager.disconnect(sendByeBye = false); serviceScope.launch { delay(1500); checkAlreadyConnectedUsb(force = true) } }
    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) { if (granted) { if (UsbDeviceCompat.isInAccessoryMode(device)) { isSwitchingToAccessory.set(true); serviceScope.launch { try { connectUsbWithRetry(device) } finally { isSwitchingToAccessory.set(false) } } } else { isSwitchingToAccessory.set(true); serviceScope.launch(Dispatchers.IO) { try { if (UsbAccessoryMode(getSystemService(Context.USB_SERVICE) as UsbManager).connectAndSwitch(device)) {} } finally { isSwitchingToAccessory.set(false) } } } } else Toast.makeText(this, getString(R.string.usb_permission_denied), Toast.LENGTH_LONG).show() }
    private fun requestUsbPermission(device: UsbDevice) { (getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, UsbReceiver.createPermissionPendingIntent(this)) }
    private fun onHandshakeFailed() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessoryDevice = usbManager.deviceList.values.firstOrNull { UsbDeviceCompat.isInAccessoryMode(it) } ?: return
        accessoryHandshakeFailures++
        if (accessoryHandshakeFailures > MAX_STALE_ACCESSORY_RETRIES) { accessoryHandshakeFailures = 0; isSwitchingToAccessory.set(true); serviceScope.launch(Dispatchers.IO) { try { UsbAccessoryMode(usbManager).connectAndSwitch(accessoryDevice) } catch (e: Exception) {} finally { isSwitchingToAccessory.set(false) } } }
    }

    private fun checkAlreadyConnectedUsb(force: Boolean = false) {
        val settings = App.provide(this).settings
        if (!force && !settings.autoConnectLastSession && !settings.autoConnectSingleUsbDevice && !settings.autoStartOnUsb) return
        if (commManager.isConnected || isSwitchingToAccessory.get()) return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usbManager.deviceList.values) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                if (!usbManager.hasPermission(device)) { startActivity(Intent(this, UsbAttachedActivity::class.java).apply { action = UsbManager.ACTION_USB_DEVICE_ATTACHED; putExtra(UsbManager.EXTRA_DEVICE, device); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return }
                isSwitchingToAccessory.set(true); serviceScope.launch { try { connectUsbWithRetry(device) } finally { isSwitchingToAccessory.set(false) } }; return
            }
        }
        if (settings.autoConnectLastSession) {
            for (device in usbManager.deviceList.values) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) { isSwitchingToAccessory.set(true); serviceScope.launch(Dispatchers.IO) { try { UsbAccessoryMode(usbManager).connectAndSwitch(device) } finally { isSwitchingToAccessory.set(false) } }; return }
                    else { requestUsbPermission(device); return }
                }
            }
        }
        if (settings.autoStartOnUsb || settings.autoConnectSingleUsbDevice) {
            val nonAccessoryDevices = usbManager.deviceList.values.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) } else nonAccessoryDevices
            if (candidates.size == 1) performSingleUsbConnect(candidates[0])
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) { isSwitchingToAccessory.set(true); serviceScope.launch(Dispatchers.IO) { try { UsbAccessoryMode(usbManager).connectAndSwitch(device) } finally { isSwitchingToAccessory.set(false) } } }
        else requestUsbPermission(device)
    }

    private suspend fun connectUsbWithRetry(device: UsbDevice) {
        val settings = App.provide(this).settings
        commManager.connect(device)
        if (commManager.isConnected) {
            settings.saveLastConnection(Settings.CONNECTION_TYPE_USB, usbDevice = UsbDeviceCompat(device).uniqueName)
        }
    }
    
    private fun startDiscovery(oneShot: Boolean = false) {
        if (networkDiscovery == null) {
            networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
                override fun onServiceFound(ip: String, port: Int, socket: Socket?) {
                    serviceScope.launch { 
                        if (socket != null) commManager.connect(socket)
                        else commManager.connect(ip, port)
                    }
                }
                override fun onScanFinished() {}
            })
        }
        networkDiscovery?.startScan()
    }
    
    private fun startWirelessServer() { 
        if (wirelessServer == null) { 
            wirelessServer = WirelessServer()
            wirelessServer?.start() 
        } 
    }
    
    private fun stopWirelessServer() { 
        wirelessServer?.stopServer()
        wirelessServer = null 
    }
    
    private fun startSelfMode() { selfMode = true; startWirelessServer(); startActivity(Intent(this, UsbAttachedActivity::class.java).apply { action = ACTION_START_SELF_MODE; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
    private fun launchMainActivityOnBoot() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !AndroidSettings.canDrawOverlays(this)) { showOverlayPermissionNotification(); return }; val intent = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "boot") }; startActivity(intent) }
    private fun showOverlayPermissionNotification() { val intent = Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE); (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(9002, NotificationCompat.Builder(this, "service_channel").setContentTitle(getString(R.string.overlay_permission_title)).setContentText(getString(R.string.overlay_permission_description)).setSmallIcon(R.drawable.ic_network_wifi_white).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pendingIntent).setAutoCancel(true).build()) }
    private fun launchMainActivityIfNeeded(reason: String) { if (commManager.isConnected || isSwitchingToAccessory.get()) return; val settings = App.provide(this).settings; if (settings.autoStartOnUsb) { AppLog.i("Auto-launching UI (reason=$reason)"); launchMainActivityOnBoot() } }
    private fun createNotification(): Notification {
        val channelId = "service_channel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(android.app.NotificationChannel(channelId, "Headunit Service", NotificationManager.IMPORTANCE_LOW)) }
        val stopIntent = Intent(this, AapService::class.java).apply { action = ACTION_STOP_SERVICE }; val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_service_running)).setSmallIcon(R.drawable.ic_network_wifi_white).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent).build()
    }
    private fun updateNotification() { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.notify(1, createNotification()) }

    private inner class WirelessServer {
        private var serverSocket: ServerSocket? = null
        private var nsdManager: NsdManager? = null
        private var registrationListener: NsdManager.RegistrationListener? = null
        private var job: Job? = null

        fun start(registerNsd: Boolean = true) {
            nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                AppLog.e("WirelessServer: NsdManager not available on this device.")
            } else if (registerNsd) {
                registerNsd()
            }

            job = serviceScope.launch(Dispatchers.IO) {
                try {
                    serverSocket = ServerSocket(5288).apply { reuseAddress = true }
                    AppLog.i("Wireless Server listening on port 5288")
                    logLocalNetworkInterfaces()

                    while (isActive) {
                        AppLog.d("WirelessServer: Waiting for TCP connection on port 5288...")
                        val clientSocket = serverSocket?.accept() ?: break
                        AppLog.i("WirelessServer: Incoming connection detected from ${clientSocket.inetAddress}")
                        serviceScope.launch {
                            if (commManager.isConnected) {
                                AppLog.w("WirelessServer: Already connected, dropping client from ${clientSocket.inetAddress}")
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("WirelessServer: Accepted client connection from ${clientSocket.inetAddress}. Passing to CommManager...")
                                commManager.connect(clientSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) AppLog.e("Wireless server error", e)
                } finally {
                    unregisterNsd()
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
        }

        /** Logs all non-loopback IPv4 addresses; useful for debugging connectivity issues. */
        private fun logLocalNetworkInterfaces() {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Error logging interfaces", e)
            }
        }

        private fun registerNsd() {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = 5288
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            registrationListener = null
        }

        fun stopServer() {
            job?.cancel()
            job = null
            // Close the socket to unblock the accept() call in the coroutine.
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    companion object {
        const val ACTION_START_SELF_MODE = "com.andrerinas.headunitrevived.START_SELF_MODE"
        const val ACTION_START_WIRELESS = "com.andrerinas.headunitrevived.START_WIRELESS"
        const val ACTION_STOP_WIRELESS = "com.andrerinas.headunitrevived.STOP_WIRELESS"
        const val ACTION_START_WIRELESS_SCAN = "com.andrerinas.headunitrevived.START_WIRELESS_SCAN"
        const val ACTION_DISCONNECT = "com.andrerinas.headunitrevived.DISCONNECT"
        const val ACTION_STOP_SERVICE = "com.andrerinas.headunitrevived.STOP_SERVICE"
        const val ACTION_CHECK_USB = "com.andrerinas.headunitrevived.CHECK_USB"
        const val ACTION_CONNECT_SOCKET = "com.andrerinas.headunitrevived.CONNECT_SOCKET"
        const val ACTION_NATIVE_AA_POKE = "com.andrerinas.headunitrevived.NATIVE_AA_POKE"
        const val ACTION_NEARBY_CONNECT = "com.andrerinas.headunitrevived.NEARBY_CONNECT"
        const val EXTRA_MAC = "mac"
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val ACTION_NIGHT_MODE_CHANGED = "com.andrerinas.headunitrevived.NIGHT_MODE_CHANGED"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.headunitrevived.REQUEST_NIGHT_MODE_UPDATE"
        private const val HIBERNATE_WAKE_THRESHOLD_MS = 30_000L
        private const val USB_ATTACH_FALLBACK_DELAY_MS = 1000L
        private const val USB_RECONNECT_DELAY_MS = 2000L
        private const val MAX_STALE_ACCESSORY_RETRIES = 3
        var selfMode = false
        val wifiDirectName = MutableStateFlow<String?>(null)
        val scanningState = MutableStateFlow<Boolean>(false)
    }
}
