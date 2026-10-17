package com.airbridge.touch.protocol

import com.airbridge.touch.core.AppError

object SecurityValidators {
    private val unsafeChars = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")
    private val windowsReservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun safeDisplayFileName(remoteName: String): String {
        val trimmed = remoteName.trim().replace(unsafeChars, "_")
        if (trimmed.isBlank()) throw AppError.SecurityRejection("Remote filename is empty.")
        if (trimmed == "." || trimmed == "..") throw AppError.SecurityRejection("Remote filename is not allowed.")
        if (trimmed.contains("..")) throw AppError.SecurityRejection("Remote filename is not allowed.")
        val base = trimmed.substringBeforeLast('.').uppercase()
        if (base in windowsReservedNames) throw AppError.SecurityRejection("Remote filename uses a reserved name.")
        return trimmed.take(180)
    }

    fun validateTransferSize(size: Long, maxBytes: Long) {
        if (size < 0) throw AppError.SecurityRejection("Negative file size is not allowed.")
        if (size > maxBytes) throw AppError.SecurityRejection("Transfer exceeds the configured size limit.")
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
