package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutoBalancerTest {

    @Before
    fun setUp() {
        SplateVpnService.clearDeadServers()
    }

    @Test
    fun testDeadNodeSwitching_notBlockedBySlidingWindowLimit() {
        // Симулируем 4 переключения подряд
        for (i in 1..4) {
            SplateVpnService.recordAutoSwitch()
        }

        assertEquals(4, SplateVpnService.consecutiveAutoSwitchCount)

        // Фоновая оптимизация качества (isDeadNode = false) должна быть заблокирована лимитом окна
        assertFalse(
            "Фоновая оптимизация должна быть заблокирована после 4 переключений",
            SplateVpnService.canAutoSwitch(isDeadNode = false)
        )

        // Аварийное переключение мертвого узла (isDeadNode = true)
        // Не должно блокироваться лимитом окна (только 1-секундным кулдауном)
        // Сразу после recordAutoSwitch кулдаун активен
        assertFalse(
            "Аварийное переключение должно ждать минимальный 1-секундный кулдаун",
            SplateVpnService.canAutoSwitch(isDeadNode = true)
        )

        // Имитируем истечение 1-секундного кулдауна
        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 1100L

        // Теперь аварийное переключение мертвого узла ОБЯЗАНО быть разрешено,
        // несмотря на то, что за 5 минут уже было 4 смены!
        assertTrue(
            "Аварийное переключение мертвого узла должно срабатывать даже при 4+ смен в окне",
            SplateVpnService.canAutoSwitch(isDeadNode = true)
        )
    }

    @Test
    fun testDeadServersTrackingAndClearing() {
        SplateVpnService.deadServerIds.add("server-1")
        SplateVpnService.deadServerIds.add("server-2")

        assertTrue(SplateVpnService.deadServerIds.contains("server-1"))
        assertTrue(SplateVpnService.deadServerIds.contains("server-2"))

        SplateVpnService.unmarkDeadServer("server-1")
        assertFalse(SplateVpnService.deadServerIds.contains("server-1"))
        assertTrue(SplateVpnService.deadServerIds.contains("server-2"))

        SplateVpnService.clearDeadServers()
        assertTrue(SplateVpnService.deadServerIds.isEmpty())
        assertEquals(0L, SplateVpnService.lastAutoSwitchTimestamp)
    }

    @Test fun customOptimizationPauseDoesNotDelayEmergencyRecovery() {
        SplateVpnService.lastAutoSwitchTimestamp = System.currentTimeMillis() - 120_000L
        assertTrue(SplateVpnService.canAutoSwitch(false))
        assertFalse(SplateVpnService.canAutoSwitch(false, 180_000L))
        assertTrue(SplateVpnService.canAutoSwitch(true, 180_000L))
    }
}
