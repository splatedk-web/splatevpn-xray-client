package com.danieldk.splatevpn.data

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    @ApplicationContext private val context: Context,
    private val anonymousReports: AnonymousReportStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val reportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val maxBufferEntries = 3000
    private val maxErrorEntries = 1000
    private val bufferLock = Any()
    private val logQueue = ArrayDeque<LogEntry>()
    private val errorQueue = ArrayDeque<LogEntry>()
    private var hasPendingFlowUpdate = false

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _errorsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val errorsFlow: StateFlow<List<LogEntry>> = _errorsFlow.asStateFlow()

    private val logFile by lazy {
        File(context.filesDir, "splate_debug.log")
    }

    private val writes = LogWriteQueue<LogEntry>(capacity = 1000)
    private val writerWakeup = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (isActive) {
                delay(200L)
                synchronized(bufferLock) {
                    if (hasPendingFlowUpdate) {
                        hasPendingFlowUpdate = false
                        _logsFlow.value = logQueue.toList()
                        _errorsFlow.value = errorQueue.toList()
                    }
                }
            }
        }
        scope.launch {
            var diskClearPending = false
            for (signal in writerWakeup) {
                while (true) {
                    val write = writes.poll() ?: break
                    if (write == LogWrite.Clear) diskClearPending = true
                    try {
                        if (diskClearPending) {
                            for (file in listOf(logFile, File(context.filesDir, "splate_debug.log.1"))) {
                                check(!file.exists() || file.delete() || !file.exists()) { "Could not clear log file" }
                            }
                            diskClearPending = false
                        }
                        if (write is LogWrite.Append) {
                            if (logFile.length() > 5 * 1024 * 1024L) {
                                val backupFile = File(context.filesDir, "splate_debug.log.1")
                                if (backupFile.exists()) backupFile.delete()
                                logFile.renameTo(backupFile)
                            }
                            FileOutputStream(logFile, true).use { output ->
                                output.write("${write.entry}\n".toByteArray(Charsets.UTF_8))
                            }
                        }
                    } catch (_: Exception) {
                        // Memory logging remains available if storage cannot be written.
                    }
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

        synchronized(bufferLock) {
            logQueue.addLast(entry)
            while (logQueue.size > maxBufferEntries) logQueue.removeFirst()
            if (level == "ERROR" || level == "WARN") {
                errorQueue.addLast(entry)
                while (errorQueue.size > maxErrorEntries) errorQueue.removeFirst()
            }
            hasPendingFlowUpdate = true
            writes.append(entry)
        }

        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }

        writerWakeup.trySend(Unit)
    }

    fun i(tag: String, message: String) = log(tag, message, "INFO")
    fun d(tag: String, message: String) = log(tag, message, "DEBUG")
    fun w(tag: String, message: String) = log(tag, message, "WARN")
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) anonymousReports?.recordError(throwable, when {
            tag.contains("qr", true) -> "QR"
            tag.contains("tor", true) -> "TOR"
            tag.contains("transfer", true) -> "TRANSFER"
            else -> "CONNECTION"
        })
        val fullMsg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(tag, fullMsg, "ERROR")
    }

    fun clear() {
        synchronized(bufferLock) {
            logQueue.clear()
            errorQueue.clear()
            hasPendingFlowUpdate = false
            _logsFlow.value = emptyList()
            _errorsFlow.value = emptyList()
            writes.clear()
        }
        writerWakeup.trySend(Unit)
        log("AppLogManager", "Журнал логов очищен")
    }

    fun generateDiagnosticReport(
        activeServerName: String? = null,
        activeServerProtocol: String? = null,
        routingMode: String? = null,
        dnsProvider: String? = null,
        vpnConnected: Boolean = false,
        ipDetails: String? = null,
        privateValues: Collection<String> = emptyList()
    ): String {
        val sb = StringBuilder()
        val now = synchronized(reportDateFormat) { reportDateFormat.format(Date()) }

        sb.appendLine("==========================================")
        sb.appendLine("       SplateVPN - ДИАГНОСТИЧЕСКИЙ ОТЧЕТ       ")
        sb.appendLine("==========================================")
        sb.appendLine("Дата и время: $now")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        sb.appendLine("Версия SplateVPN: ${version ?: "unknown"}")
        sb.appendLine("Ядро Xray: ${com.danieldk.splatevpn.SplateConstants.XRAY_VERSION}")
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

        val (recentErrors, recentLogs) = synchronized(bufferLock) {
            errorQueue.toList().takeLast(100) to logQueue.toList().takeLast(200)
        }
        if (recentErrors.isNotEmpty()) {
            sb.appendLine("------------------------------------------")
            sb.appendLine("СОХРАНЕННЫЕ ОШИБКИ И ПРЕДУПРЕЖДЕНИЯ (${recentErrors.size} записей):")
            sb.appendLine("------------------------------------------")
            for (err in recentErrors) {
                sb.appendLine(err.toString())
            }
        }

        sb.appendLine("------------------------------------------")
        sb.appendLine("ПОСЛЕДНИЕ СОБЫТИЯ И ЛОГИ СИСТЕМЫ (${recentLogs.size} записей):")
        sb.appendLine("------------------------------------------")

        for (entry in recentLogs) {
            sb.appendLine(entry.toString())
        }

        sb.appendLine("==========================================")
        sb.appendLine("           КОНЕЦ ОТЧЕТА                   ")
        sb.appendLine("==========================================")

        return DiagnosticRedactor.redact(sb.toString(), privateValues + listOfNotNull(activeServerName))
    }
}
