package com.airbridge.touch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun `plain filename passes through`() {
        assertEquals("photo.jpg", FileNameSanitizer.sanitize("photo.jpg"))
    }

    @Test
    fun `path traversal is stripped`() {
        assertEquals("doc.pdf", FileNameSanitizer.sanitize("../../etc/passwd/doc.pdf"))
        assertEquals("doc.pdf", FileNameSanitizer.sanitize("..\\..\\windows\\doc.pdf"))
    }

    @Test
    fun `hidden traversal-only name is rejected`() {
        assertThrows(AppError.SecurityRejection::class.java) {
            FileNameSanitizer.sanitize("../../")
        }
    }

    @Test
    fun `forbidden characters are removed`() {
        assertEquals("ab_cd.txt", FileNameSanitizer.sanitize("ab:cd?.txt"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("file.txt", FileNameSanitizer.sanitize("fi\u0000le.txt"))
    }

    @Test
    fun `windows reserved name is prefixed`() {
        val out = FileNameSanitizer.sanitize("CON.txt")
        assertTrue(out.startsWith("_"))
    }

    @Test
    fun `empty name rejected`() {
        assertThrows(AppError.SecurityRejection::class.java) {
            FileNameSanitizer.sanitize("   ")
        }
    }

    @Test
    fun `trailing dot removed`() {
        assertEquals("file", FileNameSanitizer.sanitize("file."))
    }

    @Test
    fun `overlong name truncated`() {
        val long = "a".repeat(400) + ".txt"
        assertEquals(255, FileNameSanitizer.sanitize(long).length)
    }
}

class HashingTest {

    @Test
    fun `sha256 of empty matches known vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `sha256 of abc matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `valid hex check`() {
        assertTrue(Hashing.isValidSha256Hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }
}

class ProgressReporterTest {

    @Test
    fun `suppresses rapid duplicate progress`() {
        val r = ProgressReporter(minIntervalMs = 100)
        assertTrue(r.shouldEmit(nowMs = 1000, bytesNow = 100, bytesPrev = 0))
        assertEquals(false, r.shouldEmit(nowMs = 1050, bytesNow = 200, bytesPrev = 100))
        assertTrue(r.shouldEmit(nowMs = 1100, bytesNow = 300, bytesPrev = 200))
    }

    @Test
    fun `ignores non-advancing progress`() {
        val r = ProgressReporter(minIntervalMs = 0)
        assertTrue(r.shouldEmit(0, 100, 0))
        assertEquals(false, r.shouldEmit(1000, 100, 100))
        assertEquals(false, r.shouldEmit(1000, 50, 100))
    }
}
