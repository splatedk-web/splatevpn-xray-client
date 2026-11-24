package com.danieldk.splatevpn.vpn

/** Consecutive traffic failures belong to one physical network, never to a handover. */
internal class TorTrafficHealthCounter<N : Any> {
    private var network: N? = null
    private var failures = 0
    fun reset() { network = null; failures = 0 }
    fun record(before: N?, after: N?, healthy: Boolean): Boolean {
        if (before == null || before != after) { reset(); return false }
        if (before != network) { network = before; failures = 0 }
        failures = if (healthy) 0 else failures + 1
        return failures >= 3
    }
}
