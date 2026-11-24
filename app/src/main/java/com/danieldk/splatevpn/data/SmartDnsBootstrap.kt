package com.danieldk.splatevpn.data

import android.content.Context
import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** Refresh only resolver bootstrap addresses. DoH still authenticates the operator hostname with TLS. */
internal object SmartDnsBootstrap {
    private data class Key(val network: Long, val host: String)
    private data class Entry(val addresses: List<String>, val expires: Long)
    private val cache = ConcurrentHashMap<Key, Entry>()
    internal fun publicIpv4(addresses: List<InetAddress>): List<String> = addresses.filterIsInstance<Inet4Address>()
        .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress }
        .filter { val bytes = it.address.map { b -> b.toInt() and 255 }
            bytes[0] !in setOf(0, 127) && bytes[0] < 224 && !(bytes[0] == 198 && bytes[1] in 18..19) &&
                !(bytes[0] == 100 && bytes[1] in 64..127)
        }.map { it.hostAddress!! }.distinct().take(8)

    suspend fun refresh(context: Context, root: JsonObject) = coroutineScope {
        val hosts = root.getAsJsonObject("dns")?.getAsJsonObject("hosts") ?: return@coroutineScope
        val providers = SmartDnsProviders.all.filter { hosts.has(it.host) }
        if (providers.isEmpty()) return@coroutineScope
        val handle = NetworkAccessPolicy.physicalNetworkKey(context)?.takeIf { it > 1 } ?: return@coroutineScope
        val replacements = providers.map { provider -> async {
            val key = Key(handle, provider.host); val now = android.os.SystemClock.elapsedRealtime()
            val stored = cache[key]?.takeIf { it.expires > now }
            val result = stored ?: run {
                val resolved = try {
                    BoundedNetworkTask.run(1500) {
                        val addresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            Network.fromNetworkHandle(handle).getAllByName(provider.host).toList()
                        } else {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                            @Suppress("DEPRECATION")
                            val net = cm?.allNetworks?.firstOrNull { it.networkHandle == handle }
                            (net?.getAllByName(provider.host) ?: InetAddress.getAllByName(provider.host)).toList()
                        }
                        publicIpv4(addresses)
                    }.orEmpty()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
                Entry(resolved, now + if (resolved.isEmpty()) 30_000 else 10 * 60_000).also {
                    if (cache.size > 128) cache.entries.removeIf { item -> item.value.expires <= now }
                    cache[key] = it
                }
            }
            provider.host to result.addresses
        } }.awaitAll()
        // Native config is private to this preparation. Never publish an empty hosts override.
        replacements.forEach { (host, addresses) -> if (addresses.isNotEmpty()) hosts.add(host, JsonArray().apply { addresses.forEach { add(it) } }) }
    }
}
