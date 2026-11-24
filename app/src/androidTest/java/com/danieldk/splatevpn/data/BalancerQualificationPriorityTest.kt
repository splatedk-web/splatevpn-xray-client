package com.danieldk.splatevpn.data

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateApplication
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class BalancerQualificationPriorityTest {
    @Test fun priorityTunnelStartsWhileAllUserProbeSlotsAreHeld(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SplateApplication
        val previous = app.settingsManager.preferenceSnapshot()
        val repository = EntryPointAccessors.fromApplication(app,
            SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val probes = ServerRepository::class.java.getDeclaredField("probes").apply { isAccessible = true }.get(repository) as ServerProbeRepository
        val userGate = ServerProbeRepository::class.java.getDeclaredField("realDelayGate").apply { isAccessible = true }.get(probes) as ServerProbeGate
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val jobs = mutableListOf<Job>()
        try {
            app.settingsManager.setChecksWifiOnly(false)
            repeat(REAL_DELAY_PARALLELISM) {
                jobs += launch { userGate.withSlot(20_000) {
                    if (count.incrementAndGet() == REAL_DELAY_PARALLELISM) entered.complete(Unit)
                    release.await()
                } }
            }
            withTimeout(3000) { entered.await() }
            val result = withTimeout(10_000) {
                probes.withServerTunnel(ServerNode("priority-fixture", "Priority fixture", "socks", "127.0.0.1", 9),
                    6000, useBalancerGate = true) { port, _ ->
                    Socket("127.0.0.1", port).use { it.isConnected }
                }
            }
            assertEquals(true, result)
            assertFalse(release.isCompleted)
        } finally {
            release.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
            app.settingsManager.restorePreferenceSnapshot(previous)
        }
    }
}
