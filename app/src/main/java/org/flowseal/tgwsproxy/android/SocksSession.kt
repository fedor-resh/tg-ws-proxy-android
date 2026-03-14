package org.flowseal.tgwsproxy.android

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SocksSession(
    private val clientSocket: Socket,
    private val dcOptions: Map<Int, String>,
    private val wsBlacklist: MutableSet<DcKey>,
    private val dcFailUntil: ConcurrentHashMap<DcKey, Long>,
) {
    fun run() {
        val label = clientSocket.remoteSocketAddress?.toString() ?: "?"
        var destination = "?"
        var port = 0
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = SOCKS_TIMEOUT_MS

            val input = BufferedInputStream(clientSocket.getInputStream())
            val output = BufferedOutputStream(clientSocket.getOutputStream())

            val version = input.read()
            if (version != 0x05) {
                ProxyLog.d("[$label] not SOCKS5 (ver=$version)")
                return
            }

            val methodCount = input.read()
            if (methodCount < 0) {
                return
            }
            readExact(input, methodCount)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val request = readExact(input, 4)
            val command = request[1].toInt() and 0xFF
            val atyp = request[3].toInt() and 0xFF
            if (command != 0x01) {
                writeReply(output, 0x07)
                return
            }

            destination = when (atyp) {
                0x01 -> InetAddress.getByAddress(readExact(input, 4)).hostAddress.orEmpty()
                0x03 -> {
                    val len = input.read()
                    if (len < 0) {
                        throw EOFException("Unexpected EOF while reading host length")
                    }
                    readExact(input, len).toString(Charsets.UTF_8)
                }
                0x04 -> InetAddress.getByAddress(readExact(input, 16)).hostAddress.orEmpty()
                else -> {
                    writeReply(output, 0x08)
                    return
                }
            }
            port = readUnsignedShort(input)

            if (!TelegramRoutes.isTelegramIp(destination)) {
                ProxyLog.d("[$label] passthrough -> $destination:$port")
                val remote = connectTcp(destination, port) ?: run {
                    writeReply(output, 0x05)
                    return
                }
                writeReply(output, 0x00)
                clientSocket.soTimeout = 0
                remote.soTimeout = 0
                bridgeTcp(
                    localInput = input,
                    localOutput = output,
                    remote = remote,
                    destination = destination,
                    port = port,
                )
                return
            }

            writeReply(output, 0x00)
            val init = readExact(input, 64)
            if (MtProtoInit.isHttpTransport(init)) {
                ProxyLog.d("[$label] HTTP transport rejected for $destination:$port")
                return
            }

            var workingInit = init
            var dcInfo = MtProtoInit.extractDc(workingInit)
            var initPatched = false

            if (dcInfo.dc == null) {
                val mapped = TelegramRoutes.lookupDc(destination)
                if (mapped != null) {
                    dcInfo = DcInfo(mapped.dc, mapped.isMedia)
                    if (pickWsTarget(mapped.dc) != null) {
                        val dcForPatch = if (mapped.isMedia) mapped.dc else -mapped.dc
                        workingInit = MtProtoInit.patchDc(workingInit, dcForPatch)
                        initPatched = true
                        ProxyLog.i("[$label] patched init dc_id for ${mapped.dc} ($destination:$port)")
                    }
                }
            }

            val dc = dcInfo.dc
            val target = dc?.let(::pickWsTarget)
            if (dc == null || target == null) {
                ProxyLog.w("[$label] unknown DC for $destination:$port, using TCP fallback")
                tcpFallback(input, output, destination, port, workingInit)
                return
            }

            if (!dcOptions.containsKey(dc)) {
                ProxyLog.i("[$label] DC$dc has no explicit target IP, using fallback edge $target")
            }

            val dcKey = DcKey(dc, dcInfo.isMedia)
            val now = SystemClock.elapsedRealtime()
            if (wsBlacklist.contains(dcKey)) {
                ProxyLog.d("[$label] DC$dc WS blacklisted, using TCP fallback")
                tcpFallback(input, output, destination, port, workingInit)
                return
            }

            val failUntil = dcFailUntil[dcKey] ?: 0L
            if (now < failUntil) {
                ProxyLog.d("[$label] DC$dc WS cooldown active, using TCP fallback")
                tcpFallback(input, output, destination, port, workingInit)
                return
            }

            val domains = TelegramRoutes.wsDomains(dc, dcInfo.isMedia)
            var ws: RawWebSocket? = null
            var redirectFailure = false
            var allRedirects = true

            for (domain in domains) {
                try {
                    ProxyLog.i("[$label] DC$dc -> wss://$domain/apiws via $target")
                    ws = RawWebSocket.connect(target, domain)
                    ProxyLog.i("[$label] DC$dc WebSocket connected via $domain")
                    allRedirects = false
                    break
                } catch (error: WsHandshakeException) {
                    if (error.isRedirect) {
                        redirectFailure = true
                        ProxyLog.w("[$label] WS redirect for $domain -> ${error.location ?: "?"}")
                    } else {
                        allRedirects = false
                        ProxyLog.w("[$label] WS handshake failed: ${error.statusLine}", error)
                    }
                } catch (error: Exception) {
                    allRedirects = false
                    ProxyLog.w("[$label] WS connect failed for $domain", error)
                }
            }

            if (ws == null) {
                if (redirectFailure && allRedirects) {
                    wsBlacklist += dcKey
                } else {
                    dcFailUntil[dcKey] = now + FAIL_COOLDOWN_MS
                }
                tcpFallback(input, output, destination, port, workingInit)
                return
            }

            dcFailUntil.remove(dcKey)
            clientSocket.soTimeout = 0
            ws.sendBinary(workingInit)
            val splitter = if (initPatched) {
                runCatching { MsgSplitter(workingInit) }.getOrNull()
            } else {
                null
            }
            bridgeWebSocket(
                localInput = input,
                localOutput = output,
                ws = ws,
                splitter = splitter,
                label = label,
                dc = dc,
                destination = destination,
                port = port,
                isMedia = dcInfo.isMedia,
            )
        } catch (error: SocketTimeoutException) {
            ProxyLog.w("SOCKS session timed out", error)
        } catch (error: EOFException) {
            ProxyLog.d("Client disconnected during handshake")
        } catch (error: InterruptedException) {
            ProxyLog.d("[$label] session interrupted for $destination:$port")
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            ProxyLog.w("SOCKS session failed", error)
        }
    }

    private fun tcpFallback(
        localInput: InputStream,
        localOutput: OutputStream,
        destination: String,
        port: Int,
        init: ByteArray,
    ) {
        val remote = connectTcp(destination, port) ?: return
        remote.getOutputStream().write(init)
        remote.getOutputStream().flush()
        clientSocket.soTimeout = 0
        remote.soTimeout = 0
        bridgeTcp(localInput, localOutput, remote, destination, port)
    }

    private fun connectTcp(host: String, port: Int): Socket? {
        return try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
        } catch (error: Exception) {
            ProxyLog.w("TCP connect failed to $host:$port", error)
            null
        }
    }

    private fun pickWsTarget(dc: Int): String? {
        return dcOptions[dc] ?: dcOptions.values.firstOrNull()
    }

    private fun bridgeTcp(
        localInput: InputStream,
        localOutput: OutputStream,
        remote: Socket,
        destination: String,
        port: Int,
    ) {
        var upBytes = 0L
        var downBytes = 0L
        val startedAt = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        executor.execute {
            try {
                upBytes = pipe(localInput, remote.getOutputStream())
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }
        executor.execute {
            try {
                downBytes = pipe(remote.getInputStream(), localOutput)
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }

        done.await()
        try {
            remote.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        ProxyLog.i(
            "TCP fallback closed for $destination:$port " +
                "up=${humanBytes(upBytes)} down=${humanBytes(downBytes)} " +
                "time=${((SystemClock.elapsedRealtime() - startedAt) / 1000.0)}s",
        )
    }

    private fun bridgeWebSocket(
        localInput: InputStream,
        localOutput: OutputStream,
        ws: RawWebSocket,
        splitter: MsgSplitter?,
        label: String,
        dc: Int,
        destination: String,
        port: Int,
        isMedia: Boolean,
    ) {
        var upBytes = 0L
        var downBytes = 0L
        val startedAt = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        executor.execute {
            try {
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = localInput.read(buffer)
                    if (read < 0) {
                        break
                    }
                    upBytes += read.toLong()
                    val chunk = buffer.copyOf(read)
                    if (splitter != null) {
                        val parts = splitter.split(chunk)
                        if (parts.size > 1) {
                            ws.sendBatch(parts)
                        } else {
                            ws.sendBinary(parts.first())
                        }
                    } else {
                        ws.sendBinary(chunk)
                    }
                }
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }

        executor.execute {
            try {
                while (true) {
                    val payload = ws.recv() ?: break
                    downBytes += payload.size.toLong()
                    localOutput.write(payload)
                    localOutput.flush()
                }
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }

        done.await()
        try {
            ws.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        val mediaSuffix = if (isMedia) "m" else ""
        ProxyLog.i(
            "[$label] DC$dc$mediaSuffix WS closed for $destination:$port " +
                "up=${humanBytes(upBytes)} down=${humanBytes(downBytes)} " +
                "time=${((SystemClock.elapsedRealtime() - startedAt) / 1000.0)}s",
        )
    }

    private fun pipe(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var transferred = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            transferred += read.toLong()
            output.write(buffer, 0, read)
            output.flush()
        }
        return transferred
    }

    private fun writeReply(output: BufferedOutputStream, status: Int) {
        output.write(
            byteArrayOf(
                0x05,
                status.toByte(),
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            ),
        )
        output.flush()
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF")
            }
            offset += read
        }
        return result
    }

    private fun readUnsignedShort(input: BufferedInputStream): Int {
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) {
            throw EOFException("Unexpected EOF while reading port")
        }
        return (high shl 8) or low
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKS_TIMEOUT_MS = 10_000
        private const val FAIL_COOLDOWN_MS = 60_000L

        private fun humanBytes(value: Long): String {
            var current = value.toDouble()
            val units = arrayOf("B", "KB", "MB", "GB")
            for (unit in units) {
                if (current < 1024.0) {
                    return String.format("%.1f%s", current, unit)
                }
                current /= 1024.0
            }
            return String.format("%.1fTB", current)
        }
    }
}
