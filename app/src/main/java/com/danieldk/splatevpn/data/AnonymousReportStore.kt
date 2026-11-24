package com.danieldk.splatevpn.data

import android.content.Context
import android.util.AtomicFile
import com.danieldk.splatevpn.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalErrorReport(val id: String, val json: String)

/** No network client, device information, exception messages, subscriptions or raw logs. */
@Singleton
class AnonymousReportStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("anonymous_reports", Context.MODE_PRIVATE)
    private val directory = File(context.noBackupFilesDir, "anonymous-reports")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var consent = preferences.getBoolean("enabled", false)
    private val _enabled = MutableStateFlow(consent)
    val enabled = _enabled.asStateFlow()
    private val _reports = MutableStateFlow<List<LocalErrorReport>>(emptyList())
    val reports = _reports.asStateFlow()
    private var installed = false
    private val lastError = java.util.concurrent.atomic.AtomicLong(0)

    init { scope.launch { synchronized(lock) { refreshLocked() } } }

    @Synchronized fun installCrashHandler() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (consent) runCatching { write(AnonymousErrorReports.create(BuildConfig.VERSION_NAME, "OTHER", error)) }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(preferences.edit().putBoolean("enabled", value).commit()) { "Could not save report consent" }
            consent = value
            _enabled.value = value
            if (!value) clearLocked()
        }
    }

    suspend fun recordFeature(category: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(consent) { "Enable local reports first" }
            write(AnonymousErrorReports.create(BuildConfig.VERSION_NAME, category))
        }
    }

    fun recordError(error: Throwable, category: String = "OTHER") {
        if (!consent) return
        val now = System.nanoTime()
        val before = lastError.get()
        if (before != 0L && now - before < 10_000_000_000L) return
        if (!lastError.compareAndSet(before, now)) return
        scope.launch { runCatching { write(AnonymousErrorReports.create(BuildConfig.VERSION_NAME, category, error)) } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { synchronized(lock) {
        require(Regex("[a-fA-F0-9-]{36}\\.json").matches(id))
        AtomicFile(File(directory, id)).delete()
        refreshLocked()
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { synchronized(lock) { clearLocked() } }

    private fun clearLocked() {
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        _reports.value = emptyList()
    }

    private fun write(report: AnonymousErrorReport) = synchronized(lock) {
        if (!consent) return@synchronized
        val bytes = AnonymousErrorReports.json(report).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32_768)
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, "${UUID.randomUUID()}.json"))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
            .drop(5).forEach { AtomicFile(it).delete() }
        refreshLocked()
    }

    private fun refreshLocked() {
        if (!consent) { clearLocked(); return }
        _reports.value = directory.listFiles().orEmpty().filter { it.extension == "json" && it.length() <= 32_768 }
            .sortedByDescending { it.lastModified() }.take(5).mapNotNull { file ->
                runCatching { LocalErrorReport(file.name, file.readText()) }.getOrNull()
            }
    }
}
