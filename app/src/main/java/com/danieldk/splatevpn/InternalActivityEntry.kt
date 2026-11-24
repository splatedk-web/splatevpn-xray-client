package com.danieldk.splatevpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Android rejects other applications at the non-exported activity alias. */
internal object InternalActivityEntry {
    private fun name(context: Context) = "${context.packageName}.InternalMainActivity"
    fun intent(context: Context) = Intent().setComponent(ComponentName(context, name(context)))
    fun isInternal(context: Context, intent: Intent) = intent.component == ComponentName(context, name(context))
}
