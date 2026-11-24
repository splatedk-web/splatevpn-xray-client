package com.danieldk.splatevpn.data

import android.util.Log
import com.danieldk.splatevpn.SplateConstants
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit

@Singleton
class ServerSelectionCoordinator @Inject constructor(
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager,
    private val database: AppDatabase,
    private val probes: ServerProbeRepository,
    private val performance: CandidatePerformanceRepository
) {
    private val TAG = "ServerSelection"
    private fun calculateEffectivePing(server: ServerNode, rawPing: Long, isCurrentlyActive: Boolean = false) =
        probes.calculateEffectivePing(server, rawPing, isCurrentlyActive)
    // Параметры двухэтапного балансировщика (TCP-префильтр -> Real Delay верификация)
    private val selectionMutex = Mutex()
    private val standbyRotation = StandbyProbeRotation()
    private val discoveryRotation = CandidateDiscoveryRotation()
    private val selectionHandoff = SelectionHandoff<AutoSelectionRequest, ServerNode>(selectionMutex)
    private data class AutoSelectionRequest(
        val excluded: Set<String>, val urgent: Boolean, val commit: Boolean, val revision: Long,
        val probeSettings: List<Any>, val measureDownload: Boolean
    )
    private val qualityTracker get() = probes.qualityTracker
    private val AUTO_SELECT_SHORTLIST_SIZE = 6
    private val AUTO_SELECT_TCP_TIMEOUT_MS = 1800
    // Increased from 3500: Reality TLS handshake over mobile LTE easily exceeds 3 sec.
    // "urgent" path uses a tighter budget so the user doesn't wait too long for failure feedback.
    private fun realDelayTimeoutMs(urgent: Boolean) = if (urgent) 6000 else 8000

    /** Background checks never change selection. Two probes leave room for foreground selection. */
    suspend fun refreshStandbyServers(excludeIds: Set<String> = emptySet(), sampleDownload: Boolean = false) = withContext(Dispatchers.IO) {
        if (selectionMutex.isLocked || !settingsManager.areChecksAllowed()) return@withContext
        val prefs = settingsManager.getBalancerPreferences()
        if (!settingsManager.isAutoSelectServer() || !prefs.maintainsStandby) return@withContext
        val selectedId = serverDao.getSelectedServerSync()?.id
        val nodes = getRankedCandidateServers(excludeIds + setOfNotNull(selectedId))
        val now = System.currentTimeMillis()
        val fresh = nodes.filter { node -> probes.verifiedDelays.value[node.id]?.let {
            it.network == probes.currentNetworkKey() && now - it.checkedAt in 0..ServerProbeRepository.STANDBY_PROOF_FRESH_MS && it.profile == QuickServerSelection.profile(node)
        } == true && (!prefs.needsServiceQualification || performance.fresh(node, prefs.copy(metric = BalancerMetric.LATENCY)) != null) }
            .mapTo(hashSetOf()) { it.id }
        val batchCount = if (fresh.size < 4) 4 else 2
        val verified = standbyRotation.take(nodes, fresh, now, count = batchCount).map { node -> async {
            node.takeIf { measureRealDelaySafely(it) > 0 &&
                (!prefs.needsServiceQualification ||
                    performance.qualify(it, prefs.copy(metric = BalancerMetric.LATENCY), priority = true) != null) }
        } }.awaitAll().filterNotNull()
        if (sampleDownload && prefs.metric == BalancerMetric.DOWNLOAD && !selectionMutex.isLocked &&
            performance.speedBudgetResumesAt() == null) {
            verified.firstOrNull { performance.fresh(it, prefs) == null }?.let { candidate ->
                performance.qualify(candidate, prefs, priority = true)
            }
        }
    }

    /** Commit only if the user has not disabled balancing, changed the node or removed its source. */
    suspend fun activateAutoCandidate(candidate: ServerNode, expectedCurrent: ServerNode?, stillCurrent: () -> Boolean): Boolean =
        database.withTransaction {
            val prefs = settingsManager.getBalancerPreferences()
            if (!stillCurrent() || !settingsManager.areChecksAllowed() || !probes.hasFreshTunnelProof(candidate) ||
                (prefs.goal != BalancerGoal.GENERAL && !qualifiesForGoal(candidate))) return@withTransaction false
            if (!settingsManager.isServerEnabled(candidate) || !settingsManager.isAutoSelectServer() ||
                serverDao.getSelectedServerSync()?.let(QuickServerSelection::profile) != expectedCurrent?.let(QuickServerSelection::profile)) {
                return@withTransaction false
            }
            val disabled = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
            val latest = serverDao.getServerById(candidate.id)
            if (candidate.subId in disabled || latest == null || QuickServerSelection.profile(latest) != QuickServerSelection.profile(candidate)) {
                return@withTransaction false
            }
            serverDao.setActiveServer(candidate.id)
            if (!stillCurrent()) throw CancellationException("Connection changed before automatic selection was committed")
            settingsManager.setSelectedServerName(candidate.name)
            true
        }

    /**
     * Возвращает отранжированный список доступных серверов (лучшие первые),
     * исключая временно недоступные узлы из excludeIds.
     */
    suspend fun getRankedCandidateServers(
        excludeIds: Set<String> = emptySet(),
        prioritizeRecovery: Boolean = true
    ): List<ServerNode> = withContext(Dispatchers.IO) {
        val disabled = subscriptionDao.getAllSubscriptionsSync().filter { !it.enabled }.map { it.id }.toSet()
        val prefs = settingsManager.getBalancerPreferences()
        val network = probes.currentNetworkKey() // Also expires quarantine/history from a different network.
        val configuration = performance.configuration()
        val now = System.currentTimeMillis()
        val measurements = performance.measurements.value
        val disabledIds = settingsManager.disabledServerIds.value
        val allowed = serverDao.getAllServersSync().filter {
            settingsManager.isServerEnabled(it, disabledIds, now) && it.id !in excludeIds && it.subId !in disabled
        }
        val nodes = eligibleBalancerCandidates(allowed, excludeIds, disabled, qualityTracker)
        val fresh = nodes.mapNotNull { node -> measurements[node.id]?.takeIf {
            it.matches(node, network, configuration, prefs.goal, now) &&
                (prefs.metric != BalancerMetric.DOWNLOAD || it.freshDownload(now) != null)
        }?.let { node.id to it } }.toMap()
        val recentFailures = nodes.filter { node -> measurements[node.id]?.let {
            !it.qualified && it.network == network && it.configuration == configuration && it.goal == prefs.goal &&
                it.profile == QuickServerSelection.profile(node) && now - it.checkedAt in 0..120_000L
        } == true }.mapTo(hashSetOf()) { it.id }
        val routingRegion = settingsManager.getRoutingRegion()
        val clientCountry = java.util.Locale.getDefault().country.takeIf { it.isNotBlank() }
        // Snapshot network and configuration once, rather than issuing system queries inside the comparator.
        nodes.sortedWith(compareBy<ServerNode> {
            if (prioritizeRecovery) !qualityTracker.isQuarantined(it.id) else qualityTracker.isQuarantined(it.id)
        }.thenBy { node ->
            if (prefs.goal == BalancerGoal.GENERAL && prefs.metric == BalancerMetric.LATENCY || node.id in fresh) 0
            else if (node.id in recentFailures) 2 else 1
        }.thenBy { node ->
            if (prefs.metric == BalancerMetric.DOWNLOAD) downloadCandidateScore(fresh[node.id]?.freshDownload(now),
                node.ping, calculateEffectivePing(node, node.ping)) else 0L
        }.thenBy { node ->
            when {
                qualityTracker.hasFreshSuccess(node.id) -> {
                    val eff = calculateEffectivePing(node, node.ping)
                    val bucket = eff / 35L
                    val tier = ServerCountryHint.geoTier(node, routingRegion, clientCountry)
                    bucket * 1000L + tier * 100L + (eff % 35L)
                }
                node.ping > 0 -> {
                    val eff = calculateEffectivePing(node, node.ping)
                    val tier = ServerCountryHint.geoTier(node, routingRegion, clientCountry)
                    1000L + (eff / 35L) * 1000L + tier * 100L + (eff % 35L)
                }
                node.ping == 0L -> {
                    val tier = ServerCountryHint.geoTier(node, routingRegion, clientCountry)
                    4000L + tier * 200L
                }
                else -> 10_000L
            }
        })
    }

    /** Prefer fresh, end-to-end verified reserves; exclusions are never relaxed on exhaustion. */
    suspend fun autoSelectFastestServer(
        excludeIds: Set<String> = emptySet(),
        urgent: Boolean = false,
        commitSelection: Boolean = true,
        reuseRecentSelection: Boolean = false,
        allowSpeedProbe: Boolean = true
    ): ServerNode? = settingsManager.withCheckNetwork {
        val prefs = settingsManager.getBalancerPreferences()
        val measureDownload = allowSpeedProbe && prefs.metric == BalancerMetric.DOWNLOAD
        val request = AutoSelectionRequest(excludeIds.toSet(), urgent, commitSelection, settingsManager.selectionRevision(), listOf(
            settingsManager.getXhttpMode(), settingsManager.isTlsFragmentEnabled(),
            settingsManager.getTlsFragmentLength(), settingsManager.getTlsFragmentInterval(),
            settingsManager.getTlsFragmentPackets(), settingsManager.isTlsMixedSniEnabled(), settingsManager.isMuxEnabled(), settingsManager.getMuxConcurrency(),
            prefs.metric, prefs.goal
        ), measureDownload)
        selectionHandoff.run(request, reuseRecentSelection, isValid = { previous ->
            val eligible = getRankedCandidateServers(excludeIds).firstOrNull { it.id == previous.id }
            request.revision == settingsManager.selectionRevision() && eligible != null && qualityTracker.hasFreshSuccess(previous.id, 10_000L) && qualifiesForGoal(eligible) &&
                eligible.copy(ping = previous.ping, isSelected = previous.isSelected) == previous &&
                (!commitSelection || serverDao.getSelectedServerSync()?.id == previous.id)
        }) {
            // Waiting for another caller no longer spends this search's own probe budget.
            val started = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "autoSelect: начат поиск рабочего сервера")
            val extended = measureDownload || prefs.goal != BalancerGoal.GENERAL
            val result = withTimeoutOrNull(balancerSelectionBudgetMillis(urgent, extended)) {
                selectFastestServerInternal(excludeIds, urgent, commitSelection, request.revision, allowSpeedProbe)
            }?.takeIf { request.revision == settingsManager.selectionRevision() && settingsManager.isServerEnabled(it) &&
                // For GENERAL goal the Real Delay itself is the proof; skip qualifiesForGoal to avoid a
                // race between recordTunnelSuccess (async StateFlow.update) and this post-filter.
                (prefs.goal == BalancerGoal.GENERAL || qualifiesForGoal(it)) }
            Log.i(TAG, "autoSelect: поиск завершён за ${android.os.SystemClock.elapsedRealtime() - started} мс: " +
                if (result == null) "рабочий сервер не найден или истёк таймаут" else "сервер проверен")
            result
        }
    }

    private suspend fun selectFastestServerInternal(
        excludeIds: Set<String>, urgent: Boolean, commitSelection: Boolean, revision: Long, allowSpeedProbe: Boolean
    ): ServerNode? = withContext(Dispatchers.IO) {
        val ranked = getRankedCandidateServers(excludeIds, prioritizeRecovery = !urgent)
        if (ranked.isEmpty()) return@withContext null
        val recovery = if (urgent) {
            emptySet()
        } else {
            ranked.filter { qualityTracker.isQuarantined(it.id) }.mapTo(hashSetOf()) { it.id }
        }
        val sponsorUrl = SubscriptionHwidPolicy.canonicalUrl(SplateConstants.PRESET_SUB_SPONSOR_URL)
        val sponsorIds = subscriptionDao.getAllSubscriptionsSync().filter {
            it.enabled && (it.id == SplateConstants.PRESET_SUB_SPONSOR_ID || (sponsorUrl != null && SubscriptionHwidPolicy.canonicalUrl(it.url) == sponsorUrl))
        }.mapTo(hashSetOf()) { it.id }
        val (sponsor, others) = ranked.partition { it.subId in sponsorIds }
        val currentSelected = serverDao.getSelectedServerSync()

        val prefs = settingsManager.getBalancerPreferences()
        val configuration = performance.configuration()
        val network = probes.currentNetworkKey() ?: return@withContext null
        suspend fun commit(candidate: ServerNode): Boolean = database.withTransaction {
            val selected = serverDao.getSelectedServerSync()
            val latest = serverDao.getServerById(candidate.id)
            if (revision != settingsManager.selectionRevision() || prefs != settingsManager.getBalancerPreferences() ||
                configuration != performance.configuration() || network != probes.currentNetworkKey() || !settingsManager.areChecksAllowed() ||
                selected?.id != currentSelected?.id || selected?.let(QuickServerSelection::profile) != currentSelected?.let(QuickServerSelection::profile) ||
                latest == null || QuickServerSelection.profile(latest) != QuickServerSelection.profile(candidate) ||
                !settingsManager.isServerEnabled(latest) || !probes.hasFreshTunnelProof(latest) ||
                (prefs.goal != BalancerGoal.GENERAL && !qualifiesForGoal(latest)) ||
                subscriptionDao.getAllSubscriptionsSync().any { it.id == latest.subId && !it.enabled }) return@withTransaction false
            if (commitSelection) {
                serverDao.setActiveServer(latest.id)
                settingsManager.setSelectedServerName(latest.name)
            }
            true
        }
        if (revision != settingsManager.selectionRevision()) return@withContext null

        // 1. Каскадный выбор (Waterfall): первоочередная проверка узлов подписки WTPN
        if (sponsor.isNotEmpty()) {
            val sponsorVerified = sponsor.filter { probes.hasFreshTunnelProof(it) }.mapTo(hashSetOf()) { it.id }
            val sponsorCandidates = discoveryRotation.order(
                protocolDiverseCandidates(sponsor.sortedByDescending { it.isHysteria2 }), network, sponsorVerified, recovery)
            val extended = allowSpeedProbe && prefs.metric == BalancerMetric.DOWNLOAD || prefs.goal != BalancerGoal.GENERAL
            val budget = balancerSelectionBudgetMillis(urgent, extended)
            // Reserve time for other sources if the priority pool stalls during native startup.
            val verifiedSponsor = withTimeoutOrNull(if (others.isEmpty()) budget else if (extended) budget / 2 else minOf(15000L, budget / 2)) {
                val sponsorShortlist = prefilterCandidates(sponsorCandidates, targetCount = AUTO_SELECT_SHORTLIST_SIZE, urgent = urgent)
                Log.i(TAG, "autoSelect: [Waterfall] Проверка приоритетных узлов WTPN (${sponsorShortlist.size} узлов)")
                verifyShortlistByRealDelay(
                    shortlist = discoveryRotation.order(protocolDiverseCandidates(completeDiscoveryQueue(sponsorShortlist, sponsorCandidates)), network, sponsorVerified, recovery),
                    currentSelectedId = currentSelected?.id,
                    urgent = urgent,
                    headStartWorkers = 0,
                    allowSpeedProbe = allowSpeedProbe,
                    prioritizeHysteria = true
                )
            }
            if (verifiedSponsor != null) {
                val (chosen, delayMs) = verifiedSponsor
                if (commit(chosen)) {
                    Log.i(TAG, "autoSelect: [Waterfall] Успешно выбран узел WTPN '${chosen.name}', ${delayMs}мс (протокол: ${chosen.protocol})")
                    return@withContext chosen.copy(ping = delayMs)
                }
            }
            Log.w(TAG, "autoSelect: [Waterfall] Ни один узел WTPN не прошёл проверку, переходим к резервным подпискам (${others.size} узлов)")
        }

        // 2. Вторичный этап: если WTPN пуст или ни один узел WTPN не ответил — проверяем остальные подписки
        if (others.isEmpty()) return@withContext null
        val otherVerified = others.filter { probes.hasFreshTunnelProof(it) }.mapTo(hashSetOf()) { it.id }
        val otherCandidates = discoveryRotation.order(protocolDiverseCandidates(others), network, otherVerified, recovery)
        val shortlist = prefilterCandidates(otherCandidates, targetCount = AUTO_SELECT_SHORTLIST_SIZE * 2, urgent = urgent)
        Log.i(TAG, "autoSelect: [Waterfall] Проверка резервных узлов (${shortlist.size} узлов)")
        val verifiedOther = verifyShortlistByRealDelay(
            shortlist = discoveryRotation.order(protocolDiverseCandidates(completeDiscoveryQueue(shortlist, otherCandidates)), network, otherVerified, recovery),
            currentSelectedId = currentSelected?.id,
            urgent = urgent,
            headStartWorkers = 0,
            allowSpeedProbe = allowSpeedProbe,
            prioritizeHysteria = false // Для сторонних подписок Hysteria НЕ имеет искусственного приоритета
        ) ?: return@withContext null

        val (chosen, delayMs) = verifiedOther
        if (!commit(chosen)) return@withContext null
        Log.i(TAG, "autoSelect: [Waterfall] Выбран резервный узел '${chosen.name}', ${delayMs}мс (протокол: ${chosen.protocol})")
        chosen.copy(ping = delayMs)
    }

    /**
     * Fast parallel TCP prefilter: rapidly checks raw socket connectivity across candidates with concurrency 32.
     * Prioritizes responsive endpoints within 350–500 ms; the full queue still receives tunnel verification.
     */
    private suspend fun prefilterCandidates(
        candidates: List<ServerNode>,
        targetCount: Int = AUTO_SELECT_SHORTLIST_SIZE * 2,
        urgent: Boolean = false
    ): List<ServerNode> = withContext(Dispatchers.IO) {
        prefilterBalancerCandidates(candidates, targetCount, if (urgent) 350L else 500L,
            bypass = {
                it.usesUdpTransport || probes.hasFreshTunnelProof(it, if (urgent) 60_000L else ServerProbeRepository.STANDBY_PROOF_FRESH_MS) ||
                    qualityTracker.hasFreshSuccess(it.id, if (urgent) 30_000L else 60_000L)
            },
            measure = { TcpProbe.measure(it.address, it.port, if (urgent) 1000 else AUTO_SELECT_TCP_TIMEOUT_MS) })
    }

    /**
     * Этап 2: параллельная Real Delay верификация шорт-листа (HTTP 204 через изолированный Xray).
     * Возвращает узел с лучшим скором по реальной задержке либо null, если ни один не пропустил трафик.
     */
    private suspend fun verifyShortlistByRealDelay(
        shortlist: List<ServerNode>,
        currentSelectedId: String? = null,
        urgent: Boolean = false,
        tcpPrefilter: Boolean = false,
        headStartWorkers: Int = 0,
        allowSpeedProbe: Boolean = true,
        prioritizeHysteria: Boolean = false,
        onTcpFailure: (ServerNode) -> Unit = {}
    ): Pair<ServerNode, Long>? {
        val prefs = settingsManager.getBalancerPreferences()
        // Connection startup uses cached Mbps for ranking, but never waits for a new download.
        val measureDownload = allowSpeedProbe && prefs.metric == BalancerMetric.DOWNLOAD
        val qualificationPrefs = if (measureDownload) prefs else prefs.copy(metric = BalancerMetric.LATENCY)
        return selectResponsiveCandidate(shortlist,
            alwaysCompare = measureDownload,
            settleMs = if (measureDownload) { if (urgent) 12_000L else 25_000L } else (if (urgent) 250L else 750L),
            parallelism = AUTO_SELECT_SHORTLIST_SIZE,
            headStartWorkers = headStartWorkers,
            score = { server, delay ->
                val effective = calculateEffectivePing(server, delay, server.id == currentSelectedId)
                val baseScore = if (prefs.metric == BalancerMetric.DOWNLOAD)
                    downloadCandidateScore(performance.fresh(server, prefs)?.downloadMbps, delay, effective)
                else effective
                // Hysteria 2 priority: применяется ИСКЛЮЧИТЕЛЬНО для подписки WTPN (когда prioritizeHysteria = true)
                if (prioritizeHysteria && server.isHysteria2) (baseScore * 0.35).toLong().coerceAtLeast(1L) else baseScore
            }) { server ->
            if (!settingsManager.isServerEnabled(server) || !qualityTracker.canProbe(server.id)) -1L
            else {
                val useTcp = tcpPrefilter && !server.usesUdpTransport &&
                    server.network.lowercase() !in setOf("xhttp", "splithttp", "ws", "grpc") && !probes.hasFreshTunnelProof(server)
                val tcp = if (useTcp) probes.pingActivity.track(server.id) {
                    TcpProbe.measure(server.address, server.port, AUTO_SELECT_TCP_TIMEOUT_MS)
                } else 1L
                if (tcp <= 0) { onTcpFailure(server); -1L }
                else {
                    val delay = measureRealDelaySafely(server, urgent)
                    val needsQualification = prefs.goal != BalancerGoal.GENERAL || measureDownload
                    if (delay <= 0 || !settingsManager.isServerEnabled(server)) -1L
                    else if (!needsQualification) delay
                    else {
                        val qualified = performance.qualify(server, qualificationPrefs, priority = true)
                        // An exhausted speed budget must still allow a working, goal-verified tunnel.
                        val fallback = measureDownload &&
                            performance.fresh(server, prefs.copy(metric = BalancerMetric.LATENCY)) != null
                        if (qualified != null || fallback) delay else -1L
                    }
                }
            }
        }
    }
    /**
     * Фоновая оптимизация балансировщика во время активной сессии VPN:
     * Ищет узел, который СУЩЕСТВЕННО лучше текущего.
     * Использует анти-флаппинг гистерезис: новый узел должен давать выигрыш >= [minImprovementRatio] (30%)
     * И уменьшать задержку минимум на [minMsSaved] (40мс).
     */
    suspend fun findBetterServerIfAvailable(
        currentServer: ServerNode,
        currentPing: Long,
        excludeIds: Set<String> = emptySet(),
        minImprovementRatio: Float = 0.30f,
        minMsSaved: Long = 40L,
        verifyGoalOnly: Boolean = false
    ): ServerNode? = withContext(Dispatchers.IO) {
        if (!settingsManager.areChecksAllowed()) return@withContext null
        val prefs = settingsManager.getBalancerPreferences()
        val currentQualification = if (prefs.goal != BalancerGoal.GENERAL || prefs.metric == BalancerMetric.DOWNLOAD)
            performance.qualify(currentServer, if (verifyGoalOnly) prefs.copy(metric = BalancerMetric.LATENCY) else prefs,
                SpeedProbePurpose.BASELINE, priority = true) else null
        val goalMissing = prefs.goal != BalancerGoal.GENERAL &&
            performance.fresh(currentServer, prefs.copy(metric = BalancerMetric.LATENCY)) == null
        if (goalMissing) return@withContext autoSelectFastestServer(
            excludeIds = excludeIds + currentServer.id, urgent = true,
            commitSelection = false, allowSpeedProbe = false)
        if (verifyGoalOnly) return@withContext null
        if (!goalMissing && prefs.metric == BalancerMetric.LATENCY && currentPing <= 65L) {
            // Текущий узел уже отличный (< 65мс), переключать нет смысла
            return@withContext null
        }

        val sponsorUrl = SubscriptionHwidPolicy.canonicalUrl(SplateConstants.PRESET_SUB_SPONSOR_URL)
        val sponsorIds = subscriptionDao.getAllSubscriptionsSync().filter {
            it.enabled && (it.id == SplateConstants.PRESET_SUB_SPONSOR_ID || (sponsorUrl != null && SubscriptionHwidPolicy.canonicalUrl(it.url) == sponsorUrl))
        }.mapTo(hashSetOf()) { it.id }
        val isCurrentSponsor = currentServer.subId in sponsorIds

        val rankedCandidates = getRankedCandidateServers(excludeIds + currentServer.id).filter {
            !qualityTracker.isQuarantined(it.id) &&
            // При активном узле WTPN не переключаемся в фоне на сторонние подписки
            (!isCurrentSponsor || it.subId in sponsorIds)
        }
        if (rankedCandidates.isEmpty()) return@withContext null

        val exploring = if (prefs.metric == BalancerMetric.DOWNLOAD) discoveryRotation.order(
            protocolDiverseCandidates(rankedCandidates.filter { performance.fresh(it, prefs)?.freshDownload() == null }),
            probes.currentNetworkKey(), emptySet()).take(3) else emptyList()
        val candidateBatchSize = (5 + rankedCandidates.size / 20).coerceIn(5, 12)
        val topCandidates = (exploring + rankedCandidates).distinctBy { it.id }
            .let { if (isCurrentSponsor) it.sortedByDescending { node -> node.isHysteria2 } else it }
            .take(candidateBatchSize)

        val verified = verifyShortlistByRealDelay(
            shortlist = topCandidates,
            currentSelectedId = currentServer.id,
            prioritizeHysteria = isCurrentSponsor
        ) ?: return@withContext null
        val (bestCandidate, bestRealDelay) = verified

        if (goalMissing) return@withContext bestCandidate.copy(ping = bestRealDelay)
        if (prefs.metric == BalancerMetric.DOWNLOAD) {
            val baseline = currentQualification?.downloadMbps ?: return@withContext null
            val measured = performance.fresh(bestCandidate, prefs)?.downloadMbps ?: return@withContext null
            return@withContext bestCandidate.copy(ping = bestRealDelay).takeIf {
                measured >= baseline * (1 + minImprovementRatio) && measured - baseline >= 1.0
            }
        }

        val currentScore = calculateEffectivePing(currentServer, currentPing, isCurrentlyActive = true)
        val candidateScore = calculateEffectivePing(bestCandidate, bestRealDelay, isCurrentlyActive = false)

        val msSaved = currentScore - candidateScore
        val improvement = msSaved.toFloat() / currentScore.coerceAtLeast(1L)

        // Hysteria upgrade bonus: действует ИСКЛЮЧИТЕЛЬНО для подписки WTPN
        val isHysteriaUpgrade = isCurrentSponsor && bestCandidate.isHysteria2 && !currentServer.isHysteria2
        if (isHysteriaUpgrade || (msSaved >= minMsSaved && improvement >= minImprovementRatio)) {
            Log.i(
                TAG,
                "findBetterServer: найден узел '${bestCandidate.name}' (${bestRealDelay}ms, score $candidateScore, hy2Upgrade=$isHysteriaUpgrade) взамен '${currentServer.name}' (${currentPing}ms, score $currentScore). Выигрыш: ${msSaved}ms / ${(improvement * 100).toInt()}%"
            )
            return@withContext bestCandidate.copy(ping = bestRealDelay)
        }

        null
    }

    private suspend fun measureRealDelaySafely(server: ServerNode, urgent: Boolean = false): Long {
        val checkedNetwork = probes.currentNetworkKey()
        return try {
        discoveryRotation.started(server.id)
        val delay = probes.testRealDelayForBalancer(server, timeoutMs = realDelayTimeoutMs(urgent))
        delay
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: WifiRequiredException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "autoSelect: Real Delay '${server.name}' завершился ошибкой: ${e.message}")
        probes.savePing(server.id, -1L)
        probes.recordServerFailure(server, checkedNetwork)
        -1L
        }
    }

    fun qualifiesForGoal(server: ServerNode): Boolean {
        val prefs = settingsManager.getBalancerPreferences()
        if (prefs.goal == BalancerGoal.GENERAL) {
            return probes.hasFreshTunnelProof(server) || qualityTracker.hasFreshSuccess(server.id) || server.ping > 0
        }
        return probes.hasFreshTunnelProof(server) &&
            performance.fresh(server, prefs.copy(metric = BalancerMetric.LATENCY)) != null
    }

}
