package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses a real APK parser and an isolated update directory, without network or installation. */
class UpdateCacheValidationTest {
    @Test fun cachedApkMustMatchTheRequestedVersionAndCorruptEntriesCanBeRetried() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "update-cache-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getExternalFilesDir(type: String?) = directory
            override fun getCacheDir() = directory
        }
        try {
            val version = requireNotNull(app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            val manager = UpdateManager(isolated)
            val updates = File(directory, "updates").apply { mkdirs() }
            val current = UpdateFilePolicy.cachedApk(updates, version, "app.apk")
            val source = File(app.applicationInfo.sourceDir)
            val staged = UpdateFilePolicy.stagedApk(current)
            source.copyTo(staged)
            assertNull(manager.inspectUpdateApk(staged, version))
            assertEquals(UpdateApkIssue.PACKAGE_OR_VERSION, manager.inspectUpdateApk(staged, "9000.0.0"))
            assertNull(manager.getDownloadedApkFile(version, "app.apk"))
            assertTrue(staged.renameTo(current))
            assertEquals(current, manager.getDownloadedApkFile(version, "app.apk"))
            assertEquals(current, manager.getDownloadedApkFile(version, "app.apk"))

            val otherVersion = "9000.0.0"
            val other = UpdateFilePolicy.cachedApk(updates, otherVersion, "app.apk")
            source.copyTo(other)
            assertNull(manager.getDownloadedApkFile(otherVersion, "app.apk"))
            assertFalse("Wrong-version APK must not trap the update button", other.exists())
            assertTrue("Other release rejection must retain the correct cache", current.isFile)

            current.writeText("not an APK")
            assertNull(manager.getDownloadedApkFile(version, "app.apk"))
            assertFalse(current.exists())
            source.copyTo(current)
            assertEquals(current, manager.getDownloadedApkFile(version, "app.apk"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
