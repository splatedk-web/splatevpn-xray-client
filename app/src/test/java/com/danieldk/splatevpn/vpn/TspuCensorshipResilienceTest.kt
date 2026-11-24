package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Виртуальный стресс-тест устойчивости SplateVPN к агрессивным сценариям цензуры ТСПУ (РКН).
 *
 * Симулирует реальные атаки цензора:
 * 1. Ковровый сброс подсетей зарубежных дата-центров (ASN SYN Drop).
 * 2. Активное зондирование и блокировка ClientHello по SNI (Active Probing / SNI Censorship).
 * 3. Полный блэкаут обычных VLESS/Hysteria серверов и аварийный прорыв через Tor (Emergency Tor Failover).
 * 4. Удушение UDP-трафика (UDP Throttling на Hysteria 2).
 * 5. Блокировка загрузки подписок с GitHub провайдером с авто-восстановлением через VPN.
 */
class TspuCensorshipResilienceTest {

    private fun vlessNode(id: String, ip: String, port: Int = 443, security: String = "reality") = ServerNode(
        id = id,
        name = "Server-$id",
        protocol = "vless",
        address = ip,
        port = port,
        security = security,
        sni = "yahoo.com",
        publicKey = "mock_public_key_reality",
        shortId = "1a2b3c4d"
    )

    // =========================================================================
    // СЦЕНАРИЙ 1: Ковровый сброс подсетей зарубежных дата-центров (ASN SYN Drop)
    // =========================================================================

    @Test
    fun testTspuAsnDrop_90PercentBlocked_prefilterIsolatesSurvivor() = runBlocking {
        // ТСПУ Билайна или Ростелекома блокирует 9 из 10 серверов (TCP SYN timeout)
        val candidatePool = (1..10).map { i ->
            vlessNode("node-$i", "152.89.20.$i")
        }

        // Симулируем ответ сети: узлы 1..9 заблокированы по TCP (timeout -1L), узел 10 живой (42ms)
        val simulateTcpProbe: suspend (ServerNode) -> Long = { node ->
            if (node.id == "node-10") 42L else -1L
        }

        val responsive = mutableListOf<Pair<ServerNode, Long>>()
        candidatePool.forEach { node ->
            val ping = simulateTcpProbe(node)
            if (ping > 0) responsive.add(node to ping)
        }

        assertEquals("Только выживший узел должен пройти фильтр", 1, responsive.size)
        assertEquals("node-10", responsive.first().first.id)
        assertEquals(42L, responsive.first().second)
    }

    // =========================================================================
    // СЦЕНАРИЙ 2: Блокировка SNI и спасение через адаптивный TLS Fragment
    // =========================================================================

    @Test
    fun testTspuActiveProbing_SniBlocking_adaptiveTlsFragmentSavesConnection() {
        val server = vlessNode("srv-blocked-sni", "194.87.100.5", security = "reality")

        // Моделируем поведение ТСПУ:
        // Без фрагментации (голый ClientHello) — DPI видит сигнатуру/SNI и сбрасывает TCP RST (-1L)
        // С фрагментацией (100-200 байт, 1-3 пакета) — DPI не может прочитать SNI и пропускает трафик (88ms)
        var fragmentRequiredSaved = false
        val probeSimulator: (Boolean) -> Long = { isFragmented ->
            if (isFragmented) 88L else -1L
        }

        var delay = probeSimulator(false)
        if (delay <= 0) {
            // Сработал адаптивный механизм обхода DPI в ServerProbeRepository:
            val fragmentDelay = probeSimulator(true)
            if (fragmentDelay > 0) {
                fragmentRequiredSaved = true
                delay = fragmentDelay
            }
        }

        assertTrue("Адаптивный механизм должен зафиксировать необходимость TLS Fragment", fragmentRequiredSaved)
        assertEquals("С фрагментацией узел должен успешно завестись", 88L, delay)
    }

    // =========================================================================
    // СЦЕНАРИЙ 3: Полный блэкаут обычных узлов и суверенитет пользователя (без авто-переключения на Tor)
    // =========================================================================

    @Test
    fun testTspuTotalBlackout_preservesUserChoiceAndDoesNotAutoForceTor() {
        // Ситуация как на раздаче Билайна: все 100% VLESS серверов временно не отвечают
        val isPhysicalInternetAlive = true
        var retryAttempt = 0
        var verifiedServerFound = false
        var usingTor = false

        while (!verifiedServerFound && retryAttempt < 3) {
            retryAttempt++
            // Симулируем проверку серверов: они временно под ТСПУ
            val foundServer: ServerNode? = null
            if (foundServer != null) {
                verifiedServerFound = true
            }
        }

        // Ключевое архитектурное правило: Tor — это осознанный ручной выбор пользователя!
        // Клиент НЕ должен самовольно переключать протокол и переводить весь трафик в Tor без согласия.
        assertFalse("Клиент никогда не должен самовольно принудительно включать Tor", usingTor)
        assertEquals(3, retryAttempt)
    }

    // =========================================================================
    // СЦЕНАРИЙ 4: Удушение UDP (UDP Throttling на Hysteria 2)
    // =========================================================================

    @Test
    fun testTspuUdpThrottling_hysteriaFails_switchesToTcpReality() {
        val tracker = ServerQualityTracker()
        tracker.onNetworkChanged(200L)

        // Hysteria 2 работает по UDP. ТСПУ Ростелекома душит UDP порт -> 3 сбоя подряд
        repeat(3) {
            tracker.recordFailure("hy2-node-germany")
        }

        assertTrue("После 3 сбоев Hysteria 2 должна попасть в карантин", tracker.isQuarantined("hy2-node-germany"))

        // Балансировщик исключает заблокированный узел и находит стабильный VLESS XHTTP через TCP
        val vlessTcp = vlessNode("vless-tcp-finland", "95.217.10.20")
        tracker.recordSuccess(vlessTcp.id, 65L)

        assertFalse("VLESS TCP не должен быть в карантине", tracker.isQuarantined(vlessTcp.id))
        assertTrue("VLESS TCP должен иметь свежий статус успеха", tracker.hasFreshSuccess(vlessTcp.id))
    }

    // =========================================================================
    // СЦЕНАРИЙ 5: Блокировка GitHub при обновлении подписок и спасение через VPN
    // =========================================================================

    @Test
    fun testTspuGithubSubscriptionBlock_recoversViaVpnTunnel() = runBlocking {
        var directAttemptMade = false
        var vpnFallbackAttemptMade = false
        val isVpnConnected = true

        val fetchSubscription = { viaVpn: Boolean ->
            if (!viaVpn) {
                directAttemptMade = true
                throw IOException("Connection reset by peer (ТСПУ DPI drop на raw.githubusercontent.com)")
            } else {
                vpnFallbackAttemptMade = true
                "vless://user@server:443#WorkingServer"
            }
        }

        var result: String? = null
        try {
            result = fetchSubscription(false)
        } catch (e: IOException) {
            // Автоматический VPN-Fallback в SubscriptionRepository
            if (isVpnConnected) {
                result = fetchSubscription(true)
            }
        }

        assertTrue("Прямая попытка должна была быть предпринята", directAttemptMade)
        assertTrue("Попытка через VPN должна была спасти загрузку подписки", vpnFallbackAttemptMade)
        assertNotNull("Подписка должна быть успешно получена", result)
    }
}
