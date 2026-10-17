package com.airbridge.touch.transport

import android.content.Context
import com.airbridge.touch.core.AppError
import com.airbridge.touch.core.ErrorMapper
import com.airbridge.touch.core.TransferLimits
import com.airbridge.touch.protocol.Abtp
import com.airbridge.touch.protocol.Frame
import com.airbridge.touch.protocol.FrameCodec
import com.airbridge.touch.protocol.PayloadReader
import com.airbridge.touch.protocol.Payloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TcpLink owns the single TCP session between two peers over the Wi-Fi Direct group.
 * Exactly one socket; server side listens on [PORT], client side connects to the group owner.
 */
class TcpLink(
    private val deviceId: String,
    private val deviceName: String,
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val PORT = 47931
        private const val MAX_CHUNK = 128 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    val incomingFrames = MutableStateFlow<Frame?>(null)

    suspend fun connectClient(groupOwnerAddress: InetAddress) {
        withIo {
            val s = Socket()
            s.bind(null)
            s.connect(InetSocketAddress(groupOwnerAddress, PORT), TransferLimits.CONNECT_TIMEOUT_MS)
            s.soTimeout = TransferLimits.SOCKET_TIMEOUT_MS
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
        }
    }

    suspend fun acceptServer() {
        withIo {
            val srv = ServerSocket(PORT)
            srv.soTimeout = TransferLimits.CONNECT_TIMEOUT_MS * 3
            server = srv
            val s = srv.accept()
            s.soTimeout = TransferLimits.SOCKET_TIMEOUT_MS
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 256 * 1024))
        }
    }

    suspend fun send(frame: Frame) = withIo {
        val out = output ?: throw AppError.SocketFailure("Not connected.")
        FrameCodec.write(out, frame)
    }

    suspend fun receive(): Frame = withIo {
        val inp = input ?: throw AppError.SocketFailure("Not connected.")
        FrameCodec.decode(inp)
    }

    suspend fun handshake(): PeerIdentity {
        send(Frame(Abtp.Type.HELLO, Payloads.writeHello(deviceId, deviceName)))
        val reply = receive()
        return when (reply.type) {
            Abtp.Type.HELLO -> {
                val (peerId, peerName) = Payloads.readHello(PayloadReader(reply.payload))
                send(Frame(Abtp.Type.HELLO_ACK, Payloads.writeHelloAck(java.util.UUID.randomUUID().toString())))
                PeerIdentity(peerId, peerName)
            }
            Abtp.Type.HELLO_ACK -> {
                // We initiated as client and got an ack — peer already sent HELLO first; read it.
                val hello = receive()
                if (hello.type != Abtp.Type.HELLO) throw AppError.ProtocolViolation("Expected HELLO")
                val (peerId, peerName) = Payloads.readHello(PayloadReader(hello.payload))
                PeerIdentity(peerId, peerName)
            }
            else -> throw AppError.ProtocolViolation("Unexpected frame during handshake")
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withIo {
            runCatching { send(Frame(Abtp.Type.CLOSE, ByteArray(0))) }
            runCatching { socket?.close() }
            runCatching { server?.close() }
        }
        socket = null; server = null; input = null; output = null
        scope.cancel()
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: java.net.SocketTimeoutException) {
            throw AppError.Timeout("Connection timed out.")
        } catch (e: java.io.IOException) {
            throw ErrorMapper.fromThrowable(e)
        }
    }

    data class PeerIdentity(val peerId: String, val peerName: String)
}
