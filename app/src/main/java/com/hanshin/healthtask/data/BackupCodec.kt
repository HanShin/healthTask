package com.hanshin.healthtask.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.RoutineEntity
import com.hanshin.healthtask.data.db.RoutineItemEntity
import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.TrainingPlanEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.isExternal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BackupPayload(
    val schemaVersion: Int = 3,
    val exportedAt: String = Instant.now().toString(),
    val profile: ProfileEntity?,
    val exercises: List<ExerciseEntity>,
    val routines: List<RoutineEntity>,
    val routineItems: List<RoutineItemEntity>,
    val sessions: List<WorkoutSessionEntity>,
    val workoutItems: List<WorkoutItemEntity>,
    val setRecords: List<SetRecordEntity>,
    val healthMeasurements: List<HealthMeasurementEntity>,
    val samsungWorkoutLinks: List<SamsungWorkoutLinkEntity>,
    val trainingPlans: List<TrainingPlanEntity>? = null,
    val planSlots: List<PlanSlotEntity>? = null,
)

data class ImportReport(
    val schemaVersion: Int,
    val routines: Int,
    val sessions: Int,
    val healthMeasurements: Int,
    val trainingPlans: Int = 0,
)

class BackupCodec(private val database: HealthTaskDatabase) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun export(): String {
        val dao = database.dao()
        val routines = dao.getRoutines()
        val sessions = dao.getSessions().filterNot { it.session.source.isExternal() }
        val localIds = sessions.mapTo(mutableSetOf()) { it.session.id }
        val trainingPlans = dao.getTrainingPlans()
        val payload = BackupPayload(
            profile = dao.getProfile(),
            exercises = dao.getExercises(),
            routines = routines.map { it.routine },
            routineItems = routines.flatMap { it.items },
            sessions = sessions.map { it.session },
            workoutItems = sessions.flatMap { it.items }.map { it.item },
            setRecords = sessions.flatMap { it.items }.flatMap { it.sets },
            healthMeasurements = dao.getHealthMeasurements().filterNot { it.source.isExternal() },
            samsungWorkoutLinks = dao.getWorkoutLinks().filter { it.localSessionId in localIds },
            trainingPlans = trainingPlans.map { it.plan },
            planSlots = trainingPlans.flatMap { it.slots },
        )
        return gson.toJson(payload)
    }

    suspend fun import(raw: String): ImportReport {
        val root = JsonParser.parseString(raw).asJsonObject
        return if (root.int("schemaVersion") in 2..3) importModern(root) else importLegacy(root)
    }

    private suspend fun importModern(root: JsonObject): ImportReport {
        val payload = gson.fromJson(root, BackupPayload::class.java)
        database.withTransaction {
            val dao = database.dao()
            payload.profile?.let { dao.upsertProfile(it) }
            if (payload.exercises.isNotEmpty()) dao.upsertExercises(payload.exercises)
            payload.routines.forEach { dao.upsertRoutine(it) }
            if (payload.routineItems.isNotEmpty()) dao.upsertRoutineItems(payload.routineItems)
            payload.sessions.forEach { dao.upsertSession(it) }
            if (payload.workoutItems.isNotEmpty()) dao.upsertWorkoutItems(payload.workoutItems)
            if (payload.setRecords.isNotEmpty()) dao.upsertSetRecords(payload.setRecords)
            if (payload.healthMeasurements.isNotEmpty()) dao.upsertHealthMeasurements(payload.healthMeasurements)
            payload.samsungWorkoutLinks.forEach { dao.upsertWorkoutLink(it) }
            payload.trainingPlans.orEmpty().forEach { dao.upsertTrainingPlan(it) }
            if (payload.planSlots.orEmpty().isNotEmpty()) dao.upsertPlanSlots(payload.planSlots.orEmpty())
        }
        return ImportReport(
            root.int("schemaVersion") ?: 2,
            payload.routines.size,
            payload.sessions.size,
            payload.healthMeasurements.size,
            payload.trainingPlans.orEmpty().size,
        )
    }

    private suspend fun importLegacy(root: JsonObject): ImportReport {
        val exercises = root.array("exercises").map { element ->
            val item = element.asJsonObject
            val category = category(item)
            val guide = item.obj("guide")
            ExerciseEntity(
                id = item.string("id") ?: error("운동 ID가 없습니다."),
                name = item.string("name") ?: "이름 없는 운동",
                category = category,
                recordMode = if (category == ExerciseCategory.CARDIO) RecordMode.CARDIO else RecordMode.SETS,
                muscleGroup = item.string("muscleGroup"),
                equipment = item.string("equipment"),
                guideHeadline = guide?.string("headline"),
                guideCues = guide?.array("cues")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }?.joinToString("\n"),
                guideWarning = guide?.string("warning"),
                guideVideoUrl = guide?.array("resources")?.firstOrNull()?.asJsonObject?.string("url"),
                guideAssetPath = null,
                isCustom = item.bool("isCustom") ?: false,
                createdAt = epoch(item.string("createdAt")),
            )
        }
        val exerciseMap = (SeedData.exercises + exercises).associateBy { it.id }
        val routines = mutableListOf<RoutineEntity>()
        val routineItems = mutableListOf<RoutineItemEntity>()
        root.array("routines").forEach { element ->
            val item = element.asJsonObject
            val id = item.string("id") ?: error("루틴 ID가 없습니다.")
            routines += RoutineEntity(
                id = id,
                name = item.string("name") ?: "가져온 루틴",
                source = item.string("source") ?: "manual",
                isActive = item.bool("isActive") ?: true,
                createdAt = epoch(item.string("createdAt")),
                updatedAt = epoch(item.string("updatedAt")),
            )
            item.array("items").forEachIndexed { index, child ->
                val plan = child.asJsonObject
                val exerciseId = plan.string("exerciseId") ?: return@forEachIndexed
                val planCategory = category(plan, exerciseMap[exerciseId]?.category)
                val planId = plan.string("id") ?: "$id-plan-${index + 1}"
                routineItems += RoutineItemEntity(
                    id = planId,
                    routineId = id,
                    exerciseId = exerciseId,
                    orderIndex = plan.int("order") ?: index + 1,
                    category = planCategory,
                    recordMode = if (planCategory == ExerciseCategory.CARDIO) RecordMode.CARDIO else RecordMode.SETS,
                    setCount = plan.int("sets"),
                    targetReps = plan.int("targetReps"),
                    restSeconds = plan.int("restSeconds"),
                    targetWeightKg = plan.double("targetWeightKg"),
                    targetActivityLabel = plan.string("targetActivityLabel"),
                    targetDistanceKm = plan.double("targetDistanceKm"),
                    targetDurationMin = plan.double("targetDurationMin"),
                    targetPaceMinPerKm = plan.double("targetPaceMinPerKm"),
                    note = plan.string("note"),
                )
            }
        }

        val routineNames = routines.associate { it.id to it.name }
        val sessions = mutableListOf<WorkoutSessionEntity>()
        val workoutItems = mutableListOf<WorkoutItemEntity>()
        val setRecords = mutableListOf<SetRecordEntity>()
        root.array("sessions").forEach { element ->
            val item = element.asJsonObject
            val id = item.string("id") ?: error("세션 ID가 없습니다.")
            val sessionDate = item.string("sessionDate") ?: LocalDate.now().toString()
            val started = epoch(item.string("startedAt") ?: item.string("createdAt"), sessionDate)
            val ended = item.string("endedAt")?.let(::epoch)
            val status = when (item.string("status")) {
                "partial" -> WorkoutStatus.PARTIAL
                "skipped" -> WorkoutStatus.SKIPPED
                else -> WorkoutStatus.COMPLETED
            }
            sessions += WorkoutSessionEntity(
                id = id,
                routineId = item.string("routineId"),
                title = item.string("routineId")?.let(routineNames::get) ?: "가져온 운동",
                sessionDate = sessionDate,
                status = status,
                source = WorkoutSource.LEGACY_IMPORT,
                startedAt = started,
                endedAt = ended ?: started,
                memo = item.string("memo"),
                syncStatus = SyncStatus.SYNCED,
                createdAt = epoch(item.string("createdAt"), sessionDate),
                updatedAt = System.currentTimeMillis(),
            )
            item.array("items").forEachIndexed { index, child ->
                val record = child.asJsonObject
                val exerciseId = record.string("exerciseId") ?: return@forEachIndexed
                val recordCategory = category(record, exerciseMap[exerciseId]?.category)
                val itemId = record.string("id") ?: "$id-item-${index + 1}"
                workoutItems += WorkoutItemEntity(
                    id = itemId,
                    sessionId = id,
                    exerciseId = exerciseId,
                    exerciseName = exerciseMap[exerciseId]?.name ?: exerciseId,
                    orderIndex = record.int("order") ?: index + 1,
                    category = recordCategory,
                    recordMode = if (recordCategory == ExerciseCategory.CARDIO) RecordMode.CARDIO else RecordMode.SETS,
                    activityLabel = record.string("activityLabel"),
                    distanceKm = record.double("distanceKm"),
                    durationMin = record.double("durationMin"),
                    avgPaceMinPerKm = record.double("avgPaceMinPerKm"),
                    note = record.string("note"),
                )
                record.array("sets").forEachIndexed { setIndex, setElement ->
                    val set = setElement.asJsonObject
                    val order = set.int("order") ?: setIndex + 1
                    setRecords += SetRecordEntity(
                        id = "$itemId-set-$order",
                        workoutItemId = itemId,
                        orderIndex = order,
                        plannedReps = set.int("plannedReps"),
                        actualReps = set.int("actualReps"),
                        plannedWeightKg = set.double("plannedWeightKg"),
                        actualWeightKg = set.double("actualWeightKg"),
                        completed = set.bool("completed") ?: false,
                    )
                }
            }
        }

        val health = mutableListOf<HealthMeasurementEntity>()
        root.array("healthEntries").forEach { element ->
            val item = element.asJsonObject
            val baseId = item.string("id") ?: "legacy-health-${item.string("recordDate")}"
            val date = item.string("recordDate") ?: LocalDate.now().toString()
            val measuredAt = epoch(item.string("createdAt"), date)
            listOf(
                Triple("weightKg", HealthMetricType.WEIGHT_KG, item.double("weightKg")),
                Triple("bodyFatKg", HealthMetricType.BODY_FAT_MASS_KG, item.double("bodyFatKg")),
                Triple("skeletalMuscleKg", HealthMetricType.SKELETAL_MUSCLE_KG, item.double("skeletalMuscleKg")),
                Triple("visceralFatLevel", HealthMetricType.VISCERAL_FAT_LEVEL, item.double("visceralFatLevel")),
            ).forEach { (key, type, value) ->
                if (value != null) health += HealthMeasurementEntity(
                    id = "$baseId-$key",
                    recordDate = date,
                    measuredAt = measuredAt,
                    type = type,
                    value = value,
                    source = WorkoutSource.LEGACY_IMPORT,
                )
            }
        }
        val profile = root.obj("profile")?.let { item ->
            ProfileEntity(
                workoutsPerWeek = (item.int("weeklyGoalCount") ?: item.int("workoutsPerWeek") ?: 3).coerceIn(1, 7),
                onboardingDone = item.bool("onboardingDone") ?: true,
                createdAt = epoch(item.string("createdAt")),
                updatedAt = epoch(item.string("updatedAt")),
            )
        }
        database.withTransaction {
            val dao = database.dao()
            profile?.let { dao.upsertProfile(it) }
            dao.upsertExercises(SeedData.exercises + exercises)
            routines.forEach { dao.upsertRoutine(it) }
            if (routineItems.isNotEmpty()) dao.upsertRoutineItems(routineItems)
            sessions.forEach { dao.upsertSession(it) }
            if (workoutItems.isNotEmpty()) dao.upsertWorkoutItems(workoutItems)
            if (setRecords.isNotEmpty()) dao.upsertSetRecords(setRecords)
            if (health.isNotEmpty()) dao.upsertHealthMeasurements(health)
        }
        return ImportReport(1, routines.size, sessions.size, health.size)
    }

    private fun category(value: JsonObject, fallback: ExerciseCategory? = null): ExerciseCategory = when {
        value.string("category") == "cardio" || value.string("kind") == "running" -> ExerciseCategory.CARDIO
        value.string("category") == "bodyweight" || value.string("equipment") == "bodyweight" -> ExerciseCategory.BODYWEIGHT
        value.string("category") == "weight" || value.string("kind") == "strength" -> ExerciseCategory.WEIGHT
        else -> fallback ?: ExerciseCategory.WEIGHT
    }

    private fun epoch(value: String?, fallbackDate: String? = null): Long = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrElse {
        fallbackDate?.let { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            ?: System.currentTimeMillis()
    }
}

private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.double(name: String): Double? = get(name)?.takeUnless { it.isJsonNull }?.asDouble
private fun JsonObject.bool(name: String): Boolean? = get(name)?.takeUnless { it.isJsonNull }?.asBoolean
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
