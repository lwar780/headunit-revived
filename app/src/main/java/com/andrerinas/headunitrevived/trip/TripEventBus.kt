package com.andrerinas.headunitrevived.trip

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus for [TripEvent]s using Kotlin Coroutines SharedFlow.
 * Replay is set to 1 to ensure that new subscribers receive the last emitted event.
 */
class TripEventBus {
    private val _events = MutableSharedFlow<TripEvent>(replay = 1)
    
    /**
     * Flow of trip events.
     */
    val events: SharedFlow<TripEvent> = _events.asSharedFlow()

    /**
     * Emits a new [TripEvent] to the bus.
     */
    fun emit(event: TripEvent) {
        _events.tryEmit(event)
    }

    companion object {
        @Volatile
        private var instance: TripEventBus? = null

        /**
         * Returns the singleton instance of [TripEventBus].
         */
        fun getInstance(): TripEventBus {
            return instance ?: synchronized(this) {
                instance ?: TripEventBus().also { instance = it }
            }
        }
    }
}
