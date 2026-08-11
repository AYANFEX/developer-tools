package com.unixshells.devbrowser

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_FILE_NAME = "devbrowser_logs.txt"
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            log("AppLogger initialized. Log file path: ${logFile?.absolutePath}")

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val stackTrace = throwable.stackTraceToString()
                log("CRASH UNCAUGHT on thread ${thread.name}: $stackTrace")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to init AppLogger: ${e.message}")
        }
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] $message\n"
        Log.d("DevBrowserLog", message)
        try {
            logFile?.appendBytes(logLine.toByteArray())
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to write log: ${e.message}")
        }
    }

    fun readLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "No logs recorded yet."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.writeText("")
            log("Logs cleared.")
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to clear logs: ${e.message}")
        }
    }
}
