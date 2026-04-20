package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket

/**
 * Shared infrastructure for connection confirmation.
 * Manages the flow between connection attempts in AapService and user approval in the UI.
 */
object ConnectionMediator {

    sealed class PendingConnection {
        data class Usb(val device: UsbDevice) : PendingConnection()
        data class Wifi(val ip: String, val port: Int) : PendingConnection()
        data class Incoming(val socket: Socket) : PendingConnection()
        data class Nearby(val endpointId: String) : PendingConnection()
    }

    private val _pendingConnection = MutableStateFlow<PendingConnection?>(null)
    val pendingConnection: StateFlow<PendingConnection?> = _pendingConnection.asStateFlow()

    private val _pendingBtWarning = MutableStateFlow(false)
    val pendingBtWarning: StateFlow<Boolean> = _pendingBtWarning.asStateFlow()

    fun signalBtRequired() {
        _pendingBtWarning.value = true
    }

    fun clearBtWarning() {
        _pendingBtWarning.value = false
    }

    /**
     * Internal channel used to notify requestConnection of the user's decision.
     */
    private val approvalChannel = Channel<Boolean>(Channel.CONFLATED)

    private val requestMutex = Mutex()

    /**
     * Requests user approval for a new connection.
     * Suspends until the user approves or rejects, or until the request is cancelled.
     * If multiple requests occur, they are handled sequentially via a Mutex (queuing).
     */
    suspend fun requestConnection(conn: PendingConnection): Boolean = requestMutex.withLock {
        // Clear any stale value from the conflated channel
        approvalChannel.tryReceive()

        _pendingConnection.value = conn
        try {
            return approvalChannel.receive()
        } finally {
            _pendingConnection.value = null
        }
    }

    /**
     * Called by the UI to approve the current pending connection.
     */
    fun approve() {
        approvalChannel.trySend(true)
    }

    /**
     * Called by the UI to reject the current pending connection.
     */
    fun reject() {
        approvalChannel.trySend(false)
    }
}
