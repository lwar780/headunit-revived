package com.andrerinas.headunitrevived.trip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TripJournalService : Service() {

    private lateinit var dbHelper: TripDatabaseHelper
    private lateinit var featureFlags: FeatureFlags
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var currentTripStartLocation: String? = null
    private var currentTripEndLocation: String? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("TripJournalService: Creating...")
        dbHelper = TripDatabaseHelper(this)
        featureFlags = FeatureFlags(this)

        if (featureFlags.tripJournal) {
            subscribeToEvents()
            // Cleanup old data on start
            dbHelper.deleteOldTrips()
        } else {
            AppLog.i("TripJournalService: Feature disabled in flags. Stopping...")
            stopSelf()
        }
    }

    private fun subscribeToEvents() {
        TripEventBus.getInstance().events.onEach { event ->
            handleEvent(event)
        }.launchIn(serviceScope)
    }

    private fun handleEvent(event: TripEvent) {
        when (event) {
            is TripEvent.IgnitionDetected -> {
                AppLog.i("TripJournalService: Ignition detected, resetting locations.")
                currentTripStartLocation = null
                currentTripEndLocation = null
            }
            is TripEvent.DrivingStarted -> {
                AppLog.i("TripJournalService: Driving started.")
                // If we had a logic to get current location, we would set currentTripStartLocation here
            }
            is TripEvent.ParkingDetected -> {
                AppLog.i("TripJournalService: Parking detected at ${event.location}")
                if (currentTripStartLocation == null) {
                    currentTripStartLocation = event.location // Use first known location as start if not set
                }
                currentTripEndLocation = event.location
            }
            is TripEvent.TripEnded -> {
                AppLog.i("TripJournalService: Trip ended. Saving to database.")
                saveTrip(event)
            }
            else -> { /* Ignore other events */ }
        }
    }

    private fun saveTrip(event: TripEvent.TripEnded) {
        val durationSec = (event.endTime - event.startTime) / 1000
        val id = dbHelper.insertTrip(
            startTime = event.startTime,
            endTime = event.endTime,
            startLocation = currentTripStartLocation ?: "Unknown",
            endLocation = currentTripEndLocation ?: "Unknown",
            distanceKm = event.distanceKm,
            durationSec = durationSec,
            eventsJson = event.eventsJson
        )
        if (id != -1L) {
            AppLog.i("TripJournalService: Trip saved with id: $id")
        } else {
            AppLog.e("TripJournalService: Failed to save trip.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, TripJournalService::class.java)
        }
    }
}
