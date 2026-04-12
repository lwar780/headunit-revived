package com.andrerinas.headunitrevived.trip

/**
 * Sealed class representing various events occurring during a trip.
 */
sealed class TripEvent {
    data class IgnitionDetected(val timestamp: Long, val shouldResumeNav: Boolean = false) : TripEvent()
    data class DrivingStarted(val timestamp: Long) : TripEvent()
    data class ParkingDetected(val timestamp: Long, val location: String) : TripEvent()
    data class SignalLost(val lastUpdateTimestamp: Long) : TripEvent()
    object SignalResumed : TripEvent()
    data class TripEnded(
        val startTime: Long,
        val endTime: Long,
        val distanceKm: Double,
        val eventsJson: String
    ) : TripEvent()
}
