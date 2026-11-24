package com.danieldk.splatevpn.data

import android.content.ContextWrapper
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.VpnStatusManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotificationPreferencesAndroidTest {
    @Test fun preferencesPersistAndDisabledCategoriesDoNotPostOrKeepSystemAlerts() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "notification_test_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val settings = SettingsManager(context)
        val nm = app.getSystemService(NotificationManager::class.java)
        val advisor = TunnelHealthAdvisor(context, DnsProviderChecker(), settings, VpnStatusManager(context), javax.inject.Provider { error("No network probe needed") })
        val post = TunnelHealthAdvisor::class.java.getDeclaredMethod("setIssue", TunnelHealthIssue::class.java).apply { isAccessible = true }
        val directPost = TunnelHealthAdvisor::class.java.getDeclaredMethod("postIssueNotification", TunnelHealthIssue::class.java).apply { isAccessible = true }
        fun present() = nm.activeNotifications.any { it.id == TunnelHealthAdvisor.NOTIFICATION_ID_HEALTH_ISSUE }
        try {
            delay(100)
            val issue = TunnelHealthIssue(HealthIssueKind.NETWORK_UNAVAILABLE,"Splate notification test","Local diagnostic fixture")
            post.invoke(advisor, issue)
            withTimeout(3000) { while (!present()) delay(25) }
            settings.setNotificationPreferences(settings.getNotificationPreferences().copy(connectionIssues = false))
            withTimeout(3000) { while (present()) delay(25) }
            assertEquals(issue, advisor.issue.value)
            directPost.invoke(advisor, issue); delay(100); assertFalse(present())
            directPost.invoke(advisor, issue.copy(kind = HealthIssueKind.DNS_BLOCKED)); delay(100); assertFalse(present())
            settings.setNotificationPreferences(NotificationPreferences(healthAlerts = false, dnsIssues = true, showServer = false, showSpeed = false, showDuration = false))
            assertEquals(settings.getNotificationPreferences(), SettingsManager(context).getNotificationPreferences())
            directPost.invoke(advisor, issue.copy(kind = HealthIssueKind.DNS_BLOCKED)); delay(100); assertFalse(present())
        } finally { advisor.dismiss(); app.deleteSharedPreferences(prefix + "splate_prefs") }
    }
}
