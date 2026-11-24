package com.danieldk.splatevpn.vpn

import android.content.Context
import com.danieldk.splatevpn.data.*

internal fun testHealthAdvisor(context: Context, status: VpnStatusManager) =
    TunnelHealthAdvisor(context, DnsProviderChecker(), SettingsManager(context), status,
        javax.inject.Provider { error("Fragmentation probes are not used by core lifecycle tests") })
