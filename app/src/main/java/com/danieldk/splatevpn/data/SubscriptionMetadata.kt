package com.danieldk.splatevpn.data

import com.google.gson.Gson
import java.net.URI
import java.util.Base64

data class SubscriptionMetadata(
    val title: String = "",
    val announcement: String = "",
    val website: String = "",
    val support: String = "",
    val updateHours: Int = 0
) {
    fun encode(): String = Gson().toJson(this)

    fun displayName(current: SubscriptionEntity): String {
        val previous = decode(current.publisherMetadataJson)
        val host = runCatching { URI(LinkParser.normalizeSubscriptionUrl(current.url) ?: current.url).host }.getOrNull()
        val automatic = current.name == current.url || current.name == host ||
            current.name == previous.title || current.name == "Подписка"
        return if (title.isNotBlank() && automatic) title else current.name
    }

    private fun sanitized() = copy(title = clean(title, 160), announcement = clean(announcement, 4096),
        website = link(website), support = link(support), updateHours = updateHours.takeIf { it in 1..168 } ?: 0)

    companion object {
        private fun clean(value: String?, limit: Int): String = value.orEmpty().filter {
            !it.isISOControl() || it == '\n' || it == '\t'
        }.trim().take(limit)

        private fun label(value: String?): String {
            val input = value.orEmpty().trim()
            if (!input.startsWith("base64:", true)) return input
            return runCatching {
                Base64.getDecoder().decode(input.substringAfter(':').filterNot(Char::isWhitespace)
                    .replace('-', '+').replace('_', '/')).toString(Charsets.UTF_8)
            }.getOrDefault("")
        }

        private fun link(value: String?): String {
            val input = clean(value, 2048)
            return runCatching {
                val uri = URI(input)
                input.takeIf { uri.scheme?.lowercase() in setOf("https", "http", "tg") && !uri.host.isNullOrBlank() }
            }.getOrNull().orEmpty()
        }

        fun from(response: SubscriptionResponse): SubscriptionMetadata = SubscriptionMetadata(
            title = label(response.header("profile-title") ?: response.header("subscription-name")).lineSequence().firstOrNull().orEmpty(),
            announcement = label(response.header("announce") ?: response.header("announcement") ?: response.header("profile-description")),
            website = response.header("profile-web-page-url").orEmpty(), support = response.header("support-url").orEmpty(),
            updateHours = response.header("profile-update-interval")?.trim()?.toIntOrNull() ?: 0
        ).sanitized()

        fun decode(json: String?): SubscriptionMetadata = if (json.isNullOrBlank() || json.length > 16384) SubscriptionMetadata()
            else runCatching { Gson().fromJson(json, SubscriptionMetadata::class.java).sanitized() }.getOrDefault(SubscriptionMetadata())
    }
}
