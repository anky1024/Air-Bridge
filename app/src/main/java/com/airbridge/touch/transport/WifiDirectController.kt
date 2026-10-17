package com.airbridge.touch.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.airbridge.touch.core.AppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Wraps WifiP2pManager into suspend/Flow APIs. All framework callbacks arrive on the
 * application's main thread; this class only translates them, it does no network I/O.
 */
class WifiDirectController(
    private val context: Context,
    private val logger: (String) -> Unit = {}
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo

    private val _thisDevice = MutableStateFlow<WifiP2pDevice?>(null)
    val thisDevice: StateFlow<WifiP2pDevice?> = _thisDevice

    val supported: Boolean get() = manager != null

    fun initChannel(): Boolean {
        if (manager == null) return false
        if (channel == null) {
            channel = manager.initialize(context, context.mainLooper, null)
        }
        return channel != null
    }

    fun wifiStateEnabled(): Boolean {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return false
        return wm.isWifiEnabled
    }

    // ----- Events as a Flow -----

    fun p2pEvents(): kotlinx.coroutines.flow.Flow<P2pEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        trySend(P2pEvent.P2pEnabled(enabled))
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers { list -> trySend(P2pEvent.PeersChanged(list)) }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        requestConnectionInfo { info -> trySend(P2pEvent.ConnectionChanged(info)) }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val dev = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        if (dev != null) { _thisDevice.value = dev; trySend(P2pEvent.ThisDeviceChanged(dev)) }
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        trySend(P2pEvent.DiscoveryChanged(started))
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        val flags = if (Build.VERSION.SDK_INT >= 33)
            Context.RECEIVER_NOT_EXPORTED else 0
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, flags)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    fun requestPeers(onResult: (List<WifiP2pDevice>) -> Unit) {
        val ch = channel ?: run { onResult(emptyList()); return }
        manager?.requestPeers(ch) { list -> onResult(list.deviceList.toList()) }
    }

    fun requestConnectionInfo(onResult: (WifiP2pInfo?) -> Unit) {
        val ch = channel ?: run { onResult(null); return }
        manager?.requestConnectionInfo(ch) { info -> onResult(info) }
    }

    // ----- Suspend operations -----

    suspend fun startDiscovery(timeoutMs: Long = 10_000): Boolean = withContext(Dispatchers.Main) {
        val ch = channel ?: throw AppError.WifiDirectUnsupported()
        if (!wifiStateEnabled()) throw AppError.WifiDisabled()
        val ok = suspendCancellableCoroutine { cont ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            manager.discoverPeers(ch, listener)
        }
        if (!ok) throw AppError.DiscoveryFailed("System rejected peer discovery.")
        // Wait for the first peers-changed broadcast or timeout.
        val got = withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.flow.first(p2pEvents()) { it is P2pEvent.PeersChanged }
        }
        ok && got != null
    }

    suspend fun stopDiscovery() = withContext(Dispatchers.Main) {
        val ch = channel ?: return@withContext
        suspendCancellableCoroutine { cont ->
            manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
            })
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: WifiP2pDevice, timeoutMs: Long = 45_000): WifiP2pInfo = withContext(Dispatchers.Main) {
        val ch = channel ?: throw AppError.WifiDirectUnsupported()
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        val initiated = suspendCancellableCoroutine { cont ->
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
            })
        }
        if (!initiated) throw AppError.ConnectionFailed("System rejected the connection request.")

        // Wait for the connection to become formed (group established).
        val info: WifiP2pInfo = withTimeoutOrNull(timeoutMs) {
            var result: WifiP2pInfo? = null
            p2pEvents().collect { ev ->
                if (ev is P2pEvent.ConnectionChanged && ev.info != null && ev.info.groupFormed) {
                    result = ev.info
                    return@collect
                }
            }
            result
        } ?: throw AppError.ConnectionFailed("Connection timed out while forming the group.", true)
        _connectionInfo.value = info
        info
    }

    suspend fun requestGroupInfo(): WifiP2pManager.GroupInfo? = withContext(Dispatchers.Main) {
        val ch = channel ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            manager.requestGroupInfo(ch) { gi ->
                if (cont.isActive) cont.resume(gi)
            }
        }
    }

    suspend fun removeGroup() = withContext(Dispatchers.Main) {
        val ch = channel ?: return@withContext
        suspendCancellableCoroutine { cont ->
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
            })
        }
        _connectionInfo.value = null
    }

    fun close() {
        runCatching { channel?.close() }
        channel = null
    }

    companion object {
        fun toPeerDevice(d: WifiP2pDevice): PeerDevice = PeerDevice(
            id = d.deviceAddress,
            name = d.deviceName.ifBlank { "Unknown device" },
            address = d.deviceAddress,
            status = when (d.status) {
                WifiP2pDevice.CONNECTED -> "Connected"
                WifiP2pDevice.INVITED -> "Invited"
                WifiP2pDevice.FAILED -> "Failed"
                WifiP2pDevice.AVAILABLE -> "Available"
                WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                else -> "Unknown"
            }
        )
    }
}

sealed class P2pEvent {
    data class P2pEnabled(val enabled: Boolean) : P2pEvent()
    data class PeersChanged(val devices: List<WifiP2pDevice>) : P2pEvent()
    data class ConnectionChanged(val info: WifiP2pInfo?) : P2pEvent()
    data class ThisDeviceChanged(val device: WifiP2pDevice) : P2pEvent()
    data class DiscoveryChanged(val started: Boolean) : P2pEvent()
}
