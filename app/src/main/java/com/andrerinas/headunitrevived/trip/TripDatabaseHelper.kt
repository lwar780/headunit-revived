package com.andrerinas.headunitrevived.trip

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.andrerinas.headunitrevived.utils.AppLog

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "trip_journal.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_TRIPS = "trips"
        const val COLUMN_ID = "id"
        const val COLUMN_START_TIME = "start_time"
        const val COLUMN_END_TIME = "end_time"
        const val COLUMN_START_LOCATION = "start_location"
        const val COLUMN_END_LOCATION = "end_location"
        const val COLUMN_DISTANCE_KM = "distance_km"
        const val COLUMN_DURATION_SEC = "duration_sec"
        const val COLUMN_EVENTS_JSON = "events_json"

        private const val CREATE_TABLE_TRIPS = """
            CREATE TABLE $TABLE_TRIPS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_START_TIME INTEGER,
                $COLUMN_END_TIME INTEGER,
                $COLUMN_START_LOCATION TEXT,
                $COLUMN_END_LOCATION TEXT,
                $COLUMN_DISTANCE_KM REAL,
                $COLUMN_DURATION_SEC INTEGER,
                $COLUMN_EVENTS_JSON TEXT
            )
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_TRIPS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle migrations if needed
    }

    fun insertTrip(
        startTime: Long,
        endTime: Long,
        startLocation: String?,
        endLocation: String?,
        distanceKm: Double,
        durationSec: Long,
        eventsJson: String?
    ): Long {
        val values = ContentValues().apply {
            put(COLUMN_START_TIME, startTime)
            put(COLUMN_END_TIME, endTime)
            put(COLUMN_START_LOCATION, startLocation)
            put(COLUMN_END_LOCATION, endLocation)
            put(COLUMN_DISTANCE_KM, distanceKm)
            put(COLUMN_DURATION_SEC, durationSec)
            put(COLUMN_EVENTS_JSON, eventsJson)
        }
        return writableDatabase.insert(TABLE_TRIPS, null, values)
    }

    fun deleteOldTrips(daysThreshold: Int = 90) {
        val thresholdMillis = System.currentTimeMillis() - (daysThreshold.toLong() * 24 * 60 * 60 * 1000)
        val deletedRows = writableDatabase.delete(
            TABLE_TRIPS,
            "$COLUMN_END_TIME < ?",
            arrayOf(thresholdMillis.toString())
        )
        if (deletedRows > 0) {
            AppLog.i("TripDatabaseHelper: Deleted $deletedRows trips older than $daysThreshold days.")
        }
    }
}
