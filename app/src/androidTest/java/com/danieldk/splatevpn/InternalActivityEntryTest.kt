package com.danieldk.splatevpn

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class InternalActivityEntryTest {
    @Test fun onlyTheNonExportedAliasIsRecognizedAsAnInternalEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = (context.applicationContext as SplateApplication).settingsManager
        settings.completeOnboarding("en", "ru", "global", false)
        settings.setAutoUpdateInterval("off")
        settings.setAutoConnectOnBoot(false)
        val internal = InternalActivityEntry.intent(context)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(internal.component!!, 0)
        assertFalse(info.exported)
        assertFalse(InternalActivityEntry.isInternal(context,
            Intent(context, MainActivity::class.java).putExtra("AUTO_CONNECT", true)))
        ActivityScenario.launch<MainActivity>(internal).use { scenario ->
            scenario.onActivity { assertTrue(InternalActivityEntry.isInternal(it, it.intent)) }
        }
    }
}
