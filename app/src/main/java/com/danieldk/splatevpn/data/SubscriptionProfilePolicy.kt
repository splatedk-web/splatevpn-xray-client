package com.danieldk.splatevpn.data

import java.util.Locale

internal class SubscriptionPlaceholderException : IllegalArgumentException(
    "Источник вернул служебные заглушки вместо рабочих конфигураций. Проверьте новую ссылку у автора подписки."
)

/** Some publishers encode an outage or a migration notice as a syntactically valid VLESS URI. */
internal fun isSubscriptionPlaceholder(node: ServerNode): Boolean {
    val name = node.name.lowercase(Locale.ROOT)
    return listOf("подписка не работает", "подписка переехала", "новые подписки в @",
        "subscription is not working", "subscription not working", "subscription has moved",
        "subscription expired").any(name::contains)
}

internal fun parseUsableSubscription(content: String, id: String, name: String): List<ServerNode> {
    val parsed = LinkParser.parseSubscription(content, id, name)
    val usable = parsed.filterNot(::isSubscriptionPlaceholder)
    if (parsed.isNotEmpty() && usable.isEmpty()) throw SubscriptionPlaceholderException()
    return usable
}
