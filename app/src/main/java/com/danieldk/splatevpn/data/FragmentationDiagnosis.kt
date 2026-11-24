package com.danieldk.splatevpn.data

internal data class FragmentationProfile(
    val enabled: Boolean = true,
    val length: String = "100-200",
    val interval: String = "10-20",
    val packets: String = "tlshello"
) {
    companion object {
        fun read(settings: SettingsManager) = FragmentationProfile(
            settings.isTlsFragmentEnabled(), settings.getTlsFragmentLength(),
            settings.getTlsFragmentInterval(), settings.getTlsFragmentPackets()
        )
    }
}

/** Compare the same node and endpoints; never blame fragmentation on a single failed request. */
internal suspend fun confirmFragmentationMismatch(
    active: FragmentationProfile,
    current: () -> Boolean,
    measure: suspend (FragmentationProfile) -> Long
): Boolean {
    val standard = FragmentationProfile()
    if (!active.enabled || active == standard || !current()) return false
    repeat(2) {
        if (!current() || measure(active) > 0 || !current()) return false
        if (measure(standard) <= 0 || !current()) return false
    }
    return true
}
