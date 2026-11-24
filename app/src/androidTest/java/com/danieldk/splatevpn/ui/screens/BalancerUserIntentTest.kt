package com.danieldk.splatevpn.ui.screens

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider
import com.danieldk.splatevpn.vpn.VpnState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BalancerUserIntentTest {
    @Test fun importingASubscriptionDuringSelectionKeepsAutoEnabledAndDoesNotSelectUncheckedNodes() = withLocalFixture { fixture ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(context, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val previousSources = runBlocking { fixture.database.subscriptionDao().getAllSubscriptionsSync().map { it.id }.toSet() }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { origin ->
            val responder = Thread {
                origin.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "socks5://127.0.0.1:${fixture.manualNode.port}".toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(body)
                }
            }.apply { isDaemon = true; start() }
            try {
                runBlocking { fixture.database.serverDao().setActiveServer(fixture.manualNode.id) }
                fixture.settings.setServersEnabled(setOf(fixture.manualNode.id), true)
                fixture.scenario.onActivity { fixture.model.autoSelectFastestServer(enableAutoBalancer = true) }
                val imported = runBlocking { withTimeout(15000) { repository.importSubscription("http://127.0.0.1:${origin.localPort}/subscription") } }
                assertTrue(imported.isSuccess)
                assertEquals(1, imported.getOrThrow())
                assertTrue(fixture.settings.isAutoSelectServer())
                assertEquals(fixture.manualNode.id, runBlocking { fixture.database.serverDao().getSelectedServerSync()?.id })
            } finally {
                fixture.scenario.onActivity { fixture.model.setAutoSelect(false); fixture.model.cancelPingChecks() }
                awaitIdle(fixture.model)
                runBlocking {
                    fixture.database.subscriptionDao().getAllSubscriptionsSync().filter { it.id !in previousSources }.forEach {
                        fixture.database.serverDao().deleteServersBySubId(it.id)
                        fixture.database.subscriptionDao().deleteSubscription(it.id)
                    }
                }
                responder.join(1000)
            }
        }
    }
    @Test fun rapidPreferenceChangesCannotReenableAutoAfterTheUserTurnsItOff() = withLocalFixture { fixture ->
        val lateEnables = AtomicInteger()
        var observer: Job? = null
        fixture.scenario.onActivity { activity ->
            // Keep cancellation continuations queued behind this Main callback. The old
            // search must finish cancelling before a preference-triggered search starts.
            fixture.model.autoSelectFastestServer(enableAutoBalancer = true)
            assertTrue(fixture.model.isPinging.value)
            fixture.model.setQuickBalancerPreferences(BalancerPreferences(goal = BalancerGoal.TELEGRAM, warmStandby = false))
            fixture.model.setQuickBalancerPreferences(BalancerPreferences(goal = BalancerGoal.YOUTUBE, warmStandby = false))
            fixture.model.setAutoSelect(false)
            observer = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.model.isAutoSelect.collect { if (it) lateEnables.incrementAndGet() }
            }
        }
        try {
            awaitIdle(fixture.model)
            assertRemainsManual(fixture.model)
            assertEquals("A cancelled preference request must not turn Auto back on", 0, lateEnables.get())
        } finally { observer?.cancel() }
    }

    @Test fun explicitServerChoiceCancelsTheOlderSearchAndKeepsItsSelection() = withLocalFixture { fixture ->
        val oldCompletions = AtomicInteger()
        val selected = CompletableDeferred<Result<Unit>>()
        fixture.scenario.onActivity { activity ->
            fixture.model.autoSelectFastestServer(enableAutoBalancer = true) { oldCompletions.incrementAndGet() }
            assertTrue(fixture.model.isPinging.value)
            fixture.settings.setServersEnabled(setOf(fixture.manualNode.id), true)
            activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                selected.complete(fixture.model.selectVpnServer(fixture.manualNode.id))
            }
        }
        val result = runBlocking { withTimeout(10_000) { selected.await() } }
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        awaitIdle(fixture.model)
        assertRemainsManual(fixture.model)
        assertEquals("An abandoned search must not publish its old result", 0, oldCompletions.get())
        assertEquals(fixture.manualNode.id, runBlocking { fixture.database.serverDao().getSelectedServerSync()?.id })
        assertEquals(VpnState.DISCONNECTED, fixture.model.vpnState.value)
    }

    private data class Fixture(
        val scenario: ActivityScenario<MainActivity>, val model: MainViewModel,
        val settings: SettingsManager, val database: AppDatabase, val manualNode: ServerNode
    )

    private fun withLocalFixture(test: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as SplateApplication
        val settings = app.settingsManager
        val status = EntryPointAccessors.fromApplication(context, SplateAppWidgetProvider.WidgetEntryPoint::class.java).vpnStatusManager()
        assumeTrue("Run this regression with the VPN disconnected", status.state.value == VpnState.DISCONNECTED)
        val tor = EntryPointAccessors.fromApplication(context, TorSubscriptionWorker.Dependencies::class.java).torRepository()
        assumeFalse("This fixture exercises VPN selection", runBlocking { tor.load().enabled })
        val repository = EntryPointAccessors.fromApplication(context, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        // Use the application's Room instance so fixture changes invalidate its live flows.
        val database = ServerRepository::class.java.getDeclaredField("database").apply { isAccessible = true }
            .get(repository) as AppDatabase
        val previousSettings = settings.preferenceSnapshot()
        val previousSelection = runBlocking { database.serverDao().getAllServersSync().filter { it.isSelected }.map { it.id } }
        val token = UUID.randomUUID().toString()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { blackhole ->
            // No relay accepts these connections: even a stale search can only reach loopback.
            val manual = ServerNode("intent-$token", "Local manual fixture", "socks", "127.0.0.1", blackhole.localPort)
            try {
                settings.completeOnboarding("en", "ru", "global", false)
                settings.setInitializedPresetSubscriptions(true)
                settings.setInitializedSponsorSubscription()
                settings.setAutoConnectOnBoot(false)
                settings.setAutoUpdateInterval("off")
                settings.setAutoSelectServer(false)
                settings.setChecksWifiOnly(false)
                settings.setBalancerPreferences(BalancerPreferences(warmStandby = false))
                runBlocking {
                    database.serverDao().insertServer(manual)
                    settings.storeDisabledServerIds(database.serverDao().getAllServersSync().mapTo(hashSetOf()) { it.id })
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: MainViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
                    runBlocking { withTimeout(15_000) { while (model.isStartupLoading.value) delay(20) } }
                    try { test(Fixture(scenario, model, settings, database, manual)) }
                    finally {
                        scenario.onActivity { model.setAutoSelect(false); model.cancelPingChecks() }
                        awaitIdle(model)
                    }
                }
            } finally {
                runBlocking {
                    database.withTransaction {
                        database.serverDao().deleteServer(manual.id)
                        database.serverDao().clearSelection()
                        previousSelection.forEach { id -> database.serverDao().selectServer(id) }
                    }
                }
                settings.restorePreferenceSnapshot(previousSettings)
            }
        }
    }

    private fun awaitIdle(model: MainViewModel) {
        runBlocking { withTimeout(10_000) { while (model.isPinging.value) delay(20) } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertRemainsManual(model: MainViewModel) = runBlocking {
        repeat(5) {
            delay(100)
            assertFalse("User turned Auto off", model.isAutoSelect.value)
            assertFalse("No queued search should restart", model.isPinging.value)
        }
    }
}
