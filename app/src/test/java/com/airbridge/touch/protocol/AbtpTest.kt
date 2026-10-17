package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Tests for the canonical protocol in AirBridgeProtocol.kt. */
class AbtpTest {

    private fun codecOut(): Pair<FrameCodec, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        val c = FrameCodec(ByteArrayInputStream(ByteArray(0)), out)
        return c to out
    }

    @Test
    fun frameRoundTrip() {
        val (codecOut, out) = codecOut()
        val payload = BinaryPayloads.hello("Pixel 10a", UUID.randomUUID())
        codecOut.write(Frame(MessageType.HELLO, payload))
        val codecIn = FrameCodec(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = codecIn.read()
        assertEquals(MessageType.HELLO, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun badMagicRejected() {
        val (codecOut, out) = codecOut()
        codecOut.write(Frame(MessageType.HELLO, BinaryPayloads.hello("a", UUID.randomUUID())))
        val bytes = out.toByteArray()
        bytes[0] = 0x00
        val codecIn = FrameCodec(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        assertThrows(AppError.ProtocolViolation::class.java) { codecIn.read() }
    }

    @Test
    fun invalidFrameLengthRejected() {
        val (codecOut, out) = codecOut()
        codecOut.write(Frame(MessageType.HELLO, BinaryPayloads.hello("a", UUID.randomUUID())))
        val bytes = out.toByteArray()
        bytes[8] = 0x7F
        val codecIn = FrameCodec(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        assertThrows(AppError.ProtocolViolation::class.java) { codecIn.read() }
    }

    @Test
    fun unknownMessageTypeRejected() {
        val (codecOut, out) = codecOut()
        codecOut.write(Frame(MessageType.CLOSE, ByteArray(0)))
        val bytes = out.toByteArray()
        bytes[4] = 0x7F
        bytes[5] = 0x7F
        val codecIn = FrameCodec(ByteArrayInputStream(bytes), ByteArrayOutputStream())
        assertThrows(AppError.ProtocolViolation::class.java) { codecIn.read() }
    }

    @Test
    fun transferRequestRoundTrip() {
        val id = UUID.randomUUID()
        val sha = ByteArray(32) { it.toByte() }
        val bytes = BinaryPayloads.transferRequest(id, "photo.jpg", 1234567L, sha)
        val req = BinaryPayloads.parseTransferRequest(bytes)
        assertEquals(id, req.transferId)
        assertEquals("photo.jpg", req.fileName)
        assertEquals(1234567L, req.size)
        assertArrayEquals(sha, req.sha256)
    }

    @Test
    fun oversizedTransferRequestRejected() {
        val bytes = BinaryPayloads.transferRequest(UUID.randomUUID(), "f.bin", Long.MAX_VALUE / 2, ByteArray(32))
        assertThrows(AppError.SecurityRejection::class.java) {
            BinaryPayloads.parseTransferRequest(bytes)
        }
    }

    @Test
    fun dataChunkRoundTrip() {
        val id = UUID.randomUUID()
        val data = ByteArray(1000) { (it % 251).toByte() }
        val bytes = BinaryPayloads.dataChunk(id, 42L, data)
        val chunk = BinaryPayloads.parseDataChunk(bytes)
        assertEquals(id, chunk.transferId)
        assertEquals(42L, chunk.offset)
        assertArrayEquals(data, chunk.data)
    }

    @Test
    fun oversizedChunkLengthRejected() {
        val buffer = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putLong(UUID.randomUUID().mostSignificantBits)
        buffer.putLong(UUID.randomUUID().leastSignificantBits)
        buffer.putLong(0L)
        buffer.putInt(AirBridgeProtocol.MAX_CHUNK_BYTES + 1)
        val bytes = buffer.array().copyOf(buffer.position())
        assertThrows(AppError.ProtocolViolation::class.java) {
            BinaryPayloads.parseDataChunk(bytes)
        }
    }

    @Test
    fun transferResponseRoundTrip() {
        val id = UUID.randomUUID()
        val bytes = BinaryPayloads.transferResponse(id, true, "ok")
        val resp = BinaryPayloads.parseTransferResponse(bytes)
        assertEquals(id, resp.transferId)
        assertEquals(true, resp.accepted)
        assertEquals("ok", resp.reason)
    }

    @Test
    fun emptyPayloadFrameWorks() {
        val (codecOut, out) = codecOut()
        codecOut.write(Frame(MessageType.CLOSE, ByteArray(0)))
        val codecIn = FrameCodec(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = codecIn.read()
        assertEquals(MessageType.CLOSE, frame.type)
        assertEquals(0, frame.payload.size)
    }
}
