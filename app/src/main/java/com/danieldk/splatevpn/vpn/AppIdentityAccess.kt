package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** A private TUN listener, never published as the user's Mixed/LAN proxy. */
class AppIdentityAccess(
    val port: Int,
    val packages: Set<String>,
    val fallback: LocalProxyCredentials
) {
    init {
        require(port in 1..65535)
        require(packages.isNotEmpty())
        require(packages.size <= MAX_PACKAGES) { "Слишком много приложений в составных правилах (максимум $MAX_PACKAGES)" }
        packages.forEach(AdvancedRoutingRules::appIdentity)
    }

    val usernames: Set<String> = packages.mapTo(linkedSetOf(), AdvancedRoutingRules::appIdentity)

    init { require(fallback.username !in usernames) { "Fallback identity must differ from every application identity" } }

    internal fun settings() = JsonObject().apply {
        addProperty("auth", "password")
        addProperty("udp", true)
        addProperty("ip", "127.0.0.1")
        add("accounts", JsonArray().apply {
            (usernames + fallback.username).forEach { name -> add(JsonObject().apply {
                addProperty("user", name); addProperty("pass", fallback.password)
            }) }
        })
    }

    override fun toString() = "AppIdentityAccess([private])"

    companion object {
        const val INBOUND_TAG = "app-tun-in"
        const val MAX_PACKAGES = AdvancedRoutingRules.MAX_PACKAGES
    }
}
