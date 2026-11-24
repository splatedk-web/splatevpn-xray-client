package com.danieldk.splatevpn.vpn

internal data class PhysicalNetworkCandidate<T>(
    val network: T,
    val internet: Boolean,
    val unrestricted: Boolean,
    val notVpn: Boolean,
    val validated: Boolean,
    val wifi: Boolean,
    val blocked: Boolean = false
)

/** Keep a usable network stable; an unvalidated Wi-Fi must not outrank working mobile data. */
internal fun <T> selectPhysicalNetwork(candidates: List<PhysicalNetworkCandidate<T>>, preferred: T?): T? =
    candidates.filter { it.internet && it.unrestricted && it.notVpn && !it.blocked }
        .maxWithOrNull(compareBy<PhysicalNetworkCandidate<T>> { it.validated }
            .thenBy { it.wifi }
            .thenBy { it.network == preferred })?.network
