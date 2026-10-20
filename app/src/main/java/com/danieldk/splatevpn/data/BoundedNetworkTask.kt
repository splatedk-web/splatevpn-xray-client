package com.danieldk.splatevpn.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Socket timeouts do not cover DNS resolution or executor queue time. */
internal object BoundedNetworkTask {
    private val workers = Executors.newFixedThreadPool(32) { task ->
        Thread(task, "splate-socket-probe").apply { isDaemon = true }
    }
    suspend fun <T> run(timeoutMs: Int, onCancel: () -> Unit = {}, block: () -> T): T? =
        withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine { continuation ->
                val future = workers.submit {
                    try {
                        if (continuation.isActive) {
                            val value = block()
                            if (continuation.isActive) continuation.resume(value)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
                continuation.invokeOnCancellation {
                    future.cancel(true)
                    try { onCancel() } catch (_: Exception) { }
                }
            }
        }
}
