package com.danieldk.splatevpn.data

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String
) {
    override fun toString(): String {
        return "[$timestamp] [$level/$tag]: $message"
    }
}

@Singleton
class AppLogManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val reportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val maxBufferEntries = 3000
    private val maxErrorEntries = 1000
    private val logQueue = ConcurrentLinkedDeque<LogEntry>()
    private val errorQueue = ConcurrentLinkedDeque<LogEntry>()
    private val hasPendingFlowUpdate = AtomicBoolean(false)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _errorsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val errorsFlow: StateFlow<List<LogEntry>> = _errorsFlow.asStateFlow()

    private val logFile by lazy {
        File(context.filesDir, "splate_debug.log")
    }

    private val logChannel = kotlinx.coroutines.channels.Channel<LogEntry>(capacity = 1000)

    init {
        // Publish at most five snapshots per second, even when the disk writer
        // catches up after each individual line. Always flush the final update.
        scope.launch {
            while (isActive) {
                delay(200L)
                if (hasPendingFlowUpdate.compareAndSet(true, false)) {
                    _logsFlow.value = logQueue.toList()
                    _errorsFlow.value = errorQueue.toList()
                }
            }
        }
        scope.launch {
            for (entry in logChannel) {
                try {
                    // Rotate log file to .1 when reaching 5MB instead of deleting all history
                    val maxLogFileSize = 5 * 1024 * 1024L
                    if (logFile.length() > maxLogFileSize) {
                        val backupFile = File(context.filesDir, "splate_debug.log.1")
                        if (backupFile.exists()) backupFile.delete()
                        logFile.renameTo(backupFile)
                    }
                    FileOutputStream(logFile, true).use { fos ->
                        fos.write("${entry}\n".toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    // ignore file write errors
                }

            }
        }
        log("AppLogManager", "Диагностический логгер инициализирован")
    }

    fun log(tag: String, message: String, level: String = "INFO") {
        val now = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = LogEntry(
            timestamp = now,
            level = level,
            tag = tag,
            message = message
        )

        logQueue.addLast(entry)
        while (logQueue.size > maxBufferEntries) {
            logQueue.pollFirst()
        }

        if (level == "ERROR" || level == "WARN") {
            errorQueue.addLast(entry)
            while (errorQueue.size > maxErrorEntries) {
                errorQueue.pollFirst()
            }
        }

        // Also write to logcat
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }

        hasPendingFlowUpdate.set(true)
        logChannel.trySend(entry)
    }

    fun i(tag: String, message: String) = log(tag, message, "INFO")
    fun d(tag: String, message: String) = log(tag, message, "DEBUG")
    fun w(tag: String, message: String) = log(tag, message, "WARN")
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(tag, fullMsg, "ERROR")
    }

    fun clear() {
        logQueue.clear()
        errorQueue.clear()
        _logsFlow.value = emptyList()
        _errorsFlow.value = emptyList()
        scope.launch {
            try {
                if (logFile.exists()) logFile.delete()
                val backupFile = File(context.filesDir, "splate_debug.log.1")
                if (backupFile.exists()) backupFile.delete()
            } catch (e: Exception) {
                // ignore
            }
        }
        log("AppLogManager", "Журнал логов очищен")
    }

    fun generateDiagnosticReport(
        activeServerName: String? = null,
        activeServerProtocol: String? = null,
        routingMode: String? = null,
        dnsProvider: String? = null,
        vpnConnected: Boolean = false,
        ipDetails: String? = null
    ): String {
        val sb = StringBuilder()
        val now = synchronized(reportDateFormat) { reportDateFormat.format(Date()) }

        sb.appendLine("==========================================")
        sb.appendLine("       SplateVPN - ДИАГНОСТИЧЕСКИЙ ОТЧЕТ       ")
        sb.appendLine("==========================================")
        sb.appendLine("Дата и время: $now")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Версия Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Архитектура: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Статус VPN: ${if (vpnConnected) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО"}")
        sb.appendLine("Активный сервер: ${activeServerName ?: "Не выбран"} (${activeServerProtocol ?: "N/A"})")
        sb.appendLine("Режим маршрутизации: ${routingMode ?: "N/A"}")
        sb.appendLine("DNS провайдер: ${dnsProvider ?: "N/A"}")
        if (!ipDetails.isNullOrBlank()) {
            sb.appendLine("Геолокация выхода: $ipDetails")
        }

        val recentErrors = errorQueue.toList()
        if (recentErrors.isNotEmpty()) {
            sb.appendLine("------------------------------------------")
            sb.appendLine("СОХРАНЕННЫЕ ОШИБКИ И ПРЕДУПРЕЖДЕНИЯ (${recentErrors.size} записей):")
            sb.appendLine("------------------------------------------")
            for (err in recentErrors) {
                sb.appendLine(err.toString())
            }
        }

        sb.appendLine("------------------------------------------")
        sb.appendLine("ПОСЛЕДНИЕ СОБЫТИЯ И ЛОГИ СИСТЕМЫ (${logQueue.size} записей):")
        sb.appendLine("------------------------------------------")

        for (entry in logQueue) {
            sb.appendLine(entry.toString())
        }

        sb.appendLine("==========================================")
        sb.appendLine("           КОНЕЦ ОТЧЕТА                   ")
        sb.appendLine("==========================================")

        return sb.toString()
    }
}
