package com.hanshin.healthtask.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementDateField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    val valid = parsed != null && !parsed.isAfter(LocalDate.now())
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(),
        label = { Text("측정일") }, singleLine = true, isError = !valid,
        supportingText = if (!valid) ({ Text("오늘 또는 과거 날짜를 YYYY-MM-DD로 입력해 주세요.") }) else null,
        trailingIcon = { IconButton(onClick = { showPicker = true }) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = "측정일 선택")
        } },
    )
    if (showPicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (parsed?.takeUnless { it.isAfter(LocalDate.now()) } ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
                override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
            },
        )
        DatePickerDialog(onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                showPicker = false
            }) { Text("선택") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } },
        ) { DatePicker(picker) }
    }
}
