package com.danieldk.splatevpn.data

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.util.Locale

/** Conservative domain-only subset. URL, script, cosmetic and contextual rules are not DNS rules. */
internal object AdFilterCompiler {
    private val block = Regex("^\\|\\|([a-zA-Z0-9_.-]+)\\^$")
    private val exception = Regex("^@@\\|\\|([a-zA-Z0-9_.-]+)\\^(?:\\$.*)?$")
    private val protected = setOf("yandex.ru", "yandex.net", "yandex.com", "ya.ru", "vk.com", "vk.ru",
        "mail.ru", "my.com", "google.com", "googleapis.com", "gstatic.com", "youtube.com",
        "googlevideo.com", "ytimg.com", "yastatic.net", "imgsmail.ru", "cloudflare.com", "github.com",
        "tiktok.com", "yahoo.com", "yahooinc.com", "miui.com")

    fun normalizeDomain(value: String): String? = runCatching {
        val host = IDN.toASCII(value.trim().trimEnd('.')).lowercase(Locale.ROOT)
        require(host.length in 3..253 && host.contains('.') && host.split('.').all {
            it.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it)
        } && !host.all { it.isDigit() || it == '.' })
        host
    }.getOrNull()

    fun parse(text: String, additional: List<String>, allow: List<String>): List<String> {
        val blocked = mutableSetOf<String>()
        val exceptions = allow.mapNotNull(::normalizeDomain).toMutableSet()
        text.lineSequence().map(String::trim).forEach { line ->
            exception.matchEntire(line)?.groupValues?.get(1)?.let { normalizeDomain(it)?.let(exceptions::add) }
            block.matchEntire(line)?.groupValues?.get(1)?.let { normalizeDomain(it)?.let(blocked::add) }
        }
        additional.mapNotNull(::normalizeDomain).forEach(blocked::add)
        // An allowed child must not still match a blocked parent. Drop that parent conservatively.
        (exceptions + protected).forEach { domain ->
            var parent = domain
            while (parent.contains('.')) { blocked.remove(parent); parent = parent.substringAfter('.') }
        }
        return blocked.filter { domain ->
            var parent = domain
            var excluded = false
            while (parent.contains('.')) {
                // Protected roots allow dedicated ad subdomains; explicit exceptions allow descendants too.
                if (parent in exceptions) excluded = true
                parent = parent.substringAfter('.')
            }
            !excluded
        }.sorted()
    }

    /** GeoSiteList -> GeoSite("ADS") -> Domain(RootDomain=2), compatible with Xray's protobuf schema. */
    fun encode(domains: List<String>): ByteArray {
        fun varint(out: ByteArrayOutputStream, number: Int) {
            var n = number
            while (n >= 128) { out.write((n and 127) or 128); n = n ushr 7 }
            out.write(n)
        }
        fun field(out: ByteArrayOutputStream, tag: Int, bytes: ByteArray) {
            out.write(tag); varint(out, bytes.size); out.write(bytes)
        }
        val site = ByteArrayOutputStream()
        field(site, 10, "ADS".toByteArray())
        domains.forEach { domain ->
            val entry = ByteArrayOutputStream()
            entry.write(8); entry.write(2)
            field(entry, 18, domain.toByteArray(Charsets.UTF_8))
            field(site, 18, entry.toByteArray())
        }
        return ByteArrayOutputStream().also { field(it, 10, site.toByteArray()) }.toByteArray()
    }
}
