package com.hanshin.healthtask.health

import com.hanshin.healthtask.domain.HealthMetricType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class InBodyScreenshotParserTest {
    @Test
    fun parsesKoreanLabelsWithValuesOnFollowingLines() {
        val result = InBodyScreenshotParser.parse(
            """
            2026.08.24
            골격근량
            33.4 kg
            체지방량
            14.5 kg
            총 체수분
            42.1 L
            내장지방 레벨
            7
            인바디 점수
            82점
            """.trimIndent()
        )

        assertEquals(LocalDate.of(2026, 8, 24), result.measuredDate)
        assertEquals(33.4, result.values[HealthMetricType.SKELETAL_MUSCLE_KG]!!, 0.001)
        assertEquals(14.5, result.values[HealthMetricType.BODY_FAT_MASS_KG]!!, 0.001)
        assertEquals(null, result.values[HealthMetricType.BODY_WATER_L])
        assertEquals(7.0, result.values[HealthMetricType.VISCERAL_FAT_LEVEL]!!, 0.001)
        assertEquals(82.0, result.values[HealthMetricType.INBODY_SCORE]!!, 0.001)
    }

    @Test
    fun parsesEnglishInlineLabelsAndSlashDate() {
        val result = InBodyScreenshotParser.parse(
            """
            2026/8/25
            Skeletal Muscle Mass 31.8 kg
            Body Fat Mass 13.2 kg
            Total Body Water 40.7 L
            Visceral Fat Level 6
            InBody Score 79
            """.trimIndent()
        )

        assertEquals(LocalDate.of(2026, 8, 25), result.measuredDate)
        assertEquals(31.8, result.values[HealthMetricType.SKELETAL_MUSCLE_KG]!!, 0.001)
        assertEquals(13.2, result.values[HealthMetricType.BODY_FAT_MASS_KG]!!, 0.001)
        assertEquals(null, result.values[HealthMetricType.BODY_WATER_L])
        assertEquals(6.0, result.values[HealthMetricType.VISCERAL_FAT_LEVEL]!!, 0.001)
        assertEquals(79.0, result.values[HealthMetricType.INBODY_SCORE]!!, 0.001)
    }

    @Test
    fun parsesInBodyAppDetailScreenshotFormat() {
        val result = InBodyScreenshotParser.parse(
            """
            상세
            26.08.19(수) 06:46
            인바디점수 0
            골격근·지방분석
            체중
            88.1kg
            +0.5
            골격근량
            38.1kg
            +0.2
            체지방량
            21.6kg
            +0.3
            77점
            """.trimIndent()
        )

        assertEquals(LocalDate.of(2026, 8, 19), result.measuredDate)
        assertEquals(38.1, result.values[HealthMetricType.SKELETAL_MUSCLE_KG]!!, 0.001)
        assertEquals(21.6, result.values[HealthMetricType.BODY_FAT_MASS_KG]!!, 0.001)
        assertEquals(77.0, result.values[HealthMetricType.INBODY_SCORE]!!, 0.001)
        assertEquals(null, result.values[HealthMetricType.BODY_WATER_L])
        assertEquals(null, result.values[HealthMetricType.VISCERAL_FAT_LEVEL])
    }
}
