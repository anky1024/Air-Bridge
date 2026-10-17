package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

object AirBridgeProtocol {
    const val MAGIC: Int = 0x41425431 // ABT1
    const val VERSION: UShort = 1u
    const val HEADER_BYTES: Int = 12
    const val MAX_FRAME_BYTES: Int = 1024 * 1024
    const val MAX_METADATA_BYTES: Int = 16 * 1024
    const val MAX_FILE_NAME_BYTES: Int = 180
    const val MAX_CHUNK_BYTES: Int = 256 * 1024
    const val DEFAULT_MAX_TRANSFER_BYTES: Long = 2L * 1024L * 1024L * 1024L
    const val SOCKET_TIMEOUT_MS: Int = 30_000
}

enum class MessageType(val code: UShort) {
    HELLO(1u),
    HELLO_ACK(2u),
    TRANSFER_REQUEST(10u),
    TRANSFER_RESPONSE(11u),
    FILE_METADATA(12u),
    TRANSFER_START(13u),
    DATA_CHUNK(14u),
    PROGRESS_ACK(15u),
    TRANSFER_COMPLETE(16u),
    CANCEL(17u),
    ERROR(18u),
    CLOSE(19u);

    companion object {
        fun fromCode(code: UShort): MessageType = entries.firstOrNull { it.code == code }
            ?: throw AppError.ProtocolViolation("Unknown protocol message type.")
    }
}

data class Frame(val type: MessageType, val payload: ByteArray) {
    init {
        if (payload.size > AirBridgeProtocol.MAX_FRAME_BYTES) {
            throw AppError.ProtocolViolation("Frame payload exceeds maximum size.")
        }
    }
}

class FrameCodec(
    private val input: InputStream,
    private val output: OutputStream
) {
    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)

    @Synchronized
    fun write(frame: Frame) {
        val length = frame.payload.size
        if (length > AirBridgeProtocol.MAX_FRAME_BYTES) throw AppError.ProtocolViolation("Frame too large.")
        val header = ByteBuffer.allocate(AirBridgeProtocol.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        header.putInt(AirBridgeProtocol.MAGIC)
        header.putShort(frame.type.code.toShort())
        header.putShort(AirBridgeProtocol.VERSION.toShort())
        header.putInt(length)
        dout.write(header.array())
        dout.write(frame.payload)
        dout.flush()
    }

    fun read(): Frame {
        val headerBytes = ByteArray(AirBridgeProtocol.HEADER_BYTES)
        try {
            din.readFully(headerBytes)
        } catch (e: EOFException) {
            throw AppError.ProtocolViolation("Connection closed during frame header.")
        }
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = header.int
        if (magic != AirBridgeProtocol.MAGIC) throw AppError.ProtocolViolation("Invalid protocol magic.")
        val type = MessageType.fromCode(header.short.toUShort())
        val version = header.short.toUShort()
        if (version > AirBridgeProtocol.VERSION) throw AppError.ProtocolViolation("Unsupported protocol version.")
        val length = header.int
        if (length < 0 || length > AirBridgeProtocol.MAX_FRAME_BYTES) {
            throw AppError.ProtocolViolation("Invalid frame length.")
        }
        val payload = ByteArray(length)
        din.readFully(payload)
        return Frame(type, payload)
    }
}

object BinaryPayloads {
    fun hello(deviceName: String, sessionId: UUID): ByteArray = buildPayload {
        putUuid(sessionId)
        putString(deviceName, 120)
        putShort(AirBridgeProtocol.VERSION.toShort())
    }

    fun transferRequest(transferId: UUID, fileName: String, size: Long, sha256: ByteArray): ByteArray {
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        return buildPayload {
            putUuid(transferId)
            putString(fileName, AirBridgeProtocol.MAX_FILE_NAME_BYTES)
            putLong(size)
            put(sha256)
        }
    }

    fun transferResponse(transferId: UUID, accepted: Boolean, reason: String): ByteArray = buildPayload {
        putUuid(transferId)
        put(if (accepted) 1 else 0)
        putString(reason, 240)
    }

    fun dataChunk(transferId: UUID, offset: Long, data: ByteArray, len: Int = data.size): ByteArray {
        if (len < 0 || len > AirBridgeProtocol.MAX_CHUNK_BYTES) throw AppError.ProtocolViolation("Invalid chunk length.")
        return buildPayload {
            putUuid(transferId)
            putLong(offset)
            putInt(len)
            put(data, 0, len)
        }
    }

    fun completion(transferId: UUID, totalBytes: Long, sha256: ByteArray): ByteArray {
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        return buildPayload {
            putUuid(transferId)
            putLong(totalBytes)
            put(sha256)
        }
    }

    fun error(code: String, safeMessage: String): ByteArray = buildPayload {
        putString(code, 48)
        putString(safeMessage, 240)
    }

    fun parseTransferRequest(payload: ByteArray): TransferRequestPayload {
        val b = payload.reader()
        val id = b.readUuid()
        val name = SecurityValidators.safeDisplayFileName(b.readString(AirBridgeProtocol.MAX_FILE_NAME_BYTES))
        val size = b.readLong()
        if (size < 0 || size > AirBridgeProtocol.DEFAULT_MAX_TRANSFER_BYTES) throw AppError.SecurityRejection("Transfer size is not allowed.")
        val hash = b.readBytes(32)
        b.requireEnd()
        return TransferRequestPayload(id, name, size, hash)
    }

    fun parseTransferResponse(payload: ByteArray): TransferResponsePayload {
        val b = payload.reader()
        val id = b.readUuid()
        val accepted = b.readByte().toInt() != 0
        val reason = b.readString(240)
        b.requireEnd()
        return TransferResponsePayload(id, accepted, reason)
    }

    fun parseDataChunk(payload: ByteArray): DataChunkPayload {
        val b = payload.reader()
        val id = b.readUuid()
        val offset = b.readLong()
        val len = b.readInt()
        if (offset < 0 || len < 0 || len > AirBridgeProtocol.MAX_CHUNK_BYTES) throw AppError.ProtocolViolation("Invalid chunk metadata.")
        val data = b.readBytes(len)
        b.requireEnd()
        return DataChunkPayload(id, offset, data)
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

data class TransferRequestPayload(val transferId: UUID, val fileName: String, val size: Long, val sha256: ByteArray)
data class TransferResponsePayload(val transferId: UUID, val accepted: Boolean, val reason: String)
data class DataChunkPayload(val transferId: UUID, val offset: Long, val data: ByteArray)

private fun buildPayload(block: ByteBuffer.() -> Unit): ByteArray {
    val buffer = ByteBuffer.allocate(AirBridgeProtocol.MAX_FRAME_BYTES).order(ByteOrder.BIG_ENDIAN)
    buffer.block()
    return buffer.array().copyOf(buffer.position())
}

private fun ByteBuffer.putUuid(id: UUID) {
    putLong(id.mostSignificantBits)
    putLong(id.leastSignificantBits)
}

private fun ByteBuffer.putString(value: String, maxUtf8Bytes: Int) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size > maxUtf8Bytes) throw AppError.ProtocolViolation("String field exceeds maximum length.")
    putShort(bytes.size.toShort())
    put(bytes)
}

private fun ByteArray.reader(): PayloadReader = PayloadReader(ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN))

private class PayloadReader(private val buffer: ByteBuffer) {
    fun readUuid(): UUID = UUID(buffer.long, buffer.long)
    fun readLong(): Long = buffer.long
    fun readInt(): Int = buffer.int
    fun readByte(): Byte = buffer.get()
    fun readBytes(len: Int): ByteArray {
        if (len < 0 || len > buffer.remaining()) throw AppError.ProtocolViolation("Truncated payload.")
        val out = ByteArray(len)
        buffer.get(out)
        return out
    }
    fun readString(maxUtf8Bytes: Int): String {
        val len = buffer.short.toInt() and 0xffff
        if (len > maxUtf8Bytes || len > buffer.remaining()) throw AppError.ProtocolViolation("Invalid string length.")
        return readBytes(len).toString(Charsets.UTF_8)
    }
    fun requireEnd() {
        if (buffer.hasRemaining()) throw AppError.ProtocolViolation("Unexpected trailing payload bytes.")
    }
}
