package com.danieldk.splatevpn.data

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Measures TCP reachability only; protocol authentication belongs to the Xray URL test. */
internal object TcpProbe {
    suspend fun measure(host: String, port: Int, timeoutMs: Int): Long {
        val socketRef = AtomicReference<Socket?>()
        return BoundedNetworkTask.run(timeoutMs, onCancel = { socketRef.getAndSet(null)?.close() }) {
            val started = System.nanoTime()
            try {
                Socket().also { socketRef.set(it) }.use {
                    it.connect(InetSocketAddress(host, port), timeoutMs)
                }
                ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1L)
            } catch (_: Exception) {
                -1L
            } finally {
                try { socketRef.getAndSet(null)?.close() } catch (_: Exception) { }
            }
        } ?: -1L
    }
}
