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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
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

    private val maxBufferEntries = 600
    private val logQueue = ConcurrentLinkedDeque<LogEntry>()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val logFile by lazy {
        File(context.filesDir, "splate_debug.log")
    }

    init {
        log("AppLogManager", "Диагностический логгер инициализирован")
    }

    fun log(tag: String, message: String, level: String = "INFO") {
        val now = dateFormat.format(Date())
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

        // Also write to logcat
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }

        // Async: update Flow and append to file
        scope.launch {
            _logsFlow.value = logQueue.toList()
            try {
                if (logFile.length() > 3 * 1024 * 1024) {
                    logFile.delete()
                }
                FileOutputStream(logFile, true).use { fos ->
                    fos.write("${entry}\n".toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                // ignore file write errors
            }
        }
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
        _logsFlow.value = emptyList()
        scope.launch {
            try {
                if (logFile.exists()) logFile.delete()
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
        val now = reportDateFormat.format(Date())

        sb.appendLine("==========================================")
        sb.appendLine("       SPLATE VPN - ДИАГНОСТИЧЕСКИЙ ОТЧЕТ       ")
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
