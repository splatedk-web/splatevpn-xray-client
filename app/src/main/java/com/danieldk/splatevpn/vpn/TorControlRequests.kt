package com.danieldk.splatevpn.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * jtorctl wraps an interrupted waiter in IOException, losing coroutine cancellation semantics.
 * Keep that blocking worker separate from the caller so a cancelled control request can never
 * escape as an ordinary I/O failure. Cancellation also closes this request's socket immediately.
 */
internal class TorControlRequests(
    private val workerScope: CoroutineScope,
    private val timeoutMillis: Long = 5_000
) {
    suspend fun <T> execute(socket: Socket?, request: () -> T): T = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val worker = workerScope.launch(Dispatchers.IO) {
                try {
                    val result = runInterruptible(Dispatchers.IO) { request() }
                    continuation.resume(result)
                } catch (error: Throwable) {
                    // An interrupted jtorctl waiter may throw IOException after the caller has
                    // already cancelled. Its exception must not replace the cancellation cause.
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation {
                // Capture the socket, rather than closing a later connection after a restart.
                runCatching { socket?.close() }
                worker.cancel()
            }
        }
    }
}
