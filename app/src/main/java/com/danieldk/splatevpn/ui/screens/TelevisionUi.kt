package com.danieldk.splatevpn.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** UI mode is authoritative; Leanback also covers launchers with an incomplete configuration. */
internal fun isTelevisionUi(uiMode: Int, hasLeanback: Boolean): Boolean =
    (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback

internal fun isTelevisionDevice(context: Context): Boolean = isTelevisionUi(
    context.resources.configuration.uiMode,
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
)
