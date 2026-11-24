package com.danieldk.splatevpn.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CloudBackupSettings(
    val enabled: Boolean = false,
    val unmeteredOnly: Boolean = true, val intervalHours: Int = 24, val hasPassword: Boolean = false,
    val lastSuccessAt: Long = 0, val lastError: String = "",
    val folderMigrationNotice: Boolean = false
)
data class CloudBackupState(val busy: Boolean = false, val message: String = "", val error: Boolean = false)

internal object CloudBackupPolicy {
    const val HISTORY_LIMIT = 10
    fun fileName(device: String, now: Long, nonce: String) : String {
        require(device.matches(Regex("[a-f0-9]{12}")) && nonce.matches(Regex("[a-f0-9]{8}")) && now > 0)
        return "splate-$device-$now-$nonce.json"
    }
    fun migrateLegacySettings(value: CloudBackupSettings, legacyTarget: String?): CloudBackupSettings =
        if (legacyTarget != null && legacyTarget != "webdav") value.copy(enabled = false, folderMigrationNotice = true)
        else value
}

/** Scheduled encrypted snapshots. Restoring on another device always uses the existing preview flow. */
@Singleton
class CloudBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webDav: WebDavSyncManager
) {
    private data class Saved(val settings: CloudBackupSettings, val password: String, val device: String)
    private val store = DeviceSecretStore(context, "splate-cloud-backup-v1")
    private val gate = Mutex()
    private val operation = Mutex()
    private val gson = Gson()
    private var saved: Saved? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(CloudBackupSettings())
    val settings = _settings.asStateFlow()
    private val _state = MutableStateFlow(CloudBackupState())
    val state = _state.asStateFlow()

    fun start() { scope.launch { runCatching { load() }.onSuccess { CloudBackupWorker.schedule(context, it) } } }
    suspend fun load(): CloudBackupSettings = withContext(Dispatchers.IO) { gate.withLock {
        if (saved == null) {
            saved = try { loadStored() }
            catch (_: Exception) {
                _state.value = CloudBackupState(message = "Не удалось открыть настройки облачной копии. Сохраните их заново.", error = true)
                null
            } ?: Saved(CloudBackupSettings(), "", UUID.randomUUID().toString().replace("-", "").take(12))
            _settings.value = requireNotNull(saved).settings.copy(hasPassword = requireNotNull(saved).password.isNotEmpty())
        }
        _settings.value
    } }

    private fun loadStored(): Saved? {
        val document = store.read() ?: return null
        val decoded = requireNotNull(gson.fromJson(document, Saved::class.java))
        val legacy = JsonParser.parseString(document).asJsonObject.getAsJsonObject("settings")
        val legacyTarget = legacy["target"]?.takeUnless { it.isJsonNull }?.asString
            ?: "folder".takeIf { legacy["treeUri"]?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true }
        val next = decoded.copy(settings = CloudBackupPolicy.migrateLegacySettings(
            decoded.settings.copy(hasPassword = decoded.password.isNotEmpty()), legacyTarget))
        if (legacy.has("target") || legacy.has("treeUri")) {
            // Forget only the obsolete destination. Do not touch files or revoke grants that the
            // user might still use for manual export; retain the device-bound encryption secret.
            try { store.write(gson.toJson(next)) }
            catch (_: Exception) {
                _state.value = CloudBackupState(message = "Автокопия в папку отключена, но изменение пока не удалось сохранить.", error = true)
            }
        }
        return next
    }

    suspend fun dismissFolderMigrationNotice(): Result<Unit> = withContext(Dispatchers.IO) { operation.withLock {
        load()
        runCatching { gate.withLock {
            val next = requireNotNull(saved).let { it.copy(settings = it.settings.copy(folderMigrationNotice = false)) }
            store.write(gson.toJson(next)); saved = next; _settings.value = next.settings
        } }.onFailure { if (it is CancellationException) throw it }
    } }

    suspend fun save(value: CloudBackupSettings, password: CharArray? = null): Result<Unit> = withContext(Dispatchers.IO) { operation.withLock operationLock@{
        load()
        if (!value.enabled && _settings.value.enabled) {
            return@operationLock runCatching {
                gate.withLock {
                    val next = requireNotNull(saved).let { it.copy(settings = it.settings.copy(enabled = false)) }
                    store.write(gson.toJson(next)); saved = next; _settings.value = next.settings
                }
                CloudBackupWorker.schedule(context, _settings.value)
            }.onFailure { if (it is CancellationException) throw it }
        }
        runCatching {
            require(value.intervalHours in setOf(6, 24))
            require(webDav.load().endpoint.isNotBlank()) { "Сначала сохраните адрес и учётные данные WebDAV" }
            gate.withLock {
                val current = requireNotNull(saved)
                val secret = password?.concatToString()?.takeIf { it.isNotEmpty() } ?: current.password
                require(secret.length in 8..1024) { "Нужен пароль шифрования от 8 до 1024 символов" }
                val next = current.copy(settings = value.copy(hasPassword = true, lastSuccessAt = current.settings.lastSuccessAt,
                    lastError = "", folderMigrationNotice = false), password = secret)
                store.write(gson.toJson(next)); saved = next; _settings.value = next.settings
            }
            CloudBackupWorker.schedule(context, _settings.value)
        }.onFailure { if (it is CancellationException) throw it }
    } }

    suspend fun forget(): Result<Unit> = withContext(Dispatchers.IO) { operation.withLock { gate.withLock {
        runCatching {
            store.delete(); saved = null; _settings.value = CloudBackupSettings(); _state.value = CloudBackupState()
            CloudBackupWorker.schedule(context, _settings.value)
        }
    } } }

    suspend fun backupNow(scheduled: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) { operation.withLock {
        load()
        val snapshot = gate.withLock { requireNotNull(saved) }
        if (scheduled && !snapshot.settings.enabled) return@withLock Result.success(Unit)
        if (snapshot.settings.folderMigrationNotice) return@withLock Result.failure(IllegalStateException(
            "Автокопирование в папку больше не используется. Для новой копии настройте WebDAV или воспользуйтесь ручным экспортом."))
        _state.value = CloudBackupState(busy = true)
        try {
        val password = snapshot.password.toCharArray()
        val result = try {
            require(password.size >= 8) { "Сохраните настройки и пароль шифрования" }
            val now = System.currentTimeMillis()
            val name = CloudBackupPolicy.fileName(snapshot.device, now, UUID.randomUUID().toString().replace("-", "").take(8))
            webDav.uploadScheduled(password, name).getOrThrow()
            Result.success(Unit)
        } catch (error: CancellationException) { _state.value = CloudBackupState(); throw error
        } catch (error: Exception) { Result.failure(error)
        } finally { password.fill('\u0000') }
        gate.withLock {
            val current = requireNotNull(saved)
            val next = current.copy(settings = current.settings.copy(
                lastSuccessAt = if (result.isSuccess) System.currentTimeMillis() else current.settings.lastSuccessAt,
                lastError = result.exceptionOrNull()?.message.orEmpty().take(512)))
            store.write(gson.toJson(next)); saved = next; _settings.value = next.settings
        }
        _state.value = CloudBackupState(message = result.exceptionOrNull()?.message ?: "Зашифрованная копия сохранена", error = result.isFailure)
        result
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            _state.value = CloudBackupState(message = error.message.orEmpty(), error = true)
            Result.failure(error)
        } finally { _state.value = _state.value.copy(busy = false) }
    } }

}
