package dev.localai.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var downloadsLogUri: Uri? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val locationToastShown = AtomicBoolean(false)
    private val failureToastShown = AtomicBoolean(false)

    /** Absolute path of the file that is currently receiving diagnostics. */
    val logFilePath: String
        get() = logFile?.absolutePath ?: "arquivo de log ainda não inicializado"

    fun initialize(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            appContext = context.applicationContext

            // Prefer the literal /data/data path on Android's primary user (UID range 0).
            // On secondary profiles, use dataDir because each user has a distinct app sandbox.
            val appDataDirectory = if (context.applicationInfo.uid / 100000 == 0) {
                File("/data/data/${context.packageName}")
            } else {
                context.dataDir
            }
            val candidates = listOfNotNull(
                File(appDataDirectory, "logs"),
                File(context.filesDir, "logs"),
                context.getExternalFilesDir("logs"),
            ).distinctBy { it.absolutePath }
            var preparedFile: File? = null
            var lastFailure: Throwable? = null
            for (directory in candidates) {
                try {
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw java.io.IOException("Não foi possível criar ${directory.absolutePath}")
                    }
                    val candidate = File(directory, "localai-diagnostics.txt")
                    FileOutputStream(candidate, true).use { it.flush() }
                    preparedFile = candidate
                    break
                } catch (failure: Throwable) {
                    lastFailure = failure
                    Log.e(TAG, "Diretório de logs indisponível: ${directory.absolutePath}", failure)
                }
            }
            if (preparedFile == null) {
                val reason = "Não foi possível preparar arquivo de diagnóstico. ${lastFailure?.javaClass?.simpleName}: ${lastFailure?.message}"
                Log.e(TAG, reason, lastFailure)
                showFailureToast(reason)
                return
            }
            logFile = preparedFile
            prepareDownloadsLog(context)
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Synchronous writes are intentional: persist fatal data before process death.
                logFatal(thread, error)
                val delegate = previousHandler
                if (delegate != null) delegate.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
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
                Log.e(TAG, "Falha ao gravar linha de logcat em ${target.absolutePath}", error)
                showFailureToast("Falha ao salvar diagnóstico em ${target.absolutePath}: ${error.message}")
                return
            }
        }
        mirrorToDownloads(line)
        showLocationToastOnce(target)
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
                Log.e(TAG, "Falha ao gravar log no armazenamento privado: ${target.absolutePath}", loggingFailure)
                showFailureToast("Falha ao salvar diagnóstico em ${target.absolutePath}: ${loggingFailure.message}")
                return
            }
        }
        mirrorToDownloads(line)
        showLocationToastOnce(target)
    }

    private fun prepareDownloadsLog(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "localai-diagnostics-${System.currentTimeMillis()}.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LocalAI")
            }
            downloadsLogUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val uri = downloadsLogUri
            if (uri != null) {
                context.contentResolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.appendLine("${timestamp()} [INFO] [AppLogger] Diagnóstico espelhado em Downloads/LocalAI")
                }
            }
        } catch (error: Throwable) {
            downloadsLogUri = null
            Log.e(TAG, "Não foi possível criar log em Downloads/LocalAI", error)
        }
    }

    private fun mirrorToDownloads(line: String) {
        val context = appContext ?: return
        val uri = downloadsLogUri ?: return
        try {
            context.contentResolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.append(line)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Falha ao espelhar log em Downloads/LocalAI", error)
        }
    }

    private fun showLocationToastOnce(target: File) {
        if (!locationToastShown.compareAndSet(false, true)) return
        val context = appContext ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(
                context,
                "Log de diagnóstico salvo em:\n${target.absolutePath}",
                Toast.LENGTH_LONG,
            ).show()
        }, 900L)
    }

    private fun showFailureToast(message: String) {
        if (!failureToastShown.compareAndSet(false, true)) return
        val context = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
