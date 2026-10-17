package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import com.airbridge.touch.core.TransferLimits
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * AirBridgeTransferProtocol (ABTP) v1 wire format.
 *
 * Frame: [u8 magic=0xAB][u8 version=1][u8 type][u8 reserved=0][u32 payloadLen BE][payload]
 * All multi-byte integers are big-endian. Strings are UTF-8, length-prefixed with u16.
 * Max payload = TransferLimits.MAX_FRAME_BYTES.
 */
object Abtp {
    const val MAGIC: Byte = 0xAB.toByte()
    const val VERSION: UByte = 1u
    const val HEADER_SIZE = 9

    enum class Type(val id: UByte) {
        HELLO(1u),
        HELLO_ACK(2u),
        TRANSFER_REQUEST(3u),
        TRANSFER_ACCEPT(4u),
        TRANSFER_REJECT(5u),
        FILE_START(6u),
        CHUNK(7u),
        PROGRESS_ACK(8u),
        FILE_COMPLETE(9u),
        CANCEL(10u),
        ERROR(11u),
        CLOSE(12u);

        companion object {
            fun from(id: UByte): Type? = entries.firstOrNull { it.id == id }
        }
    }
}

data class Frame(val type: Abtp.Type, val payload: ByteArray) {
    init {
        if (payload.size > TransferLimits.MAX_FRAME_BYTES) {
            throw AppError.ProtocolViolation("Frame payload exceeds limit (${payload.size})")
        }
    }
}

object FrameCodec {
    fun encode(frame: Frame): ByteArray {
        val out = ByteArray(Abtp.HEADER_SIZE + frame.payload.size)
        out[0] = Abtp.MAGIC
        out[1] = Abtp.VERSION.toByte()
        out[2] = frame.type.id.toByte()
        out[3] = 0
        val len = frame.payload.size
        out[4] = ((len ushr 24) and 0xFF).toByte()
        out[5] = ((len ushr 16) and 0xFF).toByte()
        out[6] = ((len ushr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()
        out[8] = 0
        frame.payload.copyInto(out, Abtp.HEADER_SIZE)
        return out
    }

    /** Reads exactly one frame; throws AppError.ProtocolViolation on malformed input. */
    fun decode(input: DataInputStream): Frame {
        val header = ByteArray(Abtp.HEADER_SIZE)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            throw AppError.SocketFailure("Peer closed the connection.")
        }
        if (header[0] != Abtp.MAGIC) throw AppError.ProtocolViolation("Bad frame magic")
        if (header[1].toUByte() != Abtp.VERSION) throw AppError.ProtocolViolation("Unsupported protocol version")
        val type = Abtp.Type.from(header[2].toUByte())
            ?: throw AppError.ProtocolViolation("Unknown frame type ${header[2]}")
        val len = ((header[4].toInt() and 0xFF) shl 24) or ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        if (len < 0 || len > TransferLimits.MAX_FRAME_BYTES) {
            throw AppError.ProtocolViolation("Invalid frame length $len")
        }
        val payload = ByteArray(len)
        input.readFully(payload)
        return Frame(type, payload)
    }

    fun write(output: DataOutputStream, frame: Frame) {
        output.write(encode(frame))
        output.flush()
    }
}

/** Strict payload reader; every read is bounds-checked. */
class PayloadReader(private val data: ByteArray) {
    private var pos = 0
    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw AppError.ProtocolViolation("Truncated payload")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2; return v }
    fun u32(): Long {
        need(4); var v = 0L
        repeat(4) { v = (v shl 8) or (data[pos + it].toLong() and 0xFF) }
        pos += 4; return v
    }
    fun u64(): Long {
        need(8); var v = 0L
        repeat(8) { v = (v shl 8) or (data[pos + it].toLong() and 0xFF) }
        pos += 8; return v
    }

    fun string(maxBytes: Int): String {
        val len = u16()
        if (len > maxBytes) throw AppError.ProtocolViolation("String field too large ($len)")
        need(len)
        val s = String(data, pos, len, StandardCharsets.UTF_8)
        pos += len
        return s
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun remainder(): ByteArray = bytes(remaining)
}

/** Payload writer with fixed-size prefixes; throws on overflow. */
class PayloadWriter(initial: Int = 256) {
    private val buf = java.io.ByteArrayOutputStream(initial)
    private val out = java.io.DataOutputStream(buf)

    fun u8(v: Int): PayloadWriter { out.writeByte(v); return this }
    fun u16(v: Int): PayloadWriter { out.writeShort(v); return this }
    fun u32(v: Long): PayloadWriter { out.writeInt(v.toInt()); return this }
    fun u64(v: Long): PayloadWriter { out.writeLong(v); return this }

    fun string(s: String, maxBytes: Int): PayloadWriter {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        require(b.size <= maxBytes && b.size <= 0xFFFF) { "String exceeds $maxBytes bytes" }
        out.writeShort(b.size)
        out.write(b)
        return this
    }

    fun bytes(b: ByteArray): PayloadWriter { out.write(b); return this }

    fun toByteArray(): ByteArray = buf.toByteArray()
}

object Payloads {
    fun writeHello(deviceId: String, deviceName: String): ByteArray =
        PayloadWriter().string(deviceId, 64).string(deviceName, TransferLimits.MAX_METADATA_NAME_BYTES).toByteArray()

    fun readHello(p: PayloadReader): Pair<String, String> =
        p.string(64) to p.string(TransferLimits.MAX_METADATA_NAME_BYTES)

    fun writeHelloAck(sessionId: String): ByteArray =
        PayloadWriter().string(sessionId, 64).toByteArray()

    fun readHelloAck(p: PayloadReader): String = p.string(64)

    data class TransferRequest(val transferId: String, val fileName: String, val size: Long, val sha256Hex: String)

    fun writeTransferRequest(transferId: String, fileName: String, size: Long, sha256Hex: String): ByteArray =
        PayloadWriter()
            .string(transferId, 64)
            .string(fileName, TransferLimits.MAX_METADATA_NAME_BYTES)
            .u64(size)
            .string(sha256Hex, 64)
            .toByteArray()

    fun readTransferRequest(p: PayloadReader): TransferRequest {
        val id = p.string(64)
        val name = p.string(TransferLimits.MAX_METADATA_NAME_BYTES)
        val size = p.u64()
        val sha = p.string(64)
        if (size < 0 || size > TransferLimits.DEFAULT_MAX_FILE_BYTES * 8) {
            throw AppError.ProtocolViolation("Unreasonable announced size $size")
        }
        return TransferRequest(id, name, size, sha)
    }

    fun writeTransferId(transferId: String): ByteArray =
        PayloadWriter().string(transferId, 64).toByteArray()

    fun readTransferId(p: PayloadReader): String = p.string(64)

    fun writeFileStart(transferId: String, fileName: String, size: Long, sha256Hex: String): ByteArray =
        writeTransferRequest(transferId, fileName, size, sha256Hex)

    fun readFileStart(p: PayloadReader): TransferRequest = readTransferRequest(p)

    fun writeChunk(transferId: String, chunk: ByteArray): ByteArray =
        PayloadWriter(8 + chunk.size).string(transferId, 64).bytes(chunk).toByteArray()

    fun readChunk(p: PayloadReader, maxChunk: Int): Pair<String, ByteArray> {
        val id = p.string(64)
        val n = p.remaining
        if (n > maxChunk) throw AppError.ProtocolViolation("Chunk too large")
        return id to p.bytes(n)
    }

    fun writeProgressAck(transferId: String, bytesReceived: Long): ByteArray =
        PayloadWriter().string(transferId, 64).u64(bytesReceived).toByteArray()

    fun readProgressAck(p: PayloadReader): Pair<String, Long> {
        val id = p.string(64)
        return id to p.u64()
    }

    fun writeFileComplete(transferId: String, sha256Hex: String): ByteArray =
        PayloadWriter().string(transferId, 64).string(sha256Hex, 64).toByteArray()

    fun readFileComplete(p: PayloadReader): Pair<String, String> {
        val id = p.string(64)
        return id to p.string(64)
    }

    fun writeCancel(transferId: String, reason: String): ByteArray =
        PayloadWriter().string(transferId, 64).string(reason.take(120), 120).toByteArray()

    fun readCancel(p: PayloadReader): Pair<String, String> {
        val id = p.string(64)
        return id to p.string(120)
    }

    fun writeError(transferId: String, safeMessage: String): ByteArray = writeCancel(transferId, safeMessage)

    fun readError(p: PayloadReader): Pair<String, String> = readCancel(p)
}
