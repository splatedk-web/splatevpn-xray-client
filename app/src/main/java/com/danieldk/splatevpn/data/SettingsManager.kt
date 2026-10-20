package com.danieldk.splatevpn.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    init {
        // Apply the new default once, then retain the user's grouping choice.
        if (!prefs.getBoolean("folders_default_applied", false)) {
            prefs.edit().putBoolean("servers_grouped", true)
                .putString("servers_view_mode", "ALL").remove("servers_filter_sub_id")
                .putBoolean("folders_default_applied", true).apply()
        }
    }

    fun getServersListPreferences() = ServersListPreferences(
        viewMode = prefs.getString("servers_view_mode", "ALL") ?: "ALL",
        filterSubId = prefs.getString("servers_filter_sub_id", null),
        grouped = prefs.getBoolean("servers_grouped", true),
        expandedGroupIds = HashSet(prefs.getStringSet("servers_expanded_groups", emptySet()) ?: emptySet())
    )

    fun setServersListPreferences(state: ServersListPreferences) {
        prefs.edit()
            .putString("servers_view_mode", state.viewMode)
            .putString("servers_filter_sub_id", state.filterSubId)
            .putBoolean("servers_grouped", state.grouped)
            .putStringSet("servers_expanded_groups", HashSet(state.expandedGroupIds))
            .apply()
    }

    fun isSortServersByPing(): Boolean = prefs.getBoolean("servers_sort_by_ping", false)

    fun setSortServersByPing(enabled: Boolean) {
        prefs.edit().putBoolean("servers_sort_by_ping", enabled).apply()
    }

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

    companion object {
        const val PER_APP_MODE_INCLUDE = "include"
        const val PER_APP_MODE_EXCLUDE = "exclude"
    }

    private val _perAppModeFlow = MutableStateFlow(getPerAppMode())
    val perAppModeFlow: StateFlow<String> = _perAppModeFlow.asStateFlow()

    fun getPerAppMode(): String {
        return prefs.getString("per_app_mode", PER_APP_MODE_INCLUDE) ?: PER_APP_MODE_INCLUDE
    }

    fun setPerAppMode(mode: String) {
        prefs.edit().putString("per_app_mode", mode).apply()
        _perAppModeFlow.value = mode
    }

    fun getPerAppPackages(): Set<String> {
        // IMPORTANT: Always return a NEW HashSet copy!
        // SharedPreferences.getStringSet() returns a reference to its internal Set.
        // Mutating it (even via toMutableSet()) can cause putStringSet() to silently
        // not persist changes because it detects "same object, no change".
        return HashSet(prefs.getStringSet("per_app_packages", emptySet()) ?: emptySet())
    }

    /** Persist the complete rule before exposing its state or reconnecting the tunnel. */
    fun setAppRouting(mode: AppRoutingMode, packages: Set<String>) {
        require(mode.canApply(packages)) { "Select at least one application for include mode" }
        val listMode = when (mode) {
            AppRoutingMode.INCLUDE -> PER_APP_MODE_INCLUDE
            AppRoutingMode.EXCLUDE -> PER_APP_MODE_EXCLUDE
            AppRoutingMode.ALL -> getPerAppMode()
        }
        prefs.edit().putBoolean("is_global_app_proxy", mode == AppRoutingMode.ALL)
            .putString("per_app_mode", listMode)
            .putStringSet("per_app_packages", HashSet(packages)).apply()
        _perAppModeFlow.value = listMode
        _isGlobalAppProxyFlow.value = mode == AppRoutingMode.ALL
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

    private val _isAdBlockerFlow = MutableStateFlow(isAdBlocker())
    val isAdBlockerFlow: StateFlow<Boolean> = _isAdBlockerFlow.asStateFlow()

    fun isAdBlocker(): Boolean {
        return prefs.getBoolean("ad_blocker", true)
    }

    fun setAdBlocker(enabled: Boolean) {
        prefs.edit().putBoolean("ad_blocker", enabled).apply()
        _isAdBlockerFlow.value = enabled
    }

    fun getAdBlockAllowlist(): List<String> = prefs.getStringSet("ad_block_allowlist", emptySet())!!.sorted()
    private val _adBlockAllowlist = MutableStateFlow(getAdBlockAllowlist())
    val adBlockAllowlist = _adBlockAllowlist.asStateFlow()
    fun setAdBlockAllowlist(domains: List<String>) {
        require(domains.size <= 100)
        val normalized = domains.map { requireNotNull(AdFilterCompiler.normalizeDomain(it)) }.distinct().sorted()
        prefs.edit().putStringSet("ad_block_allowlist", normalized.toSet()).apply()
        _adBlockAllowlist.value = normalized
    }

    private val _isFakeDnsFlow = MutableStateFlow(isFakeDns())
    val isFakeDnsFlow: StateFlow<Boolean> = _isFakeDnsFlow.asStateFlow()

    fun isFakeDns(): Boolean {
        return prefs.getBoolean("fake_dns", false)
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
        val provider = prefs.getString("dns_provider", "cloudflare") ?: "cloudflare"
        return if (provider.lowercase() in listOf("xbox", "comss")) "dns_ai" else provider
    }

    private val _pingModeFlow = MutableStateFlow(getPingMode())
    val pingModeFlow: StateFlow<String> = _pingModeFlow.asStateFlow()

    fun getPingMode(): String {
        return prefs.getString("ping_mode", "tcp") ?: "tcp"
    }

    fun setPingMode(mode: String) {
        prefs.edit().putString("ping_mode", mode).apply()
        _pingModeFlow.value = mode
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

    fun getBalancerPreferences(): BalancerPreferences = BalancerPreferences(
        mode = runCatching { BalancerMode.valueOf(prefs.getString("balancer_mode", "BALANCED") ?: "BALANCED") }.getOrDefault(BalancerMode.BALANCED),
        healthIntervalSeconds = prefs.getInt("balancer_health_seconds", 2),
        failureConfirmations = prefs.getInt("balancer_failures", 2),
        optimizationMinutes = prefs.getInt("balancer_optimization_minutes", 5),
        cooldownSeconds = prefs.getInt("balancer_cooldown_seconds", 60),
        improvementPercent = prefs.getInt("balancer_improvement_percent", 30)
    ).validated()

    private val _balancerPreferences = MutableStateFlow(getBalancerPreferences())
    val balancerPreferences: StateFlow<BalancerPreferences> = _balancerPreferences.asStateFlow()

    fun setBalancerPreferences(value: BalancerPreferences) {
        val valid = value.validated()
        prefs.edit().putString("balancer_mode", valid.mode.name)
            .putInt("balancer_health_seconds", valid.healthIntervalSeconds)
            .putInt("balancer_failures", valid.failureConfirmations)
            .putInt("balancer_optimization_minutes", valid.optimizationMinutes)
            .putInt("balancer_cooldown_seconds", valid.cooldownSeconds)
            .putInt("balancer_improvement_percent", valid.improvementPercent).apply()
        _balancerPreferences.value = valid
    }

    fun getAutoUpdateInterval(): String {
        return prefs.getString("sub_auto_update_interval", "24h") ?: "24h"
    }

    fun setAutoUpdateInterval(interval: String) {
        prefs.edit().putString("sub_auto_update_interval", interval).apply()
        SubscriptionUpdateWorker.schedule(context, interval)
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
    fun getGlassTransparency(): Float = prefs.getFloat("glass_transparency", 0f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    private val _glassTransparency = MutableStateFlow(getGlassTransparency())
    val glassTransparency: StateFlow<Float> = _glassTransparency.asStateFlow()
    fun getGlassBlurEnabled(): Boolean = prefs.getBoolean("glass_blur_enabled", true)
    private val _glassBlurEnabled = MutableStateFlow(getGlassBlurEnabled())
    val glassBlurEnabled: StateFlow<Boolean> = _glassBlurEnabled.asStateFlow()
    fun setGlassBlurEnabled(enabled: Boolean) {
        _glassBlurEnabled.value = enabled
        prefs.edit().putBoolean("glass_blur_enabled", enabled).apply()
    }
    fun setGlassTransparency(value: Float, persist: Boolean = true) {
        val valid = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        _glassTransparency.value = valid
        if (persist) prefs.edit().putFloat("glass_transparency", valid).apply()
    }
    val appThemeFlow: StateFlow<String> = _appThemeFlow.asStateFlow()

    fun getAppTheme(): String {
        return prefs.getString("app_theme", "dark") ?: "dark"
    }

    fun setAppTheme(theme: String) {
        prefs.edit().putString("app_theme", theme).apply()
        _appThemeFlow.value = theme
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

    private val _appLanguageFlow = MutableStateFlow(getAppLanguage())
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    fun getAppLanguage(): String {
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _appLanguageFlow.value = lang
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

    private val _isBypassAiFlow = MutableStateFlow(isBypassAi())
    val isBypassAiFlow: StateFlow<Boolean> = _isBypassAiFlow.asStateFlow()

    fun isBypassAi(): Boolean {
        return prefs.getBoolean("bypass_ai", true)
    }

    fun setBypassAi(enabled: Boolean) {
        prefs.edit().putBoolean("bypass_ai", enabled).apply()
        _isBypassAiFlow.value = enabled
    }

    private val _aiRoutingModeFlow = MutableStateFlow(getAiRoutingMode())
    val aiRoutingModeFlow: StateFlow<String> = _aiRoutingModeFlow.asStateFlow()

    fun getAiRoutingMode(): String = if (prefs.getString("ai_routing_mode", "dns_ai") == "vpn") "vpn" else "dns_ai"

    fun getAiDnsProvider(): String = SmartDnsProviders.forAi(prefs.getString("ai_dns_provider", "dns_ai") ?: "dns_ai").key
    private val _aiDnsProviderFlow = MutableStateFlow(getAiDnsProvider())
    val aiDnsProviderFlow: StateFlow<String> = _aiDnsProviderFlow.asStateFlow()
    fun isAiTlsFragmentEnabled(): Boolean = prefs.getBoolean("ai_tls_fragment", false)
    private val _aiTlsFragmentFlow = MutableStateFlow(isAiTlsFragmentEnabled())
    val aiTlsFragmentFlow: StateFlow<Boolean> = _aiTlsFragmentFlow.asStateFlow()
    fun setAiTlsFragmentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ai_tls_fragment", enabled).apply()
        _aiTlsFragmentFlow.value = enabled
    }
    fun setAiDnsProvider(provider: String) {
        val normalized = SmartDnsProviders.forAi(provider).key
        prefs.edit().putString("ai_dns_provider", normalized).apply()
        _aiDnsProviderFlow.value = normalized
    }

    fun setAiSmartDnsChoice(choice: AiDnsChoice) {
        val provider = SmartDnsProviders.forAi(choice.providerKey).key
        prefs.edit().putString("ai_dns_provider", provider).putBoolean("ai_tls_fragment", choice.tlsFragment).apply()
        _aiDnsProviderFlow.value = provider
        _aiTlsFragmentFlow.value = choice.tlsFragment
    }

    fun setAiRoutingMode(mode: String) {
        val normalized = if (mode == "vpn") "vpn" else "dns_ai"
        prefs.edit().putString("ai_routing_mode", normalized).apply()
        _aiRoutingModeFlow.value = normalized
    }

    private val _isAutoConnectOnBootFlow = MutableStateFlow(isAutoConnectOnBoot())
    val isAutoConnectOnBootFlow: StateFlow<Boolean> = _isAutoConnectOnBootFlow.asStateFlow()

    fun isAutoConnectOnBoot(): Boolean {
        return prefs.getBoolean("auto_connect_on_boot", false)
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect_on_boot", enabled).apply()
        _isAutoConnectOnBootFlow.value = enabled
    }

    private val _isTlsFragmentFlow = MutableStateFlow(isTlsFragmentEnabled())
    val isTlsFragmentFlow: StateFlow<Boolean> = _isTlsFragmentFlow.asStateFlow()

    fun isTlsFragmentEnabled(): Boolean {
        return prefs.getBoolean("tls_fragment_enabled", false)
    }

    fun setTlsFragmentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tls_fragment_enabled", enabled).apply()
        _isTlsFragmentFlow.value = enabled
    }

    private val _tlsFragmentPresetFlow = MutableStateFlow(getTlsFragmentPreset())
    val tlsFragmentPresetFlow: StateFlow<String> = _tlsFragmentPresetFlow.asStateFlow()

    fun getTlsFragmentPreset(): String {
        return prefs.getString("tls_fragment_preset", "standard") ?: "standard"
    }

    fun setTlsFragmentPreset(preset: String) {
        prefs.edit().putString("tls_fragment_preset", preset).apply()
        _tlsFragmentPresetFlow.value = preset
        when (preset) {
            "standard" -> {
                setTlsFragmentLength("100-200")
                setTlsFragmentInterval("10-20")
                setTlsFragmentPackets("tlshello")
            }
            "aggressive" -> {
                setTlsFragmentLength("50-100")
                setTlsFragmentInterval("20-40")
                setTlsFragmentPackets("1-3")
            }
        }
    }

    private val _tlsFragmentLengthFlow = MutableStateFlow(getTlsFragmentLength())
    val tlsFragmentLengthFlow: StateFlow<String> = _tlsFragmentLengthFlow.asStateFlow()

    fun getTlsFragmentLength(): String {
        return prefs.getString("tls_fragment_length", "100-200") ?: "100-200"
    }

    fun setTlsFragmentLength(length: String) {
        prefs.edit().putString("tls_fragment_length", length).apply()
        _tlsFragmentLengthFlow.value = length
    }

    private val _tlsFragmentIntervalFlow = MutableStateFlow(getTlsFragmentInterval())
    val tlsFragmentIntervalFlow: StateFlow<String> = _tlsFragmentIntervalFlow.asStateFlow()

    fun getTlsFragmentInterval(): String {
        return prefs.getString("tls_fragment_interval", "10-20") ?: "10-20"
    }

    fun setTlsFragmentInterval(interval: String) {
        prefs.edit().putString("tls_fragment_interval", interval).apply()
        _tlsFragmentIntervalFlow.value = interval
    }

    private val _tlsFragmentPacketsFlow = MutableStateFlow(getTlsFragmentPackets())
    val tlsFragmentPacketsFlow: StateFlow<String> = _tlsFragmentPacketsFlow.asStateFlow()

    fun getTlsFragmentPackets(): String {
        return prefs.getString("tls_fragment_packets", "tlshello") ?: "tlshello"
    }

    fun setTlsFragmentPackets(packets: String) {
        prefs.edit().putString("tls_fragment_packets", packets).apply()
        _tlsFragmentPacketsFlow.value = packets
    }

    // Custom DoH endpoint
    private val _customDohUrlFlow = MutableStateFlow(getCustomDohUrl())
    val customDohUrlFlow: StateFlow<String> = _customDohUrlFlow.asStateFlow()

    fun getCustomDohUrl(): String {
        return prefs.getString("custom_doh_url", "https://dns.google/dns-query") ?: "https://dns.google/dns-query"
    }

    fun setCustomDohUrl(url: String) {
        prefs.edit().putString("custom_doh_url", url).apply()
        _customDohUrlFlow.value = url
    }

    // Multiplexing (Mux.Cool)
    private val _isMuxFlow = MutableStateFlow(isMuxEnabled())
    val isMuxFlow: StateFlow<Boolean> = _isMuxFlow.asStateFlow()

    fun isMuxEnabled(): Boolean {
        return prefs.getBoolean("mux_enabled", false)
    }

    fun setMuxEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mux_enabled", enabled).apply()
        _isMuxFlow.value = enabled
    }

    private val _muxConcurrencyFlow = MutableStateFlow(getMuxConcurrency())
    val muxConcurrencyFlow: StateFlow<Int> = _muxConcurrencyFlow.asStateFlow()

    fun getMuxConcurrency(): Int {
        return prefs.getInt("mux_concurrency", 8)
    }

    fun setMuxConcurrency(concurrency: Int) {
        prefs.edit().putInt("mux_concurrency", concurrency).apply()
        _muxConcurrencyFlow.value = concurrency
    }

    fun setSelectedServerName(name: String) {
        prefs.edit().putString("selected_server_name", name).apply()
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
    }

    fun getSelectedServerName(): String {
        return prefs.getString("selected_server_name", "") ?: ""
    }

    // Custom domain routing rules: List of (domain, outboundTag) pairs
    // outboundTag: "direct" | "proxy" | "block"
    private val gson = Gson()
    private val _customRoutesFlow = MutableStateFlow(getCustomRoutes())
    val customRoutesFlow: StateFlow<List<Pair<String, String>>> = _customRoutesFlow.asStateFlow()

    fun getCustomRoutes(): List<Pair<String, String>> {
        return try {
            val json = prefs.getString("custom_routes_json", null) ?: return emptyList()
            val type = object : TypeToken<List<List<String>>>() {}.type
            val raw: List<List<String>> = gson.fromJson(json, type)
            raw.mapNotNull { entry ->
                if (entry.size == 2) Pair(entry[0], entry[1]) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomRoute(domain: String, outboundTag: String) {
        val current = getCustomRoutes().toMutableList()
        val cleanDomain = domain.trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .removeSuffix("/")
        // Remove existing entry for this domain
        current.removeAll { it.first == cleanDomain }
        current.add(Pair(cleanDomain, outboundTag))
        saveCustomRoutes(current)
    }

    fun removeCustomRoute(domain: String) {
        val current = getCustomRoutes().toMutableList()
        current.removeAll { it.first == domain }
        saveCustomRoutes(current)
    }

    internal fun saveCustomRoutes(routes: List<Pair<String, String>>) {
        val asLists = routes.map { listOf(it.first, it.second) }
        val json = gson.toJson(asLists)
        prefs.edit().putString("custom_routes_json", json).apply()
        _customRoutesFlow.value = routes
    }

    fun hasInitializedPresetSubscriptions(): Boolean {
        return prefs.getBoolean("has_initialized_preset_subscriptions_v1", false)
    }

    fun setInitializedPresetSubscriptions(initialized: Boolean) {
        prefs.edit().putBoolean("has_initialized_preset_subscriptions_v1", initialized).apply()
    }

    fun hasInitializedUniversalWhitelist(): Boolean =
        prefs.getBoolean("has_initialized_universal_whitelist_v1", false)

    fun setInitializedUniversalWhitelist() {
        prefs.edit().putBoolean("has_initialized_universal_whitelist_v1", true).apply()
    }

    // LAN / Tethering Proxy Sharing (Non-root Hotspot & USB tethering)
    private val _isShareLanFlow = MutableStateFlow(isShareLanEnabled())
    val isShareLanFlow: StateFlow<Boolean> = _isShareLanFlow.asStateFlow()

    fun isShareLanEnabled(): Boolean {
        return prefs.getBoolean("share_lan_enabled", false)
    }

    fun setShareLanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("share_lan_enabled", enabled).apply()
        _isShareLanFlow.value = enabled
    }
    /** Used only to roll back an unsuccessful backup import. Never exported. */
    internal fun preferenceSnapshot(): Map<String, *> = prefs.all.toMap()

    internal fun restorePreferenceSnapshot(snapshot: Map<String, *>) {
        val editor = prefs.edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Could not restore preferences" }
        _routingModeFlow.value = getRoutingMode()
        _isGlobalAppProxyFlow.value = isGlobalAppProxy()
        _perAppModeFlow.value = getPerAppMode()
        _isBlockQuicFlow.value = isBlockQuic()
        _isAdBlockerFlow.value = isAdBlocker()
        _adBlockAllowlist.value = getAdBlockAllowlist()
        _isFakeDnsFlow.value = isFakeDns()
        _pingModeFlow.value = getPingMode()
        _isAutoSelectFlow.value = isAutoSelectServer()
        _balancerPreferences.value = getBalancerPreferences()
        _appThemeFlow.value = getAppTheme()
        _glassTransparency.value = getGlassTransparency()
        _glassBlurEnabled.value = getGlassBlurEnabled()
        _appLanguageFlow.value = getAppLanguage()
        _isBypassAiFlow.value = isBypassAi()
        _aiRoutingModeFlow.value = getAiRoutingMode()
        _aiDnsProviderFlow.value = getAiDnsProvider()
        _aiTlsFragmentFlow.value = isAiTlsFragmentEnabled()
        _isAutoConnectOnBootFlow.value = isAutoConnectOnBoot()
        _isTlsFragmentFlow.value = isTlsFragmentEnabled()
        _tlsFragmentPresetFlow.value = getTlsFragmentPreset()
        _tlsFragmentLengthFlow.value = getTlsFragmentLength()
        _tlsFragmentIntervalFlow.value = getTlsFragmentInterval()
        _tlsFragmentPacketsFlow.value = getTlsFragmentPackets()
        _customDohUrlFlow.value = getCustomDohUrl()
        _isMuxFlow.value = isMuxEnabled()
        _muxConcurrencyFlow.value = getMuxConcurrency()
        _customRoutesFlow.value = getCustomRoutes()
        _isShareLanFlow.value = isShareLanEnabled()
        SubscriptionUpdateWorker.schedule(context, getAutoUpdateInterval())
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

}
