package com.danieldk.splatevpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A denied check is not evidence that a server failed. Keep this distinct from network failures. */
class WifiRequiredException : IOException("Wi-Fi is required by your network settings / В настройках выбран доступ только по Wi-Fi")

internal data class NetworkRouteState(
    val internet: Boolean,
    val wifi: Boolean,
    val cellular: Boolean = false,
    val vpn: Boolean = false,
    val suspended: Boolean = false,
    val unmetered: Boolean = false
)

/** Only the actual default route counts. An unrelated connected Wi-Fi never authorizes mobile traffic. */
internal fun wifiRouteAllowed(default: NetworkRouteState?, vpnUnderlying: NetworkRouteState? = null): Boolean {
    fun wifi(route: NetworkRouteState) = route.internet && route.wifi && !route.cellular && !route.suspended
    return default != null && wifi(default) && (vpnUnderlying == null || wifi(vpnUnderlying))
}

private class WifiPolicyCancellation : CancellationException("Wi-Fi-only operation paused")

/** Consuming the body stays inside the call's cancellation lifetime, including a stalled download. */
internal suspend fun <T> Call.awaitConsumedResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use(read)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

/** Cancels sockets/process probes on route loss before their result can be recorded as a failure. */
internal suspend fun <T> runWithNetworkPermission(allowed: () -> Boolean, block: suspend () -> T): T = coroutineScope {
    if (!allowed()) throw WifiRequiredException()
    val operation = async { block() }
    val watcher = launch {
        while (operation.isActive) {
            delay(200)
            if (!allowed()) {
                operation.cancel(WifiPolicyCancellation())
                break
            }
        }
    }
    try {
        operation.await().also { if (!allowed()) throw WifiRequiredException() }
    } catch (_: WifiPolicyCancellation) {
        throw WifiRequiredException()
    } finally {
        watcher.cancel()
    }
}

object NetworkAccessPolicy {
    const val UPDATES_WIFI_ONLY = "updates_wifi_only"
    const val CHECKS_WIFI_ONLY = "checks_wifi_only"
    @Volatile private var vpnUnderlyingNetwork: Network? = null

    /** Called by our VPN when its real upstream changes, including on older Android releases. */
    internal fun awgUnderlyingNetwork(): Network? = vpnUnderlyingNetwork

    fun setVpnUnderlyingNetwork(network: Network?) { vpnUnderlyingNetwork = network }

    internal fun physicalNetworkKey(context: Context): Long? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        fun isValid(net: Network?): Boolean {
            if (net == null) return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        fun isPhysical(net: Network?): Boolean {
            if (net == null) return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val explicit = vpnUnderlyingNetwork?.takeIf(::isValid) ?: vpnUnderlyingNetwork?.takeIf(::isPhysical)
        val active = cm.activeNetwork?.takeIf(::isValid)
        val candidate = explicit ?: active ?: cm.allNetworks.firstOrNull(::isValid) ?: cm.allNetworks.firstOrNull(::isPhysical) ?: return 1L
        candidate.networkHandle
    }.getOrDefault(1L)

    private fun snapshot(cm: ConnectivityManager, network: Network?): NetworkRouteState? {
        val caps = network?.let(cm::getNetworkCapabilities) ?: return null
        return NetworkRouteState(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            suspended = Build.VERSION.SDK_INT >= 28 && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }

    fun isWifiDefault(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
        val underlying = vpnUnderlyingNetwork
        val physical = underlying?.let { snapshot(cm, it) ?: return@runCatching false }
        // Android VPN capabilities include their underlying transport types. A VPN advertising
        // cellular as well as Wi-Fi is ambiguous, so the Wi-Fi-only option fails closed.
        wifiRouteAllowed(snapshot(cm, cm.activeNetwork), physical)
    }.getOrDefault(false)

    fun isDefaultUnmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@runCatching false
        val active = snapshot(cm, cm.activeNetwork) ?: return@runCatching false
        val underlying = vpnUnderlyingNetwork?.let { snapshot(cm, it) ?: return@runCatching false }
        active.internet && !active.suspended && active.unmetered &&
            (underlying == null || underlying.internet && !underlying.suspended && underlying.unmetered)
    }.getOrDefault(false)

    private fun allowed(context: Context, key: String) =
        !context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE).getBoolean(key, false) || isWifiDefault(context)

    fun updatesAllowed(context: Context): Boolean = allowed(context, UPDATES_WIFI_ONLY)
    fun checksAllowed(context: Context): Boolean = allowed(context, CHECKS_WIFI_ONLY)
    fun requireUpdates(context: Context) { if (!updatesAllowed(context)) throw WifiRequiredException() }
    fun requireChecks(context: Context) { if (!checksAllowed(context)) throw WifiRequiredException() }
    suspend fun <T> withUpdates(context: Context, block: suspend () -> T): T =
        runWithNetworkPermission({ updatesAllowed(context) }, block)
    suspend fun <T> withChecks(context: Context, block: suspend () -> T): T =
        runWithNetworkPermission({ checksAllowed(context) }, block)
}
