package com.hanshin.healthtask.data

import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.RoutineEntity
import com.hanshin.healthtask.data.db.RoutineItemEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.RecordMode

object SeedData {
    private fun strength(
        id: String,
        name: String,
        muscle: String,
        equipment: String,
        headline: String? = null,
        cues: List<String> = emptyList(),
        warning: String? = null,
        video: String? = null,
        bodyweight: Boolean = equipment == "bodyweight",
    ) = ExerciseEntity(
        id = id,
        name = name,
        category = if (bodyweight) ExerciseCategory.BODYWEIGHT else ExerciseCategory.WEIGHT,
        recordMode = RecordMode.SETS,
        muscleGroup = muscle,
        equipment = equipment,
        guideHeadline = headline,
        guideCues = cues.joinToString("\n"),
        guideWarning = warning,
        guideVideoUrl = video,
        guideAssetPath = guideAsset(id),
    )

    private fun cardio(id: String, name: String, headline: String, video: String) = ExerciseEntity(
        id = id,
        name = name,
        category = ExerciseCategory.CARDIO,
        recordMode = RecordMode.CARDIO,
        equipment = "running",
        guideHeadline = headline,
        guideCues = "상체 힘을 빼고 시선은 정면\n발은 몸 아래에 가깝게 착지\n보폭보다 편안한 리듬을 우선",
        guideWarning = "통증이 느껴지면 속도와 거리를 줄이세요.",
        guideVideoUrl = video,
        guideAssetPath = "guides/running.svg",
    )

    val exercises: List<ExerciseEntity> = listOf(
        strength("bench-press", "벤치프레스", "chest", "barbell", "견갑을 벤치에 고정하고 발로 바닥을 밀며 바를 수직으로 올립니다.", listOf("가슴을 열고 어깨는 아래로 고정", "손목과 전완을 수직으로 유지"), "바가 목 쪽으로 흐르지 않게 하세요.", "https://www.youtube.com/watch?v=FN4zDtiy3Gg"),
        strength("flat-dumbbell-press", "플랫 덤벨 프레스", "chest", "dumbbell", "견갑을 고정한 채 덤벨을 가슴 위로 모읍니다.", listOf("좌우 균형 유지", "반동 없이 천천히 내리기"), video = "https://www.youtube.com/watch?v=4NpzFtatcK8"),
        strength("incline-dumbbell-press", "인클라인 덤벨 프레스", "chest", "dumbbell"),
        strength("dumbbell-floor-press", "덤벨 플로어 프레스", "chest", "dumbbell"),
        strength("dumbbell-fly", "덤벨 플라이", "chest", "dumbbell"),
        strength("push-up", "푸시업", "chest", "bodyweight", "몸통 전체를 하나의 판처럼 유지하며 바닥을 밀어냅니다."),
        strength("seated-row", "시티드 로우", "back", "cable", "가슴을 세우고 팔꿈치로 뒤를 긁어내듯 당깁니다.", video = "https://www.youtube.com/watch?v=b_seZMf3MfM"),
        strength("lat-pulldown", "랫풀다운", "back", "cable", "바를 쇄골 앞쪽으로 끌어오며 광배로 당깁니다.", video = "https://www.youtube.com/watch?v=hVa5dsH3DaA"),
        strength("one-arm-dumbbell-row", "원암 덤벨 로우", "back", "dumbbell", "몸통을 고정하고 팔꿈치를 엉덩이 쪽으로 당깁니다.", video = "https://www.youtube.com/watch?v=bXrkh09AcKg"),
        strength("chest-supported-dumbbell-row", "체스트 서포티드 덤벨 로우", "back", "dumbbell"),
        strength("dumbbell-pullover", "덤벨 풀오버", "back", "dumbbell"),
        strength("kettlebell-row", "케틀벨 로우", "back", "kettlebell"),
        strength("inverted-row", "인버티드 로우", "back", "bodyweight"),
        strength("pull-up", "풀업", "back", "bodyweight", "견갑을 먼저 끌어내린 뒤 팔꿈치를 아래로 당깁니다."),
        strength("chin-up", "친업", "back", "bodyweight"),
        strength("squat", "스쿼트", "legs", "barbell", "복압을 만들고 무릎과 발끝 방향을 맞추며 내려갑니다.", video = "https://www.youtube.com/watch?v=50f62PSGY7k"),
        strength("romanian-deadlift", "루마니안 데드리프트", "legs", "barbell", "엉덩이를 뒤로 보내며 햄스트링 신장을 느낍니다.", video = "https://www.youtube.com/watch?v=hRVNKm9K4zU"),
        strength("leg-press", "레그프레스", "legs", "machine", "등과 엉덩이를 패드에 붙이고 발 전체로 밀어냅니다.", video = "https://www.youtube.com/watch?v=hYwJrXpzEfs"),
        strength("dumbbell-goblet-squat", "덤벨 고블릿 스쿼트", "legs", "dumbbell"),
        strength("dumbbell-bulgarian-split-squat", "덤벨 불가리안 스플릿 스쿼트", "legs", "dumbbell", video = "https://www.youtube.com/watch?v=bqKv1toW73Y"),
        strength("dumbbell-reverse-lunge", "덤벨 리버스 런지", "legs", "dumbbell"),
        strength("dumbbell-step-up", "덤벨 스텝업", "legs", "dumbbell"),
        strength("dumbbell-romanian-deadlift", "덤벨 루마니안 데드리프트", "legs", "dumbbell"),
        strength("kettlebell-goblet-squat", "케틀벨 고블릿 스쿼트", "legs", "kettlebell"),
        strength("kettlebell-deadlift", "케틀벨 데드리프트", "legs", "kettlebell"),
        strength("kettlebell-swing", "케틀벨 스윙", "legs", "kettlebell", "팔이 아니라 힙힌지와 엉덩이 힘으로 케틀벨을 보냅니다.", video = "https://www.youtube.com/watch?v=UIxi5LPD-6A"),
        strength("kettlebell-front-rack-reverse-lunge", "케틀벨 프론트 랙 리버스 런지", "legs", "kettlebell"),
        strength("bodyweight-squat", "맨몸 스쿼트", "legs", "bodyweight"),
        strength("bodyweight-reverse-lunge", "맨몸 리버스 런지", "legs", "bodyweight"),
        strength("glute-bridge", "글루트 브리지", "legs", "bodyweight"),
        strength("shoulder-press", "숄더프레스", "shoulders", "dumbbell", "갈비뼈를 잠그고 덤벨을 머리 위로 밀어 올립니다.", video = "https://www.youtube.com/watch?v=OMCJoZfKhxM"),
        strength("dumbbell-push-press", "덤벨 푸시 프레스", "shoulders", "dumbbell"),
        strength("dumbbell-thruster", "덤벨 쓰러스터", "shoulders", "dumbbell"),
        strength("dumbbell-lateral-raise", "덤벨 레터럴 레이즈", "shoulders", "dumbbell"),
        strength("dumbbell-rear-delt-fly", "덤벨 리어델트 플라이", "shoulders", "dumbbell"),
        strength("pike-push-up", "파이크 푸시업", "shoulders", "bodyweight"),
        strength("kettlebell-clean-and-press", "케틀벨 클린 앤 프레스", "shoulders", "kettlebell", video = "https://www.youtube.com/watch?v=Rc4PqOSg4OQ"),
        strength("kettlebell-halo", "케틀벨 헤일로", "shoulders", "kettlebell"),
        strength("plank", "플랭크", "core", "bodyweight", "머리부터 발뒤꿈치까지 일직선을 유지하며 복부를 조입니다.", video = "https://www.youtube.com/watch?v=QVRZhClEHLw"),
        strength("hanging-knee-raise", "행잉 니 레이즈", "core", "bodyweight"),
        strength("kettlebell-turkish-get-up", "케틀벨 터키시 겟업", "core", "kettlebell", video = "https://www.youtube.com/watch?v=htV9K6wgApI"),
        strength("dumbbell-biceps-curl", "덤벨 바이셉 컬", "arms", "dumbbell"),
        strength("hammer-curl", "해머 컬", "arms", "dumbbell"),
        strength("overhead-dumbbell-triceps-extension", "오버헤드 덤벨 트라이셉 익스텐션", "arms", "dumbbell"),
        strength("dip", "딥스", "arms", "bodyweight"),
        cardio("easy-run", "가벼운 유산소", "호흡이 편한 강도로 부드럽게 움직입니다.", "https://www.youtube.com/watch?v=Ggbm_coe5uM"),
        cardio("tempo-run", "템포 유산소", "약간 숨찰 정도의 일정한 리듬을 유지합니다.", "https://www.youtube.com/watch?v=GpQ21f1b-cg"),
        cardio("long-run", "지구력 유산소", "지속 가능한 리듬으로 상체 힘을 빼고 움직입니다.", "https://www.youtube.com/watch?v=ewHVH6-udbg"),
    )

    private fun setItem(routineId: String, exerciseId: String, order: Int, sets: Int, reps: Int, weight: Double) =
        RoutineItemEntity(
            id = "$routineId-$exerciseId-$order",
            routineId = routineId,
            exerciseId = exerciseId,
            orderIndex = order,
            category = exercises.first { it.id == exerciseId }.category,
            recordMode = RecordMode.SETS,
            setCount = sets,
            targetReps = reps,
            targetWeightKg = weight,
            restSeconds = 75,
        )

    private fun cardioItem(routineId: String, exerciseId: String, order: Int, km: Double, minutes: Double) =
        RoutineItemEntity(
            id = "$routineId-$exerciseId-$order",
            routineId = routineId,
            exerciseId = exerciseId,
            orderIndex = order,
            category = ExerciseCategory.CARDIO,
            recordMode = RecordMode.CARDIO,
            targetActivityLabel = "유산소",
            targetDistanceKm = km,
            targetDurationMin = minutes,
            targetPaceMinPerKm = minutes / km,
        )

    val templates: List<RoutineWithItems> = listOf(
        template("template-dumbbell-upper", "덤벨 밀기·당기기", listOf(
            setItem("template-dumbbell-upper", "flat-dumbbell-press", 1, 4, 8, 18.0),
            setItem("template-dumbbell-upper", "one-arm-dumbbell-row", 2, 4, 10, 18.0),
            setItem("template-dumbbell-upper", "shoulder-press", 3, 3, 10, 14.0),
            setItem("template-dumbbell-upper", "hammer-curl", 4, 3, 12, 10.0),
        )),
        template("template-dumbbell-lower", "덤벨 하체 빌드", listOf(
            setItem("template-dumbbell-lower", "dumbbell-goblet-squat", 1, 4, 10, 20.0),
            setItem("template-dumbbell-lower", "dumbbell-romanian-deadlift", 2, 4, 10, 18.0),
            setItem("template-dumbbell-lower", "dumbbell-bulgarian-split-squat", 3, 3, 10, 12.0),
            setItem("template-dumbbell-lower", "plank", 4, 3, 1, 0.0),
        )),
        template("template-bodyweight-foundation", "맨몸운동 기초", listOf(
            setItem("template-bodyweight-foundation", "push-up", 1, 4, 10, 0.0),
            setItem("template-bodyweight-foundation", "bodyweight-squat", 2, 4, 15, 0.0),
            setItem("template-bodyweight-foundation", "bodyweight-reverse-lunge", 3, 3, 10, 0.0),
            setItem("template-bodyweight-foundation", "plank", 4, 3, 1, 0.0),
        )),
        template("template-kettlebell-flow", "케틀벨 플로우", listOf(
            setItem("template-kettlebell-flow", "kettlebell-swing", 1, 5, 15, 16.0),
            setItem("template-kettlebell-flow", "kettlebell-goblet-squat", 2, 4, 10, 16.0),
            setItem("template-kettlebell-flow", "kettlebell-clean-and-press", 3, 4, 8, 12.0),
        )),
        template("template-dumbbell-hybrid", "프리웨이트 + 유산소", listOf(
            setItem("template-dumbbell-hybrid", "dumbbell-goblet-squat", 1, 3, 10, 18.0),
            setItem("template-dumbbell-hybrid", "flat-dumbbell-press", 2, 3, 10, 16.0),
            setItem("template-dumbbell-hybrid", "one-arm-dumbbell-row", 3, 3, 10, 16.0),
            cardioItem("template-dumbbell-hybrid", "easy-run", 4, 3.0, 20.0),
        )),
    )

    private fun template(id: String, name: String, items: List<RoutineItemEntity>) = RoutineWithItems(
        RoutineEntity(id = id, name = name, source = "template", createdAt = templatesBaseTime + templatesCounter++),
        items,
    )

    private const val templatesBaseTime = 1_700_000_000_000L
    private var templatesCounter = 0L

    private fun guideAsset(id: String): String? = mapOf(
        "bench-press" to "bench-press.svg",
        "one-arm-dumbbell-row" to "dumbbell-row.svg",
        "dumbbell-goblet-squat" to "goblet-squat.svg",
        "incline-dumbbell-press" to "incline-dumbbell-press.svg",
        "kettlebell-clean-and-press" to "kettlebell-clean-press.svg",
        "kettlebell-swing" to "kettlebell-swing.svg",
        "lat-pulldown" to "lat-pulldown.svg",
        "leg-press" to "leg-press.svg",
        "plank" to "plank.svg",
        "romanian-deadlift" to "romanian-deadlift.svg",
        "seated-row" to "seated-row.svg",
        "shoulder-press" to "shoulder-press.svg",
        "dumbbell-bulgarian-split-squat" to "split-squat.svg",
        "squat" to "squat.svg",
        "kettlebell-turkish-get-up" to "turkish-get-up.svg",
    )[id]?.let { "guides/$it" }
}
