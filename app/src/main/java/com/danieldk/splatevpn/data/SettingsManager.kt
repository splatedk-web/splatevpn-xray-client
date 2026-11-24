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
    private val serverSelectionRevision = java.util.concurrent.atomic.AtomicLong()
    internal fun selectionRevision(): Long = serverSelectionRevision.get()
    internal fun invalidateServerSelection() { serverSelectionRevision.incrementAndGet() }
    private val recovery = SettingsRecovery(context)
    private var prefs: SharedPreferences = recovery.open(context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE))
    internal val recoveryNotice = recovery.notice
    internal val subscriptionHwidStore by lazy { SubscriptionHwidStore(context) }
    internal fun getSubscriptionHwidOverrides(): Map<String, Boolean> = runCatching {
        SubscriptionHwidPreferences.decode(prefs.getString("subscription_hwid_v1", "{}").orEmpty())
    }.getOrDefault(emptyMap())
    internal fun subscriptionHwidOverridesJson(): String = SubscriptionHwidPreferences.encode(getSubscriptionHwidOverrides())
    internal fun storeSubscriptionHwidOverrides(json: String, merge: Boolean = false) {
        val imported = SubscriptionHwidPreferences.decode(json)
        val overrides = if (merge) getSubscriptionHwidOverrides() + imported else imported
        check(prefs.edit().putString("subscription_hwid_v1", SubscriptionHwidPreferences.encode(overrides)).commit())
    }
    private fun subscriptionUserAgents(): Map<String, SubscriptionUserAgent> {
        val saved = prefs.getString("subscription_ua_overrides_v1", null)
        if (saved != null) return runCatching { SubscriptionUserAgent.decodeOverrides(saved) }.getOrDefault(emptyMap())
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("subscription_ua_")) return@mapNotNull null
            val agent = SubscriptionUserAgent.fromId(value as? String)
            agent.takeUnless { it == SubscriptionUserAgent.AUTO }?.let { key.removePrefix("subscription_ua_") to it }
        }.toMap()
    }

    fun getSubscriptionUserAgent(url: String): SubscriptionUserAgent? = SubscriptionHwidPolicy.key(url)?.let { key ->
        if (prefs.contains("subscription_ua_overrides_v1")) subscriptionUserAgents()[key] ?: SubscriptionUserAgent.AUTO
        else SubscriptionUserAgent.fromId(prefs.getString("subscription_ua_$key", null))
    }

    @Synchronized fun setSubscriptionUserAgent(url: String, agent: SubscriptionUserAgent) {
        val key = SubscriptionHwidPolicy.key(url) ?: return
        val overrides = subscriptionUserAgents()
        saveSubscriptionUserAgents(if (agent == SubscriptionUserAgent.AUTO) overrides - key else overrides + (key to agent))
    }

    internal fun subscriptionUserAgentsJson() = SubscriptionUserAgent.encodeOverrides(subscriptionUserAgents())

    @Synchronized internal fun storeSubscriptionUserAgents(json: String, mergedSources: List<SubscriptionEntity>?) {
        val imported = SubscriptionUserAgent.decodeOverrides(json)
        val old = mergedSources?.mapNotNull { SubscriptionHwidPolicy.key(it.url) }?.let { keys ->
            subscriptionUserAgents() - keys.toSet()
        }.orEmpty()
        saveSubscriptionUserAgents(old + imported)
    }

    private fun saveSubscriptionUserAgents(overrides: Map<String, SubscriptionUserAgent>) {
        val json = SubscriptionUserAgent.encodeOverrides(overrides)
        SubscriptionUserAgent.decodeOverrides(json)
        val editor = prefs.edit().putString("subscription_ua_overrides_v1", json)
        prefs.all.keys.filter { it.startsWith("subscription_ua_") && it != "subscription_ua_overrides_v1" }.forEach(editor::remove)
        editor.apply()
    }

    fun isSubscriptionHwidEnabled(url: String): Boolean = SubscriptionHwidPolicy.enabled(url, getSubscriptionHwidOverrides())
    @Synchronized fun setSubscriptionHwidEnabled(url: String, enabled: Boolean) {
        val key = SubscriptionHwidPolicy.key(url) ?: return
        if (!SubscriptionHwidPolicy.canSend(url)) return
        storeSubscriptionHwidOverrides(SubscriptionHwidPreferences.encode(getSubscriptionHwidOverrides() + (key to enabled)))
    }
    internal fun acceptRecoveredDefaults() {
        prefs.unregisterOnSharedPreferenceChangeListener(networkObserver)
        prefs = recovery.acceptDefaults()
        _notificationPreferences.value = getNotificationPreferences()
        prefs.registerOnSharedPreferenceChangeListener(networkObserver)
        _networkPreferences.value = NetworkPreferences.capture(preferenceSnapshot())
        _advancedRouting.value = advancedRoutingJson()
    }
    internal fun dismissRecoveryNotice() = recovery.dismissNotice()
    internal fun canUseNetworkSettings(): Boolean = recoveryNotice.value?.needsDecision != true
    internal fun connectionProfilesJson(): String = prefs.getString("connection_profiles_v1", "[]") ?: "[]"
    internal fun connectionProfileUndo(): String? = prefs.getString("connection_profile_undo_v1", null)
    internal fun storeConnectionProfiles(json: String) { check(prefs.edit().putString("connection_profiles_v1", json).commit()) }
    fun advancedRoutingJson(): String = prefs.getString("advanced_routes_v1", "[]") ?: "[]"
    private val _advancedRouting = MutableStateFlow(advancedRoutingJson())
    val advancedRouting = _advancedRouting.asStateFlow()
    fun storeAdvancedRouting(json: String) {
        val validated = AdvancedRoutingRules.validateEncoded(json)
        check(prefs.edit().putString("advanced_routes_v1", validated).commit())
        _advancedRouting.value = validated
    }

    init {
        if (!prefs.contains("regional_settings_version")) {
            val existing = prefs.all.isNotEmpty()
            val legacyMode = prefs.getString("routing_mode", "smart_ru")
            if (!existing) prefs.edit().putString("ai_routing_mode", "vpn").apply()
            check(prefs.edit()
                .putString("routing_region", if (existing) "ru" else RoutingRegion.suggested(java.util.Locale.getDefault()).id)
                .putString("routing_mode", if (!existing || legacyMode != "global") "smart" else "global")
                .putInt("onboarding_version", if (existing) 1 else 0)
                .putBoolean("regional_presets_enabled", existing || RoutingRegion.suggested(java.util.Locale.getDefault()) == RoutingRegion.RUSSIA)
                .putInt("regional_settings_version", 1).commit())
        }
        if (prefs.getInt("regional_settings_version", 0) < 2) {
            val editor = prefs.edit().putInt("regional_settings_version", 2)
            if (prefs.getInt("onboarding_version", 0) == 0) editor.putString("routing_mode", "smart")
                .putBoolean("regional_presets_enabled", prefs.getString("routing_region", "") == "ru")
            check(editor.commit())
        }
        if (prefs.getInt("regional_settings_version", 0) < 3) {
            val editor = prefs.edit().putInt("regional_settings_version", 3)
            // Subscription sources and the country used for routing are independent choices.
            if (prefs.getInt("onboarding_version", 0) == 0) editor.putBoolean("regional_presets_enabled", true)
            check(editor.commit())
        }
        // Apply the new default once, then retain the user's grouping choice.
        if (!prefs.getBoolean("folders_default_applied", false)) {
            prefs.edit().putBoolean("servers_grouped", true)
                .putString("servers_view_mode", "ALL").remove("servers_filter_sub_id")
                .putBoolean("folders_default_applied", true).apply()
        }
        if (!prefs.getBoolean("balancer_relaxed_defaults_applied", false)) {
            val editor = prefs.edit()
            if (prefs.getInt("balancer_health_seconds", 10) <= 2) {
                editor.putInt("balancer_health_seconds", 10)
            }
            if (prefs.getInt("balancer_failures", 3) <= 2) {
                editor.putInt("balancer_failures", 3)
            }
            editor.putBoolean("balancer_relaxed_defaults_applied", true).apply()
        }
    }

    init {
        if (!prefs.contains("folder_controls_version")) prefs.edit().remove("disabled_server_ids")
            .putInt("folder_controls_version", 1).apply()
    }
    fun isPersonalFolderEnabled(disabled: Set<String> = getDisabledServerIds()) = "__personal_folder__" !in disabled
    fun setPersonalFolderEnabled(enabled: Boolean) = setServersEnabled(setOf("__personal_folder__"), enabled)
    fun isServerEnabled(node: ServerNode, disabled: Set<String> = getDisabledServerIds(),
        now: Long = System.currentTimeMillis()) = (!node.isRetained || SubscriptionNodeMerger.canRetain(node, now)) &&
        node.id !in disabled &&
        (isPersonalFolderEnabled(disabled) || !QuickServerSelection.isPersonal(node))

    fun getDisabledServerIds(): Set<String> = prefs.getString("disabled_server_ids", "").orEmpty()
        .lineSequence().filter { it.isNotBlank() }.toSet()
    private val _disabledServerIds = MutableStateFlow(getDisabledServerIds())
    val disabledServerIds = _disabledServerIds.asStateFlow()
    @Synchronized fun setServersEnabled(ids: Set<String>, enabled: Boolean) {
        val disabled = getDisabledServerIds().let { if (enabled) it - ids else it + ids }
        storeDisabledServerIds(disabled)
    }
    internal fun storeDisabledServerIds(ids: Set<String>) {
        prefs.edit().putString("disabled_server_ids", ids.sorted().joinToString("\n")).apply()
        _disabledServerIds.value = ids.toSet()
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
        return if (prefs.getString("routing_mode", "global") in setOf("smart", "smart_ru")) "smart" else "global"
    }

    fun setRoutingMode(mode: String) {
        require(mode in setOf("smart", "smart_ru", "global"))
        if (mode == "smart_ru") setRoutingRegion("ru")
        val normalized = if (mode == "smart_ru") "smart" else mode
        prefs.edit().putString("routing_mode", normalized).apply()
        _routingModeFlow.value = normalized
    }

    private val _routingRegion = MutableStateFlow(getRoutingRegion())
    val routingRegion = _routingRegion.asStateFlow()
    fun getRoutingRegion(): String = RoutingRegion.fromId(prefs.getString("routing_region", null)).id
    fun setRoutingRegion(region: String) {
        require(RoutingRegion.entries.any { it.id == region })
        prefs.edit().putString("routing_region", region).apply()
        _routingRegion.value = region
    }
    private val _needsOnboarding = MutableStateFlow(prefs.getInt("onboarding_version", 0) < 1)
    val needsOnboarding = _needsOnboarding.asStateFlow()
    fun showOnboarding() { _needsOnboarding.value = true }
    fun completeOnboarding(language: String, region: String, mode: String, presets: Boolean, appMode: AppRoutingMode? = null, packages: Set<String> = emptySet()) {
        require(language in setOf("system", "ru", "en", "zh-Hans", "fa"))
        require(RoutingRegion.entries.any { it.id == region } && mode in setOf("smart", "global"))
        require(appMode == null || appMode.canApply(packages))
        val editor = prefs.edit().putString("app_language", language).putString("routing_region", region)
            .putString("routing_mode", mode).putBoolean("regional_presets_enabled", presets)
            .putInt("onboarding_version", 1)
        if (prefs.getInt("onboarding_version", 0) == 0 && !prefs.contains("auto_select_server")) {
            editor.putBoolean("auto_select_server", true)
        }
        if (appMode != null) editor.putBoolean("is_global_app_proxy", appMode == AppRoutingMode.ALL)
            .putString("per_app_mode", if (appMode == AppRoutingMode.EXCLUDE) "exclude" else "include")
            .putStringSet("per_app_packages", packages.toSet())
        check(editor.commit())
        _appLanguageFlow.value = language
        _routingRegion.value = region
        _routingModeFlow.value = mode
        _isAutoSelectFlow.value = isAutoSelectServer()
        _isGlobalAppProxyFlow.value = isGlobalAppProxy()
        _perAppModeFlow.value = getPerAppMode()
        _needsOnboarding.value = false
    }
    fun regionalPresetsEnabled(): Boolean = prefs.getBoolean("regional_presets_enabled", false)
    internal fun setRegionalPresetsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("regional_presets_enabled", enabled).apply()
    }
    fun isSourceDownloadsViaVpn(): Boolean = prefs.getBoolean("source_downloads_via_vpn", false)
    private val _sourceDownloadsViaVpnFlow = MutableStateFlow(isSourceDownloadsViaVpn())
    val sourceDownloadsViaVpnFlow = _sourceDownloadsViaVpnFlow.asStateFlow()
    fun setSourceDownloadsViaVpn(enabled: Boolean) {
        prefs.edit().putBoolean("source_downloads_via_vpn", enabled).apply()
        _sourceDownloadsViaVpnFlow.value = enabled
    }

    fun isUpdatesWifiOnly(): Boolean = prefs.getBoolean(NetworkAccessPolicy.UPDATES_WIFI_ONLY, false)
    private val _updatesWifiOnly = MutableStateFlow(isUpdatesWifiOnly())
    val updatesWifiOnly = _updatesWifiOnly.asStateFlow()
    fun setUpdatesWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(NetworkAccessPolicy.UPDATES_WIFI_ONLY, enabled).apply()
        _updatesWifiOnly.value = enabled
    }
    fun isChecksWifiOnly(): Boolean = prefs.getBoolean(NetworkAccessPolicy.CHECKS_WIFI_ONLY, false)
    private val _checksWifiOnly = MutableStateFlow(isChecksWifiOnly())
    val checksWifiOnly = _checksWifiOnly.asStateFlow()
    fun setChecksWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(NetworkAccessPolicy.CHECKS_WIFI_ONLY, enabled).apply()
        _checksWifiOnly.value = enabled
    }
    fun areUpdatesAllowed(): Boolean = NetworkAccessPolicy.updatesAllowed(context)
    fun areChecksAllowed(): Boolean = NetworkAccessPolicy.checksAllowed(context)
    fun requireUpdatesNetwork() = NetworkAccessPolicy.requireUpdates(context)
    fun requireChecksNetwork() = NetworkAccessPolicy.requireChecks(context)
    suspend fun <T> withUpdateNetwork(block: suspend () -> T): T = NetworkAccessPolicy.withUpdates(context, block)
    suspend fun <T> withCheckNetwork(block: suspend () -> T): T = NetworkAccessPolicy.withChecks(context, block)

    fun isGlobalAppProxy(): Boolean {
        return prefs.getBoolean("is_global_app_proxy", true)
    }

    fun setGlobalAppProxy(global: Boolean) {
        prefs.edit().putBoolean("is_global_app_proxy", global).apply()
        _isGlobalAppProxyFlow.value = global
    }

    companion object {
        internal val APP_LANGUAGES = setOf("system", "ru", "en", "zh-Hans", "fa")
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
        val provider = prefs.getString("dns_provider", "cloudflare") ?: "cloudflare"
        return if (provider.lowercase() in listOf("xbox", "comss", "comss_smart")) "dns_ai" else provider
    }

    private val _pingModeFlow = MutableStateFlow(getPingMode())
    val pingModeFlow: StateFlow<String> = _pingModeFlow.asStateFlow()

    fun getPingMode(): String {
        return prefs.getString("ping_mode", "real_delay") ?: "real_delay"
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
        if (!enabled) invalidateServerSelection()
        prefs.edit().putBoolean("auto_select_server", enabled).apply()
        _isAutoSelectFlow.value = enabled
    }

    fun getBalancerPreferences(): BalancerPreferences = BalancerPreferences(
        mode = runCatching { BalancerMode.valueOf(prefs.getString("balancer_mode", "BALANCED") ?: "BALANCED") }.getOrDefault(BalancerMode.BALANCED),
        healthIntervalSeconds = prefs.getInt("balancer_health_seconds", 10),
        failureConfirmations = prefs.getInt("balancer_failures", 3),
        optimizationMinutes = prefs.getInt("balancer_optimization_minutes", 5),
        cooldownSeconds = prefs.getInt("balancer_cooldown_seconds", 60),
        improvementPercent = prefs.getInt("balancer_improvement_percent", 30),
        metric = runCatching { BalancerMetric.valueOf(prefs.getString("balancer_metric", "LATENCY")!!) }.getOrDefault(BalancerMetric.LATENCY),
        goal = runCatching { BalancerGoal.valueOf(prefs.getString("balancer_goal", "GENERAL")!!) }.getOrDefault(BalancerGoal.GENERAL),
        warmStandby = prefs.getBoolean("balancer_warm_standby", true),
        speedBudgetMiB = prefs.getInt("balancer_speed_budget_mib", 64)
    ).validated()

    private val _balancerPreferences = MutableStateFlow(getBalancerPreferences())
    val balancerPreferences: StateFlow<BalancerPreferences> = _balancerPreferences.asStateFlow()

    fun setBalancerPreferences(value: BalancerPreferences) {
        invalidateServerSelection()
        val valid = value.validated()
        prefs.edit().putString("balancer_mode", valid.mode.name)
            .putInt("balancer_health_seconds", valid.healthIntervalSeconds)
            .putInt("balancer_failures", valid.failureConfirmations)
            .putInt("balancer_optimization_minutes", valid.optimizationMinutes)
            .putInt("balancer_cooldown_seconds", valid.cooldownSeconds)
            .putInt("balancer_improvement_percent", valid.improvementPercent)
            .putString("balancer_metric", valid.metric.name)
            .putString("balancer_goal", valid.goal.name)
            .putBoolean("balancer_warm_standby", valid.warmStandby)
            .putInt("balancer_speed_budget_mib", valid.speedBudgetMiB).apply()
        _balancerPreferences.value = valid
    }

    fun getAutoUpdateInterval(): String {
        return prefs.getString("sub_auto_update_interval", "24h") ?: "24h"
    }

    private val _autoUpdateInterval = MutableStateFlow(getAutoUpdateInterval())
    val autoUpdateInterval = _autoUpdateInterval.asStateFlow()

    fun setAutoUpdateInterval(interval: String, reschedule: Boolean = true) {
        prefs.edit().putString("sub_auto_update_interval", interval).apply()
        _autoUpdateInterval.value = interval
        if (reschedule) SubscriptionUpdateWorker.schedule(context, interval)
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

    private val _mtu = MutableStateFlow(getMtu())
    val mtuFlow = _mtu.asStateFlow()

    fun getMtu(): Int {
        return prefs.getInt("vpn_mtu", 1400).coerceIn(1280, 1500)
    }

    fun setMtu(mtu: Int) {
        val valid = mtu.coerceIn(1280, 1500)
        prefs.edit().putInt("vpn_mtu", valid).apply()
        _mtu.value = valid
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
    fun getPerformanceSettings(): UiPerformanceSettings = UiPerformanceSettings(
        animationsEnabled = prefs.getBoolean("ui_animations_enabled", true),
        decorativeBackgroundEnabled = prefs.getBoolean("ui_decorative_background_enabled", true),
        glassHighlightsEnabled = prefs.getBoolean("ui_glass_highlights_enabled", true)
    )
    private val _performanceSettings = MutableStateFlow(getPerformanceSettings())
    val performanceSettings: StateFlow<UiPerformanceSettings> = _performanceSettings.asStateFlow()

    fun setPerformanceSettings(value: UiPerformanceSettings) {
        prefs.edit().putBoolean("ui_animations_enabled", value.animationsEnabled)
            .putBoolean("ui_decorative_background_enabled", value.decorativeBackgroundEnabled)
            .putBoolean("ui_glass_highlights_enabled", value.glassHighlightsEnabled).apply()
        _performanceSettings.value = value
    }
    fun setUiAnimationsEnabled(enabled: Boolean) =
        setPerformanceSettings(performanceSettings.value.copy(animationsEnabled = enabled))
    fun setDecorativeBackgroundEnabled(enabled: Boolean) =
        setPerformanceSettings(performanceSettings.value.copy(decorativeBackgroundEnabled = enabled))
    fun setGlassHighlightsEnabled(enabled: Boolean) =
        setPerformanceSettings(performanceSettings.value.copy(glassHighlightsEnabled = enabled))

    /** Apply the preset atomically; transparency and the current theme stay unchanged. */
    fun setLightUiMode() = setUiEffectsPreset(enabled = false)
    fun restoreFullUiEffects() = setUiEffectsPreset(enabled = true)
    private fun setUiEffectsPreset(enabled: Boolean) {
        prefs.edit().putBoolean("ui_animations_enabled", enabled)
            .putBoolean("ui_decorative_background_enabled", enabled)
            .putBoolean("ui_glass_highlights_enabled", enabled)
            .putBoolean("glass_blur_enabled", enabled).apply()
        _performanceSettings.value = UiPerformanceSettings(enabled, enabled, enabled)
        _glassBlurEnabled.value = enabled
    }
    fun setGlassTransparency(value: Float, persist: Boolean = true) {
        val valid = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        _glassTransparency.value = valid
        if (persist) prefs.edit().putFloat("glass_transparency", valid).apply()
    }
    val appThemeFlow: StateFlow<String> = _appThemeFlow.asStateFlow()

    fun getAppTheme(): String {
        return com.danieldk.splatevpn.ui.theme.canonicalThemeKey(prefs.getString("app_theme", "dark") ?: "dark")
    }

    fun setAppTheme(theme: String) {
        val key = com.danieldk.splatevpn.ui.theme.canonicalThemeKey(theme)
        prefs.edit().putString("app_theme", key).apply()
        _appThemeFlow.value = key
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

    private val _appLanguageFlow = MutableStateFlow(getAppLanguage())
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    fun getAppLanguage(): String {
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setAppLanguage(lang: String) {
        require(lang in APP_LANGUAGES)
        prefs.edit().putString("app_language", lang).apply()
        _appLanguageFlow.value = lang
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

    private val _uiModeFlow = MutableStateFlow(getUiMode())
    val uiModeFlow: StateFlow<AppUiMode> = _uiModeFlow.asStateFlow()

    fun getUiMode(): AppUiMode = AppUiMode.fromId(prefs.getString("ui_mode", AppUiMode.SIMPLE.id))

    fun setUiMode(mode: AppUiMode) {
        prefs.edit().putString("ui_mode", mode.id).apply()
        _uiModeFlow.value = mode
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

    fun getAiRoutingMode(): String = prefs.getString("ai_routing_mode", "dns_ai")
        ?.takeIf { it in setOf("vpn", "dns_ai", "dns_vpn") } ?: "dns_ai"

    fun getAiDnsProvider(): String = SmartDnsProviders.forAi(prefs.getString("ai_dns_provider", "dns_ai") ?: "dns_ai").key
    private val _aiDnsProviderFlow = MutableStateFlow(getAiDnsProvider())
    val aiDnsProviderFlow: StateFlow<String> = _aiDnsProviderFlow.asStateFlow()
    fun isAiTlsFragmentEnabled(): Boolean = prefs.getBoolean("ai_tls_fragment", false)
    private val _aiTlsFragmentFlow = MutableStateFlow(isAiTlsFragmentEnabled())
    val aiTlsFragmentFlow: StateFlow<Boolean> = _aiTlsFragmentFlow.asStateFlow()
    fun isAiDnsAutoSwitchEnabled(): Boolean = prefs.getBoolean("ai_dns_auto_switch", true)
    private val _aiDnsAutoSwitchFlow = MutableStateFlow(isAiDnsAutoSwitchEnabled())
    val aiDnsAutoSwitchFlow: StateFlow<Boolean> = _aiDnsAutoSwitchFlow.asStateFlow()
    fun setAiDnsAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ai_dns_auto_switch", enabled).apply()
        _aiDnsAutoSwitchFlow.value = enabled
    }
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
        val normalized = mode.takeIf { it in setOf("vpn", "dns_ai", "dns_vpn") } ?: "dns_ai"
        prefs.edit().putString("ai_routing_mode", normalized).apply()
        _aiRoutingModeFlow.value = normalized
    }

    private val _autoConnectOnOpenFlow = MutableStateFlow(isAutoConnectOnOpen())
    val autoConnectOnOpenFlow = _autoConnectOnOpenFlow.asStateFlow()
    fun isAutoConnectOnOpen() = prefs.getBoolean("auto_connect_on_open", false)
    fun setAutoConnectOnOpen(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect_on_open", enabled).apply()
        _autoConnectOnOpenFlow.value = enabled
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

    fun isTlsMixedSniEnabled() = prefs.getBoolean("tls_mixed_sni", false)

    private val _tlsMixedSni = MutableStateFlow(isTlsMixedSniEnabled())
    val tlsMixedSni = _tlsMixedSni.asStateFlow()

    fun setTlsMixedSniEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tls_mixed_sni", enabled).apply()
        _tlsMixedSni.value = enabled
    }

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
            "two_parts" -> {
                setTlsFragmentLength("100")
                setTlsFragmentInterval("10-20")
                setTlsFragmentPackets(TLS_HELLO_TWO_PARTS)
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

    fun hasInitializedExpandedWhitelists(): Boolean =
        prefs.getBoolean("has_initialized_expanded_whitelists_v1", false)

    fun setInitializedExpandedWhitelists() {
        check(prefs.edit().putBoolean("has_initialized_expanded_whitelists_v1", true).commit())
    }

    fun hasInitializedSponsorSubscription() = prefs.getBoolean("has_initialized_sponsor_subscription_v1", false)
    fun setInitializedSponsorSubscription() {
        check(prefs.edit().putBoolean("has_initialized_sponsor_subscription_v1", true).commit())
    }

    fun hasInitializedRkpSubscriptions() = prefs.getBoolean("has_initialized_rkp_subscriptions", false)
    fun setInitializedRkpSubscriptions() {
        prefs.edit().putBoolean("has_initialized_rkp_subscriptions", true).apply()
    }

    fun hasInitializedBlackLists(): Boolean = prefs.getBoolean("has_initialized_black_lists_v1", false)
    fun setInitializedBlackLists() {
        check(prefs.edit().putBoolean("has_initialized_black_lists_v1", true).commit())
    }

    // LAN / Tethering Proxy Sharing (Non-root Hotspot & USB tethering)
    fun isLocalProxyAuthenticationEnabled(): Boolean = prefs.getBoolean("local_proxy_auth_enabled", true)
    private val _localProxyAuthentication = MutableStateFlow(isLocalProxyAuthenticationEnabled())
    val localProxyAuthentication = _localProxyAuthentication.asStateFlow()
    fun setLocalProxyAuthenticationEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean("local_proxy_auth_enabled", enabled).commit())
        _localProxyAuthentication.value = enabled
    }
    @Synchronized fun getLocalProxyCredentials(): com.danieldk.splatevpn.vpn.LocalProxyCredentials {
        val stored = runCatching {
            com.danieldk.splatevpn.vpn.LocalProxyCredentials(
                prefs.getString("local_proxy_username", "").orEmpty(), prefs.getString("local_proxy_password", "").orEmpty())
        }.getOrNull()
        if (stored != null) return stored
        return regenerateLocalProxyCredentials()
    }
    @Synchronized fun regenerateLocalProxyCredentials(): com.danieldk.splatevpn.vpn.LocalProxyCredentials =
        com.danieldk.splatevpn.vpn.LocalProxyCredentials.generate().also { setLocalProxyCredentials(it.username, it.password) }
    fun setLocalProxyCredentials(username: String, password: String) {
        val value = com.danieldk.splatevpn.vpn.LocalProxyCredentials(username, password)
        check(prefs.edit().putString("local_proxy_username", value.username).putString("local_proxy_password", value.password).commit())
    }
    fun localProxyAccess(): com.danieldk.splatevpn.vpn.LocalProxyCredentials? =
        if (isLocalProxyAuthenticationEnabled()) getLocalProxyCredentials() else null

    fun getLanInterface(): String = prefs.getString("lan_interface", "") ?: ""
    fun setLanInterface(name: String) { prefs.edit().putString("lan_interface", name).apply() }
    private val _lanAuthentication = MutableStateFlow(isLanAuthenticationEnabled())
    val lanAuthentication = _lanAuthentication.asStateFlow()
    fun isLanAuthenticationEnabled() = prefs.getBoolean("lan_auth_enabled", true)
    fun setLanAuthenticationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("lan_auth_enabled", enabled).apply()
        _lanAuthentication.value = enabled
    }
    @Synchronized fun getLanPassword(): String {
        prefs.getString("lan_password", null)?.takeIf { it.length >= 12 }?.let { return it }
        val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
        val value = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        check(prefs.edit().putString("lan_password", value).commit())
        return value
    }
    fun rotateLanPassword(): String {
        check(prefs.edit().remove("lan_password").commit())
        return getLanPassword()
    }
    private val _isShareLanFlow = MutableStateFlow(isShareLanEnabled())
    val isShareLanFlow: StateFlow<Boolean> = _isShareLanFlow.asStateFlow()

    fun isShareLanEnabled(): Boolean {
        return prefs.getBoolean("share_lan_enabled", false)
    }

    fun setShareLanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("share_lan_enabled", enabled).apply()
        _isShareLanFlow.value = enabled
    }
    /** Snapshot for atomic backup rollback and connection profiles. Never exported as diagnostic data. */
    internal fun preferenceSnapshot(): Map<String, *> = prefs.all.toMap()

    fun getNotificationPreferences(): NotificationPreferences = NotificationPreferences.read { key, default -> prefs.getBoolean(key, default) }
    private val _notificationPreferences = MutableStateFlow(getNotificationPreferences())
    val notificationPreferences = _notificationPreferences.asStateFlow()
    fun setNotificationPreferences(value: NotificationPreferences) {
        prefs.edit().also { editor -> value.values().forEach { (key, enabled) -> editor.putBoolean(key, enabled) } }.apply()
        _notificationPreferences.value = value
    }

    private val _networkPreferences = MutableStateFlow(NetworkPreferences.capture(preferenceSnapshot()))
    internal val networkPreferences = _networkPreferences.asStateFlow()
    private val networkObserver = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in NotificationPreferences.keys) _notificationPreferences.value = getNotificationPreferences()
        if (key == null || key in NetworkPreferences.keys) _networkPreferences.value = NetworkPreferences.capture(preferenceSnapshot())
        if (key == null || key == "local_proxy_auth_enabled") _localProxyAuthentication.value = isLocalProxyAuthenticationEnabled()
        if (key == null || key == "source_downloads_via_vpn") _sourceDownloadsViaVpnFlow.value = isSourceDownloadsViaVpn()
        if (key == null || key == NetworkAccessPolicy.UPDATES_WIFI_ONLY) _updatesWifiOnly.value = isUpdatesWifiOnly()
        if (key == null || key == NetworkAccessPolicy.CHECKS_WIFI_ONLY) _checksWifiOnly.value = isChecksWifiOnly()
        if (key == null || key == "advanced_routes_v1") _advancedRouting.value = advancedRoutingJson()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(networkObserver) }

    internal fun restorePreferenceSnapshot(snapshot: Map<String, *>, rescheduleUpdates: Boolean = true) {
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
        _notificationPreferences.value = getNotificationPreferences()
        _disabledServerIds.value = getDisabledServerIds()
        _networkPreferences.value = NetworkPreferences.capture(preferenceSnapshot())
        _routingModeFlow.value = getRoutingMode()
        _routingRegion.value = getRoutingRegion()
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
        _performanceSettings.value = getPerformanceSettings()
        _appLanguageFlow.value = getAppLanguage()
        _uiModeFlow.value = getUiMode()
        _autoUpdateInterval.value = getAutoUpdateInterval()
        _isBypassAiFlow.value = isBypassAi()
        _aiRoutingModeFlow.value = getAiRoutingMode()
        _aiDnsProviderFlow.value = getAiDnsProvider()
        _aiTlsFragmentFlow.value = isAiTlsFragmentEnabled()
        _aiDnsAutoSwitchFlow.value = isAiDnsAutoSwitchEnabled()
        _isAutoConnectOnBootFlow.value = isAutoConnectOnBoot()
        _autoConnectOnOpenFlow.value = isAutoConnectOnOpen()
        _isTlsFragmentFlow.value = isTlsFragmentEnabled()
        _tlsFragmentPresetFlow.value = getTlsFragmentPreset()
        _tlsFragmentLengthFlow.value = getTlsFragmentLength()
        _tlsFragmentIntervalFlow.value = getTlsFragmentInterval()
        _tlsFragmentPacketsFlow.value = getTlsFragmentPackets()
        _tlsMixedSni.value = isTlsMixedSniEnabled()
        _mtu.value = getMtu()
        _customDohUrlFlow.value = getCustomDohUrl()
        _isMuxFlow.value = isMuxEnabled()
        _muxConcurrencyFlow.value = getMuxConcurrency()
        _customRoutesFlow.value = getCustomRoutes()
        _isShareLanFlow.value = isShareLanEnabled()
        _lanAuthentication.value = isLanAuthenticationEnabled()
        _localProxyAuthentication.value = isLocalProxyAuthenticationEnabled()
        _sourceDownloadsViaVpnFlow.value = isSourceDownloadsViaVpn()
        _updatesWifiOnly.value = isUpdatesWifiOnly()
        _checksWifiOnly.value = isChecksWifiOnly()
        _advancedRouting.value = advancedRoutingJson()
        if (rescheduleUpdates) SubscriptionUpdateWorker.schedule(context, getAutoUpdateInterval())
        com.danieldk.splatevpn.ui.widget.SplateAppWidgetProvider.notifyUpdate(context)
        com.danieldk.splatevpn.ui.widget.SplateQuickWidgetProvider.notifyUpdate(context)
    }

}
