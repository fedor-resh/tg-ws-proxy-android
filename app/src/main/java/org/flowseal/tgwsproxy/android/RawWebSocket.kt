package org.flowseal.tgwsproxy.android

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WsHandshakeException(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
) : Closeable {
    private val random = SecureRandom()

    @Volatile
    private var closed = false

    fun sendBinary(data: ByteArray) {
        check(!closed) { "WebSocket already closed" }
        output.write(buildFrame(OP_BINARY, data, mask = true))
        output.flush()
    }

    fun sendBatch(parts: List<ByteArray>) {
        check(!closed) { "WebSocket already closed" }
        for (part in parts) {
            output.write(buildFrame(OP_BINARY, part, mask = true))
        }
        output.flush()
    }

    fun recv(): ByteArray? {
        while (!closed) {
            val frame = readFrame()
            when (frame.opcode) {
                OP_CLOSE -> {
                    closed = true
                    return null
                }
                OP_PING -> {
                    output.write(buildFrame(OP_PONG, frame.payload, mask = true))
                    output.flush()
                }
                OP_PONG -> Unit
                OP_TEXT, OP_BINARY -> return frame.payload
                else -> Unit
            }
        }
        return null
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            output.write(buildFrame(OP_CLOSE, ByteArray(0), mask = true))
            output.flush()
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun readFrame(): WsFrame {
        val hdr = readExact(input, 2)
        val opcode = hdr[0].toInt() and 0x0F
        val masked = (hdr[1].toInt() and 0x80) != 0
        var length = hdr[1].toInt() and 0x7F

        if (length == 126) {
            val ext = readExact(input, 2)
            length = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        } else if (length == 127) {
            val ext = readExact(input, 8)
            val longLength = ext.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
            if (longLength > Int.MAX_VALUE) {
                throw IllegalStateException("Frame too large: $longLength")
            }
            length = longLength.toInt()
        }

        val maskKey = if (masked) readExact(input, 4) else null
        val payload = readExact(input, length)
        return if (maskKey == null) {
            WsFrame(opcode, payload)
        } else {
            WsFrame(opcode, xorMask(payload, maskKey))
        }
    }

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val length = data.size
        val header = ArrayList<Byte>()
        header += (0x80 or opcode).toByte()

        val maskBit = if (mask) 0x80 else 0x00
        when {
            length < 126 -> header += (maskBit or length).toByte()
            length < 65_536 -> {
                header += (maskBit or 126).toByte()
                header += ((length ushr 8) and 0xFF).toByte()
                header += (length and 0xFF).toByte()
            }
            else -> {
                header += (maskBit or 127).toByte()
                for (shift in 56 downTo 0 step 8) {
                    header += ((length.toLong() ushr shift) and 0xFF).toByte()
                }
            }
        }

        val headerBytes = header.toByteArray()
        if (!mask) {
            return headerBytes + data
        }

        val maskKey = ByteArray(4).also(random::nextBytes)
        return headerBytes + maskKey + xorMask(data, maskKey)
    }

    companion object {
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        fun connect(
            ip: String,
            domain: String,
            path: String = "/apiws",
            timeoutMs: Int = 10_000,
        ): RawWebSocket {
            val plain = Socket()
            plain.connect(InetSocketAddress(ip, 443), timeoutMs)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val socket = factory.createSocket(plain, domain, 443, true) as SSLSocket
            socket.soTimeout = timeoutMs

            val params: SSLParameters = socket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()

            val output = BufferedOutputStream(socket.outputStream)
            val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            output.write(
                buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $domain\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("Sec-WebSocket-Protocol: binary\r\n")
                    append("Origin: https://web.telegram.org\r\n")
                    append("User-Agent: Mozilla/5.0 (Linux; Android 14)\r\n")
                    append("\r\n")
                }.encodeToByteArray()
            )
            output.flush()

            val input = BufferedInputStream(socket.inputStream)
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) {
                    break
                }
                lines += line
            }

            if (lines.isEmpty()) {
                socket.close()
                throw WsHandshakeException(0, "empty response")
            }

            val first = lines.first()
            val statusCode = first.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (statusCode == 101) {
                socket.soTimeout = 0
                return RawWebSocket(socket, input, output)
            }

            val headers = linkedMapOf<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            socket.close()
            throw WsHandshakeException(statusCode, first, headers, headers["location"])
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = input.read()
                if (next == -1) {
                    if (bytes.isEmpty()) {
                        throw EOFException("Unexpected EOF while reading header")
                    }
                    break
                }
                if (next == '\n'.code) {
                    break
                }
                if (next != '\r'.code) {
                    bytes += next.toByte()
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }

        private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(result, offset, length - offset)
                if (count < 0) {
                    throw EOFException("Unexpected EOF")
                }
                offset += count
            }
            return result
        }

        private fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
            val result = ByteArray(data.size)
            for (index in data.indices) {
                result[index] = (data[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
            return result
        }
    }
}

private data class WsFrame(val opcode: Int, val payload: ByteArray)
