package com.danieldk.splatevpn.data

/** Immutable transport snapshot: provider tests must use the same profile/options as the VPN. */
data class AiDnsVpnRoute(
    val node: ServerNode,
    val xhttpMode: String = "auto",
    val tlsFragment: Boolean = false,
    val tlsFragmentLength: String = "100-200",
    val tlsFragmentInterval: String = "10-20",
    val tlsFragmentPackets: String = "tlshello",
    val tlsMixedSni: Boolean = false,
    val mux: Boolean = false,
    val muxConcurrency: Int = 8
) {
    override fun toString() = "AiDnsVpnRoute([hidden])"

    companion object {
        fun capture(node: ServerNode, settings: SettingsManager, requiresTlsFragment: Boolean = false) = AiDnsVpnRoute(QuickServerSelection.profile(node),
            xhttpMode = settings.getXhttpMode(), tlsFragment = settings.isTlsFragmentEnabled() || requiresTlsFragment,
            tlsFragmentLength = settings.getTlsFragmentLength(), tlsFragmentInterval = settings.getTlsFragmentInterval(),
            tlsFragmentPackets = settings.getTlsFragmentPackets(), tlsMixedSni = settings.isTlsMixedSniEnabled(), mux = settings.isMuxEnabled(),
            muxConcurrency = settings.getMuxConcurrency())
    }
}
