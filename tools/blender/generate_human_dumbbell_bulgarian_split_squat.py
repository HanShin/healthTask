"""Build the offline dumbbell Bulgarian split-squat motion guide.

Run from the packed approved squat athlete, for example::

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_dumbbell_bulgarian_split_squat.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/dumbbell_bulgarian_split_squat_human_sample.blend \
      --mode preview

The left foot is the working/front foot and the right instep is fixed to a
transverse flat bench.  Both feet remain on separate hip-width rails while the
pelvis descends vertically.  Compact dumbbells hang beside the thighs in a
closed neutral grip.  The generated loop contains 241 Blender frames; frames
1 through 240 encode the exact eight-second, 30 fps movie and frame 241 is an
exact copy of frame 1 for loop-seam validation.

Form references:
  - https://www.nasm.org/resource-center/exercise-library/bulgarian-split-squat
  - https://www.acefitness.org/resources/everyone/exercise-library/366/bulgarian-split-squat/
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
    add_ik,
    configure_athlete_materials,
    copy_world_rotation,
    empty,
    reset_squat_scene,
)
from generate_squat_sample import (  # noqa: E402
    P,
    configure_scene,
    cylinder,
    look_at,
    material,
    rounded_cube,
    smoothstep,
)
from motion_collision import assert_no_mesh_intersections  # noqa: E402


EXERCISE = "dumbbell_bulgarian_split_squat"
FPS = 30
FRAME_END = 241
ENCODED_FRAMES = FRAME_END - 1
TOP_FRAME = 1
MID_FRAME = 61
BOTTOM_FRAME = 121
KEY_FRAMES = (TOP_FRAME, MID_FRAME, BOTTOM_FRAME, FRAME_END)
COLLISION_FRAMES = tuple(range(1, FRAME_END + 1, 15))

# The athlete faces world -Y.  Left and right remain on separate X rails;
# only the sagittal Y positions differ.  These values are solved against the
# approved rig's 0.36209 m thigh and 0.35148 m calf.
FRONT_ANKLE = Vector((0.1543, -0.2200, 0.0622))
REAR_ANKLE = Vector((-0.1543, 0.4200, 0.3570))
TOP_ROOT_Z = -0.040
BOTTOM_ROOT_Z = -0.380
FRONT_KNEE_POLE = Vector((0.350, -0.760, 0.320))
REAR_KNEE_POLE = Vector((-0.350, 0.080, -0.180))
REAR_FOOT_PITCH_DEGREES = 150.0

# The approved 1.22 x 0.30 m flat-bench pad is turned across the athlete.  Its
# top is fractionally below the right shoe's laces so the instep reads as
# supported without mesh penetration.
BENCH_CENTER_Y = 0.560
BENCH_PAD_CENTER_Z = 0.257
BENCH_PAD_HALF = Vector((0.610, 0.150, 0.050))
BENCH_TOP = BENCH_PAD_CENTER_Z + BENCH_PAD_HALF.z
BENCH_FRONT = BENCH_CENTER_Y - BENCH_PAD_HALF.y
BENCH_BACK = BENCH_CENTER_Y + BENCH_PAD_HALF.y

DUMBBELL_HANDLE_RADIUS = 0.016
DUMBBELL_HANDLE_LENGTH = 0.200
DUMBBELL_PLATE_RADIUS = 0.078
DUMBBELL_PLATE_THICKNESS = 0.050
DUMBBELL_PLATE_CENTER = 0.105
DUMBBELL_CAP_RADIUS = 0.052
DUMBBELL_CAP_THICKNESS = 0.006
DUMBBELL_CAP_CENTER = 0.133

ATHLETE_MESHES = (
    "Human",
    "Human.eyebrow004",
    "Human.eyelashes01",
    "Human.female_sportsuit01",
    "Human.low-poly",
    "Human.ponytail01",
    "Human.shoes05",
    "Rigged compression shorts",
)
BENCH_COLLIDERS = (
    "BSS bench pad",
    "BSS bench spine",
    "BSS bench leg -0.40",
    "BSS bench foot -0.40",
    "BSS bench leg +0.40",
    "BSS bench foot +0.40",
)
BENCH_FRAME_COLLIDERS = BENCH_COLLIDERS[1:]
ATHLETE_WITHOUT_SHOES = tuple(
    name for name in ATHLETE_MESHES if name != "Human.shoes05"
)
DUMBBELL_PLATE_COLLIDERS = tuple(
    f"{side} BSS dumbbell {part} {end_sign:+d}"
    for side in ("L", "R")
    for part in ("plate", "cap")
    for end_sign in (-1, 1)
)
DUMBBELL_COMPONENTS = {
    side: (
        f"{side} BSS dumbbell handle",
        *(f"{side} BSS dumbbell plate {end_sign:+d}" for end_sign in (-1, 1)),
        *(f"{side} BSS dumbbell cap {end_sign:+d}" for end_sign in (-1, 1)),
    )
    for side in ("L", "R")
}
GRIP_DIGITS = ("index", "middle", "ring", "pinky", "thumb")
GRIP_FINGERS = GRIP_DIGITS[:-1]


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument("--preview-dir")
    parser.add_argument(
        "--mode",
        choices=("preview", "grip", "contact", "render", "validate"),
        default="preview",
    )
    return parser.parse_args(argv)


def split_squat_depth(frame: int) -> float:
    """One controlled repetition with readable endpoint holds."""
    if frame <= 15:
        return 0.0
    if frame <= 105:
        return smoothstep((frame - 15) / 90.0)
    if frame <= 135:
        return 1.0
    if frame <= 225:
        return 1.0 - smoothstep((frame - 135) / 90.0)
    return 0.0


def root_height(depth: float) -> float:
    return TOP_ROOT_Z + (BOTTOM_ROOT_Z - TOP_ROOT_Z) * depth


def bone_point(rig, bone_name: str, endpoint: str = "head") -> Vector:
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def joint_angle(first: Vector, joint: Vector, last: Vector) -> float:
    return math.degrees((first - joint).angle(last - joint))


def quaternion_delta_degrees(first: Quaternion, second: Quaternion) -> float:
    delta = math.degrees(first.rotation_difference(second).angle)
    return min(delta, 360.0 - delta)


def evaluated_vertices(obj_name: str) -> list[Vector]:
    obj = bpy.data.objects[obj_name]
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        return [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
    finally:
        evaluated.to_mesh_clear()


def build_bench():
    mats = {
        "pad": material(
            "BSS bench pad material",
            (0.035, 0.045, 0.075, 1.0),
            roughness=0.48,
        ),
        "frame": material(
            "BSS bench steel",
            P.metal,
            metallic=0.92,
            roughness=0.18,
        ),
    }
    rounded_cube(
        "BSS bench pad",
        (0.0, BENCH_CENTER_Y, BENCH_PAD_CENTER_Z),
        BENCH_PAD_HALF,
        mats["pad"],
        bevel=0.035,
    )
    rounded_cube(
        "BSS bench spine",
        (0.0, BENCH_CENTER_Y, 0.180),
        (0.500, 0.035, 0.035),
        mats["frame"],
        bevel=0.018,
    )
    for x in (-0.40, 0.40):
        rounded_cube(
            f"BSS bench leg {x:+.2f}",
            (x, BENCH_CENTER_Y, 0.130),
            (0.035, 0.035, 0.105),
            mats["frame"],
            bevel=0.015,
        )
        rounded_cube(
            f"BSS bench foot {x:+.2f}",
            (x, BENCH_CENTER_Y, 0.040),
            (0.045, 0.260, 0.030),
            mats["frame"],
            bevel=0.018,
        )


def build_dumbbells():
    mats = {
        "rubber": material(
            "BSS dumbbell rubber",
            (0.025, 0.032, 0.052, 1.0),
            metallic=0.18,
            roughness=0.34,
        ),
        "handle": material(
            "BSS dumbbell handle material",
            (0.46, 0.52, 0.60, 1.0),
            metallic=0.95,
            roughness=0.14,
        ),
        "teal": material(
            "BSS dumbbell teal",
            P.teal,
            roughness=0.2,
            emission=P.teal,
            emission_strength=1.8,
        ),
        "violet": material(
            "BSS dumbbell violet",
            P.violet,
            roughness=0.2,
            emission=P.violet,
            emission_strength=1.8,
        ),
    }
    dumbbells = {}
    for side, accent in (("l", "violet"), ("r", "teal")):
        label = side.upper()
        root = empty(f"{label} BSS dumbbell root", display="PLAIN_AXES")
        root.empty_display_size = 0.14
        handle = cylinder(
            f"{label} BSS dumbbell handle",
            (0.0, 0.0, 0.0),
            DUMBBELL_HANDLE_RADIUS,
            DUMBBELL_HANDLE_LENGTH,
            mats["handle"],
            vertices=32,
        )
        handle.parent = root
        for end_sign in (-1, 1):
            plate = cylinder(
                f"{label} BSS dumbbell plate {end_sign:+d}",
                (0.0, 0.0, 0.0),
                DUMBBELL_PLATE_RADIUS,
                DUMBBELL_PLATE_THICKNESS,
                mats["rubber"],
                vertices=48,
            )
            plate.parent = root
            plate.location = (0.0, 0.0, DUMBBELL_PLATE_CENTER * end_sign)
            cap = cylinder(
                f"{label} BSS dumbbell cap {end_sign:+d}",
                (0.0, 0.0, 0.0),
                DUMBBELL_CAP_RADIUS,
                DUMBBELL_CAP_THICKNESS,
                mats[accent],
                vertices=48,
            )
            cap.parent = root
            cap.location = (0.0, 0.0, DUMBBELL_CAP_CENTER * end_sign)
        dumbbells[side] = root
    return dumbbells


def hand_rest_basis(rig, side: str) -> tuple[Vector, Vector, Vector]:
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
    return finger_spread_local, hand_length_local, palm_normal_local


def neutral_axes(side: str, desired_length: Vector) -> tuple[Vector, Vector]:
    sign = 1.0 if side == "l" else -1.0
    desired_length = desired_length.normalized()
    inward = Vector((-sign, 0.0, 0.0))
    palm_normal = inward - desired_length * inward.dot(desired_length)
    palm_normal.normalize()
    handle_axis = desired_length.cross(palm_normal).normalized()
    return handle_axis, palm_normal


def hand_grip_quaternion(rig, side: str, desired_length: Vector) -> Quaternion:
    finger_spread_local, hand_length_local, palm_normal_local = hand_rest_basis(
        rig, side
    )
    source_basis = Matrix(
        (finger_spread_local, hand_length_local, palm_normal_local)
    ).transposed()
    desired_spread, desired_normal = neutral_axes(side, desired_length)
    desired_basis = Matrix(
        (desired_spread, desired_length.normalized(), desired_normal)
    ).transposed()
    return (desired_basis @ source_basis.transposed()).to_quaternion()


def dumbbell_quaternion(side: str, desired_length: Vector) -> Quaternion:
    handle_axis, _ = neutral_axes(side, desired_length)
    return Vector((0.0, 0.0, 1.0)).rotation_difference(handle_axis)


def configure_grips(rig):
    rotations = {}
    for side in ("l", "r"):
        sign = 1.0 if side == "l" else -1.0
        target = empty(f"{side.upper()} BSS grip rotation")
        target.rotation_mode = "QUATERNION"
        copy_world_rotation(rig, f"hand_{side}", target)
        rotations[side] = target
        for finger, curls in {
            "index": (90, 30, 18),
            "middle": (84, 38, 40),
            "ring": (80, 40, 34),
            "pinky": (86, 30, 18),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                bone.rotation_mode = "XYZ"
                bone.rotation_euler = (math.radians(degrees * sign), 0.0, 0.0)
        for joint, degrees in (
            ("01", (38, 35, 0)),
            ("02", (52, 0, 0)),
            ("03", (36, 0, 0)),
        ):
            bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            bone.rotation_mode = "XYZ"
            bone.rotation_euler = tuple(math.radians(value) for value in degrees)
    return rotations


def pole_for_elbow(shoulder: Vector, wrist: Vector, elbow: Vector) -> Vector:
    shoulder_to_wrist = wrist - shoulder
    projection = shoulder + shoulder_to_wrist * (
        (elbow - shoulder).dot(shoulder_to_wrist)
        / shoulder_to_wrist.length_squared
    )
    offset = elbow - projection
    if offset.length < 1e-5:
        offset = Vector((0.0, -1.0, 0.0))
    return elbow + offset.normalized() * 0.55


def rear_foot_rotation(standing_foot_rotation: Matrix) -> Quaternion:
    """Turn the approved right shoe back onto its instep without axial roll.

    The world-X pitch reverses the standing toe direction from forward/down to
    rearward/almost horizontal.  Because it preserves the standing shoe's roll,
    the outsole faces world +Z and the laces face the bench instead of placing a
    lateral shoe edge on the pad.
    """
    standing = standing_foot_rotation.to_quaternion()
    pitch = Quaternion(
        (1.0, 0.0, 0.0),
        math.radians(REAR_FOOT_PITCH_DEGREES),
    )
    return pitch @ standing


def animate(rig, standing_foot_rotations, dumbbells):
    rig.location = (0.0, 0.0, TOP_ROOT_Z)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (0.0, 0.0, 0.0)
    bpy.context.view_layer.update()

    feet = {
        "l": empty("L BSS front ankle target", FRONT_ANKLE),
        "r": empty("R BSS rear ankle target", REAR_ANKLE),
    }
    knees = {
        "l": empty("L BSS front knee pole", FRONT_KNEE_POLE),
        "r": empty("R BSS rear knee pole", REAR_KNEE_POLE),
    }
    foot_rotations = {}
    for side in ("l", "r"):
        add_ik(rig, f"calf_{side}", feet[side], knees[side])
        target = empty(f"{side.upper()} BSS foot rotation")
        target.rotation_mode = "QUATERNION"
        if side == "l":
            target.matrix_world = standing_foot_rotations[side]
            target.rotation_mode = "QUATERNION"
        else:
            target.rotation_quaternion = rear_foot_rotation(
                standing_foot_rotations[side]
            )
        copy_world_rotation(rig, f"foot_{side}", target)
        foot_rotations[side] = target

    hands = {}
    elbows = {}
    upper_lengths = {}
    lower_lengths = {}
    for side in ("l", "r"):
        hands[side] = empty(f"{side.upper()} BSS hand target")
        elbows[side] = empty(f"{side.upper()} BSS elbow pole")
        add_ik(rig, f"lowerarm_{side}", hands[side], elbows[side])
        upper_lengths[side] = rig.data.bones[f"upperarm_{side}"].length
        lower_lengths[side] = rig.data.bones[f"lowerarm_{side}"].length
    hand_rotations = configure_grips(rig)

    for frame in range(1, FRAME_END + 1):
        depth = split_squat_depth(frame)
        rig.location = (0.0, 0.0, root_height(depth))
        rig.keyframe_insert("location", frame=frame)
        bpy.context.view_layer.update()

        for side, sign in (("l", 1.0), ("r", -1.0)):
            shoulder = bone_point(rig, f"upperarm_{side}")
            upper_direction = Vector((0.43 * sign, -0.10, -0.897)).normalized()
            forearm_direction = Vector((0.25 * sign, -0.103, -0.963)).normalized()
            elbow = shoulder + upper_direction * upper_lengths[side]
            wrist = elbow + forearm_direction * lower_lengths[side]
            hands[side].location = wrist
            hands[side].keyframe_insert("location", frame=frame)
            elbows[side].location = pole_for_elbow(shoulder, wrist, elbow)
            elbows[side].keyframe_insert("location", frame=frame)

            hand_rotations[side].rotation_quaternion = hand_grip_quaternion(
                rig, side, forearm_direction
            )
            hand_rotations[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )
            handle_axis, inward = neutral_axes(side, forearm_direction)
            center = (
                wrist
                + forearm_direction * 0.042
                + inward * 0.024
                + handle_axis * 0.009 * sign
            )
            dumbbells[side].rotation_mode = "QUATERNION"
            dumbbells[side].rotation_quaternion = dumbbell_quaternion(
                side, forearm_direction
            )
            dumbbells[side].location = center
            dumbbells[side].keyframe_insert("location", frame=frame)
            dumbbells[side].keyframe_insert("rotation_quaternion", frame=frame)

    bpy.context.scene.frame_set(TOP_FRAME)
    return {
        "feet": feet,
        "knees": knees,
        "foot_rotations": foot_rotations,
        "hands": hands,
        "elbows": elbows,
        "hand_rotations": hand_rotations,
        "dumbbells": dumbbells,
    }


def mirror_right_thumb_pose(rig) -> None:
    scene = bpy.context.scene
    original = scene.frame_current
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()
    mirror_x = Matrix.Diagonal((-1.0, 1.0, 1.0, 1.0))
    matrices = {
        joint: mirror_x @ rig.pose.bones[f"thumb_{joint}_l"].matrix.copy() @ mirror_x
        for joint in ("01", "02", "03")
    }
    for joint, matrix in matrices.items():
        rig.pose.bones[f"thumb_{joint}_r"].matrix = matrix
        bpy.context.view_layer.update()
    scene.frame_set(original)


def mirror_right_fingertip_contacts(rig) -> None:
    """Mirror the complete left finger chains onto the right neutral grip.

    A tip-only IK target can choose a different bend plane for the short pinky
    even when the target itself is mirrored.  Mirroring all three joints gives
    an exact anatomical counterpart that stays exact while both hands follow
    their mirrored arm targets through the vertical repetition.
    """
    scene = bpy.context.scene
    original = scene.frame_current
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()
    mirror_x = Matrix.Diagonal((-1.0, 1.0, 1.0, 1.0))
    matrices = {
        (finger, joint): (
            mirror_x
            @ rig.pose.bones[f"{finger}_{joint}_l"].matrix.copy()
            @ mirror_x
        )
        for finger in GRIP_FINGERS
        for joint in ("01", "02", "03")
    }
    for finger in GRIP_FINGERS:
        for joint in ("01", "02", "03"):
            rig.pose.bones[f"{finger}_{joint}_r"].matrix = matrices[(finger, joint)]
            bpy.context.view_layer.update()
    scene.frame_set(original)
    bpy.context.view_layer.update()


def spine_shape(rig) -> tuple[float, ...]:
    axes = []
    for name in ("pelvis", "spine_01", "spine_02", "spine_03", "neck_01", "head"):
        axes.append((bone_point(rig, name, "tail") - bone_point(rig, name)).normalized())
    return tuple(
        math.degrees(lower.angle(upper))
        for lower, upper in zip(axes, axes[1:])
    )


def frame_snapshot(rig, controls) -> tuple[tuple[float, ...], ...]:
    values = []
    for name in (
        "pelvis", "thigh_l", "calf_l", "foot_l", "thigh_r", "calf_r",
        "foot_r", "upperarm_l", "lowerarm_l", "hand_l", "upperarm_r",
        "lowerarm_r", "hand_r",
    ):
        for endpoint in ("head", "tail"):
            point = bone_point(rig, name, endpoint)
            values.append(tuple(point))
    for side in ("l", "r"):
        values.append(tuple(controls["dumbbells"][side].matrix_world.translation))
        for digit in GRIP_DIGITS:
            values.append(tuple(bone_point(rig, f"{digit}_03_{side}", "tail")))
    return tuple(values)


def validate_motion(rig, controls) -> None:
    scene = bpy.context.scene
    original = scene.frame_current
    errors = []
    knees = {"l": [], "r": []}
    knee_angles = {"l": [], "r": []}
    dumbbell_positions = {"l": [], "r": []}
    max_front_foot_error = 0.0
    max_rear_foot_error = 0.0
    max_target_drift = 0.0
    max_root_xy_drift = 0.0
    max_knee_travel = 0.0
    max_knee_accel = 0.0
    max_thigh_from_horizontal = 0.0
    min_rear_knee_clearance = float("inf")
    max_rear_knee_clearance = 0.0
    max_wrist_error = 0.0
    min_elbow_angle = float("inf")
    max_arm_from_vertical = 0.0
    max_dumbbell_horizontal_drift = 0.0
    max_dumbbell_travel = 0.0
    max_grip_basis_error = 0.0
    min_neutral_palm_dot = 1.0
    min_handle_sagittal_dot = 1.0
    max_grip_axial = 0.0
    min_grip_radial = float("inf")
    max_grip_radial = 0.0
    max_grip_mirror_error = 0.0
    max_thumb_opposition = -1.0
    min_front_sole_depth = float("inf")
    max_front_sole_depth = 0.0
    min_rear_contact_gap = float("inf")
    max_rear_penetration = 0.0
    min_rear_toe_back_dot = 1.0
    min_rear_outsole_up_dot = 1.0
    seam = {}

    depths = [split_squat_depth(frame) for frame in range(1, FRAME_END + 1)]
    if (
        any(depth != 0.0 for depth in depths[0:15])
        or not all(0.0 < depth <= 1.0 for depth in depths[15:105])
        or any(depth != 1.0 for depth in depths[105:135])
        or not all(0.0 <= depth < 1.0 for depth in depths[135:225])
        or any(depth != 0.0 for depth in depths[225:241])
        or any(a > b for a, b in zip(depths[15:104], depths[16:105]))
        or any(a < b for a, b in zip(depths[135:224], depths[136:225]))
    ):
        errors.append("timeline left 15/90/30/90/16 frame contract")

    try:
        scene.frame_set(TOP_FRAME)
        bpy.context.view_layer.update()
        target_bases = {
            side: target.matrix_world.translation.copy()
            for side, target in controls["feet"].items()
        }
        rotation_bases = {
            side: target.matrix_world.to_quaternion()
            for side, target in controls["foot_rotations"].items()
        }
        spine_base = spine_shape(rig)
        dumbbell_bases = {
            side: controls["dumbbells"][side].matrix_world.translation.copy()
            for side in ("l", "r")
        }

        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            depth = split_squat_depth(frame)
            expected_z = root_height(depth)
            root = rig.matrix_world.translation
            max_root_xy_drift = max(max_root_xy_drift, abs(root.x), abs(root.y))
            if abs(root.z - expected_z) > 0.0005 or abs(root.x) > 0.0005 or abs(root.y) > 0.0005:
                errors.append(f"frame {frame}: root left vertical path {tuple(root)}")

            current_shape = spine_shape(rig)
            if max(abs(a - b) for a, b in zip(current_shape, spine_base)) > 1.0:
                errors.append(f"frame {frame}: neutral spine shape changed")

            for side, target in controls["feet"].items():
                target_drift = (target.matrix_world.translation - target_bases[side]).length
                max_target_drift = max(max_target_drift, target_drift)
                if target_drift > 0.0005:
                    errors.append(f"frame {frame}: {side} ankle target drift {target_drift:.5f}")
                rotation_drift = quaternion_delta_degrees(
                    rotation_bases[side],
                    controls["foot_rotations"][side].matrix_world.to_quaternion(),
                )
                if rotation_drift > 0.20:
                    errors.append(f"frame {frame}: {side} foot rotation drift {rotation_drift:.3f}")

            rear_rotation = controls["foot_rotations"]["r"].matrix_world.to_quaternion()
            rear_toe = (rear_rotation @ Vector((0.0, 1.0, 0.0))).normalized()
            rear_outsole = (rear_rotation @ Vector((0.0, 0.0, 1.0))).normalized()
            rear_toe_back_dot = rear_toe.dot(Vector((0.0, 1.0, 0.0)))
            rear_outsole_up_dot = rear_outsole.dot(Vector((0.0, 0.0, 1.0)))
            min_rear_toe_back_dot = min(min_rear_toe_back_dot, rear_toe_back_dot)
            min_rear_outsole_up_dot = min(
                min_rear_outsole_up_dot,
                rear_outsole_up_dot,
            )
            if rear_toe_back_dot < 0.96 or rear_outsole_up_dot < 0.96:
                errors.append(
                    f"frame {frame}: rear shoe orientation "
                    f"toe_back={rear_toe_back_dot:.3f} "
                    f"outsole_up={rear_outsole_up_dot:.3f}"
                )

            front_foot_error = (bone_point(rig, "foot_l") - FRONT_ANKLE).length
            rear_foot_error = (bone_point(rig, "foot_r") - REAR_ANKLE).length
            max_front_foot_error = max(max_front_foot_error, front_foot_error)
            max_rear_foot_error = max(max_rear_foot_error, rear_foot_error)
            if front_foot_error > 0.004:
                errors.append(f"frame {frame}: front ankle error {front_foot_error:.4f}")
            if rear_foot_error > 0.004:
                errors.append(f"frame {frame}: rear ankle error {rear_foot_error:.4f}")

            shoe_vertices = evaluated_vertices("Human.shoes05")
            left_shoe = [point for point in shoe_vertices if point.x > 0.0]
            front_sole_depth = -min(point.z for point in left_shoe)
            min_front_sole_depth = min(min_front_sole_depth, front_sole_depth)
            max_front_sole_depth = max(max_front_sole_depth, front_sole_depth)
            if not -0.002 <= front_sole_depth <= 0.026:
                errors.append(f"frame {frame}: front sole depth {front_sole_depth:.4f}")
            rear_patch = [
                point for point in shoe_vertices
                if point.x < 0.0
                and BENCH_FRONT <= point.y <= BENCH_BACK
                and abs(point.x) <= BENCH_PAD_HALF.x
            ]
            if not rear_patch:
                errors.append(f"frame {frame}: rear shoe left bench footprint")
            else:
                gap = min(abs(point.z - BENCH_TOP) for point in rear_patch)
                penetration = max(BENCH_TOP - point.z for point in rear_patch)
                min_rear_contact_gap = min(min_rear_contact_gap, gap)
                max_rear_penetration = max(max_rear_penetration, penetration)
                if gap > 0.018:
                    errors.append(f"frame {frame}: rear instep gap {gap:.4f}")
                if penetration > 0.008:
                    errors.append(f"frame {frame}: rear shoe penetrates pad {penetration:.4f}")

            for side in ("l", "r"):
                hip = bone_point(rig, f"thigh_{side}")
                knee = bone_point(rig, f"calf_{side}")
                ankle = bone_point(rig, f"calf_{side}", "tail")
                angle = joint_angle(hip, knee, ankle)
                knees[side].append(knee.copy())
                knee_angles[side].append(angle)
                if len(knees[side]) >= 2:
                    travel = (knees[side][-1] - knees[side][-2]).length
                    max_knee_travel = max(max_knee_travel, travel)
                    if travel > 0.014:
                        errors.append(f"frame {frame}: {side} knee travel {travel:.4f}")
                if len(knees[side]) >= 3:
                    accel = (knees[side][-1] - 2 * knees[side][-2] + knees[side][-3]).length
                    max_knee_accel = max(max_knee_accel, accel)
                    if accel > 0.005:
                        errors.append(f"frame {frame}: {side} knee accel {accel:.4f}")
                rail_error = max(
                    min(hip.x, ankle.x) - knee.x,
                    knee.x - max(hip.x, ankle.x),
                    0.0,
                )
                if rail_error > 0.035:
                    errors.append(f"frame {frame}: {side} knee left toe rail {rail_error:.4f}")

            if depth > 0.99:
                front_hip = bone_point(rig, "thigh_l")
                front_knee = bone_point(rig, "calf_l")
                thigh = front_knee - front_hip
                from_horizontal = math.degrees(math.asin(min(1.0, abs(thigh.z) / thigh.length)))
                max_thigh_from_horizontal = max(max_thigh_from_horizontal, from_horizontal)
                rear_knee = bone_point(rig, "calf_r")
                min_rear_knee_clearance = min(min_rear_knee_clearance, rear_knee.z)
                max_rear_knee_clearance = max(max_rear_knee_clearance, rear_knee.z)
                if from_horizontal > 10.0:
                    errors.append(f"frame {frame}: front thigh {from_horizontal:.2f}deg from horizontal")
                if not 0.035 <= rear_knee.z <= 0.145:
                    errors.append(f"frame {frame}: rear knee clearance {rear_knee.z:.4f}")
                if not 52.0 <= knee_angles["l"][-1] <= 88.0:
                    errors.append(f"frame {frame}: front knee angle {knee_angles['l'][-1]:.2f}")

            frame_tips = {}
            for side, sign in (("l", 1.0), ("r", -1.0)):
                shoulder = bone_point(rig, f"upperarm_{side}")
                elbow = bone_point(rig, f"lowerarm_{side}")
                wrist = bone_point(rig, f"hand_{side}")
                wrist_error = (wrist - controls["hands"][side].matrix_world.translation).length
                max_wrist_error = max(max_wrist_error, wrist_error)
                if wrist_error > 0.004:
                    errors.append(f"frame {frame}: {side} wrist error {wrist_error:.4f}")
                elbow_angle = joint_angle(shoulder, elbow, wrist)
                min_elbow_angle = min(min_elbow_angle, elbow_angle)
                if elbow_angle < 160.0:
                    errors.append(f"frame {frame}: {side} elbow {elbow_angle:.2f}")
                arm = wrist - shoulder
                from_vertical = math.degrees(arm.angle(Vector((0.0, 0.0, -1.0))))
                max_arm_from_vertical = max(max_arm_from_vertical, from_vertical)
                if from_vertical > 24.0:
                    errors.append(f"frame {frame}: {side} arm {from_vertical:.2f}deg from vertical")

                forearm = (wrist - elbow).normalized()
                expected_axis, expected_palm = neutral_axes(side, forearm)
                center = controls["dumbbells"][side].matrix_world.translation
                root_quat = controls["dumbbells"][side].matrix_world.to_quaternion()
                actual_axis = (root_quat @ Vector((0.0, 0.0, 1.0))).normalized()
                handle_dot = abs(actual_axis.dot(Vector((0.0, 1.0, 0.0))))
                min_handle_sagittal_dot = min(min_handle_sagittal_dot, handle_dot)
                if handle_dot < 0.94 or abs(actual_axis.dot(expected_axis)) < 0.99:
                    errors.append(f"frame {frame}: {side} handle left sagittal axis")
                _, _, palm_local = hand_rest_basis(rig, side)
                hand_world = (rig.matrix_world @ rig.pose.bones[f"hand_{side}"].matrix).to_quaternion()
                actual_palm = (hand_world @ palm_local).normalized()
                palm_dot = actual_palm.dot(expected_palm)
                min_neutral_palm_dot = min(min_neutral_palm_dot, palm_dot)
                grip_error = math.degrees(actual_palm.angle(expected_palm))
                max_grip_basis_error = max(max_grip_basis_error, grip_error)
                if palm_dot < 0.94:
                    errors.append(f"frame {frame}: {side} palm not inward {palm_dot:.3f}")

                horizontal_drift = Vector((center.x - dumbbell_bases[side].x, center.y - dumbbell_bases[side].y, 0.0)).length
                max_dumbbell_horizontal_drift = max(max_dumbbell_horizontal_drift, horizontal_drift)
                if horizontal_drift > 0.004:
                    errors.append(f"frame {frame}: {side} dumbbell swung {horizontal_drift:.4f}")
                history = dumbbell_positions[side]
                history.append(center.copy())
                if len(history) >= 2:
                    travel = (history[-1] - history[-2]).length
                    max_dumbbell_travel = max(max_dumbbell_travel, travel)
                    if travel > 0.014:
                        errors.append(f"frame {frame}: {side} dumbbell travel {travel:.4f}")

                frame_tips[side] = {}
                radial_dirs = {}
                for digit in GRIP_DIGITS:
                    tip = bone_point(rig, f"{digit}_03_{side}", "tail")
                    frame_tips[side][digit] = tip
                    offset = tip - center
                    axial = offset.dot(expected_axis)
                    radial_vector = offset - expected_axis * axial
                    radial = radial_vector.length
                    max_grip_axial = max(max_grip_axial, abs(axial))
                    min_grip_radial = min(min_grip_radial, radial)
                    max_grip_radial = max(max_grip_radial, radial)
                    radial_dirs[digit] = radial_vector.normalized()
                    # The approved hammer-curl fist seats the fingertip pads
                    # against the far half of the 32 mm shaft.  The short
                    # terminal bones therefore end 6-11 mm from the shaft axis
                    # while the visible pads fully cover its 16 mm radius.
                    if abs(axial) > 0.052 or not 0.005 <= radial <= 0.050:
                        errors.append(f"frame {frame}: {side} {digit} lost handle contact axial={axial:.4f} radial={radial:.4f}")
                average_fingers = sum((radial_dirs[name] for name in GRIP_FINGERS), Vector()).normalized()
                opposition = average_fingers.dot(radial_dirs["thumb"])
                max_thumb_opposition = max(max_thumb_opposition, opposition)
                if opposition > -0.30:
                    errors.append(f"frame {frame}: {side} thumb opposition {opposition:.3f}")

            for digit in GRIP_DIGITS:
                left = frame_tips["l"][digit]
                mirrored = Vector((-left.x, left.y, left.z))
                mirror_error = (mirrored - frame_tips["r"][digit]).length
                max_grip_mirror_error = max(max_grip_mirror_error, mirror_error)
                if mirror_error > 0.004:
                    errors.append(f"frame {frame}: {digit} grip mirror {mirror_error:.4f}")

            left_center = controls["dumbbells"]["l"].matrix_world.translation
            right_center = controls["dumbbells"]["r"].matrix_world.translation
            if max(abs(left_center.x + right_center.x), abs(left_center.y - right_center.y), abs(left_center.z - right_center.z)) > 0.004:
                errors.append(f"frame {frame}: dumbbells lost symmetry")
            pair_gap = abs(left_center.x - right_center.x) - 2 * DUMBBELL_PLATE_RADIUS
            if pair_gap < 0.020:
                errors.append(f"frame {frame}: dumbbell pair gap {pair_gap:.4f}")

            if frame in (TOP_FRAME, FRAME_END):
                seam[frame] = frame_snapshot(rig, controls)

        seam_error = max(
            abs(a - b)
            for item_a, item_b in zip(seam[TOP_FRAME], seam[FRAME_END])
            for a, b in zip(item_a, item_b)
        )
        if seam_error > 0.00005:
            errors.append(f"loop seam error {seam_error:.6f}")
    finally:
        scene.frame_set(original)

    if errors:
        unique = list(dict.fromkeys(errors))
        raise RuntimeError(
            "BSS_MOTION_CHECK FAIL (first 24): " + "; ".join(unique[:24])
        )
    print(
        "BSS_MOTION_CHECK PASS",
        f"frames=1-{FRAME_END}",
        f"front_ankle_error={max_front_foot_error:.4f}m",
        f"rear_ankle_error={max_rear_foot_error:.4f}m",
        f"target_drift={max_target_drift:.5f}m",
        f"root_xy_drift={max_root_xy_drift:.5f}m",
        f"front_knee={min(knee_angles['l']):.2f}-{max(knee_angles['l']):.2f}deg",
        f"rear_knee={min(knee_angles['r']):.2f}-{max(knee_angles['r']):.2f}deg",
        f"front_thigh_horizontal_error={max_thigh_from_horizontal:.2f}deg",
        f"rear_knee_clearance={min_rear_knee_clearance:.4f}-{max_rear_knee_clearance:.4f}m",
        f"knee_travel={max_knee_travel:.4f}m/frame",
        f"knee_accel={max_knee_accel:.4f}m/frame^2",
        f"front_sole_depth={min_front_sole_depth:.4f}-{max_front_sole_depth:.4f}m",
        f"rear_contact_gap={min_rear_contact_gap:.4f}m",
        f"rear_penetration={max_rear_penetration:.4f}m",
        f"rear_toe_back_dot={min_rear_toe_back_dot:.3f}",
        f"rear_outsole_up_dot={min_rear_outsole_up_dot:.3f}",
        f"wrist_error={max_wrist_error:.4f}m",
        f"elbow_min={min_elbow_angle:.2f}deg",
        f"arm_vertical_error={max_arm_from_vertical:.2f}deg",
        f"dumbbell_horizontal_drift={max_dumbbell_horizontal_drift:.4f}m",
        f"dumbbell_travel={max_dumbbell_travel:.4f}m/frame",
        f"grip_basis_error={max_grip_basis_error:.2f}deg",
        f"palm_inward_dot={min_neutral_palm_dot:.3f}",
        f"handle_sagittal_dot={min_handle_sagittal_dot:.3f}",
        f"grip_axial={max_grip_axial:.4f}m",
        f"grip_radial={min_grip_radial:.4f}-{max_grip_radial:.4f}m",
        f"thumb_opposition={max_thumb_opposition:.3f}",
        f"grip_mirror={max_grip_mirror_error:.4f}m",
        "loop=frame-1-equals-frame-241",
    )


def validate_collisions() -> None:
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        DUMBBELL_PLATE_COLLIDERS,
        COLLISION_FRAMES,
    )
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        BENCH_FRAME_COLLIDERS,
        COLLISION_FRAMES,
    )
    # The right shoe intentionally meets the pad and is checked analytically
    # on all 241 frames above.  Every other athlete mesh must clear the pad;
    # the complete athlete (including both shoes) must clear the steel frame.
    assert_no_mesh_intersections(
        ATHLETE_WITHOUT_SHOES,
        ("BSS bench pad",),
        COLLISION_FRAMES,
    )
    assert_no_mesh_intersections(
        DUMBBELL_COMPONENTS["L"],
        DUMBBELL_COMPONENTS["R"],
        COLLISION_FRAMES,
    )
    assert_no_mesh_intersections(
        DUMBBELL_COMPONENTS["L"] + DUMBBELL_COMPONENTS["R"],
        BENCH_COLLIDERS,
        COLLISION_FRAMES,
    )


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.25, 0.88), (0.0, 0.06, 0.76), 62),
        ("side", (4.20, -0.42, 0.88), (0.0, 0.06, 0.75), 64),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"BSS {name.title()} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera
    for name, location, energy, size, color in (
        ("Key", (-2.4, -2.5, 3.2), 980, 2.8, (1.0, 0.92, 0.84)),
        ("Fill", (2.4, -1.3, 2.2), 430, 2.2, (0.78, 0.88, 1.0)),
        ("Rim", (0.3, 2.2, 2.7), 560, 2.0, (0.72, 0.62, 1.0)),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        lamp = bpy.context.object
        lamp.name = f"BSS {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.05, 0.80)))
    return cameras


def default_preview_directory(output_dir: str) -> str:
    return os.path.abspath(
        os.path.join(
            os.path.dirname(output_dir),
            "..", "..", "..", "..", "design", "motion", "previews",
        )
    )


def render_pose_previews(cameras, preview_dir: str) -> None:
    scene = bpy.context.scene
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


def render_grip_previews(rig, preview_dir: str) -> None:
    scene = bpy.context.scene
    os.makedirs(preview_dir, exist_ok=True)
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()
    hidden = [obj for obj in bpy.data.objects if obj.name.startswith("R BSS")]
    old = {obj.name: obj.hide_render for obj in hidden}
    for obj in hidden:
        obj.hide_render = True
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "BSS grip inspection camera"
    camera.data.lens = 82
    scene.camera = camera
    hand = bone_point(rig, "hand_l")
    for name, offset in (
        ("front", Vector((0.70, -0.55, 0.16))),
        ("angle", Vector((0.74, -0.25, 0.38))),
        ("rear", Vector((0.67, 0.55, 0.13))),
    ):
        camera.location = hand + offset
        look_at(camera, hand + Vector((0.0, -0.015, 0.015)))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_grip_{name}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)
    for obj in hidden:
        obj.hide_render = old[obj.name]


def render_rear_foot_contact_preview(preview_dir: str) -> None:
    scene = bpy.context.scene
    os.makedirs(preview_dir, exist_ok=True)
    scene.frame_set(BOTTOM_FRAME)
    bpy.context.view_layer.update()
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "BSS rear-foot contact inspection camera"
    camera.data.lens = 84
    # A slightly elevated three-quarter view shows both the upward outsole and
    # the laces/instep meeting the pad, while retaining a readable contact gap.
    camera.location = (0.55, 0.08, 0.72)
    look_at(camera, Vector((-0.154, 0.49, BENCH_TOP + 0.025)))
    scene.camera = camera
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = os.path.join(
        preview_dir, f"human_{EXERCISE}_rear_foot_contact.png"
    )
    bpy.ops.render.render(write_still=True)
    print("CONTACT_PREVIEW", scene.render.filepath)


def render_movies(cameras, output_dir: str) -> None:
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
                raise RuntimeError("Blender FFmpeg output and system ffmpeg unavailable")
            with tempfile.TemporaryDirectory(prefix=f"healthtask-{EXERCISE}-{name}-") as frame_dir:
                scene.render.image_settings.file_format = "PNG"
                scene.render.image_settings.color_mode = "RGB"
                scene.render.filepath = os.path.join(frame_dir, "frame_")
                bpy.ops.render.render(animation=True)
                subprocess.run(
                    [
                        ffmpeg, "-y", "-loglevel", "warning", "-framerate", str(FPS),
                        "-i", os.path.join(frame_dir, "frame_%04d.png"),
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an",
                        movie_path,
                    ],
                    check=True,
                )
        print("MOVIE", movie_path)


def main() -> None:
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    preview_dir = os.path.abspath(
        args.preview_dir or default_preview_directory(output_dir)
    )
    configure_scene()
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = FRAME_END
    rig, standing_foot_rotations = reset_squat_scene()
    rig.location = (0.0, 0.0, TOP_ROOT_Z)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (0.0, 0.0, 0.0)
    configure_athlete_materials()
    build_bench()
    dumbbells = build_dumbbells()
    controls = animate(rig, standing_foot_rotations, dumbbells)
    mirror_right_thumb_pose(rig)
    mirror_right_fingertip_contacts(rig)
    validate_motion(rig, controls)
    validate_collisions()
    cameras = build_cameras_and_lights()
    scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_pose_previews(cameras, preview_dir)
        render_grip_previews(rig, preview_dir)
        render_rear_foot_contact_preview(preview_dir)
    elif args.mode == "grip":
        render_grip_previews(rig, preview_dir)
    elif args.mode == "contact":
        render_rear_foot_contact_preview(preview_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
