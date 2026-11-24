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
    private val database: AppDatabase,
    private val autoBackupManager: AutoBackupManager,
    private val torRepository: TorRepository? = null
) {
    private val importGate = Mutex()
    private val journal = BackupImportJournal(context, database, settingsManager, torRepository)

    internal suspend fun recoverInterruptedImport() = withContext(Dispatchers.IO) {
        importGate.withLock { journal.recover() }
    }

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
        val customRoutes: List<List<String>> = emptyList(),
        val torSettings: TorSettings? = null
    )

    suspend fun exportBackup(uri: Uri, password: CharArray? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val (servers, subscriptions) = database.withTransaction {
                serverDao.getAllServersSync() to subscriptionDao.getAllSubscriptionsSync()
            }
            val perApps = settingsManager.getPerAppPackages().toList()
            val routes = settingsManager.getCustomRoutes().map { listOf(it.first, it.second) }

            val settingsMap = mutableMapOf<String, Any>(
                "app_theme" to settingsManager.getAppTheme(),
                "app_language" to settingsManager.getAppLanguage(),
                "ui_mode" to settingsManager.getUiMode().id,
                "regional_presets_enabled" to settingsManager.regionalPresetsEnabled(),
                "servers_sort_by_ping" to settingsManager.isSortServersByPing(),
                "sub_auto_update_interval" to settingsManager.getAutoUpdateInterval(),
                "last_sub_update_time" to settingsManager.getLastAutoUpdateTime().toString(),
                "updates_wifi_only" to settingsManager.isUpdatesWifiOnly(),
                "checks_wifi_only" to settingsManager.isChecksWifiOnly(),
                "xhttp_mode" to settingsManager.getXhttpMode(),
                "custom_doh_url" to settingsManager.getCustomDohUrl(),
                "tls_mixed_sni" to settingsManager.isTlsMixedSniEnabled(),
                "tls_fragment_preset" to settingsManager.getTlsFragmentPreset(),
                "tls_fragment_length" to settingsManager.getTlsFragmentLength(),
                "tls_fragment_interval" to settingsManager.getTlsFragmentInterval(),
                "tls_fragment_packets" to settingsManager.getTlsFragmentPackets(),
                "mux_enabled" to settingsManager.isMuxEnabled(),
                "mux_concurrency" to settingsManager.getMuxConcurrency(),
                "share_lan_enabled" to settingsManager.isShareLanEnabled(),
                "lan_interface" to settingsManager.getLanInterface(),
                "lan_auth_enabled" to settingsManager.isLanAuthenticationEnabled(),
                "routing_mode" to settingsManager.getRoutingMode(),
                "routing_region" to settingsManager.getRoutingRegion(),
                "mtu" to settingsManager.getMtu(),
                "glass_transparency" to settingsManager.getGlassTransparency(),
                "glass_blur_enabled" to settingsManager.getGlassBlurEnabled(),
                "ui_animations_enabled" to settingsManager.getPerformanceSettings().animationsEnabled,
                "ui_decorative_background_enabled" to settingsManager.getPerformanceSettings().decorativeBackgroundEnabled,
                "ui_glass_highlights_enabled" to settingsManager.getPerformanceSettings().glassHighlightsEnabled,
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
                "ai_dns_auto_switch" to settingsManager.isAiDnsAutoSwitchEnabled(),
                "auto_select_server" to settingsManager.isAutoSelectServer(),
                "balancer_mode" to settingsManager.getBalancerPreferences().mode.name,
                "balancer_health_seconds" to settingsManager.getBalancerPreferences().healthIntervalSeconds,
                "balancer_failures" to settingsManager.getBalancerPreferences().failureConfirmations,
                "balancer_optimization_minutes" to settingsManager.getBalancerPreferences().optimizationMinutes,
                "balancer_cooldown_seconds" to settingsManager.getBalancerPreferences().cooldownSeconds,
                "balancer_improvement_percent" to settingsManager.getBalancerPreferences().improvementPercent,
                "balancer_metric" to settingsManager.getBalancerPreferences().metric.name,
                "balancer_goal" to settingsManager.getBalancerPreferences().goal.name,
                "balancer_warm_standby" to settingsManager.getBalancerPreferences().warmStandby,
                "balancer_speed_budget_mib" to settingsManager.getBalancerPreferences().speedBudgetMiB,
                "auto_connect_on_boot" to settingsManager.isAutoConnectOnBoot(),
                "auto_connect_on_open" to settingsManager.isAutoConnectOnOpen(),
                "source_downloads_via_vpn" to settingsManager.isSourceDownloadsViaVpn(),
                "subscription_hwid_v1" to settingsManager.subscriptionHwidOverridesJson(),
                "subscription_ua_overrides_v1" to settingsManager.subscriptionUserAgentsJson(),
                "tls_fragment_enabled" to settingsManager.isTlsFragmentEnabled(),
                "connection_profiles_v1" to settingsManager.connectionProfilesJson(),
                "advanced_routes_v1" to settingsManager.advancedRoutingJson(),
                "disabled_server_ids" to settingsManager.getDisabledServerIds().sorted().joinToString("\n")
            )
            settingsMap.putAll(settingsManager.getNotificationPreferences().values())
            // LAN credentials and Tor configuration travel only in an authenticated encrypted envelope.
            if (password != null) settingsMap["lan_password"] = settingsManager.getLanPassword()

            val backup = BackupData(
                servers = servers,
                subscriptions = subscriptions,
                settings = settingsMap,
                perAppPackages = perApps,
                customRoutes = routes,
                torSettings = if (password != null) torRepository?.load() else null
            )

            val plaintext = gson.toJson(backup)
            val json = if (password != null) BackupCipher.encrypt(plaintext, password) else plaintext
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(json)
                }
            } ?: return@withContext Result.failure(Exception("Не удалось открыть поток записи"))

            Log.i(TAG, "Экспорт резервной копии успешно завершён (${servers.size} серверов, ${subscriptions.size} подписок)")
            Result.success(servers.size)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка экспорта резервной копии", e)
            Result.failure(e)
        }
    }

    suspend fun importBackup(uri: Uri, mode: ImportMode = ImportMode.MERGE, password: CharArray? = null): Result<Int> = withContext(Dispatchers.IO) {
        importGate.withLock {
        try {
            journal.recover()
            val jsonContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BACKUP_BYTES * 2) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_BYTES * 2 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= MAX_BACKUP_BYTES * 2) { "Backup exceeds 16 MB" }
                bytes.toString(Charsets.UTF_8)
            } ?: error("Could not open backup")
            val backup = BackupValidation.decode(BackupCipher.decryptIfNeeded(jsonContent, password))
            val token = journal.begin(includeTor = backup.torSettings != null)
            try {
                val applyImport: suspend () -> Unit = {
                    withContext(NonCancellable) {
                        database.withTransaction {
                            if (mode == ImportMode.REPLACE) {
                                serverDao.clearAll()
                                subscriptionDao.clearAll()
                                subscriptionDao.clearUpdateHistory()
                            }
                            val selectedId = backup.servers.firstOrNull { it.isSelected }?.id
                                ?: if (mode == ImportMode.MERGE) serverDao.getSelectedServerSync()?.id else null
                            if (selectedId != null) serverDao.clearSelection()
                            subscriptionDao.insertSubscriptions(backup.subscriptions)
                            serverDao.insertServers(backup.servers.map { it.copy(isSelected = it.id == selectedId) })
                            if (selectedId != null) serverDao.setActiveServer(selectedId)
                            settingsManager.setSelectedServerName(serverDao.getSelectedServerSync()?.name.orEmpty())
                            if (mode == ImportMode.REPLACE) {
                                settingsManager.setPerAppPackages(backup.perAppPackages.toSet())
                                settingsManager.saveCustomRoutes(backup.customRoutes.filter { it.size >= 2 }.map { it[0] to it[1] })
                            } else {
                                // Merge keeps local selections/rules; Replace restores empty lists too.
                                settingsManager.setPerAppPackages(settingsManager.getPerAppPackages() + backup.perAppPackages)
                                backup.customRoutes.forEach { if (it.size >= 2) settingsManager.setCustomRoute(it[0], it[1]) }
                            }
                            if (mode == ImportMode.REPLACE && "advanced_routes_v1" !in backup.settings) settingsManager.storeAdvancedRouting("[]")
                            if (mode == ImportMode.REPLACE && "subscription_hwid_v1" !in backup.settings) settingsManager.storeSubscriptionHwidOverrides("{}")
                            (backup.settings["subscription_hwid_v1"] as? String)?.let {
                                settingsManager.storeSubscriptionHwidOverrides(it, merge = mode == ImportMode.MERGE)
                            }
                            (backup.settings["subscription_ua_overrides_v1"] as? String)?.let {
                                settingsManager.storeSubscriptionUserAgents(it, backup.subscriptions.takeIf { mode == ImportMode.MERGE })
                            }
                            applySettings(backup, mode)
                            backup.torSettings?.let { tor ->
                                requireNotNull(torRepository) { "Tor configuration storage is unavailable" }.save(tor)
                            }
                            // Flush preferences before the SQLite transaction can become durable.
                            settingsManager.restorePreferenceSnapshot(settingsManager.preferenceSnapshot(), rescheduleUpdates = false)
                            journal.markCommitted(token)
                            // Reject deferred constraints while rollback is still controlled by Room.
                            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                                check(!cursor.moveToFirst()) { "Backup violates database relationships" }
                            }
                        }
                    }
                }
                if (mode == ImportMode.REPLACE) autoBackupManager.deleteAndBackup() { applyImport() }
                else { applyImport(); autoBackupManager.backupToVault() }
            } catch (e: Exception) {
                withContext(NonCancellable) { journal.recover() }
                throw e
            }
            withContext(NonCancellable) {
                journal.recover()
                SubscriptionUpdateWorker.schedule(context, settingsManager.getAutoUpdateInterval())
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

    /** Portable encrypted document used by LAN pairing and WebDAV, without granting file access. */
    suspend fun exportDocument(password: CharArray): Result<String> = withContext(Dispatchers.IO) {
        val file = java.io.File.createTempFile("device-export-", ".json", context.cacheDir)
        try {
            exportBackup(Uri.fromFile(file), password).map { file.readText(Charsets.UTF_8) }
        } finally { file.delete() }
    }

    suspend fun previewDocument(document: String, password: CharArray, allowPlaintext: Boolean = false): Result<TransferPreview> = withContext(Dispatchers.IO) {
        try {
            require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Transfer exceeds 32 MB" }
            if (!allowPlaintext) DeviceTransferProtocol.requireEncrypted(document)
            val backup = BackupValidation.decode(BackupCipher.decryptIfNeeded(document, password))
            Result.success(TransferPreview(backup.servers.size, backup.subscriptions.size,
                backup.torSettings != null, backup.settings.containsKey("lan_password"),
                encrypted = com.google.gson.JsonParser.parseString(document).asJsonObject["app"]?.asString == "SplateVPN-Encrypted",
                customRoutes = backup.customRoutes.size,
                advancedRules = AdvancedRoutingRules.decode(backup.settings["advanced_routes_v1"] as? String ?: "[]").size,
                subscriptionUpdateInterval = backup.settings["sub_auto_update_interval"] as? String))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun importDocument(document: String, password: CharArray, mode: ImportMode, allowPlaintext: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val file = java.io.File.createTempFile("device-import-", ".json", context.cacheDir)
        try {
            require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Transfer exceeds 32 MB" }
            if (!allowPlaintext) DeviceTransferProtocol.requireEncrypted(document)
            file.writeText(document, Charsets.UTF_8)
            importBackup(Uri.fromFile(file), mode, password)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error)
        } finally { file.delete() }
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = MAX_BACKUP_BYTES * 2
    }

    private fun applySettings(backup: BackupData, mode: ImportMode) {
            // 5. Restore settings defensively
            backup.settings.forEach { (key, value) ->
                when (key) {
                    in NotificationPreferences.keys -> toBoolean(value)?.let { enabled ->
                        val values = settingsManager.getNotificationPreferences().values() + (key to enabled)
                        settingsManager.setNotificationPreferences(NotificationPreferences.read { name, default -> values[name] ?: default })
                    }
                    "app_theme" -> toStringValue(value)?.let(settingsManager::setAppTheme)
                    "app_language" -> toStringValue(value)?.let(settingsManager::setAppLanguage)
                    "ui_mode" -> settingsManager.setUiMode(AppUiMode.fromId(toStringValue(value)))
                    "regional_presets_enabled" -> toBoolean(value)?.let(settingsManager::setRegionalPresetsEnabled)
                    "servers_sort_by_ping" -> toBoolean(value)?.let(settingsManager::setSortServersByPing)
                    "sub_auto_update_interval" -> toStringValue(value)?.let {
                        settingsManager.setAutoUpdateInterval(it, reschedule = false)
                    }
                    "last_sub_update_time" -> (value as? String)?.toLongOrNull()?.let {
                        val restored = if (mode == ImportMode.MERGE) maxOf(it, settingsManager.getLastAutoUpdateTime()) else it
                        settingsManager.setLastAutoUpdateTime(restored)
                    }
                    "updates_wifi_only" -> toBoolean(value)?.let(settingsManager::setUpdatesWifiOnly)
                    "checks_wifi_only" -> toBoolean(value)?.let(settingsManager::setChecksWifiOnly)
                    "xhttp_mode" -> toStringValue(value)?.let(settingsManager::setXhttpMode)
                    "custom_doh_url" -> toStringValue(value)?.let(settingsManager::setCustomDohUrl)
                    "mux_enabled" -> toBoolean(value)?.let(settingsManager::setMuxEnabled)
                    "mux_concurrency" -> (value as? Number)?.toInt()?.let(settingsManager::setMuxConcurrency)
                    "lan_auth_enabled" -> toBoolean(value)?.let(settingsManager::setLanAuthenticationEnabled)
                    "lan_password" -> toStringValue(value)?.let { password ->
                        settingsManager.restorePreferenceSnapshot(
                            settingsManager.preferenceSnapshot() + ("lan_password" to password), rescheduleUpdates = false
                        )
                    }
                    "lan_interface" -> toStringValue(value)?.let(settingsManager::setLanInterface)
                    "share_lan_enabled" -> toBoolean(value)?.let(settingsManager::setShareLanEnabled)

                    "routing_mode" -> toStringValue(value)?.let { settingsManager.setRoutingMode(it) }
                    "routing_region" -> toStringValue(value)?.let(settingsManager::setRoutingRegion)
                    "mtu" -> (value as? Number)?.toInt()?.let(settingsManager::setMtu)
                    "glass_blur_enabled" -> toBoolean(value)?.let { settingsManager.setGlassBlurEnabled(it) }
                    "ui_animations_enabled" -> toBoolean(value)?.let(settingsManager::setUiAnimationsEnabled)
                    "ui_decorative_background_enabled" -> toBoolean(value)?.let(settingsManager::setDecorativeBackgroundEnabled)
                    "ui_glass_highlights_enabled" -> toBoolean(value)?.let(settingsManager::setGlassHighlightsEnabled)
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
                    "ai_dns_auto_switch" -> toBoolean(value)?.let { settingsManager.setAiDnsAutoSwitchEnabled(it) }
                    "auto_select_server" -> toBoolean(value)?.let { settingsManager.setAutoSelectServer(it) }
                    "auto_connect_on_boot" -> toBoolean(value)?.let { settingsManager.setAutoConnectOnBoot(it) }
                    "auto_connect_on_open" -> toBoolean(value)?.let { settingsManager.setAutoConnectOnOpen(it) }
                    "source_downloads_via_vpn" -> toBoolean(value)?.let(settingsManager::setSourceDownloadsViaVpn)
                    "tls_mixed_sni" -> toBoolean(value)?.let(settingsManager::setTlsMixedSniEnabled)
                    "tls_fragment_enabled" -> toBoolean(value)?.let { settingsManager.setTlsFragmentEnabled(it) }
                    "connection_profiles_v1" -> toStringValue(value)?.let { settingsManager.storeConnectionProfiles(it) }
                    "advanced_routes_v1" -> toStringValue(value)?.let { settingsManager.storeAdvancedRouting(it) }
                    "disabled_server_ids" -> toStringValue(value)?.let { raw -> settingsManager.storeDisabledServerIds(raw.lineSequence().filter { it.isNotBlank() }.toSet()) }
                }
            }

            val previous = settingsManager.getBalancerPreferences()
            fun balancerInt(key: String, fallback: Int): Int = when (val value = backup.settings[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: fallback
                else -> fallback
            }
            settingsManager.setBalancerPreferences(previous.copy(
                mode = runCatching { BalancerMode.valueOf(toStringValue(backup.settings["balancer_mode"]).orEmpty()) }.getOrDefault(previous.mode),
                healthIntervalSeconds = balancerInt("balancer_health_seconds", previous.healthIntervalSeconds),
                failureConfirmations = balancerInt("balancer_failures", previous.failureConfirmations),
                optimizationMinutes = balancerInt("balancer_optimization_minutes", previous.optimizationMinutes),
                cooldownSeconds = balancerInt("balancer_cooldown_seconds", previous.cooldownSeconds),
                improvementPercent = balancerInt("balancer_improvement_percent", previous.improvementPercent),
                metric = runCatching { BalancerMetric.valueOf(toStringValue(backup.settings["balancer_metric"]).orEmpty()) }.getOrDefault(previous.metric),
                goal = runCatching { BalancerGoal.valueOf(toStringValue(backup.settings["balancer_goal"]).orEmpty()) }.getOrDefault(previous.goal),
                warmStandby = toBoolean(backup.settings["balancer_warm_standby"]) ?: previous.warmStandby,
                speedBudgetMiB = balancerInt("balancer_speed_budget_mib", previous.speedBudgetMiB)
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
