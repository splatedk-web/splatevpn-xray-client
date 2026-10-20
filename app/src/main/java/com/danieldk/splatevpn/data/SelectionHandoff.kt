package com.danieldk.splatevpn.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Hand a completed search to concurrent callers, or to an immediately following connection. */
internal class SelectionHandoff<K, T>(
    private val mutex: Mutex,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Completed<K, T>(val generation: Long, val key: K, val value: T?, val time: Long)
    private val generation = AtomicLong()
    private var completed: Completed<K, T>? = null

    suspend fun run(
        key: K,
        reuseRecent: Boolean,
        isValid: suspend (T) -> Boolean,
        search: suspend () -> T?
    ): T? {
        val observed = generation.get()
        return mutex.withLock {
            val previous = completed
            if (previous != null && previous.key == key) {
                val overlapped = previous.generation > observed
                val recentSuccess = reuseRecent && previous.value != null && clock() - previous.time in 0..10_000L
                if ((overlapped || recentSuccess) && (previous.value == null || isValid(previous.value))) {
                    return@withLock previous.value
                }
            }
            // A cancelled owner publishes no result. The next caller can perform its own search.
            val result = search()
            completed = Completed(generation.incrementAndGet(), key, result, clock())
            result
        }
    }
}
