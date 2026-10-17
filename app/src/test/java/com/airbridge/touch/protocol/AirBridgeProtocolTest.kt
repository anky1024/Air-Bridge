package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AirBridgeProtocolTest {
    @Test
    fun frameRoundTripHandlesPartialIndependentPayload() {
        val out = ByteArrayOutputStream()
        val writer = FrameCodec(ByteArrayInputStream(ByteArray(0)), out)
        writer.write(Frame(MessageType.HELLO, byteArrayOf(1, 2, 3, 4)))

        val reader = FrameCodec(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = reader.read()

        assertEquals(MessageType.HELLO, frame.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.payload)
    }

    @Test(expected = AppError.ProtocolViolation::class)
    fun invalidFrameLengthIsRejected() {
        val header = ByteBuffer.allocate(AirBridgeProtocol.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(AirBridgeProtocol.MAGIC)
            .putShort(MessageType.DATA_CHUNK.code.toShort())
            .putShort(AirBridgeProtocol.VERSION.toShort())
            .putInt(AirBridgeProtocol.MAX_FRAME_BYTES + 1)
            .array()
        FrameCodec(ByteArrayInputStream(header), ByteArrayOutputStream()).read()
    }

    @Test(expected = AppError.SecurityRejection::class)
    fun pathTraversalFileNameIsRejected() {
        SecurityValidators.safeDisplayFileName("../secret.txt")
    }

    @Test
    fun unsafeCharactersAreSanitized() {
        assertEquals("my_bad_name.pdf", SecurityValidators.safeDisplayFileName("my/bad:name.pdf"))
    }

    @Test
    fun transferRequestRoundTripSupportsEmptyFiles() {
        val id = UUID.randomUUID()
        val hash = BinaryPayloads.sha256(ByteArray(0))
        val payload = BinaryPayloads.transferRequest(id, "empty.txt", 0, hash)
        val parsed = BinaryPayloads.parseTransferRequest(payload)

        assertEquals(id, parsed.transferId)
        assertEquals("empty.txt", parsed.fileName)
        assertEquals(0L, parsed.size)
        assertArrayEquals(hash, parsed.sha256)
    }

    @Test(expected = AppError.SecurityRejection::class)
    fun oversizedTransferRequestIsRejected() {
        val id = UUID.randomUUID()
        val hash = BinaryPayloads.sha256(byteArrayOf(1))
        val payload = BinaryPayloads.transferRequest(id, "huge.bin", AirBridgeProtocol.DEFAULT_MAX_TRANSFER_BYTES + 1, hash)
        BinaryPayloads.parseTransferRequest(payload)
    }

    @Test
    fun constantTimeEqualsWorksForHashValidation() {
        val a = BinaryPayloads.sha256("abc".toByteArray())
        val b = BinaryPayloads.sha256("abc".toByteArray())
        val c = BinaryPayloads.sha256("xyz".toByteArray())
        assertTrue(SecurityValidators.constantTimeEquals(a, b))
        assertTrue(!SecurityValidators.constantTimeEquals(a, c))
    }
}
