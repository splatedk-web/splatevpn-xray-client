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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import com.danieldk.splatevpn.data.AppLogManager
import com.google.gson.JsonParser
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
    private val liveConnectionTracker: LiveConnectionTracker,
    private val tunnelHealthAdvisor: com.danieldk.splatevpn.data.TunnelHealthAdvisor,
    private val geoData: com.danieldk.splatevpn.data.GeoDataRepository = com.danieldk.splatevpn.data.GeoDataRepository(context)
) {
    private val TAG = "XrayCoreManager"
    @Volatile private var xrayProcess: Process? = null
    @Volatile private var activeProcessPid: Int? = null
    private var logJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var isIntentionallyStopped = false

    @Volatile var activeAssetDirectory: File? = null
        private set

    private var currentConfigJson: String? = null
    private var preparedTransports: PreparedTransports? = null
    @Volatile private var activeSocksPort: Int = SplateConstants.XRAY_SOCKS_PORT

    private fun extractSocksPort(configJson: String): Int = runCatching {
        val root = JsonParser.parseString(configJson).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")?.mapNotNull { it.asJsonObject }.orEmpty()
        inbounds.firstOrNull { it["tag"]?.asString == "socks-in" }?.get("port")?.asInt
            ?.takeIf { it in 1..65535 }
            ?: SplateConstants.XRAY_SOCKS_PORT
    }.getOrDefault(SplateConstants.XRAY_SOCKS_PORT)

    internal fun extractPid(process: Process): Int? {
        try {
            val pidMethod = process.javaClass.getMethod("pid")
            val pidVal = pidMethod.invoke(process)
            if (pidVal is Number) return pidVal.toInt()
        } catch (_: Throwable) { }
        try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            return field.getInt(process)
        } catch (_: Throwable) { }
        return null
    }

    internal fun isPortFree(port: Int, address: String = SplateConstants.XRAY_SOCKS_ADDRESS): Boolean {
        return try {
            java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(address), port), 1)
            }.use { true }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForPortRelease(
        port: Int = SplateConstants.XRAY_SOCKS_PORT,
        address: String = SplateConstants.XRAY_SOCKS_ADDRESS,
        timeoutMs: Long = 3000L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isPortFree(port, address)) return true
            delay(50)
        }
        return isPortFree(port, address)
    }

    suspend fun startCore(configJson: String): Boolean {
        val request = generation.incrementAndGet()
        return withContext(Dispatchers.IO) { lifecycleMutex.withLock {
        if (request != generation.get()) return@withLock false
        currentConfigJson = configJson
        isIntentionallyStopped = false
        startCoreInternal(configJson, attempt = 1)
        } }
    }

    private suspend fun startCoreInternal(configJson: String, attempt: Int, restartWatchdog: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val startingGeneration = generation.get()
        try {
            stopCoreInternal() // Ensure clean state before starting
            isIntentionallyStopped = false

            val targetSocksPort = extractSocksPort(configJson)
            activeSocksPort = targetSocksPort

            // Ensure target SOCKS port is strictly released before attempting to bind new instance
            val portAvailable = waitForPortRelease(port = targetSocksPort, timeoutMs = 2000L)
            if (!portAvailable) {
                val portErr = "Порт $targetSocksPort занят другим процессом перед запуском Xray!"
                appLogManager.e(TAG, portErr)
                vpnStatusManager.setState(VpnState.FAILED, portErr)
                return@withContext false
            }

            if (startingGeneration != generation.get()) {
                return@withContext false
            }

            ensureAssetsExtracted()

            val configFile = File(context.filesDir, "xray_config.json")
            val transports = PreparedTransports.prepare(context, configJson)
            preparedTransports = transports
            configFile.writeText(transports.json)
            appLogManager.i(TAG, "Конфигурация Xray записана (${configFile.length()} байт) [Attempt $attempt]")

            val xrayBin = getXrayBinary()
            if (xrayBin == null || !xrayBin.exists()) {
                val err = "Бинарный файл Xray не найден!"
                appLogManager.e(TAG, err)
                vpnStatusManager.setState(VpnState.FAILED, err)
                return@withContext false
            }

            appLogManager.i(TAG, "Запуск ядра Xray: ${xrayBin.absolutePath}")
            val assetDir = geoData.assetDirectory()
            activeAssetDirectory = assetDir
            val pb = ProcessBuilder(
                "sh", "-c",
                "export XRAY_LOCATION_ASSET='${assetDir.absolutePath}'; cd '${context.filesDir.absolutePath}'; exec '${xrayBin.absolutePath}' run -c '${configFile.absolutePath}'"
            )
            pb.directory(context.filesDir)
            pb.environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
            pb.redirectErrorStream(true)

            val process = pb.start()
            xrayProcess = process
            activeProcessPid = extractPid(process)

            logJob = scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (!isActive || xrayProcess !== process) break
                        parseAndLogXrayLine(line)
                    }
                } catch (e: Exception) {
                    appLogManager.w(TAG, "Поток логов Xray закрыт: ${e.message}")
                }
            }

            val expectedCredentials = runCatching {
                val root = JsonParser.parseString(configJson).asJsonObject
                val inbounds = root.getAsJsonArray("inbounds")
                inbounds?.mapNotNull { it.asJsonObject }?.firstOrNull {
                    it["tag"]?.asString == "socks-in" || it["port"]?.asInt == targetSocksPort
                }?.let { inbound ->
                    val settings = inbound.getAsJsonObject("settings")
                    if (settings?.get("auth")?.asString == "password") {
                        val accounts = settings.getAsJsonArray("accounts")
                        val first = accounts?.firstOrNull()?.asJsonObject
                        val user = first?.get("user")?.asString
                        val pass = first?.get("pass")?.asString
                        if (!user.isNullOrBlank() && pass != null) user to pass else null
                    } else null
                }
            }.getOrNull() ?: LocalProxyAccess.active?.let { it.username to it.password }

            // An alive process may still be loading geodata. Wait for the actual local listener.
            val ready = withTimeoutOrNull(15_000L) {
                while (process.isAlive && startingGeneration == generation.get()) {
                    val listening = try {
                        java.net.Socket().use { socket ->
                            socket.soTimeout = 500
                            socket.connect(
                                java.net.InetSocketAddress(SplateConstants.XRAY_SOCKS_ADDRESS, targetSocksPort),
                                200
                            )
                            LocalProxyAccess.probeReadiness(socket, expectedCredentials)
                        }
                    } catch (_: java.io.IOException) { false }
                    if (listening && process.isAlive && startingGeneration == generation.get()) {
                        return@withTimeoutOrNull true
                    }
                    delay(50)
                }
                false
            } == true
            if (startingGeneration != generation.get()) {
                withContext(NonCancellable) { stopCoreInternal() }
                return@withContext false
            }

            if (!ready || !process.isAlive) {
                val error = if (process.isAlive) "Xray SOCKS listener startup timed out" else "Xray exited with code ${process.exitValue()}"
                appLogManager.e(TAG, "Ядро Xray не готово: $error")
                stopCoreInternal()
                if (attempt == 1 && startingGeneration == generation.get()) {
                    if (assetDir != context.filesDir) {
                        appLogManager.w(TAG, "Updated geodata startup failed; restoring previous databases")
                        geoData.rollback()
                    }
                    return@withContext startCoreInternal(configJson, attempt = 2, restartWatchdog = restartWatchdog)
                }
                vpnStatusManager.setState(VpnState.FAILED, error)
                return@withContext false
            }

            appLogManager.i(TAG, "Ядро Xray успешно запущено (PID=${activeProcessPid ?: "unknown"} активен)")

            // Start Watchdog
            if (restartWatchdog) startWatchdog()

            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopCoreInternal() }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { stopCoreInternal() }
            appLogManager.e(TAG, "Ошибка при запуске ядра Xray", e)
            vpnStatusManager.setState(VpnState.FAILED, e.message)
            false
        }
    }

    private val logPolicy = XrayLogPolicy()

    private fun parseAndLogXrayLine(rawLine: String) {
        tunnelHealthAdvisor.inspectXrayLog(rawLine)
        // If this is a real-time connection routing entry, record it in LiveConnectionTracker
        val isAccessLog = liveConnectionTracker.recordLine(rawLine)
        if (isAccessLog) {
            return
        }
        val line = logPolicy.select(rawLine) ?: return

        val lowerLine = line.lowercase()
        var level = "INFO"

        when {
            Regex("\\[warning] core: xray [^\\s]+ started$").containsMatchIn(lowerLine) -> level = "INFO"
            lowerLine.contains("cannot find the pending request") -> {
                // Безобидное уведомление Xray ядра о запоздалом/дублированном UDP DNS ответе, не является ошибкой приложения
                level = "DEBUG"
            }
            lowerLine.contains("read tcp 127.0.0.1:") && lowerLine.contains("connection reset") -> {
                // Штатное закрытие локального сокета приложением Android при завершении HTTP-запроса или закрытии вкладки
                level = "INFO"
            }
            lowerLine.contains("address already in use") || lowerLine.contains("bind") && lowerLine.contains("in use") -> {
                level = "ERROR"
                appLogManager.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА: Порт Xray занят (Address already in use). Возможен конфликт.")
                vpnStatusManager.setState(VpnState.FAILED, "Port already in use")
            }
            lowerLine.contains("tls: user canceled") || lowerLine.contains("read: connection reset") ||
                (lowerLine.contains("handshake") && (lowerLine.contains("failed") || lowerLine.contains("error") || lowerLine.contains("rejected"))) -> {
                level = "WARN"
            }
            lowerLine.contains("i/o timeout") || lowerLine.contains("eof") -> {
                level = "WARN"
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
        val expectedGeneration = generation.get()
        watchdogJob = scope.launch {
            val restartBudget = CoreRestartBudget().apply { started() }
            var connectedId = vpnStatusManager.connectedServerId
            var connectedProfile = vpnStatusManager.connectedServerProfile
            val maxRetries = restartBudget.maxRetries

            while (isActive) {
                delay(2000)
                if (isIntentionallyStopped || expectedGeneration != generation.get()) break

                val proc = xrayProcess
                if ((proc == null || !proc.isAlive || preparedTransports?.alive == false) && currentConfigJson != null) {
                    val mayRestart = restartBudget.crashed()
                    val crashCount = restartBudget.failures

                    if (!mayRestart) {
                        appLogManager.e(TAG, "Watchdog: Превышен лимит перезапусков Xray ($maxRetries). Остановка.")
                        lifecycleMutex.withLock {
                            if (expectedGeneration == generation.get()) {
                                vpnStatusManager.setState(VpnState.FAILED, "Xray crashed continuously")
                            }
                        }
                        break
                    }

                    appLogManager.e(TAG, "Watchdog: Ядро Xray неожиданно завершилось! (Попытка $crashCount из $maxRetries)")

                    connectedId = vpnStatusManager.connectedServerId ?: connectedId
                    connectedProfile = vpnStatusManager.connectedServerProfile ?: connectedProfile

                    // Exponential backoff
                    val backoff = min(15000L, (1000L * (1 shl (crashCount - 1))))
                    appLogManager.i(TAG, "Watchdog: Ожидание ${backoff}мс перед перезапуском...")
                    delay(backoff)

                    lifecycleMutex.withLock {
                        if (!isIntentionallyStopped && expectedGeneration == generation.get() && currentConfigJson != null) {
                            vpnStatusManager.setState(VpnState.RECONNECTING)
                            appLogManager.i(TAG, "Watchdog: Перезапуск ядра Xray (попытка $crashCount из $maxRetries)...")
                            val restarted = startCoreInternal(currentConfigJson!!, attempt = crashCount + 1, restartWatchdog = false)
                            if (expectedGeneration != generation.get()) return@withLock
                            if (restarted) {
                                val isHealthy = vpnStatusManager.performHealthCheck()
                                if (expectedGeneration != generation.get()) return@withLock
                                if (isHealthy) {
                                    restartBudget.started()
                                    appLogManager.i(TAG, "Watchdog: Xray успешно перезапущен, трафик подтверждён.")
                                } else {
                                    appLogManager.w(TAG, "Watchdog: ядро запущено, но проверка трафика не прошла")
                                }
                                // Keep tunnel monitoring alive even when this node fails the restart probe.
                                vpnStatusManager.setState(VpnState.CONNECTED)
                                vpnStatusManager.connectedServerId = connectedId
                                vpnStatusManager.connectedServerProfile = connectedProfile
                            } else {
                                appLogManager.e(TAG, "Watchdog: Не удалось перезапустить ядро Xray (попытка $crashCount из $maxRetries)")
                                if (restartBudget.isExhausted()) {
                                    vpnStatusManager.setState(VpnState.FAILED, "Xray failed to restart after $maxRetries attempts")
                                    return@withLock
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun generationToken(): Long = generation.get()

    suspend fun stopCore(expectedGeneration: Long? = null) {
        val request = if (expectedGeneration == null) generation.incrementAndGet() else {
            if (!generation.compareAndSet(expectedGeneration, expectedGeneration + 1)) return
            expectedGeneration + 1
        }
        withContext(NonCancellable + Dispatchers.IO) { lifecycleMutex.withLock {
        if (request != generation.get()) return@withLock
        isIntentionallyStopped = true
        watchdogJob?.cancel()
        watchdogJob = null
        currentConfigJson = null
        liveConnectionTracker.clear()
        stopCoreInternal()
        } }
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
                if (args.any { it.endsWith("/libxray.so") || it.endsWith("/xray") } && mainConfig in args) entry.name.toInt() else null
            } catch (_: Exception) { null }
        }
    }

    private suspend fun stopCoreInternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            logJob?.cancel()
            logJob = null
            val transports = preparedTransports
            preparedTransports = null
            runCatching { transports?.close() }.onFailure { appLogManager.e(TAG, "AWG cleanup failed: ${it.javaClass.simpleName}") }

            val proc = xrayProcess
            val pid = activeProcessPid
            if (proc != null) {
                // Signal before closing Java streams: destroy() can block behind a reader
                // when a native process is stalled.
                if (pid != null) {
                    try { android.system.Os.kill(pid, android.system.OsConstants.SIGTERM) } catch (_: Exception) { }
                }
                val ownedPids = findOwnedMainCorePids()
                ownedPids.forEach { p ->
                    try { android.system.Os.kill(p, android.system.OsConstants.SIGTERM) } catch (_: Exception) { }
                }

                proc.destroy()

                var waited = 0
                while (proc.isAlive && waited < 1000) {
                    delay(50)
                    waited += 50
                }
                if (proc.isAlive) {
                    if (pid != null) {
                        try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
                    }
                    ownedPids.forEach { p ->
                        try { android.system.Os.kill(p, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
                    }
                    proc.destroyForcibly()
                }
                try {
                    proc.waitFor(1500, TimeUnit.MILLISECONDS)
                } catch (_: Exception) { }
                Log.d(TAG, "Xray process terminated.")
            }
            xrayProcess = null
            activeProcessPid = null

            // Recover a main core orphaned by a previous app process without killing probes.
            findOwnedMainCorePids().forEach { p ->
                try { android.system.Os.kill(p, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
            }

            // Ensure SOCKS port is completely released before returning
            val portToRelease = activeSocksPort
            val portFree = waitForPortRelease(port = portToRelease, timeoutMs = 3000L)
            if (!portFree) {
                Log.w(TAG, "Warning: port $portToRelease still bound after stopping core, retrying kill...")
                if (pid != null) {
                    try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
                }
                findOwnedMainCorePids().forEach { p ->
                    try { android.system.Os.kill(p, android.system.OsConstants.SIGKILL) } catch (_: Exception) { }
                }
                return@withContext waitForPortRelease(port = portToRelease, timeoutMs = 1500L)
            }
            return@withContext true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
            return@withContext false
        }
    }

    fun isRunning(): Boolean {
        return xrayProcess?.isAlive == true && preparedTransports?.alive != false
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
