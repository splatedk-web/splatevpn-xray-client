package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.TrafficDay
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrafficStatisticsSheet(days: List<TrafficDay>, onClear: () -> Unit, onDismiss: () -> Unit) {
    val text = toolsText()
    val colors = LocalAppThemeColors.current
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    var anchorEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var selectedEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var month by rememberSaveable { mutableStateOf(false) }
    var metric by rememberSaveable { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    val anchor = LocalDate.ofEpochDay(anchorEpoch)
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val monthStart = anchor.withDayOfMonth(1)
    val history = remember(days) { days.associateBy { it.date } }
    val chartDates = remember(anchor, month) {
        if (month) (0 until anchor.lengthOfMonth()).map { monthStart.plusDays(it.toLong()) }
        else (0L..6L).map { anchor.minusDays(6 - it) }
    }
    val chartDays = chartDates.map { history[it] ?: TrafficDay(it) }
    val totals = if (month) days.filter { it.date.year == anchor.year && it.date.month == anchor.month }
        else listOfNotNull(history[selected])
    val rx = totals.sumOf { it.rx }
    val tx = totals.sumOf { it.tx }
    val timeMs = totals.sumOf { it.connectedMs }
    fun navigate(date: LocalDate) { anchorEpoch = date.toEpochDay(); selectedEpoch = anchorEpoch }
    fun duration(value: Long) = text("${value / 3_600_000} ч ${(value / 60_000) % 60} мин", "${value / 3_600_000} h ${(value / 60_000) % 60} min")
    val dayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", text.locale)
    val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", text.locale)

    ToolsSheet(text("Статистика трафика", "Traffic statistics"), onDismiss) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(text("День", "Day"), !month, Modifier.weight(1f)) { month = false; navigate(selected.coerceAtMost(today)) }
            PeriodChip(text("Месяц", "Month"), month, Modifier.weight(1f)) { month = true; navigate(selected) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigate(if (month) monthStart.minusMonths(1) else selected.minusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, text("Предыдущий период", "Previous period"), tint = colors.textPrimary)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((if (month) anchor.format(monthFormat) else selected.format(dayFormat)).replaceFirstChar { it.titlecase(text.locale) },
                    color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
                Text(if (month) text("Итоги месяца", "Monthly totals") else text("Итоги дня", "Daily totals"), color = colors.textSecondary, fontSize = 11.sp)
            }
            IconButton(enabled = if (month) monthStart < today.withDayOfMonth(1) else selected < today,
                onClick = { navigate(if (month) monthStart.plusMonths(1) else selected.plusDays(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, text("Следующий период", "Next period"), tint = colors.textPrimary.copy(alpha = if ((if (month) monthStart < today.withDayOfMonth(1) else selected < today)) 1f else 0.3f))
            }
            IconButton(onClick = { showCalendar = true }) {
                Icon(Icons.Default.CalendarMonth, text("Выбрать дату", "Choose date"), tint = colors.accentCyan)
            }
        }
        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, accentBorder = colors.accentCyan).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text("ВСЕГО ТРАФИКА", "TOTAL TRAFFIC"), color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp)
            AnimatedContent(targetState = trafficBytes(rx + tx), label = "traffic_total") { amount ->
                Text(amount, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 32.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(15.dp))
                Text(text("Подключение: ", "Connected: ") + duration(timeMs), color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrafficMetricCard(text("Скачано", "Downloaded"), trafficBytes(rx), Icons.Default.SouthWest, colors.accentCyan, Modifier.weight(1f))
            TrafficMetricCard(text("Отправлено", "Uploaded"), trafficBytes(tx), Icons.Default.NorthEast, colors.accentSecondary, Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val selectedDay = history[selected] ?: TrafficDay(selected)
            val selectedTraffic = when (metric) { "rx" -> selectedDay.rx; "tx" -> selectedDay.tx; else -> selectedDay.rx + selectedDay.tx }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (month) text("По дням", "By day") else text("7 дней", "7 days"), modifier = Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(selected.format(DateTimeFormatter.ofPattern("d MMM", text.locale)) + " · " + trafficBytes(selectedTraffic), color = colors.accentCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to text("Всё", "All"), "rx" to text("Скачано", "Down"), "tx" to text("Отправлено", "Up")).forEach { (id, label) ->
                    PeriodChip(label, metric == id, Modifier.weight(1f)) { metric = id }
                }
            }
            TrafficChart(chartDays, selected, today, metric) { selectedEpoch = it.toEpochDay() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val labels = if (month) listOf(chartDates.first(), chartDates[chartDates.size / 2], chartDates.last()) else listOf(chartDates.first(), chartDates.last())
                labels.forEach { Text(it.format(DateTimeFormatter.ofPattern("d MMM", text.locale)), color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (metric != "tx") ChartLegend(text("Скачано", "Downloaded"), colors.accentCyan)
                if (metric != "rx") ChartLegend(text("Отправлено", "Uploaded"), colors.accentSecondary)
            }
            Text(text("Коснитесь столбца или проведите по графику", "Tap a bar or drag across the chart"), color = colors.textSecondary, fontSize = 11.sp)
        }
        val dayListState = rememberLazyListState()
        val selectedIndex = chartDates.indexOf(selected).coerceAtLeast(0)
        LaunchedEffect(selectedIndex, chartDates) { dayListState.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0)) }
        LazyRow(state = dayListState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chartDates, key = { it.toEpochDay() }) { date ->
                val active = date == selected
                Column(Modifier.widthIn(min = 48.dp).liquidGlass(cornerRadius = 12, accentBorder = if (active) colors.accentCyan else null)
                    .selectable(selected = active, enabled = date <= today, role = Role.Tab, onClick = { selectedEpoch = date.toEpochDay() })
                    .padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("EE", text.locale)), color = colors.textSecondary, fontSize = 10.sp)
                    Text("${date.dayOfMonth}", color = (if (active) colors.accentCyan else colors.textPrimary).copy(alpha = if (date <= today) 1f else 0.3f), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (month) {
            val day = history[selected] ?: TrafficDay(selected)
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selected.format(dayFormat), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("↓ ${trafficBytes(day.rx)}   ↑ ${trafficBytes(day.tx)}", color = colors.accentCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(text("Подключение: ", "Connected: ") + duration(day.connectedMs), color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        if (totals.isEmpty()) {
            Text(text("За этот период данных пока нет. Статистика появится после подключения VPN.", "No data for this period yet. Statistics appear after connecting VPN."), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { navigate(today) }) { Text(text("К текущему периоду", "Current period"), color = colors.accentCyan) }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteOutline, text("Очистить историю", "Clear history"), tint = colors.textTertiary) }
        }
        Text(text("Трафик VPN-интерфейса, включая прямые маршруты внутри него. История хранится 400 дней и собирается с этой версии. При аварийном завершении последние 15 секунд могут не сохраниться.",
            "VPN interface traffic, including its direct routes. History is retained for 400 days and starts with this version. A crash may lose the last 15 seconds."), color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(text("Очистить статистику?", "Clear statistics?")) },
        text = { Text(text("Вся история трафика и времени подключения будет удалена.", "All traffic and connection-time history will be deleted.")) },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text(text("Удалить", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(text("Отмена", "Cancel")) } })
    if (showCalendar) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= today
                override fun isSelectableYear(year: Int) = year <= today.year
            })
        DatePickerDialog(onDismissRequest = { showCalendar = false },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { navigate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                showCalendar = false
            }) { Text(text("Выбрать", "Select")) } },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text(text("Отмена", "Cancel")) } }) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalAppThemeColors.current
    Box(modifier.liquidGlass(cornerRadius = 12, accentBorder = if (selected) colors.accentCyan else null,
        backgroundColor = if (selected) colors.accentCyan.copy(alpha = 0.12f) else null)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.accentCyan else colors.textSecondary, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrafficMetricCard(title: String, amount: String, icon: ImageVector, accent: Color, modifier: Modifier) {
    val colors = LocalAppThemeColors.current
    Column(modifier.liquidGlass(cornerRadius = 16).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Text(title, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    val colors = LocalAppThemeColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, color = colors.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun TrafficChart(days: List<TrafficDay>, selected: LocalDate, today: LocalDate, metric: String, onSelect: (LocalDate) -> Unit) {
    val colors = LocalAppThemeColors.current
    val text = toolsText()
    val callback by rememberUpdatedState(onSelect)
    val visibleRx = days.map { if (metric == "tx") 0L else it.rx }
    val visibleTx = days.map { if (metric == "rx") 0L else it.tx }
    val maximum = days.indices.maxOfOrNull { visibleRx[it] + visibleTx[it] } ?: 0L
    var revealed by remember(days.map { it.date }, metric) { mutableStateOf(false) }
    LaunchedEffect(days.map { it.date }, metric) { revealed = true }
    val growth by animateFloatAsState(if (revealed) 1f else 0f, tween(350), label = "traffic_chart_growth")
    val dates = days.map { it.date }
    fun selectAt(x: Float, width: Int) {
        if (width <= 0) return
        val index = (x / width * days.size).toInt().coerceIn(days.indices)
        if (days[index].date <= today) callback(days[index].date)
    }
    Column {
        Text(trafficBytes(maximum), color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Canvas(Modifier.fillMaxWidth().height(170.dp)
            .semantics {
                contentDescription = text("Трафик по дням. Выберите день на графике", "Daily traffic. Select a day on the chart")
                progressBarRangeInfo = ProgressBarRangeInfo(dates.indexOf(selected).coerceAtLeast(0).toFloat(), 0f..days.lastIndex.toFloat(), (days.size - 2).coerceAtLeast(0))
                setProgress { value ->
                    val date = dates[value.toInt().coerceIn(dates.indices)]
                    if (date <= today) { callback(date); true } else false
                }
            }
            .pointerInput(dates, today) { detectTapGestures { selectAt(it.x, size.width) } }
            .pointerInput(dates, today) {
                detectHorizontalDragGestures(onDragStart = { selectAt(it.x, size.width) }) { change, _ ->
                    change.consume(); selectAt(change.position.x, size.width)
                }
            }) {
            val bottom = size.height - 6.dp.toPx()
            val top = 12.dp.toPx()
            val height = bottom - top
            val slot = size.width / days.size
            val barWidth = slot * if (days.size > 7) 0.62f else 0.55f
            for (line in 0..3) {
                val y = top + height * line / 3
                drawLine(colors.borderSubtle.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            days.forEachIndexed { index, day ->
                val x = slot * index + (slot - barWidth) / 2
                val active = day.date == selected
                if (active) drawRoundRect(colors.accentCyan.copy(alpha = 0.08f), Offset(slot * index, 0f), Size(slot, size.height), CornerRadius(6.dp.toPx()))
                val scale = height / maximum.coerceAtLeast(1).toFloat() * growth
                val rxHeight = visibleRx[index] * scale
                val txHeight = visibleTx[index] * scale
                val totalHeight = rxHeight + txHeight
                drawRoundRect(colors.textTertiary.copy(alpha = 0.16f), Offset(x, bottom - 3.dp.toPx()), Size(barWidth, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                val opacity = if (active) 1f else 0.65f
                if (rxHeight > 0) drawRect(Brush.verticalGradient(listOf(colors.accentCyan.copy(alpha = opacity), colors.accentCyan.copy(alpha = opacity * 0.45f))), Offset(x, bottom - rxHeight), Size(barWidth, rxHeight))
                if (txHeight > 0) drawRect(Brush.verticalGradient(listOf(colors.accentSecondary.copy(alpha = opacity), colors.accentSecondary.copy(alpha = opacity * 0.55f))), Offset(x, bottom - totalHeight), Size(barWidth, txHeight))
                if (active) drawCircle(colors.accentCyan, 3.dp.toPx(), Offset(slot * (index + 0.5f), (bottom - totalHeight - 6.dp.toPx()).coerceAtLeast(4.dp.toPx())))
            }
        }
    }
}
