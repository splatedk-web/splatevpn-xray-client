package com.danieldk.splatevpn.data

internal sealed interface LogWrite<out T> {
    data class Append<T>(val entry: T) : LogWrite<T>
    data object Clear : LogWrite<Nothing>
}

/** Clearing must survive a full queue and precede every subsequent append. */
internal class LogWriteQueue<T>(private val capacity: Int) {
    private val entries = ArrayDeque<T>()
    private var clearPending = false

    init { require(capacity > 0) }

    @Synchronized fun append(entry: T): Boolean {
        if (entries.size >= capacity) return false
        entries.addLast(entry)
        return true
    }

    @Synchronized fun clear() {
        entries.clear()
        clearPending = true
    }

    @Synchronized fun poll(): LogWrite<T>? {
        if (clearPending) {
            clearPending = false
            return LogWrite.Clear
        }
        return entries.removeFirstOrNull()?.let { LogWrite.Append(it) }
    }
}
