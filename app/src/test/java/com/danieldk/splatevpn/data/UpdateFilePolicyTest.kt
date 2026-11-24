package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdateFilePolicyTest {
    @Test fun releaseDigestAndStagedPathAreValidated() {
        val digest = "ABCDEF".repeat(10) + "1234"
        assertEquals(digest.lowercase(), UpdateFilePolicy.sha256FromAsset("sha256:$digest"))
        assertNull(UpdateFilePolicy.sha256FromAsset(digest))
        assertNull(UpdateFilePolicy.sha256FromAsset("sha256:short"))
        val target = File("updates", "splatevpn_1.3.0_release.APK")
        val staged = UpdateFilePolicy.stagedApk(target)
        assertEquals(target.parentFile, staged.parentFile)
        assertTrue(staged.name.endsWith(".apk"))
        assertNotEquals(target, staged)
    }
    @Test fun updateCertificatesMustBePresentAndMatchInstalledSigningHistory() {
        val known = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        assertTrue(UpdateFilePolicy.hasMatchingCertificate(listOf(known), listOf(known.copyOf())))
        assertTrue(UpdateFilePolicy.hasMatchingCertificate(listOf(other, known), listOf(known)))
        assertFalse(UpdateFilePolicy.hasMatchingCertificate(emptyList(), listOf(known)))
        assertFalse(UpdateFilePolicy.hasMatchingCertificate(listOf(known), emptyList()))
        assertFalse(UpdateFilePolicy.hasMatchingCertificate(emptyList(), emptyList()))
        assertFalse(UpdateFilePolicy.hasMatchingCertificate(listOf(byteArrayOf()), listOf(byteArrayOf())))
        assertFalse(UpdateFilePolicy.hasMatchingCertificate(listOf(known), listOf(other)))
    }

    @Test fun apkIdentityMustMatchTheRequestedReleaseRatherThanOnlyItsFilename() {
        val app = "com.example.vpn"
        assertTrue(UpdateFilePolicy.matchesApk(app, "1.2.7", app, "1.2.7"))
        assertTrue(UpdateFilePolicy.matchesApk(app, "v1.2.7", app, "1.2.7"))
        assertTrue(UpdateFilePolicy.matchesApk(app, "1.2.7", app, "v1.2.7"))
        for (version in listOf(null, "", "1.2.6", "1.2.70", "1.2.7-rc.1", "1.2.7-core-compare")) {
            assertFalse("Unexpected APK version: $version", UpdateFilePolicy.matchesApk(app, "1.2.7", app, version))
        }
        assertFalse(UpdateFilePolicy.matchesApk(app, "1.2.7", "com.other.vpn", "1.2.7"))
        assertFalse(UpdateFilePolicy.matchesApk(app, "1.2.7", null, null))
        assertFalse(UpdateFilePolicy.matchesApk(app, "", app, ""))
    }

    @Test fun identicalAssetNamesDoNotReuseAnOlderRelease() {
        val directory = Files.createTempDirectory("update-cache").toFile()
        try {
            val old = UpdateFilePolicy.cachedApk(directory, "1.2.6", "app-universal-release.apk")
            old.writeText("already downloaded")
            val next = UpdateFilePolicy.cachedApk(directory, "1.2.7", "app-universal-release.apk")
            assertNotEquals(old, next)
            assertFalse(next.exists())
            assertEquals(old, UpdateFilePolicy.cachedApk(directory, "1.2.6", "app-universal-release.apk"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun defaultAndSplitAssetsStayInsideTheCache() {
        val directory = File(System.getProperty("java.io.tmpdir"), "update-cache-policy")
        for (asset in listOf(null, "", "app-arm64-v8a.apk", "Splate VPN (universal).apk")) {
            val file = UpdateFilePolicy.cachedApk(directory, "1.2.7-rc.1+build2", asset)
            assertEquals(directory.canonicalFile, file.canonicalFile.parentFile)
            assertTrue(file.name.endsWith(".apk"))
        }
    }

    @Test fun remoteMetadataCannotChooseAPathOutsideTheCache() {
        val directory = File(System.getProperty("java.io.tmpdir"), "update-cache-policy")
        for (asset in listOf("../other.apk", "..\\other.apk", "/tmp/other.apk", "C:\\other.apk", "bad\u0000.apk", "file.zip")) {
            assertTrue(asset, runCatching { UpdateFilePolicy.cachedApk(directory, "1.2.7", asset) }.isFailure)
        }
        for (version in listOf("../1.2.7", "..\\1.2.7", "v1\n", "", "x".repeat(65))) {
            assertTrue(version, runCatching { UpdateFilePolicy.cachedApk(directory, version, "app.apk") }.isFailure)
        }
    }
}
