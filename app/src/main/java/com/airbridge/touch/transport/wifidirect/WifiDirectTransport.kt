package com.airbridge.touch.transport.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.airbridge.touch.core.AppError
import com.airbridge.touch.transport.FileTransferTransport
import com.airbridge.touch.transport.PeerDevice
import com.airbridge.touch.transport.TransportCapabilities
import com.airbridge.touch.transport.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WifiDirectTransport(private val appContext: Context) : FileTransferTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val manager: WifiP2pManager? = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = manager?.initialize(appContext, appContext.mainLooper) {
        _state.value = TransportState.Failed("Wi-Fi Direct channel disconnected.", retryable = true)
    }
    private val peers = linkedMapOf<String, WifiP2pDevice>()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) _state.value = TransportState.Unavailable("Wi-Fi Direct is disabled or unavailable.")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeerList()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
            }
        }
    }

    override suspend fun capabilities(): TransportCapabilities {
        val supported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && manager != null && channel != null
        val wifiEnabled = isWifiEnabled()
        val permissionsGranted = hasRequiredPermission()
        val details = when {
            !supported -> "Wi-Fi Direct framework or hardware is unavailable on this device."
            !wifiEnabled -> "Wi-Fi is disabled. Enable Wi-Fi before discovery."
            !permissionsGranted -> "Required nearby-device/location permission is not granted."
            else -> "Wi-Fi Direct is available. Discovery still depends on device firmware and environment."
        }
        return TransportCapabilities(supported, wifiEnabled, permissionsGranted, details)
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        val caps = capabilities()
        if (!caps.supported) throw AppError.WifiDirectUnsupported()
        if (!caps.wifiEnabled) throw AppError.WifiDisabled()
        if (!caps.permissionsGranted) throw AppError.PermissionMissing(caps.details)
        registerReceiverIfNeeded()
        val m = manager ?: throw AppError.WifiDirectUnsupported()
        val ch = channel ?: throw AppError.WifiDirectUnsupported()
        _state.value = TransportState.Discovering
        val result = suspendAction { listener -> m.discoverPeers(ch, listener) }
        if (!result.success) {
            _state.value = TransportState.Failed("Peer discovery failed: ${result.reasonText}", true)
            throw AppError.DiscoveryFailed("Peer discovery failed: ${result.reasonText}")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        suspendAction { listener -> m.stopPeerDiscovery(ch, listener) }
        _state.value = TransportState.Idle
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peerId: String) {
        val device = peers[peerId] ?: throw AppError.ConnectionFailed("Selected peer is no longer available.")
        val m = manager ?: throw AppError.WifiDirectUnsupported()
        val ch = channel ?: throw AppError.WifiDirectUnsupported()
        _state.value = TransportState.Connecting(device.toPeerDevice())
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        val result = suspendAction { listener -> m.connect(ch, config, listener) }
        if (!result.success) {
            _state.value = TransportState.Failed("Connection request failed: ${result.reasonText}", true)
            throw AppError.ConnectionFailed("Connection request failed: ${result.reasonText}")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        val m = manager ?: return
        val ch = channel ?: return
        suspendAction { listener -> m.removeGroup(ch, listener) }
        peers.clear()
        _state.value = TransportState.Idle
    }

    override suspend fun shutdown() {
        stopDiscovery()
        unregisterReceiverIfNeeded()
        peers.clear()
        channel = null
        _state.value = TransportState.Idle
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun requestPeerList() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!hasRequiredPermission()) return
        try {
            m.requestPeers(ch) { peerList ->
                peers.clear()
                peerList.deviceList.forEach { peers[it.deviceAddress] = it }
                _state.value = TransportState.PeersAvailable(peers.values.map { it.toPeerDevice() })
            }
        } catch (se: SecurityException) {
            _state.value = TransportState.Failed("Permission is required to read Wi-Fi Direct peers.", true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                if (info.groupFormed) {
                    val peerName = peers.values.firstOrNull()?.deviceName ?: "Connected peer"
                    _state.value = TransportState.Connected(peerName, info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
                } else {
                    _state.value = TransportState.Idle
                }
            }
        } catch (se: SecurityException) {
            _state.value = TransportState.Failed("Permission is required to read Wi-Fi Direct connection info.", true)
        }
    }

    private fun isWifiEnabled(): Boolean {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        @Suppress("DEPRECATION")
        return wifi.isWifiEnabled
    }

    private fun hasRequiredPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun suspendAction(block: (WifiP2pManager.ActionListener) -> Unit): ActionResult = suspendCancellableCoroutine { cont ->
        try {
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(ActionResult(true, 0))
                }
                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resume(ActionResult(false, reason))
                }
            })
        } catch (se: SecurityException) {
            if (cont.isActive) cont.resume(ActionResult(false, SECURITY_FAILURE))
        }
    }

    private fun WifiP2pDevice.toPeerDevice(): PeerDevice = PeerDevice(
        id = deviceAddress,
        name = deviceName.ifBlank { "Unnamed device" },
        address = deviceAddress,
        status = statusToText(status)
    )

    private fun statusToText(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }

    private data class ActionResult(val success: Boolean, val reason: Int) {
        val reasonText: String get() = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported."
            WifiP2pManager.ERROR -> "Framework error."
            WifiP2pManager.BUSY -> "Wi-Fi Direct service is busy."
            SECURITY_FAILURE -> "Required permission was denied."
            else -> "Reason $reason"
        }
    }

    companion object { private const val SECURITY_FAILURE = -1001 }
}
