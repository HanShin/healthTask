package com.hanshin.healthtask.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.health.InBodyCsvParser
import com.hanshin.healthtask.health.InBodyCsvPreview
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun InBodyCsvReviewDialog(preview: InBodyCsvPreview, onDismiss: () -> Unit, onSave: (InBodyCsvPreview) -> Unit) {
    var zoneText by remember(preview) { mutableStateOf(preview.records.first().timeZoneId) }
    val zone = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()
    val validZone = zone != null && preview.records.all { zone.rules.getValidOffsets(LocalDateTime.parse(it.measuredLocal)).size == 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("인바디 CSV 확인") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("csv-review-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(preview.fileName, style = MaterialTheme.typography.labelMedium)
                    Text("측정 ${preview.records.size}건 · 수치 ${preview.metricCount}개", style = MaterialTheme.typography.titleMedium)
                    Text("값이 없는 항목은 만들지 않습니다. 같은 측정 시각·장비는 중복 저장하지 않고, 비어 있던 항목만 보충합니다. 값이 충돌하면 기존 값을 유지합니다.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    OutlinedTextField(zoneText, { zoneText = it }, label = { Text("측정 시간대") }, singleLine = true,
                        isError = !validZone, modifier = Modifier.fillMaxWidth().testTag("csv-timezone"))
                    Text("파일에는 시간대가 없어 휴대폰 시간대를 기본 적용합니다. 해외에서 측정했다면 해당 지역으로 바꿔 주세요. 예: Asia/Seoul, America/New_York",
                        style = MaterialTheme.typography.bodySmall)
                }
                items(preview.records, key = { it.id }) { record ->
                    Column {
                        Text("${measurementTimeLabel(record.measuredLocal)} · ${record.device}", style = MaterialTheme.typography.titleSmall)
                        Text("수치 ${record.values.size}개 · 값 없음 ${record.missingCount}개", style = MaterialTheme.typography.bodySmall)
                        InBodyRawFields(record.rawFields, initiallyExpanded = preview.records.size == 1)
                    }
                }
                if (preview.warnings.isNotEmpty()) item {
                    Text("확인이 필요한 항목 ${preview.warnings.size}개", color = MaterialTheme.colorScheme.error)
                    preview.warnings.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (preview.warnings.size > 20) Text("나머지는 저장 후 원본 항목에서 확인할 수 있습니다.")
                }
            }
        },
        confirmButton = { TextButton(enabled = validZone, modifier = Modifier.testTag("save-inbody-csv"), onClick = {
            val selectedZone = requireNotNull(zone)
            onSave(preview.copy(records = preview.records.map {
                it.copy(timeZoneId = selectedZone.id, measuredAt = LocalDateTime.parse(it.measuredLocal).atZone(selectedZone).toInstant().toEpochMilli())
            }))
        }) { Text("${preview.records.size}건 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun InBodyCsvRecordCard(record: InBodyRecordEntity, onDelete: (InBodyRecordEntity) -> Unit) {
    val raw = remember(record.rawFieldsJson) { InBodyCsvParser.decodeRaw(record.rawFieldsJson) }
    AppCard(modifier = Modifier.testTag("csv-record-${record.id}")) {
        Text(measurementTimeLabel(record.measuredLocal), style = MaterialTheme.typography.titleMedium)
        Text("${record.device} · 인바디 CSV · ${InBodyCsvParser.values(raw).size}개 항목", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Text("${record.sourceFileName} · ${record.timeZoneId}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        InBodyRawFields(raw)
        TextButton(onClick = { onDelete(record) }, modifier = Modifier.testTag("delete-csv-${record.id}")) { Text("측정 기록 삭제") }
    }
}

@Composable
private fun InBodyRawFields(raw: Map<String, String>, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var showMissing by rememberSaveable { mutableStateOf(false) }
    val fields = raw.filterKeys { it !in setOf("날짜", "측정장비") }
    val present = fields.filterValues { !InBodyCsvParser.isMissing(it) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("csv-expand-details")) {
        Text(if (expanded) "상세 항목 접기" else "상세 항목 ${present.size}개 보기")
    }
    if (expanded) {
        present.entries.groupBy { InBodyCsvParser.metricType(it.key)?.group ?: "추가 원본 항목" }.forEach { (group, entries) ->
            Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            entries.forEach { (header, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(header, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (present.keys.any { InBodyCsvParser.metricType(it)?.isCode == true }) {
            Text("균형 항목은 인바디가 내보낸 원본 코드입니다. 정상·불균형 등으로 임의 해석하지 않습니다.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        val missing = fields.filterValues(InBodyCsvParser::isMissing)
        if (missing.isNotEmpty()) {
            TextButton(onClick = { showMissing = !showMissing }) { Text(if (showMissing) "값 없는 항목 접기" else "값 없는 항목 ${missing.size}개 보기") }
            if (showMissing) missing.forEach { (header, _) ->
                Text("$header · 값 없음", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

private fun measurementTimeLabel(local: String): String = LocalDateTime.parse(local).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"))

internal fun bodyValueText(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
