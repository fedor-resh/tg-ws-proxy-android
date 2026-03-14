package org.flowseal.tgwsproxy.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProxyLog {
    private const val TAG = "TgWsProxy"
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var verboseEnabled = false

    @Volatile
    private var logFile: File? = null

    fun configure(context: Context, verbose: Boolean) {
        verboseEnabled = verbose
        val file = File(context.applicationContext.filesDir, "proxy.log")
        file.parentFile?.mkdirs()
        logFile = file
    }

    fun readTail(context: Context, maxChars: Int = 12_000): String {
        val file = logFile ?: File(context.applicationContext.filesDir, "proxy.log")
        if (!file.exists()) {
            return "Log file is empty."
        }

        val text = file.readText()
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun d(message: String) {
        if (verboseEnabled) {
            append("DEBUG", message, null)
        }
    }

    fun i(message: String) {
        append("INFO", message, null)
    }

    fun w(message: String, error: Throwable? = null) {
        append("WARN", message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        append("ERROR", message, error)
    }

    fun captureCrash(message: String, error: Throwable) {
        append("FATAL", message, error)
    }

    private fun append(level: String, message: String, error: Throwable?) {
        when (level) {
            "DEBUG" -> Log.d(TAG, message, error)
            "INFO" -> Log.i(TAG, message, error)
            "WARN" -> Log.w(TAG, message, error)
            else -> Log.e(TAG, message, error)
        }

        synchronized(lock) {
            val timestamp = formatter.format(Date())
            val line = buildString {
                append(timestamp)
                append("  ")
                append(level)
                append("  ")
                append(message)
                if (error != null) {
                    append(" :: ")
                    append(error.javaClass.simpleName)
                    append(": ")
                    append(error.message ?: "(no message)")
                    append('\n')
                    append(stackTraceToString(error))
                } else {
                    append('\n')
                }
            }
            val file = logFile ?: return
            file.appendText(line)
        }
    }

    private fun stackTraceToString(error: Throwable): String {
        val buffer = StringWriter()
        val writer = PrintWriter(buffer)
        error.printStackTrace(writer)
        writer.flush()
        return buffer.toString()
    }
}
