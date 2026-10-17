package com.airbridge.touch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbridge.touch.transport.PeerDevice
import com.airbridge.touch.transport.TransportCapabilities
import com.airbridge.touch.transport.TransportState
import com.airbridge.touch.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirBridgeApp() }
    }
}

class MainViewModel(private val transport: WifiDirectTransport) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transport.state.collectLatest { state ->
                _uiState.value = _uiState.value.copy(transportState = state, error = null)
            }
        }
        refreshCapabilities()
    }

    fun refreshCapabilities() = viewModelScope.launch {
        runCatching { transport.capabilities() }
            .onSuccess { _uiState.value = _uiState.value.copy(capabilities = it, error = null) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Capability check failed") }
    }

    fun discover() = viewModelScope.launch {
        runCatching { transport.startDiscovery() }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Discovery failed") }
    }

    fun stopDiscovery() = viewModelScope.launch { runCatching { transport.stopDiscovery() } }

    fun connect(peer: PeerDevice) = viewModelScope.launch {
        runCatching { transport.connect(peer.id) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "Connection failed") }
    }

    fun disconnect() = viewModelScope.launch { runCatching { transport.disconnect() } }

    override fun onCleared() {
        viewModelScope.launch { transport.shutdown() }
    }
}

data class MainUiState(
    val capabilities: TransportCapabilities? = null,
    val transportState: TransportState = TransportState.Idle,
    val error: String? = null
)

@Suppress("UNCHECKED_CAST")
class MainViewModelFactory(private val transport: WifiDirectTransport) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(transport) as T
}

@Composable
fun AirBridgeApp() {
    val context = LocalContext.current.applicationContext
    val transport = remember { WifiDirectTransport(context) }
    val vm: MainViewModel = viewModel(factory = MainViewModelFactory(transport))
    val state by vm.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshCapabilities() }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    AirBridgeTheme { MainScreen(state, vm::discover, vm::stopDiscovery, vm::connect, vm::disconnect, vm::refreshCapabilities) }
}

@Composable
fun AirBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onDiscover: () -> Unit,
    onStop: () -> Unit,
    onConnect: (PeerDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("AirBridgeTouch") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Rounded.Refresh, null) }, label = { Text("Home") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Refresh, null) }, label = { Text("Transfers") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Rounded.Refresh, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Secure offline file transfer over Wi‑Fi Direct", style = MaterialTheme.typography.headlineSmall)
                CapabilityCard(state.capabilities, onRefresh)
                StateCard(state.transportState, state.error, onDiscover, onStop, onDisconnect)
                PeerList(state.transportState, onConnect)
            }
        }
    }
}

@Composable
private fun CapabilityCard(caps: TransportCapabilities?, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Device capability status" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capability", style = MaterialTheme.typography.titleMedium)
            Text(caps?.details ?: "Checking Wi‑Fi Direct capability…")
            Text("Supported: ${caps?.supported ?: false} · Wi‑Fi enabled: ${caps?.wifiEnabled ?: false} · Permissions: ${caps?.permissionsGranted ?: false}")
            Button(onClick = onRefresh) { Text("Refresh diagnostics") }
        }
    }
}

@Composable
private fun StateCard(state: TransportState, error: String?, onDiscover: () -> Unit, onStop: () -> Unit, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Connection status" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text(statusText(state))
            if (error != null) Text("Error: $error", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDiscover) { Text("Discover nearby devices") }
                Button(onClick = onStop) { Text("Stop") }
                Button(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun PeerList(state: TransportState, onConnect: (PeerDevice) -> Unit) {
    val peers = (state as? TransportState.PeersAvailable)?.peers.orEmpty()
    Text("Nearby devices", style = MaterialTheme.typography.titleLarge)
    if (peers.isEmpty()) {
        Text("No real Wi‑Fi Direct peers discovered yet. Keep both phones unlocked and nearby, then retry discovery.")
        Spacer(Modifier.height(8.dp))
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers, key = { it.id }) { peer ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(peer.name); Text(peer.status, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { onConnect(peer) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}

private fun statusText(state: TransportState): String = when (state) {
    TransportState.Idle -> "Idle"
    TransportState.Discovering -> "Discovering nearby Wi‑Fi Direct peers…"
    is TransportState.PeersAvailable -> "Found ${state.peers.size} peer(s)."
    is TransportState.Connecting -> "Connecting to ${state.peer.name}…"
    is TransportState.Connected -> "Connected to ${state.peerName}; group owner=${state.isGroupOwner}; owner address=${state.groupOwnerAddress ?: "pending"}."
    is TransportState.Failed -> state.message
    is TransportState.Unavailable -> state.reason
}
