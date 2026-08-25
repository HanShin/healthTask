package com.hanshin.healthtask.health

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.hanshin.healthtask.domain.HealthMetricType
import java.time.DateTimeException
import java.time.LocalDate
import kotlinx.coroutines.tasks.await

data class InBodyScreenshotResult(
    val measuredDate: LocalDate?,
    val values: Map<HealthMetricType, Double>,
    val recognizedText: String,
)

class InBodyScreenshotReader(private val context: Context) {
    suspend fun read(uri: Uri): InBodyScreenshotResult {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestSide = maxOf(info.size.width, info.size.height)
            if (longestSide > MAX_OCR_IMAGE_SIDE) {
                val scale = MAX_OCR_IMAGE_SIDE.toDouble() / longestSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            InBodyScreenshotParser.parse(recognizer.process(image).await().text)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_OCR_IMAGE_SIDE = 2_048
    }
}

object InBodyScreenshotParser {
    private data class MetricDefinition(
        val type: HealthMetricType,
        val aliases: List<String>,
        val range: ClosedFloatingPointRange<Double>,
    )

    private val metricDefinitions = listOf(
        MetricDefinition(
            HealthMetricType.SKELETAL_MUSCLE_KG,
            listOf("골격근량", "skeletalmusclemass", "smm"),
            2.0..100.0,
        ),
        MetricDefinition(
            HealthMetricType.BODY_FAT_MASS_KG,
            listOf("체지방량", "bodyfatmass", "bfm"),
            1.0..150.0,
        ),
        MetricDefinition(
            HealthMetricType.VISCERAL_FAT_LEVEL,
            listOf("내장지방레벨", "내장지방단계", "visceralfatlevel", "vfl"),
            1.0..30.0,
        ),
        MetricDefinition(
            HealthMetricType.INBODY_SCORE,
            listOf("인바디점수", "inbodyscore"),
            1.0..120.0,
        ),
    )

    private val numberRegex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{1,2})?)(?!\\d)")
    private val scoreWithUnitRegex = Regex("(?<![\\d.])(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*점")
    private val compactDateRegex = Regex("(?<!\\d)(20\\d{2}|\\d{2})[.\\-/년]\\s*(\\d{1,2})[.\\-/월]\\s*(\\d{1,2})(?:일)?(?!\\d)")

    fun parse(text: String): InBodyScreenshotResult {
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("(?<=\\d),(?=\\d)"), ".") }
            .filter { it.isNotBlank() }
            .toList()
        val normalized = lines.map(::normalize)
        val values = metricDefinitions.mapNotNull { definition ->
            val value = if (definition.type == HealthMetricType.INBODY_SCORE) {
                findScoreWithUnit(text) ?: findValue(lines, normalized, definition)
            } else {
                findValue(lines, normalized, definition)
            }
            value?.let { definition.type to it }
        }.toMap()
        return InBodyScreenshotResult(
            measuredDate = findDate(text),
            values = values,
            recognizedText = text,
        )
    }

    private fun findValue(
        lines: List<String>,
        normalized: List<String>,
        definition: MetricDefinition,
    ): Double? {
        normalized.forEachIndexed { index, compactLine ->
            val alias = definition.aliases.firstOrNull(compactLine::contains) ?: return@forEachIndexed
            val aliasEnd = compactLine.indexOf(alias) + alias.length
            numbers(compactLine.substring(aliasEnd))
                .firstOrNull { it in definition.range }
                ?.let { return it }

            for (nextIndex in (index + 1)..minOf(index + 3, lines.lastIndex)) {
                val nextNormalized = normalized[nextIndex]
                if (metricDefinitions.any { other -> other.aliases.any(nextNormalized::contains) }) break
                numbers(lines[nextIndex]).firstOrNull { it in definition.range }?.let { return it }
            }
        }
        return null
    }

    private fun numbers(value: String): List<Double> = numberRegex.findAll(value)
        .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        .toList()

    private fun findScoreWithUnit(value: String): Double? = scoreWithUnitRegex.findAll(value)
        .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        .firstOrNull { it in 1.0..120.0 }

    private fun findDate(value: String): LocalDate? = compactDateRegex.find(value)?.let { match ->
        try {
            val parsedYear = match.groupValues[1].toInt()
            LocalDate.of(
                if (parsedYear < 100) parsedYear + 2_000 else parsedYear,
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("""[\s_()\[\]{}:·%㎏]"""), "")
        .replace("kilograms", "kg")
        .replace("kilogram", "kg")
        .replace("liters", "l")
        .replace("liter", "l")
}
