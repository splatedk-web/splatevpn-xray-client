package com.danieldk.splatevpn.vpn

import android.content.Context
import android.util.Log
import com.danieldk.splatevpn.SplateConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import com.danieldk.splatevpn.data.AppLogManager
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import java.util.concurrent.TimeUnit

@Singleton
class XrayCoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogManager: AppLogManager,
    private val vpnStatusManager: VpnStatusManager,
    private val liveConnectionTracker: LiveConnectionTracker
) {
    private val TAG = "XrayCoreManager"
    private var xrayProcess: Process? = null
    private var logJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isIntentionallyStopped = false

    private var currentConfigJson: String? = null

    suspend fun startCore(configJson: String): Boolean = withContext(Dispatchers.IO) {
        currentConfigJson = configJson
        isIntentionallyStopped = false
        startCoreInternal(configJson, attempt = 1)
    }

    private suspend fun startCoreInternal(configJson: String, attempt: Int, restartWatchdog: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            stopCoreInternal() // Ensure clean state before starting
            isIntentionallyStopped = false

            ensureAssetsExtracted()

            val configFile = File(context.filesDir, "xray_config.json")
            configFile.writeText(configJson)
            appLogManager.i(TAG, "Конфигурация Xray записана (${configFile.length()} байт) [Attempt $attempt]")

            val xrayBin = getXrayBinary()
            if (xrayBin == null || !xrayBin.exists()) {
                val err = "Бинарный файл Xray не найден!"
                appLogManager.e(TAG, err)
                vpnStatusManager.setState(VpnState.FAILED, err)
                return@withContext false
            }

            appLogManager.i(TAG, "Запуск ядра Xray: ${xrayBin.absolutePath}")
            val pb = ProcessBuilder(
                "sh", "-c",
                "export XRAY_LOCATION_ASSET='${context.filesDir.absolutePath}'; cd '${context.filesDir.absolutePath}'; exec '${xrayBin.absolutePath}' run -c '${configFile.absolutePath}'"
            )
            pb.directory(context.filesDir)
            pb.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            pb.redirectErrorStream(true)

            val process = pb.start()
            xrayProcess = process

            logJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        parseAndLogXrayLine(line)
                    }
                } catch (e: Exception) {
                    appLogManager.w(TAG, "Поток логов Xray закрыт: ${e.message}")
                }
            }

            // An alive process may still be loading geodata. Wait for the actual local listener.
            val ready = withTimeoutOrNull(8_000L) {
                while (process.isAlive) {
                    val listening = try {
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, SplateConstants.XRAY_SOCKS_PORT), 150)
                        }
                        true
                    } catch (_: java.io.IOException) { false }
                    if (listening) return@withTimeoutOrNull true
                    delay(50)
                }
                false
            } == true
            if (!ready || !process.isAlive) {
                val error = if (process.isAlive) "Xray SOCKS listener startup timed out" else "Xray exited with code ${process.exitValue()}"
                appLogManager.e(TAG, "Ядро Xray не готово: $error")
                stopCoreInternal()
                vpnStatusManager.setState(VpnState.FAILED, error)
                return@withContext false
            }

            appLogManager.i(TAG, "Ядро Xray успешно запущено (PID процесса активен)")

            // Start Watchdog
            if (restartWatchdog) startWatchdog()

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogManager.e(TAG, "Ошибка при запуске ядра Xray", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
            false
        }
    }

    private fun parseAndLogXrayLine(line: String) {
        // If this is a real-time connection routing entry, record it in LiveConnectionTracker
        val isAccessLog = liveConnectionTracker.recordLine(line)
        if (isAccessLog) {
            return
        }

        val lowerLine = line.lowercase()
        var level = "INFO"

        when {
            lowerLine.contains("cannot find the pending request") -> {
                // Безобидное уведомление Xray ядра о запоздалом/дублированном UDP DNS ответе, не является ошибкой приложения
                level = "DEBUG"
            }
            lowerLine.contains("address already in use") || lowerLine.contains("bind") && lowerLine.contains("in use") -> {
                level = "ERROR"
                appLogManager.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА: Порт Xray занят (Address already in use). Возможен конфликт.")
                vpnStatusManager.setState(VpnState.FAILED, "Port already in use")
            }
            lowerLine.contains("tls: user canceled") || lowerLine.contains("read: connection reset") || lowerLine.contains("handshake") -> {
                level = "WARN"
                appLogManager.w(TAG, "Ошибка Handshake/TLS: Возможно несовпадение REALITY ключей, блокировка ТСПУ или нестабильная сеть.")
            }
            lowerLine.contains("i/o timeout") || lowerLine.contains("eof") -> {
                level = "WARN"
                appLogManager.w(TAG, "Таймаут или обрыв соединения (Network Drop / Timeout).")
            }
            line.contains("[Error]", ignoreCase = false) || line.contains("[Critical]", ignoreCase = false) || lowerLine.contains("panic") -> {
                level = "ERROR"
            }
            line.contains("[Warning]", ignoreCase = false) -> {
                level = "WARN"
            }
        }

        appLogManager.log("Xray", line, level)
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var crashCount = 0
            val maxRetries = 5

            while (isActive) {
                delay(2000)
                if (isIntentionallyStopped) break

                val proc = xrayProcess
                if ((proc == null || !proc.isAlive) && currentConfigJson != null) {
                    crashCount++
                    appLogManager.e(TAG, "Watchdog: Ядро Xray неожиданно завершилось! (Попытка $crashCount из $maxRetries)")

                    if (crashCount > maxRetries) {
                        appLogManager.e(TAG, "Watchdog: Превышен лимит перезапусков Xray. Остановка.")
                        vpnStatusManager.setState(VpnState.FAILED, "Xray crashed continuously")
                        break
                    }

                    vpnStatusManager.setState(VpnState.RECONNECTING)

                    // Exponential backoff
                    val backoff = min(15000L, (1000L * (1 shl (crashCount - 1))))
                    appLogManager.i(TAG, "Watchdog: Ожидание ${backoff}мс перед перезапуском...")
                    delay(backoff)

                    if (!isIntentionallyStopped && currentConfigJson != null) {
                        appLogManager.i(TAG, "Watchdog: Перезапуск ядра Xray...")
                        val restarted = startCoreInternal(currentConfigJson!!, attempt = crashCount + 1, restartWatchdog = false)
                        if (restarted) {
                            appLogManager.i(TAG, "Watchdog: Xray успешно перезапущен.")
                            val isHealthy = vpnStatusManager.performHealthCheck()
                            // Keep tunnel monitoring alive even when this node fails the restart probe.
                            vpnStatusManager.setState(VpnState.CONNECTED)
                            if (isHealthy) crashCount = 0
                        }
                    }
                }
            }
        }
    }

    suspend fun stopCore() = withContext(Dispatchers.IO) {
        isIntentionallyStopped = true
        watchdogJob?.cancel()
        watchdogJob = null
        currentConfigJson = null
        liveConnectionTracker.clear()
        stopCoreInternal()
    }

    private fun findOwnedMainCorePids(): List<Int> {
        val mainConfig = File(context.filesDir, "xray_config.json").absolutePath
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            if (!entry.name.all { it.isDigit() }) return@mapNotNull null
            try {
                if (android.system.Os.stat(entry.absolutePath).st_uid != android.os.Process.myUid()) {
                    return@mapNotNull null
                }
                val args = File(entry, "cmdline").readText().split('\u0000')
                if (args.any { it.endsWith("/libxray.so") } && mainConfig in args) entry.name.toInt() else null
            } catch (_: Exception) { null }
        }
    }

    private suspend fun stopCoreInternal() = withContext(Dispatchers.IO) {
        try {
            logJob?.cancel()
            logJob = null

            xrayProcess?.let { proc ->
                // Signal before closing Java streams: destroy() can block behind a reader
                // when a native process is stalled. Only target this app's main config.
                val ownedPids = findOwnedMainCorePids()
                ownedPids.forEach { pid ->
                    try { android.system.Os.kill(pid, android.system.OsConstants.SIGTERM) } catch (_: Exception) { }
                }
                var waited = 0
                while (proc.isAlive && waited < 400) {
                    delay(100)
                    waited += 100
                }
                if (proc.isAlive) {
                    ownedPids.forEach { pid ->
                        try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
                    }
                    proc.destroyForcibly()
                } else {
                    proc.destroy()
                }
                proc.waitFor(1, TimeUnit.SECONDS)
                Log.d(TAG, "Xray process terminated.")
            }
            xrayProcess = null

            // Recover a main core orphaned by a previous app process without killing probes.
            findOwnedMainCorePids().forEach { pid ->
                try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
            }

            // Ensure SOCKS port is completely released before returning
            var portFree = false
            for (i in 0..10) { // Check up to 1 second
                try {
                    java.net.ServerSocket(SplateConstants.XRAY_SOCKS_PORT, 1, java.net.InetAddress.getByName(SplateConstants.XRAY_SOCKS_ADDRESS)).use {
                        portFree = true
                    }
                    if (portFree) break
                } catch (e: Exception) {
                    delay(100)
                }
            }
            if (!portFree) {
                Log.w(TAG, "Warning: port ${SplateConstants.XRAY_SOCKS_PORT} still bound after stopping core")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
        }
    }

    fun isRunning(): Boolean {
        return xrayProcess?.isAlive == true
    }

    fun getXrayBinary(): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libXray = File(nativeDir, "libxray.so")
        if (libXray.exists()) {
            return libXray
        }

        // Fallback: check filesDir/xray
        val fallbackBin = File(context.filesDir, "xray")
        if (fallbackBin.exists()) {
            fallbackBin.setExecutable(true)
            return fallbackBin
        }

        return null
    }

    @Synchronized
    fun ensureAssetsExtracted() {
        val prefs = context.getSharedPreferences("xray_assets_prefs", Context.MODE_PRIVATE)
        val currentVersion = try {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }

        val lastExtractedVersion = prefs.getLong("last_extracted_version", -1L)
        val lastGeoDataRevision = prefs.getString("geodata_revision", null)
        val forceReExtract = currentVersion != lastExtractedVersion ||
            lastGeoDataRevision != SplateConstants.GEO_DATA_REVISION

        val assetNames = listOf("geoip.dat", "geosite.dat")
        var allExtracted = true
        for (asset in assetNames) {
            val destFile = File(context.filesDir, asset)
            // Re-extract if app updated, file doesn't exist, or is 0 bytes
            if (forceReExtract || !destFile.exists() || destFile.length() == 0L) {
                try {
                    context.assets.open(asset).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted asset $asset to ${destFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract asset: $asset", e)
                    allExtracted = false
                }
            }
        }
        if (allExtracted && currentVersion > 0L) {
            prefs.edit()
                .putLong("last_extracted_version", currentVersion)
                .putString("geodata_revision", SplateConstants.GEO_DATA_REVISION)
                .apply()
        }
    }
}
