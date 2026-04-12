package com.andrerinas.headunitrevived.trip

/**
 * States of a trip lifecycle as defined in the Trip Intelligence specification.
 */
enum class TripState {
    IDLE,
    IGNITION_ON,
    DRIVING,
    PARKING,
    ENGINE_OFF
}

/**
 * Inputs for the trip state machine.
 * These are abstracted from Android/AAP events to ensure pure logic without Android dependencies.
 */
sealed class TripInput {
    data class SpeedChanged(val speedKmH: Float, val timestamp: Long) : TripInput()
    data class ConnectionChanged(val connected: Boolean, val timestamp: Long) : TripInput()
    data class ScreenChanged(val on: Boolean, val timestamp: Long) : TripInput()
    data class MapsActiveChanged(val active: Boolean, val timestamp: Long) : TripInput()
    data class Tick(val timestamp: Long) : TripInput()
}

/**
 * Pure logic State Machine for detecting trip lifecycle.
 *
 * Implements the transitions defined in 2026-04-13-trip-intelligence-offline-resilience-design.md:
 * - IDLE → IGNITION_ON: SCREEN_ON + Connection + no connection in last 30s
 * - IGNITION_ON → DRIVING: Speed > 10 km/h sustained for 5s
 * - DRIVING → PARKING: Speed < 5 km/h sustained for 10s + Maps active
 * - PARKING → ENGINE_OFF: SCREEN_OFF + connection drops
 * - ENGINE_OFF → IDLE: Immediate cleanup
 * - False Ignition: No speed change within 60s → IDLE
 * - False Alarm: Connection drops within 10s of IGNITION_ON → IDLE
 */
class TripStateMachine {
    private var currentState: TripState = TripState.IDLE
    private var lastDisconnectTimestamp: Long = 0
    private var stateEnterTimestamp: Long = 0
    
    // Sustained condition tracking
    private var speedConditionStartTimestamp: Long? = null
    
    // Internal state tracking
    private var isConnected: Boolean = false
    private var isScreenOn: Boolean = false
    private var isMapsActive: Boolean = false
    private var currentSpeedKmH: Float = 0f

    /**
     * Processes a [TripInput] and returns the new [TripState] if a transition occurred, or null.
     */
    fun transition(input: TripInput): TripState? {
        val oldState = currentState
        val timestamp = getInputTimestamp(input)

        // Update internal tracking
        updateInternalState(input)

        // Handle state transitions
        when (currentState) {
            TripState.IDLE -> handleIdle(timestamp)
            TripState.IGNITION_ON -> handleIgnitionOn(timestamp)
            TripState.DRIVING -> handleDriving(timestamp)
            TripState.PARKING -> handleParking(timestamp)
            TripState.ENGINE_OFF -> handleEngineOff(timestamp)
        }

        return if (currentState != oldState) currentState else null
    }

    private fun getInputTimestamp(input: TripInput): Long = when (input) {
        is TripInput.SpeedChanged -> input.timestamp
        is TripInput.ConnectionChanged -> input.timestamp
        is TripInput.ScreenChanged -> input.timestamp
        is TripInput.MapsActiveChanged -> input.timestamp
        is TripInput.Tick -> input.timestamp
    }

    private fun updateInternalState(input: TripInput) {
        when (input) {
            is TripInput.SpeedChanged -> currentSpeedKmH = input.speedKmH
            is TripInput.ConnectionChanged -> {
                if (isConnected && !input.connected) {
                    lastDisconnectTimestamp = input.timestamp
                }
                isConnected = input.connected
            }
            is TripInput.ScreenChanged -> isScreenOn = input.on
            is TripInput.MapsActiveChanged -> isMapsActive = input.active
            is TripInput.Tick -> { /* NOOP */ }
        }
    }

    private fun handleIdle(timestamp: Long) {
        // IDLE → IGNITION_ON: SCREEN_ON + Connection + no connection in last 30s
        if (isScreenOn && isConnected && (timestamp - lastDisconnectTimestamp >= 30_000)) {
            changeState(TripState.IGNITION_ON, timestamp)
        }
    }

    private fun handleIgnitionOn(timestamp: Long) {
        // False alarm: connection drops within 10s
        if (!isConnected && (timestamp - stateEnterTimestamp < 10_000)) {
            changeState(TripState.IDLE, timestamp)
            return
        }
        
        // False ignition: no speed change within 60s
        if (timestamp - stateEnterTimestamp >= 60_000 && speedConditionStartTimestamp == null) {
            changeState(TripState.IDLE, timestamp)
            return
        }

        // Speed > 10 km/h sustained for 5s → DRIVING
        if (currentSpeedKmH > 10f) {
            if (speedConditionStartTimestamp == null) {
                speedConditionStartTimestamp = timestamp
            } else if (timestamp - speedConditionStartTimestamp!! >= 5_000) {
                changeState(TripState.DRIVING, timestamp)
            }
        } else {
            speedConditionStartTimestamp = null
        }
    }

    private fun handleDriving(timestamp: Long) {
        // Speed < 5 km/h sustained for 10s + Maps active → PARKING
        if (currentSpeedKmH < 5f && isMapsActive) {
            if (speedConditionStartTimestamp == null) {
                speedConditionStartTimestamp = timestamp
            } else if (timestamp - speedConditionStartTimestamp!! >= 10_000) {
                changeState(TripState.PARKING, timestamp)
            }
        } else {
            speedConditionStartTimestamp = null
        }

        // Brief disconnection handling (<30s stay in DRIVING).
        // Transition to ENGINE_OFF only if SCREEN_OFF also detected.
        if (!isConnected && !isScreenOn) {
            changeState(TripState.ENGINE_OFF, timestamp)
        }
    }

    private fun handleParking(timestamp: Long) {
        // PARKING → ENGINE_OFF: SCREEN_OFF + connection drops
        if (!isScreenOn && !isConnected) {
            changeState(TripState.ENGINE_OFF, timestamp)
        }
    }

    private fun handleEngineOff(timestamp: Long) {
        // Immediate cleanup
        changeState(TripState.IDLE, timestamp)
    }

    private fun changeState(newState: TripState, timestamp: Long) {
        currentState = newState
        stateEnterTimestamp = timestamp
        speedConditionStartTimestamp = null
    }

    /**
     * Returns the current state of the machine.
     */
    fun getCurrentState(): TripState = currentState
}
