package com.danieldk.splatevpn.vpn

import android.content.Intent
import android.service.quicksettings.TileService
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.MainActivity
import org.junit.Assert.*
import org.junit.Test

class QuickSettingsLaunchTest {
    @Test fun tileLongPressResolvesToApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("AUTO_CONNECT", true)
        val activity = context.packageManager.resolveActivity(intent, 0)
        assertEquals(MainActivity::class.java.name, activity?.activityInfo?.name)
    }
}
