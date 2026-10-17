package com.airbridge.touch.transport

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import com.airbridge.touch.core.AppError
import com.airbridge.touch.core.ErrorMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection state machine over WifiDirectController. Prevents invalid transitions,
 * duplicate connection attempts, and conflicting concurrent operations.
 *
 * States: IDLE -> DISCOVERING -> CONNECTING -> CONNECTED -> DISCONNECTING -> IDLE
 * Any state may move to FAILED (then back to IDLE via reset()).
 */
class ConnectionMachine(
    private val controller: WifiDirectController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val logger: (String) -> Unit = {}
) {
    enum class S { IDLE, DISCOVERING, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

    private val _state = MutableStateFlow(S.IDLE)
    val state: StateFlow<S> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _connectedPeer = MutableStateFlow<PeerDevice?>(null)
    val connectedPeer: StateFlow<PeerDevice?> = _connectedPeer

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner

    private val _groupOwnerAddress = MutableStateFlow<String?>(null)
    val groupOwnerAddress: StateFlow<String?> = _groupOwnerAddress

    private val _availablePeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val availablePeers: StateFlow<List<PeerDevice>> = _availablePeers

    private val connectMutex = Mutex()
    private var eventJob: Job? = null
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!controller.supported) {
            _state.value = S.FAILED
            _error.value = "This device does not support Wi-Fi Direct."
            return
        }
        if (!controller.initChannel()) {
            _state.value = S.FAILED
            _error.value = "Wi-Fi Direct could not be initialized."
            return
        }
        eventJob = scope.launch {
            controller.p2pEvents().collect { ev ->
                when (ev) {
                    is P2pEvent.PeersChanged -> {
                        _availablePeers.value = ev.devices.map(WifiDirectController::toPeerDevice)
                    }
                    is P2pEvent.ConnectionChanged -> {
                        if (ev.info == null || !ev.info.groupFormed) {
                            if (_state.value == S.CONNECTED) {
                                logger("Group lost; moving to IDLE")
                                reset("Connection lost.")
                            }
                        }
                    }
                    is P2pEvent.P2pEnabled -> {
                        if (!ev.enabled && _state.value != S.IDLE) {
                            reset("Wi-Fi was turned off.")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    suspend fun startDiscovery() {
        transition(S.DISCOVERING)
        _error.value = null
        try {
            controller.startDiscovery()
        } catch (t: Throwable) {
            val e = ErrorMapper.fromThrowable(t)
            fail(e.message ?: "Discovery failed")
            throw e
        }
    }

    suspend fun stopDiscovery() {
        if (_state.value == S.DISCOVERING) {
            runCatching { controller.stopDiscovery() }
            transition(S.IDLE)
        }
    }

    /** Single-flight connect. Returns the raw target device address on success. */
    suspend fun connect(peer: PeerDevice): String = connectMutex.withLock {
        if (_state.value == S.CONNECTED) throw AppError.ConnectionFailed("Already connected. Disconnect first.", false)
        if (_state.value == S.CONNECTING) throw AppError.ConnectionFailed("Connection already in progress.", false)
        _state.value = S.CONNECTING
        _error.value = null
        try {
            // We need the raw WifiP2pDevice; re-request the current peer list.
            val raw = kotlinx.coroutines.suspendCoroutine { cont ->
                controller.requestPeers { list ->
                    cont.resumeWith(Result.success(list.firstOrNull { it.deviceAddress == peer.id }))
                }
            } ?: throw AppError.ConnectionFailed("Peer is no longer visible. Rescan and retry.", true)
            val info = controller.connect(raw as WifiP2pDevice)
            _isGroupOwner.value = info.isGroupOwner
            _groupOwnerAddress.value = info.groupOwnerAddress?.hostAddress
            _connectedPeer.value = peer
            _state.value = S.CONNECTED
            peer.id
        } catch (t: Throwable) {
            val e = ErrorMapper.fromThrowable(t)
            fail(e.message ?: "Connection failed")
            throw e
        }
    }

    suspend fun disconnect() {
        if (_state.value == S.IDLE) return
        transition(S.DISCONNECTING)
        runCatching { controller.removeGroup() }
        _connectedPeer.value = null
        _isGroupOwner.value = false
        _groupOwnerAddress.value = null
        transition(S.IDLE)
    }

    fun reset(message: String? = null) {
        scope.launch {
            runCatching { controller.removeGroup() }
        }
        _connectedPeer.value = null
        _isGroupOwner.value = false
        _groupOwnerAddress.value = null
        if (message != null) {
            _error.value = message
            _state.value = S.FAILED
        } else {
            _state.value = S.IDLE
        }
    }

    private fun fail(message: String) {
        _error.value = message
        _state.value = S.FAILED
    }

    private fun transition(to: S) {
        val from = _state.value
        val allowed = when (to) {
            S.DISCOVERING -> from == S.IDLE
            S.CONNECTING -> from == S.IDLE || from == S.DISCOVERING
            S.CONNECTED -> from == S.CONNECTING
            S.DISCONNECTING -> from == S.CONNECTED
            S.IDLE -> from in setOf(S.DISCOVERING, S.DISCONNECTING, S.FAILED)
            S.FAILED -> true
        }
        if (!allowed) {
            logger("Illegal transition $from -> $to ignored")
            return
        }
        _state.value = to
    }

    fun shutdown() {
        eventJob?.cancel()
        scope.cancel()
        controller.close()
    }
}
