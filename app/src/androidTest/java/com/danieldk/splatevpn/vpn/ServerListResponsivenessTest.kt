package com.danieldk.splatevpn.vpn

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.serverUiSnapshots
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerListResponsivenessTest {
    @Test fun expensiveListProjectionLeavesMainLooperResponsive(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val ranOnMain = AtomicBoolean(false)
        val nodes = object : AbstractList<ServerNode>() {
            override val size = 2000
            override fun get(index: Int): ServerNode {
                if (index == 0) {
                    ranOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    entered.countDown(); Thread.sleep(350)
                }
                return ServerNode("$index", "Node $index", "vless", "example.com", 443)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val result = scope.async { serverUiSnapshots(MutableStateFlow(nodes), MutableStateFlow(mapOf("0" to 17L))).first() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val heartbeat = CompletableDeferred<Long>(); val start = System.nanoTime()
            Handler(Looper.getMainLooper()).post { heartbeat.complete((System.nanoTime()-start)/1_000_000) }
            val latency = withTimeout(2000) { heartbeat.await() }
            assertTrue("Main looper delayed $latency ms", latency < 200)
            assertFalse(ranOnMain.get())
            val list = withTimeout(3000) { result.await() }
            assertEquals(2000,list.size); assertEquals(17L,list.first().ping)
            android.util.Log.i("VpnResponsiveness","serverProjectionMainHeartbeatMs=$latency nodes=${list.size}")
        } finally { scope.cancel() }
    }
}
