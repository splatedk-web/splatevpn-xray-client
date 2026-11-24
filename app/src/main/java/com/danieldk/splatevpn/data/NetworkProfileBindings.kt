package com.danieldk.splatevpn.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** An explicit binding stores the reviewed snapshot, not a mutable profile reference. */
@Singleton
class NetworkProfileBindings @Inject constructor(@ApplicationContext context: Context,
    private val settings: SettingsManager, private val profiles: ConnectionProfiles) {
    private val prefs = context.getSharedPreferences("network_profile_bindings", Context.MODE_PRIVATE)
    private val _bindings = MutableStateFlow(labels())
    val bindings = _bindings.asStateFlow()
    private fun labels() = listOf("wifi", "cellular").associateWith { prefs.getString("${it}_label", "").orEmpty() }
    @Synchronized fun bind(network: String, profile: String?, label: String = "") {
        require(network in setOf("wifi", "cellular"))
        val snapshot = profile?.let { PreferenceValues.encode(profiles.target(it)) }
        check(prefs.edit().putString(network, snapshot).putString("${network}_label", if (profile == null) "" else label.take(48)).commit())
        _bindings.value = labels()
    }
    @Synchronized fun apply(network: String): Boolean {
        if (!settings.canUseNetworkSettings()) return false
        val raw = prefs.getString(network, null) ?: return false
        val target = runCatching { PreferenceValues.decode(raw).also(NetworkPreferences::validate) }.getOrNull() ?: return false
        val current = settings.preferenceSnapshot()
        if (NetworkPreferences.capture(current) == target) return false
        val next = NetworkPreferences.apply(current, target).toMutableMap()
        next["connection_profile_undo_v1"] = PreferenceValues.encode(NetworkPreferences.capture(current))
        settings.restorePreferenceSnapshot(next)
        profiles.reload()
        return true
    }
}
