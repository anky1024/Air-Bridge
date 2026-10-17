package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class OutgoingFile(
    val transferId: UUID = UUID.randomUUID(),
    val fileName: String,
    val sizeBytes: Long,
    val expectedSha256: ByteArray,
    val openInput: suspend () -> InputStream
)

data class IncomingSink(
    val openOutput: suspend (safeFileName: String) -> OutputStream,
    val finalizeOutput: suspend () -> Unit,
    val cleanupPartial: suspend () -> Unit
)

data class TransferProgress(
    val transferId: UUID,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val completed: Boolean = false
)

class TransferProtocolEngine(
    private val codec: FrameCodec,
    private val maxTransferBytes: Long = AirBridgeProtocol.DEFAULT_MAX_TRANSFER_BYTES,
    private val onProgress: suspend (TransferProgress) -> Unit = {}
) {
    suspend fun sendFile(file: OutgoingFile) = withContext(Dispatchers.IO) {
        val safeName = SecurityValidators.safeDisplayFileName(file.fileName)
        SecurityValidators.validateTransferSize(file.sizeBytes, maxTransferBytes)
        codec.write(Frame(MessageType.TRANSFER_REQUEST, BinaryPayloads.transferRequest(file.transferId, safeName, file.sizeBytes, file.expectedSha256)))
        val responseFrame = codec.read()
        if (responseFrame.type != MessageType.TRANSFER_RESPONSE) throw AppError.ProtocolViolation("Expected transfer response.")
        val response = BinaryPayloads.parseTransferResponse(responseFrame.payload)
        if (response.transferId != file.transferId) throw AppError.ProtocolViolation("Transfer response identifier mismatch.")
        if (!response.accepted) throw AppError.TransferRejected(response.reason.ifBlank { "Receiver rejected transfer." })

        codec.write(Frame(MessageType.TRANSFER_START, BinaryPayloads.transferResponse(file.transferId, true, "start")))
        var offset = 0L
        file.openInput().use { input ->
            val buffer = ByteArray(AirBridgeProtocol.MAX_CHUNK_BYTES)
            while (offset < file.sizeBytes) {
                coroutineContext.ensureActive()
                val maxRead = minOf(buffer.size.toLong(), file.sizeBytes - offset).toInt()
                val read = input.read(buffer, 0, maxRead)
                if (read < 0) throw AppError.SizeMismatch()
                codec.write(Frame(MessageType.DATA_CHUNK, BinaryPayloads.dataChunk(file.transferId, offset, buffer, read)))
                offset += read
                onProgress(TransferProgress(file.transferId, offset, file.sizeBytes))
            }
        }
        codec.write(Frame(MessageType.TRANSFER_COMPLETE, BinaryPayloads.completion(file.transferId, offset, file.expectedSha256)))
        val ack = codec.read()
        if (ack.type != MessageType.PROGRESS_ACK) throw AppError.ProtocolViolation("Expected receiver completion acknowledgement.")
        onProgress(TransferProgress(file.transferId, offset, file.sizeBytes, completed = true))
    }

    suspend fun receiveOne(approve: suspend (TransferRequestPayload) -> Boolean, sink: IncomingSink): TransferRequestPayload = withContext(Dispatchers.IO) {
        val requestFrame = codec.read()
        if (requestFrame.type != MessageType.TRANSFER_REQUEST) throw AppError.ProtocolViolation("Expected transfer request.")
        val request = BinaryPayloads.parseTransferRequest(requestFrame.payload)
        SecurityValidators.validateTransferSize(request.size, maxTransferBytes)
        if (!approve(request)) {
            codec.write(Frame(MessageType.TRANSFER_RESPONSE, BinaryPayloads.transferResponse(request.transferId, false, "Rejected by receiver.")))
            throw AppError.TransferRejected("Incoming transfer rejected.")
        }
        codec.write(Frame(MessageType.TRANSFER_RESPONSE, BinaryPayloads.transferResponse(request.transferId, true, "Accepted.")))

        val start = codec.read()
        if (start.type != MessageType.TRANSFER_START) throw AppError.ProtocolViolation("Expected transfer start.")

        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            sink.openOutput(request.fileName).use { output ->
                while (received < request.size) {
                    coroutineContext.ensureActive()
                    val frame = codec.read()
                    if (frame.type == MessageType.CANCEL) throw AppError.Cancelled()
                    if (frame.type != MessageType.DATA_CHUNK) throw AppError.ProtocolViolation("Expected data chunk.")
                    val chunk = BinaryPayloads.parseDataChunk(frame.payload)
                    if (chunk.transferId != request.transferId || chunk.offset != received) throw AppError.ProtocolViolation("Unexpected chunk order.")
                    if (received + chunk.data.size > request.size) throw AppError.ProtocolViolation("Received too many bytes.")
                    output.write(chunk.data)
                    digest.update(chunk.data)
                    received += chunk.data.size
                    onProgress(TransferProgress(request.transferId, received, request.size))
                }
                output.flush()
            }
            val complete = codec.read()
            if (complete.type != MessageType.TRANSFER_COMPLETE) throw AppError.ProtocolViolation("Expected transfer completion.")
            if (received != request.size) throw AppError.SizeMismatch()
            val actualHash = digest.digest()
            if (!SecurityValidators.constantTimeEquals(actualHash, request.sha256)) throw AppError.HashMismatch()
            sink.finalizeOutput()
            codec.write(Frame(MessageType.PROGRESS_ACK, BinaryPayloads.completion(request.transferId, received, actualHash)))
            onProgress(TransferProgress(request.transferId, received, request.size, completed = true))
            request
        } catch (t: Throwable) {
            sink.cleanupPartial()
            throw t
        }
    }
}
