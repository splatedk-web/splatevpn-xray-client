package com.danieldk.splatevpn.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class TrafficDay(val date: LocalDate, val rx: Long = 0, val tx: Long = 0, val connectedMs: Long = 0)

/** Splits a sample at local midnight, preserving every byte and millisecond. */
internal fun splitTrafficSample(endMs: Long, durationMs: Long, rx: Long, tx: Long, zone: ZoneId): List<TrafficDay> {
    val duration = durationMs.coerceAtLeast(1)
    var cursor = endMs - duration
    var remainingRx = rx.coerceAtLeast(0)
    var remainingTx = tx.coerceAtLeast(0)
    val result = mutableListOf<TrafficDay>()
    while (cursor < endMs) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val next = minOf(endMs, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val slice = next - cursor
        val last = next == endMs
        val sliceRx = if (last) remainingRx else (rx.coerceAtLeast(0).toDouble() * slice / duration).toLong()
        val sliceTx = if (last) remainingTx else (tx.coerceAtLeast(0).toDouble() * slice / duration).toLong()
        result += TrafficDay(date, sliceRx, sliceTx, slice)
        remainingRx -= sliceRx
        remainingTx -= sliceTx
        cursor = next
    }
    return result
}

@Singleton
class TrafficHistory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("traffic_history", Context.MODE_PRIVATE)
    private val _days = MutableStateFlow(load())
    val days = _days.asStateFlow()
    private var lastPersistMs = 0L

    private fun load(): List<TrafficDay> = try {
        val array = JSONArray(prefs.getString("days", "[]"))
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            TrafficDay(LocalDate.parse(row.getString("date")), row.getLong("rx"), row.getLong("tx"), row.getLong("ms"))
        }.sortedByDescending { it.date }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun record(endMs: Long, durationMs: Long, rx: Long, tx: Long) {
        val zone = ZoneId.systemDefault()
        val rows = _days.value.associateBy { it.date }.toMutableMap()
        splitTrafficSample(endMs, durationMs, rx, tx, zone).forEach { sample ->
            val old = rows[sample.date] ?: TrafficDay(sample.date)
            rows[sample.date] = old.copy(rx = old.rx + sample.rx, tx = old.tx + sample.tx,
                connectedMs = old.connectedMs + sample.connectedMs)
        }
        val cutoff = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate().minusDays(399)
        _days.value = rows.values.filter { it.date >= cutoff }.sortedByDescending { it.date }
        if (endMs - lastPersistMs >= 15_000L || endMs < lastPersistMs) {
            persist()
            lastPersistMs = endMs
        }
    }

    @Synchronized
    fun clear() { _days.value = emptyList(); persist() }

    @Synchronized
    fun flush() = persist()

    private fun persist() {
        val array = JSONArray()
        _days.value.forEach { row -> array.put(JSONObject().put("date", row.date.toString())
            .put("rx", row.rx).put("tx", row.tx).put("ms", row.connectedMs)) }
        prefs.edit().putString("days", array.toString()).apply()
    }
}
