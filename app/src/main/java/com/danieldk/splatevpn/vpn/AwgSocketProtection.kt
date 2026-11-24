package com.danieldk.splatevpn.vpn

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** SCM_RIGHTS passes real descriptors; a child-process fd number is never used in the parent. */
internal class AwgSocketProtection(private val service: VpnService, private val bind: (java.io.FileDescriptor) -> Unit) : Closeable {
    private val name = "splate-awg-${UUID.randomUUID()}"
    private val server = LocalServerSocket(name)
    private val clients = java.util.concurrent.CopyOnWriteArraySet<LocalSocket>()
    @Volatile private var closed = false
    private val closeStarted = AtomicBoolean()
    private val closedCompletely = CompletableDeferred<Unit>()
    private val thread = Thread({
        while (!closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            clients.add(socket)
            // accept may finish after close took its client snapshot.
            if (closed) { clients.remove(socket); runCatching { socket.close() }; break }
            try {
                socket.soTimeout = 3000
                socket.use {
                    check(it.peerCredentials.uid == android.os.Process.myUid()) { "Foreign AWG socket" }
                    check(it.inputStream.read() == 1)
                    val descriptors = it.ancillaryFileDescriptors ?: error("Missing AWG socket")
                    try {
                        check(descriptors.size == 1)
                        ParcelFileDescriptor.dup(descriptors.single()).use { fd ->
                            check(service.protect(fd.fd)) { "AWG socket protection failed" }
                            bind(fd.fileDescriptor)
                            check(!closed) { "AWG socket protector is closed" }
                        }
                        it.outputStream.write(1); it.outputStream.flush()
                    } finally { descriptors.forEach { fd -> runCatching { android.system.Os.close(fd) } } }
                }
            } catch (_: Exception) { runCatching { socket.close() } }
            finally { clients.remove(socket) }
        }
    }, "awg-protect").apply { isDaemon = true }
    init { synchronized(lock) { check(path == null); path = name }; thread.start() }
    /** Revoke immediately; socket shutdown and thread joining must never hold the UI thread. */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closed = true
        synchronized(lock) { if (path == name) path = null }
        cleanupScope.launch {
            try {
                // close alone need not wake a Linux thread blocked in accept.
                runCatching { android.system.Os.shutdown(server.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
                try { server.close() } finally { clients.forEach { runCatching { it.close() } } }
                thread.join(3500)
                check(!thread.isAlive) { "AWG socket protector did not stop" }
                closedCompletely.complete(Unit)
            } catch (error: Exception) {
                android.util.Log.w("AwgSocketProtection", "Socket cleanup failed: ${error.javaClass.simpleName}")
                closedCompletely.completeExceptionally(error)
            }
        }
    }
    suspend fun awaitClosed() { close(); closedCompletely.await() }
    companion object {
        private val lock = Any()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile var path: String? = null; private set
    }
}
