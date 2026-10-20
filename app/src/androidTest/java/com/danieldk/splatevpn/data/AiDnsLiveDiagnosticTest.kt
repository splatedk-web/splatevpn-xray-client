package com.danieldk.splatevpn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.vpn.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in network diagnostic. Does not apply settings or start/stop the VPN. */
@RunWith(AndroidJUnit4::class)
class AiDnsLiveDiagnosticTest {
    @Test fun geoHideOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAiDns") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logs = AppLogManager(context)
        val core = XrayCoreManager(context, logs, VpnStatusManager(context), LiveConnectionTracker())
        core.ensureAssetsExtracted()
        val selector = AiDnsAutoSelector(context, core, XrayConfigGenerator(), logs)
        val keys = InstrumentationRegistry.getArguments().getString("providers")?.split(',') ?: listOf("geohide")
        for (key in keys) {
        for (fragment in listOf(false, true)) {
            val result = withContext(Dispatchers.IO) {
                selector.probeCandidate(requireNotNull(core.getXrayBinary()), AiDnsChoice(key, fragment), renderGemini = true)
            }
            android.util.Log.i("AiDnsProbe", "LIVE $result")
        }
        }
    }
}
