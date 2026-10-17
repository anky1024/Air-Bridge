package com.airbridge.touch.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransferDirection { SEND, RECEIVE }
enum class TransferStatus { PENDING, AWAITING_APPROVAL, IN_PROGRESS, SUCCESS, FAILED, CANCELLED }

data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val fileName: String,
    val sizeBytes: Long,
    val peerName: String,
    val status: TransferStatus,
    val bytesTransferred: Long = 0,
    val sha256: String? = null,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val failureReason: String? = null,
    val destinationUri: String? = null
)

data class ProgressSnapshot(
    val transferId: String,
    val fileName: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val elapsedMs: Long,
    val etaMs: Long?,
    val verifying: Boolean
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toDouble() / totalBytes).toFloat() else 0f
}

/** Emits at most one update per [minIntervalMs] and only when progress actually moved. */
class ProgressReporter(private val minIntervalMs: Long = 200L) {
    private val lastEmit = MutableStateFlow(0L)

    fun shouldEmit(nowMs: Long, bytesNow: Long, bytesPrev: Long): Boolean {
        if (bytesNow <= bytesPrev) return false
        val last = lastEmit.value
        if (nowMs - last >= minIntervalMs) {
            lastEmit.value = nowMs
            return true
        }
        return false
    }

    fun reset() { lastEmit.value = 0 }
}

object TransferLimits {
    const val DEFAULT_MAX_FILE_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MAX_METADATA_NAME_BYTES = 255
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024
    const val SOCKET_TIMEOUT_MS = 15_000
    const val CONNECT_TIMEOUT_MS = 10_000
}
