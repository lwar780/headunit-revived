package com.andrerinas.headunitrevived.trip

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.FeatureFlags

/**
 * Service that monitors GPS signal health and notifies UI via [TripEventBus].
 */
class OfflineUiService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val featureFlags by lazy { FeatureFlags(this) }
    private var lastUpdateMs: Long = 0
    private var isSignalLost = false

    private val signalWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (lastUpdateMs > 0 && now - lastUpdateMs > 10000) {
                if (!isSignalLost) {
                    isSignalLost = true
                    AppLog.w("OfflineUiService: Signal lost (no update for 10s)")
                    TripEventBus.getInstance().emit(TripEvent.SignalLost(lastUpdateMs))
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationUpdateIntent.action) {
                lastUpdateMs = System.currentTimeMillis()
                if (isSignalLost) {
                    isSignalLost = false
                    AppLog.i("OfflineUiService: Signal resumed")
                    TripEventBus.getInstance().emit(TripEvent.SignalResumed)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!featureFlags.offlineGracefulDegradation) {
            AppLog.i("OfflineUiService: Feature disabled, stopping.")
            stopSelf()
            return
        }

        AppLog.i("OfflineUiService: Starting...")
        val filter = IntentFilter(LocationUpdateIntent.action)
        registerReceiver(receiver, filter)
        handler.post(signalWatchdog)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        handler.removeCallbacks(signalWatchdog)
        AppLog.i("OfflineUiService: Stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun intent(context: Context): Intent = Intent(context, OfflineUiService::class.java)
    }
}
