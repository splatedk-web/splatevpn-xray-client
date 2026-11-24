package com.danieldk.splatevpn.vpn

/** Resolves stale/imported rules before modifying the currently established interface. */
internal data class PerAppRoutingPlan(val allowed: Set<String> = emptySet(), val excluded: Set<String> = emptySet()) {
    companion object {
        fun resolve(global: Boolean, mode: String, packages: Set<String>, ownPackage: String, installed: (String) -> Boolean): PerAppRoutingPlan {
            if (global) return PerAppRoutingPlan(excluded = setOf(ownPackage))
            require(mode == "include" || mode == "exclude") { "Unknown application routing mode" }
            val available = packages.filterTo(linkedSetOf()) { it != ownPackage && installed(it) }
            if (mode == "exclude") return PerAppRoutingPlan(excluded = available + ownPackage)
            require(available.isNotEmpty()) { "No selected VPN applications are installed. Review Applications settings." }
            return PerAppRoutingPlan(allowed = available)
        }
    }
}
