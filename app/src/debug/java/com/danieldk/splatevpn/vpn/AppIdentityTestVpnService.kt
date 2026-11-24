package com.danieldk.splatevpn.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService

/** Real Android VPN permission/UID test fixture; only the reserved benchmark range is routed. */
class AppIdentityTestVpnService : VpnService() {
    inner class LocalBinder : Binder() { val service get() = this@AppIdentityTestVpnService }
    private var tunnel: ParcelFileDescriptor? = null
    private var native: TProxyService? = null
    val flows = java.util.concurrent.ConcurrentLinkedQueue<String>()

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else LocalBinder()

    fun startTunnel(config: String, access: AppIdentityAccess) {
        stopTunnel()
        val fd = requireNotNull(Builder().setSession("Splate UID instrumentation")
            .addAddress("172.19.0.2", 30).addRoute("198.18.0.0", 15)
            .addAllowedApplication(packageName).addAllowedApplication("$packageName.test")
            .setMtu(1500).establish()) { "Test VPN permission is missing" }
        tunnel = fd
        val resolver = AppFlowIdentityResolver(this, access)
        flows.clear()
        native = TProxyService(fd).also { service -> service.start(config,
            TProxyService.FlowIdentityResolver { protocol, local, localPort, remote, remotePort ->
                val user = resolver.resolve(protocol, local, localPort, remote, remotePort)
                flows += "$protocol $local:$localPort -> $remote:$remotePort = $user"
                user
            }) }
    }

    fun stopTunnel() {
        native?.stop(); native = null
        tunnel?.close(); tunnel = null
    }

    override fun onRevoke() { stopTunnel(); super.onRevoke() }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }
}
