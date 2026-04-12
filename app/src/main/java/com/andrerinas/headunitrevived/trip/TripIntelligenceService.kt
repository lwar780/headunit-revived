package com.andrerinas.headunitrevived.trip

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.FeatureFlags
import com.andrerinas.headunitrevived.connection.CommManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Service that monitors trip lifecycle using AAP signals and system events.
 */
class TripIntelligenceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateMachine = TripStateMachine()
    private val featureFlags by lazy { FeatureFlags(this) }
    private val commManager by lazy { App.provide(this).commManager }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val timestamp = System.currentTimeMillis()
            when (intent.action) {
                LocationUpdateIntent.action -> {
                    val location = LocationUpdateIntent.extractLocation(intent)
                    // Speed is in m/s, convert to km/h if needed by state machine (spec says km/h)
                    val speedKmH = location.speed * 3.6f
                    updateState(TripInput.SpeedChanged(speedKmH, timestamp))
                }
                Intent.ACTION_SCREEN_ON -> updateState(TripInput.ScreenChanged(true, timestamp))
                Intent.ACTION_SCREEN_OFF -> updateState(TripInput.ScreenChanged(false, timestamp))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!featureFlags.tripIntelligence) {
            AppLog.i("TripIntelligenceService: Feature disabled, stopping.")
            stopSelf()
            return
        }

        AppLog.i("TripIntelligenceService: Starting...")

        val filter = IntentFilter().apply {
            addAction(LocationUpdateIntent.action)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter)

        observeConnection()
    }

    private fun observeConnection() {
        serviceScope.launch {
            commManager.connectionState.collect { state ->
                val connected = state is CommManager.ConnectionState.HandshakeComplete
                updateState(TripInput.ConnectionChanged(connected, System.currentTimeMillis()))
            }
        }
    }

    private fun updateState(input: TripInput) {
        val newState = stateMachine.transition(input)
        if (newState != null) {
            AppLog.i("TripIntelligence: State transitioned to $newState")
            publishEvent(newState)
        }
    }

    private fun publishEvent(state: TripState) {
        val timestamp = System.currentTimeMillis()
        val event = when (state) {
            TripState.IGNITION_ON -> {
                // In a real implementation, we would check if Maps was active in the last trip
                // For now, we'll assume it was to trigger the voice announcement prompt
                TripEvent.IgnitionDetected(timestamp, shouldResumeNav = true)
            }
            TripState.DRIVING -> TripEvent.DrivingStarted(timestamp)
            TripState.PARKING -> {
                // In a real app, we'd get last known location
                TripEvent.ParkingDetected(timestamp, "Unknown")
            }
            TripState.ENGINE_OFF -> {
                // For now, publishing a generic TripEnded with placeholder stats
                TripEvent.TripEnded(startTime = 0L, endTime = timestamp, distanceKm = 0.0, eventsJson = "[]")
            }
            else -> null
        }

        event?.let {
            serviceScope.launch {
                TripEventBus.getInstance().emit(it)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        serviceScope.cancel()
        AppLog.i("TripIntelligenceService: Stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun intent(context: Context): Intent = Intent(context, TripIntelligenceService::class.java)
    }
}
