package com.danieldk.splatevpn.data

import android.content.Context
import android.util.AtomicFile
import com.danieldk.splatevpn.SplateConstants
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

enum class GeoDataResult { UPDATED, CURRENT, ROLLED_BACK, NO_PREVIOUS, FAILED }

data class GeoDataStatus(val revision: String, val checkedAt: Long = 0, val error: Boolean = false,
    val result: GeoDataResult? = null, val busy: Boolean = false)

/** Only an atomic pointer is mutable; both databases are published as a validated pair. */
@Singleton
class GeoDataRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val adFilters: AdFilterRepository? = null) {
    private val root = File(context.filesDir, "geodata-versions").apply { mkdirs() }
    private val pointer = AtomicFile(File(root, "active"))
    private val pointerLock = Any()
    private val prefs = context.getSharedPreferences("geodata_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var inUse: String? = null
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            check(chain.request().url.isHttps) { "HTTPS required" }
            chain.proceed(chain.request())
        }.build()
    private val tunnelClient get() = com.danieldk.splatevpn.vpn.LocalProxyAccess.client(client)
    private val _status = MutableStateFlow(GeoDataStatus(currentRevision(), prefs.getLong("checked", 0)))
    val status = _status.asStateFlow()

    private fun selected(): File? = synchronized(pointerLock) { runCatching {
        val name = pointer.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        require(name.matches(Regex("[0-9]{12}-[a-f0-9-]{36}")))
        File(root, name).takeIf { directory ->
            name.substringBefore('-') >= SplateConstants.GEO_DATA_REVISION &&
                listOf("geoip.dat", "geosite.dat").all { File(directory, it).length() > 0 }
        }
    }.getOrNull() }
    fun currentRevision(): String = selected()?.name?.substringBefore('-') ?: SplateConstants.GEO_DATA_REVISION
    @Synchronized fun assetDirectory(): File {
        val directory = selected() ?: return context.filesDir
        inUse = directory.name
        // User-compiled ad rules share Xray's asset namespace with the geo databases.
        adFilters?.copyCompiledTo(directory)
        return directory
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        var staging: File? = null
        _status.value = _status.value.copy(busy = true, error = false, result = null)
        try {
            NetworkAccessPolicy.requireUpdates(context)
            val metadata = fetch("https://api.github.com/repos/Loyalsoldier/v2ray-rules-dat/releases/latest", 2_000_000)
            val revision = JsonParser.parseString(metadata.toString(Charsets.UTF_8)).asJsonObject["tag_name"].asString
            require(revision.matches(Regex("[0-9]{12}")))
            val checked = System.currentTimeMillis()
            val updated = revision > currentRevision()
            if (updated) {
                val directory = File(root, "$revision-${UUID.randomUUID()}").apply { check(mkdir()) }
                staging = directory
                val base = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/$revision"
                for (name in listOf("geoip.dat", "geosite.dat")) {
                    val hash = fetch("$base/$name.sha256sum", 4096).toString(Charsets.UTF_8).trim().substringBefore(' ')
                    require(hash.matches(Regex("[a-fA-F0-9]{64}")))
                    val bytes = fetch("$base/$name", 64 * 1024 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    require(digest.equals(hash, true)) { "Geodata checksum mismatch" }
                    File(directory, name).outputStream().use { it.write(bytes); it.fd.sync() }
                }
                validate(directory)
                coroutineContext.ensureActive()
                val previous = selected()?.name.orEmpty()
                check(prefs.edit().putString("previous", previous).commit())
                publish(directory.name)
                staging = null
                val keep = setOf(directory.name, previous, inUse)
                root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[0-9]{12}-[a-f0-9-]{36}")) && it.name !in keep }
                    ?.forEach { obsolete -> if (obsolete.canonicalFile.parentFile == root.canonicalFile) obsolete.deleteRecursively() }
            }
            prefs.edit().putLong("checked", checked).apply()
            _status.value = GeoDataStatus(currentRevision(), checked, result = if (updated) GeoDataResult.UPDATED else GeoDataResult.CURRENT)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _status.value = _status.value.copy(error = true, result = GeoDataResult.FAILED)
            false
        } finally { staging?.deleteRecursively(); _status.value = _status.value.copy(busy = false) }
    } }

    suspend fun rollback() = withContext(Dispatchers.IO) { mutex.withLock {
        _status.value = _status.value.copy(busy = true, error = false, result = null)
        try {
            if (!prefs.contains("previous")) {
                _status.value = _status.value.copy(result = GeoDataResult.NO_PREVIOUS)
                return@withLock
            }
            val previous = prefs.getString("previous", "").orEmpty()
            if (previous.isEmpty()) {
                synchronized(pointerLock) { pointer.delete() }
            } else {
                require(previous.matches(Regex("[0-9]{12}-[a-f0-9-]{36}")))
                val directory = File(root, previous)
                check(directory.isDirectory)
                validate(directory)
                publish(previous)
            }
            prefs.edit().remove("previous").apply()
            _status.value = GeoDataStatus(currentRevision(), result = GeoDataResult.ROLLED_BACK)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("GeoData", "Rollback failed", error)
            _status.value = _status.value.copy(error = true, result = GeoDataResult.FAILED)
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    } }

    private fun publish(name: String) = synchronized(pointerLock) {
        val stream = pointer.startWrite()
        try { stream.write(name.toByteArray()); pointer.finishWrite(stream) }
        catch (error: Exception) { pointer.failWrite(stream); throw error }
    }
    private suspend fun fetch(url: String, limit: Int): ByteArray {
        coroutineContext.ensureActive()
        val operationContext = coroutineContext
        val viaVpn = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE).getBoolean("source_downloads_via_vpn", false)
        return NetworkAccessPolicy.withUpdates(context) {
          (if (viaVpn) tunnelClient else client).newCall(Request.Builder().url(url).header("User-Agent", "SplateVPN-Geodata").build()).awaitConsumedResponse { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= limit)
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    operationContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(out.size().toLong() + read <= limit)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
          }
        }
    }
    internal fun validate(directory: File) {
        val domains = RoutingRegion.entries.flatMap { it.domains }.joinToString(",") { "\"$it\"" }
        val ips = RoutingRegion.entries.mapNotNull { it.geoIp }.joinToString(",") { "\"$it\"" }
        val config = File(directory, "check.json").apply { writeText("""{"log":{"loglevel":"error"},"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field","outboundTag":"direct","domain":[$domains]},{"type":"field","outboundTag":"direct","ip":[$ips]}]}}""") }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        check(binary.isFile)
        val process = ProcessBuilder(binary.absolutePath, "run", "-test", "-c", config.absolutePath)
            .apply { environment()["XRAY_LOCATION_ASSET"] = directory.absolutePath }
            .redirectErrorStream(true).redirectOutput(File(directory, "check.log")).start()
        try { check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "Xray rejected geodata" } }
        finally { process.destroyForcibly(); config.delete(); File(directory, "check.log").delete() }
    }
}
