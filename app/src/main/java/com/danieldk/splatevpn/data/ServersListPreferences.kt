package com.danieldk.splatevpn.data

data class ServersListPreferences(
    val viewMode: String = "ALL",
    val filterSubId: String? = null,
    val grouped: Boolean = true,
    val expandedGroupIds: Set<String> = emptySet()
)
