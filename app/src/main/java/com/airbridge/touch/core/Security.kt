package com.airbridge.touch.core

import java.security.MessageDigest

/** Validates remote-supplied file names. Never trust metadata from peers. */
object FileNameSanitizer {
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")

    /** Returns a safe single-path-segment file name or throws SecurityRejection. */
    fun sanitize(raw: String, maxBytes: Int = TransferLimits.MAX_METADATA_NAME_BYTES): String {
        if (raw.isBlank()) throw AppError.SecurityRejection("Empty file name")
        // Strip directory components entirely; peers must never control paths.
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name != raw.trim()) { /* path components removed; that is expected */ }
        // Remove control characters and forbidden characters.
        name = name.filter { it.code >= 32 && it !in "\\/:*?\"<>|" }
        if (name.isBlank()) throw AppError.SecurityRejection("File name contains no usable characters")
        if (name.length > maxBytes) name = name.take(maxBytes)
        if (name.endsWith(".")) name = name.dropLast(1)
        if (name.isBlank()) throw AppError.SecurityRejection("File name became empty after sanitizing")
        val stem = name.substringBefore('.', name).uppercase()
        if (stem in RESERVED) name = "_$name"
        // Guard against hidden files and Windows-reserved trailing characters.
        while (name.lastOrNull() in setOf('.', ' ')) name = name.dropLast(1)
        if (name.isBlank()) throw AppError.SecurityRejection("File name invalid after sanitizing")
        return name
    }
}

object Hashing {
    private const val HEX = "0123456789abcdef"

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(d)
    }

    fun newSha256Digester() = MessageDigest.getInstance("SHA-256")

    fun toHex(digest: ByteArray): String {
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    fun isValidSha256Hex(hex: String): Boolean =
        hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }
}
