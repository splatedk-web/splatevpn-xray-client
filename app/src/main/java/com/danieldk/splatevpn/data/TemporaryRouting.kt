package com.danieldk.splatevpn.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TemporaryException(val id: String, val target: String, val app: Boolean, val outbound: String,
    val createdAt: Long, val expiresAt: Long) {
    fun active(now: Long) = now >= createdAt && now < expiresAt
}

@Singleton
class TemporaryRouting @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("temporary_routing", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _entries = MutableStateFlow(read().filter { it.active(System.currentTimeMillis()) })
    val entries = _entries.asStateFlow()
    init {
        // App routing belongs to the Apps screen. Remove obsolete timed bypasses.
        if (_entries.value.any { it.app }) store(_entries.value.filterNot { it.app })
    }
    private fun read(): List<TemporaryException> = runCatching {
        gson.fromJson<List<TemporaryException>>(preferences.getString("entries", "[]"), object : TypeToken<List<TemporaryException>>() {}.type)
            .orEmpty().take(64).filter { it.target.length in 1..253 && it.outbound in setOf("direct", "proxy", "block") }
    }.getOrDefault(emptyList())
    @Synchronized fun add(target: String, app: Boolean, outbound: String, durationMinutes: Int) {
        require(!app) { "Use the Apps screen for application routing" }
        require(durationMinutes in setOf(15, 60, 1440))
        require(outbound in setOf("direct", "proxy", "block") && (!app || outbound == "direct"))
        val normalized = if (app) target.trim().also {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
            require(it != context.packageName)
            context.packageManager.getApplicationInfo(it, 0)
        } else requireNotNull(AdFilterCompiler.normalizeDomain(target)) { "Enter a valid domain" }
        val now = System.currentTimeMillis()
        val current = _entries.value.filter { it.active(now) && !(it.target == normalized && it.app == app) }
        require(current.size < 64)
        store(current + TemporaryException(UUID.randomUUID().toString(), normalized, app, outbound, now, now + durationMinutes * 60_000L))
    }
    @Synchronized fun remove(id: String) = store(_entries.value.filterNot { it.id == id })
    @Synchronized fun prune() {
        val valid = _entries.value.filter { it.active(System.currentTimeMillis()) }
        if (valid != _entries.value) store(valid)
    }
    private fun store(entries: List<TemporaryException>) {
        check(preferences.edit().putString("entries", gson.toJson(entries)).commit())
        _entries.value = entries
    }
    fun domainRoutes(base: List<Pair<String, String>>): List<Pair<String, String>> {
        val overrides = _entries.value.filter { !it.app && it.active(System.currentTimeMillis()) }
        return overrides.map { "full:${it.target}" to it.outbound } + base
    }
    fun bypassApps(): Set<String> = _entries.value.filter { it.app && it.active(System.currentTimeMillis()) }.map { it.target }.toSet()
}
