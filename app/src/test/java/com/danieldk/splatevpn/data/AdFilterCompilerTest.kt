package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.vpn.AdBlockingPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class AdFilterCompilerTest {
    @Test fun onlyDomainRulesAreAcceptedAndExceptionsWin() {
        val source = """
            ||ads.example.com^
            ||example.net^
            ||sub.safe.example.net^
            @@||safe.example.net^
            ||context.example.org^${'$'}third-party
            ||wild*.example.org^
            example.org##.ads
            /ads.js
        """.trimIndent()
        assertEquals(listOf("ads.example.com"), AdFilterCompiler.parse(source, emptyList(), emptyList()))
    }
    @Test fun userExceptionsOverrideManualRulesAndParentsWithoutAllowingOtherContent() {
        val source = "||tracking.example.com^\n||example.org^\n||ads.example.net^"
        assertEquals(listOf("ads.example.net"), AdFilterCompiler.parse(source, listOf("sub.tracking.example.com"),
            listOf("tracking.example.com", "safe.example.org")))
    }
    @Test fun protectedRootsCannotBecomeBlanketBlocksButDedicatedAdsStillWork() {
        val source = "||yandex.ru^\n||youtube.com^\n||googlevideo.com^\n||an.yandex.ru^"
        assertEquals(listOf("an.yandex.ru"), AdFilterCompiler.parse(source, emptyList(), emptyList()))
        assertEquals(emptyList<String>(), AdFilterCompiler.parse(source, emptyList(), listOf("yandex.ru")))
    }
    @Test fun normalizeRejectsUrlsPathsAndIpAddresses() {
        listOf("https://example.com", "example.com/ads", "*.example.com", "127.0.0.1", "localhost", "a..com").forEach {
            assertNull(it, AdFilterCompiler.normalizeDomain(it))
        }
        assertEquals("example.com", AdFilterCompiler.normalizeDomain(" EXAMPLE.COM. "))
        assertEquals("xn--e1afmkfd.xn--p1ai", AdFilterCompiler.normalizeDomain("пример.рф"))
    }
    @Test fun bundledOfflineListIsUsableAndRetainsRussianAdNetworks() {
        val file = File("src/main/assets/adguard-dns-source.bin").takeIf { it.exists() } ?: File("app/src/main/assets/adguard-dns-source.bin")
        val source = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
        val domains = AdFilterCompiler.parse(source, AdBlockingPolicy.domains, emptyList())
        assertTrue("Expected a real offline base, got ${domains.size}", domains.size in 5_000..300_000)
        listOf("an.yandex.ru", "adsdk.yandex.ru", "r.my.com", "adfox.ru").forEach { assertTrue(it, it in domains) }
        assertFalse("yandex.ru" in domains)
        assertTrue(AdFilterCompiler.encode(domains).size < 8_000_000)
    }
}
