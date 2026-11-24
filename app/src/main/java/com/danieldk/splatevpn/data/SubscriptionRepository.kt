package com.danieldk.splatevpn.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager,
    private val autoBackupManager: AutoBackupManager,
    private val database: AppDatabase,
    private val transport: SubscriptionTransport,
    private val hwidStore: SubscriptionHwidStore = settingsManager.subscriptionHwidStore
) {
    private val TAG = "SubscriptionRepository"
    private val updateLocks = Array(64) { Mutex() }
    private val _updatingIds = MutableStateFlow<Set<String>>(emptySet())
    val updatingIds = _updatingIds.asStateFlow()
    private fun sourceLoader(sourceUrl: String): SubscriptionSourceLoader {
        val identity = if (settingsManager.isSubscriptionHwidEnabled(sourceUrl)) {
            SubscriptionRequestIdentity(sourceUrl, hwidStore.idForSource(sourceUrl))
        } else null
        return SubscriptionSourceLoader(fetch = { url, agent, viaVpn ->
            settingsManager.withUpdateNetwork {
                try {
                    transport.fetch(url, agent, viaVpn, identity)
                } catch (e: Exception) {
                    if (!viaVpn && e is IOException && com.danieldk.splatevpn.vpn.LocalProxyAccess.isAvailable) {
                        Log.i(TAG, "Прямая загрузка подписки не удалась, повтор через активный туннель VPN: ${e.message}")
                        transport.fetch(url, agent, true, identity)
                    } else throw e
                }
            }
        }, deviceBound = identity != null, userAgent = settingsManager.getSubscriptionUserAgent(sourceUrl) ?: SubscriptionUserAgent.AUTO)
    }
    fun updateHistory(id: String) = subscriptionDao.updateHistory(id)

    private suspend fun recordResult(before: SubscriptionEntity, error: String, retryable: Boolean) {
        database.withTransaction {
            val latest = subscriptionDao.getSubscriptionById(before.id) ?: return@withTransaction
            subscriptionDao.recordUpdateResult(before.id, error, retryable, before.nodeCount)
            subscriptionDao.insertUpdateEvent(SubscriptionUpdateEvent(subscriptionId = before.id,
                attemptedAt = latest.lastAttemptAt, successful = error.isEmpty(), previousNodeCount = before.nodeCount,
                nodeCount = latest.nodeCount, error = error, retryable = retryable))
            subscriptionDao.trimUpdateHistory(before.id)
        }
    }

    suspend fun updateSubscription(subId: String): Result<Int> = updateLocks[(subId.hashCode() and Int.MAX_VALUE) % updateLocks.size].withLock {
        if (!settingsManager.areUpdatesAllowed()) return@withLock Result.failure(WifiRequiredException())
        val before = subscriptionDao.getSubscriptionById(subId)
            ?: return@withLock Result.failure(IllegalArgumentException("Subscription not found"))
        _updatingIds.update { it + subId }
        try {
            val attemptedAt = System.currentTimeMillis()
            subscriptionDao.recordAttempt(subId, attemptedAt)
            val result = try {
                updateSubscriptionInternal(subId)
            } catch (error: CancellationException) {
                withContext(NonCancellable) { recordResult(before, "Update interrupted", true) }
                throw error
            }
            val error = result.exceptionOrNull()
            withContext(NonCancellable) {
                if (error is WifiRequiredException) {
                    subscriptionDao.restoreUpdateAttempt(subId, attemptedAt, before.lastAttemptAt, before.lastError, before.lastFailureRetryable)
                } else {
                    recordResult(before, if (error == null) "" else subscriptionFailureLabel(error),
                        error != null && isRetryableSubscriptionFailure(error))
                }
            }
            if (error !is WifiRequiredException) autoBackupManager.backupToVault()
            result
        } finally {
            _updatingIds.update { it - subId }
        }
    }
    private fun safeSubscriptionHost(url: String): String = try {
        java.net.URI(url).host ?: "сервер подписки"
    } catch (_: Exception) {
        "сервер подписки"
    }

    private fun subscriptionNetworkError(error: Throwable, url: String): Exception {
        if (error is WifiRequiredException) return error
        if (error is SubscriptionHwidException) return error
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        val host = safeSubscriptionHost(url)
        return when {
            causes.any { it is java.security.cert.CertificateExpiredException } ->
                IOException("TLS-сертификат сервера $host истёк. Обновите сертификат на сервере подписки.", error)
            causes.any {
                it is javax.net.ssl.SSLHandshakeException ||
                        it is javax.net.ssl.SSLPeerUnverifiedException ||
                        it is java.security.cert.CertificateException
            } -> IOException(
                "Сертификат HTTPS сервера $host не прошёл проверку. Проверьте его срок действия, цепочку доверия и имя домена.",
                error
            )
            else -> if (error is IOException) IOException("Ошибка загрузки подписки", error)
                else Exception(error.message ?: "Некорректные данные подписки", error)
        }
    }

    suspend fun importSubscription(subUrl: String, subTitle: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            settingsManager.requireUpdatesNetwork()
            val input = subUrl.trim()
            if (input.isBlank()) {
                return@withContext Result.failure(Exception("URL подписки пуст"))
            }
            if (LinkParser.isUnsupportedEncryptedSubscriptionLink(input)) {
                return@withContext Result.failure(
                    Exception("Этот формат шифрования подписки пока не поддерживается. Запросите у провайдера обычную HTTPS-ссылку или INCY crypt1.")
                )
            }
            val incy = if (IncyLinkCodec.isSupportedLink(input)) IncyLinkCodec.decode(input) else null
            val trimmedUrl = LinkParser.normalizeSubscriptionUrl(input)
                ?: return@withContext Result.failure(Exception("Ожидается HTTPS/HTTP-ссылка подписки или ссылка Happ/INCY с такой ссылкой внутри"))

            Log.d(TAG, "importSubscription: Fetching subscription")
            val parsedHost = try { java.net.URI(trimmedUrl).host } catch (e: Exception) { null }
            val subId = UUID.randomUUID().toString()
            fun responseName(response: SubscriptionResponse): String = subTitle?.takeIf { it.isNotBlank() && it != trimmedUrl }
                ?: SubscriptionMetadata.from(response).title.takeIf { it.isNotBlank() }
                ?: incy?.name
                ?: parsedHost
                ?: "Подписка"
            val loaded = sourceLoader(trimmedUrl).load(trimmedUrl, settingsManager.isSourceDownloadsViaVpn()) { response ->
                parseUsableSubscription(response.content, subId, responseName(response))
            }
            val subName = responseName(loaded.response)
            val nodes = loaded.nodes
            val importedRules = SubscriptionRoutingRules.parse(loaded.response.content)
            val userInfo = parseSubscriptionUserInfo(loaded.response.header("Subscription-Userinfo"))
            if (loaded.usedFallback) Log.i(TAG, "importSubscription: Использован резервный CIDR-источник")
            Log.i(TAG, "importSubscription: Total parsed nodes = ${nodes.size}")

            require(nodes.size <= SubscriptionTransport.MAX_NODES) { "Too many subscription nodes" }
            if (nodes.isNotEmpty()) {
                database.withTransaction {
                    val currentServers = serverDao.getAllServersSync()
                    val firstUsable = nodes.firstOrNull { it.address != "127.0.0.1" && it.address != "0.0.0.0" && it.port > 0 } ?: nodes.firstOrNull()
                    val toInsert = if (currentServers.isEmpty()) {
                        nodes.map { node -> if (node.id == firstUsable?.id) node.copy(isSelected = true) else node }
                    } else {
                        nodes
                    }
                    serverDao.insertServers(toInsert)
                    subscriptionDao.insertSubscription(
                        SubscriptionEntity(
                            id = subId,
                            name = subName,
                            publisherMetadataJson = SubscriptionMetadata.from(loaded.response).encode(),
                            url = if (incy != null) input else trimmedUrl,
                            lastUpdated = System.currentTimeMillis(),
                            nodeCount = nodes.size,
                            autoUpdate = true,
                            importedRulesJson = importedRules.json,
                            importedRulesSkipped = importedRules.skipped,
                            uploadBytes = userInfo.upload,
                            downloadBytes = userInfo.download,
                            totalBytes = userInfo.total,
                            expireTimestamp = userInfo.expire
                        )
                    )
                }
                settingsManager.setSubscriptionUrl(if (incy != null) input else trimmedUrl)
                autoBackupManager.backupToVault()
                Result.success(nodes.size)
            } else {
                Log.e(TAG, "importSubscription: No valid nodes parsed")
                Result.failure(Exception("В подписке не найдено подходящих узлов (VLESS, VMess, SS, Trojan, Clash)"))
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val url = LinkParser.normalizeSubscriptionUrl(subUrl) ?: subUrl
            Log.e(TAG, "importSubscription failed for ${safeSubscriptionHost(url)}: ${e.javaClass.simpleName}")
            Result.failure(subscriptionNetworkError(e, url))
        }
    }

    private suspend fun updateSubscriptionInternal(subId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sub = subscriptionDao.getSubscriptionById(subId)
                ?: return@withContext Result.failure(Exception("Подписка не найдена"))

            if (sub.url.isBlank()) {
                return@withContext Result.failure(Exception("У подписки нет URL для обновления"))
            }

            if (LinkParser.isUnsupportedEncryptedSubscriptionLink(sub.url)) {
                return@withContext Result.failure(Exception("Этот формат шифрования подписки пока не поддерживается. Запросите у провайдера обычную HTTPS-ссылку или INCY crypt1."))
            }
            val trimmedUrl = LinkParser.normalizeSubscriptionUrl(sub.url)
                ?: return@withContext Result.failure(Exception("Некорректный URL подписки"))
            Log.d(TAG, "updateSubscription: Fetching subscription")
            val loaded = sourceLoader(trimmedUrl).load(trimmedUrl, settingsManager.isSourceDownloadsViaVpn()) { response ->
                parseUsableSubscription(response.content, sub.id, SubscriptionMetadata.from(response).displayName(sub))
            }
            var nodes = loaded.nodes
            val importedRules = SubscriptionRoutingRules.parse(loaded.response.content)
            val userInfo = parseSubscriptionUserInfo(loaded.response.header("Subscription-Userinfo"))
            if (loaded.usedFallback) Log.i(TAG, "updateSubscription: Использован резервный CIDR-источник")
            require(nodes.size <= SubscriptionTransport.MAX_NODES) { "Too many subscription nodes" }
            if (nodes.isNotEmpty()) {
                val saved = database.withTransaction {
                    val latestSub = subscriptionDao.getSubscriptionById(sub.id)
                        ?: return@withTransaction false
                    val metadata = SubscriptionMetadata.from(loaded.response)
                    val updatedName = metadata.displayName(latestSub)
                    check(latestSub.url == sub.url) { "Subscription URL changed. Please update again." }
                    nodes = SubscriptionNodeMerger.merge(serverDao.getServersBySubIdSync(sub.id), nodes,
                        retainWorkingNodes = latestSub.retainWorkingNodes)
                        .map { it.copy(subName = updatedName) }
                    serverDao.deleteServersBySubId(sub.id)
                    serverDao.insertServers(nodes)
                    if (serverDao.getSelectedServerSync() == null) {
                        val fallback = nodes.firstOrNull { it.address != "127.0.0.1" && it.address != "0.0.0.0" && it.port > 0 } ?: nodes.first()
                        serverDao.setActiveServer(fallback.id)
                    }
                    subscriptionDao.insertSubscription(
                        latestSub.copy(
                            lastUpdated = System.currentTimeMillis(),
                            nodeCount = nodes.size,
                            retainedNodeCount = nodes.count { it.isRetained },
                            importedRulesJson = importedRules.json,
                            importedRulesSkipped = importedRules.skipped,
                            useSubscriptionRules = latestSub.useSubscriptionRules && importedRules.json.isNotEmpty() &&
                                importedRules.skipped == 0 && importedRules.json == latestSub.importedRulesJson,
                            name = updatedName,
                            publisherMetadataJson = metadata.encode(),
                            uploadBytes = if (userInfo.total > 0L || userInfo.upload > 0L) userInfo.upload else latestSub.uploadBytes,
                            downloadBytes = if (userInfo.total > 0L || userInfo.download > 0L) userInfo.download else latestSub.downloadBytes,
                            totalBytes = if (userInfo.total > 0L) userInfo.total else latestSub.totalBytes,
                            expireTimestamp = if (userInfo.expire > 0L) userInfo.expire else latestSub.expireTimestamp
                        )
                    )
                    true
                }
                if (!saved) return@withContext Result.failure(Exception("Подписка удалена во время обновления"))
                Result.success(nodes.size)
            } else {
                Result.failure(Exception("В обновлении не найдено подходящих узлов"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateSubscription failed: ${e.javaClass.simpleName}")
            Result.failure(subscriptionNetworkError(e, ""))
        }
    }

    private data class SubscriptionUserInfo(
        val upload: Long = 0L,
        val download: Long = 0L,
        val total: Long = 0L,
        val expire: Long = 0L
    )

    private fun parseSubscriptionUserInfo(headerValue: String?): SubscriptionUserInfo {
        if (headerValue.isNullOrBlank()) return SubscriptionUserInfo()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        headerValue.split(';').forEach { part ->
            val eqIdx = part.indexOf('=')
            if (eqIdx > 0) {
                val key = part.substring(0, eqIdx).trim().lowercase()
                val value = part.substring(eqIdx + 1).trim().toLongOrNull() ?: 0L
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    suspend fun deleteSubscription(subId: String) = withContext(Dispatchers.IO) {
        autoBackupManager.deleteAndBackup {
            serverDao.deleteServersBySubId(subId)
            subscriptionDao.deleteSubscription(subId)
            subscriptionDao.deleteUpdateHistory(subId)
        }
    }

    suspend fun setSubscriptionEnabled(subId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        subscriptionDao.updateSubscriptionEnabled(subId, enabled)
        autoBackupManager.backupToVault()
    }

    suspend fun setSubscriptionAutoUpdate(subId: String, autoUpdate: Boolean) = withContext(Dispatchers.IO) {
        subscriptionDao.updateSubscriptionAutoUpdate(subId, autoUpdate)
        autoBackupManager.backupToVault()
    }

    /** Disabling the reserve removes its stale provider entries immediately. */
    suspend fun setSubscriptionRetention(subId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val update: suspend () -> Unit = {
            database.withTransaction {
                subscriptionDao.updateSubscriptionRetention(subId, enabled)
                if (!enabled) purgeRetainedNodesInternal(subId)
            }
        }
        if (enabled) { update(); autoBackupManager.backupToVault() }
        else autoBackupManager.deleteAndBackup { update() }
    }

    suspend fun purgeRetainedNodes(subId: String): Int = withContext(Dispatchers.IO) {
        var removed = 0
        autoBackupManager.deleteAndBackup {
            database.withTransaction { removed = purgeRetainedNodesInternal(subId) }
        }
        removed
    }

    suspend fun pruneExpiredRetainedNodes() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var changed = false
        database.withTransaction {
            val retainedBySub = serverDao.getRetainedServersSync().groupBy { it.subId }
            for (sub in subscriptionDao.getAllSubscriptionsSync()) {
                val retained = retainedBySub[sub.id] ?: continue
                val expired = retained.filter { !sub.retainWorkingNodes || !SubscriptionNodeMerger.canRetain(it, now) }
                if (expired.isEmpty()) continue
                val nodes = serverDao.getServersBySubIdSync(sub.id)
                expired.forEach { serverDao.deleteServer(it.id) }
                val expiredIds = expired.mapTo(hashSetOf()) { it.id }
                val remaining = nodes.filterNot { it.id in expiredIds }
                subscriptionDao.insertSubscription(sub.copy(nodeCount = remaining.size,
                    retainedNodeCount = remaining.count { it.isRetained }))
                changed = true
            }
            if (serverDao.getSelectedServerSync() == null) {
                serverDao.getAllServersSync().firstOrNull()?.let { serverDao.setActiveServer(it.id) }
            }
        }
        if (changed) autoBackupManager.backupToVault()
    }

    private suspend fun purgeRetainedNodesInternal(subId: String): Int {
        val sub = subscriptionDao.getSubscriptionById(subId) ?: return 0
        val nodes = serverDao.getServersBySubIdSync(subId)
        val retained = nodes.filter { it.isRetained }
        retained.forEach { serverDao.deleteServer(it.id) }
        val remaining = nodes.filterNot { it.isRetained }
        if (serverDao.getSelectedServerSync() == null) {
            (remaining.firstOrNull() ?: serverDao.getAllServersSync().firstOrNull())?.let { serverDao.setActiveServer(it.id) }
        }
        subscriptionDao.insertSubscription(sub.copy(nodeCount = remaining.size, retainedNodeCount = 0))
        return retained.size
    }

    suspend fun setSubscriptionRulesEnabled(subId: String, enabled: Boolean, expectedRulesJson: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                val sub = requireNotNull(subscriptionDao.getSubscriptionById(subId)) { "Subscription not found" }
                if (enabled) {
                    val current = SubscriptionRoutingRules.validateStored(sub.importedRulesJson)
                    require(sub.importedRulesSkipped == 0 && SubscriptionRoutingRules.preview(current).isNotEmpty()) {
                        "Subscription has no complete supported routing rules"
                    }
                    if (expectedRulesJson != null) require(current == SubscriptionRoutingRules.validateStored(expectedRulesJson)) {
                        "Subscription rules changed. Review the updated rules before enabling them."
                    }
                }
                subscriptionDao.updateSubscriptionRulesEnabled(subId, enabled)
            }
            autoBackupManager.backupToVault()
            Result.success(Unit)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun editSubscription(subId: String, name: String, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(name.isNotBlank()) { "Subscription name is required" }
            val normalized = LinkParser.normalizeSubscriptionUrl(url)
            val validUrl = normalized?.let { runCatching { java.net.URI(it).host?.isNotBlank() == true }.getOrDefault(false) } == true
            require(validUrl || LinkParser.isUnsupportedEncryptedSubscriptionLink(url)) { "Invalid subscription URL" }
            val storedUrl = if (IncyLinkCodec.isSupportedLink(url)) url.trim() else normalized ?: url.trim()
            database.withTransaction {
                val existing = checkNotNull(subscriptionDao.getSubscriptionById(subId)) { "Subscription no longer exists" }
                subscriptionDao.updateSubscriptionDetails(subId, name.trim(), storedUrl)
                if (existing.url != storedUrl) {
                    val latest = requireNotNull(subscriptionDao.getSubscriptionById(subId))
                    subscriptionDao.insertSubscription(latest.copy(useSubscriptionRules = false,
                        importedRulesJson = "", importedRulesSkipped = 0))
                    purgeRetainedNodesInternal(subId)
                }
                serverDao.updateSubscriptionName(subId, name.trim())
            }
            autoBackupManager.backupToVault()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAllSubscriptions(): Int = refreshSubscriptions(manual = false).updated

    private val refreshMutex = Mutex()

    suspend fun refreshSubscriptions(manual: Boolean, retryOnly: Boolean = false, dueOnly: Boolean = false): SubscriptionUpdateReport =
        withContext(Dispatchers.IO) { refreshMutex.withLock {
            if (!settingsManager.areUpdatesAllowed()) {
                if (manual) throw WifiRequiredException()
                return@withLock SubscriptionUpdateReport(0, emptyList())
            }
            val interval = settingsManager.getAutoUpdateInterval()
            if (!manual && (interval == "off" || dueOnly && !SubscriptionUpdatePolicy.canRun(interval,
                    settingsManager.needsOnboarding.value, settingsManager.canUseNetworkSettings()))) {
                return@withLock SubscriptionUpdateReport(0, emptyList())
            }
            val eligible = subscriptionsToUpdate(subscriptionDao.getAllSubscriptionsSync(), manual)
                .filter { !dueOnly || manual || SubscriptionUpdatePolicy.isDue(it, interval, System.currentTimeMillis()) }
            val subs = eligible.filter { !retryOnly || (it.lastError.isNotBlank() && it.lastFailureRetryable) }
            var totalUpdated = 0
            var retryableFailures = 0
            val failures = mutableListOf<Pair<String, String>>()
            if (retryOnly) eligible.filter { it.lastError.isNotBlank() && !it.lastFailureRetryable }
                .forEach { failures += it.name to it.lastError }
            for (candidate in subs) {
                // A user can disable a source or the schedule while an earlier download runs.
                val currentInterval = settingsManager.getAutoUpdateInterval()
                if (!manual && (currentInterval == "off" || dueOnly && !SubscriptionUpdatePolicy.canRun(currentInterval,
                        settingsManager.needsOnboarding.value, settingsManager.canUseNetworkSettings()))) break
                val sub = subscriptionDao.getSubscriptionById(candidate.id) ?: continue
                if (!sub.enabled || !manual && !sub.autoUpdate) continue
                if (retryOnly && (sub.lastError.isBlank() || !sub.lastFailureRetryable)) continue
                if (dueOnly && !manual && !SubscriptionUpdatePolicy.isDue(sub, currentInterval, System.currentTimeMillis())) continue
                val res = updateSubscription(sub.id)
                if (res.isSuccess) {
                    totalUpdated++
                } else {
                    if (isRetryableSubscriptionFailure(res.exceptionOrNull()!!)) retryableFailures++
                    failures += sub.name to (res.exceptionOrNull()?.localizedMessage ?: "Update failed")
                }
            }
            if (totalUpdated > 0) settingsManager.setLastAutoUpdateTime(System.currentTimeMillis())
            SubscriptionUpdateReport(totalUpdated, failures, retryableFailures)
        } }


}
