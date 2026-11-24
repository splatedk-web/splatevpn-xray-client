package com.danieldk.splatevpn.data

/** App choices are separate from Android's permission and notification-channel settings. */
data class NotificationPreferences(
    val healthAlerts: Boolean = true,
    val connectionIssues: Boolean = true,
    val dnsIssues: Boolean = false,
    val accessIssues: Boolean = true,
    val deviceIssues: Boolean = true,
    val showSpeed: Boolean = true,
    val showServer: Boolean = true,
    val showDuration: Boolean = true
) {
    fun allows(kind: HealthIssueKind): Boolean = healthAlerts && when (kind) {
        HealthIssueKind.NETWORK_UNAVAILABLE, HealthIssueKind.CONNECTIVITY_UNVERIFIED, HealthIssueKind.SILENT_BLACKHOLE -> connectionIssues
        HealthIssueKind.DNS_BLOCKED -> dnsIssues
        HealthIssueKind.REALITY_TLS_FAILURE, HealthIssueKind.MTU_FRAGMENTATION, HealthIssueKind.TLS_FRAGMENTATION, HealthIssueKind.GEO_BLOCKED_DOMAIN -> accessIssues
        HealthIssueKind.CAPTIVE_PORTAL, HealthIssueKind.TIME_DESYNC, HealthIssueKind.ROUTING_LOOP -> deviceIssues
    }
    fun values(): Map<String, Boolean> = listOf(healthAlerts, connectionIssues, dnsIssues, accessIssues, deviceIssues, showSpeed, showServer, showDuration)
        .mapIndexed { index, value -> keys[index] to value }.toMap()
    companion object {
        val keys = listOf("notification_health", "notification_connection", "notification_dns", "notification_access", "notification_device", "notification_speed", "notification_server", "notification_duration")
        fun read(get: (String, Boolean) -> Boolean): NotificationPreferences {
            val defaults = NotificationPreferences().values()
            val v = keys.map { get(it, defaults.getValue(it)) }
            return NotificationPreferences(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
        }
    }
}
