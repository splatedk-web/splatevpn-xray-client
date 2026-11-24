package com.danieldk.splatevpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudBackupLinksTest {
    @Test fun driveViewLinksBecomeDownloadsAndKeepResourceKeys() {
        val link = CloudBackupLinks.resolve("https://drive.google.com/file/d/abc123_DEF456/view?usp=sharing&resourcekey=key")
        assertEquals("https://drive.google.com/uc?export=download&id=abc123_DEF456&resourcekey=key", link.url)
        assertEquals("Google Drive", link.source)
        assertTrue(runCatching { CloudBackupLinks.resolve("https://drive.google.com/drive/folders/abc123_DEF456") }.isFailure)
    }
    @Test fun dropboxPreservesAccessKeyAndReplacesOnlyDownloadFlags() {
        val link = CloudBackupLinks.resolve("https://www.dropbox.com/scl/fi/token/backup.json?rlkey=secret&dl=0&raw=0")
        assertTrue(link.url.contains("rlkey=secret")); assertTrue(link.url.contains("dl=1"))
        assertFalse(link.url.contains("raw=")); assertFalse(link.url.contains("dl=0"))
    }
    @Test fun yandexResolvesDownloadHrefBeforeFetchingBackup() = runBlocking {
        val calls = mutableListOf<String>()
        val result = CloudBackupLinks.load("https://disk.yandex.ru/d/file-token") { url, _ ->
            calls += url
            if (calls.size == 1) BackupLinkResponse(200, "{\"href\":\"https://downloader.disk.yandex.ru/file\",\"method\":\"GET\"}")
            else BackupLinkResponse(200, "{\"app\":\"SplateVPN\"}")
        }
        assertTrue(calls.first().startsWith("https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key="))
        assertEquals("https://downloader.disk.yandex.ru/file", calls.last())
        assertEquals("Яндекс Диск", result.second)
    }
    @Test fun safeRedirectsAreFollowedAndHtmlIsNeverAcceptedAsBackup() = runBlocking {
        var calls = 0
        val result = CloudBackupLinks.load("https://files.example/backup") { _, _ ->
            calls++; if (calls == 1) BackupLinkResponse(302, "", "/download") else BackupLinkResponse(200, "\uFEFF {\"app\":\"SplateVPN\"}")
        }
        assertEquals(2, calls); assertTrue(result.first.startsWith('{'))
        assertTrue(runCatching { CloudBackupLinks.load("https://files.example/backup") { _, _ -> BackupLinkResponse(200, "<!doctype html>Sign in") } }.isFailure)
    }
    @Test fun rejectsDowngradesCredentialsRedirectLoopsAndPrivateCloudPages() = runBlocking {
        for (url in listOf("http://example.test/file", "https://user:pass@example.test/file", "file:///tmp/backup")) {
            assertTrue(runCatching { CloudBackupLinks.resolve(url) }.isFailure)
        }
        assertTrue(runCatching { CloudBackupLinks.load("https://files.example/backup") { _, _ -> BackupLinkResponse(302, "", "http://files.example/plain") } }.isFailure)
        var calls = 0
        assertTrue(runCatching { CloudBackupLinks.load("https://files.example/backup") { _, _ -> calls++; BackupLinkResponse(302, "", "/backup") } }.isFailure)
        assertEquals(6, calls)
        assertTrue(runCatching { CloudBackupLinks.load("https://files.example/backup") { _, _ -> BackupLinkResponse(403, "no") } }.isFailure)
    }
}
