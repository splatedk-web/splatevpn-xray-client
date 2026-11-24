package com.danieldk.splatevpn.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val appLogManager: AppLogManager,
    private val database: AppDatabase
) {
    private val TAG = "AutoBackupManager"
    private val VAULT_FILE_NAME = "auto_backup_vault.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val vaultMutex = Mutex()
    private val metadata = context.getSharedPreferences("vault_metadata", Context.MODE_PRIVATE)

    /** Persist before deleting: an interrupted delete must never resurrect credentials. */
    fun prepareForDeletion() {
        check(metadata.edit().putBoolean("intentional_deletion", true).commit())
    }

    suspend fun <T> deleteAndBackup(afterCommit: () -> Unit = {}, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            prepareForDeletion()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val result = database.withTransaction { block() }
                afterCommit()
                backupLocked()
                result
            }
        }
    }

    data class VaultPayload(
        val version: Int = 1,
        val updatedAt: Long = System.currentTimeMillis(),
        val servers: List<ServerNode> = emptyList(),
        val subscriptions: List<SubscriptionEntity> = emptyList()
    )

    private fun getVaultFile(): File = File(context.filesDir, VAULT_FILE_NAME)

    /**
     * Сохраняет текущее состояние серверов и подписок в защищённый локальный файл-снапшот.
     */
    suspend fun backupToVault() = withContext(Dispatchers.IO) { vaultMutex.withLock { backupLocked() } }

    private suspend fun backupLocked() {
        try {
            val payload = database.withTransaction {
                VaultPayload(servers = serverDao.getAllServersSync(), subscriptions = subscriptionDao.getAllSubscriptionsSync())
            }
            val json = AwgLocalVault.seal(gson.toJsonTree(payload).asJsonObject).toString()
            require(json.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "Vault exceeds 32 MB" }
            val file = AtomicFile(getVaultFile())
            val output = file.startWrite()
            try { output.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            if (payload.servers.isNotEmpty()) metadata.edit().putBoolean("intentional_deletion", false).commit()
            Log.d(TAG, "Снапшот базы данных сохранён (${payload.servers.size} узлов)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения снапшота базы данных: ${e.message}", e)
        }
    }

    /**
     * Проверяет наличие сохранённого снапшота.
     */
    fun hasVaultBackup(): Boolean {
        val file = getVaultFile()
        return file.exists() || File(file.path + ".bak").exists()
    }

    /**
     * Восстанавливает узлы и подписки из локального снапшота.
     */
    suspend fun restoreFromVault(): Result<Int> = withContext(Dispatchers.IO) { vaultMutex.withLock { restoreLocked() } }

    private suspend fun restoreLocked(onlyIfEmpty: Boolean = false): Result<Int> {
        try {
            val file = getVaultFile()
            if (!file.exists()) {
                if (!File(file.path + ".bak").exists()) return Result.failure(Exception("Файл резервной автокопии не найден"))
            }

            val content = AtomicFile(file).openRead().use { it.readLimited(32 * 1024 * 1024).toString(Charsets.UTF_8) }
            val document = AwgLocalVault.open(JsonParser.parseString(content).asJsonObject)
            require(document["version"]?.asInt == 1) { "Unsupported vault version" }
            document.addProperty("app", "SplateVPN")
            document.add("settings", JsonObject())
            document.add("perAppPackages", com.google.gson.JsonArray())
            document.add("customRoutes", com.google.gson.JsonArray())
            val validated = BackupValidation.decode(document.toString())
            val payload = gson.fromJson(document, VaultPayload::class.java)
                ?: return Result.failure(Exception("Не удалось прочитать резервную автокопию"))

            var restoredCount = 0
            database.withTransaction {
            if (onlyIfEmpty && serverDao.getAllServersSync().isNotEmpty()) return@withTransaction
            if (validated.servers.isNotEmpty()) {
                val selected = serverDao.getSelectedServerSync()?.id ?: validated.servers.firstOrNull { it.isSelected }?.id
                serverDao.insertServers(validated.servers.map { it.copy(isSelected = it.id == selected) })
                restoredCount += payload.servers.size
            }
            if (payload.subscriptions.isNotEmpty()) {
                subscriptionDao.insertSubscriptions(validated.subscriptions)
            }
            }

            appLogManager.i(TAG, "Восстановлено из автокопии: $restoredCount серверов, ${payload.subscriptions.size} подписок")
            return Result.success(restoredCount)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка восстановления из автокопии: ${e.message}", e)
            return Result.failure(e)
        }
    }

    /**
     * Аварийное самовосстановление: вызывается при инициализации, если база данных пуста.
     * Проверяет снапшот, а если его нет — пытается спасти узел из последнего конфига Xray.
     */
    suspend fun tryAutoRestoreIfEmpty(): Int = withContext(Dispatchers.IO) { vaultMutex.withLock {
        try {
            val currentServers = serverDao.getAllServersSync()
            if (currentServers.isNotEmpty()) {
                // База не пуста, обновляем снапшот на всякий случай
                backupLocked()
                return@withLock 0
            }
            if (metadata.getBoolean("intentional_deletion", false)) return@withLock 0

            // 1. Попытка восстановить из vault-снапшота
            if (hasVaultBackup()) {
                val res = restoreLocked(onlyIfEmpty = true)
                if (res.isSuccess && (res.getOrNull() ?: 0) > 0) {
                    val count = res.getOrNull() ?: 0
                    appLogManager.w(TAG, "🛡️ База данных была пуста. Автоматически восстановлено $count серверов из резервной автокопии!")
                    return@withLock count
                }
                // A valid empty snapshot records an intentionally empty database.
                if (res.isSuccess) return@withLock 0
            }

            // 2. Если автокопии нет (первый запуск после неудачной миграции), ищем последний рабочий xray_config.json
            val rescuedServer = rescueFromXrayConfig()
            if (rescuedServer != null) {
                val inserted = database.withTransaction {
                    if (serverDao.getAllServersSync().isNotEmpty()) false else {
                        serverDao.insertServer(rescuedServer.copy(isSelected = true)); true
                    }
                }
                if (!inserted) return@withLock 0
                appLogManager.w(TAG, "🛡️ База данных была пуста после обновления. Автоматически спасён и восстановлен рабочий узел: ${rescuedServer.name} (${rescuedServer.address}:${rescuedServer.port})")
                backupLocked()
                return@withLock 1
            }

            0
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в tryAutoRestoreIfEmpty: ${e.message}", e)
            0
        }
    } }

    /**
     * Извлекает узел из последнего сохранённого xray_config.json, если база была стёрта.
     */
    private fun rescueFromXrayConfig(): ServerNode? {
        return try {
            val configFile = File(context.filesDir, "xray_config.json")
            if (!configFile.exists() || configFile.length() < 20L) return null

            val jsonContent = configFile.readText()
            val root = JsonParser.parseString(jsonContent).asJsonObject
            val outbounds = root.getAsJsonArray("outbounds") ?: return null

            for (elem in outbounds) {
                val out = elem.asJsonObject
                val tag = out.get("tag")?.asString ?: ""
                val protocol = out.get("protocol")?.asString?.lowercase() ?: ""

                if (tag == "proxy" || protocol in listOf("vless", "vmess", "trojan", "shadowsocks")) {
                    val settings = out.getAsJsonObject("settings")
                    var address = ""
                    var port = 443
                    var uuid = ""
                    var flow = ""
                    var encryption = "none"

                    if (settings != null && settings.has("vnext")) {
                        val vnext = settings.getAsJsonArray("vnext")
                        if (vnext.size() > 0) {
                            val target = vnext[0].asJsonObject
                            address = target.get("address")?.asString ?: ""
                            port = target.get("port")?.asInt ?: 443
                            val users = target.getAsJsonArray("users")
                            if (users != null && users.size() > 0) {
                                val user = users[0].asJsonObject
                                uuid = user.get("id")?.asString ?: ""
                                flow = user.get("flow")?.asString ?: ""
                                encryption = user.get("encryption")?.asString ?: "none"
                            }
                        }
                    }

                    if (address.isBlank()) continue

                    val stream = out.getAsJsonObject("streamSettings")
                    var network = "tcp"
                    var security = "none"
                    var sni = ""
                    var fingerprint = "chrome"
                    var publicKey = ""
                    var shortId = ""
                    var spiderX = ""
                    var path = ""
                    var host = ""
                    var mode = "auto"

                    if (stream != null) {
                        network = stream.get("network")?.asString ?: "tcp"
                        security = stream.get("security")?.asString ?: "none"

                        if (stream.has("realitySettings")) {
                            val r = stream.getAsJsonObject("realitySettings")
                            sni = r.get("serverName")?.asString ?: ""
                            fingerprint = r.get("fingerprint")?.asString ?: "chrome"
                            publicKey = r.get("publicKey")?.asString ?: ""
                            shortId = r.get("shortId")?.asString ?: ""
                            spiderX = r.get("spiderX")?.asString ?: ""
                        } else if (stream.has("tlsSettings")) {
                            val t = stream.getAsJsonObject("tlsSettings")
                            sni = t.get("serverName")?.asString ?: ""
                            fingerprint = t.get("fingerprint")?.asString ?: "chrome"
                        }

                        if (stream.has("wsSettings")) {
                            val w = stream.getAsJsonObject("wsSettings")
                            path = w.get("path")?.asString ?: ""
                            val headers = w.getAsJsonObject("headers")
                            host = headers?.get("Host")?.asString ?: ""
                        } else if (stream.has("xhttpSettings")) {
                            val x = stream.getAsJsonObject("xhttpSettings")
                            path = x.get("path")?.asString ?: ""
                            mode = x.get("mode")?.asString ?: "auto"
                            val headers = x.getAsJsonObject("headers")
                            host = headers?.get("Host")?.asString ?: ""
                        }
                    }

                    return ServerNode(
                        id = UUID.randomUUID().toString(),
                        name = "Восстановленный ($address)",
                        protocol = protocol,
                        address = address,
                        port = port,
                        uuid = uuid,
                        flow = flow,
                        encryption = encryption,
                        network = network,
                        security = security,
                        sni = sni,
                        fingerprint = fingerprint,
                        publicKey = publicKey,
                        shortId = shortId,
                        spiderX = spiderX,
                        path = path,
                        host = host,
                        mode = mode,
                        isSelected = true,
                        subId = "recovered",
                        subName = "Восстановленные"
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка извлечения узла из xray_config.json: ${e.message}", e)
            null
        }
    }
}
