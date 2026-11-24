package com.danieldk.splatevpn.vpn

/** A brief successful restart must not turn a crash loop into unlimited retries. */
internal class CoreRestartBudget(
    private val stableMs: Long = 60_000,
    val maxRetries: Int = 5,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    var failures = 0
        private set
    private var runningSince: Long? = null

    fun started() { runningSince = nowMs() }

    fun crashed(): Boolean {
        val since = runningSince
        if (since != null && nowMs() - since >= stableMs) failures = 0
        runningSince = null
        failures++
        return failures <= maxRetries
    }

    fun isExhausted(): Boolean = failures >= maxRetries

    fun reset() {
        failures = 0
        runningSince = null
    }
}
