package com.danieldk.splatevpn.data

enum class BalancerMode { BALANCED, FAILOVER, INITIAL_ONLY }

data class BalancerPreferences(
    val mode: BalancerMode = BalancerMode.BALANCED,
    val healthIntervalSeconds: Int = 2,
    val failureConfirmations: Int = 2,
    val optimizationMinutes: Int = 5,
    val cooldownSeconds: Int = 60,
    val improvementPercent: Int = 30
) {
    val monitorsConnection get() = mode != BalancerMode.INITIAL_ONLY
    val optimizesConnection get() = mode == BalancerMode.BALANCED

    fun validated() = copy(
        healthIntervalSeconds = healthIntervalSeconds.coerceIn(2, 30),
        failureConfirmations = failureConfirmations.coerceIn(2, 5),
        optimizationMinutes = optimizationMinutes.coerceIn(5, 60),
        cooldownSeconds = cooldownSeconds.coerceIn(60, 1800),
        improvementPercent = improvementPercent.coerceIn(30, 80)
    )
}
