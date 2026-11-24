package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.SplateConstants
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GeoDataValidationTest {
    @Test fun rollbackWithoutPreviousVersionReportsAnExplicitResult() = kotlinx.coroutines.runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "geo-rollback-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val preferenceName = "geo-rollback-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(preferenceName, mode)
        }
        try {
            val repository = GeoDataRepository(context)
            repository.rollback()
            assertEquals(GeoDataResult.NO_PREVIOUS, repository.status.value.result)
            assertFalse(repository.status.value.busy)
            assertFalse(repository.status.value.error)
            assertEquals(SplateConstants.GEO_DATA_REVISION, repository.currentRevision())
        } finally {
            directory.deleteRecursively()
            app.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun bundledDatabasesValidateEverySupportedRegionAndRejectCorruption() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "geo-validation-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        try {
            listOf("geoip.dat", "geosite.dat").forEach { name ->
                app.assets.open(name).use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
            }
            val repository = GeoDataRepository(context)
            repository.validate(directory)
            assertEquals(SplateConstants.GEO_DATA_REVISION, repository.currentRevision())
            File(directory, "geosite.dat").writeText("broken database")
            assertThrows(IllegalStateException::class.java) { repository.validate(directory) }
            assertEquals(SplateConstants.GEO_DATA_REVISION, repository.currentRevision())
        } finally { directory.deleteRecursively() }
    }
}
