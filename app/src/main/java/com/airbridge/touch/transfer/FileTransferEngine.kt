package com.airbridge.touch.transfer

import android.content.Context
import android.net.Uri
import com.airbridge.touch.core.AppError
import com.airbridge.touch.core.FileNameSanitizer
import com.airbridge.touch.core.Hashing
import com.airbridge.touch.core.TransferLimits
import com.airbridge.touch.protocol.Abtp
import com.airbridge.touch.protocol.Frame
import com.airbridge.touch.protocol.PayloadReader
import com.airbridge.touch.protocol.Payloads
import com.airbridge.touch.transport.TcpLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Sender: streams a file from an [InputStream] (SAF or plain) over an established [TcpLink].
 * Bounded memory: only one chunk buffer at a time.
 */
class FileSender(private val link: TcpLink, private val maxFileBytes: Long = TransferLimits.DEFAULT_MAX_FILE_BYTES) {

    suspend fun sendFile(
        transferId: String,
        fileName: String,
        size: Long,
        sha256Hex: String,
        open: suspend () -> InputStream,
        onProgress: (bytesSent: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Unit = withContext(Dispatchers.IO) {
        if (size < 0) throw AppError.ProtocolViolation("Negative file size")
        if (size > maxFileBytes) throw AppError.SecurityRejection("File exceeds the configured size limit")
        val safeName = FileNameSanitizer.sanitize(fileName)

        link.send(Frame(Abtp.Type.TRANSFER_REQUEST, Payloads.writeTransferRequest(transferId, safeName, size, sha256Hex)))
        val response = link.receive()
        when (response.type) {
            Abtp.Type.TRANSFER_ACCEPT -> Unit
            Abtp.Type.TRANSFER_REJECT -> throw AppError.TransferRejected("Receiver declined the transfer.")
            Abtp.Type.CANCEL -> throw AppError.Cancelled()
            else -> throw AppError.ProtocolViolation("Unexpected reply to transfer request")
        }

        link.send(Frame(Abtp.Type.FILE_START, Payloads.writeFileStart(transferId, safeName, size, sha256Hex)))

        val chunk = ByteArray(TransferLimits.CHUNK_SIZE)
        var sent = 0L
        val digest = Hashing.newSha256Digester()
        open().use { stream ->
            while (sent < size) {
                if (isCancelled()) {
                    runCatching { link.send(Frame(Abtp.Type.CANCEL, Payloads.writeCancel(transferId, "Sender cancelled"))) }
                    throw AppError.Cancelled()
                }
                coroutineContext.ensureActive()
                val want = minOf(chunk.size.toLong(), size - sent).toInt()
                val n = stream.read(chunk, 0, want)
                if (n < 0) throw AppError.SizeMismatch()
                if (n == 0) continue
                digest.update(chunk, 0, n)
                link.send(Frame(Abtp.Type.CHUNK, Payloads.writeChunk(transferId, chunk.copyOf(n))))
                sent += n
                onProgress(sent)
            }
        }

        val localHash = Hashing.toHex(digest.digest())
        link.send(Frame(Abtp.Type.FILE_COMPLETE, Payloads.writeFileComplete(transferId, localHash)))

        // Await receiver acknowledgement (receiver echoes FILE_COMPLETE after verifying).
        val ack = link.receive()
        when (ack.type) {
            Abtp.Type.FILE_COMPLETE -> {
                val (ackId, receiverHash) = Payloads.readFileComplete(PayloadReader(ack.payload))
                if (ackId != transferId) throw AppError.ProtocolViolation("Ack transfer id mismatch")
                if (sha256Hex.isNotEmpty() && receiverHash.isNotEmpty() && !receiverHash.equals(localHash, ignoreCase = true)) {
                    throw AppError.HashMismatch()
                }
            }
            Abtp.Type.CANCEL, Abtp.Type.ERROR -> throw AppError.TransferRejected("Receiver reported a failure.")
            else -> throw AppError.ProtocolViolation("Unexpected frame after completion")
        }
    }
}

/**
 * Receiver: approves an incoming transfer, streams it into a temp file, verifies hash and size,
 * then finalizes via a caller-supplied commit function.
 */
class FileReceiver(private val link: TcpLink, private val maxFileBytes: Long = TransferLimits.DEFAULT_MAX_FILE_BYTES) {

    data class IncomingRequest(val transferId: String, val fileName: String, val size: Long, val sha256Hex: String)

    suspend fun receiveFile(
        openOutput: (safeName: String, size: Long) -> OutputStream,
        commit: (safeName: String, sha256Hex: String) -> String?,
        onProgress: (bytesReceived: Long) -> Unit,
        isCancelled: () -> Boolean
    ): IncomingRequest = withContext(Dispatchers.IO) {
        val reqFrame = link.receive()
        if (reqFrame.type != Abtp.Type.TRANSFER_REQUEST) {
            throw AppError.ProtocolViolation("Expected TRANSFER_REQUEST")
        }
        val req = Payloads.readTransferRequest(PayloadReader(reqFrame.payload))
        val safeName = FileNameSanitizer.sanitize(req.fileName)
        if (req.size > maxFileBytes) {
            link.send(Frame(Abtp.Type.TRANSFER_REJECT, Payloads.writeTransferId(req.transferId)))
            throw AppError.SecurityRejection("Transfer exceeds the configured size limit")
        }

        // Hand the request to the caller for user approval (or auto-accept policy).
        val approved = approve(req, safeName)
        if (!approved) {
            link.send(Frame(Abtp.Type.TRANSFER_REJECT, Payloads.writeTransferId(req.transferId)))
            throw AppError.TransferRejected("You declined the transfer.")
        }
        link.send(Frame(Abtp.Type.TRANSFER_ACCEPT, Payloads.writeTransferId(req.transferId)))

        val start = link.receive()
        if (start.type != Abtp.Type.FILE_START) throw AppError.ProtocolViolation("Expected FILE_START")
        val meta = Payloads.readFileStart(PayloadReader(start.payload))
        if (meta.transferId != req.transferId || meta.size != req.size) {
            throw AppError.ProtocolViolation("Metadata mismatch between request and start")
        }

        val digest = Hashing.newSha256Digester()
        var received = 0L
        try {
            openOutput(safeName, req.size).use { out ->
                while (received < req.size) {
                    if (isCancelled()) {
                        runCatching { link.send(Frame(Abtp.Type.CANCEL, Payloads.writeCancel(req.transferId, "Receiver cancelled"))) }
                        throw AppError.Cancelled()
                    }
                    coroutineContext.ensureActive()
                    val frame = link.receive()
                    when (frame.type) {
                        Abtp.Type.CHUNK -> {
                            val (id, data) = Payloads.readChunk(PayloadReader(frame.payload), TransferLimits.CHUNK_SIZE)
                            if (id != req.transferId) throw AppError.ProtocolViolation("Chunk transfer id mismatch")
                            if (received + data.size > req.size) throw AppError.SizeMismatch()
                            out.write(data)
                            digest.update(data)
                            received += data.size
                            onProgress(received)
                        }
                        Abtp.Type.CANCEL -> throw AppError.Cancelled()
                        else -> throw AppError.ProtocolViolation("Unexpected frame during data phase")
                    }
                }
            }
        } catch (e: Exception) {
            // Caller cleans up the temp file via onAbort callback contract; rethrow.
            throw e
        }

        if (received != req.size) throw AppError.SizeMismatch()

        val finalFrame = link.receive()
        if (finalFrame.type != Abtp.Type.FILE_COMPLETE) throw AppError.ProtocolViolation("Expected FILE_COMPLETE")
        val (_, senderHash) = Payloads.readFileComplete(PayloadReader(finalFrame.payload))
        val localHash = Hashing.toHex(digest.digest())
        if (senderHash.isNotEmpty() && !localHash.equals(senderHash, ignoreCase = true)) {
            throw AppError.HashMismatch()
        }
        val committedUri = commit(safeName, localHash)
            ?: throw AppError.StorageError("Could not finalize the received file.")
        link.send(Frame(Abtp.Type.FILE_COMPLETE, Payloads.writeFileComplete(req.transferId, localHash)))
        req.copy(sha256Hex = localHash)
    }

    /** Overridable approval hook; default auto-approves (UI layer installs an interactive gate). */
    open suspend fun approve(req: IncomingRequest, safeName: String): Boolean = true
}

private suspend fun <T> kotlinx.coroutines.suspendCancellableCoroutine<T>.bind() { /* helper placeholder */ }
