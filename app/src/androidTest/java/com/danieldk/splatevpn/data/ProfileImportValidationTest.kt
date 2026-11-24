package com.danieldk.splatevpn.data

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileImportValidationTest {
    @Test fun damagedKeyRejectsWholeImportWithoutSavingPartialBatch() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(app, SubscriptionUpdateWorker.WorkerDependencies::class.java).serverRepository()
        val before = repository.allServers.first().map { it.id }.toSet()
        val valid = "vless://11111111-2222-3333-4444-555555555555@192.0.2.1:443?security=reality&type=xhttp&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val error = runCatching { repository.addServersFromLinks(valid + "\n" + valid + "&pqv=damaged") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("REALITY"))
        assertEquals(before, repository.allServers.first().map { it.id }.toSet())
    }
}
