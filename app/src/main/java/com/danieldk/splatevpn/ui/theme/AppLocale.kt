package com.danieldk.splatevpn.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

val LocalAppResources = compositionLocalOf<Resources> { Resources.getSystem() }

fun appLocale(setting: String, system: Locale): Locale = when (setting.lowercase(Locale.ROOT)) {
    "ru" -> Locale.forLanguageTag("ru")
    "en" -> Locale.ENGLISH
    "zh-hans", "zh" -> Locale.SIMPLIFIED_CHINESE
    "fa" -> Locale.forLanguageTag("fa")
    else -> if (system.language == "zh") Locale.SIMPLIFIED_CHINESE else system
}

fun localizedResources(context: Context, locale: Locale): Resources = context.createConfigurationContext(
    Configuration(context.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
).resources
