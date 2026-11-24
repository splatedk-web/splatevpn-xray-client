package com.danieldk.splatevpn.vpn

/** Published only after the core and Android TUN have been configured successfully. */
internal data class ActiveRouting(
    val preferences: Map<String, *>,
    val includedApps: Int?,
    val excludedApps: Int,
    val lanAddress: String? = null,
    val domainRules: String = "[]",
    val assetDirectory: String? = null
)
