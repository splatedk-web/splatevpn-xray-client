package com.danieldk.splatevpn.data

import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val isDirectApk: Boolean = true,
    val apkFileName: String? = null
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadState
    data class Completed(val file: File) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}

internal data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("body") val body: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("assets") val assets: List<GithubAsset>?
)

internal data class GithubAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val browserDownloadUrl: String
)

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val apiUrl = "https://api.github.com/repos/splatedk-web/splatevpn-xray-client/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    /**
     * Checks for updates by querying the GitHub API.
     * Compares the remote tag with the local version name.
     * Returns UpdateInfo if an update is available, null otherwise.
     */
    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "SplateVPN-Client")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val release = gson.fromJson(responseText, GithubRelease::class.java)

                val remoteVersion = release.tagName.removePrefix("v").trim()
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val currentVersion = pInfo.versionName?.removePrefix("v")?.trim() ?: ""

                if (isNewerVersion(currentVersion, remoteVersion)) {
                    val apkAsset = selectBestApkAsset(release.assets)
                    val downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl
                    val isApk = apkAsset != null || downloadUrl.endsWith(".apk", ignoreCase = true)
                    val apkFileName = apkAsset?.name ?: "splatevpn_${remoteVersion}.apk"

                    // Check if already downloaded
                    val existingFile = getDownloadedApkFile(remoteVersion, apkFileName)
                    if (existingFile != null && _downloadState.value !is UpdateDownloadState.Downloading) {
                        _downloadState.value = UpdateDownloadState.Completed(existingFile)
                    }

                    return@withContext UpdateInfo(
                        version = remoteVersion,
                        changelog = release.body ?: "",
                        downloadUrl = downloadUrl,
                        releaseUrl = release.htmlUrl,
                        isDirectApk = isApk,
                        apkFileName = apkFileName
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    /**
     * Returns the update directory where APK files are stored.
     */
    private fun getUpdatesDir(): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        return File(baseDir, "updates").apply { mkdirs() }
    }

    /**
     * Checks if the APK file for this version has already been completely downloaded.
     */
    /**
     * Checks if the APK file for this version has already been completely downloaded.
     */
    fun getDownloadedApkFile(version: String, fileName: String? = null): File? {
        val targetName = if (!fileName.isNullOrBlank()) fileName else "splatevpn_$version.apk"
        val file = File(getUpdatesDir(), targetName)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Selects the best APK asset matching the device architecture.
     * Evaluates Build.SUPPORTED_ABIS in priority order (e.g. arm64-v8a, armeabi-v7a, x86_64).
     */
    internal fun selectBestApkAsset(assets: List<GithubAsset>?): GithubAsset? {
        if (assets.isNullOrEmpty()) return null
        val apkAssets = assets.filter {
            it.name?.endsWith(".apk", ignoreCase = true) == true ||
                    it.browserDownloadUrl.endsWith(".apk", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()

        // 1. Try finding an exact match for each supported ABI in priority order
        for (abi in supportedAbis) {
            val normalizedAbi = abi.lowercase()
            val match = apkAssets.firstOrNull { asset ->
                val name = (asset.name ?: asset.browserDownloadUrl).lowercase()
                matchesAbi(name, normalizedAbi)
            }
            if (match != null) {
                Log.i("UpdateManager", "Выбран APK обновления для архитектуры $abi: ${match.name}")
                return match
            }
        }

        // 2. Try universal / all APK, or generic APK without specific ABI tag (e.g. splvpn.apk, universal, etc.)
        val universalMatch = apkAssets.firstOrNull { asset ->
            val name = (asset.name ?: asset.browserDownloadUrl).lowercase()
            name.contains("universal") || name.contains("all") || name.contains("splvpn") ||
                    (!name.contains("arm") && !name.contains("v7") && !name.contains("v8") && !name.contains("x86"))
        }
        if (universalMatch != null) {
            Log.i("UpdateManager", "Выбран универсальный APK обновления: ${universalMatch.name}")
            return universalMatch
        }

        // 3. Fallback to first APK asset
        Log.w("UpdateManager", "Архитектурный APK не найден. Используется первый доступный: ${apkAssets.first().name}")
        return apkAssets.first()
    }

    internal fun matchesAbi(fileName: String, targetAbi: String): Boolean {
        return when (targetAbi) {
            "arm64-v8a" -> {
                (fileName.contains("arm64-v8a") || fileName.contains("arm64") || fileName.contains("v8a") || fileName.contains("armv8")) &&
                        !fileName.contains("v7") && !fileName.contains("armeabi-v7a")
            }
            "armeabi-v7a" -> {
                (fileName.contains("armeabi-v7a") || fileName.contains("armv7") || fileName.contains("v7a") || fileName.contains("armeabi") || fileName.contains("arm32")) &&
                        !fileName.contains("arm64") && !fileName.contains("v8")
            }
            "x86_64" -> {
                fileName.contains("x86_64") || fileName.contains("x86-64") || fileName.contains("x64") || fileName.contains("amd64")
            }
            "x86" -> {
                fileName.contains("x86") && !fileName.contains("x86_64") && !fileName.contains("x86-64")
            }
            else -> fileName.contains(targetAbi)
        }
    }

    /**
     * Starts downloading the APK update with progress reporting.
     */
    fun startDownload(info: UpdateInfo) {
        if (!info.isDirectApk) {
            // Open in browser if not a direct APK link
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        // Check if already downloaded
        val targetFileName = info.apkFileName ?: "splatevpn_${info.version}.apk"
        val existing = getDownloadedApkFile(info.version, targetFileName)
        if (existing != null) {
            _downloadState.value = UpdateDownloadState.Completed(existing)
            installApk(existing)
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val updatesDir = getUpdatesDir()
            val destFile = File(updatesDir, targetFileName)
            val tempFile = File(updatesDir, "$targetFileName.tmp")

            var connection: HttpURLConnection? = null
            var currentUrl = info.downloadUrl

            try {
                _downloadState.value = UpdateDownloadState.Downloading(0f, 0L, -1L)

                // Follow redirects manually if needed (GitHub asset redirects to AWS S3/CDN)
                var redirects = 0
                var responseCode: Int
                while (true) {
                    val url = URL(currentUrl)
                    if (url.protocol != "https") throw IOException("Update download requires HTTPS")
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "SplateVPN-Client")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                    connection.connect()
                    responseCode = connection.responseCode
                    if (responseCode in 301..308 && responseCode != 304) {
                        val newLocation = connection.getHeaderField("Location") ?: break
                        connection.disconnect()
                        currentUrl = URL(url, newLocation).toExternalForm()
                        redirects++
                        if (redirects > 5) break
                    } else {
                        break
                    }
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $responseCode: ${connection?.responseMessage ?: "Download error"}")
                }

                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L

                if (tempFile.exists()) tempFile.delete()

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 150 || downloadedBytes == totalBytes) {
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else {
                                    -1f
                                }
                                _downloadState.value = UpdateDownloadState.Downloading(
                                    progress = progress,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes
                                )
                                lastEmitTime = now
                            }
                        }
                    }
                }

                ensureActive()
                if (downloadedBytes == 0L || (totalBytes >= 0 && downloadedBytes != totalBytes)) {
                    throw IOException("Incomplete update download")
                }
                val archive = context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)
                if (archive?.packageName != context.packageName) {
                    throw IOException("Invalid update APK")
                }
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    throw IOException("Failed to save downloaded update file")
                }

                _downloadState.value = UpdateDownloadState.Completed(destFile)

                // Prompt user to install immediately upon download completion
                withContext(Dispatchers.Main) {
                    installApk(destFile)
                }
            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = UpdateDownloadState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = UpdateDownloadState.Error(e.localizedMessage ?: "Download failed")
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Cancels active update download.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = UpdateDownloadState.Idle
    }

    /**
     * Resets the download state to Idle.
     */
    fun resetDownloadState() {
        _downloadState.value = UpdateDownloadState.Idle
    }

    /**
     * Checks if the app has permission to install unknown apps (Android 8.0+).
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens system settings to grant unknown apps installation permission for this app.
     */
    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Triggers the Android package installer to install the downloaded APK file.
     */
    fun installApk(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }

            if (!canInstallPackages()) {
                Toast.makeText(context, "Please allow installing apps from this source", Toast.LENGTH_LONG).show()
                openInstallSettings()
                return
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Install error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(currentParts.size, remoteParts.size)
            for (i in 0 until maxLength) {
                val c = currentParts.getOrElse(i) { 0 }
                val r = remoteParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (e: Exception) {
            return remote > current
        }
        return false
    }
}
