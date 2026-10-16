package dev.localai.chat

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash-first diagnostics. Writes synchronously to app-private storage so fatal errors
 * are persisted before Android terminates the process.
 */
object AppLogger {
    private const val TAG = "LocalAI-Diagnostics"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    @Volatile private var logFile: File? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            val directory = File(context.filesDir, "logs")
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Não foi possível criar a pasta de logs: ${directory.absolutePath}")
                return
            }
            logFile = File(directory, "localai-diagnostics.txt")
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // File I/O is intentionally synchronous on the crashing thread.
                logFatal(thread, error)
                val delegate = previousHandler
                if (delegate != null) delegate.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
            write("INFO", "AppLogger", "Logger inicializado. Arquivo: ${logFile?.absolutePath}")
        }
    }

    fun info(tag: String, message: String) = write("INFO", tag, message)
    fun warn(tag: String, message: String, error: Throwable? = null) =
        write("WARN", tag, message + (error?.let { "\n" + stackTrace(it) } ?: ""))
    fun error(tag: String, message: String, error: Throwable? = null) =
        write("ERROR", tag, message + (error?.let { "\n" + stackTrace(it) } ?: ""))

    private fun logFatal(thread: Thread, error: Throwable) {
        write("FATAL", "UncaughtException", "Crash na thread '${thread.name}' (id=${thread.id}):\n${stackTrace(error)}", forceSync = true)
    }

    private fun write(level: String, tag: String, message: String, forceSync: Boolean = false) {
        val line = "${timestamp()} [${level}] [${tag}] ${message.replace("\r", "")}\n"
        Log.println(
            when (level) { "ERROR", "FATAL" -> Log.ERROR; "WARN" -> Log.WARN; else -> Log.INFO },
            TAG,
            "$tag: $message"
        )
        val target = logFile ?: return
        synchronized(this) {
            try {
                if (target.exists() && target.length() > MAX_LOG_BYTES) {
                    val backup = File(target.parentFile, "localai-diagnostics-previous.txt")
                    if (backup.exists()) backup.delete()
                    if (!target.renameTo(backup)) target.writeText("")
                }
                FileOutputStream(target, true).use { stream ->
                    stream.write(line.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    if (forceSync || level == "ERROR" || level == "FATAL") stream.fd.sync()
                }
            } catch (loggingFailure: Throwable) {
                Log.e(TAG, "Falha ao gravar log no armazenamento privado", loggingFailure)
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())

    private fun stackTrace(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
