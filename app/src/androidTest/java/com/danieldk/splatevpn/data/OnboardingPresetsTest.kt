package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.vpn.XrayConfigGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OnboardingPresetsTest {
    @Test fun onboardingCreatesCachedSubscriptionsEvenWithStaleInitializationFlags() = verify(staleFlags = true)
    @Test fun freshOnboardingCreatesSubscriptionsWithoutInternet() = verify(staleFlags = false)
    @Test fun internationalOnboardingAlsoCreatesBundledSubscriptions() = verify(staleFlags = false, region = "international")

    @Test fun sponsorIsAvailableWithoutRegionalListsAndDeletionIsRespected() = withFixture {
        settings.completeOnboarding("en", "international", "global", false)
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        if (SplateConstants.PRESET_SUB_SPONSOR_URL.isBlank()) {
            assertTrue(subs.getAllSubscriptionsSync().isEmpty())
            assertFalse(settings.hasInitializedSponsorSubscription())
            return@withFixture
        }
        val source = requireNotNull(subs.getSubscriptionById(SplateConstants.PRESET_SUB_SPONSOR_ID))
        assertEquals(1, subs.getAllSubscriptionsSync().size)
        assertEquals(0, source.nodeCount)
        assertEquals(0L, source.lastUpdated)
        assertEquals(source.name, SubscriptionMetadata.decode(source.publisherMetadataJson).title)
        subs.deleteSubscription(source.id)
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        assertNull(subs.getSubscriptionById(source.id))
    }

    @Test fun sponsorAlreadyImportedByTheUserIsNotDuplicated() = withFixture {
        settings.completeOnboarding("en", "international", "global", false)
        val sourceUrl = SplateConstants.PRESET_SUB_SPONSOR_URL.ifBlank { "https://subscriptions.example/manual" }
        val existing = SubscriptionEntity("manual-sponsor", "My chosen name", sourceUrl,
            enabled = false, autoUpdate = false)
        subs.insertSubscription(existing)
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        assertEquals(listOf(existing), subs.getAllSubscriptionsSync())
    }

    @Test fun upgradeAddsOnlyNewSourcesAndPreservesEditedLegacySubscriptionAndSelection() = withFixture {
        val edited = seedExistingUserSetup()
        val oldNodeIds = dao.getAllServersSync().map { it.id }.toSet()
        repository.initPresetSubscriptions(refreshFromNetwork = false)

        val sponsorIds = builtInSubscriptions.filter { it.group == PresetGroup.SPONSOR }.map { it.id }
        assertEquals(setOf(SplateConstants.PRESET_SUB_CIDR_ID, SplateConstants.PRESET_SUB_UNIVERSAL_ID,
            SplateConstants.PRESET_SUB_RKP_WL_ID, SplateConstants.PRESET_SUB_RKP_BL_ID) + expandedIds + sponsorIds,
            subs.getAllSubscriptionsSync().map { it.id }.toSet())
        assertNull("An upgrade must not restore a legacy source the user deleted",
            subs.getSubscriptionById(SplateConstants.PRESET_SUB_HARVESTER_ID))
        assertEquals("User edits, disabled/auto-update switches and update history metadata must survive",
            edited, subs.getSubscriptionById(edited.id))
        assertEquals(oldNodeIds, dao.getAllServersSync().filter { it.subId !in expandedIds }.map { it.id }.toSet())
        assertEquals("selected-user-node", dao.getSelectedServerSync()?.id)
        assertTrue(settings.hasInitializedExpandedWhitelists())
        for (id in expandedIds) {
            val source = requireNotNull(subs.getSubscriptionById(id))
            assertTrue("The added source must have usable bundled nodes: $id", dao.getServersBySubIdSync(id).isNotEmpty())
            assertEquals("A cached snapshot is not a successful network refresh", 0L, source.lastUpdated)
            assertEquals("Offline initialization must not record a network attempt", 0L, source.lastAttemptAt)
        }

        val allNodes = dao.getAllServersSync().map { it.id }.toSet()
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        assertEquals("The next launch must not replace existing node identities", allNodes, dao.getAllServersSync().map { it.id }.toSet())
        assertEquals(edited, subs.getSubscriptionById(edited.id))
        assertEquals("selected-user-node", dao.getSelectedServerSync()?.id)
    }

    @Test fun deletedExpandedSourceStaysDeletedUntilExplicitRestoreWhichUsesAnUnrefreshedSnapshot() = withFixture {
        val edited = seedExistingUserSetup()
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        val deleted = SplateConstants.PRESET_SUB_LTE_ID
        val deletedNodeIds = dao.getServersBySubIdSync(deleted).map { it.id }.toSet()
        assertTrue("The source must exist before exercising deletion", deletedNodeIds.isNotEmpty())
        val preservedNodeIds = dao.getAllServersSync().filter { it.subId != deleted }.map { it.id }.toSet()
        dao.deleteServersBySubId(deleted)
        subs.deleteSubscription(deleted)

        // The expanded-group flag survives this launch; deletion is an explicit user choice.
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        assertNull(subs.getSubscriptionById(deleted))
        assertTrue(dao.getServersBySubIdSync(deleted).isEmpty())
        assertNull(subs.getSubscriptionById(SplateConstants.PRESET_SUB_HARVESTER_ID))
        assertEquals(preservedNodeIds, dao.getAllServersSync().map { it.id }.toSet())

        repository.initPresetSubscriptions(restoreMissing = true, refreshFromNetwork = false)
        val restored = requireNotNull(subs.getSubscriptionById(deleted))
        val restoredNodes = dao.getServersBySubIdSync(deleted)
        assertTrue(restoredNodes.isNotEmpty())
        assertEquals(SplateConstants.PRESET_SUB_LTE_URL, restored.url)
        assertEquals(0L, restored.lastUpdated)
        assertEquals(0L, restored.lastAttemptAt)
        assertEquals("", restored.lastError)
        assertEquals(restoredNodes.size, restored.nodeCount)
        assertTrue("Restore creates new IDs only for the deleted source", restoredNodes.none { it.id in deletedNodeIds })
        assertTrue(dao.getAllServersSync().map { it.id }.toSet().containsAll(preservedNodeIds))
        assertNotNull("An explicit full restore may also recover the deleted legacy source",
            subs.getSubscriptionById(SplateConstants.PRESET_SUB_HARVESTER_ID))
        assertEquals(edited, subs.getSubscriptionById(edited.id))
        assertEquals("selected-user-node", dao.getSelectedServerSync()?.id)
    }

    private val expandedIds = builtInSubscriptions.filter { it.group == PresetGroup.EXPANDED_WHITELISTS }
        .map { it.id }.toSet()

    private suspend fun Fixture.seedExistingUserSetup(): SubscriptionEntity {
        settings.completeOnboarding("en", "ru", "smart", true)
        settings.setInitializedPresetSubscriptions(true)
        settings.setInitializedUniversalWhitelist()
        settings.setInitializedBlackLists()
        assertFalse(settings.hasInitializedExpandedWhitelists())
        val edited = SubscriptionEntity(id = SplateConstants.PRESET_SUB_CIDR_ID,
            name = "My edited CIDR source", url = "https://custom.example/user-owned-cidr.txt",
            lastUpdated = 123_456_789L, nodeCount = 1, autoUpdate = false, enabled = false,
            lastAttemptAt = 123_456_999L, lastError = "Saved previous failure", lastFailureRetryable = true,
            previousNodeCount = 7)
        subs.insertSubscriptions(listOf(edited, SubscriptionEntity(id = SplateConstants.PRESET_SUB_UNIVERSAL_ID,
            name = SplateConstants.PRESET_SUB_UNIVERSAL_NAME, url = SplateConstants.PRESET_SUB_UNIVERSAL_URL,
            nodeCount = 1, lastUpdated = 123_456_789L)))
        val selected = requireNotNull(LinkParser.parse("vless://00000000-0000-0000-0000-000000000000@198.51.100.10:443#User"))
            .copy(id = "selected-user-node", subId = edited.id, isSelected = true)
        val universal = selected.copy(id = "existing-universal-node", subId = SplateConstants.PRESET_SUB_UNIVERSAL_ID,
            isSelected = false)
        dao.insertServers(listOf(selected, universal))
        return edited
    }

    private fun verify(staleFlags: Boolean, region: String = "ru") = withFixture {
        settings.completeOnboarding("ru", region, "smart", true)
        assertEquals(region, settings.getRoutingRegion())
        assertTrue(settings.regionalPresetsEnabled())
        if (staleFlags) {
            settings.setInitializedPresetSubscriptions(true)
            settings.setInitializedUniversalWhitelist()
        }
        repository.initPresetSubscriptions(restoreMissing = true, refreshFromNetwork = false)
        for (preset in builtInSubscriptions) {
            val source = requireNotNull(subs.getSubscriptionById(preset.id))
            val nodes = dao.getServersBySubIdSync(preset.id)
            if (preset.asset.isBlank()) {
                assertTrue(nodes.isEmpty())
                assertEquals(0, source.nodeCount)
                assertEquals(0L, source.lastUpdated)
            } else {
                assertTrue("Bundled nodes missing for ${preset.id}", nodes.isNotEmpty())
            }
        }
        assertNotNull(dao.getSelectedServerSync())
        val before = dao.getAllServersSync().map { it.id }.toSet()
        repository.initPresetSubscriptions(restoreMissing = true, refreshFromNetwork = false)
        assertEquals(before, dao.getAllServersSync().map { it.id }.toSet())
        // Normal launches must continue respecting sources the user intentionally deleted.
        val deleted = SplateConstants.PRESET_SUB_CIDR_ID
        dao.deleteServersBySubId(deleted)
        subs.deleteSubscription(deleted)
        repository.initPresetSubscriptions(refreshFromNetwork = false)
        assertNull(subs.getSubscriptionById(deleted))
    }

    private data class Fixture(val settings: SettingsManager, val dao: ServerDao,
        val subs: SubscriptionDao, val repository: ServerRepository)

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "onboarding-presets-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$prefix-$key", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val settings = SettingsManager(context)
            val dao = db.serverDao()
            val subs = db.subscriptionDao()
            val vault = AutoBackupManager(context, dao, subs, AppLogManager(context), db)
            val probes = ServerProbeRepository(context, dao, settings,
                javax.inject.Provider { error("No VPN needed to initialize bundled lists") },
                XrayConfigGenerator(), javax.inject.Provider { error("No core needed to initialize bundled lists") }, PhysicalFailureEvidence(context, settings))
            val performance = CandidatePerformanceRepository(context, probes, settings)
            val repository = ServerRepository(context, dao, subs, settings, vault, db, probes, performance,
                ServerSelectionCoordinator(dao, subs, settings, db, probes, performance),
                SubscriptionRepository(dao, subs, settings, vault, db, SubscriptionTransport()))
            Fixture(settings, dao, subs, repository).block()
        } finally {
            db.close()
            listOf("splate_prefs", "vault_metadata", "splate_logs").forEach { app.deleteSharedPreferences("$prefix-$it") }
            directory.deleteRecursively()
        }
    }
}
