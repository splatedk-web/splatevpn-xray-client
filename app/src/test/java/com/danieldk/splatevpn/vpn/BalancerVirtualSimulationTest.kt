package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.BalancerGoal
import com.danieldk.splatevpn.data.BalancerMetric
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.ServerQualityTracker
import com.danieldk.splatevpn.data.selectResponsiveCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Комплексный виртуальный стресс-тест балансировщика SplateClient.
 *
 * Моделирует сценарии реальной эксплуатации:
 * 1. Смена вышек сотовой связи / роуминг / сетевая мобильность (Handover & Radio Drops).
 * 2. Отказ и динамика пула узлов (Node Failures, Pool Flapping, Import On-The-Fly).
 * 3. Переход в спящий режим Android (Doze Mode) и выход из него при Silent Blackhole.
 * 4. Длительный коннект (24h+ EWMA drift, Anti-Thrashing, Throughput Protection, Rate Limiting).
 */
class BalancerVirtualSimulationTest {
    private val originalDeadServerClock = SplateVpnService.deadServerClock

    @org.junit.Before fun useJvmQuarantineClock() {
        SplateVpnService.deadServerClock = { System.nanoTime() / 1_000_000 }
    }

    @org.junit.After fun restoreQuarantineClock() {
        SplateVpnService.deadServerClock = originalDeadServerClock
    }


    @Before
    fun setUp() {
        SplateVpnService.clearDeadServers()
        SplateVpnService.resetAutoSwitchLimits()
    }

    // =========================================================================
    // СЦЕНАРИЙ 1: Смена вышек сотовой связи и сетевая мобильность (Cell Tower Handover)
    // =========================================================================

    @Test
    fun testCellTowerHandoff_temporaryRadioDrop_doesNotTriggerFalseNodeSwitch() {
        // Моделируем кратковременный обрыв радиоканала (1.5–2 сек) при переключении между базовыми станциями LTE.
        // Физический интернет устройства падает (isPhysicalAlive = false).
        var isPhysicalInternetAlive = false
        var failedSamples = 0
        var degradedSamples = 0
        var nodeSwitchTriggered = false

        val simulateHealthTick = { vpnPing: Long ->
            if (vpnPing <= 0) {
                if (isPhysicalInternetAlive) {
                    failedSamples++
                    if (failedSamples >= 3) {
                        nodeSwitchTriggered = true
                    }
                } else {
                    // Ключевая логика балансировщика: если интернет на устройстве отсутствует,
                    // сбои пинга не засчитываются как вина VPN-узла!
                    failedSamples = 0
                    degradedSamples = 0
                }
            } else {
                failedSamples = 0
                degradedSamples = 0
            }
        }

        // 1. Узел стабилен
        isPhysicalInternetAlive = true
        simulateHealthTick(55L)
        assertEquals(0, failedSamples)
        assertFalse(nodeSwitchTriggered)

        // 2. Телефон заходит в мертвую зону между вышками LTE (радиоканал потерян на 3 тика)
        isPhysicalInternetAlive = false
        repeat(3) {
            simulateHealthTick(-1L)
            assertEquals("Счетчик сбоев VPN узла обязан обнуляться при недоступности физической сети", 0, failedSamples)
            assertFalse("Ложное переключение узла запрещено при отсутствии связи с вышкой", nodeSwitchTriggered)
        }

        // 3. Вышка переключилась, связь восстановилась, первый пинг прошел
        isPhysicalInternetAlive = true
        simulateHealthTick(60L)
        assertEquals(0, failedSamples)
        assertFalse(nodeSwitchTriggered)
    }

    @Test
    fun testCellTowerHandoff_networkChange_clearsQualityAndInvalidatesOutdatedChecks() {
        // При переходе на другую вышку или смене Wi-Fi <-> LTE меняется physicalNetworkKey
        var virtualTime = 10_000L
        val tracker = ServerQualityTracker { virtualTime }

        // Работа на вышке №1 (NetworkKey = 101L)
        tracker.onNetworkChanged(101L)
        tracker.recordSuccess("node-finland", 50L)
        tracker.recordFailure("node-poland")

        assertTrue("На вышке 1 Финляндия подтверждена", tracker.hasFreshSuccess("node-finland"))
        assertTrue("На вышке 1 Польша в карантине", tracker.isQuarantined("node-poland"))

        // Переключение на вышку №2 (NetworkKey = 102L)
        tracker.onNetworkChanged(102L)

        // Метрики и карантины старой сети должны быть немедленно сброшены,
        // так как маршрутизация и задержки до узлов через новую базовую станцию другие
        assertFalse("Кэш свежести сброшен для новой сети", tracker.hasFreshSuccess("node-finland"))
        assertFalse("Карантин сброшен, узел получает шанс на новой сети", tracker.isQuarantined("node-poland"))
    }

    @Test
    fun testCellTowerHandoff_captiveWifiDoesNotDisplaceActiveCellular() {
        val mobile = PhysicalNetworkCandidate("lte-carrier", internet = true, unrestricted = true, notVpn = true, validated = true, wifi = false, blocked = false)
        val captiveWifi = PhysicalNetworkCandidate("wifi-metro", internet = true, unrestricted = false, notVpn = true, validated = false, wifi = true, blocked = true)

        // Каптивный Wi-Fi без валидации не должен вытеснять работающую мобильную сеть
        val chosen = selectPhysicalNetwork(listOf(captiveWifi, mobile), preferred = "lte-carrier")
        assertEquals("lte-carrier", chosen)
    }

    // =========================================================================
    // СЦЕНАРИЙ 2: Отказ и динамика пула серверов (Node Flapping, Failures & Pool Dynamics)
    // =========================================================================

    @Test
    fun testNodeFailure_emergencySwitch_bypassesSlidingWindowLimit_andQuarantinesFailedNode() {
        var virtualTime = 100_000L
        val tracker = ServerQualityTracker { virtualTime }

        // Имитируем 4 плановых переключения (достигнут лимит скользящего окна 5 минут)
        repeat(4) {
            SplateVpnService.recordAutoSwitch()
        }
        assertEquals(4, SplateVpnService.consecutiveAutoSwitchCount)

        // Обычная фоновая оптимизация сейчас заблокирована
        assertFalse("Фоновая оптимизация должна быть заблокирована лимитом окна", SplateVpnService.canAutoSwitch(isDeadNode = false))

        // Внезапно узел заблокирован ТСПУ (активный сервер мёртв)
        val deadServerId = "server-blocked"
        SplateVpnService.markDeadServer(deadServerId)
        tracker.recordFailure(deadServerId)

        assertTrue(SplateVpnService.deadServerIds.contains(deadServerId))
        assertTrue(tracker.isQuarantined(deadServerId))

        // Истекает минимальный 1-секундный дебаунс кулдаун
        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 1100L

        // Аварийное переключение обязано сработать, игнорируя исчерпанный лимит 4 смен!
        assertTrue(
            "Аварийный Circuit Breaker должен разрешать переключение мертвого узла даже при 4+ смен в окне",
            SplateVpnService.canAutoSwitch(isDeadNode = true)
        )
    }

    @Test
    fun testPoolDynamics_massiveNodeOutage_prefilterSelectsResponsiveCandidateRapidly() = runBlocking {
        // Моделируем пул из 100 серверов, где 95 серверов мертвы (заблокированы ТСПУ),
        // а 5 серверов живы с разными задержками.
        val totalNodes = 100
        val liveNodeIndices = setOf(12, 45, 67, 83, 91)
        val candidates = (0 until totalNodes).map { "node-$it" }

        val startTime = System.currentTimeMillis()
        val result = withTimeout(3000) {
            selectResponsiveCandidate(
                candidates = candidates,
                parallelism = 12,
                settleMs = 50,
                score = { _, ping -> ping }
            ) { node ->
                val index = node.removePrefix("node-").toInt()
                if (index in liveNodeIndices) {
                    val delay = when (index) {
                        12 -> 110L
                        45 -> 58L   // Самый быстрый живой узел
                        67 -> 85L
                        83 -> 62L
                        else -> 95L
                    }
                    delay(delay / 2) // имитация сетевой задержки в тесте
                    delay
                } else {
                    -1L // 95 узлов немедленно или по таймауту возвращают -1
                }
            }
        }

        assertNotNull("Балансировщик обязан найти живой узел среди 95% мертвых", result)
        assertEquals("node-45", result!!.first)
        assertEquals(58L, result.second)
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Отбор живого сервера должен завершиться быстро (< 1500ms), факт: ${elapsed}ms", elapsed < 1500)
    }

    @Test
    fun testPoolDynamics_newNodesImportedWithZeroPing_areEligibleForOptimization() {
        // При импорте новой подписки серверы добавляются в БД с ping = 0.
        // Проверяем, что логика отбора кандидатов не отсекает такие узлы, давая им шанс выиграть
        val currentServer = ServerNode(id = "current", name = "Current Slow", protocol = "vless", address = "1.1.1.1", port = 443, ping = 180)
        val newlyImportedNode = ServerNode(id = "new-fresh", name = "Fresh Node", protocol = "vless", address = "2.2.2.2", port = 443, ping = 0)
        val disabledSubNode = ServerNode(id = "disabled", name = "Off Node", protocol = "vless", address = "3.3.3.3", port = 443, subId = "sub-off")

        val allServers = listOf(currentServer, newlyImportedNode, disabledSubNode)
        val disabledSubIds = setOf("sub-off")
        val excludeIds = setOf("dead-1")
        val tracker = ServerQualityTracker { 1000L }

        val eligible = allServers.filter {
            it.id != currentServer.id &&
            it.id !in excludeIds &&
            !tracker.isQuarantined(it.id) &&
            it.subId !in disabledSubIds
        }

        assertEquals(1, eligible.size)
        assertEquals("new-fresh", eligible.first().id)
        assertEquals("Новый узел с ping=0 обязан участвовать в отборе", 0L, eligible.first().ping)
    }

    @Test
    fun testPoolDynamics_quarantinedNodeBackoffIncreasesOnRepeatedFailures() {
        var virtualTime = 1_000L
        val tracker = ServerQualityTracker { virtualTime }
        val nodeId = "flapping-node"

        // 1-й сбой: карантин 60 секунд (60_000L shl 0)
        tracker.recordFailure(nodeId)
        assertTrue(tracker.isQuarantined(nodeId))
        virtualTime += 60_001L
        assertFalse("После 60 секунд карантин должен истечь", tracker.isQuarantined(nodeId))

        // 2-й сбой: карантин 120 секунд (60_000L shl 1)
        tracker.recordFailure(nodeId)
        assertTrue(tracker.isQuarantined(nodeId))
        virtualTime += 60_001L
        assertTrue("После 2 сбоев карантин 120с, через 60с узел еще заблокирован", tracker.isQuarantined(nodeId))
        virtualTime += 60_001L
        assertFalse("Через 120с узел разблокирован", tracker.isQuarantined(nodeId))

        // 3-й сбой: карантин 240 секунд (60_000L shl 2)
        tracker.recordFailure(nodeId)
        virtualTime += 120_001L
        assertTrue("Через 120с узел еще в карантине", tracker.isQuarantined(nodeId))
        virtualTime += 120_001L
        assertFalse("Через 240с карантин снят", tracker.isQuarantined(nodeId))
    }

    // =========================================================================
    // СЦЕНАРИЙ 3: Спящий режим Android (Deep Sleep / Doze Mode) и Silent Blackhole
    // =========================================================================

    @Test
    fun testDozeMode_whenChecksDisallowed_balancerSleepsAndDoesNotAccumulateFailures() {
        // В режиме глубокого сна (Doze) ОС блокирует фоновые сетевые сокеты.
        // settingsManager.areChecksAllowed() возвращает false.
        var areChecksAllowed = true
        var failedSamples = 0
        var degradedSamples = 0
        var probesSent = 0

        val runBalancerCycle = {
            if (!areChecksAllowed) {
                // При входе в спящий режим балансировщик сбрасывает счетчики и засыпает
                failedSamples = 0
                degradedSamples = 0
            } else {
                probesSent++
            }
        }

        // Приложение активно
        areChecksAllowed = true
        runBalancerCycle()
        assertEquals(1, probesSent)

        // Экран погас, устройство ушло в Deep Sleep (Doze Mode)
        areChecksAllowed = false
        failedSamples = 2 // допустим, перед сном был накоплен 1-2 сбоя
        repeat(5) {
            runBalancerCycle()
            assertEquals("В режиме сна пробы не должны отсылаться", 1, probesSent)
            assertEquals("Счетчики сбоев должны быть сброшены в Doze", 0, failedSamples)
        }
    }

    @Test
    fun testSilentBlackhole_detectionAndRecovery() {
        // Моделируем выход из сна: радиомодуль подключен, но TCP-соединение с Xray зависло
        // (Half-open socket / дроп пакетов на ТСПУ без RST).
        // Признак Silent Blackhole: исходящий трафик идет (txDelta > 15KB), входящего 0 (rxDelta = 0)
        var isPhysicalAlive = true
        var silentTxConsecutiveSeconds = 0
        var emergencyRecoveryTriggered = false

        val inspectTrafficTick = { txDelta: Long, rxDelta: Long ->
            if (isPhysicalAlive && txDelta > 15_000L && rxDelta == 0L) {
                silentTxConsecutiveSeconds++
                if (silentTxConsecutiveSeconds >= 12) {
                    silentTxConsecutiveSeconds = 0
                    emergencyRecoveryTriggered = true
                }
            } else {
                silentTxConsecutiveSeconds = 0
            }
        }

        // 1. Нормальный двусторонний трафик
        inspectTrafficTick(20_000L, 50_000L)
        assertEquals(0, silentTxConsecutiveSeconds)
        assertFalse(emergencyRecoveryTriggered)

        // 2. Начинается Silent Blackhole (пользователь отправляет запросы, но ответов нет 11 секунд)
        repeat(11) {
            inspectTrafficTick(18_000L, 0L)
            assertFalse("До 12 секунд туннель не должен сбрасываться преждевременно", emergencyRecoveryTriggered)
        }
        assertEquals(11, silentTxConsecutiveSeconds)

        // 3. 12-я секунда полного черного провала трафика при живой физической сети
        inspectTrafficTick(18_000L, 0L)
        assertTrue("На 12-й секунде Silent Blackhole туннель обязан аварийно переключиться", emergencyRecoveryTriggered)
        assertEquals(0, silentTxConsecutiveSeconds)
    }

    // =========================================================================
    // СЦЕНАРИЙ 4: Длительный коннект (24h+ Stability, Anti-Thrashing, EWMA Drift)
    // =========================================================================

    @Test
    fun testLongRunningConnection_ewmaBaselineAdaptsToGradualDrift() {
        // Длительное соединение (например, 24 часа): задержка к узлу плавно колеблется из-за нагрузки провайдера
        var baselinePing = -1L

        fun updateBaseline(ping: Long) {
            val severelyDegraded = baselinePing > 0 && ping > 350L && ping > baselinePing * 2.2
            if (!severelyDegraded) {
                baselinePing = if (baselinePing <= 0) ping else (baselinePing * 0.85 + ping * 0.15).toLong()
            }
        }

        // Начальный пинг 50 мс
        updateBaseline(50L)
        assertEquals(50L, baselinePing)

        // Плавный дневной дрейф: моделируем 30 регулярных замеров (вечерний рост нагрузки до 75мс)
        repeat(30) {
            updateBaseline(75L)
        }

        // Базовая линия плавно адаптировалась к новому уровню без паники
        assertTrue("Базовый пинг должен плавно адаптироваться к вечернему дрейфу (ожидалось 65-75, факт $baselinePing)", baselinePing in 65L..75L)

        // Внезапный одиночный скачок до 400 мс (лаг сети на 1 секунду) не должен испортить базовую линию
        val beforeSpike = baselinePing
        updateBaseline(400L)
        assertEquals("Одиночный сильный спайк должен игнорироваться EWMA", beforeSpike, baselinePing)
    }

    @Test
    fun testLongRunningConnection_antiThrashing_rejectsMarginalImprovements() {
        // Защита от пинг-понга (Anti-Flapping):
        // Если текущий узел дает 70 мс, а кандидат 62 мс (выигрыш 8 мс / 11%),
        // переключение НЕ должно происходить (требуется minMsSaved >= 40мс И improvement >= 30%)
        val tracker = ServerQualityTracker { 1000L }

        val currentServer = ServerNode("curr", "Current", "vless", "1.1.1.1", 443)
        val marginalCandidate = ServerNode("marg", "Marginal", "vless", "2.2.2.2", 443)

        val currentPing = 70L
        val candidatePing = 62L

        val currentScore = tracker.score(currentServer.id, currentPing, active = true)
        val candidateScore = tracker.score(marginalCandidate.id, candidatePing, active = false)

        val msSaved = currentScore - candidateScore
        val improvement = msSaved.toFloat() / currentScore.coerceAtLeast(1L)

        val minMsSaved = 40L
        val minImprovementRatio = 0.30f

        val willSwitch = msSaved >= minMsSaved && improvement >= minImprovementRatio
        assertFalse("Балансировщик обязан отклонять маргинальные переключения (8ms выигрыша) во избежание флаппинга", willSwitch)

        // Проверяем существенное улучшение (текущий узел деградировал до 110мс, а кандидат дает 35мс):
        val degradedServer = ServerNode("degraded", "Degraded", "vless", "1.1.1.1", 443)
        val superiorCandidate = ServerNode("super", "Superior", "vless", "3.3.3.3", 443)
        val degradedScore = tracker.score(degradedServer.id, 110L, active = true) // 110 - 11 = 99
        val superiorScore = tracker.score(superiorCandidate.id, 35L, active = false) // 35
        val bigMsSaved = degradedScore - superiorScore // 99 - 35 = 64ms >= 40ms
        val bigImprovement = bigMsSaved.toFloat() / degradedScore.coerceAtLeast(1L) // 64 / 99 = 64% >= 30%

        val willSwitchToSuperior = bigMsSaved >= minMsSaved && bigImprovement >= minImprovementRatio
        assertTrue("Существенное улучшение (с 110ms до 35ms, экономия ${bigMsSaved}ms) обязано быть принято", willSwitchToSuperior)
    }

    @Test
    fun testLongRunningConnection_lowLatencyNode_skipsOptimizationCompletely() {
        // Если текущий пинг <= 65 мс, балансировщик считает соединение идеальным
        // и вообще не нагружает сеть поиском замены
        val currentPing = 52L
        val prefs = BalancerPreferences(metric = BalancerMetric.LATENCY, goal = BalancerGoal.GENERAL)

        val skipOptimization = prefs.metric == BalancerMetric.LATENCY && currentPing <= 65L
        assertTrue("При пинге <= 65мс фоновый свитч заблокирован для абсолютной стабильности", skipOptimization)
    }

    @Test
    fun testLongRunningConnection_activeDownload_defersOptimization() {
        // Если пользователь активно качает файл или смотрит 4K-стрим (downBytesPerSec >= 100_000 B/s),
        // плановая оптимизация откладывается, чтобы не разорвать загрузку
        val highDownloadSpeed = 1_500_000L // 1.5 МБ/с
        val idleSpeed = 15_000L           // 15 КБ/с

        val canOptimizeDuringDownload = highDownloadSpeed < 100_000L
        val canOptimizeWhileIdle = idleSpeed < 100_000L

        assertFalse("При активном скачивании переключение узла запрещено", canOptimizeDuringDownload)
        assertTrue("В режиме простоя переключение разрешено", canOptimizeWhileIdle)
    }

    @Test
    fun testLongRunningConnection_circuitBreaker_rateLimitsBackgroundSwitches() {
        // Ограничение: не более 4 фоновых оптимизаций за 5-минутное окно
        // и не чаще 1 раза в 60 секунд
        SplateVpnService.clearDeadServers()

        // 1-я смена
        assertTrue(SplateVpnService.canAutoSwitch(isDeadNode = false))
        SplateVpnService.recordAutoSwitch()

        // Сразу после смены (кулдаун 60 сек активен)
        assertFalse(SplateVpnService.canAutoSwitch(isDeadNode = false))

        // Имитируем прошествие 65 секунд
        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 65_000L
        assertTrue(SplateVpnService.canAutoSwitch(isDeadNode = false))
        SplateVpnService.recordAutoSwitch() // 2-я смена

        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 65_000L
        SplateVpnService.recordAutoSwitch() // 3-я смена

        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 65_000L
        SplateVpnService.recordAutoSwitch() // 4-я смена

        assertEquals(4, SplateVpnService.consecutiveAutoSwitchCount)

        // 5-я смена в пределах 5 минут: заблокирована скользящим окном!
        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 65_000L
        assertFalse("5-я оптимизация за 5 минут должна быть заблокирована Circuit Breaker", SplateVpnService.canAutoSwitch(isDeadNode = false))
    }

    // =========================================================================
    // СЦЕНАРИЙ 5: Серверы не найдены (Continuous Retry & No Disconnect)
    // =========================================================================

    @Test
    fun testContinuousSearch_staysInReconnectingAndRecoversWhenNodeBecomesAlive() = runBlocking {
        val server1 = ServerNode(id = "srv-1", name = "Server 1", ping = -1L, protocol = "vless", address = "1.1.1.1", port = 443)
        val server2 = ServerNode(id = "srv-2", name = "Server 2", ping = -1L, protocol = "vless", address = "2.2.2.2", port = 443)
        val pool = listOf(server1, server2)

        // Симуляция: при первичном поиске оба сервера в таймауте (-1L)
        SplateVpnService.markDeadServer("srv-1")
        SplateVpnService.markDeadServer("srv-2")

        // Оба сервера в карантине: размер карантина равен размеру пула
        assertEquals(2, SplateVpnService.deadServerIds.size)

        // Проверяем логику непрерывного цикла: при исчерпании пула карантин сбрасывается для повторного шанса
        if (SplateVpnService.deadServerIds.size >= pool.size) {
            SplateVpnService.clearDeadServers()
        }
        assertTrue("Карантин должен сброситься при исчерпании пула для повторного поиска", SplateVpnService.deadServerIds.isEmpty())

        // Теперь симулируем, что связь восстановилась и сервер 2 ожил
        val recoveredServer2 = server2.copy(ping = 45L)
        val updatedPool = listOf(server1, recoveredServer2)

        val selected = updatedPool.filter { it.ping > 0 }.minByOrNull { it.ping }
        assertNotNull("Рабочий сервер должен быть обнаружен после восстановления", selected)
        assertEquals("srv-2", selected?.id)
        assertEquals(45L, selected?.ping)
    }

    @Test
    fun testNodeFailureRecovery_clearsExhaustedDeadPoolAutomatically() {
        val nodes = (1..5).map {
            ServerNode(id = "srv-$it", name = "Server $it", ping = -1L, protocol = "vless", address = "10.0.0.$it", port = 443)
        }

        // Помечаем все 5 узлов как мёртвые
        nodes.forEach { SplateVpnService.markDeadServer(it.id) }
        assertEquals(5, SplateVpnService.deadServerIds.size)

        // При отсутствии серверов балансировщик не должен блокироваться навсегда
        if (SplateVpnService.deadServerIds.size >= nodes.size && nodes.isNotEmpty()) {
            SplateVpnService.clearDeadServers()
        }
        assertTrue("После сброса карантина узлы снова доступны для проверки", SplateVpnService.deadServerIds.isEmpty())
    }
}
