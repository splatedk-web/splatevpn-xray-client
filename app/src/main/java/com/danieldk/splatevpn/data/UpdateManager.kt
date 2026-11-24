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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val isDirectApk: Boolean = true,
    val apkFileName: String? = null,
    val apkSize: Long? = null,
    val apkSha256: String? = null
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object WifiRequired : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadState
    data class Completed(val file: File) : UpdateDownloadState
    data class Error(val message: String, val apkIssue: UpdateApkIssue? = null) : UpdateDownloadState
}

enum class UpdateApkIssue {
    INCOMPLETE, INTEGRITY, UNREADABLE, PACKAGE_OR_VERSION, SIGNATURE
}

private class UpdateApkException(val issue: UpdateApkIssue) : IOException(issue.name)

internal data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("body") val body: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("assets") val assets: List<GithubAsset>?
)

internal data class GithubAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val browserDownloadUrl: String,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("digest") val digest: String? = null
)

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val apiUrl = "https://api.github.com/repos/splatedk-web/splatevpn-xray-client/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val downloadMutex = Mutex()
    private data class VerifiedApk(val path: String, val length: Long, val modifiedAt: Long, val version: String)
    @Volatile private var verifiedApk: VerifiedApk? = null

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    suspend fun checkUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!NetworkAccessPolicy.updatesAllowed(context)) return@withContext UpdateCheckResult.WifiRequired
        var connection: HttpURLConnection? = null
        val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val routeWatcher = scope.launch {
            while (isActive) {
                delay(200)
                if (!NetworkAccessPolicy.updatesAllowed(context)) { runCatching { activeConnection.get()?.disconnect() }; break }
            }
        }
        try {
            NetworkAccessPolicy.requireUpdates(context)
            val url = URL(apiUrl)
            connection = url.openConnection() as HttpURLConnection
            activeConnection.set(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "SplateVPN-Client")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                NetworkAccessPolicy.requireUpdates(context)
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

                val versionPattern = Regex("[0-9]+(?:\\.[0-9]+)*(?:[-+][A-Za-z0-9.+_-]+)?")
                if (!versionPattern.matches(remoteVersion) || !versionPattern.matches(currentVersion)) {
                    return@withContext UpdateCheckResult.Failed
                }
                if (isNewerVersion(currentVersion, remoteVersion)) {
                    val apkAsset = selectBestApkAsset(release.assets)
                    val downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl
                    val isApk = apkAsset != null || downloadUrl.endsWith(".apk", ignoreCase = true)
                    val apkFileName = apkAsset?.name ?: "splatevpn_${remoteVersion}.apk"

                    val existingFile = getDownloadedApkFile(remoteVersion, apkFileName)
                    if (existingFile != null && _downloadState.value !is UpdateDownloadState.Downloading) {
                        _downloadState.value = UpdateDownloadState.Completed(existingFile)
                    } else if (existingFile == null && _downloadState.value is UpdateDownloadState.Completed) {
                        _downloadState.value = UpdateDownloadState.Idle
                    }

                    return@withContext UpdateCheckResult.Available(UpdateInfo(
                        version = remoteVersion,
                        changelog = release.body ?: "",
                        downloadUrl = downloadUrl,
                        releaseUrl = release.htmlUrl,
                        isDirectApk = isApk,
                        apkFileName = apkFileName,
                        apkSize = apkAsset?.size?.takeIf { it > 0 },
                        apkSha256 = UpdateFilePolicy.sha256FromAsset(apkAsset?.digest)
                    ))
                }
                return@withContext UpdateCheckResult.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: WifiRequiredException) {
            return@withContext UpdateCheckResult.WifiRequired
        } catch (e: Exception) {
            Log.w("UpdateManager", "Update check failed", e)
        } finally {
            routeWatcher.cancel()
            connection?.disconnect()
        }
        if (NetworkAccessPolicy.updatesAllowed(context)) UpdateCheckResult.Failed else UpdateCheckResult.WifiRequired
    }

    private fun getUpdatesDir(): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        return File(baseDir, "updates").apply { mkdirs() }
    }

    fun getDownloadedApkFile(version: String, fileName: String? = null): File? {
        val file = runCatching { UpdateFilePolicy.cachedApk(getUpdatesDir(), version, fileName) }.getOrNull() ?: return null
        if (!file.isFile || file.length() == 0L) return null
        val fingerprint = apkFingerprint(file, version)
        if (verifiedApk == fingerprint) return file
        if (inspectUpdateApk(file, version) == null && apkFingerprint(file, version) == fingerprint) {
            verifiedApk = fingerprint
            return file
        }
        if (verifiedApk?.path == file.absolutePath) verifiedApk = null
        // An invalid cache entry must not trap the update button in repeated install failures.
        runCatching { file.delete() }
        return null
    }

    private fun apkFingerprint(file: File, version: String) =
        VerifiedApk(file.absolutePath, file.length(), file.lastModified(), version.trim().removePrefix("v"))

    internal fun inspectUpdateApk(file: File, version: String): UpdateApkIssue? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val archive = runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: return UpdateApkIssue.UNREADABLE
        if (!UpdateFilePolicy.matchesApk(context.packageName, version, archive.packageName, archive.versionName)) {
            return UpdateApkIssue.PACKAGE_OR_VERSION
        }
        val currentInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, flags)
            }
        }.getOrNull() ?: return UpdateApkIssue.SIGNATURE

        val currentSignatures = getSignatures(currentInfo)
        val apkSignatures = getSignatures(archive)
        val matches = UpdateFilePolicy.hasMatchingCertificate(currentSignatures, apkSignatures)
        if (!matches) Log.w("UpdateManager", "Missing or mismatched update signing certificate")
        return if (matches) null else UpdateApkIssue.SIGNATURE
    }

    private fun getSignatures(info: android.content.pm.PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val sigs = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            return sigs?.map { it.toByteArray() } ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            return info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
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

    fun openReleasePage(info: UpdateInfo) {
        val uri = Uri.parse(info.releaseUrl)
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true)) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: android.content.ActivityNotFoundException) {
            Log.w("UpdateManager", "No browser available to open release", error)
        }
    }

    fun startDownload(info: UpdateInfo) {
        if (!info.isDirectApk) {
            if (!NetworkAccessPolicy.updatesAllowed(context)) {
                _downloadState.value = UpdateDownloadState.Error(WifiRequiredException().message.orEmpty())
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        val destination = try { UpdateFilePolicy.cachedApk(getUpdatesDir(), info.version, info.apkFileName) }
        catch (error: Exception) {
            _downloadState.value = UpdateDownloadState.Error(error.message.orEmpty())
            return
        }
        val existing = getDownloadedApkFile(info.version, info.apkFileName)
        if (existing != null) {
            _downloadState.value = UpdateDownloadState.Completed(existing)
            installApk(existing)
            return
        }
        if (!NetworkAccessPolicy.updatesAllowed(context)) {
            _downloadState.value = UpdateDownloadState.Error(WifiRequiredException().message.orEmpty())
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            // Cancellation of blocking HTTP reads is not immediate. The previous job
            // must finish its cleanup before a new download touches the same .tmp file.
            downloadMutex.withLock {
            val destFile = destination
            val tempFile = UpdateFilePolicy.stagedApk(destFile)

            var connection: HttpURLConnection? = null
            val activeConnection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
            val routeWatcher = scope.launch {
                while (isActive) {
                    delay(200)
                    if (!NetworkAccessPolicy.updatesAllowed(context)) { runCatching { activeConnection.get()?.disconnect() }; break }
                }
            }
            var currentUrl = info.downloadUrl

            try {
                _downloadState.value = UpdateDownloadState.Downloading(0f, 0L, -1L)

                // Follow redirects manually if needed (GitHub asset redirects to AWS S3/CDN)
                var redirects = 0
                var responseCode: Int
                while (true) {
                    NetworkAccessPolicy.requireUpdates(context)
                    val url = URL(currentUrl)
                    if (url.protocol != "https") throw IOException("Update download requires HTTPS")
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "SplateVPN-Client")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                    activeConnection.set(connection)
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

                val responseBytes = connection.contentLengthLong
                val totalBytes = info.apkSize ?: responseBytes
                if (info.apkSize != null && responseBytes >= 0 && responseBytes != info.apkSize) {
                    throw UpdateApkException(UpdateApkIssue.INTEGRITY)
                }
                val digest = info.apkSha256?.let { MessageDigest.getInstance("SHA-256") }
                var downloadedBytes = 0L

                if (tempFile.exists()) tempFile.delete()

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            NetworkAccessPolicy.requireUpdates(context)
                            downloadedBytes += bytesRead
                            if (totalBytes >= 0 && downloadedBytes > totalBytes) {
                                throw UpdateApkException(UpdateApkIssue.INTEGRITY)
                            }
                            digest?.update(buffer, 0, bytesRead)
                            output.write(buffer, 0, bytesRead)

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
                NetworkAccessPolicy.requireUpdates(context)
                if (downloadedBytes == 0L || (totalBytes >= 0 && downloadedBytes != totalBytes)) {
                    throw UpdateApkException(UpdateApkIssue.INCOMPLETE)
                }
                if (digest?.digest()?.joinToString("") { "%02x".format(it) } != info.apkSha256) {
                    throw UpdateApkException(UpdateApkIssue.INTEGRITY)
                }
                inspectUpdateApk(tempFile, info.version)?.let { throw UpdateApkException(it) }
                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    throw IOException("Failed to save downloaded update file")
                }
                verifiedApk = apkFingerprint(destFile, info.version)

                _downloadState.value = UpdateDownloadState.Completed(destFile)

                withContext(Dispatchers.Main) {
                    installApk(destFile)
                }
            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = UpdateDownloadState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = if (!NetworkAccessPolicy.updatesAllowed(context)) {
                    UpdateDownloadState.Error(WifiRequiredException().message.orEmpty())
                } else {
                    UpdateDownloadState.Error(e.localizedMessage ?: "Download failed", (e as? UpdateApkException)?.issue)
                }
            } finally {
                routeWatcher.cancel()
                connection?.disconnect()
            }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = UpdateDownloadState.Idle
    }

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

    internal fun isNewerVersion(current: String, remote: String): Boolean = UpdateFilePolicy.isNewerVersion(current, remote)
}
