package com.danieldk.splatevpn.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.v2ray.ang.service.TProxyService
import java.net.InetAddress
import java.net.InetSocketAddress

/** Called once per original HEV flow, before the original app's tuple is lost. */
internal class AppFlowIdentityResolver(
    private val context: Context,
    private val access: AppIdentityAccess,
    private val onUnidentifiedFlow: () -> Unit = {}
) : TProxyService.FlowIdentityResolver {
    private val reported = java.util.concurrent.atomic.AtomicBoolean()
    private fun unidentified(): String? {
        if (reported.compareAndSet(false, true)) runCatching(onUnidentifiedFlow)
        return null
    }
    override fun resolve(protocol: Int, localAddress: String, localPort: Int, remoteAddress: String, remotePort: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP) return null
        if (localPort !in 1..65535 || remotePort !in 1..65535) return null
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val uid = connectivity.getConnectionOwnerUid(protocol,
                InetSocketAddress(numeric(localAddress), localPort),
                InetSocketAddress(numeric(remoteAddress), remotePort))
            if (uid < 0) return unidentified()
            // Resolve on every new flow: a UID can be reused after uninstall/reinstall.
            // Keeping a persistent UID→package cache could route another app under old rules.
            val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
            // Known native system services (e.g. Android's DNS resolver) can have a
            // valid reserved UID without an APK. They cannot be a selected package.
            if (packages.isEmpty() && uid in 0 until android.os.Process.FIRST_APPLICATION_UID) return access.fallback.username
            classify(packages, access.packages, access.fallback.username) ?: unidentified()
        } catch (_: Exception) {
            // Never erase a package condition when Android cannot identify a connection.
            unidentified()
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
        fun validatePackages(context: Context, packages: Collection<String>) {
            if (packages.isEmpty()) return
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "Правила «приложение + сайт» требуют Android 10 или новее"
            }
            require(packages.size <= AppIdentityAccess.MAX_PACKAGES) {
                "В составных правилах разрешено не более ${AppIdentityAccess.MAX_PACKAGES} приложений"
            }
            packages.forEach { name ->
                AdvancedRoutingRules.appIdentity(name)
                require(name != context.packageName) { "Сам Splate исключён из VPN; его нельзя выбрать в составном правиле" }
                val app = try { context.packageManager.getApplicationInfo(name, 0) }
                catch (_: PackageManager.NameNotFoundException) { return@forEach }
                val sharing = context.packageManager.getPackagesForUid(app.uid)?.toSet().orEmpty()
                require(sharing == setOf(name)) {
                    "Приложение $name использует общий Android UID с другими пакетами; отдельно сопоставить его нельзя"
                }
            }
        }

        internal fun classify(packages: Set<String>, selected: Set<String>, fallback: String): String? {
            if (packages.isEmpty()) return null
            val targets = packages.intersect(selected)
            if (targets.isEmpty()) return fallback
            // Shared UID packages cannot be attributed reliably to one selected package.
            if (packages.size != 1 || targets.size != 1) return null
            return AdvancedRoutingRules.appIdentity(targets.single())
        }
    }
}
