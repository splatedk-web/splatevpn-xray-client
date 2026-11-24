package com.danieldk.splatevpn.vpn

import IPtProxy.Controller
import android.content.Context
import java.io.Closeable
import java.io.File

internal class TorTransportInitializationException(cause: Throwable) :
    IllegalStateException("Tor transport library could not initialize its private storage or native controller", cause)

/** IPtProxy has process-global initialization. Runtime/benchmark circuits share listeners, not Tor state. */
internal object TorTransportPool {
    private var controller: Controller? = null
    private val users = mutableMapOf<String, Int>()

    class Lease internal constructor(val ports: Map<String, Int>) : Closeable {
        private var closed = false
        override fun close() = synchronized(TorTransportPool) {
            if (!closed) { closed = true; release(ports.keys) }
        }
    }

    @Synchronized fun acquire(context: Context, names: Set<String>): Lease {
        if (names.isEmpty()) return Lease(emptyMap())
        val current = controller ?: try {
            val directory = File(context.applicationContext.noBackupFilesDir, "tor-transports")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private transport directory" }
            File.createTempFile("transport-", ".check", directory).also {
                check(it.delete()) { "Cannot remove private storage check" }
            }
            Controller(directory.absolutePath, false, false, "ERROR", null).also { controller = it }
        } catch (error: Exception) { throw TorTransportInitializationException(error) }
        val acquired = linkedMapOf<String, Int>()
        try {
            names.forEach { name ->
                if (users.getOrDefault(name, 0) == 0) current.start(name, "")
                val port = current.port(name).toInt()
                require(port in 1..65535) { "Bridge transport did not open its SOCKS port" }
                users[name] = users.getOrDefault(name, 0) + 1
                acquired[name] = port
            }
            return Lease(acquired.toMap())
        } catch (error: Exception) {
            release(acquired.keys)
            names.filter { it !in acquired && users.getOrDefault(it, 0) == 0 }
                .forEach { runCatching { current.stop(it) } }
            throw error
        }
    }

    private fun release(names: Set<String>) {
        names.forEach { name ->
            val remaining = users.getOrDefault(name, 0) - 1
            if (remaining > 0) users[name] = remaining
            else {
                users.remove(name)
                controller?.stop(name)
            }
        }
    }
}
