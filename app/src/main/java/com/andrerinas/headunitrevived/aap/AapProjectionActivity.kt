package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.utils.PlatformGuard
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.contract.KeyIntent
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.GlProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI
import android.content.IntentFilter
import com.andrerinas.headunitrevived.view.ProjectionViewScaler
import com.andrerinas.headunitrevived.BuildConfig
import android.net.Uri
import android.widget.LinearLayout
import com.andrerinas.headunitrevived.view.GlassView.GlassState
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Android Auto projection activity with rotation support.
 *
 * Display features (configurable via Settings):
 * - **Orientation modes**: SYSTEM (user), AUTO (sensor), LANDSCAPE, LANDSCAPE_REVERSE, PORTRAIT, PORTRAIT_REVERSE
 * - **Fullscreen modes**: NONE, IMMERSIVE (hide all bars), STATUS_ONLY (hide nav only), IMMERSIVE_WITH_NOTCH (edge-to-edge)
 * - **View modes**: SURFACE (default), TEXTURE (compatibility), GLES (GPU-accelerated with filters)
 * - **Scaling tweaks**: forcedScale (legacy fix, SURFACE-only), stretchToFill (ignore aspect ratio)
 * - **Night mode filter**: aaMonochromeEnabled + aaDesaturationLevel (GLES-only desaturation)
 *
 * Rotation handling: AUTO mode allows sensor rotation. onConfigurationChanged re-negotiates resolution.
 */
class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private var isSurfaceSet = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false
    private var fpsTextView: TextView? = null
    private var micIndicator: TextView? = null
    private val micHideRunnable = Runnable { micIndicator?.visibility = View.GONE }

    private var micFailOverlay: View? = null
    private val micFailReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val errorCode = intent.getIntExtra("error_code", 0)
            val errorMsg = intent.getStringExtra("error_message") ?: "Unknown error"

            runOnUiThread {
                showMicFailOverlay(errorMsg, errorCode)
            }
        }
    }

    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            val loadingOverlay = findViewById<View>(R.id.loading_overlay)
            if (loadingOverlay?.visibility == View.VISIBLE && commManager.isConnected) {
                // If the decoder already rendered something, hide the overlay immediately
                if (videoDecoder.lastFrameRenderedMs > 0) {
                    AppLog.i("Watchdog: Decoder is already rendering frames. Hiding overlay.")
                    loadingOverlay.visibility = View.GONE
                    overlayState = OverlayState.HIDDEN
                    return
                }

                AppLog.w("Watchdog: No video received yet. Requesting Keyframe (Unsolicited Focus)...")
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                watchdogHandler.postDelayed(this, 1500)
            }
        }
    }
    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            // Only run watchdog if we are actually supposed to be connected
            if (commManager.connectionState.value !is CommManager.ConnectionState.HandshakeComplete) {
                return
            }
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay
                watchdogHandler.postDelayed(this, 2000)
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastFrame
            if (overlayState == OverlayState.HIDDEN && gap > 10000) {
                showReconnectingOverlay()
            } else if (overlayState == OverlayState.RECONNECTING && gap < 2000) {
                hideReconnectingOverlay()
            }
            watchdogHandler.postDelayed(this, 2000)
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
        }
    }
    private fun checkAndForceSurface() {
        AppLog.i("Watchdog: checkAndForceSurface executing...")
        if (projectionView is TextureView) {
            val tv = projectionView as TextureView
            if (tv.isAvailable) {
                AppLog.w("Watchdog: TextureView IS available. Forcing onSurfaceChanged.")
                onSurfaceChanged(android.view.Surface(tv.surfaceTexture), tv.width, tv.height)
            } else {
                AppLog.e("Watchdog: TextureView NOT available. Vis=${tv.visibility}, W=${tv.width}, H=${tv.height}")
            }
        } else if (projectionView is GlProjectionView) {
             val gles = projectionView as GlProjectionView
             if (gles.isSurfaceValid()) {
                 AppLog.w("Watchdog: GlProjectionView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(gles.getSurface()!!, gles.width, gles.height)
             } else {
                 AppLog.e("Watchdog: GlProjectionView NOT valid.")
             }
        } else if (projectionView is ProjectionView) {
             val sv = projectionView as ProjectionView
             if (sv.holder.surface.isValid) {
                 AppLog.w("Watchdog: SurfaceView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(sv.holder.surface, sv.width, sv.height)
             } else {
                 AppLog.e("Watchdog: SurfaceView NOT valid.")
             }
        }
    }

    private val nightModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isNight = intent.getBooleanExtra("isNight", false)
            updateDesaturation(isNight)
        }
    }

    private fun updateDesaturation(isNight: Boolean) {
        if (settings.aaMonochromeEnabled && projectionView is GlProjectionView) {
            val level = if (isNight) settings.aaDesaturationLevel / 100f else 0f
            (projectionView as GlProjectionView).setDesaturation(level)
        } else if (projectionView is GlProjectionView) {
            (projectionView as GlProjectionView).setDesaturation(0f)
        }
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (PlatformGuard.hasElevation) {
            enableEdgeToEdge()
        }
        super.onCreate(savedInstanceState)

        val screenOrientation = settings.screenOrientation
        requestedOrientation = screenOrientation.androidOrientation

        setContentView(R.layout.activity_headunit)

        if (settings.showFpsCounter) {
            val container = findViewById<FrameLayout>(R.id.container)
            fpsTextView = TextView(this).apply {
                setTextColor(Color.YELLOW)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(10, 5, 10, 5)
                text = "FPS: --"
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                // Lift it above everything
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 100f
                    translationZ = 100f
                }
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(20, 20, 0, 0)
            }
            container.addView(fpsTextView, params)

            videoDecoder.onFpsChanged = { fps ->
                runOnUiThread { 
                    fpsTextView?.text = "FPS: $fps"
                    fpsTextView?.contentDescription = getString(R.string.cd_fps_counter, fps)
                }
            }
        }

        // Mic status indicator — top-right corner, shows active source + level
        run {
            val container = findViewById<FrameLayout>(R.id.container)
            micIndicator = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(8, 4, 8, 4)
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 100f
                    translationZ = 100f
                }
            }
            container.addView(micIndicator, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, 20, 20, 0) })
        }

        videoDecoder.dimensionsListener = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        var isFirstEmission = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    val first = isFirstEmission
                    isFirstEmission = false
                    
                    if (first && state is CommManager.ConnectionState.Disconnected) {
                        AppLog.i("AapProjectionActivity: Ignoring initial Disconnected state from StateFlow replay.")
                        return@collect
                    }

                    when (state) {
                        is CommManager.ConnectionState.Disconnected -> {
                            watchdogHandler.removeCallbacksAndMessages(null)
                            if (!state.isClean && !state.isUserExit) {
                                AppLog.w("AapProjectionActivity: Disconnected unexpectedly.")
                                Toast.makeText(this@AapProjectionActivity, getString(R.string.wifi_disconnect_toast), Toast.LENGTH_LONG).show()
                            }
                            // Only finish immediately if the user explicitly exited or it was a clean close.
                            if (state.isUserExit || state.isClean) {
                                AppLog.i("AapProjectionActivity: Finishing because state isUserExit=${state.isUserExit}, isClean=${state.isClean}")
                                hideReconnectingOverlay()
                                finish()
                            } else {
                                // For unexpected disconnects (especially Wireless), wait a tiny bit to see if service restarts it
                                watchdogHandler.postDelayed({
                                    if (commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
                                        AppLog.i("AapProjectionActivity: Finishing after delay due to Disconnected state.")
                                        hideReconnectingOverlay()
                                        finish()
                                    }
                                }, 2000)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            // Handshake done. If the surface is already ready (e.g. reconnect
                            // while the activity is in the foreground), start reading immediately.
                            // If not, onSurfaceChanged() will call startReading() when the surface
                            // becomes available.
                            if (isSurfaceSet) {
                                commManager.startReading()
                            }
                            // Wire up mic status indicator
                            commManager.micStatusListener = object : com.andrerinas.headunitrevived.decoder.MicRecorder.MicStatusListener {
                                override fun onMicStatus(sourceName: String, rmsDb: Float, isActive: Boolean) {
                                    runOnUiThread {
                                        if (!isActive) {
                                            micIndicator?.visibility = View.GONE
                                            return@runOnUiThread
                                        }
                                        val color = when {
                                            rmsDb > -50f -> Color.GREEN
                                            rmsDb > -70f -> Color.YELLOW
                                            else -> Color.RED
                                        }
                                        micIndicator?.setTextColor(color)
                                        micIndicator?.text = "MIC: $sourceName ${String.format("%.0f", rmsDb)}dB"
                                        micIndicator?.contentDescription = getString(R.string.cd_mic_indicator, sourceName, String.format("%.0f", rmsDb))
                                        micIndicator?.visibility = View.VISIBLE
                                        // Auto-hide after 5s of no updates
                                        watchdogHandler.removeCallbacks(micHideRunnable)
                                        watchdogHandler.postDelayed(micHideRunnable, 5000)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid., since 2025 André Rinas All Rights Reserved...")

        val container = findViewById<android.widget.FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(android.graphics.Color.BLACK)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = com.andrerinas.headunitrevived.view.GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as View
        container.addView(view)

        projectionView.addCallback(this)

        val overlayView = OverlayTouchView(this)
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        overlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                overlayView.requestFocus()
            }
            sendTouchEvent(event)
            true
        }

        container.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Ensure overlayView size matches the usable area (after insets are applied)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = overlayView.layoutParams as FrameLayout.LayoutParams
            params.setMargins(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            overlayView.layoutParams = params
            insets
        }

        overlayView.requestFocus()
        setFullscreen()

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        loadingOverlay?.bringToFront()

        if (BuildConfig.DEBUG) {
            val micDebugOverlay = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply { setMargins(16, 100, 0, 0) }
                setTextColor(Color.YELLOW)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setPadding(12, 8, 12, 8)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                text = "MIC: init"
                visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 101f
                    translationZ = 101f
                }
            }
            container.addView(micDebugOverlay)

            // Wire to MicRecorder status
            commManager.micStatusListener = object : com.andrerinas.headunitrevived.decoder.MicRecorder.MicStatusListener {
                override fun onMicStatus(sourceName: String, rmsDb: Float, isActive: Boolean) {
                    runOnUiThread {
                        micDebugOverlay.visibility = if (isActive) View.VISIBLE else View.GONE
                        micDebugOverlay.text = "MIC: %s | %.1f dB".format(sourceName, rmsDb)
                    }
                }
            }

            AppLog.i("DEBUG: Mic status overlay enabled")
        }

        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener {
            commManager.disconnect()
        }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                loadingOverlay?.visibility = View.GONE
                overlayState = OverlayState.HIDDEN
            }
        }
    }

    override fun onPause() {
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        unregisterReceiver(keyCodeReceiver)
        unregisterReceiver(nightModeReceiver)
        try { unregisterReceiver(micFailReceiver) } catch (e: Exception) {}
    }

    override fun onResume() {
        AppLog.i("AapProjectionActivity: onResume")
        super.onResume()
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)

        // Register key event receiver safely for Android 14+
        ContextCompat.registerReceiver(this, keyCodeReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Register night mode receiver for AA monochrome filter
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Register mic fail receiver
        ContextCompat.registerReceiver(this, micFailReceiver, IntentFilter("com.andrerinas.headunitrevived.MIC_FAILED"), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Request current night mode state for initial desaturation
        sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(packageName)
        })

        setFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AapProjectionActivity: onNewIntent received")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        AppLog.i("AapProjectionActivity: onConfigurationChanged - orientation=${newConfig.orientation}")

        // 1. Re-initialize HeadUnitScreenConfig with new dimensions
        HeadUnitScreenConfig.init(this, resources.displayMetrics, settings)

        // 2. Update projection view scaling if video is already active
        if (::projectionView.isInitialized && videoDecoder.videoWidth > 0 && videoDecoder.videoHeight > 0) {
            runOnUiThread {
                ProjectionViewScaler.updateScale(
                    projectionView as View,
                    videoDecoder.videoWidth,
                    videoDecoder.videoHeight
                )
            }
        }

        // 3. Reapply fullscreen mode (system bars may appear during rotation)
        setFullscreen()

        // 4. Request fresh keyframe if projection is active
        if (isSurfaceSet && commManager.connectionState.value is CommManager.ConnectionState.HandshakeComplete) {
            commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
        }

        AppLog.i("AapProjectionActivity: New resolution: ${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}")
    }

    private fun showReconnectingOverlay() {
        AppLog.i("Showing reconnecting overlay")
        overlayState = OverlayState.RECONNECTING
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val title = findViewById<TextView>(R.id.overlay_text)
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.VISIBLE
        title?.text = getString(R.string.connection_interrupted)
        detail?.text = getString(R.string.connection_interrupted_detail)
        detail?.visibility = View.VISIBLE
        button?.visibility = View.VISIBLE
    }

    private fun hideReconnectingOverlay() {
        AppLog.i("Hiding reconnecting overlay — frames resumed")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, settings.fullscreenMode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && settings.fullscreenMode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        SystemUI.apply(window, container, settings.fullscreenMode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }
                    }, 2000)
                }
            }
        }
    }

    private fun showExitDialog() {
        val items = mutableListOf(getString(R.string.exit_dialog_stop))
        
        if (PlatformGuard.hasPipSupport(this)) {
            items.add(getString(R.string.exit_dialog_pip))
        }
        
        items.add(getString(R.string.exit_dialog_background))

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setItems(items.toTypedArray()) { _, which ->
                val selected = items[which]
                when {
                    selected == getString(R.string.exit_dialog_stop) -> {
                        commManager.disconnect(sendByeBye = true)
                        finish()
                    }
                    selected == getString(R.string.exit_dialog_pip) -> {
                        enterPiP()
                    }
                    selected == getString(R.string.exit_dialog_background) -> {
                        moveToBackground()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun enterPiP() {
        if (PlatformGuard.hasPipSupport(this)) {
            try {
                val params = android.app.PictureInPictureParams.Builder()
                    // Default aspect ratio for AA (usually 16:9 or 16:10)
                    .setAspectRatio(android.util.Rational(videoDecoder.videoWidth.coerceAtLeast(1), videoDecoder.videoHeight.coerceAtLeast(1)))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                AppLog.e("Failed to enter PiP mode: ${e.message}")
            }
        }
    }

    private fun moveToBackground() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    private fun showMicFailOverlay(message: String, errorCode: Int) {
        val container = findViewById<FrameLayout>(R.id.container)
        // Remove existing overlay if present
        micFailOverlay?.let { container.removeView(it) }

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            isClickable = true
            isFocusable = true

            val content = LinearLayout(this@AapProjectionActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)

                addView(TextView(this@AapProjectionActivity).apply {
                    text = "🎤 Microphone Failed"
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })

                addView(TextView(this@AapProjectionActivity).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.rgb(255, 200, 200))
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 32)
                })

                addView(Button(this@AapProjectionActivity).apply {
                    text = "Retry"
                    textSize = 18f
                    setOnClickListener {
                        container.removeView(this@apply)
                        micFailOverlay = null
                        // Trigger mic restart via broadcast to AapService/AapControl
                        sendBroadcast(Intent("com.andrerinas.headunitrevived.RETRY_MIC"))
                    }
                })

                if (errorCode == -3) {  // Permission denied
                    addView(Button(this@AapProjectionActivity).apply {
                        text = "Open Settings"
                        textSize = 14f
                        setOnClickListener {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        }
                        // Added padding via LayoutParams for better control
                    }.apply {
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        lp.topMargin = 16
                        layoutParams = lp
                    })
                }
            }

            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        container.addView(overlay)
        micFailOverlay = overlay
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        if (PlatformGuard.hasPipSupport(this)) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        if (isInPictureInPictureMode) {
            // Hide UI elements during PiP (like FPS counter, loading overlay)
            findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
            fpsTextView?.visibility = View.GONE
        } else {
            // Restore UI if needed
            fpsTextView?.visibility = if (settings.showFpsCounter) View.VISIBLE else View.GONE
            setFullscreen()
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home (like HUR 8)
        // For now, we only enter via dialog as requested.
        super.onUserLeaveHint()
    }

    private val commManager get() = App.provide(this).commManager

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 1. 2-finger swipe detection from the left edge (to open exit menu)
        if (ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    initialX = ev.getX(0)
                    initialY = ev.getY(0)
                    isPotentialGesture = initialX < 100
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPotentialGesture) {
                        val deltaX = ev.getX(0) - initialX
                        val deltaY = Math.abs(ev.getY(0) - initialY)
                        if (deltaX > 200 && deltaY < 100) {
                            isPotentialGesture = false
                            showExitDialog()
                            return true // Consume
                        }
                    }
                }
            }
        }
        
        // 2. Legacy Touch handling for older devices (API < 19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(ev)
        }
        
        return super.dispatchTouchEvent(ev)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true
        
        videoDecoder.setSurface(surface)

        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> {
                // AapService should have started the handshake already, but as a fallback
                // (e.g. service restarted) kick it off here. The HandshakeComplete observer
                // will call startReading() once the handshake finishes.
                lifecycleScope.launch { commManager.startHandshake() }
            }
            is CommManager.ConnectionState.StartingTransport -> {
                // Handshake is in progress. The HandshakeComplete observer will call
                // startReading() when it finishes.
            }
            is CommManager.ConnectionState.HandshakeComplete -> {
                // Handshake already done before surface was ready — start reading now.
                lifecycleScope.launch { commManager.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                // Surface recreated while transport was already running; request a keyframe.
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            else -> {
                commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
            }
        }

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                ProjectionViewScaler.updateScale(projectionView as View, currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val horizontalCorrection = HeadUnitScreenConfig.getHorizontalCorrection()
        val verticalCorrection = HeadUnitScreenConfig.getVerticalCorrection()

        if (horizontalCorrection <= 0 || verticalCorrection <= 0) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config not ready yet.")
            return
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)

            val correctedX = (x * horizontalCorrection).toInt()
            val correctedY = (y * verticalCorrection).toInt()

            pointerData.add(Triple(pointerId, correctedX, correctedY))
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event)
        }
        onKeyEvent(keyCode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyUp(keyCode, event)
        }
        onKeyEvent(keyCode, false)
        return true
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        AppLog.d("AapProjectionActivity: onKeyEvent code=$keyCode, isPress=$isPress")
        commManager.send(keyCode, isPress)
    }

    private fun showMicFailOverlay(message: String, errorCode: Int) {
        // Remove existing overlay if present
        val container = findViewById<FrameLayout>(R.id.container) ?: return
        micFailOverlay?.let { container.removeView(it) }

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(220, 0, 0, 0))

            val content = LinearLayout(this@AapProjectionActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)

                addView(TextView(this@AapProjectionActivity).apply {
                    text = getString(R.string.mic_failed_title)
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })

                addView(TextView(this@AapProjectionActivity).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.rgb(255, 200, 200))
                    gravity = Gravity.CENTER
                    setPadding(0, 16, 0, 32)
                })

                addView(Button(this@AapProjectionActivity).apply {
                    text = getString(R.string.retry)
                    textSize = 18f
                    setOnClickListener {
                        micFailOverlay?.let { container.removeView(it) }
                        micFailOverlay = null
                        sendBroadcast(Intent("com.andrerinas.headunitrevived.RETRY_MIC"))
                    }
                })

                if (errorCode == -3) {  // Permission denied
                    addView(Button(this@AapProjectionActivity).apply {
                        text = getString(R.string.open_settings)
                        textSize = 14f
                        setOnClickListener {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        }
                        setPadding(0, 16, 0, 0)
                    })
                }
            }

            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        container.addView(overlay)
        micFailOverlay = overlay
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
}