package com.hanshin.healthtask.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.hanshin.healthtask.data.InBodyGraphPreferences
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.domain.HealthMetricType
import kotlinx.coroutines.launch

@Composable
internal fun HealthTrendChart(
    health: List<HealthMeasurementEntity>, csvRecords: List<InBodyRecordEntity> = emptyList(),
    preferences: InBodyGraphPreferences? = null, onPreferencesChange: (InBodyGraphPreferences) -> Unit = {},
) {
    val measurements = remember(health) { bodyHistoryMeasurements(health) }
    val available = remember(measurements) { bodyHistoryTypes(measurements) }
    val originals = remember(csvRecords) { csvRecords.associateBy { it.id } }
    var localAll by rememberSaveable { mutableStateOf(false) }
    var localHidden by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val settings = preferences ?: InBodyGraphPreferences(localAll, localHidden.toSet())
    fun update(value: InBodyGraphPreferences) {
        if (preferences == null) { localAll = value.showAll; localHidden = value.hiddenMetrics.toList() }
        else onPreferencesChange(value)
    }
    var configuring by rememberSaveable { mutableStateOf(false) }
    val visible = available.filter { it.name !in settings.hiddenMetrics }
    LazyColumn(Modifier.fillMaxSize().testTag("inbody-trend-list"), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "controls") {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("최근", style = MaterialTheme.typography.labelLarge,
                        color = if (!settings.showAll) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = settings.showAll, onCheckedChange = { update(settings.copy(showAll = it)) },
                        modifier = Modifier.padding(horizontal = 6.dp).testTag("body-history-all").semantics { contentDescription = "전체 측정 보기" })
                    Text("전체", style = MaterialTheme.typography.labelLarge,
                        color = if (settings.showAll) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { configuring = true }, contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.testTag("body-display-settings")) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("표시 항목", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(if (settings.showAll) "전체 기록 · 그래프를 좌우로 넘겨보세요" else "항목별 최근 ${RECENT_BODY_MEASUREMENTS}회 · 좌우로 넘겨보세요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("측정 순서대로 표시 · 같은 날 측정도 각각 표시", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (visible.isEmpty()) item { AppCard {
            Text("표시할 항목을 선택해 주세요.")
            TextButton(onClick = { configuring = true }) { Text("표시 항목 설정") }
        } }
        items(visible, key = { it.name }) { type ->
            val series = remember(measurements, type, settings.showAll) { bodyHistorySeries(measurements, type, settings.showAll) }
            BodyHistoryCard(type, series, originals, settings.showAll)
        }
        item(key = "scale-note") {
            Text("항목마다 세로 눈금이 다릅니다. 선은 측정값을 연결한 표시이며 정상·목표 범위나 중간 날짜의 측정값을 뜻하지 않습니다.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (configuring) BodyGraphSettings(available, settings.hiddenMetrics,
        onDismiss = { configuring = false }, onSave = { update(settings.copy(hiddenMetrics = it)); configuring = false })
}

@Composable
private fun BodyGraphSettings(
    available: List<HealthMetricType>, hidden: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit,
) {
    var draft by remember { mutableStateOf(hidden) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("표시 항목 설정") }, text = {
        Column {
            Text("측정값이 있는 항목만 표시됩니다. 원본 기록은 숨겨도 그대로 보관됩니다.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 380.dp).testTag("body-settings-list")) {
                items(available, key = { it.name }) { type ->
                    val checked = type.name !in draft
                    Row(Modifier.fillMaxWidth().clickable { draft = if (checked) draft + type.name else draft - type.name }
                        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { draft = if (it) draft - type.name else draft + type.name },
                            modifier = Modifier.testTag("body-visible-${type.name}"))
                        Text(type.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(metricUnit(type), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TextButton(onClick = { draft = draft - available.map { it.name }.toSet() }) { Text("모두 표시") }
        }
    }, confirmButton = { TextButton(enabled = available.any { it.name !in draft }, onClick = { onSave(draft) },
        modifier = Modifier.testTag("body-settings-save")) { Text("적용") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun BodyHistoryCard(
    type: HealthMetricType, series: List<HealthMeasurementEntity>, originals: Map<String, InBodyRecordEntity>, showAll: Boolean,
) {
    if (series.isEmpty()) return
    var selectedId by rememberSaveable(type.name, showAll) { mutableStateOf<String?>(null) }
    val selectedIndex = series.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: series.lastIndex
    val measurement = series[selectedIndex]
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(type, showAll, series.last().id) { scroll.scrollToItem(selectedIndex) }
    fun select(index: Int, reveal: Boolean = false) {
        selectedId = series[index].id
        if (reveal) scope.launch { scroll.animateScrollToItem(index) }
    }
    val bounds = remember(series, type) { healthTrendBounds(series, type) }
    val color = bodyGraphColor(type)
    val fontScale = LocalDensity.current.fontScale
    val plotHeight = 176.dp
    val top = (24 * fontScale + 8).dp
    val bottom = plotHeight - 16.dp
    var calloutWidth by remember { mutableIntStateOf(0) }
    fun y(value: Double) = top + (bottom - top) * (1 - ((value - bounds.minimum) / bounds.range).coerceIn(0.0, 1.0)).toFloat()
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("body-card-${type.name}")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("${type.label} (${metricUnit(type)})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()) {
              Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF2F3F5),
                modifier = Modifier.offset {
                    val layout = scroll.layoutInfo
                    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
                    val center = item?.let { it.offset + it.size / 2 } ?: (viewport / 2)
                    IntOffset((center - calloutWidth / 2).coerceIn(0, (viewport - calloutWidth).coerceAtLeast(0)), 0)
                }.onSizeChanged { calloutWidth = it.width }.testTag("body-selection-${type.name}")) {
                FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bodyHistoryDateLabel(measurement, originals[measurement.externalRecordId]),
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFF656970))
                    Text("${bodyValueText(measurement.value)} ${metricUnit(type)}", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold, color = Color(0xFF252A30))
                }
              }
            }
            Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Canvas(Modifier.fillMaxWidth().height(plotHeight)) {
                    repeat(5) { i ->
                        val lineY = top.toPx() + (bottom - top).toPx() * i / 4f
                        drawLine(Color(0xFFEDEEF0), Offset(0f, lineY), Offset(size.width, lineY), 1.dp.toPx())
                    }
                }
                LazyRow(state = scroll, horizontalArrangement = if (series.size == 1) Arrangement.Center else Arrangement.Start,
                    modifier = Modifier.fillMaxWidth().testTag("body-plot-${type.name}")) {
                    // Lazy slots retain repeated dates and keep long histories bounded in memory.
                    itemsIndexed(series, key = { _, point -> point.id }) { index, point ->
                        val pointY = y(point.value)
                        val isSelected = index == selectedIndex
                        Column(Modifier.width((52 * fontScale.coerceAtLeast(1f)).dp).clickable { select(index) }
                            .testTag("body-point-${type.name}-$index").semantics {
                                selected = isSelected
                                contentDescription = "${bodyHistoryDateLabel(point, originals[point.externalRecordId])}, ${bodyValueText(point.value)} ${metricUnit(type)}, ${healthSourceLabel(point)}"
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(plotHeight)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val center = size.width / 2
                                    clipRect {
                                        if (isSelected) drawLine(Color(0xFFC8CBD0), Offset(center, 0f), Offset(center, size.height),
                                            strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                                        val path = Path().apply {
                                            if (index > 0) {
                                                val prevY = y(series[index - 1].value).toPx()
                                                moveTo(-center, prevY)
                                                cubicTo(0f, prevY, 0f, pointY.toPx(), center, pointY.toPx())
                                            } else moveTo(center, pointY.toPx())
                                            if (index < series.lastIndex) {
                                                val nextY = y(series[index + 1].value).toPx()
                                                cubicTo(size.width, pointY.toPx(), size.width, nextY, size.width + center, nextY)
                                            }
                                        }
                                        drawPath(path, Color(0xFFDDE0E3), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                                        drawCircle(Color.White, if (isSelected) 7.dp.toPx() else 5.5.dp.toPx(), Offset(center, pointY.toPx()))
                                        drawCircle(color, if (isSelected) 4.8.dp.toPx() else 3.8.dp.toPx(), Offset(center, pointY.toPx()))
                                    }
                                }
                                Text(bodyValueText(point.value), fontSize = 12.sp, lineHeight = 16.sp,
                                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().offset(y = pointY - (24 * fontScale).dp).padding(horizontal = 2.dp))
                            }
                            Text(point.recordDate.substring(5).replace('-', '.'), fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (series.size == 1) "첫 측정 · 1회" else "${series.size}회 · 처음 대비 ${healthTrendDelta(series.first().value, series.last().value)} ${bodyChangeUnit(type)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("body-change-${type.name}"))
                    Text(healthSourceLabel(measurement), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (series.size > 1) {
                    IconButton(enabled = selectedIndex > 0, onClick = { select(selectedIndex - 1, true) },
                        modifier = Modifier.size(40.dp).testTag("body-previous-${type.name}")) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "${type.label} 이전 측정")
                    }
                    Text("${selectedIndex + 1}/${series.size}", style = MaterialTheme.typography.labelSmall)
                    IconButton(enabled = selectedIndex < series.lastIndex, onClick = { select(selectedIndex + 1, true) },
                        modifier = Modifier.size(40.dp).testTag("body-next-${type.name}")) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "${type.label} 다음 측정")
                    }
                }
            }
        }
    }
}

private fun bodyGraphColor(type: HealthMetricType): Color = when (type) {
    HealthMetricType.INBODY_SCORE -> Color(0xFF78848F)
    HealthMetricType.WEIGHT_KG -> Color(0xFF688CB0)
    HealthMetricType.BODY_FAT_PERCENT, HealthMetricType.BODY_FAT_MASS_KG -> Color(0xFFC19A60)
    else -> Color(0xFF51B6A0)
}
private fun bodyChangeUnit(type: HealthMetricType) = if (type.unit == "%") "%p" else metricUnit(type)
