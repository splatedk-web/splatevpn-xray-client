package com.danieldk.splatevpn.vpn

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VpnStatusRecoveryTest {
    private fun manager() = VpnStatusManager(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun reconnectKeepsSessionTrafficAndDisconnectClearsSpeedAndOldError() {
        val manager = manager()
        manager.setState(VpnState.CONNECTED)
        manager.updateSessionBytes(120, 45)
        val started = manager.sessionStartTime.value
        manager.setState(VpnState.RECONNECTING)
        manager.setState(VpnState.CONNECTED)
        manager.setState(VpnState.CONNECTED)
        assertEquals(120L, manager.sessionRxBytes.value)
        assertEquals(45L, manager.sessionTxBytes.value)
        assertEquals(started, manager.sessionStartTime.value)
        manager.updateSpeeds("1", "2", 500)
        manager.setState(VpnState.FAILED, "previous error")
        assertEquals(0L, manager.downBytesPerSec.value)
        manager.setState(VpnState.DISCONNECTED)
        assertNull(manager.errorMessage.value)
        assertEquals(0L, manager.sessionRxBytes.value)
    }

    @Test fun lateIpResponseCannotRestoreDetailsAfterDisconnect() = runBlocking {
        val manager = manager()
        manager.setState(VpnState.CONNECTED)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val job = launch {
            manager.fetchCurrentIpDetailsUsing { _, _ ->
                entered.complete(Unit)
                response.await()
            }
        }
        withTimeout(3_000) { entered.await() }
        manager.setState(VpnState.DISCONNECTED)
        response.complete("""{"success":true,"ip":"192.0.2.1","country":"Test","country_code":"NL"}""")
        withTimeout(3_000) { job.join() }
        assertEquals("", manager.currentIp.value)
        assertFalse(manager.isDetectingIp.value)
    }

    @Test fun cancelledIpLookupDoesNotTryOtherProvidersOrLeaveSpinner() = runBlocking {
        val manager = manager()
        val entered = CompletableDeferred<Unit>()
        var requests = 0
        val job = launch {
            manager.fetchCurrentIpDetailsUsing { _, _ ->
                requests++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(3_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(1, requests)
        assertFalse(manager.isDetectingIp.value)
    }
}
