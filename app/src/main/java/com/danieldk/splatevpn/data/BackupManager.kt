package com.danieldk.splatevpn.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase
) {
    private val importGate = Mutex()
    enum class ImportMode { MERGE, REPLACE }

    private val TAG = "BackupManager"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class BackupData(
        val app: String = "SplateVPN",
        val version: String = "2.2.0",
        val exportTime: Long = System.currentTimeMillis(),
        val servers: List<ServerNode> = emptyList(),
        val subscriptions: List<SubscriptionEntity> = emptyList(),
        val settings: Map<String, Any> = emptyMap(),
        val perAppPackages: List<String> = emptyList(),
        val customRoutes: List<List<String>> = emptyList()
    )

    suspend fun exportBackup(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val servers = serverDao.getAllServersSync()
            val subscriptions = subscriptionDao.getAllSubscriptionsSync()
            val perApps = settingsManager.getPerAppPackages().toList()
            val routes = settingsManager.getCustomRoutes().map { listOf(it.first, it.second) }

            val settingsMap = mapOf(
                "app_theme" to settingsManager.getAppTheme(),
                "app_language" to settingsManager.getAppLanguage(),
                "sub_auto_update_interval" to settingsManager.getAutoUpdateInterval(),
                "xhttp_mode" to settingsManager.getXhttpMode(),
                "custom_doh_url" to settingsManager.getCustomDohUrl(),
                "tls_fragment_preset" to settingsManager.getTlsFragmentPreset(),
                "tls_fragment_length" to settingsManager.getTlsFragmentLength(),
                "tls_fragment_interval" to settingsManager.getTlsFragmentInterval(),
                "tls_fragment_packets" to settingsManager.getTlsFragmentPackets(),
                "mux_enabled" to settingsManager.isMuxEnabled(),
                "mux_concurrency" to settingsManager.getMuxConcurrency(),
                "share_lan_enabled" to settingsManager.isShareLanEnabled(),
                "routing_mode" to settingsManager.getRoutingMode(),
                "glass_transparency" to settingsManager.getGlassTransparency(),
                "glass_blur_enabled" to settingsManager.getGlassBlurEnabled(),
                "is_global_app_proxy" to settingsManager.isGlobalAppProxy(),
                "per_app_mode" to settingsManager.getPerAppMode(),
                "block_quic" to settingsManager.isBlockQuic(),
                "block_ipv6" to settingsManager.isBlockIpv6(),
                "ad_blocker" to settingsManager.isAdBlocker(),
                "ad_block_allowlist" to settingsManager.getAdBlockAllowlist().joinToString("\n"),
                "fake_dns" to settingsManager.isFakeDns(),
                "enable_sniffing" to settingsManager.isEnableSniffing(),
                "route_only_sniffing" to settingsManager.isRouteOnlySniffing(),
                "dns_provider" to settingsManager.getDnsProvider(),
                "ping_mode" to settingsManager.getPingMode(),
                "bypass_ai" to settingsManager.isBypassAi(),
                "ai_routing_mode" to settingsManager.getAiRoutingMode(),
                "ai_dns_provider" to settingsManager.getAiDnsProvider(),
                "ai_tls_fragment" to settingsManager.isAiTlsFragmentEnabled(),
                "auto_select_server" to settingsManager.isAutoSelectServer(),
                "balancer_mode" to settingsManager.getBalancerPreferences().mode.name,
                "balancer_health_seconds" to settingsManager.getBalancerPreferences().healthIntervalSeconds,
                "balancer_failures" to settingsManager.getBalancerPreferences().failureConfirmations,
                "balancer_optimization_minutes" to settingsManager.getBalancerPreferences().optimizationMinutes,
                "balancer_cooldown_seconds" to settingsManager.getBalancerPreferences().cooldownSeconds,
                "balancer_improvement_percent" to settingsManager.getBalancerPreferences().improvementPercent,
                "auto_connect_on_boot" to settingsManager.isAutoConnectOnBoot(),
                "tls_fragment_enabled" to settingsManager.isTlsFragmentEnabled()
            )

            val backup = BackupData(
                servers = servers,
                subscriptions = subscriptions,
                settings = settingsMap,
                perAppPackages = perApps,
                customRoutes = routes
            )

            val json = gson.toJson(backup)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(json)
                }
            } ?: return@withContext Result.failure(Exception("Не удалось открыть поток записи"))

            Log.i(TAG, "Экспорт резервной копии успешно завершён (${servers.size} серверов, ${subscriptions.size} подписок)")
            Result.success(servers.size)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка экспорта резервной копии", e)
            Result.failure(e)
        }
    }

    suspend fun importBackup(uri: Uri, mode: ImportMode = ImportMode.MERGE): Result<Int> = withContext(Dispatchers.IO) {
        importGate.withLock {
        try {
            val jsonContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BACKUP_BYTES) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_BYTES + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= MAX_BACKUP_BYTES) { "Backup exceeds 16 MB" }
                bytes.toString(Charsets.UTF_8)
            } ?: error("Could not open backup")
            val backup = BackupValidation.decode(jsonContent)
            val previousPreferences = settingsManager.preferenceSnapshot()
            try {
                // Room rolls back all server/subscription writes on error. Preferences are
                // compensated below; this is not a cross-store crash-atomic transaction.
                withContext(NonCancellable) {
                database.withTransaction {
                    if (mode == ImportMode.REPLACE) {
                        serverDao.clearAll()
                        subscriptionDao.clearAll()
                    }
                    val selectedId = backup.servers.firstOrNull { it.isSelected }?.id
                        ?: if (mode == ImportMode.MERGE) serverDao.getSelectedServerSync()?.id else null
                    if (selectedId != null) serverDao.clearSelection()
                    subscriptionDao.insertSubscriptions(backup.subscriptions)
                    serverDao.insertServers(backup.servers.map { it.copy(isSelected = it.id == selectedId) })
                    if (selectedId != null) serverDao.setActiveServer(selectedId)
                    if (mode == ImportMode.REPLACE) {
                        settingsManager.setPerAppPackages(backup.perAppPackages.toSet())
                        settingsManager.saveCustomRoutes(backup.customRoutes.map { it[0] to it[1] })
                    } else {
                        // Merge keeps local selections/rules; Replace restores empty lists too.
                        settingsManager.setPerAppPackages(settingsManager.getPerAppPackages() + backup.perAppPackages)
                        backup.customRoutes.forEach { settingsManager.setCustomRoute(it[0], it[1]) }
                    }
                    applySettings(backup)
                }
                }
            } catch (e: Exception) {
                withContext(NonCancellable) { settingsManager.restorePreferenceSnapshot(previousPreferences) }
                throw e
            }
            Log.i(TAG, "Backup imported (${backup.servers.size} servers, mode=$mode)")
            Result.success(backup.servers.size)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Backup import failed", e)
            Result.failure(e)
        }
        }
    }

    companion object { private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024 }

    private fun applySettings(backup: BackupData) {
            // 5. Restore settings defensively
            backup.settings.forEach { (key, value) ->
                when (key) {
                    "app_theme" -> toStringValue(value)?.let(settingsManager::setAppTheme)
                    "app_language" -> toStringValue(value)?.let(settingsManager::setAppLanguage)
                    "sub_auto_update_interval" -> toStringValue(value)?.let(settingsManager::setAutoUpdateInterval)
                    "xhttp_mode" -> toStringValue(value)?.let(settingsManager::setXhttpMode)
                    "custom_doh_url" -> toStringValue(value)?.let(settingsManager::setCustomDohUrl)
                    "mux_enabled" -> toBoolean(value)?.let(settingsManager::setMuxEnabled)
                    "mux_concurrency" -> (value as? Number)?.toInt()?.let(settingsManager::setMuxConcurrency)
                    "share_lan_enabled" -> toBoolean(value)?.let(settingsManager::setShareLanEnabled)

                    "routing_mode" -> toStringValue(value)?.let { settingsManager.setRoutingMode(it) }
                    "glass_blur_enabled" -> toBoolean(value)?.let { settingsManager.setGlassBlurEnabled(it) }
                    "glass_transparency" -> when (value) {
                        is Number -> settingsManager.setGlassTransparency(value.toFloat())
                        is String -> value.toFloatOrNull()?.let { settingsManager.setGlassTransparency(it) }
                        else -> Unit
                    }
                    "is_global_app_proxy" -> toBoolean(value)?.let { settingsManager.setGlobalAppProxy(it) }
                    "per_app_mode" -> toStringValue(value)?.let { settingsManager.setPerAppMode(it) }
                    "block_quic" -> toBoolean(value)?.let { settingsManager.setBlockQuic(it) }
                    "block_ipv6" -> toBoolean(value)?.let { settingsManager.setBlockIpv6(it) }
                    "ad_blocker" -> toBoolean(value)?.let { settingsManager.setAdBlocker(it) }
                    "ad_block_allowlist" -> toStringValue(value)?.let { raw ->
                        val domains = raw.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
                        if (domains.size <= 100 && domains.all { AdFilterCompiler.normalizeDomain(it) != null }) settingsManager.setAdBlockAllowlist(domains)
                    }
                    "fake_dns" -> toBoolean(value)?.let { settingsManager.setFakeDns(it) }
                    "enable_sniffing" -> toBoolean(value)?.let { settingsManager.setEnableSniffing(it) }
                    "route_only_sniffing" -> toBoolean(value)?.let { settingsManager.setRouteOnlySniffing(it) }
                    "dns_provider" -> toStringValue(value)?.let { settingsManager.setDnsProvider(it) }
                    "ping_mode" -> toStringValue(value)?.let { settingsManager.setPingMode(it) }
                    "bypass_ai" -> toBoolean(value)?.let { settingsManager.setBypassAi(it) }
                    "ai_routing_mode" -> toStringValue(value)?.let { settingsManager.setAiRoutingMode(it) }
                    "ai_dns_provider" -> toStringValue(value)?.let { settingsManager.setAiDnsProvider(it) }
                    "ai_tls_fragment" -> toBoolean(value)?.let { settingsManager.setAiTlsFragmentEnabled(it) }
                    "auto_select_server" -> toBoolean(value)?.let { settingsManager.setAutoSelectServer(it) }
                    "auto_connect_on_boot" -> toBoolean(value)?.let { settingsManager.setAutoConnectOnBoot(it) }
                    "tls_fragment_enabled" -> toBoolean(value)?.let { settingsManager.setTlsFragmentEnabled(it) }
                }
            }

            val previous = settingsManager.getBalancerPreferences()
            fun balancerInt(key: String, fallback: Int): Int = when (val value = backup.settings[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: fallback
                else -> fallback
            }
            settingsManager.setBalancerPreferences(previous.copy(
                mode = runCatching { BalancerMode.valueOf(backup.settings["balancer_mode"] as String) }.getOrDefault(previous.mode),
                healthIntervalSeconds = balancerInt("balancer_health_seconds", previous.healthIntervalSeconds),
                failureConfirmations = balancerInt("balancer_failures", previous.failureConfirmations),
                optimizationMinutes = balancerInt("balancer_optimization_minutes", previous.optimizationMinutes),
                cooldownSeconds = balancerInt("balancer_cooldown_seconds", previous.cooldownSeconds),
                improvementPercent = balancerInt("balancer_improvement_percent", previous.improvementPercent)
            ))
            // Preset setters may set defaults: restore explicit custom values afterwards.
            backup.settings["tls_fragment_preset"]?.let { settingsManager.setTlsFragmentPreset(it.toString()) }
            backup.settings["tls_fragment_length"]?.let { settingsManager.setTlsFragmentLength(it.toString()) }
            backup.settings["tls_fragment_interval"]?.let { settingsManager.setTlsFragmentInterval(it.toString()) }
            backup.settings["tls_fragment_packets"]?.let { settingsManager.setTlsFragmentPackets(it.toString()) }
    }

    private fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    private fun toStringValue(value: Any?): String? = when (value) {
        is String -> value
        null -> null
        else -> value.toString()
    }
}
