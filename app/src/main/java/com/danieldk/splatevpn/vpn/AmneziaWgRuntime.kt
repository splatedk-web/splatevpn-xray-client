package com.danieldk.splatevpn.vpn

import android.content.Context
import com.danieldk.splatevpn.data.AmneziaWg
import com.danieldk.splatevpn.data.AmneziaWgProfile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Each isolated probe and main core owns its own AWG process and authenticated listener. */
internal class AmneziaWgRuntime private constructor(val process: Process, val port: Int, val credentials: LocalProxyCredentials) : Closeable {
    override fun close() {
        runCatching { synchronized(process) { process.outputStream.write("STOP\n".toByteArray()); process.outputStream.flush() } }
        if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        check(process.waitFor(1500, TimeUnit.MILLISECONDS)) { "AWG process did not terminate" }
        runCatching { process.outputStream.close(); process.inputStream.close(); process.errorStream.close() }
        sessions.remove(this)
    }
    private fun rebind() { synchronized(process) { process.outputStream.write("REBIND\n".toByteArray()); process.outputStream.flush() } }
    companion object {
        private val sessions = java.util.concurrent.CopyOnWriteArraySet<AmneziaWgRuntime>()
        fun rebindAll() { sessions.forEach { runCatching { it.rebind() }.onFailure { _ -> it.process.destroy() } } }
        internal val runningCount get() = sessions.count { it.process.isAlive }
        suspend fun start(context: Context, source: AmneziaWgProfile): AmneziaWgRuntime {
            var owned: AmneziaWgRuntime? = null
            try {
            withContext(Dispatchers.IO) {
            val profile = source.validated()
            val (host, endpointPort) = AmneziaWg.endpoint(profile.endpoint)
            // Only the encrypted peer endpoint is resolved on the physical network.
            val network = com.danieldk.splatevpn.data.NetworkAccessPolicy.awgUnderlyingNetwork()
            val ips = com.danieldk.splatevpn.data.BoundedNetworkTask.run(3000) {
                if (network != null) network.getAllByName(host) else InetAddress.getAllByName(host)
            } ?: error("AWG endpoint resolution timed out")
            val address = ips.firstOrNull() ?: error("AWG endpoint could not be resolved")
            val endpoint = if (address.address.size == 16) "[${address.hostAddress}]:$endpointPort" else "${address.hostAddress}:$endpointPort"
            val credentials = LocalProxyCredentials.generate()
            val config = JsonObject().apply {
                add("addresses", Gson().toJsonTree(profile.addresses.map { it.substringBefore('/') }))
                add("dns", Gson().toJsonTree(profile.dns.ifEmpty { listOf("1.1.1.1", "2606:4700:4700::1111") }))
                addProperty("mtu", profile.mtu); addProperty("uapi", AmneziaWg.uapi(profile, endpoint))
                addProperty("username", credentials.username); addProperty("password", credentials.password)
                addProperty("protectPath", AwgSocketProtection.path.orEmpty())
            }
            val binary = File(context.applicationInfo.nativeLibraryDir, "libamneziawg.so")
            check(binary.isFile) { "AmneziaWG core is unavailable for this architecture" }
            val builder = ProcessBuilder(binary.absolutePath).directory(context.filesDir)
            builder.environment()["GOMAXPROCS"] = "2"
            val process = builder.start()
            try {
                process.outputStream.write((config.toString() + "\n").toByteArray()); process.outputStream.flush()
                // A blocked native startup cannot outlive the owner or retain its secret input.
                val line = com.danieldk.splatevpn.data.BoundedNetworkTask.run(8000, onCancel = { process.destroyForcibly() }) {
                    process.inputStream.bufferedReader().readLine()
                }
                check(process.isAlive && line != null) { "AmneziaWG startup failed" }
                val ready = JsonParser.parseString(line).asJsonObject
                val port = ready["port"]?.asInt ?: error("AWG listener not ready")
                check(ready["ready"]?.asBoolean == true && port in 1..65535) { "Invalid AWG listener" }
                AmneziaWgRuntime(process, port, credentials).also { sessions.add(it); owned = it }
            } catch (error: Throwable) { process.destroyForcibly(); process.waitFor(1500, TimeUnit.MILLISECONDS); throw error }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(owned)
            } catch (error: Throwable) {
                owned?.close()
                throw error
            }
        }
    }
}

internal class PreparedTransports(val json: String, private val runtimes: List<AmneziaWgRuntime>) : Closeable {
    val alive get() = runtimes.all { it.process.isAlive }
    override fun close() { var failure: Exception? = null; runtimes.asReversed().forEach { try { it.close() } catch (e: Exception) { failure = e } }; failure?.let { throw it } }
    companion object {
        suspend fun prepare(context: Context, json: String): PreparedTransports {
            val root = JsonParser.parseString(json).asJsonObject
            com.danieldk.splatevpn.data.SmartDnsBootstrap.refresh(context, root)
            val runtimes = mutableListOf<AmneziaWgRuntime>()
            try {
                val byProfile = mutableMapOf<AmneziaWgProfile, AmneziaWgRuntime>()
                root.getAsJsonArray("outbounds")?.forEach { element ->
                    val outbound = element.asJsonObject
                    if (outbound["protocol"]?.asString == "splate-amneziawg") {
                        val profile = Gson().fromJson(outbound["awg"], AmneziaWgProfile::class.java).validated()
                        val runtime = byProfile[profile] ?: run {
                            require(byProfile.size < 4) { "At most four simultaneous AWG routes are supported" }
                            AmneziaWgRuntime.start(context, profile).also { runtimes.add(it); byProfile[profile] = it }
                        }
                        val tag = outbound["tag"].asString
                        outbound.entrySet().map { it.key }.forEach { outbound.remove(it) }
                        outbound.addProperty("tag", tag); outbound.addProperty("protocol", "socks")
                        outbound.add("settings", JsonParser.parseString("""{"servers":[{"address":"127.0.0.1","port":${runtime.port},"users":[{"user":"${runtime.credentials.username}","pass":"${runtime.credentials.password}"}]}]}"""))
                    }
                }
                return PreparedTransports(root.toString(), runtimes)
            } catch (error: Throwable) { runtimes.asReversed().forEach { runCatching { it.close() } }; throw error }
        }
    }
}
