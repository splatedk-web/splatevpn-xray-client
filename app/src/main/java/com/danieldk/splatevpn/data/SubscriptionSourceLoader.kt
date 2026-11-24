package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLDecoder

/** Only the public Igareck CIDR source has an implicit mirror. Private URLs stay private. */
internal fun subscriptionSourceCandidates(primaryUrl: String): List<String> {
    fun knownPublicBlack(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null) return@runCatching false
        when (uri.host?.lowercase()) {
            "raw.githubusercontent.com" -> uri.rawPath in setOf(
                "/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
                "/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt"
            )
            "github.com" -> uri.rawPath in setOf(
                "/igareck/vpn-configs-for-russia/raw/main/BLACK_VLESS_RUS.txt",
                "/igareck/vpn-configs-for-russia/raw/refs/heads/main/BLACK_VLESS_RUS.txt"
            )
            "gitlab.com" -> uri.rawPath in setOf(
                "/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS.txt",
                "/igareck/vpn-configs-for-russia/raw/main/BLACK_VLESS_RUS.txt"
            )
            else -> false
        }
    }.getOrDefault(false)

    if (knownPublicBlack(primaryUrl)) {
        val fallback = if (primaryUrl == SplateConstants.PRESET_SUB_BLACK_FALLBACK_URL)
            SplateConstants.PRESET_SUB_BLACK_URL else SplateConstants.PRESET_SUB_BLACK_FALLBACK_URL
        return listOf(primaryUrl, fallback).distinct()
    }

    fun knownPublicCidr(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null) return@runCatching false
        when (uri.host?.lowercase()) {
            "white.igareck.net" -> uri.rawPath == "/WHITE-CIDR-RU-all.txt"
            "raw.githubusercontent.com" -> uri.rawPath in setOf(
                "/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
                "/igareck/vpn-configs-for-russia/refs/heads/main/WHITE-CIDR-RU-all.txt")
            "github.com" -> uri.rawPath == "/igareck/vpn-configs-for-russia/raw/refs/heads/main/WHITE-CIDR-RU-all.txt"
            else -> false
        }
    }.getOrDefault(false)

    val known = knownPublicCidr(primaryUrl) || runCatching {
        val uri = URI(primaryUrl)
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawFragment != null ||
            uri.host?.lowercase() !in setOf("translate.yandex.ru", "translate.yandex.com") ||
            uri.rawPath != "/translate") return@runCatching false
        val targets = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            if (key != "url" || '=' !in part) null else URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
        targets.size == 1 && knownPublicCidr(targets.single())
    }.getOrDefault(false)
    return if (known) listOf(primaryUrl, SplateConstants.PRESET_SUB_CIDR_FALLBACK_URL).distinct()
        else listOf(primaryUrl)
}

internal data class LoadedSubscription<T>(
    val response: SubscriptionResponse,
    val nodes: List<T>,
    val usedFallback: Boolean
)

/** Import and refresh accept an endpoint only after its response yields valid nodes. */
internal class SubscriptionSourceLoader(
    private val fetch: suspend (url: String, userAgent: String, viaVpn: Boolean) -> SubscriptionResponse,
    private val deviceBound: Boolean = false,
    private val userAgent: SubscriptionUserAgent = SubscriptionUserAgent.AUTO,
    private val candidates: (String) -> List<String> = ::subscriptionSourceCandidates
) {
    suspend fun <T> load(
        primaryUrl: String,
        viaVpn: Boolean,
        parse: (SubscriptionResponse) -> List<T>
    ): LoadedSubscription<T> {
        val agents = userAgent.header?.let(::listOf) ?: if (deviceBound) {
            listOf(requireNotNull(SubscriptionUserAgent.SPLATE.header))
        } else {
            listOf(requireNotNull(SubscriptionUserAgent.V2RAY.header), requireNotNull(SubscriptionUserAgent.CLASH.header))
        }
        var lastFailure: Exception? = null
        for ((index, url) in candidates(primaryUrl).withIndex()) {
            var retryWithNextAgent = true
            for (userAgent in agents) {
                if (!retryWithNextAgent) break
                try {
                    val response = fetch(url, userAgent, viaVpn)
                    response.checkHwidAccess()
                    // Preserve the compatibility retry for 4xx and valid but unreadable bodies.
                    retryWithNextAgent = response.isSuccessful || response.code in 400..499
                    if (!response.isSuccessful) throw SubscriptionHttpException(response.code)
                    require(response.content.isNotBlank()) { "Пустой ответ от сервера подписки" }
                    val nodes = parse(response)
                    require(nodes.size <= SubscriptionTransport.MAX_NODES) { "Too many subscription nodes" }
                    require(nodes.isNotEmpty()) { "В подписке не найдено подходящих узлов" }
                    return LoadedSubscription(response, nodes, usedFallback = index > 0)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SubscriptionHwidException) {
                    // Retrying a different client or mirror must not evade the panel's device policy.
                    throw error
                } catch (error: Exception) {
                    lastFailure = error
                    // Repeating the same failed network request under a different UA does not
                    // help; move to the explicitly allowlisted mirror with the same route.
                    if (error !is IllegalArgumentException && error !is SubscriptionHttpException) break
                }
            }
        }
        throw lastFailure ?: IllegalArgumentException("У подписки нет адреса загрузки")
    }
}
