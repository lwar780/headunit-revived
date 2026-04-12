package com.andrerinas.headunitrevived.trip

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.CarContext
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.activity.ComponentActivity
import com.andrerinas.headunitrevived.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Activity for the phone-side launcher.
 */
class JournalActivity : ComponentActivity()

/**
 * Android Auto entry point for the Trip Journal.
 */
class JournalService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return JournalListScreen(carContext)
            }
        }
    }
}

private data class Trip(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val startLocation: String?,
    val endLocation: String?,
    val distanceKm: Double,
    val durationSec: Long,
    val eventsJson: String?
)

/**
 * Main screen displaying a list of recent trips.
 */
private class JournalListScreen(carContext: CarContext) : Screen(carContext) {
    private val dbHelper = TripDatabaseHelper(carContext)
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    override fun onGetTemplate(): Template {
        val trips = loadTrips()
        val listBuilder = ItemList.Builder()
        
        if (trips.isEmpty()) {
            listBuilder.setNoItemsMessage(carContext.getString(R.string.no_trips_found))
        } else {
            trips.forEach { trip ->
                val durationMin = trip.durationSec / 60
                val title = "${dateFormat.format(Date(trip.startTime))} - $durationMin min"
                val distanceText = String.format("%.1f km", trip.distanceKm)
                
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(title)
                        .addText(distanceText)
                        .setOnClickListener {
                            screenManager.push(JournalDetailsScreen(carContext, trip))
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.trip_journal))
            .setSingleList(listBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun loadTrips(): List<Trip> {
        val trips = mutableListOf<Trip>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                TripDatabaseHelper.TABLE_TRIPS,
                null, null, null, null, null,
                "${TripDatabaseHelper.COLUMN_START_TIME} DESC"
            )
            while (cursor.moveToNext()) {
                trips.add(
                    Trip(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_ID)),
                        startTime = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_START_TIME)),
                        endTime = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_END_TIME)),
                        startLocation = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_START_LOCATION)),
                        endLocation = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_END_LOCATION)),
                        distanceKm = cursor.getDouble(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_DISTANCE_KM)),
                        durationSec = cursor.getLong(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_DURATION_SEC)),
                        eventsJson = cursor.getString(cursor.getColumnIndexOrThrow(TripDatabaseHelper.COLUMN_EVENTS_JSON))
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            // Log or handle error
        }
        return trips
    }
}

/**
 * Detail screen for a specific trip.
 */
private class JournalDetailsScreen(carContext: CarContext, private val trip: Trip) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()
            .addRow(Row.Builder()
                .setTitle(carContext.getString(R.string.start_location, trip.startLocation ?: "Unknown"))
                .build())
            .addRow(Row.Builder()
                .setTitle(carContext.getString(R.string.end_location, trip.endLocation ?: "Unknown"))
                .build())
            .addRow(Row.Builder()
                .setTitle(carContext.getString(R.string.trip_duration, (trip.durationSec / 60).toInt()))
                .addText(carContext.getString(R.string.trip_distance, trip.distanceKm))
                .build())

        // Show events if any
        if (!trip.eventsJson.isNullOrBlank()) {
            paneBuilder.addRow(Row.Builder()
                .setTitle(carContext.getString(R.string.trip_events))
                .addText(trip.eventsJson)
                .build())
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(carContext.getString(R.string.trip_details))
            .setHeaderAction(Action.BACK)
            .build()
    }
}
