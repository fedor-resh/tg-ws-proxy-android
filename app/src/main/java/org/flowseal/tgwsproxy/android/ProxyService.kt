package org.flowseal.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ProxyService : Service() {
    private var engine: ProxyEngine? = null
    private var activeConfigSignature: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            ProxyLog.configure(this, ProxyConfigStore(this).load().verbose)
            ProxyLog.i("Service command received: ${intent?.action ?: ACTION_START}")
            when (intent?.action) {
                ACTION_STOP -> {
                    stopProxy()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    START_NOT_STICKY
                }
                else -> {
                    startOrRestartProxy()
                    START_STICKY
                }
            }
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            running = false
            ProxyLog.captureCrash("Service start command failed", error)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun startOrRestartProxy() {
        val config = ProxyConfigStore(this).load()
        val configSignature = "${config.host}:${config.port}|${config.dcIp.joinToString(",")}|${config.verbose}"
        ProxyLog.configure(this, config.verbose)
        createNotificationChannel()
        if (running && engine != null && activeConfigSignature == configSignature) {
            ProxyLog.i("Start ignored: proxy already running with same config")
            return
        }
        stopProxy()
        lastError = null
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Запуск ${config.host}:${config.port}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        try {
            val newEngine = ProxyEngine(
                config = config,
                callbacks = object : ProxyEngine.Callbacks {
                    override fun onError(message: String, error: Throwable?) {
                        lastError = message
                        running = false
                        ProxyLog.e(message, error)
                    }

                    override fun onStopped() {
                        running = false
                    }
                },
            )
            newEngine.start()
            engine = newEngine
            activeConfigSignature = configSignature
            running = true
            ProxyLog.i("Foreground proxy service started")

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification("Слушает ${config.host}:${config.port}"),
            )
        } catch (error: Throwable) {
            running = false
            lastError = error.message ?: error.javaClass.simpleName
            ProxyLog.e("Failed to start proxy engine", error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopProxy() {
        engine?.stop()
        engine = null
        activeConfigSignature = null
        running = false
        ProxyLog.i("Proxy service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TG WS Proxy",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Foreground service for local Telegram SOCKS5 proxy"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TG WS Proxy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "org.flowseal.tgwsproxy.android.action.START"
        const val ACTION_STOP = "org.flowseal.tgwsproxy.android.action.STOP"

        @Volatile
        private var running = false

        @Volatile
        private var lastError: String? = null

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }

        fun isRunning(): Boolean = running

        fun lastErrorMessage(): String? = lastError
    }
}
