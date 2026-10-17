package com.airbridge.touch.core

sealed class AppError(message: String, val retryable: Boolean = false) : Exception(message) {
    class WifiDisabled : AppError("Wi-Fi is turned off.", true)
    class WifiDirectUnsupported : AppError("This device does not support Wi-Fi Direct.")
    class PermissionMissing(message: String) : AppError(message, true)
    class DiscoveryFailed(message: String, retryable: Boolean = true) : AppError(message, retryable)
    class ConnectionFailed(message: String, retryable: Boolean = true) : AppError(message, retryable)
    class SocketFailure(message: String, retryable: Boolean = true) : AppError(message, retryable)
    class ProtocolViolation(message: String) : AppError(message)
    class SecurityRejection(message: String) : AppError(message)
    class TransferRejected(message: String) : AppError(message)
    class HashMismatch : AppError("Integrity verification failed.")
    class SizeMismatch : AppError("Received byte count does not match the announced size.")
    class StorageError(message: String) : AppError(message)
    class Cancelled : AppError("Transfer cancelled.")
    class Timeout(message: String) : AppError(message, true)
    class Internal(message: String) : AppError(message)
}

object ErrorMapper {
    fun fromThrowable(t: Throwable): AppError = when (t) {
        is AppError -> t
        is java.net.SocketTimeoutException -> AppError.Timeout("The connection timed out.")
        is java.io.IOException -> AppError.SocketFailure("The connection was interrupted.")
        is SecurityException -> AppError.PermissionMissing(t.message ?: "A required permission is missing.")
        is IllegalStateException -> AppError.Internal("The transfer engine reached an invalid state.")
        else -> AppError.Internal("An unexpected error occurred.")
    }
}
