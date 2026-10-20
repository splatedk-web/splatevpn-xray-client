package com.danieldk.splatevpn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionHandoffTest {
    @Test fun autoButtonAndConnectShareOneSearch() = runBlocking {
        val gate = SelectionHandoff<String, String>(Mutex())
        val release = CompletableDeferred<Unit>()
        var searches = 0
        val button = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", false, { true }) { searches++; release.await(); "node" }
        }
        val connect = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", true, { true }) { searches++; "duplicate" }
        }
        release.complete(Unit)
        assertEquals("node", button.await())
        assertEquals("node", connect.await())
        assertEquals(1, searches)
    }

    @Test fun immediateConnectReusesSuccessButLaterConnectRechecks() = runBlocking {
        var time = 100L
        val gate = SelectionHandoff<String, String>(Mutex()) { time }
        gate.run("same", false, { true }) { "verified" }
        assertEquals("verified", gate.run("same", true, { true }) { "duplicate" })
        time += 10_001
        assertEquals("fresh", gate.run("same", true, { true }) { "fresh" })
    }

    @Test fun changedSettingsOrInvalidCandidateRequireNewSearch() = runBlocking {
        val gate = SelectionHandoff<String, String>(Mutex())
        gate.run("old-settings", false, { true }) { "old" }
        assertEquals("new", gate.run("new-settings", true, { true }) { "new" })
        assertEquals("eligible", gate.run("new-settings", true, { false }) { "eligible" })
    }

    @Test fun failedOverlappingSearchDoesNotRestartAnotherFullScan() = runBlocking {
        val gate = SelectionHandoff<String, String>(Mutex())
        val release = CompletableDeferred<Unit>()
        var searches = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", false, { true }) { searches++; release.await(); null }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", true, { true }) { searches++; "duplicate" }
        }
        release.complete(Unit)
        assertNull(first.await())
        assertNull(second.await())
        assertEquals(1, searches)
        // A later explicit retry gets a fresh search, including after a previous timeout.
        assertEquals("retry", gate.run("same", true, { true }) { "retry" })
    }

    @Test fun cancelledOwnerDoesNotPoisonNextConnection() = runBlocking {
        val gate = SelectionHandoff<String, String>(Mutex())
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", false, { true }) { release.await(); "cancelled" }
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run("same", true, { true }) { "recovered" }
        }
        first.cancelAndJoin()
        assertEquals("recovered", next.await())
    }
}
