package com.danieldk.splatevpn.vpn

import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.AmneziaWg
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Deliberately slow real lifecycle dependencies must never hold Android's UI thread. */
class VpnMainThreadResponsivenessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun stalledProtectionCallbackDoesNotBlockClosingOnMainThread() = runBlocking {
        assertNull("Grant VPN authorization on the isolated emulator", VpnService.prepare(app))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val before = AmneziaWgRuntime.runningCount
        val protector = AwgSocketProtection(VpnService()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val profile = AmneziaWg.parse("[Interface]\nPrivateKey = $key\nAddress = 10.77.0.2\n[Peer]\nPublicKey = $key\nEndpoint = 127.0.0.1:52991\nAllowedIPs = 0.0.0.0/0")
        val started = async(Dispatchers.IO) { runCatching { AmneziaWgRuntime.start(app, profile).use { delay(100) } } }
        try {
            assertTrue("Real descriptor protection callback was not reached", entered.await(5, TimeUnit.SECONDS))
            val elapsed = AtomicLong()
            instrumentation.runOnMainSync {
                val start = System.nanoTime(); protector.close(); elapsed.set((System.nanoTime() - start) / 1_000_000)
            }
            android.util.Log.i("VpnResponsiveness", "protectionCloseMainMs=${elapsed.get()}")
            assertNull(AwgSocketProtection.path)
            assertTrue("Main thread blocked for ${elapsed.get()} ms", elapsed.get() < 200)
        } finally {
            release.countDown()
            protector.close()
            withTimeout(6000) { started.await(); protector.awaitClosed() }
        }
        assertEquals(before, AmneziaWgRuntime.runningCount)
    }

    @Test fun statusTransitionsDoNotRunWidgetBinderCallsOnMainThread() {
        val observed = CountDownLatch(1)
        val calledOnMain = AtomicBoolean(false)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.APPWIDGET_SERVICE) {
                    if (Looper.myLooper() == Looper.getMainLooper()) calledOnMain.set(true)
                    observed.countDown()
                    Thread.sleep(350)
                }
                return super.getSystemService(name)
            }
        }
        val manager = VpnStatusManager(context)
        val elapsed = AtomicLong()
        instrumentation.runOnMainSync {
            val start = System.nanoTime()
            manager.setState(VpnState.STARTING_CORE)
            manager.setState(VpnState.CONNECTING)
            manager.setState(VpnState.CONNECTED)
            elapsed.set((System.nanoTime() - start) / 1_000_000)
        }
        assertTrue("Widget update was lost", observed.await(3, TimeUnit.SECONDS))
        android.util.Log.i("VpnResponsiveness", "statusTransitionsMainMs=${elapsed.get()}")
        assertFalse("Widget system calls ran on main", calledOnMain.get())
        assertTrue("State transitions blocked main for ${elapsed.get()} ms", elapsed.get() < 200)
        assertEquals(VpnState.CONNECTED, manager.state.value)
    }
}
