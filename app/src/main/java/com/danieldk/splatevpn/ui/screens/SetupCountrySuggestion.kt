package com.danieldk.splatevpn.ui.screens

import android.content.Context
import android.telephony.TelephonyManager
import com.danieldk.splatevpn.data.RoutingRegion
import java.util.Locale

internal enum class SetupCountrySource { NETWORK, SIM, DEVICE }
internal data class SetupCountrySuggestion(val countryCode: String, val source: SetupCountrySource) {
    val region: RoutingRegion get() = RoutingRegion.suggested(Locale("", countryCode))
    val flag: String get() = countryFlag(countryCode)
    fun displayName(locale: Locale): String = Locale("", countryCode).getDisplayCountry(locale)
}

/** Country codes need no phone/location permission and never leave the device. */
internal fun suggestSetupCountry(context: Context): SetupCountrySuggestion? {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val network = runCatching { telephony?.networkCountryIso }.getOrNull()
    val sim = runCatching { telephony?.simCountryIso }.getOrNull()
    val device = android.content.res.Resources.getSystem().configuration.locales[0].country
    return selectSetupCountry(network, sim, device)
}

internal fun selectSetupCountry(network: String?, sim: String?, device: String?): SetupCountrySuggestion? {
    val candidates = listOf(network to SetupCountrySource.NETWORK, sim to SetupCountrySource.SIM, device to SetupCountrySource.DEVICE)
    return candidates.firstNotNullOfOrNull { (value, source) ->
        value?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in countryCodes }
            ?.let { SetupCountrySuggestion(it, source) }
    }
}

private val countryCodes = Locale.getISOCountries().toSet()
internal fun countryFlag(code: String): String = if (code.uppercase(Locale.ROOT) in countryCodes) {
    code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
} else "🌐"
