package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.utils.PlatformGuard
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.LinearLayout
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
import com.andrerinas.headunitrevived.view.ProjectionViewScaler
import com.andrerinas.headunitrevived.BuildConfig
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.andrerinas.headunitrevived.utils.FeatureFlags
import com.andrerinas.headunitrevived.trip.TripEventBus
import com.andrerinas.headunitrevived.trip.TripEvent
import com.andrerinas.headunitrevived.trip.OfflineUiService
import kotlinx.coroutines.flow.collect

/**
 * Android Auto projection activity with rotation support.
 */
class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private val featureFlags by lazy { FeatureFlags(this) }
    private var surfaceReady = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var signalLossLastUpdateMs: Long = 0
    private val signalLossUpdateRunnable = object : Runnable {
        override fun run() {
            val overlay = findViewById<View>(R.id.signal_loss_overlay)
            if (overlay?.visibility == View.VISIBLE && signalLossLastUpdateMs > 0) {
                val diffMin = (System.currentTimeMillis() - signalLossLastUpdateMs) / 60000
                findViewById<TextView>(R.id.signal_loss_last_updated)?.text = "Last updated: $diffMin min ago"
                watchdogHandler.postDelayed(this, 30000)
            }
        }
    }

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
                if (videoDecoder.lastFrameRenderedMs > 0) {
                    loadingOverlay.visibility = View.GONE
                    overlayState = OverlayState.HIDDEN
                    return
                }
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                watchdogHandler.postDelayed(this, 1500)
            }
        }
    }

    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            if (commManager.connectionState.value !is CommManager.ConnectionState.HandshakeComplete) return
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame == 0L) {
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
        }
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = if (PlatformGuard.hasTiramisu) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let { onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (PlatformGuard.hasElevation) enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val screenOrientation = settings.screenOrientation
        requestedOrientation = screenOrientation.androidOrientation

        setContentView(R.layout.activity_headunit)

        val container = findViewById<FrameLayout>(R.id.container)

        if (settings.showFpsCounter) {
            fpsTextView = TextView(this).apply {
                setTextColor(Color.YELLOW)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(10, 5, 10, 5)
                text = "FPS: --"
                if (PlatformGuard.hasElevation) {
                    elevation = 100f
                    translationZ = 100f
                }
            }
            val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(20, 20, 0, 0)
            }
            container.addView(fpsTextView, params)

            videoDecoder.onFpsChanged = { fps ->
                runOnUiThread { fpsTextView?.text = "FPS: $fps" }
            }
        }

        // Mic status indicator
        run {
            micIndicator = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setBackgroundColor(Color.parseColor("#80000000"))
                setPadding(8, 4, 8, 4)
                visibility = View.GONE
                if (PlatformGuard.hasElevation) {
                    elevation = 100f
                    translationZ = 100f
                }
            }
            container.addView(micIndicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 20, 20, 0)
            })
        }

        // DEBUG Mic Status Overlay
        if (BuildConfig.DEBUG) {
            val micDebugOverlay = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(16, 100, 0, 0)
                }
                setTextColor(Color.YELLOW)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setPadding(12, 8, 12, 8)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                text = "MIC: init"
                visibility = View.GONE
                if (PlatformGuard.hasElevation) {
                    elevation = 101f
                    translationZ = 101f
                }
            }
            container.addView(micDebugOverlay)

            commManager.micStatusListener = object : com.andrerinas.headunitrevived.decoder.MicRecorder.MicStatusListener {
                override fun onMicStatus(sourceName: String, rmsDb: Float, isActive: Boolean) {
                    runOnUiThread {
                        micDebugOverlay.visibility = if (isActive) View.VISIBLE else View.GONE
                        micDebugOverlay.text = "MIC: %s | %.1f dB".format(sourceName, rmsDb)
                        
                        if (isActive) {
                            micIndicator?.visibility = View.VISIBLE
                            micIndicator?.text = "MIC: $sourceName"
                            watchdogHandler.removeCallbacks(micHideRunnable)
                            watchdogHandler.postDelayed(micHideRunnable, 5000)
                        } else {
                            micIndicator?.visibility = View.GONE
                        }
                    }
                }
            }
        }

        videoDecoder.dimensionsListener = this

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    commManager.connectionState.collect { state ->
                        if (state is CommManager.ConnectionState.Disconnected && state.isUserExit) {
                            finish()
                        }
                        // Path A: handshake completes while surface is already ready.
                        // Path B is handled in onSurfaceChanged (surface arrives after handshake).
                        if (state is CommManager.ConnectionState.HandshakeComplete && surfaceReady) {
                            lifecycleScope.launch { commManager.startReading() }
                        }
                    }
                }
                launch {
                    TripEventBus.getInstance().events.collect { event ->
                        when (event) {
                            is TripEvent.SignalLost -> {
                                runOnUiThread {
                                    val overlay = findViewById<View>(R.id.signal_loss_overlay)
                                    overlay?.visibility = View.VISIBLE
                                    signalLossLastUpdateMs = event.lastUpdateTimestamp
                                    watchdogHandler.post(signalLossUpdateRunnable)
                                }
                            }
                            is TripEvent.SignalResumed -> {
                                runOnUiThread {
                                    val overlay = findViewById<View>(R.id.signal_loss_overlay)
                                    overlay?.visibility = View.GONE
                                    watchdogHandler.removeCallbacks(signalLossUpdateRunnable)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            projectionView = TextureProjectionView(this)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            projectionView = GlProjectionView(this)
        } else {
            projectionView = ProjectionView(this)
        }
        (projectionView as View).layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        container.addView(projectionView as View)
        projectionView.addCallback(this)

        val overlayView = OverlayTouchView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) requestFocus()
                sendTouchEvent(event)
                true
            }
        }
        container.addView(overlayView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            (overlayView.layoutParams as FrameLayout.LayoutParams).setMargins(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            overlayView.requestLayout()
            insets
        }

        setFullscreen()
        findViewById<View>(R.id.loading_overlay)?.bringToFront()
        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener { commManager.disconnect() }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
                overlayState = OverlayState.HIDDEN
            }
        }
    }

    private fun showMicFailOverlay(message: String, errorCode: Int) {
        val container = findViewById<FrameLayout>(R.id.container) ?: return
        micFailOverlay?.let { container.removeView(it) }

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            isClickable = true
            isFocusable = true

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
                        container.removeView(this@apply)
                        micFailOverlay = null
                        sendBroadcast(Intent("com.andrerinas.headunitrevived.RETRY_MIC"))
                    }
                })

                if (errorCode == -3) {
                    addView(Button(this@AapProjectionActivity).apply {
                        text = getString(R.string.open_settings)
                        textSize = 14f
                        setOnClickListener {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
                    })
                }
            }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        container.addView(overlay)
        micFailOverlay = overlay
    }

    override fun onPause() {
        super.onPause()
        watchdogHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(keyCodeReceiver)
        unregisterReceiver(nightModeReceiver)
        try { unregisterReceiver(micFailReceiver) } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)
        ContextCompat.registerReceiver(this, keyCodeReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, micFailReceiver, IntentFilter("com.andrerinas.headunitrevived.MIC_FAILED"), ContextCompat.RECEIVER_NOT_EXPORTED)
        setFullscreen()
    }

    private fun setFullscreen() {
        SystemUI.apply(window, findViewById(R.id.container), settings.fullscreenMode)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        HeadUnitScreenConfig.init(this, resources.displayMetrics, settings)
        setFullscreen()
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

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val horizontalCorrection = HeadUnitScreenConfig.getHorizontalCorrection()
        val verticalCorrection = HeadUnitScreenConfig.getVerticalCorrection()
        if (horizontalCorrection <= 0 || verticalCorrection <= 0) return

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { i ->
            pointerData.add(Triple(event.getPointerId(i), (event.getX(i) * horizontalCorrection).toInt(), (event.getY(i) * verticalCorrection).toInt()))
        }
        commManager.send(TouchEvent(SystemClock.elapsedRealtime(), action, event.actionIndex, pointerData))
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        commManager.send(keyCode, isPress)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {}
    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        videoDecoder.setSurface(surface)
        surfaceReady = true
        // Path B: surface becomes ready after handshake already completed.
        // Path A is handled in the connectionState collector above.
        if (commManager.connectionState.value is CommManager.ConnectionState.HandshakeComplete) {
            lifecycleScope.launch { commManager.startReading() }
        }
    }
    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        surfaceReady = false
        videoDecoder.setSurface(null)
    }
    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        runOnUiThread { ProjectionViewScaler.updateScale(projectionView as View, width, height) }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        videoDecoder.dimensionsListener = null
        videoDecoder.onFpsChanged = null
        videoDecoder.onFirstFrameListener = null
        commManager.micStatusListener = null
    }

    private val commManager get() = App.provide(this).commManager

    companion object {
        const val EXTRA_FOCUS = "focus"
        fun intent(context: Context): Intent = Intent(context, AapProjectionActivity::class.java)
    }
}
