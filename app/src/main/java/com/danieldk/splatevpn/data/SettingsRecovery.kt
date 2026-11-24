package com.danieldk.splatevpn.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Xml
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File

internal data class SettingsRecoveryNotice(val needsDecision: Boolean, val recovered: Boolean, val message: String)

/** Restores before SettingsManager initializes its state flows. Quarantine never writes bad originals. */
internal class SettingsRecovery(private val context: Context, private val name: String = "splate_prefs") {
    private val _notice = MutableStateFlow<SettingsRecoveryNotice?>(null)
    val notice = _notice.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pending: Job? = null
    private val directory = File(context.noBackupFilesDir, "settings-recovery/$name")
    private val snapshot = AtomicFile(File(directory, "last-good.json"))
    private var original: SharedPreferences? = null
    private var quarantine: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun open(preferences: SharedPreferences): SharedPreferences {
        // Context wrappers used by instrumentation may supply another preference file. Never touch the real user's snapshot.
        if (preferences !== context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)) return preferences
        original = preferences
        val values = preferences.all
        val primary = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        val invalid = values.isNotEmpty() && !validTypes(values) || primary.exists() && !validXml(primary)
        if (invalid || !primary.exists() && values.isEmpty() && snapshot.baseFile.exists()) {
            val restored = runCatching { PreferenceValues.decode(snapshot.openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
                .getOrNull()?.takeIf(::validTypes)
            preserve(primary)
            if (restored != null) {
                write(preferences, restored)
                _notice.value = SettingsRecoveryNotice(false, true, "Settings restored from the last working snapshot.")
            } else {
                // A separate preference file holds temporary defaults until the user explicitly accepts a reset.
                quarantine = context.getSharedPreferences("${name}_recovery_preview", Context.MODE_PRIVATE).also {
                    check(it.edit().clear().putBoolean("has_initialized_preset_subscriptions_v1", true)
                        .putBoolean("has_initialized_universal_whitelist_v1", true).commit())
                }
                _notice.value = SettingsRecoveryNotice(true, false, "Settings could not be recovered. Original files were preserved.")
                return quarantine!!
            }
        }
        observe(preferences)
        return preferences
    }

    fun acceptDefaults(): SharedPreferences {
        val destination = checkNotNull(original)
        val values = checkNotNull(quarantine).all
        write(destination, values)
        quarantine = null
        _notice.value = null
        observe(destination)
        return destination
    }

    fun dismissNotice() { if (_notice.value?.needsDecision != true) _notice.value = null }

    internal fun close() {
        scope.cancel()
        listener?.let { original?.unregisterOnSharedPreferenceChangeListener(it) }
    }

    private fun observe(preferences: SharedPreferences) {
        listener?.let { preferences.unregisterOnSharedPreferenceChangeListener(it) }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule(preferences) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        schedule(preferences)
    }

    private fun schedule(preferences: SharedPreferences) = synchronized(lock) {
        pending?.cancel()
        pending = scope.launch {
            delay(300)
            val values = preferences.all
            if (!validTypes(values)) return@launch
            val bytes = runCatching { PreferenceValues.encode(values).toByteArray() }.getOrNull() ?: return@launch
            synchronized(lock) {
                directory.mkdirs()
                var stream: java.io.FileOutputStream? = null
                try { stream = snapshot.startWrite(); stream.write(bytes); snapshot.finishWrite(stream) }
                catch (_: Exception) { snapshot.failWrite(stream) }
            }
        }
    }

    private fun preserve(primary: File) {
        directory.mkdirs()
        if (primary.isFile) primary.copyTo(File(directory, "original-${System.currentTimeMillis()}.xml"))
        if (snapshot.baseFile.isFile) snapshot.baseFile.copyTo(File(directory, "snapshot-${System.currentTimeMillis()}.json"))
    }

    private fun validXml(file: File): Boolean = runCatching {
        require(file.length() <= 4 * 1024 * 1024)
        file.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var root = false
            var rootClosed = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 1) {
                    require(!root && parser.name == "map"); root = true
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 1) {
                    require(parser.name == "map"); rootClosed = true
                }
                parser.next()
            }
            require(root && rootClosed)
        }
    }.isSuccess

    private fun validTypes(values: Map<String, *>): Boolean = values.all { (key, value) ->
        when (expectedTypes[key]) {
            "Boolean" -> value is Boolean
            "String" -> value is String
            "Int" -> value is Int
            "Long" -> value is Long
            "Float" -> value is Float && value.isFinite()
            "StringSet" -> value is Set<*> && value.all { it is String }
            else -> value is String || value is Boolean || value is Int || value is Long || value is Float && value.isFinite() || value is Set<*> && value.all { it is String }
        }
    }

    companion object {
        // Kept explicit so a malformed XML string cannot crash a Boolean/Int getter during startup.
        private val expectedTypes = buildMap<String, String> {
            put("ai_dns_auto_switch", "Boolean")
            put("regional_presets_enabled", "Boolean")
            put("source_downloads_via_vpn", "Boolean")
            put("updates_wifi_only", "Boolean")
            put("checks_wifi_only", "Boolean")
            put("regional_settings_version", "Int")
            put("onboarding_version", "Int")
            put("lan_interface", "String")
            put("lan_auth_enabled", "Boolean")
            put("lan_password", "String")
            put("local_proxy_auth_enabled", "Boolean")
            put("local_proxy_username", "String")
            put("local_proxy_password", "String")
            listOf("ad_blocker", "ai_tls_fragment", "auto_connect_on_boot", "auto_connect_on_open", "auto_select_server", "block_ipv6", "block_quic", "bypass_ai", "enable_sniffing", "fake_dns", "folders_default_applied", "glass_blur_enabled", "ui_animations_enabled", "ui_decorative_background_enabled", "ui_glass_highlights_enabled", "has_initialized_preset_subscriptions_v1", "has_initialized_universal_whitelist_v1", "is_global_app_proxy", "mux_enabled", "route_only_sniffing", "servers_grouped", "servers_sort_by_ping", "share_lan_enabled", "tls_fragment_enabled", "tls_mixed_sni").forEach { put(it, "Boolean") }
            listOf("balancer_cooldown_seconds", "balancer_failures", "balancer_health_seconds", "balancer_improvement_percent", "balancer_optimization_minutes", "mux_concurrency").forEach { put(it, "Int") }
            listOf("ai_dns_provider", "ai_routing_mode", "app_language", "app_theme", "balancer_mode", "custom_doh_url", "custom_routes_json", "dns_provider", "per_app_mode", "ping_mode", "routing_mode", "routing_region", "selected_server_name", "servers_filter_sub_id", "servers_view_mode", "sub_auto_update_interval", "sub_url", "tls_fragment_interval", "tls_fragment_length", "tls_fragment_packets", "tls_fragment_preset", "xhttp_mode").forEach { put(it, "String") }
            listOf("ad_block_allowlist", "per_app_packages", "servers_expanded_groups").forEach { put(it, "StringSet") }
            put("glass_transparency", "Float"); put("last_sub_update_time", "Long")
            put("connection_profiles_v1", "String"); put("connection_profile_undo_v1", "String")
        }
        fun write(preferences: SharedPreferences, values: Map<String, *>) {
            val editor = preferences.edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(editor.commit()) { "Could not persist recovered settings" }
        }
    }
}
