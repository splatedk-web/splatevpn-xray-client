package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AnonymousReportStoreTest {
    @Test fun collectionRequiresConsentIsBoundedAndDisablingErasesReports() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "anonymous-test-${UUID.randomUUID()}"
        val directory = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(key: String, mode: Int) = app.getSharedPreferences("$name-$key", mode)
        }
        val store = AnonymousReportStore(context)
        try {
            assertFalse(store.enabled.value)
            assertTrue(store.recordFeature("QR").isFailure)
            assertTrue(store.reports.value.isEmpty())
            store.setEnabled(true)
            repeat(7) { assertTrue(store.recordFeature("TOR").isSuccess) }
            assertEquals(5, store.reports.value.size)
            store.reports.value.forEach { report ->
                assertFalse(report.json.contains("device", true))
                assertFalse(report.json.contains("timestamp", true))
                assertFalse(report.json.contains("subscription", true))
                assertTrue(report.json.contains("TOR"))
            }
            store.setEnabled(false)
            assertTrue(store.reports.value.isEmpty())
            assertTrue(File(directory, "anonymous-reports").listFiles().orEmpty().isEmpty())
            assertFalse(AnonymousReportStore(context).enabled.value)
        } finally {
            store.setEnabled(false)
            app.deleteSharedPreferences("$name-anonymous_reports")
            directory.deleteRecursively()
        }
    }
}
