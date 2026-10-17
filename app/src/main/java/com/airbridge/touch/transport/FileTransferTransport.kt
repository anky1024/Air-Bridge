package com.airbridge.touch.transport

import kotlinx.coroutines.flow.Flow

interface FileTransferTransport {
    val state: Flow<TransportState>
    suspend fun capabilities(): TransportCapabilities
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun connect(peerId: String)
    suspend fun disconnect()
    suspend fun shutdown()
}

data class TransportCapabilities(
    val supported: Boolean,
    val wifiEnabled: Boolean,
    val permissionsGranted: Boolean,
    val details: String
)

data class PeerDevice(
    val id: String,
    val name: String,
    val address: String,
    val status: String
)

sealed class TransportState {
    data object Idle : TransportState()
    data class Unavailable(val reason: String) : TransportState()
    data object Discovering : TransportState()
    data class PeersAvailable(val peers: List<PeerDevice>) : TransportState()
    data class Connecting(val peer: PeerDevice) : TransportState()
    data class Connected(val peerName: String, val isGroupOwner: Boolean, val groupOwnerAddress: String?) : TransportState()
    data class Failed(val message: String, val retryable: Boolean) : TransportState()
}
