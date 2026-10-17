package com.airbridge.touch.transport

import com.airbridge.touch.protocol.Frame
import com.airbridge.touch.protocol.FrameCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TcpLink(private val port: Int = PORT) {
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var codec: FrameCodec? = null

    suspend fun connectTo(address: InetAddress) = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = SOCKET_TIMEOUT_MS
        s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        socket = s
        codec = FrameCodec(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()))
    }

    suspend fun acceptOnce() = withContext(Dispatchers.IO) {
        val server = ServerSocket(port)
        server.soTimeout = CONNECT_TIMEOUT_MS * 3
        serverSocket = server
        val s = server.accept()
        s.tcpNoDelay = true
        s.soTimeout = SOCKET_TIMEOUT_MS
        socket = s
        codec = FrameCodec(BufferedInputStream(s.getInputStream()), BufferedOutputStream(s.getOutputStream()))
    }

    suspend fun send(frame: Frame) = withContext(Dispatchers.IO) {
        codec?.write(frame) ?: error("TCP link is not connected.")
    }

    suspend fun receive(): Frame = withContext(Dispatchers.IO) {
        codec?.read() ?: error("TCP link is not connected.")
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        codec = null
    }

    companion object {
        const val PORT = 47931
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
