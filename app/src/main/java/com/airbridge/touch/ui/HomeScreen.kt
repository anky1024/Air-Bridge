package com.airbridge.touch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, onPickFiles: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("AirBridgeTouch", style = MaterialTheme.typography.titleLarge)
            })
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionCard(ui)
            ui.error?.let { msg ->
                ErrorCard(msg, onDismiss = { vm.clearError() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPickFiles, enabled = ui.connectedPeer != null) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send files")
                }
                OutlinedButton(onClick = { vm.startDiscovery() }, enabled = !ui.busy) {
                    Text("Discover devices")
                }
            }
            if (ui.progress != null) {
                ProgressCard(ui.progress!!, onCancel = { vm.cancelTransfer() })
            }
            Text("Recent transfers", style = MaterialTheme.typography.titleMedium)
            if (ui.history.isEmpty()) {
                Text(
                    "No transfers yet. Connect to a nearby device to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(ui.history) { rec ->
                        TransferRow(rec)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(ui: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            val status = when (ui.connectionState) {
                ConnectionMachine.S.IDLE -> "Idle — not connected"
                ConnectionMachine.S.DISCOVERING -> "Searching for nearby devices…"
                ConnectionMachine.S.CONNECTING -> "Connecting…"
                ConnectionMachine.S.CONNECTED -> "Connected to ${ui.connectedPeer?.name ?: "peer"}"
                ConnectionMachine.S.DISCONNECTING -> "Disconnecting…"
                ConnectionMachine.S.FAILED -> "Error — check the message below"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (ui.connectionState == ConnectionMachine.S.CONNECTED) {
                Text(
                    if (ui.isGroupOwner) "This device is the group owner." else "Peer is the group owner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ProgressCard(p: com.airbridge.touch.core.ProgressSnapshot, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${p.direction.name.lowercase().replaceFirstChar { it.uppercase() }}: ${p.fileName}")
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatBytes(p.bytesTransferred) + " / " + formatBytes(p.totalBytes))
                Spacer(Modifier.weight(1f))
                if (p.bytesPerSecond > 0) {
                    Text(formatBytes(p.bytesPerSecond) + "/s")
                    Spacer(Modifier.width(12.dp))
                }
                IconButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun TransferRow(rec: com.airbridge.touch.core.TransferRecord) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rec.fileName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${rec.direction.name.lowercase()} • ${formatBytes(rec.sizeBytes)} • ${rec.status.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
