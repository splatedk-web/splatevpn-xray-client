package com.danieldk.splatevpn.data

import java.util.Locale

/**
 * Locality-aware heuristic for server geographic tiering and discovery.
 * Decodes flag emojis, country tags, and domain TLDs to prioritize lower-hop endpoints.
 */
internal object ServerCountryHint {

    private val COMMON_COUNTRY_CODES = setOf(
        "RU", "NL", "DE", "FI", "SE", "US", "GB", "FR", "PL", "KZ", "BY",
        "TR", "UA", "SG", "JP", "HK", "CA", "AT", "CH", "EE", "LV", "LT",
        "CN", "TW", "KR", "IR", "AE", "IN", "AU", "MD", "GE", "AM", "UZ"
    )

    private val REGION_RU_NEARBY = setOf("RU", "KZ", "BY", "FI", "SE", "EE", "LV", "LT")
    private val REGION_RU_EUROPE = setOf("DE", "NL", "PL", "AT", "CH", "FR", "GB", "CZ", "TR", "MD")

    private val REGION_CN_PRIMARY = setOf("CN", "HK", "TW", "MO")
    private val REGION_CN_NEARBY = setOf("JP", "KR", "SG")

    private val REGION_IR_PRIMARY = setOf("IR", "TR", "AE")
    private val REGION_IR_NEARBY = setOf("DE", "NL", "RU", "FR")

    /**
     * Extracts an ISO 3166-1 alpha-2 uppercase country code from server metadata.
     * Checks flag emojis first (exact Unicode Regional Indicator pair), then tag patterns, then TLD.
     */
    fun extractCountry(server: ServerNode): String? {
        val fromFlag = extractFlagEmoji(server.name)
        if (fromFlag != null) return fromFlag

        val fromName = extractCountryTag(server.name)
        if (fromName != null) return fromName

        return extractFromAddress(server.address)
    }

    /**
     * Assigns a locality tier (0 = primary/lowest expected latency, 1 = secondary, 2 = remote).
     * Used to break ties between servers with comparable ping and prioritize initial probing.
     */
    fun geoTier(server: ServerNode, preferredRegion: String, clientCountry: String? = null): Int {
        val country = extractCountry(server) ?: return 1

        val normalizedClient = clientCountry?.uppercase(Locale.ROOT)
        if (normalizedClient != null && country == normalizedClient) return 0

        return when (preferredRegion.lowercase(Locale.ROOT)) {
            "ru" -> when (country) {
                in REGION_RU_NEARBY -> 0
                in REGION_RU_EUROPE -> 1
                else -> 2
            }
            "cn" -> when (country) {
                in REGION_CN_PRIMARY -> 0
                in REGION_CN_NEARBY -> 1
                else -> 2
            }
            "ir" -> when (country) {
                in REGION_IR_PRIMARY -> 0
                in REGION_IR_NEARBY -> 1
                else -> 2
            }
            else -> 1
        }
    }

    private fun extractFlagEmoji(text: String): String? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val nextIndex = i + Character.charCount(cp)
                if (nextIndex < text.length) {
                    val cp2 = text.codePointAt(nextIndex)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val first = ('A'.code + (cp - 0x1F1E6)).toChar()
                        val second = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                        return "$first$second"
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    private val TAG_REGEX = Regex("""(?:^|[\s\[\(_\-])([A-Za-z]{2})(?:[\s\]\)_/\-]|\d|$)""")

    private fun extractCountryTag(name: String): String? {
        for (match in TAG_REGEX.findAll(name)) {
            val code = match.groupValues[1].uppercase(Locale.ROOT)
            if (code in COMMON_COUNTRY_CODES) return code
        }
        return null
    }

    private fun extractFromAddress(address: String): String? {
        val host = address.trim().lowercase(Locale.ROOT).removeSuffix(".")
        val tld = host.substringAfterLast('.', "")
        val code = tld.uppercase(Locale.ROOT)
        return if (code.length == 2 && code in COMMON_COUNTRY_CODES) code else null
    }
}
