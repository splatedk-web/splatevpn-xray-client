package com.danieldk.splatevpn.data

enum class AppRoutingMode {
    ALL, INCLUDE, EXCLUDE;

    fun canApply(packages: Set<String>) = this != INCLUDE || packages.isNotEmpty()

    fun usesVpn(packageName: String, packages: Set<String>): Boolean = when (this) {
        ALL -> true
        INCLUDE -> packageName in packages
        EXCLUDE -> packageName !in packages
    }

    companion object {
        fun fromSettings(global: Boolean, mode: String) = when {
            global -> ALL
            mode == SettingsManager.PER_APP_MODE_EXCLUDE -> EXCLUDE
            else -> INCLUDE
        }
    }
}
