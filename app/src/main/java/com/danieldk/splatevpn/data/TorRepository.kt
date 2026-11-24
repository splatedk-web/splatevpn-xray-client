package com.danieldk.splatevpn.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transport: SubscriptionTransport
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tor-settings.json"))
    private val gson = Gson()
    private val mutex = Mutex()
    private val updateMutex = Mutex()
    private var loaded = false
    private val _settings = MutableStateFlow(TorSettings())
    val settings = _settings.asStateFlow()
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError = _storageError.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { load() } }

    fun current(): TorSettings = settings.value

    suspend fun load(): TorSettings = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        _settings.value
    } }

    private fun loadLocked() {
        if (loaded) return
        try {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val json = file.openRead().use { it.readLimited(TorConfigParser.MAX_BYTES).toString(Charsets.UTF_8) }
                _settings.value = TorConfigParser.validate(requireNotNull(gson.fromJson(json, TorSettings::class.java)))
                    .withDefaultBridgeSubscription()
                TorSubscriptionWorker.schedule(context, _settings.value.autoUpdate && _settings.value.bridgeSubscriptions.isNotEmpty())
            } else {
                _settings.value = TorSettings().withDefaultBridgeSubscription()
            }
        } catch (_: Exception) {
            // Keep the unreadable original for recovery; never silently replace it on app launch.
            _storageError.value = "Saved Tor settings could not be read. Save valid settings to replace them."
        }
        loaded = true
    }

    suspend fun save(value: TorSettings) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        persistLocked(TorConfigParser.validate(value).withDefaultBridgeSubscription())
    } }

    suspend fun removeSubscription(url: String) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val current = _settings.value
        val remaining = current.bridgeSubscriptions.filterNot { it.url == url }
        if (remaining.size != current.bridgeSubscriptions.size) {
            persistLocked(current.withBridgeSources(remaining))
        }
    } }

    suspend fun addTop100Subscription() = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val current = _settings.value
        if (current.bridgeSubscriptions.none { it.url == TOR_TOP100_SUBSCRIPTION }) {
            require(current.bridgeSubscriptions.size < TorConfigParser.MAX_SUBSCRIPTIONS) { "Too many Tor subscriptions" }
            val source = TorBridgeSubscription(TOR_TOP100_SUBSCRIPTION, emptyList())
            persistLocked(current.copy(bridgeSubscriptions = listOf(source) + current.bridgeSubscriptions))
        }
    } }

    private fun persistLocked(value: TorSettings) {
        val bytes = gson.toJson(value).toByteArray(Charsets.UTF_8)
        require(bytes.size <= TorConfigParser.MAX_BYTES) { "Tor settings exceed 1 MB" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        _storageError.value = null
        _settings.value = value
        TorSubscriptionWorker.schedule(context, value.autoUpdate && value.bridgeSubscriptions.isNotEmpty())
    }

    suspend fun importText(text: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val imported = TorConfigParser.parsePasted(text)
            mutex.withLock {
                loadLocked()
                persistLocked(applyImport(_settings.value, imported))
            }
            Result.success(imported.bridges.size)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun updateSubscription(
        url: String,
        settingsOverride: TorSettings? = null,
        refreshOnly: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            try {
                NetworkAccessPolicy.requireUpdates(context)
                val before = load()
                val normalized = TorConfigParser.normalizeSubscriptionUrl(url)
                if (refreshOnly && (!before.autoUpdate || before.bridgeSubscriptions.none { it.url == normalized })) {
                    return@withLock Result.success(0)
                }
                val viaVpn = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)
                    .getBoolean("source_downloads_via_vpn", false)
                val response = NetworkAccessPolicy.withUpdates(context) { transport.fetch(normalized, "SplateVPN-Tor/1.0", viaVpn) }
                if (!response.isSuccessful) throw SubscriptionHttpException(response.code)
                val imported = TorConfigParser.parseSubscription(response.content)
                mutex.withLock {
                    loadLocked()
                    check(_settings.value == before) { "Tor settings changed during download. Retry the import." }
                    val draft = settingsOverride ?: before
                    val existing = before.bridgeSubscriptions.firstOrNull { it.url == normalized }
                    val source = TorBridgeSubscription(normalized, imported.bridges.ifEmpty { existing?.bridges.orEmpty() })
                    val sources = if (existing == null) before.bridgeSubscriptions + source
                        else before.bridgeSubscriptions.map { if (it.url == normalized) source else it }
                    val merged = draft.withBridgeSources(sources)
                    val wasDownloaded = existing != null &&
                        (existing.url != TOR_TOP100_SUBSCRIPTION || existing.bridges.isNotEmpty())
                    val updated = if (refreshOnly || (wasDownloaded && settingsOverride == null)) merged else merged.copy(
                        useBridges = imported.useBridges ?: (imported.bridges.isNotEmpty() || merged.useBridges),
                        entryNodes = imported.entryNodes ?: merged.entryNodes,
                        exitNodes = imported.exitNodes ?: merged.exitNodes,
                        excludeNodes = imported.excludeNodes ?: merged.excludeNodes,
                        strictNodes = imported.strictNodes ?: merged.strictNodes
                    )
                    persistLocked(TorConfigParser.validate(updated.copy(
                        lastImportSkipped = imported.skippedBridges,
                        lastUpdatedAt = System.currentTimeMillis()
                    )))
                }
                Result.success(imported.bridges.size)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { Result.failure(error) }
        }
    }

    private fun applyImport(before: TorSettings, imported: TorImport): TorSettings = TorConfigParser.validate(
        before.withBridgeSources(manual = before.manualBridges() + imported.bridges).copy(
            lastImportSkipped = imported.skippedBridges,
            useBridges = imported.useBridges ?: (imported.bridges.isNotEmpty() || before.useBridges),
            entryNodes = imported.entryNodes ?: before.entryNodes,
            exitNodes = imported.exitNodes ?: before.exitNodes,
            excludeNodes = imported.excludeNodes ?: before.excludeNodes,
            strictNodes = imported.strictNodes ?: before.strictNodes
        )
    )
}
