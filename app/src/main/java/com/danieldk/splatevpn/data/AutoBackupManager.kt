package com.danieldk.splatevpn.data

import android.content.Context
import android.util.Log
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
    private val appLogManager: AppLogManager
) {
    private val TAG = "AutoBackupManager"
    private val VAULT_FILE_NAME = "auto_backup_vault.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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
    suspend fun backupToVault() = withContext(Dispatchers.IO) {
        try {
            val servers = serverDao.getAllServersSync()
            val subscriptions = subscriptionDao.getAllSubscriptionsSync()

            if (servers.isEmpty() && subscriptions.isEmpty()) {
                // Не перезаписываем существующий снапшот пустыми данными при случайном старте
                return@withContext
            }

            val payload = VaultPayload(
                servers = servers,
                subscriptions = subscriptions
            )
            val json = gson.toJson(payload)
            getVaultFile().writeText(json)
            Log.d(TAG, "Снапшот базы данных успешно сохранён (${servers.size} узлов, ${subscriptions.size} подписок)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения снапшота базы данных: ${e.message}", e)
        }
    }

    /**
     * Проверяет наличие сохранённого снапшота.
     */
    fun hasVaultBackup(): Boolean {
        val file = getVaultFile()
        return file.exists() && file.length() > 10L
    }

    /**
     * Восстанавливает узлы и подписки из локального снапшота.
     */
    suspend fun restoreFromVault(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val file = getVaultFile()
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Файл резервной автокопии не найден"))
            }

            val content = file.readText()
            val payload = gson.fromJson(content, VaultPayload::class.java)
                ?: return@withContext Result.failure(Exception("Не удалось прочитать резервную автокопию"))

            var restoredCount = 0
            if (payload.servers.isNotEmpty()) {
                serverDao.insertServers(payload.servers)
                restoredCount += payload.servers.size
            }
            if (payload.subscriptions.isNotEmpty()) {
                subscriptionDao.insertSubscriptions(payload.subscriptions)
            }

            appLogManager.i(TAG, "Восстановлено из автокопии: $restoredCount серверов, ${payload.subscriptions.size} подписок")
            Result.success(restoredCount)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка восстановления из автокопии: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Аварийное самовосстановление: вызывается при инициализации, если база данных пуста.
     * Проверяет снапшот, а если его нет — пытается спасти узел из последнего конфига Xray.
     */
    suspend fun tryAutoRestoreIfEmpty(): Int = withContext(Dispatchers.IO) {
        try {
            val currentServers = serverDao.getAllServersSync()
            if (currentServers.isNotEmpty()) {
                // База не пуста, обновляем снапшот на всякий случай
                backupToVault()
                return@withContext 0
            }

            // 1. Попытка восстановить из vault-снапшота
            if (hasVaultBackup()) {
                val res = restoreFromVault()
                if (res.isSuccess && (res.getOrNull() ?: 0) > 0) {
                    val count = res.getOrNull() ?: 0
                    appLogManager.w(TAG, "🛡️ База данных была пуста. Автоматически восстановлено $count серверов из резервной автокопии!")
                    return@withContext count
                }
            }

            // 2. Если автокопии нет (первый запуск после неудачной миграции), ищем последний рабочий xray_config.json
            val rescuedServer = rescueFromXrayConfig()
            if (rescuedServer != null) {
                serverDao.insertServer(rescuedServer.copy(isSelected = true))
                appLogManager.w(TAG, "🛡️ База данных была пуста после обновления. Автоматически спасён и восстановлен рабочий узел: ${rescuedServer.name} (${rescuedServer.address}:${rescuedServer.port})")
                backupToVault()
                return@withContext 1
            }

            0
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в tryAutoRestoreIfEmpty: ${e.message}", e)
            0
        }
    }

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
