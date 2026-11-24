package com.danieldk.splatevpn.data

enum class BalancerMode { BALANCED, FAILOVER, INITIAL_ONLY }
enum class BalancerMetric { LATENCY, DOWNLOAD }
enum class BalancerGoal { GENERAL, TELEGRAM, YOUTUBE, TIKTOK, YOUTUBE_RU, INSTAGRAM, DISCORD, WHATSAPP, TWITCH }

data class BalancerPreferences(
    val mode: BalancerMode = BalancerMode.BALANCED,
    val healthIntervalSeconds: Int = 10,
    val failureConfirmations: Int = 3,
    val optimizationMinutes: Int = 5,
    val cooldownSeconds: Int = 60,
    val improvementPercent: Int = 30,
    val metric: BalancerMetric = BalancerMetric.LATENCY,
    val goal: BalancerGoal = BalancerGoal.GENERAL,
    val warmStandby: Boolean = true,
    val speedBudgetMiB: Int = 64
) {
    val monitorsConnection get() = mode != BalancerMode.INITIAL_ONLY
    val optimizesConnection get() = mode == BalancerMode.BALANCED
    val maintainsStandby get() = warmStandby && monitorsConnection
    val needsServiceQualification get() = goal != BalancerGoal.GENERAL

    fun validated() = copy(
        healthIntervalSeconds = healthIntervalSeconds.coerceIn(2, 60),
        failureConfirmations = failureConfirmations.coerceIn(2, 5),
        optimizationMinutes = optimizationMinutes.coerceIn(5, 60),
        cooldownSeconds = cooldownSeconds.coerceIn(60, 1800),
        improvementPercent = improvementPercent.coerceIn(30, 80),
        speedBudgetMiB = speedBudgetMiB.coerceIn(16, 256)
    )
}
