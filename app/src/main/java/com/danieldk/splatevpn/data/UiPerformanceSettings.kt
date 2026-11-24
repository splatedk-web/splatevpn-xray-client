package com.danieldk.splatevpn.data

/** Rendering preferences never alter the selected theme, connection or routing settings. */
data class UiPerformanceSettings(
    val animationsEnabled: Boolean = true,
    val decorativeBackgroundEnabled: Boolean = true,
    val glassHighlightsEnabled: Boolean = true
)
