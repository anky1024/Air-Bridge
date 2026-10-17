package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class AbtpTest {

    @Test
    fun `frame encode-decode round trip`() {
        val payload = Payloads.writeHello("device-1", "Pixel 10a")
        val frame = Frame(Abtp.Type.HELLO, payload)
        val bytes = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(Abtp.Type.HELLO, decoded.type)
        assertEquals("device-1", Payloads.readHello(PayloadReader(decoded.payload)).first)
        assertEquals("Pixel 10a", Payloads.readHello(PayloadReader(decoded.payload)).second)
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = FrameCodec.encode(Frame(Abtp.Type.HELLO, Payloads.writeHello("a", "b")))
        bytes[0] = 0x00
        assertThrows(AppError.ProtocolViolation::class.java) {
            FrameCodec.decode(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `invalid frame length is rejected`() {
        val bytes = FrameCodec.encode(Frame(Abtp.Type.HELLO, Payloads.writeHello("a", "b")))
        // Corrupt the length field to an absurd value.
        bytes[4] = 0x7F.toByte()
        assertThrows(AppError.ProtocolViolation::class.java) {
            FrameCodec.decode(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `unknown frame type is rejected`() {
        val bytes = FrameCodec.encode(Frame(Abtp.Type.HELLO, Payloads.writeHello("a", "b")))
        bytes[2] = 0x7F
        assertThrows(AppError.ProtocolViolation::class.java) {
            FrameCodec.decode(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `truncated payload string throws`() {
        val writer = PayloadWriter().string("id", 64)
        val p = PayloadReader(writer.toByteArray())
        // Corrupt: claim a very long string length.
        val bad = byteArrayOf(0x7F, 0x7F) + writer.toByteArray().drop(2).toByteArray()
        assertThrows(AppError.ProtocolViolation::class.java) {
            PayloadReader(bad).string(64)
        }
    }

    @Test
    fun `transfer request round trip with all fields`() {
        val bytes = Payloads.writeTransferRequest("tid-1", "photo.jpg", 1234567, "abc123")
        val req = Payloads.readTransferRequest(PayloadReader(bytes))
        assertEquals("tid-1", req.transferId)
        assertEquals("photo.jpg", req.fileName)
        assertEquals(1234567L, req.size)
        assertEquals("abc123", req.sha256Hex)
    }

    @Test
    fun `oversized announced size is rejected`() {
        val bytes = Payloads.writeTransferRequest("t", "f.bin", Long.MAX_VALUE / 2, "")
        assertThrows(AppError.ProtocolViolation::class.java) {
            Payloads.readTransferRequest(PayloadReader(bytes))
        }
    }

    @Test
    fun `chunk round trip`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val bytes = Payloads.writeChunk("t-1", data)
        val (id, out) = Payloads.readChunk(PayloadReader(bytes), 4096)
        assertEquals("t-1", id)
        assertTrue(data.contentEquals(out))
    }

    @Test
    fun `progress ack round trip`() {
        val bytes = Payloads.writeProgressAck("t-1", 987654321L)
        val (id, n) = Payloads.readProgressAck(PayloadReader(bytes))
        assertEquals("t-1", id)
        assertEquals(987654321L, n)
    }

    @Test
    fun `cancel carries reason`() {
        val bytes = Payloads.writeCancel("t-1", "User cancelled")
        val (id, reason) = Payloads.readCancel(PayloadReader(bytes))
        assertEquals("t-1", id)
        assertEquals("User cancelled", reason)
    }

    @Test
    fun `empty payload frame works`() {
        val bytes = FrameCodec.encode(Frame(Abtp.Type.CLOSE, ByteArray(0)))
        val f = FrameCodec.decode(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(Abtp.Type.CLOSE, f.type)
        assertEquals(0, f.payload.size)
    }
}
