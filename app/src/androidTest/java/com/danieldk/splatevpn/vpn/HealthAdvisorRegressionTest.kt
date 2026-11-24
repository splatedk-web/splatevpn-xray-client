package com.danieldk.splatevpn.vpn

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HealthAdvisorRegressionTest {
    @Test fun neutralConnectivityWarningUsesTheCurrentSessionAndClearsOnlyAfterItsSuccess() = runBlocking {
        withIsolatedAdvisor { _, status, advisor ->
            val server = requireNotNull(status.connectedServerId)
            val session = status.sessionToken
            advisor.reportUnverifiedConnectivity("another-server", session)
            advisor.reportUnverifiedConnectivity(server, session - 1)
            assertNull(advisor.issue.value)

            advisor.reportUnverifiedConnectivity(server, session)
            assertEquals(HealthIssueKind.CONNECTIVITY_UNVERIFIED, advisor.issue.value?.kind)
            assertNull(advisor.issue.value?.primaryActionId)
            assertNull(advisor.issue.value?.primaryActionLabel)
            assertNull(advisor.issue.value?.secondaryActionId)
            advisor.clearUnverifiedConnectivity(server, session - 1)
            assertEquals(HealthIssueKind.CONNECTIVITY_UNVERIFIED, advisor.issue.value?.kind)
            advisor.clearUnverifiedConnectivity(server, session)
            assertNull(advisor.issue.value)
        }
    }

    @Test fun neutralWarningCannotReplaceOrClearASpecificNetworkDiagnosis() = runBlocking {
        withIsolatedAdvisor { _, status, advisor ->
            val server = requireNotNull(status.connectedServerId)
            val session = status.sessionToken
            advisor.reportUnverifiedConnectivity(server, session)
            repeat(3) { advisor.inspectTraffic(0, 0, 0, 0, isPhysicalAlive = false) }
            val specific = advisor.issue.value
            assertEquals(HealthIssueKind.NETWORK_UNAVAILABLE, specific?.kind)
            advisor.reportUnverifiedConnectivity(server, session)
            advisor.clearUnverifiedConnectivity(server, session)
            assertEquals(specific, advisor.issue.value)
        }
    }

    @Test fun aSpecificDiagnosisCanReplaceTheNeutralWarningAndDismissalIsRespected() = runBlocking {
        withIsolatedAdvisor { context, status, advisor ->
            val server = requireNotNull(status.connectedServerId)
            val session = status.sessionToken
            SettingsManager(context).setMtu(1500)
            advisor.reportUnverifiedConnectivity(server, session)
            advisor.inspectXrayLog("transport/internet: message too long")
            assertEquals(HealthIssueKind.MTU_FRAGMENTATION, advisor.issue.value?.kind)
            advisor.clearUnverifiedConnectivity(server, session)
            assertEquals(HealthIssueKind.MTU_FRAGMENTATION, advisor.issue.value?.kind)
            advisor.dismiss()
            advisor.reportUnverifiedConnectivity(server, session)
            assertNull(advisor.issue.value)
        }
    }

    @Test fun failedWebsiteRequestsDoNotClaimGeoBlockingOrOfferDirectRouting() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "health-regression-${UUID.randomUUID()}-"
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(prefix + name, mode).also { preferences += prefix + name }
        }
        val status = VpnStatusManager(context)
        status.connectedServerId = "offline-regression"
        status.setState(VpnState.CONNECTED)
        val advisor = testHealthAdvisor(context, status)
        try {
            // These same lines occur when there is no internet, not just when a website rejects an IP.
            for (domain in listOf("sberbank.ru", "sber.ru", "yandex.ru", "gosuslugi.ru")) {
                repeat(4) {
                    advisor.inspectXrayLog("[proxy] tcp:$domain:443 failed: i/o timeout")
                    advisor.inspectXrayLog("[proxy] tcp:$domain:443 connection reset by peer")
                    advisor.inspectXrayLog("[proxy] tcp:$domain:443 HTTP 403 closed")
                }
            }
            assertNull(advisor.issue.value)
            assertNull(advisor.dnsIssue.value)
            repeat(3) { advisor.inspectTraffic(0, 0, 0, 0, isPhysicalAlive = false) }
            assertEquals(HealthIssueKind.NETWORK_UNAVAILABLE, advisor.issue.value?.kind)
            assertNull(advisor.issue.value?.primaryActionId)
            advisor.inspectXrayLog("[proxy] tcp:sberbank.ru:443 failed: network is unreachable")
            assertEquals(HealthIssueKind.NETWORK_UNAVAILABLE, advisor.issue.value?.kind)
            advisor.inspectTraffic(1024, 0, 1024, 0, isPhysicalAlive = true)
            assertNull(advisor.issue.value)
        } finally {
            status.setState(VpnState.DISCONNECTED)
            advisor.dismiss()
            preferences.forEach { app.deleteSharedPreferences(it) }
        }
    }

    private inline fun withIsolatedAdvisor(block: (android.content.Context, VpnStatusManager, TunnelHealthAdvisor) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "health-connectivity-${UUID.randomUUID()}-"
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(prefix + name, mode).also { preferences += prefix + name }
            override fun getSystemService(name: String): Any? =
                if (name == android.content.Context.CONNECTIVITY_SERVICE || name == android.content.Context.NOTIFICATION_SERVICE) null
                else super.getSystemService(name)
        }
        val status = VpnStatusManager(context)
        status.connectedServerId = "connectivity-regression"
        status.setState(VpnState.CONNECTED)
        SettingsManager(context).setChecksWifiOnly(true)
        val advisor = testHealthAdvisor(context, status)
        try { block(context, status, advisor) }
        finally {
            status.setState(VpnState.DISCONNECTED)
            advisor.dismiss()
            preferences.forEach { app.deleteSharedPreferences(it) }
        }
    }
}
