package com.danieldk.splatevpn.vpn

/** Only observations from Android can establish whether system protection is enabled. */
internal data class VpnProtectionStatus(val alwaysOn: Boolean? = null, val lockdown: Boolean? = null) {
    val level: Level get() = when {
        alwaysOn == true && lockdown == true -> Level.PROTECTED
        alwaysOn == true && lockdown == false -> Level.ALWAYS_ON_ONLY
        alwaysOn == false && lockdown == false -> Level.OFF
        else -> Level.UNKNOWN
    }
    enum class Level { PROTECTED, ALWAYS_ON_ONLY, OFF, UNKNOWN }

    companion object {
        fun fromSystem(alwaysOn: Boolean, lockdown: Boolean, hasInterface: Boolean): VpnProtectionStatus {
            // Some Android versions return false/false until this app owns an active VPN network.
            // Treat that result as unavailable rather than claiming protection is disabled.
            return if (!hasInterface && !alwaysOn && !lockdown) VpnProtectionStatus()
            else VpnProtectionStatus(alwaysOn, lockdown)
        }
    }
}
