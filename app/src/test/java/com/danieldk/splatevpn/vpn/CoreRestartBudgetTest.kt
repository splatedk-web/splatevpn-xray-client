package com.danieldk.splatevpn.vpn

import org.junit.Assert.*
import org.junit.Test

class CoreRestartBudgetTest {
    @Test fun shortSuccessfulRestartsCannotBypassRetryLimit() {
        var now = 0L
        val budget = CoreRestartBudget(nowMs = { now })
        repeat(5) {
            budget.started()
            now += 3_000
            assertTrue(budget.crashed())
        }
        budget.started()
        now += 3_000
        assertFalse(budget.crashed())
    }

    @Test fun onlySustainedUptimeResetsFailures() {
        var now = 0L
        val budget = CoreRestartBudget(nowMs = { now })
        repeat(5) { assertTrue(budget.crashed()) }
        budget.started()
        now += 60_000
        assertTrue(budget.crashed())
        assertEquals(1, budget.failures)
    }
}
