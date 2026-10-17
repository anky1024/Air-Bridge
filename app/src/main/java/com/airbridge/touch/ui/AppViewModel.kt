package com.airbridge.touch.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airbridge.touch.core.AppError
import com.airbridge.touch.core.ErrorMapper
import com.airbridge.touch.core.Hashing
import com.airbridge.touch.core.ProgressSnapshot
import com.airbridge.touch.core.TransferDirection
import com.airbridge.touch.core.TransferRecord
import com.airbridge.touch.core.TransferStatus
import com.airbridge.touch.data.HistoryRepository
import com.airbridge.touch.data.SettingsRepository
import com.airbridge.touch.transport.ConnectionMachine
import com.airbridge.touch.transport.TcpLink
import com.airbridge.touch.transfer.DestinationManager
import com.airbridge.touch.transfer.FileReceiver
import com.airbridge.touch.transfer.FileSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val connectionState: ConnectionMachine.S = ConnectionMachine.S.IDLE,
    val error: String? = null,
    val peers: List<com.airbridge.touch.transport.PeerDevice> = emptyList(),
    val connectedPeer: com.airbridge.touch.transport.PeerDevice? = null,
    val isGroupOwner: Boolean = false,
    val progress: ProgressSnapshot? = null,
    val history: List<TransferRecord> = emptyList(),
    val busy: Boolean = false
)

class AppViewModel(
    private val context: Context,
    val machine: ConnectionMachine,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val destinations: DestinationManager
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var link: TcpLink? = null
    private var activeTransfer: Job? = null
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        machine.start()
        viewModelScope.launch {
            machine.state.collect { s -> _ui.value = _ui.value.copy(connectionState = s) }
        }
        viewModelScope.launch {
            machine.error.collect { e -> _ui.value = _ui.value.copy(error = e) }
        }
        viewModelScope.launch {
            machine.availablePeers.collect { p -> _ui.value = _ui.value.copy(peers = p) }
        }
        viewModelScope.launch {
            machine.connectedPeer.collect { p -> _ui.value = _ui.value.copy(connectedPeer = p) }
        }
        viewModelScope.launch {
            machine.isGroupOwner.collect { o -> _ui.value = _ui.value.copy(isGroupOwner = o) }
        }
        viewModelScope.launch {
            history.observeRecent().collect { h -> _ui.value = _ui.value.copy(history = h) }
        }
        listenForIncoming()
    }

    private fun listenForIncoming() {
        viewModelScope.launch(Dispatchers.IO) {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val l = TcpLink(UUID.randomUUID().toString(), deviceName()) { logger(it) }
                try {
                    l.acceptServer()
                    val identity = l.handshake()
                    link = l
                    val receiver = FileReceiver(l, settings.current().maxFileBytes)
                    val result = receiver.receiveFile(
                        openOutput = { safeName, _ ->
                            val tree = settings.current().receiveTreeUri
                                ?: throw AppError.StorageError("No receive folder selected in Settings.")
                            val uri = destinations.createUniqueDestination(
                                Uri.parse(tree), safeName, destinations.mimeTypeFor(safeName))
                            context.contentResolver.openOutputStream(uri)
                                ?: throw AppError.StorageError("Could not open the destination file.")
                        },
                        commit = { safeName, _ -> safeName },
                        onProgress = { sent ->
                            _ui.value = _ui.value.copy(progress = ProgressSnapshot(
                                transferId = "incoming", fileName = "Incoming",
                                direction = TransferDirection.RECEIVE,
                                bytesTransferred = sent, totalBytes = 0,
                                bytesPerSecond = 0, elapsedMs = 0, etaMs = null, verifying = false))
                        },
                        isCancelled = { cancelled.get() }
                    )
                    recordHistory(TransferDirection.RECEIVE, result.fileName, result.size,
                        identity.peerName, TransferStatus.SUCCESS, result.size, result.sha256Hex)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val e = ErrorMapper.fromThrowable(t)
                    recordHistory(TransferDirection.RECEIVE, "incoming", 0, "unknown",
                        TransferStatus.FAILED, 0, null, e.message)
                } finally {
                    runCatching { l.close() }
                    if (link === l) link = null
                    _ui.value = _ui.value.copy(progress = null)
                }
            }
        }
    }

    fun startDiscovery() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            try {
                machine.startDiscovery()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(error = ErrorMapper.fromThrowable(t).message)
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch { runCatching { machine.stopDiscovery() } }
    }

    fun connect(peer: com.airbridge.touch.transport.PeerDevice) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            try {
                machine.connect(peer)
                openLinkAsClientIfNeeded()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(error = ErrorMapper.fromThrowable(t).message)
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    private suspend fun openLinkAsClientIfNeeded() {
        if (machine.isGroupOwner.value) {
            // We are the group owner: the peer connects to our listener.
            return
        }
        val info = machine.groupOwnerAddress.value
            ?: throw AppError.ConnectionFailed("Group owner address unavailable.")
        val address = java.net.InetAddress.getByName(info)
        val l = TcpLink(UUID.randomUUID().toString(), deviceName()) { logger(it) }
        l.connectClient(address)
        l.handshake()
        link = l
    }

    fun disconnect() {
        viewModelScope.launch {
            cancelled.set(true)
            activeTransfer?.cancel()
            runCatching { link?.close() }
            link = null
            machine.disconnect()
        }
    }

    fun sendFiles(uris: List<Uri>) {
        val l = link ?: run {
            _ui.value = _ui.value.copy(error = "Not connected to a peer.")
            return
        }
        activeTransfer?.cancel()
        activeTransfer = viewModelScope.launch(Dispatchers.IO) {
            val maxBytes = settings.current().maxFileBytes
            for (uri in uris) {
                if (cancelled.get()) break
                sendOne(l, uri, maxBytes)
            }
            _ui.value = _ui.value.copy(progress = null)
        }
    }

    private suspend fun sendOne(l: TcpLink, uri: Uri, maxBytes: Long) {
        try {
            val resolver = context.contentResolver
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size <= 0) throw AppError.StorageError("Cannot determine file size.")
            if (size > maxBytes) throw AppError.SecurityRejection("File exceeds the configured size limit.")
            val name = resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) ?: "file" else "file"
            } ?: "file"
            val recordId = UUID.randomUUID().toString()
            val startMs = System.currentTimeMillis()

            recordHistory(TransferDirection.SEND, name, size,
                machine.connectedPeer.value?.name ?: "", TransferStatus.IN_PROGRESS, 0, null, id = recordId)

            FileSender(l, maxBytes).sendFile(
                transferId = recordId,
                fileName = name,
                size = size,
                sha256Hex = "",
                open = {
                    val raw = resolver.openInputStream(uri)
                        ?: throw AppError.StorageError("Could not open the selected file.")
                    HashingInputStream(raw)
                },
                onProgress = { sent ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - startMs
                    val speed = if (elapsed > 0) sent * 1000 / elapsed else 0
                    _ui.value = _ui.value.copy(progress = ProgressSnapshot(
                        transferId = recordId, fileName = name, direction = TransferDirection.SEND,
                        bytesTransferred = sent, totalBytes = size, bytesPerSecond = speed,
                        elapsedMs = elapsed,
                        etaMs = if (speed > 0) ((size - sent) * 1000 / speed) else null,
                        verifying = false))
                },
                isCancelled = { cancelled.get() }
            )
            recordHistory(TransferDirection.SEND, name, size,
                machine.connectedPeer.value?.name ?: "", TransferStatus.SUCCESS, size, null, id = recordId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val e = ErrorMapper.fromThrowable(t)
            recordHistory(TransferDirection.SEND, "file", 0,
                machine.connectedPeer.value?.name ?: "", TransferStatus.FAILED, 0, null, e.message)
            _ui.value = _ui.value.copy(error = e.message)
        }
    }

    /** Streams and digests in one pass; bounded memory. */
    private class HashingInputStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        val digest = Hashing.newSha256Digester()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) digest.update(b, off, n)
            return n
        }
        fun hex(): String = Hashing.toHex(digest.digest())
    }

    private suspend fun recordHistory(
        direction: TransferDirection, fileName: String, size: Long, peer: String,
        status: TransferStatus, bytes: Long, sha: String?, failure: String? = null,
        id: String = UUID.randomUUID().toString()
    ) {
        runCatching {
            history.insert(TransferRecord(
                id = id, direction = direction, fileName = fileName, sizeBytes = size,
                peerName = peer, status = status, bytesTransferred = bytes, sha256 = sha,
                startedAtEpochMs = System.currentTimeMillis(),
                completedAtEpochMs = if (status.isTerminal()) System.currentTimeMillis() else null,
                failureReason = failure
            ))
        }
    }

    private fun TransferStatus.isTerminal(): Boolean =
        this == TransferStatus.SUCCESS || this == TransferStatus.FAILED || this == TransferStatus.CANCELLED

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun cancelTransfer() {
        cancelled.set(true)
        activeTransfer?.cancel()
        _ui.value = _ui.value.copy(progress = null)
    }

    private fun deviceName(): String = android.os.Build.MODEL ?: "Android device"

    override fun onCleared() {
        activeTransfer?.cancel()
        runCatching { link?.close() }
        machine.shutdown()
        super.onCleared()
    }
}
