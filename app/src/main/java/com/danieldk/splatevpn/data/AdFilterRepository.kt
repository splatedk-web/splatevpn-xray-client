package com.danieldk.splatevpn.data

import android.content.Context
import android.util.AtomicFile
import com.danieldk.splatevpn.vpn.AdBlockingPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AdFilterState(val count: Int = 0, val updatedAt: Long = 0, val updating: Boolean = false,
    val failed: Boolean = false)

@Singleton
class AdFilterRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val settings: SettingsManager, private val logs: AppLogManager) {
    companion object {
        const val SOURCE = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        const val MATCHER = "ext:splate-ads.dat:ads"
    }
    private val lock = Any()
    private val sourceFile = AtomicFile(File(context.filesDir, "adguard-dns-source.txt"))
    private val compiledFile = AtomicFile(File(context.filesDir, "splate-ads.dat"))
    private val metadata = context.getSharedPreferences("ad_filter_metadata", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val _state = MutableStateFlow(AdFilterState(count = metadata.getInt("count", 0), updatedAt = metadata.getLong("updated", 0)))
    val state = _state.asStateFlow()
    private var compiledKey: Pair<Long, List<String>>? = null
    private val manualRulesSignature = digest(AdBlockingPolicy.domains.joinToString("\n").toByteArray(Charsets.UTF_8))

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun bundledSource() = context.assets.open("adguard-dns-source.bin").use {
        GZIPInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
    private fun compile(text: String, allow: List<String>) = AdFilterCompiler.parse(text, AdBlockingPolicy.domains, allow)
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rememberCompiled(bytes: ByteArray, count: Int, key: Pair<Long, List<String>>) {
        metadata.edit().putLong("updated", key.first).putLong("compiled_source", key.first)
            .putString("compiled_allow", key.second.joinToString("\n")).putInt("compiler_version", 1)
            .putString("manual_rules", manualRulesSignature)
            .putString("compiled_sha256", digest(bytes)).putInt("count", count).apply()
        compiledKey = key
    }
    fun prepareMatcher(): String? = synchronized(lock) {
        try {
            val allow = settings.getAdBlockAllowlist()
            val key = metadata.getLong("updated", 0) to allow
            // Reuse a verified compiled base across app restarts; no 180k-domain reparse on each launch.
            if (compiledKey == null && metadata.getInt("compiler_version", 0) == 1 &&
                metadata.getString("manual_rules", null) == manualRulesSignature &&
                metadata.getLong("compiled_source", -1) == key.first &&
                metadata.getString("compiled_allow", null) == allow.joinToString("\n")) {
                val valid = runCatching { compiledFile.openRead().use { digest(it.readBytes()) } == metadata.getString("compiled_sha256", null) }.getOrDefault(false)
                if (valid) compiledKey = key
            }
            if (compiledKey != key || !compiledFile.baseFile.exists()) {
                val text = runCatching { sourceFile.openRead().bufferedReader().use { it.readText() } }.getOrElse { bundledSource() }
                var domains = compile(text, allow)
                if (domains.size !in 5_000..300_000) {
                    domains = compile(bundledSource(), allow)
                    _state.value = _state.value.copy(failed = true, updatedAt = 0)
                }
                require(domains.size >= 5_000) { "Filter too small" }
                val bytes = AdFilterCompiler.encode(domains)
                writeAtomic(compiledFile, bytes)
                rememberCompiled(bytes, domains.size, key)
                _state.value = _state.value.copy(count = domains.size)
            }
            MATCHER
        } catch (error: Exception) {
            logs.w("AdFilter", "Extended list unavailable; using built-in domain rules (${error.javaClass.simpleName}: ${error.message})")
            _state.value = _state.value.copy(failed = true)
            null
        }
    }
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (_state.value.updating) return@withContext false
            _state.value = _state.value.copy(updating = true, failed = false)
        }
        try {
            val text = client.newCall(Request.Builder().url(SOURCE).build()).execute().use { response ->
                check(response.isSuccessful)
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 12 * 1024 * 1024)
                val bytes = body.byteStream().use { it.readBytesLimited(12 * 1024 * 1024) }
                bytes.toString(Charsets.UTF_8)
            }
            require(text.contains("Title: AdGuard DNS filter"))
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                val allow = settings.getAdBlockAllowlist()
                val domains = compile(text, allow)
                require(domains.size in 5_000..300_000)
                require(_state.value.count == 0 || domains.size >= _state.value.count / 2)
                // Keep the old usable files until validation has finished.
                val compiled = AdFilterCompiler.encode(domains)
                writeAtomic(sourceFile, text.toByteArray(Charsets.UTF_8))
                writeAtomic(compiledFile, compiled)
                val now = System.currentTimeMillis()
                rememberCompiled(compiled, domains.size, now to allow)
                _state.value = AdFilterState(domains.size, now)
                logs.i("AdFilter", "Updated AdGuard DNS: ${domains.size} domains")
            }
            true
        } catch (error: Exception) {
            _state.value = _state.value.copy(updating = false, failed = true)
            if (error is CancellationException) throw error
            logs.w("AdFilter", "Update failed; previous offline filter retained")
            false
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(result.size() + n <= limit)
            result.write(buffer, 0, n)
        }
        return result.toByteArray()
    }
}
