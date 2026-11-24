package com.danieldk.splatevpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import com.v2ray.ang.service.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enforces Split Tunneling UID isolation directly inside hev-socks5-tunnel on Android 10+.
 * Blocks apps excluded from the tunnel (such as RKNHardening) that attempt to bypass
 * Split Tunneling via explicit ConnectivityManager VPN Network socket binding.
 */
internal class SplitTunnelFlowGuard(
    private val context: Context,
    private val plan: PerAppRoutingPlan,
    private val allowedUsername: String,
    private val delegate: TProxyService.FlowIdentityResolver? = null,
    private val onBlockedFlow: (String) -> Unit = {}
) : TProxyService.FlowIdentityResolver {

    private val reportedBlocked = AtomicBoolean()

    override fun resolve(
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return delegate?.resolve(protocol, localAddress, localPort, remoteAddress, remotePort) ?: allowedUsername
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) return null
        if (localPort !in 1..65535 || remotePort !in 1..65535) return null
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val uid = connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(numeric(localAddress), localPort),
                InetSocketAddress(numeric(remoteAddress), remotePort)
            )
            val packages = if (uid >= Process.FIRST_APPLICATION_UID) {
                context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
            } else {
                emptySet()
            }
            if (!isFlowAllowed(uid, packages, remotePort, plan)) {
                if (reportedBlocked.compareAndSet(false, true)) {
                    val label = packages.firstOrNull() ?: "uid=$uid"
                    runCatching { onBlockedFlow(label) }
                }
                return null
            }
            delegate?.resolve(protocol, localAddress, localPort, remoteAddress, remotePort) ?: allowedUsername
        } catch (_: Exception) {
            // Never allow an unverified flow to bypass Split Tunneling isolation.
            null
        }
    }

    private fun numeric(address: String): InetAddress {
        require(address.isNotEmpty() && address.all { it in "0123456789abcdefABCDEF.:" })
        require(':' in address || address.split('.').let { octets ->
            octets.size == 4 && octets.all { val octet = it.toIntOrNull(); octet != null && octet in 0..255 }
        })
        return InetAddress.getByName(address)
    }

    companion object {
        private const val DNS_PORT = 53
        internal const val FIRST_APP_UID = 10000

        fun shouldActivate(plan: PerAppRoutingPlan, ownPackage: String): Boolean =
            plan.allowed.isNotEmpty() || plan.excluded.any { it != ownPackage }

        internal fun isFlowAllowed(
            uid: Int,
            packages: Set<String>,
            remotePort: Int,
            plan: PerAppRoutingPlan,
            firstAppUid: Int = FIRST_APP_UID
        ): Boolean {
            if (uid < 0) return remotePort == DNS_PORT
            if (uid < firstAppUid) {
                if (remotePort == DNS_PORT) return true
                return plan.allowed.isEmpty()
            }
            return if (plan.allowed.isNotEmpty()) {
                packages.isNotEmpty() && packages.any { it in plan.allowed }
            } else {
                packages.none { it in plan.excluded }
            }
        }
    }
}
