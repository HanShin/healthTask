package com.hanshin.healthtask.ui

import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideMotionTest {
    @Test
    fun `tabata movements map to front and side motion resources`() {
        val expected = mapOf(
            TabataGuideMovement.BURPEE to "tabata_burpee",
            TabataGuideMovement.MOUNTAIN_CLIMBER to "tabata_mountain_climber",
            TabataGuideMovement.BODYWEIGHT_SQUAT to "tabata_bodyweight_squat",
        )

        expected.forEach { (movement, resourceStem) ->
            assertEquals(
                "${resourceStem}_front",
                guideMotionDescriptor(TABATA_EXERCISE_ID, GuideMotionAngle.FRONT, movement)?.rawResourceName,
            )
            assertEquals(
                "${resourceStem}_side",
                guideMotionDescriptor(TABATA_EXERCISE_ID, GuideMotionAngle.SIDE, movement)?.rawResourceName,
            )
        }
    }

    @Test
    fun `tabata labels describe the selected movement and angle`() {
        assertEquals(
            "마운틴 클라이머 측면 자세 시범 영상",
            guideMotionDescriptor(
                exerciseId = TABATA_EXERCISE_ID,
                angle = GuideMotionAngle.SIDE,
                tabataMovement = TabataGuideMovement.MOUNTAIN_CLIMBER,
            )?.accessibilityLabel,
        )
        assertEquals("로우 임팩트 버피", TabataGuideMovement.BURPEE.label)
        assertEquals("마운틴 클라이머", TabataGuideMovement.MOUNTAIN_CLIMBER.label)
        assertEquals("맨몸 스쿼트", TabataGuideMovement.BODYWEIGHT_SQUAT.label)
    }

    @Test
    fun `forearm plank maps to front and side motion resources`() {
        assertEquals(
            "plank_front",
            guideMotionDescriptor("plank", GuideMotionAngle.FRONT)?.rawResourceName,
        )
        assertEquals(
            "plank_side",
            guideMotionDescriptor("plank", GuideMotionAngle.SIDE)?.rawResourceName,
        )
        assertEquals(
            "플랭크 측면 자세 시범 영상",
            guideMotionDescriptor("plank", GuideMotionAngle.SIDE)?.accessibilityLabel,
        )
    }

    @Test
    fun `push-up maps to front and side motion resources`() {
        assertEquals(
            "push_up_front",
            guideMotionDescriptor("push-up", GuideMotionAngle.FRONT)?.rawResourceName,
        )
        assertEquals(
            "push_up_side",
            guideMotionDescriptor("push-up", GuideMotionAngle.SIDE)?.rawResourceName,
        )
        assertEquals(
            "푸시업 측면 자세 시범 영상",
            guideMotionDescriptor("push-up", GuideMotionAngle.SIDE)?.accessibilityLabel,
        )
    }

    @Test
    fun `existing guided exercise mapping remains available`() {
        assertEquals(
            "flat_dumbbell_press_side",
            guideMotionDescriptor("flat-dumbbell-press", GuideMotionAngle.SIDE)?.rawResourceName,
        )
        assertEquals(
            "dumbbell_romanian_deadlift_front",
            guideMotionDescriptor("dumbbell-romanian-deadlift", GuideMotionAngle.FRONT)?.rawResourceName,
        )
        assertEquals(
            "dumbbell_romanian_deadlift_side",
            guideMotionDescriptor("dumbbell-romanian-deadlift", GuideMotionAngle.SIDE)?.rawResourceName,
        )
        assertEquals(
            "덤벨 루마니안 데드리프트 측면 자세 시범 영상",
            guideMotionDescriptor("dumbbell-romanian-deadlift", GuideMotionAngle.SIDE)?.accessibilityLabel,
        )
        assertEquals(
            "dumbbell_bulgarian_split_squat_front",
            guideMotionDescriptor("dumbbell-bulgarian-split-squat", GuideMotionAngle.FRONT)?.rawResourceName,
        )
        assertEquals(
            "dumbbell_bulgarian_split_squat_side",
            guideMotionDescriptor("dumbbell-bulgarian-split-squat", GuideMotionAngle.SIDE)?.rawResourceName,
        )
        assertEquals(
            "덤벨 불가리안 스플릿 스쿼트 측면 자세 시범 영상",
            guideMotionDescriptor("dumbbell-bulgarian-split-squat", GuideMotionAngle.SIDE)?.accessibilityLabel,
        )
        assertNull(guideMotionDescriptor("exercise-without-local-video", GuideMotionAngle.FRONT))
    }
}
