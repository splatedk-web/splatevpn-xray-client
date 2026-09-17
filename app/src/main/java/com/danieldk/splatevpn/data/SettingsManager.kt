package com.danieldk.splatevpn.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)

    private val _routingModeFlow = MutableStateFlow(getRoutingMode())
    val routingModeFlow: StateFlow<String> = _routingModeFlow.asStateFlow()

    private val _isGlobalAppProxyFlow = MutableStateFlow(isGlobalAppProxy())
    val isGlobalAppProxyFlow: StateFlow<Boolean> = _isGlobalAppProxyFlow.asStateFlow()

    fun getRoutingMode(): String {
        return prefs.getString("routing_mode", "smart_ru") ?: "smart_ru"
    }

    fun setRoutingMode(mode: String) {
        prefs.edit().putString("routing_mode", mode).apply()
        _routingModeFlow.value = mode
    }

    fun isGlobalAppProxy(): Boolean {
        return prefs.getBoolean("is_global_app_proxy", true)
    }

    fun setGlobalAppProxy(global: Boolean) {
        prefs.edit().putBoolean("is_global_app_proxy", global).apply()
        _isGlobalAppProxyFlow.value = global
    }

    fun getPerAppPackages(): Set<String> {
        // IMPORTANT: Always return a NEW HashSet copy!
        // SharedPreferences.getStringSet() returns a reference to its internal Set.
        // Mutating it (even via toMutableSet()) can cause putStringSet() to silently
        // not persist changes because it detects "same object, no change".
        return HashSet(prefs.getStringSet("per_app_packages", emptySet()) ?: emptySet())
    }

    fun setPerAppPackages(packages: Set<String>) {
        // Write a defensive copy to ensure SharedPreferences detects the change
        prefs.edit().putStringSet("per_app_packages", HashSet(packages)).apply()
    }

    private val _isBlockQuicFlow = MutableStateFlow(isBlockQuic())
    val isBlockQuicFlow: StateFlow<Boolean> = _isBlockQuicFlow.asStateFlow()

    fun isBlockQuic(): Boolean {
        return prefs.getBoolean("block_quic", true)
    }

    fun setBlockQuic(block: Boolean) {
        prefs.edit().putBoolean("block_quic", block).apply()
        _isBlockQuicFlow.value = block
    }

    private val _isFakeDnsFlow = MutableStateFlow(isFakeDns())
    val isFakeDnsFlow: StateFlow<Boolean> = _isFakeDnsFlow.asStateFlow()

    fun isFakeDns(): Boolean {
        return prefs.getBoolean("fake_dns", true)
    }

    fun setFakeDns(enabled: Boolean) {
        prefs.edit().putBoolean("fake_dns", enabled).apply()
        _isFakeDnsFlow.value = enabled
    }

    fun isEnableSniffing(): Boolean {
        return prefs.getBoolean("enable_sniffing", true)
    }

    fun setEnableSniffing(enable: Boolean) {
        prefs.edit().putBoolean("enable_sniffing", enable).apply()
    }

    fun getSubscriptionUrl(): String {
        return prefs.getString("sub_url", "") ?: ""
    }

    fun setSubscriptionUrl(url: String) {
        prefs.edit().putString("sub_url", url).apply()
    }

    fun getDnsProvider(): String {
        return prefs.getString("dns_provider", "cloudflare") ?: "cloudflare"
    }

    fun setDnsProvider(provider: String) {
        prefs.edit().putString("dns_provider", provider).apply()
    }

    fun getXhttpMode(): String {
        return prefs.getString("xhttp_mode", "auto") ?: "auto"
    }

    fun setXhttpMode(mode: String) {
        prefs.edit().putString("xhttp_mode", mode).apply()
    }

    private val _isAutoSelectFlow = MutableStateFlow(isAutoSelectServer())
    val isAutoSelectFlow: StateFlow<Boolean> = _isAutoSelectFlow.asStateFlow()

    fun isAutoSelectServer(): Boolean {
        return prefs.getBoolean("auto_select_server", false)
    }

    fun setAutoSelectServer(enabled: Boolean) {
        prefs.edit().putBoolean("auto_select_server", enabled).apply()
        _isAutoSelectFlow.value = enabled
    }

    fun getAutoUpdateInterval(): String {
        return prefs.getString("sub_auto_update_interval", "24h") ?: "24h"
    }

    fun setAutoUpdateInterval(interval: String) {
        prefs.edit().putString("sub_auto_update_interval", interval).apply()
    }

    fun getLastAutoUpdateTime(): Long {
        return prefs.getLong("last_sub_update_time", 0L)
    }

    fun setLastAutoUpdateTime(time: Long) {
        prefs.edit().putLong("last_sub_update_time", time).apply()
    }

    fun isBlockIpv6(): Boolean {
        return prefs.getBoolean("block_ipv6", true)
    }

    fun setBlockIpv6(block: Boolean) {
        prefs.edit().putBoolean("block_ipv6", block).apply()
    }

    fun isRouteOnlySniffing(): Boolean {
        return prefs.getBoolean("route_only_sniffing", false)
    }

    fun setRouteOnlySniffing(routeOnly: Boolean) {
        prefs.edit().putBoolean("route_only_sniffing", routeOnly).apply()
    }

    private val _appThemeFlow = MutableStateFlow(getAppTheme())
    val appThemeFlow: StateFlow<String> = _appThemeFlow.asStateFlow()

    fun getAppTheme(): String {
        return prefs.getString("app_theme", "dark") ?: "dark"
    }

    fun setAppTheme(theme: String) {
        prefs.edit().putString("app_theme", theme).apply()
        _appThemeFlow.value = theme
    }

    private val _appLanguageFlow = MutableStateFlow(getAppLanguage())
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    fun getAppLanguage(): String {
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _appLanguageFlow.value = lang
    }
}
