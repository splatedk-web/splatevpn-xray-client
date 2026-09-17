package com.danieldk.splatevpn.vpn

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import com.danieldk.splatevpn.data.AppLogManager
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XrayCoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogManager: AppLogManager
) {
    private val TAG = "XrayCoreManager"
    private var xrayProcess: Process? = null
    private var logJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun startCore(configJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            stopCore()

            ensureAssetsExtracted()

            val configFile = File(context.filesDir, "xray_config.json")
            configFile.writeText(configJson)
            appLogManager.i(TAG, "Конфигурация Xray записана (${configFile.length()} байт)")

            val xrayBin = getXrayBinary()
            if (xrayBin == null || !xrayBin.exists()) {
                val err = "Бинарный файл Xray не найден!"
                appLogManager.e(TAG, err)
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
                        // Xray сам указывает уровень в формате [Error], [Warning], [Info], [Debug]
                        // Используем его, а не поиск по словам (иначе "connection refused" в [Info] → false ERROR)
                        val level = when {
                            line.contains("[Error]", ignoreCase = false) ||
                            line.contains("[Critical]", ignoreCase = false) ||
                            line.contains("panic", ignoreCase = true) -> "ERROR"
                            line.contains("[Warning]", ignoreCase = false) -> "WARN"
                            else -> "INFO"
                        }
                        appLogManager.log("Xray", line, level)
                    }
                } catch (e: Exception) {
                    appLogManager.w(TAG, "Поток логов Xray закрыт: ${e.message}")
                }
            }

            // Ждём 600мс и проверяем что Xray не упал сразу (неверный конфиг и т.д.)
            Thread.sleep(600)
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                appLogManager.e(TAG, "Ядро Xray завершилось сразу после запуска (exit code: $exitCode). Проверьте конфигурацию.")
                logJob?.cancel()
                logJob = null
                xrayProcess = null
                return@withContext false
            }

            appLogManager.i(TAG, "Ядро Xray успешно запущено (PID процесса активен)")
            true
        } catch (e: Exception) {
            appLogManager.e(TAG, "Ошибка при запуске ядра Xray", e)
            false
        }
    }

    suspend fun stopCore() = withContext(Dispatchers.IO) {
        try {
            logJob?.cancel()
            logJob = null

            xrayProcess?.let { proc ->
                Log.d(TAG, "Stopping Xray process...")
                proc.destroy()
                var waited = 0
                while (proc.isAlive && waited < 400) {
                    Thread.sleep(50)
                    waited += 50
                }
                if (proc.isAlive) {
                    proc.destroyForcibly()
                    try {
                        proc.waitFor()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                Log.d(TAG, "Xray process terminated.")
            }
            xrayProcess = null

            // Force kill any orphaned Xray native processes (e.g. left behind after app update)
            try {
                Runtime.getRuntime().exec("pkill -9 -f libxray.so").waitFor()
            } catch (e: Exception) {
                // pkill might not be available on all OEM ROMs, ignore
            }

            // Ensure port 10808 is completely released before returning
            var portFree = false
            for (i in 0..6) {
                try {
                    java.net.ServerSocket(10808, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
                        portFree = true
                    }
                    if (portFree) break
                } catch (e: Exception) {
                    Thread.sleep(50)
                }
            }
            if (!portFree) {
                Log.w(TAG, "Warning: port 10808 still bound after stopping core")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
        }
    }

    fun isRunning(): Boolean {
        return xrayProcess?.isAlive == true
    }

    private fun getXrayBinary(): File? {
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

    private fun ensureAssetsExtracted() {
        val assetNames = listOf("geoip.dat", "geosite.dat")
        for (asset in assetNames) {
            val destFile = File(context.filesDir, asset)
            // Re-extract if file doesn't exist or is 0 bytes (corrupted / outdated)
            if (!destFile.exists() || destFile.length() == 0L) {
                try {
                    context.assets.open(asset).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted asset $asset to ${destFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract asset: $asset", e)
                }
            }
        }
    }
}
