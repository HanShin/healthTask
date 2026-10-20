package com.hanshin.healthtask.health

import com.hanshin.healthtask.domain.HealthMetricType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class InBodyCsvParserTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 9, 24)
    private fun parse(text: String, zone: ZoneId = this.zone) = InBodyCsvParser.parse(text, "test.csv", zone, today)

    @Test fun `all 43 source measurements map independently with exact units`() {
        val types = HealthMetricType.entries
        assertEquals(43, types.size)
        val csv = "날짜,측정장비," + types.joinToString(",") { it.csvHeader } + "\n20260924092730,H40," +
            types.joinToString(",") { if (it.isCode) "0" else "1.234" }
        val record = parse(csv).records.single()
        assertEquals(43, record.values.size)
        assertEquals(45, record.rawFields.size)
        assertEquals(0.0, record.values.getValue(HealthMetricType.UPPER_BALANCE_CODE), 0.0)
        assertEquals(1.234, record.values.getValue(HealthMetricType.ECW_RATIO), 0.0)
        assertEquals("cm²", HealthMetricType.VISCERAL_FAT_AREA_CM2.unit)
        assertEquals("level", HealthMetricType.VISCERAL_FAT_LEVEL.unit)
        assertEquals("2026-09-24T09:27:30", record.measuredLocal)
        assertEquals("2026-09-24T00:27:30Z", java.time.Instant.ofEpochMilli(record.measuredAt).toString())
    }

    @Test fun `bom crlf reordered columns blanks and missing values preserve originals`() {
        val record = parse("\uFEFF인바디점수,날짜,측정장비,체중(kg),체수분(L),상체좌우\r\n80.0,20260924092730,H40,70.25,-,0\r\n\r\n").records.single()
        assertEquals(3, record.values.size)
        assertEquals(1, record.missingCount)
        assertEquals("-", record.rawFields["체수분(L)"])
        assertFalse(record.values.containsKey(HealthMetricType.BODY_WATER_L))
    }

    @Test fun `quoted commas quotes and newlines in unknown fields survive`() {
        val record = parse("날짜,측정장비,체중(kg),추가 메모\n20260924092730,H40,70,\"첫줄, \"\"메모\"\"\n두번째줄\"").records.single()
        assertEquals("첫줄, \"메모\"\n두번째줄", record.rawFields["추가 메모"])
        assertEquals(record.rawFields, InBodyCsvParser.decodeRaw(InBodyCsvParser.encodeRaw(record.rawFields)))
    }

    @Test fun `all missing markers are unavailable and never numeric zero`() {
        listOf("", "-", "—", "N/A").forEach {
            val record = parse("날짜,측정장비,체중(kg),BMI(kg/m²)\n20260924092730,H40,70,$it").records.single()
            assertEquals(1, record.values.size)
        }
    }

    @Test fun `invalid numbers remain raw with warnings rather than disappearing`() {
        listOf("NaN", "Infinity", "-4", "잘못된 값").forEach { value ->
            val result = parse("날짜,측정장비,체중(kg)\n20260924092730,H40,$value")
            assertTrue(result.records.single().values.isEmpty())
            assertEquals(value, result.records.single().rawFields["체중(kg)"])
            assertEquals(1, result.warnings.size)
        }
    }

    @Test fun `same local timestamp and equipment keep id across timezones`() {
        val csv = "날짜,측정장비,체중(kg)\n20260924092730,H40,70"
        val korea = parse(csv).records.single()
        val utc = parse(csv, ZoneId.of("UTC")).records.single()
        assertEquals(korea.id, utc.id)
        assertNotEquals(korea.measuredAt, utc.measuredAt)
    }

    @Test fun `multiple measurements on same day remain separate`() {
        val result = parse("날짜,측정장비,체중(kg)\n20260924092730,H40,70\n20260924120000,H40,71")
        assertEquals(2, result.records.size)
        assertNotEquals(result.records[0].id, result.records[1].id)
    }

    @Test fun `malformed headers rows timestamps and duplicate identities reject whole file`() {
        val header = "날짜,측정장비,체중(kg)\n"
        val bad = listOf(
            header + "20260230092730,H40,70", header + "20260925092730,H40,70",
            header + "20260924092730,H40", header + "20260924092730,H40,70,80",
            header + "20260924092730,H40,\"70", header + "20260924092730,H40,\"70\"x",
            header + "20260924092730,H40,70\n20260924092730,H40,71",
            "날짜,측정장비,체중(kg),체중(kg)\n20260924092730,H40,70,71",
            "날짜,체중(kg)\n20260924092730,70", header,
        )
        bad.forEach { assertTrue(runCatching { parse(it) }.isFailure) }
    }

    @Test fun `utf8 utf16 and korean legacy encodings decode`() {
        val csv = "날짜,측정장비,체중(kg)\n20260924092730,H40,70"
        assertEquals(csv, InBodyCsvParser.decode(csv.toByteArray()))
        assertEquals(csv, InBodyCsvParser.decode(csv.toByteArray(Charsets.UTF_16)))
        assertEquals(csv, InBodyCsvParser.decode(csv.toByteArray(charset("MS949"))))
    }

    @Test fun `ambiguous daylight saving timestamps are rejected not silently shifted`() {
        val csv = "날짜,측정장비,체중(kg)\n20261101013000,H40,70"
        assertTrue(runCatching { InBodyCsvParser.parse(csv, "test.csv", ZoneId.of("America/New_York"), LocalDate.of(2026, 11, 2)) }.isFailure)
    }

    @Test fun `ratio and muscle fields never substitute for other measurements`() {
        val record = parse("날짜,측정장비,근육량(kg),복부지방률,내장지방단면적(cm²)\n20260924092730,H40,54.1,0.95,88.5").records.single()
        assertFalse(record.values.containsKey(HealthMetricType.SKELETAL_MUSCLE_KG))
        assertFalse(record.values.containsKey(HealthMetricType.VISCERAL_FAT_LEVEL))
        assertEquals(0.95, record.values.getValue(HealthMetricType.WAIST_HIP_RATIO), 0.0)
    }
}
