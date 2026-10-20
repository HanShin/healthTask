package com.hanshin.healthtask.domain

import java.time.Instant

enum class WorkoutSource { LOCAL, LEGACY_IMPORT, SAMSUNG_HEALTH, GOOGLE_FIT, NIKE_RUN_CLUB }
enum class SyncStatus { PENDING, SYNCED, ERROR }
enum class ExerciseCategory { WEIGHT, BODYWEIGHT, CARDIO }
enum class RecordMode { SETS, CARDIO }
enum class WorkoutStatus { COMPLETED, PARTIAL, SKIPPED, ACTIVE }
enum class TrainingGoalType { BALANCED, RUNNING, STRENGTH }
enum class PlannedWorkoutType { STRENGTH, EASY_RUN, QUALITY_RUN, LONG_RUN }
enum class HealthMetricType(
    val label: String,
    val unit: String,
    val csvHeader: String,
    val group: String = "기본 체성분",
    val isCode: Boolean = false,
) {
    WEIGHT_KG("체중", "kg", "체중(kg)"),
    BODY_FAT_PERCENT("체지방률", "%", "체지방률(%)"),
    BODY_FAT_MASS_KG("체지방량", "kg", "체지방량(kg)"),
    SKELETAL_MUSCLE_KG("골격근량", "kg", "골격근량(kg)"),
    BODY_WATER_L("체수분", "L", "체수분(L)", "수분"),
    VISCERAL_FAT_LEVEL("내장지방 레벨", "level", "내장지방레벨(Level)", "복부·내장지방"),
    INBODY_SCORE("인바디 점수", "points", "인바디점수"),
    MUSCLE_MASS_KG("근육량", "kg", "근육량(kg)"),
    BMI("BMI", "kg/m²", "BMI(kg/m²)"),
    BASAL_METABOLIC_RATE_KCAL("기초대사량", "kcal", "기초대사량(kcal)"),
    INBODY_AGE("인바디 나이", "years", "인바디나이"),
    RIGHT_ARM_MUSCLE_KG("오른팔 근육량", "kg", "오른팔 근육량(kg)", "부위별 근육"),
    LEFT_ARM_MUSCLE_KG("왼팔 근육량", "kg", "왼팔 근육량(kg)", "부위별 근육"),
    TRUNK_MUSCLE_KG("몸통 근육량", "kg", "몸통 근육량(kg)", "부위별 근육"),
    RIGHT_LEG_MUSCLE_KG("오른다리 근육량", "kg", "오른다리 근육량(kg)", "부위별 근육"),
    LEFT_LEG_MUSCLE_KG("왼다리 근육량", "kg", "왼다리 근육량(kg)", "부위별 근육"),
    RIGHT_ARM_FAT_KG("오른팔 체지방", "kg", "오른팔 체지방(kg)", "부위별 체지방"),
    LEFT_ARM_FAT_KG("왼팔 체지방", "kg", "왼팔 체지방(kg)", "부위별 체지방"),
    TRUNK_FAT_KG("몸통 체지방", "kg", "몸통 체지방(kg)", "부위별 체지방"),
    RIGHT_LEG_FAT_KG("오른다리 체지방", "kg", "오른다리 체지방(kg)", "부위별 체지방"),
    LEFT_LEG_FAT_KG("왼다리 체지방", "kg", "왼다리 체지방(kg)", "부위별 체지방"),
    RIGHT_ARM_ECW_RATIO("오른팔 세포외수분비", "ratio", "오른팔 세포외수분비", "부위별 수분비"),
    LEFT_ARM_ECW_RATIO("왼팔 세포외수분비", "ratio", "왼팔 세포외수분비", "부위별 수분비"),
    TRUNK_ECW_RATIO("몸통 세포외수분비", "ratio", "몸통 세포외수분비", "부위별 수분비"),
    RIGHT_LEG_ECW_RATIO("오른다리 세포외수분비", "ratio", "오른다리 세포외수분비", "부위별 수분비"),
    LEFT_LEG_ECW_RATIO("왼다리 세포외수분비", "ratio", "왼다리 세포외수분비", "부위별 수분비"),
    WAIST_HIP_RATIO("복부지방률", "ratio", "복부지방률", "복부·내장지방"),
    WAIST_CIRCUMFERENCE_CM("허리둘레", "cm", "허리둘레(cm)", "복부·내장지방"),
    VISCERAL_FAT_AREA_CM2("내장지방 단면적", "cm²", "내장지방단면적(cm²)", "복부·내장지방"),
    INTRACELLULAR_WATER_L("세포내수분", "L", "세포내수분(L)", "수분"),
    EXTRACELLULAR_WATER_L("세포외수분", "L", "세포외수분(L)", "수분"),
    ECW_RATIO("세포외수분비", "ratio", "세포외수분비", "수분"),
    UPPER_LOWER_BALANCE_CODE("상체하체", "code", "상체하체", "균형 원본 코드", true),
    UPPER_BALANCE_CODE("상체좌우", "code", "상체좌우", "균형 원본 코드", true),
    LOWER_BALANCE_CODE("하체좌우", "code", "하체좌우", "균형 원본 코드", true),
    LEG_MUSCLE_LEVEL("다리근육 레벨", "level", "다리근육레벨(Level)", "근육·영양 상세"),
    LEG_MUSCLE_KG("다리근육량", "kg", "다리근육량(kg)", "근육·영양 상세"),
    PROTEIN_KG("단백질", "kg", "단백질(kg)", "근육·영양 상세"),
    MINERALS_KG("무기질", "kg", "무기질(kg)", "근육·영양 상세"),
    BONE_MINERAL_KG("골무기질량", "kg", "골무기질량(kg)", "근육·영양 상세"),
    BODY_CELL_MASS_KG("체세포량", "kg", "체세포량(kg)", "근육·영양 상세"),
    SMI("SMI", "kg/m²", "SMI(kg/m²)", "근육·영양 상세"),
    PHASE_ANGLE_DEGREES("전신위상각", "°", "전신위상각(°)", "근육·영양 상세"),
}

data class ExternalWorkout(
    val recordId: String,
    val title: String,
    val category: ExerciseCategory,
    val start: Instant,
    val end: Instant,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
    val source: WorkoutSource,
    val sourcePackage: String,
    val isRunning: Boolean = category == ExerciseCategory.CARDIO && title.isRunningLabel(),
)

data class ExternalHealthMeasurement(
    val recordId: String,
    val type: HealthMetricType,
    val value: Double,
    val measuredAt: Instant,
    val source: WorkoutSource = WorkoutSource.SAMSUNG_HEALTH,
    val sourcePackage: String = SAMSUNG_HEALTH_PACKAGE,
)

data class WorkoutSummary(
    val sessionId: String,
    val title: String,
    val category: ExerciseCategory,
    val startedAt: Instant,
    val endedAt: Instant,
    val distanceKm: Double? = null,
)

const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
const val NIKE_RUN_CLUB_PACKAGE = "com.nike.plusgps"
const val INBODY_PACKAGE = "com.inbody2014.inbody"
const val AUTO_LINK_OVERLAP_RATIO = 0.70

fun WorkoutSource.isExternal(): Boolean =
    this == WorkoutSource.SAMSUNG_HEALTH || this == WorkoutSource.GOOGLE_FIT || this == WorkoutSource.NIKE_RUN_CLUB
