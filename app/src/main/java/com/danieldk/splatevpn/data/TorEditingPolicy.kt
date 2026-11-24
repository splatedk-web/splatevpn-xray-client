package com.danieldk.splatevpn.data

/** Preparing inactive Tor settings must not require stopping an ordinary VPN. */
internal fun canEditTorSettings(connectionActive: Boolean, currentTorEnabled: Boolean, requestedTorEnabled: Boolean): Boolean =
    !connectionActive || !currentTorEnabled && !requestedTorEnabled
