"""Build the offline Tabata mountain-climber guide from the approved athlete.

Run after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_tabata_mountain_climber.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/tabata_mountain_climber_human_sample.blend \
      --mode preview

The source blend owns the approved MakeHuman mesh, studio and palette. This
generator changes only exercise-specific controls, cameras and output assets.

This is a controlled-speed form guide with the standard airborne leg exchange:
one foot lands beneath the flexed hip while the other stays on its forefoot
behind the body, then both feet leave the platform while their positions switch.
"""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
import sys
import tempfile

import bpy
from mathutils import Matrix, Quaternion, Vector


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_human_flat_dumbbell_press import (  # noqa: E402
    ATHLETE_MESHES,
    add_ik,
    configure_athlete_materials,
    copy_world_rotation,
    empty,
    preview_directory,
    reset_squat_scene,
)
from generate_squat_sample import (  # noqa: E402
    FRAME_END,
    configure_scene,
    look_at,
)


EXERCISE = "tabata_mountain_climber"
FPS = 30
ENCODED_FRAMES = 240
CLIMBER_CYCLES = 4
TOP_FRAME = 1
MID_FRAME = 16
BOTTOM_FRAME = 31
KEY_FRAMES = tuple(range(1, FRAME_END + 1, 15))
MESH_CHECK_FRAMES = (TOP_FRAME, 8, MID_FRAME, 24, BOTTOM_FRAME, 38, 46, 54, 61)
PLATFORM_TOP_Z = 0.0
PALM_TARGET_Z = 0.050
ACTIVE_ANKLE_Z = 0.078
SUPPORT_ANKLE_Z = 0.125
FLIGHT_CLEARANCE_Z = 0.061
FLIGHT_CLEARANCE_EXPONENT = 0.55
RETREATING_FOOT_Y = 0.220
ADVANCING_FOOT_Z = 0.040
ACTIVE_ANKLE_Y = -0.280
ACTIVE_ANKLE_X = 0.130
SUPPORT_ANKLE_X = 0.115
SUPPORT_FOOT_ANGLE = 25.0
FLIGHT_FOOT_ANGLE = 2.0
BASE_RIG_ROTATION = 75.0
PIKE_ROTATION = 13.0
PIKE_EXPONENT = 0.45
SHOULDER_CLEARANCE_LIFT = 0.050
SUPPORT_KNEE_ANGLE = 165.0
MAX_KNEE_FRAME_TRAVEL = 0.090
MAX_KNEE_FRAME_ACCELERATION = 0.035
MAX_KNEE_ANGLE_DELTA = 15.5
MAX_KNEE_ANGLE_ACCELERATION = 8.0
MAX_LEG_ROTATION_DELTA = 14.0
MAX_PELVIS_DELTA = 0.092
MAX_HEAD_DELTA = 0.045
MIN_FLIGHT_SHOE_CLEARANCE = 0.025
MIN_MIDFLIGHT_FOOT_SEPARATION = 0.140
MIN_MIDFLIGHT_KNEE_SEPARATION = 0.140
MIN_MIDFLIGHT_KNEE_ANGLE_DIFFERENCE = 18.0
TRANSITION_DRIVE_MIN = 0.25
MIN_TRANSITION_FOOT_YZ_SEPARATION = 0.055
MIN_TRANSITION_KNEE_YZ_SEPARATION = 0.030
MIN_TRANSITION_KNEE_ANGLE_DIFFERENCE = 8.0

# The contact mesh names are inherited from the approved athlete. The eye and
# lash shells are excluded from floor-clearance checks because only body,
# clothes, hair and shoes can plausibly approach the platform.
CLEARANCE_MESHES = tuple(
    name
    for name in ATHLETE_MESHES
    if name not in {"Human.low-poly", "Human.eyebrow004", "Human.eyelashes01"}
)


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render", "validate", "build"),
        default="preview",
    )
    return parser.parse_args(argv)


def drive_amounts(frame: int) -> tuple[float, float]:
    """Return complementary left/right positions for an airborne exchange.

    At every contact pose one foot is forward and the other is extended behind
    the body. The cosine timing eases both feet into contact, and the midpoint
    of every switch puts both feet in flight. Four cycles give eight controlled
    switches. Frame 241 duplicates frame 1 and is never encoded.
    """
    phase = math.tau * CLIMBER_CYCLES * (frame - 1) / ENCODED_FRAMES
    left = 0.5 - 0.5 * math.cos(phase)
    right = 1.0 - left
    return left, right


def switch_direction(frame: int) -> float:
    """Signed switch velocity: positive when the left foot advances."""
    phase = math.tau * CLIMBER_CYCLES * (frame - 1) / ENCODED_FRAMES
    return math.sin(phase)


def flight_amount(drive: float) -> float:
    """Single-hump foot clearance between the rear and forward contacts."""
    clamped = max(0.0, min(1.0, drive))
    return math.sin(math.pi * clamped) ** FLIGHT_CLEARANCE_EXPONENT


def pike_clearance_amount(drive: float) -> float:
    """Small endpoint-zero hip pulse while both feet exchange in the air."""
    if drive <= 0.0 or drive >= 1.0:
        return 0.0
    return math.sin(math.pi * drive) ** PIKE_EXPONENT


def world_bone_head(rig, bone_name: str) -> Vector:
    return rig.matrix_world @ rig.pose.bones[bone_name].head


def joint_angle(first: Vector, joint: Vector, third: Vector) -> float:
    first_direction = (first - joint).normalized()
    third_direction = (third - joint).normalized()
    cosine = max(-1.0, min(1.0, first_direction.dot(third_direction)))
    return math.degrees(math.acos(cosine))


def distance_to_line_yz(point: Vector, start: Vector, end: Vector) -> float:
    """Perpendicular distance to a line after projecting all points to YZ."""
    line_y = end.y - start.y
    line_z = end.z - start.z
    length = math.hypot(line_y, line_z)
    if length < 1e-8:
        raise RuntimeError("Cannot validate a zero-length YZ alignment line")
    point_y = point.y - start.y
    point_z = point.z - start.z
    return abs(line_y * point_z - line_z * point_y) / length


def configure_flat_hands(rig):
    """Lay both open palms flat with fingers pointing toward the head.

    MPFB's mirrored hand rolls differ between sides, so the validated squat
    basis is rebuilt from each hand's own index-to-pinky axis rather than by
    guessing Euler angles. The resulting palm normals face the platform while
    wrists stay neutral and every finger remains extended.
    """
    rotation_targets = {}
    desired_hand_length = Vector((0.0, -1.0, 0.0))
    for side, sign in (("l", 1), ("r", -1)):
        hand_bone = rig.data.bones[f"hand_{side}"]
        rest_rotation = hand_bone.matrix_local.to_quaternion()
        finger_spread = (
            rig.data.bones[f"pinky_01_{side}"].head_local
            - rig.data.bones[f"index_01_{side}"].head_local
        )
        finger_spread_local = rest_rotation.inverted() @ finger_spread
        hand_length_local = Vector((0.0, 1.0, 0.0))
        finger_spread_local = (
            finger_spread_local
            - hand_length_local * finger_spread_local.dot(hand_length_local)
        ).normalized()
        palm_normal_local = finger_spread_local.cross(hand_length_local).normalized()
        source_basis = Matrix(
            (finger_spread_local, hand_length_local, palm_normal_local)
        ).transposed()

        desired_finger_spread = Vector((sign, 0.0, 0.0))
        desired_palm_normal = desired_finger_spread.cross(desired_hand_length).normalized()
        hand_basis = Matrix(
            (desired_finger_spread, desired_hand_length, desired_palm_normal)
        ).transposed()

        target = empty(f"{side.upper()} climber hand rotation")
        target.rotation_mode = "QUATERNION"
        target.rotation_quaternion = (
            hand_basis @ source_basis.transposed()
        ).to_quaternion()
        copy_world_rotation(rig, f"hand_{side}", target)
        rotation_targets[side] = target

        for finger in ("index", "middle", "ring", "pinky"):
            for joint in ("01", "02", "03"):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (0.0, 0.0, 0.0)
        for joint in ("01", "02", "03"):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (0.0, 0.0, 0.0)
    return rotation_targets


def animate(rig, standing_foot_rotations):
    """Create the controlled-speed, airborne mountain-climber form guide.

    Form references (accessed 2026-08-26):
    - American Council on Exercise, Mountain Climbers:
      https://www.acefitness.org/resources/everyone/exercise-library/258/mountain-climbers/
    - National Academy of Sports Medicine, workout finishers:
      https://blog.nasm.org/finishing-touches-mountain-climbers-other-quick-burning-workout-finishers

    Both references emphasize firm hands, shoulders over wrists, a braced flat
    back and alternating hip flexion while the opposite leg extends. Those are
    encoded below and asserted again by ``validate_motion``.
    """
    # Establish the approved shoulder anchor before adding the small clearance
    # channel used while a knee passes under the low-poly athlete's pelvis.
    rig.location = (0.0, 0.586, 0.053)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (math.radians(70.0), 0.0, 0.0)
    rig.scale = (1.0, 1.0, 1.0)
    bpy.context.view_layer.update()
    shoulder_target = (
        world_bone_head(rig, "upperarm_l")
        + world_bone_head(rig, "upperarm_r")
    ) * 0.5
    shoulder_target.z += SHOULDER_CLEARANCE_LIFT

    hand_targets = {
        "l": empty("L climber palm target", (0.220, -0.545, PALM_TARGET_Z)),
        "r": empty("R climber palm target", (-0.220, -0.545, PALM_TARGET_Z)),
    }
    elbow_poles = {
        "l": empty("L climber elbow pole", (0.365, -0.525, 0.245)),
        "r": empty("R climber elbow pole", (-0.365, -0.525, 0.245)),
    }
    for side in ("l", "r"):
        arm_ik = add_ik(
            rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side]
        )
        # A modest 12-14% reach increase straightens the weight-bearing arms
        # and raises the base plank enough to avoid a large visible hip hike.
        arm_ik.use_stretch = True
        rig.pose.bones[f"upperarm_{side}"].ik_stretch = 1.0
        rig.pose.bones[f"lowerarm_{side}"].ik_stretch = 1.0
    configure_flat_hands(rig)

    foot_targets = {
        "l": empty("L climber foot target"),
        "r": empty("R climber foot target"),
    }
    knee_poles = {
        "l": empty("L climber knee pole"),
        "r": empty("R climber knee pole"),
    }
    foot_rotation_targets = {}
    foot_rotation_bases = {}
    for side in ("l", "r"):
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotation = empty(f"{side.upper()} climber foot rotation")
        foot_rotation.matrix_world = standing_foot_rotations[side]
        foot_rotation.rotation_mode = "QUATERNION"
        foot_rotation_bases[side] = foot_rotation.rotation_quaternion.copy()
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)
        foot_rotation_targets[side] = foot_rotation

    leg_lengths = {
        side: (
            rig.data.bones[f"thigh_{side}"].length,
            rig.data.bones[f"calf_{side}"].length,
        )
        for side in ("l", "r")
    }
    support_distances = {
        side: math.sqrt(
            upper_length ** 2
            + lower_length ** 2
            - 2.0
            * upper_length
            * lower_length
            * math.cos(math.radians(SUPPORT_KNEE_ANGLE))
        )
        for side, (upper_length, lower_length) in leg_lengths.items()
    }

    def sagittal_knee_pole(side, hip, ankle):
        """Keep the two-bone IK knee on one continuous forward branch."""
        leg_axis = ankle - hip
        distance = leg_axis.length
        if distance < 1e-6:
            raise RuntimeError(f"{side} hip and ankle targets overlap")
        unit_axis = leg_axis.normalized()
        upper_length, lower_length = leg_lengths[side]
        along = (
            upper_length ** 2 - lower_length ** 2 + distance ** 2
        ) / (2.0 * distance)
        circle_center = hip + unit_axis * along
        pole_direction = Vector((0.0, -unit_axis.z, unit_axis.y))
        if pole_direction.length < 1e-6:
            pole_direction = Vector((0.0, -1.0, 0.0))
        pole_direction.normalize()
        if pole_direction.y > 0.0:
            pole_direction.negate()
        return circle_center + pole_direction * 1.20

    def align_knee_to_leg_center(side, pole_location, target_x):
        """Solve pole X so the evaluated knee stays in its sagittal lane."""
        low = -2.0
        high = 2.0
        for _ in range(12):
            middle = (low + high) * 0.5
            knee_poles[side].location = (
                middle,
                pole_location.y,
                pole_location.z,
            )
            bpy.context.view_layer.update()
            knee_x = world_bone_head(rig, f"calf_{side}").x
            if knee_x < target_x:
                low = middle
            else:
                high = middle
        knee_poles[side].location.x = (low + high) * 0.5
        bpy.context.view_layer.update()

    for frame in range(1, FRAME_END + 1):
        left_drive, right_drive = drive_amounts(frame)
        switch = switch_direction(frame)
        pike = pike_clearance_amount(left_drive)
        rig.rotation_euler = (
            math.radians(BASE_RIG_ROTATION + PIKE_ROTATION * pike),
            0.0,
            0.0,
        )
        rig.location = (0.0, 0.0, 0.0)
        bpy.context.view_layer.update()
        shoulder = (
            world_bone_head(rig, "upperarm_l")
            + world_bone_head(rig, "upperarm_r")
        ) * 0.5
        rig.location = shoulder_target - shoulder
        bpy.context.view_layer.update()
        rig.keyframe_insert("rotation_euler", frame=frame)
        rig.keyframe_insert("location", frame=frame)

        for side, sign, drive in (
            ("l", 1.0, left_drive),
            ("r", -1.0, right_drive),
        ):
            # Contact endpoints preserve the rear forefoot and flat front-foot
            # placements. Both feet follow a lifted arc; the retreating foot
            # also clears backward first so the legs cross like a running
            # stride instead of folding into a symmetric frog jump.
            hip = world_bone_head(rig, f"thigh_{side}")
            foot_x = (
                SUPPORT_ANKLE_X
                + (ACTIVE_ANKLE_X - SUPPORT_ANKLE_X) * drive
            ) * sign
            support_delta_x = foot_x - hip.x
            support_delta_z = SUPPORT_ANKLE_Z - hip.z
            support_delta_y_squared = (
                support_distances[side] ** 2
                - support_delta_x ** 2
                - support_delta_z ** 2
            )
            if support_delta_y_squared <= 0.0:
                raise RuntimeError(
                    f"No support-leg reach solution at frame {frame}: {side}"
                )
            support_y = hip.y + math.sqrt(support_delta_y_squared)
            airborne = flight_amount(drive)
            advancing = switch if side == "l" else -switch
            retreating = max(0.0, -advancing) ** 2
            foot_targets[side].location = (
                foot_x,
                support_y
                + (ACTIVE_ANKLE_Y - support_y) * drive
                + RETREATING_FOOT_Y * retreating,
                SUPPORT_ANKLE_Z
                + (ACTIVE_ANKLE_Z - SUPPORT_ANKLE_Z) * drive
                + FLIGHT_CLEARANCE_Z * airborne
                + ADVANCING_FOOT_Z * max(0.0, advancing) ** 2,
            )
            foot_targets[side].keyframe_insert("location", frame=frame)
            bpy.context.view_layer.update()

            smooth_drive = drive * drive * (3.0 - 2.0 * drive)
            support_angle = (
                SUPPORT_FOOT_ANGLE * (1.0 - smooth_drive)
                + FLIGHT_FOOT_ANGLE * airborne
            )
            foot_rotation_targets[side].rotation_quaternion = (
                Quaternion((1.0, 0.0, 0.0), math.radians(support_angle))
                @ foot_rotation_bases[side]
            )
            foot_rotation_targets[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )

            # A geometric pole follows the hip-ankle plane rather than an
            # independent linear path. This prevents Blender from switching
            # to the opposite two-bone IK solution midway through each drive.
            ankle_target = foot_targets[side].matrix_world.translation
            pole_location = sagittal_knee_pole(side, hip, ankle_target)
            align_knee_to_leg_center(
                side,
                pole_location,
                (hip.x + ankle_target.x) * 0.5,
            )
            knee_poles[side].keyframe_insert("location", frame=frame)

        for side in ("l", "r"):
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].keyframe_insert("location", frame=frame)

    for animated in (
        rig,
        *hand_targets.values(),
        *elbow_poles.values(),
        *foot_targets.values(),
        *foot_rotation_targets.values(),
        *knee_poles.values(),
    ):
        if animated.animation_data is None or animated.animation_data.action is None:
            continue
        for curve in animated.animation_data.action.fcurves:
            for keyframe in curve.keyframe_points:
                keyframe.interpolation = "LINEAR"

    bpy.context.scene.frame_set(1)
    return {
        "hands": hand_targets,
        "feet": foot_targets,
        "foot_rotations": foot_rotation_targets,
        "knees": knee_poles,
    }


def evaluated_vertices(obj_name: str) -> list[Vector]:
    obj = bpy.data.objects[obj_name]
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        return [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
    finally:
        evaluated.to_mesh_clear()


def validate_motion(rig, controls):
    """Reject lost contacts, platform penetration, trunk sway or a bad seam."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    trunk_samples = []
    seam_samples = {}
    full_drives = {"l": 0, "r": 0}
    max_plank_deviation = 0.0
    max_knee_frame_travel = 0.0
    max_knee_frame_acceleration = 0.0
    max_knee_angle_delta = 0.0
    max_knee_angle_acceleration = 0.0
    max_leg_rotation_delta = 0.0
    max_pelvis_delta = 0.0
    max_head_delta = 0.0
    max_arm_stretch = 0.0
    min_transition_foot_yz_separation = float("inf")
    min_transition_knee_yz_separation = float("inf")
    try:
        for frame in KEY_FRAMES:
            scene.frame_set(frame)
            bpy.context.view_layer.update()

            pelvis = world_bone_head(rig, "pelvis")
            lumbar = world_bone_head(rig, "spine_01")
            upper_spine = world_bone_head(rig, "spine_03")
            head = world_bone_head(rig, "head")
            trunk_samples.append((frame, pelvis, lumbar, upper_spine, head))
            shoulders = (
                world_bone_head(rig, "upperarm_l")
                + world_bone_head(rig, "upperarm_r")
            ) * 0.5

            # Arms stay anchored beneath the shoulder girdle throughout all
            # eight exchanges. A small x offset gives natural shoulder width.
            for side in ("l", "r"):
                shoulder = world_bone_head(rig, f"upperarm_{side}")
                wrist = controls["hands"][side].matrix_world.translation
                if abs(shoulder.y - wrist.y) > 0.045:
                    raise RuntimeError(
                        f"Shoulder is not above wrist at frame {frame}: "
                        f"{side} dy={abs(shoulder.y - wrist.y):.4f}m"
                    )
                if abs(wrist.z - PALM_TARGET_Z) > 0.001:
                    raise RuntimeError(f"Palm target lost platform contact at frame {frame}")

            left_drive, right_drive = drive_amounts(frame)
            if abs(left_drive + right_drive - 1.0) > 0.0001:
                raise RuntimeError(
                    f"Leg switch lost complementary timing at frame {frame}"
                )
            if abs(switch_direction(frame)) > 0.99:
                left_foot_y = controls["feet"]["l"].matrix_world.translation.y
                right_foot_y = controls["feet"]["r"].matrix_world.translation.y
                separation = abs(left_foot_y - right_foot_y)
                if separation < MIN_MIDFLIGHT_FOOT_SEPARATION:
                    raise RuntimeError(
                        f"Feet overlapped at mid-flight frame {frame}: "
                        f"separation={separation:.4f}m"
                    )
                left_hip = world_bone_head(rig, "thigh_l")
                right_hip = world_bone_head(rig, "thigh_r")
                left_knee = world_bone_head(rig, "calf_l")
                right_knee = world_bone_head(rig, "calf_r")
                left_ankle = world_bone_head(rig, "foot_l")
                right_ankle = world_bone_head(rig, "foot_r")
                knee_separation = abs(left_knee.y - right_knee.y)
                if knee_separation < MIN_MIDFLIGHT_KNEE_SEPARATION:
                    raise RuntimeError(
                        f"Knees overlapped at mid-flight frame {frame}: "
                        f"separation={knee_separation:.4f}m"
                    )
                knee_angle_difference = abs(
                    joint_angle(left_hip, left_knee, left_ankle)
                    - joint_angle(right_hip, right_knee, right_ankle)
                )
                if knee_angle_difference < MIN_MIDFLIGHT_KNEE_ANGLE_DIFFERENCE:
                    raise RuntimeError(
                        f"Legs folded symmetrically at mid-flight frame {frame}: "
                        f"knee angle difference={knee_angle_difference:.1f} degrees"
                    )
            for side, drive in (("l", left_drive), ("r", right_drive)):
                ankle_target = controls["feet"][side].matrix_world.translation
                switch = switch_direction(frame)
                advancing = switch if side == "l" else -switch
                expected_ankle_z = (
                    SUPPORT_ANKLE_Z + (ACTIVE_ANKLE_Z - SUPPORT_ANKLE_Z) * drive
                    + FLIGHT_CLEARANCE_Z * flight_amount(drive)
                    + ADVANCING_FOOT_Z * max(0.0, advancing) ** 2
                )
                if abs(ankle_target.z - expected_ankle_z) > 0.001:
                    raise RuntimeError(
                        f"Foot target left airborne switch path at frame {frame}"
                    )
                ankle = world_bone_head(rig, f"foot_{side}")
                knee = world_bone_head(rig, f"calf_{side}")
                hip = world_bone_head(rig, f"thigh_{side}")
                elbow = world_bone_head(rig, f"lowerarm_{side}")
                if elbow.z < 0.090:
                    raise RuntimeError(
                        f"Elbow/platform penetration risk at frame {frame}: "
                        f"{side} z={elbow.z:.4f}"
                    )
                if knee.z < 0.105:
                    raise RuntimeError(
                        f"Knee/platform penetration risk at frame {frame}: {side} z={knee.z:.4f}"
                    )
                if drive > 0.99 and knee.y > pelvis.y - 0.10:
                    raise RuntimeError(
                        f"Active knee did not travel toward chest at frame {frame}: {side}"
                    )
                if drive > 0.99 and frame <= ENCODED_FRAMES:
                    full_drives[side] += 1
                if drive < 0.0001 and (ankle - hip).length < 0.700:
                    raise RuntimeError(
                        f"Reset/support leg is not fully extended at frame {frame}: "
                        f"{side} hip-ankle={(ankle - hip).length:.4f}m"
                    )
                if drive < 0.0001:
                    plank_deviation = distance_to_line_yz(pelvis, shoulders, ankle)
                    max_plank_deviation = max(max_plank_deviation, plank_deviation)
                    if plank_deviation > 0.040:
                        raise RuntimeError(
                            f"Pike/sag alignment failed at frame {frame}: pelvis is "
                            f"{plank_deviation:.4f}m from shoulder-support-ankle line"
                        )

            if frame in (1, 241):
                seam_samples[frame] = {
                    "pelvis": pelvis.copy(),
                    "left_knee": world_bone_head(rig, "calf_l"),
                    "right_knee": world_bone_head(rig, "calf_r"),
                    "left_foot": controls["feet"]["l"].matrix_world.translation.copy(),
                    "right_foot": controls["feet"]["r"].matrix_world.translation.copy(),
                }

        # Every landing inspection frame returns to the same braced torso. The
        # small clearance pulse exists only while both feet exchange in flight.
        baseline = trunk_samples[0][1:]
        for frame, *points in trunk_samples[1:]:
            left_drive, _ = drive_amounts(frame)
            if flight_amount(left_drive) > 0.01:
                continue
            for label, current, start in zip(
                ("pelvis", "lumbar", "upper spine", "head"), points, baseline
            ):
                if (current - start).length > 0.004:
                    raise RuntimeError(
                        f"Upper-body sway at frame {frame}: {label} moved "
                        f"{(current - start).length:.4f}m"
                    )

        # Neutral alignment is collinearity, not a horizontal torso. The whole
        # trunk intentionally slopes from the shoulders toward the forefeet.
        for frame, pelvis, lumbar, upper_spine, head in trunk_samples:
            for label, point in (("lumbar", lumbar), ("upper spine", upper_spine)):
                deviation = distance_to_line_yz(point, pelvis, head)
                if deviation > 0.050:
                    raise RuntimeError(
                        f"Neutral spine failed at frame {frame}: {label} is "
                        f"{deviation:.4f}m from pelvis-head line"
                    )
            if max(abs(point.x) for point in (pelvis, lumbar, upper_spine, head)) > 0.012:
                raise RuntimeError(f"Trunk shifted laterally at frame {frame}")

        for name, start in seam_samples[1].items():
            end = seam_samples[241][name]
            if (end - start).length > 0.0005:
                raise RuntimeError(
                    f"Loop seam mismatch for {name}: {(end - start).length:.6f}m"
                )

        if full_drives != {"l": CLIMBER_CYCLES, "r": CLIMBER_CYCLES}:
            raise RuntimeError(
                f"Alternation count mismatch: expected {CLIMBER_CYCLES} per side, "
                f"got {full_drives}"
            )

        # Contact previews alone can miss an IK branch switch between sampled
        # frames. Inspect every encoded frame plus the duplicate loop endpoint
        # for sagittal tracking, joint continuity and rotation continuity.
        knee_positions = {"l": [], "r": []}
        knee_angles = {"l": [], "r": []}
        leg_rotations = {"l": {}, "r": {}}
        shoulder_baseline = None
        pelvis_baseline = None
        head_baseline = None
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            left_drive, right_drive = drive_amounts(frame)
            if abs(left_drive + right_drive - 1.0) > 0.0001:
                raise RuntimeError(
                    f"Leg switch lost complementary timing at frame {frame}"
                )
            shoulders = (
                world_bone_head(rig, "upperarm_l")
                + world_bone_head(rig, "upperarm_r")
            ) * 0.5
            pelvis = world_bone_head(rig, "pelvis")
            head = world_bone_head(rig, "head")
            if shoulder_baseline is None:
                shoulder_baseline = shoulders.copy()
                pelvis_baseline = pelvis.copy()
                head_baseline = head.copy()
            shoulder_delta = (shoulders - shoulder_baseline).length
            if shoulder_delta > 0.002:
                raise RuntimeError(
                    f"Shoulder anchor drift at frame {frame}: "
                    f"delta={shoulder_delta:.4f}m"
                )
            max_pelvis_delta = max(
                max_pelvis_delta, (pelvis - pelvis_baseline).length
            )
            max_head_delta = max(max_head_delta, (head - head_baseline).length)
            if max_pelvis_delta > MAX_PELVIS_DELTA:
                raise RuntimeError(
                    f"Hip-clearance pulse is too large at frame {frame}: "
                    f"pelvis delta={max_pelvis_delta:.4f}m"
                )
            if max_head_delta > MAX_HEAD_DELTA:
                raise RuntimeError(
                    f"Head motion is too large at frame {frame}: "
                    f"delta={max_head_delta:.4f}m"
                )

            for side, drive in (("l", left_drive), ("r", right_drive)):
                hip = world_bone_head(rig, f"thigh_{side}")
                knee = world_bone_head(rig, f"calf_{side}")
                ankle = world_bone_head(rig, f"foot_{side}")

                for bone_name in (f"upperarm_{side}", f"lowerarm_{side}"):
                    child_name = (
                        f"lowerarm_{side}"
                        if bone_name.startswith("upperarm")
                        else f"hand_{side}"
                    )
                    stretch = (
                        world_bone_head(rig, child_name)
                        - world_bone_head(rig, bone_name)
                    ).length / rig.data.bones[bone_name].length
                    max_arm_stretch = max(max_arm_stretch, stretch)
                    if stretch > 1.15:
                        raise RuntimeError(
                            f"Arm IK stretched too far at frame {frame}: "
                            f"{bone_name} ratio={stretch:.3f}"
                        )

                outside_x = max(
                    min(hip.x, ankle.x) - knee.x,
                    knee.x - max(hip.x, ankle.x),
                    0.0,
                )
                if outside_x > 0.030:
                    raise RuntimeError(
                        f"Knee left sagittal corridor at frame {frame}: "
                        f"{side} outside={outside_x:.4f}m"
                    )

                knee_angle = joint_angle(hip, knee, ankle)
                if knee.z < 0.105:
                    raise RuntimeError(
                        f"Knee/platform penetration risk at frame {frame}: "
                        f"{side} z={knee.z:.4f}"
                    )
                if knee_angle < 40.0:
                    raise RuntimeError(
                        f"Knee collapsed at frame {frame}: "
                        f"{side} angle={knee_angle:.1f} degrees"
                    )
                if drive < 0.0001 and knee_angle < 164.5:
                    raise RuntimeError(
                        f"Support knee stayed bent at frame {frame}: "
                        f"{side} angle={knee_angle:.1f} degrees"
                    )
                if drive > 0.99 and knee_angle > 60.0:
                    raise RuntimeError(
                        f"Active knee did not flex enough at frame {frame}: "
                        f"{side} angle={knee_angle:.1f} degrees"
                    )

                angle_history = knee_angles[side]
                angle_history.append(knee_angle)
                if len(angle_history) >= 2:
                    angle_delta = abs(angle_history[-1] - angle_history[-2])
                    max_knee_angle_delta = max(max_knee_angle_delta, angle_delta)
                    if angle_delta > MAX_KNEE_ANGLE_DELTA:
                        raise RuntimeError(
                            f"Knee angle jump at frame {frame}: "
                            f"{side} delta={angle_delta:.1f}deg/frame"
                        )
                    # Unlike the former sliding path, a jump briefly compresses
                    # the departing knee as its foot rises. Continuity is
                    # therefore governed by per-frame angle, acceleration and
                    # bone-rotation limits instead of strict monotonic flexion.
                if len(angle_history) >= 3:
                    angle_acceleration = abs(
                        angle_history[-1]
                        - 2.0 * angle_history[-2]
                        + angle_history[-3]
                    )
                    max_knee_angle_acceleration = max(
                        max_knee_angle_acceleration, angle_acceleration
                    )
                    if angle_acceleration > MAX_KNEE_ANGLE_ACCELERATION:
                        raise RuntimeError(
                            f"Knee angular acceleration at frame {frame}: "
                            f"{side} value={angle_acceleration:.1f}deg/frame^2"
                        )

                history = knee_positions[side]
                history.append(knee.copy())
                if len(history) >= 2:
                    travel = (history[-1] - history[-2]).length
                    max_knee_frame_travel = max(max_knee_frame_travel, travel)
                    if travel > MAX_KNEE_FRAME_TRAVEL:
                        raise RuntimeError(
                            f"Knee IK jump at frame {frame}: "
                            f"{side} travel={travel:.4f}m/frame"
                        )
                if len(history) >= 3:
                    acceleration = (
                        history[-1] - 2.0 * history[-2] + history[-3]
                    ).length
                    max_knee_frame_acceleration = max(
                        max_knee_frame_acceleration, acceleration
                    )
                    if acceleration > MAX_KNEE_FRAME_ACCELERATION:
                        raise RuntimeError(
                            f"Knee trajectory kink at frame {frame}: "
                            f"{side} acceleration={acceleration:.4f}m/frame^2"
                        )

                for bone_name in (f"thigh_{side}", f"calf_{side}"):
                    rotation = (
                        rig.matrix_world @ rig.pose.bones[bone_name].matrix
                    ).to_quaternion()
                    previous = leg_rotations[side].get(bone_name)
                    if previous is not None:
                        rotation_delta = math.degrees(
                            previous.rotation_difference(rotation).angle
                        )
                        rotation_delta = min(rotation_delta, 360.0 - rotation_delta)
                        max_leg_rotation_delta = max(
                            max_leg_rotation_delta, rotation_delta
                        )
                        if rotation_delta > MAX_LEG_ROTATION_DELTA:
                            raise RuntimeError(
                                f"Leg IK rotation jump at frame {frame}: "
                                f"{bone_name} delta={rotation_delta:.1f}deg/frame"
                            )
                    leg_rotations[side][bone_name] = rotation.copy()

            # A single midpoint sample missed the former frog-like overlap:
            # the feet actually crossed two frames earlier. Inspect the whole
            # central exchange instead. Since the signed knee-angle difference
            # must pass through zero when left/right roles swap, accept either
            # visible knee separation or a clearly different amount of flexion.
            if (
                TRANSITION_DRIVE_MIN <= left_drive <= 1.0 - TRANSITION_DRIVE_MIN
                and TRANSITION_DRIVE_MIN
                <= right_drive
                <= 1.0 - TRANSITION_DRIVE_MIN
            ):
                left_foot = controls["feet"]["l"].matrix_world.translation
                right_foot = controls["feet"]["r"].matrix_world.translation
                foot_yz_separation = math.hypot(
                    left_foot.y - right_foot.y,
                    left_foot.z - right_foot.z,
                )
                min_transition_foot_yz_separation = min(
                    min_transition_foot_yz_separation, foot_yz_separation
                )
                if foot_yz_separation < MIN_TRANSITION_FOOT_YZ_SEPARATION:
                    raise RuntimeError(
                        f"Feet merged during running switch at frame {frame}: "
                        f"YZ separation={foot_yz_separation:.4f}m"
                    )

                left_hip = world_bone_head(rig, "thigh_l")
                right_hip = world_bone_head(rig, "thigh_r")
                left_knee = world_bone_head(rig, "calf_l")
                right_knee = world_bone_head(rig, "calf_r")
                left_ankle = world_bone_head(rig, "foot_l")
                right_ankle = world_bone_head(rig, "foot_r")
                knee_yz_separation = math.hypot(
                    left_knee.y - right_knee.y,
                    left_knee.z - right_knee.z,
                )
                min_transition_knee_yz_separation = min(
                    min_transition_knee_yz_separation, knee_yz_separation
                )
                knee_angle_difference = abs(
                    joint_angle(left_hip, left_knee, left_ankle)
                    - joint_angle(right_hip, right_knee, right_ankle)
                )
                if (
                    knee_yz_separation < MIN_TRANSITION_KNEE_YZ_SEPARATION
                    and knee_angle_difference
                    < MIN_TRANSITION_KNEE_ANGLE_DIFFERENCE
                ):
                    raise RuntimeError(
                        f"Legs folded together during running switch at frame "
                        f"{frame}: knee YZ separation={knee_yz_separation:.4f}m, "
                        f"angle difference={knee_angle_difference:.1f} degrees"
                    )

        # Mesh checks cover both contact poses and both halves of the airborne
        # exchange. Surface contact is allowed only at the endpoints.
        for frame in MESH_CHECK_FRAMES:
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            mesh_lows = {
                name: min(point.z for point in evaluated_vertices(name))
                for name in CLEARANCE_MESHES
            }
            lowest = min(mesh_lows.values())
            print(
                "CONTACT_METRICS",
                f"frame={frame}",
                " ".join(f"{name}={value:.4f}" for name, value in mesh_lows.items()),
            )
            if lowest < PLATFORM_TOP_Z - 0.012:
                raise RuntimeError(
                    f"Athlete/platform penetration at frame {frame}: min z={lowest:.4f}m"
                )

            hand_points = [
                point
                for point in evaluated_vertices("Human")
                if point.y < -0.430 and 0.105 < abs(point.x) < 0.390
            ]
            if not hand_points or min(point.z for point in hand_points) > 0.030:
                hand_min = min((point.z for point in hand_points), default=999.0)
                raise RuntimeError(
                    f"Palm mesh does not reach platform at frame {frame}: min z={hand_min:.4f}m"
                )

            shoe_points = evaluated_vertices("Human.shoes05")
            left_drive, right_drive = drive_amounts(frame)
            foot_metrics = []
            for side, sign, drive in (
                ("l", 1.0, left_drive),
                ("r", -1.0, right_drive),
            ):
                side_points = [point for point in shoe_points if point.x * sign > 0.0]
                shoe_min = min((point.z for point in side_points), default=999.0)
                if shoe_min < PLATFORM_TOP_Z - 0.012:
                    raise RuntimeError(
                        f"{side} shoe penetrates platform at frame {frame}: "
                        f"min z={shoe_min:.4f}m"
                    )
                minimum_y = min(point.y for point in side_points)
                maximum_y = max(point.y for point in side_points)
                span_y = maximum_y - minimum_y
                forefoot = [
                    point
                    for point in side_points
                    if point.y <= minimum_y + span_y * 0.40
                ]
                heel = [
                    point
                    for point in side_points
                    if point.y >= minimum_y + span_y * 0.65
                ]
                forefoot_min = min(point.z for point in forefoot)
                heel_min = min(point.z for point in heel)
                if drive < 0.01:
                    foot_metrics.append(
                        f"{side}:rear-toe={forefoot_min:.4f},heel={heel_min:.4f}"
                    )
                    if not -0.012 <= forefoot_min <= 0.008:
                        raise RuntimeError(
                            f"{side} forefoot contact failed at frame {frame}: "
                            f"z={forefoot_min:.4f}m"
                        )
                    if heel_min < 0.025 or heel_min - forefoot_min < 0.025:
                        raise RuntimeError(
                            f"{side} heel did not clear platform at frame {frame}: "
                            f"toe={forefoot_min:.4f}m heel={heel_min:.4f}m"
                        )
                elif drive > 0.99:
                    foot_metrics.append(
                        f"{side}:front-min={shoe_min:.4f},"
                        f"toe={forefoot_min:.4f},heel={heel_min:.4f}"
                    )
                    if not -0.012 <= shoe_min <= 0.008:
                        raise RuntimeError(
                            f"{side} front foot did not land at frame {frame}: "
                            f"min z={shoe_min:.4f}m"
                        )
                    if max(forefoot_min, heel_min) > 0.025:
                        raise RuntimeError(
                            f"{side} front-foot landing is not flat at frame {frame}: "
                            f"toe={forefoot_min:.4f}m heel={heel_min:.4f}m"
                        )
                else:
                    foot_metrics.append(
                        f"{side}:airborne={shoe_min:.4f},drive={drive:.3f}"
                    )
                    if 0.10 <= drive <= 0.90 and shoe_min < MIN_FLIGHT_SHOE_CLEARANCE:
                        raise RuntimeError(
                            f"{side} shoe did not clear platform during switch at "
                            f"frame {frame}: min z={shoe_min:.4f}m"
                        )
            print("FOOT_CONTACT", f"frame={frame}", " ".join(foot_metrics))

        print(
            "MOUNTAIN_CLIMBER_CHECK PASS",
            f"cycles={CLIMBER_CYCLES}",
            f"frames={KEY_FRAMES}",
            f"max_plank_deviation={max_plank_deviation:.4f}m",
            f"max_knee_travel={max_knee_frame_travel:.4f}m/frame",
            f"max_knee_acceleration={max_knee_frame_acceleration:.4f}m/frame^2",
            f"max_knee_angle_delta={max_knee_angle_delta:.1f}deg/frame",
            f"max_knee_angle_acceleration={max_knee_angle_acceleration:.1f}deg/frame^2",
            f"max_leg_rotation_delta={max_leg_rotation_delta:.1f}deg/frame",
            f"max_pelvis_delta={max_pelvis_delta:.4f}m",
            f"max_head_delta={max_head_delta:.4f}m",
            f"max_arm_stretch={max_arm_stretch:.3f}x",
            f"min_transition_foot_yz={min_transition_foot_yz_separation:.4f}m",
            f"min_transition_knee_yz={min_transition_knee_yz_separation:.4f}m",
            "hands=fixed-platform-contact",
            "feet=simultaneous-airborne-switch",
            "landings=rear-forefoot-and-flat-front-foot",
            "trunk=neutral-stable",
            "loop=frame-1-equals-241",
        )
    finally:
        scene.frame_set(original_frame)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        # A slightly elevated head-end camera exposes both palm contacts and
        # the alternating knee tracks without hiding the support leg.
        ("front", (0.0, -3.45, 1.35), (0.0, -0.08, 0.275), 58),
        ("side", (3.20, -0.10, 0.92), (0.0, -0.10, 0.285), 56),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Mountain climber {name} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera

    for name, location, energy, size, color in (
        ("Key", (-2.5, -2.7, 3.0), 980, 2.8, (1.0, 0.92, 0.84)),
        ("Fill", (2.5, -1.3, 2.1), 470, 2.3, (0.78, 0.88, 1.0)),
        ("Rim", (0.3, 2.2, 2.5), 650, 2.0, (0.72, 0.62, 1.0)),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        lamp = bpy.context.object
        lamp.name = f"Mountain climber {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, -0.10, 0.32)))
    return cameras


def render_contact_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Mountain climber contact camera"
    camera.data.lens = 72
    scene.camera = camera
    inspections = (
        (
            "hands",
            TOP_FRAME,
            (0.72, -1.38, 0.48),
            (0.0, -0.545, 0.045),
        ),
        (
            "left_foot_support",
            TOP_FRAME,
            (0.95, 0.95, 0.42),
            (0.115, 0.535, 0.035),
        ),
        (
            "right_foot_support",
            BOTTOM_FRAME,
            (-0.95, 0.95, 0.42),
            (-0.115, 0.535, 0.035),
        ),
    )
    for suffix, frame, location, target in inspections:
        scene.frame_set(frame)
        camera.location = location
        look_at(camera, Vector(target))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_contact_{suffix}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("CONTACT_PREVIEW", scene.render.filepath)


def render_previews(cameras, output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    os.makedirs(preview_dir, exist_ok=True)
    for frame, suffix in (
        (TOP_FRAME, "top"),
        (MID_FRAME, "mid"),
        (BOTTOM_FRAME, "bottom"),
    ):
        scene.frame_set(frame)
        for name, camera in cameras.items():
            scene.camera = camera
            scene.render.image_settings.file_format = "PNG"
            scene.render.filepath = os.path.join(
                preview_dir, f"human_{EXERCISE}_{name}_{suffix}.png"
            )
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)
    render_contact_previews(output_dir)


def render_movies(cameras, output_dir):
    scene = bpy.context.scene
    os.makedirs(output_dir, exist_ok=True)
    for name, camera in cameras.items():
        scene.camera = camera
        scene.frame_start = 1
        scene.frame_end = ENCODED_FRAMES
        movie_path = os.path.join(output_dir, f"{EXERCISE}_{name}.mp4")
        try:
            scene.render.image_settings.file_format = "FFMPEG"
            native_ffmpeg = True
        except TypeError:
            native_ffmpeg = False
        if native_ffmpeg:
            scene.render.ffmpeg.format = "MPEG4"
            scene.render.ffmpeg.codec = "H264"
            scene.render.ffmpeg.constant_rate_factor = "MEDIUM"
            scene.render.ffmpeg.ffmpeg_preset = "GOOD"
            scene.render.ffmpeg.audio_codec = "NONE"
            scene.render.filepath = movie_path
            bpy.ops.render.render(animation=True)
        else:
            ffmpeg = shutil.which("ffmpeg")
            if not ffmpeg:
                raise RuntimeError(
                    "Blender has no FFmpeg output and system ffmpeg is unavailable"
                )
            with tempfile.TemporaryDirectory(
                prefix=f"healthtask-{EXERCISE}-{name}-"
            ) as frame_dir:
                scene.render.image_settings.file_format = "PNG"
                scene.render.image_settings.color_mode = "RGB"
                scene.render.filepath = os.path.join(frame_dir, "frame_")
                bpy.ops.render.render(animation=True)
                subprocess.run(
                    [
                        ffmpeg,
                        "-y",
                        "-loglevel",
                        "warning",
                        "-framerate",
                        str(FPS),
                        "-i",
                        os.path.join(frame_dir, "frame_%04d.png"),
                        "-c:v",
                        "libx264",
                        "-preset",
                        "medium",
                        "-crf",
                        "23",
                        "-pix_fmt",
                        "yuv420p",
                        "-movflags",
                        "+faststart",
                        "-an",
                        movie_path,
                    ],
                    check=True,
                )
        print("MOVIE", movie_path)


def main():
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    configure_scene()
    rig, standing_foot_rotations = reset_squat_scene()
    configure_athlete_materials()
    controls = animate(rig, standing_foot_rotations)
    validate_motion(rig, controls)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode in ("preview", "build"):
        render_previews(cameras, output_dir)
    if args.mode in ("render", "build"):
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
