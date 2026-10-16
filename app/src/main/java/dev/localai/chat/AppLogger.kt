package dev.localai.chat

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Crash-first diagnostics saved in the app-private data directory:
 * /data/data/<package>/logs (alias of /data/user/0/<package>/logs on user 0).
 *
 * Startup logcat import and live capture are best-effort because Android/OEMs can restrict
 * which records an ordinary application process may read.
 */
object AppLogger {
    private const val TAG = "LocalAI-Diagnostics"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_HISTORY_BYTES = 512 * 1024

    @Volatile private var logFile: File? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return

            // context.dataDir maps to /data/data/<package> on the primary Android user.
            val directory = File(context.dataDir, "logs")
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "Não foi possível criar a pasta de logs: ${directory.absolutePath}")
                return
            }
            logFile = File(directory, "localai-diagnostics.txt")
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Synchronous writes are intentional: persist fatal data before process death.
                logFatal(thread, error)
                val delegate = previousHandler
                if (delegate != null) delegate.uncaughtException(thread, error)
                else {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }

            write("INFO", "AppLogger", "Logger inicializado. Arquivo: ${logFile?.absolutePath}")
            write("INFO", "AppLogger", "Solicitando logcat histórico e captura ao vivo; UID=${context.applicationInfo.uid}")
            collectBufferedLogcat(context.applicationInfo.uid)
            startLiveLogcat(context.applicationInfo.uid)
        }
    }

    fun info(tag: String, message: String) = write("INFO", tag, message)

    fun warn(tag: String, message: String, error: Throwable? = null) =
        write("WARN", tag, message + (error?.let { "\n" + stackTrace(it) } ?: ""))

    fun error(tag: String, message: String, error: Throwable? = null) =
        write("ERROR", tag, message + (error?.let { "\n" + stackTrace(it) } ?: ""))

    private fun logFatal(thread: Thread, error: Throwable) {
        write(
            "FATAL",
            "UncaughtException",
            "Crash na thread '${thread.name}' (id=${thread.id}):\n${stackTrace(error)}",
            forceSync = true,
        )
    }

    /**
     * Imports the app UID's records still retained in Android's log ring buffer, including
     * prior launches when Android still has those entries. Runs off the UI thread.
     */
    private fun collectBufferedLogcat(uid: Int) {
        Thread({
            var child: Process? = null
            try {
                val process: Process = ProcessBuilder(
                    "logcat", "-d", "-v", "threadtime", "--uid=$uid",
                ).redirectErrorStream(true).start()
                child = process
                Thread({
                    try {
                        Thread.sleep(2200)
                        if (process.isAlive) process.destroyForcibly()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }, "LocalAI-Logcat-History-Timeout").apply {
                    isDaemon = true
                    start()
                }

                var captured = 0
                process.inputStream.bufferedReader().useLines { lines ->
                    val iterator = lines.iterator()
                    while (iterator.hasNext() && captured < MAX_HISTORY_BYTES) {
                        val line = iterator.next()
                        if (!line.contains(TAG)) {
                            appendRaw("LOGCAT-HISTORY", line)
                            captured += line.toByteArray(Charsets.UTF_8).size + 1
                        }
                    }
                    if (captured >= MAX_HISTORY_BYTES) process.destroyForcibly()
                }
                val exited = process.waitFor(250, TimeUnit.MILLISECONDS)
                if (!exited) process.destroyForcibly()
                appendRaw(
                    "LOGCAT-HISTORY",
                    "Snapshot histórico concluído; bytes capturados=$captured; processo finalizado=$exited",
                )
            } catch (error: Throwable) {
                appendRaw(
                    "LOGCAT-HISTORY",
                    "Logcat histórico indisponível: ${error.javaClass.simpleName}: ${error.message}",
                )
                try { child?.destroyForcibly() } catch (_: Throwable) {}
            }
        }, "LocalAI-Logcat-History").apply {
            isDaemon = true
            start()
        }
    }

    /** Captures new logcat records matching this app UID for the life of the process. */
    private fun startLiveLogcat(uid: Int) {
        Thread({
            var child: Process? = null
            try {
                val process: Process = ProcessBuilder(
                    "logcat", "-v", "threadtime", "--uid=$uid",
                ).redirectErrorStream(true).start()
                child = process
                appendRaw("LOGCAT-LIVE", "Captura ao vivo iniciada para UID $uid")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!line.contains(TAG)) appendRaw("LOGCAT-LIVE", line)
                    }
                }
                appendRaw("LOGCAT-LIVE", "Fluxo logcat encerrado; exit=${process.waitFor()}")
            } catch (error: Throwable) {
                appendRaw(
                    "LOGCAT-LIVE",
                    "Logcat ao vivo indisponível: ${error.javaClass.simpleName}: ${error.message}",
                )
                try { child?.destroyForcibly() } catch (_: Throwable) {}
            }
        }, "LocalAI-Logcat-Live").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendRaw(source: String, message: String) {
        val target = logFile ?: return
        val line = "${timestamp()} [$source] ${message.replace("\r", "")}\n"
        synchronized(this) {
            try {
                val bytes = line.toByteArray(Charsets.UTF_8)
                rotateIfNeeded(target, bytes.size)
                FileOutputStream(target, true).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                    if (message.contains("AndroidRuntime") || message.contains("Fatal signal") ||
                        message.contains(" F ") || message.contains(" E ")
                    ) stream.fd.sync()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Falha ao gravar linha de logcat", error)
            }
        }
    }

    private fun write(
        level: String,
        tag: String,
        message: String,
        forceSync: Boolean = false,
    ) {
        val line = "${timestamp()} [$level] [$tag] ${message.replace("\r", "")}\n"
        Log.println(
            when (level) {
                "ERROR", "FATAL" -> Log.ERROR
                "WARN" -> Log.WARN
                else -> Log.INFO
            },
            TAG,
            "$tag: $message",
        )
        val target = logFile ?: return
        synchronized(this) {
            try {
                val bytes = line.toByteArray(Charsets.UTF_8)
                rotateIfNeeded(target, bytes.size)
                FileOutputStream(target, true).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                    if (forceSync || level == "ERROR" || level == "FATAL") stream.fd.sync()
                }
            } catch (loggingFailure: Throwable) {
                Log.e(TAG, "Falha ao gravar log no armazenamento privado", loggingFailure)
            }
        }
    }

    private fun rotateIfNeeded(target: File, incomingBytes: Int) {
        if (target.exists() && target.length() + incomingBytes > MAX_LOG_BYTES) {
            val backup = File(target.parentFile, "localai-diagnostics-previous.txt")
            if (backup.exists()) backup.delete()
            if (!target.renameTo(backup)) target.writeText("")
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
