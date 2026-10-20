package com.hanshin.healthtask.health

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hanshin.healthtask.domain.HealthMetricType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class InBodyCsvRecord(
    val id: String,
    val measuredLocal: String,
    val measuredAt: Long,
    val timeZoneId: String,
    val device: String,
    val rawFields: Map<String, String>,
) {
    val values: Map<HealthMetricType, Double> get() = InBodyCsvParser.values(rawFields)
    val missingCount: Int get() = rawFields.count { it.key !in setOf("날짜", "측정장비") && InBodyCsvParser.isMissing(it.value) }
}

data class InBodyCsvPreview(val fileName: String, val records: List<InBodyCsvRecord>, val warnings: List<String>) {
    val metricCount: Int get() = records.sumOf { it.values.size }
}

/** No inferred measurements or code meanings. Raw strings (including missing markers) survive import. */
object InBodyCsvParser {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val dateFormats = listOf("uuuuMMddHHmmss", "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd'T'HH:mm:ss")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    private val types = HealthMetricType.entries.associateBy { normalizeHeader(it.csvHeader) }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "CSV 파일은 5MB 이하로 선택해 주세요." }
        val charset = when {
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        fun decodeWith(value: Charset) = value.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        return runCatching { decodeWith(charset) }.getOrElse {
            require(charset == Charsets.UTF_8) { "CSV 문자 인코딩을 읽을 수 없습니다." }
            runCatching { decodeWith(Charset.forName("MS949")) }.getOrElse { error("CSV 문자 인코딩을 읽을 수 없습니다.") }
        }
    }

    fun parse(text: String, fileName: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): InBodyCsvPreview {
        require(text.length <= MAX_BYTES) { "CSV 파일이 너무 큽니다." }
        val rows = csvRows(text.removePrefix("\uFEFF"))
        require(rows.size >= 2) { "CSV에 측정 기록이 없습니다." }
        val headers = rows.first().map { it.trim() }
        require(headers.none { it.isBlank() } && headers.map(::normalizeHeader).distinct().size == headers.size) {
            "CSV의 항목명이 비어 있거나 중복됩니다."
        }
        require("날짜" in headers && "측정장비" in headers) { "인바디 앱에서 내보낸 CSV를 선택해 주세요. 날짜와 측정장비 항목이 필요합니다." }
        val warnings = mutableListOf<String>()
        val records = rows.drop(1).mapIndexed { index, cells ->
            val line = index + 2
            require(cells.size == headers.size) { "${line}번째 기록의 열 수가 맞지 않습니다. 파일은 저장하지 않았습니다." }
            val raw = headers.zip(cells).toMap()
            val dateText = raw.getValue("날짜").trim()
            val date = parseDate(dateText)
                ?: error("${line}번째 기록의 측정 날짜·시간을 읽을 수 없습니다.")
            require(!date.toLocalDate().isAfter(today)) { "${line}번째 기록의 측정일이 미래입니다. 파일과 휴대폰 날짜를 확인해 주세요." }
            require(zone.rules.getValidOffsets(date).size == 1) { "${line}번째 측정 시각은 선택한 시간대에서 모호합니다. 원본 시간대를 확인해 주세요." }
            val device = raw.getValue("측정장비").trim()
            require(!isMissing(device)) { "${line}번째 기록의 측정장비가 없습니다." }
            val record = InBodyCsvRecord(recordId(date.toString(), device), date.toString(),
                date.atZone(zone).toInstant().toEpochMilli(), zone.id, device, raw)
            raw.forEach { (header, value) ->
                val type = metricType(header)
                if (type != null && !isMissing(value) && numericValue(type, value) == null) {
                    warnings += "${line}번째 기록 · $header: 수치로 읽지 못해 원본에만 보관합니다."
                }
            }
            require(raw.any { it.key !in setOf("날짜", "측정장비") && !isMissing(it.value) }) { "${line}번째 기록에 측정값이 없습니다." }
            record
        }
        require(records.map { it.id }.distinct().size == records.size) { "파일 안에 같은 시각·장비의 기록이 중복됩니다. 중복 행을 확인해 주세요." }
        return InBodyCsvPreview(fileName.take(255), records, warnings)
    }

    fun recordId(measuredLocal: String, device: String): String = "inbody-csv-" +
        MessageDigest.getInstance("SHA-256").digest("$measuredLocal\u0000$device".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun parseDate(value: String): LocalDateTime? = dateFormats.firstNotNullOfOrNull {
        runCatching { LocalDateTime.parse(value.trim(), it) }.getOrNull()
    }

    fun metricType(header: String): HealthMetricType? = types[normalizeHeader(header)]
    fun isMissing(value: String): Boolean = value.trim() in setOf("", "-", "—", "–", "N/A", "n/a")
    fun values(raw: Map<String, String>): Map<HealthMetricType, Double> = raw.mapNotNull { (header, value) ->
        metricType(header)?.let { type -> numericValue(type, value)?.let { type to it } }
    }.toMap()
    fun numericValue(type: HealthMetricType, raw: String): Double? = raw.trim().toDoubleOrNull()?.takeIf {
        it.isFinite() && it >= 0.0 && (!type.isCode || it % 1.0 == 0.0)
    }
    fun encodeRaw(raw: Map<String, String>): String = Gson().toJson(raw)
    fun decodeRaw(json: String): Map<String, String> = JsonParser.parseString(json).asJsonObject.entrySet().associate { (key, value) ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "인바디 원본 항목 형식이 올바르지 않습니다." }
        key to value.asString
    }
    private fun normalizeHeader(header: String) = header.replace(" ", "").replace("²", "2").lowercase()

    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        fun endField() {
            require(row.size < 128 && field.length <= 4096) { "CSV 항목 수 또는 값의 길이가 제한을 넘었습니다." }
            row.add(field.toString()); field.setLength(0); closedQuote = false
        }
        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows.add(row)
            require(rows.size <= 5001) { "한 번에 최대 5,000개 측정 기록을 가져올 수 있습니다." }
            row = mutableListOf()
        }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closedQuote = true }
                } else field.append(char)
            } else when (char) {
                '"' -> { require(field.isEmpty() && !closedQuote) { "CSV 따옴표 형식이 올바르지 않습니다." }; quoted = true }
                ',' -> endField()
                '\r', '\n' -> { if (char == '\r' && index < text.length && text[index] == '\n') index++; endRow() }
                else -> { require(!closedQuote || char == ' ' || char == '\t') { "CSV 따옴표 뒤에 잘못된 문자가 있습니다." }; if (!closedQuote) field.append(char) }
            }
            require(field.length <= 4096) { "CSV 값의 길이가 제한을 넘었습니다." }
        }
        require(!quoted) { "CSV 따옴표가 닫히지 않았습니다." }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return rows
    }
}
