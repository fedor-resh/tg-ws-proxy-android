package org.flowseal.tgwsproxy.android

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DcKey(val dc: Int, val isMedia: Boolean)

class ProxyEngine(
    private val config: ProxyConfig,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onError(message: String, error: Throwable? = null)
        fun onStopped()
    }

    private val running = AtomicBoolean(false)
    private val workerPool = Executors.newCachedThreadPool()
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val wsBlacklist = ConcurrentHashMap.newKeySet<DcKey>()
    private val dcFailUntil = ConcurrentHashMap<DcKey, Long>()
    private val dcOptions = config.dcMap()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    fun start() {
        check(running.compareAndSet(false, true)) { "Proxy already running" }

        val bindAddress = InetAddress.getByName(config.host)
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, config.port), 64)
        }
        serverSocket = socket

        acceptThread = Thread(
            {
                runAcceptLoop(socket)
            },
            "proxy-accept",
        ).apply {
            isDaemon = true
            start()
        }

        ProxyLog.i("Proxy listening on ${config.host}:${config.port}")
        ProxyLog.i("Configured DC IPs: $dcOptions")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null

        synchronized(activeSockets) {
            activeSockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
            activeSockets.clear()
        }

        workerPool.shutdownNow()
        workerPool.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun runAcceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val client = server.accept()
                activeSockets += client
                workerPool.execute {
                    try {
                        SocksSession(
                            clientSocket = client,
                            dcOptions = dcOptions,
                            wsBlacklist = wsBlacklist,
                            dcFailUntil = dcFailUntil,
                        ).run()
                    } catch (error: Throwable) {
                        ProxyLog.w("Client session failed", error)
                    } finally {
                        activeSockets -= client
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: SocketException) {
            if (running.get()) {
                callbacks.onError("Accept loop aborted unexpectedly")
            }
        } catch (error: Throwable) {
            if (running.get()) {
                callbacks.onError("Accept loop crashed", error)
            }
        } finally {
            running.set(false)
            callbacks.onStopped()
        }
    }
}
