"""Build the offline standard push-up guide from the approved athlete.

Run after opening the packed approved squat source, for example::

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_push_up.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/push_up_human_sample.blend \
      --mode preview

The 8-second, 30 fps loop contains two controlled repetitions.  Frames 1--240
are encoded and frame 241 duplicates frame 1.  Both palms and both forefeet are
fixed while the approved high-plank body rotates as one rigid unit.  The full
numeric production contract lives in ``docs/motions/PUSH_UP.md``.
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
    reset_squat_scene,
)
from generate_human_tabata_burpee import (  # noqa: E402
    flatten_hands,
    hand_basis_rotation,
)
from generate_human_tabata_mountain_climber import (  # noqa: E402
    evaluated_vertices,
    joint_angle,
    world_bone_head,
)
from generate_squat_sample import configure_scene, look_at, smoothstep  # noqa: E402


EXERCISE = "push_up"
FPS = 30
FRAME_END = 241
ENCODED_FRAMES = 240
CYCLE_FRAMES = 120
TOP_FRAME = 1
MID_FRAME = 31
BOTTOM_FRAME = 61
PREVIEW_FRAMES = (
    (TOP_FRAME, "top"),
    (MID_FRAME, "mid"),
    (BOTTOM_FRAME, "bottom"),
)
CONTACT_FRAMES = (1, 31, 61, 91, 121, 151, 181, 211, 241)
REPEAT_PAIRS = ((1, 121), (31, 151), (61, 181), (91, 211))
PLATFORM_TOP_Z = 0.0

# The root pose is solved numerically from the athlete's measured limb lengths
# on every frame.  This keeps the torso rigid while the arm angle changes
# linearly through the visual depth, instead of letting the chest reach the
# bottom early and allowing the pelvis to catch up afterward.
TOP_ELBOW_DEGREES = 172.0
BOTTOM_ELBOW_DEGREES = 80.2
TARGET_KNEE_DEGREES = 174.0
HAND_TARGET = Vector((0.170, -0.430, 0.0240))
HAND_PITCH_DEGREES = 3.5
HAND_ROLL_DEGREES = 6.0
FOOT_TARGET_X = 0.160
FOREFOOT_CONTACT_Y = 0.5795
TOP_FOOT_PITCH_DEGREES = 55.0
BOTTOM_FOOT_PITCH_DEGREES = 80.0
# For each pitch: evaluated low-sole centroid relative to ankle y, followed by
# the ankle z before applying the clearance correction.  The calibrated
# 55--80 degree range keeps the visible forefoot within 0.14--0.31 mm.
FOOT_SUPPORT_PROFILE = (
    (15.0, -0.1176, 0.1004),
    (20.0, -0.1153, 0.1109),
    (30.0, -0.1003, 0.1319),
    (45.0, -0.0667, 0.1547),
    (55.0, -0.0389, 0.1621033),
    (60.0, -0.0250, 0.1648870),
    (65.0, -0.0113667, 0.1666763),
    (70.0, 0.0022667, 0.1671187),
    (75.0, 0.0159, 0.1662180),
    (80.0, 0.0293, 0.1639770),
)
CONTACT_CLEARANCE_CORRECTION = 0.010
PONYTAIL_COMPENSATION_DEGREES = 40.0
NECK_EXTENSION_DEGREES = -6.0
HEAD_EXTENSION_DEGREES = -4.0
FLOOR_PENETRATION_LIMIT = -0.001


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "contact", "render", "validate"),
        default="preview",
    )
    return parser.parse_args(argv)


def motion_amount(frame: int) -> float:
    """Return the held/eased top-to-bottom amount for either repetition."""
    local = (frame - 1) % CYCLE_FRAMES + 1
    if local <= 15:
        return 0.0
    if local <= 45:
        return smoothstep((local - 15) / 30.0)
    if local <= 75:
        return 1.0
    if local <= 105:
        return 1.0 - smoothstep((local - 75) / 30.0)
    return 0.0


def pose_depth(frame: int) -> float:
    """Keep representative descent/ascent frames inside the mid-angle band."""
    amount = motion_amount(frame)
    return amount * (0.84 + 0.16 * amount)


def interpolate_profile(pitch: float) -> tuple[float, float]:
    """Return sole-centroid y offset and ankle z for a given foot pitch."""
    if pitch <= FOOT_SUPPORT_PROFILE[0][0]:
        return FOOT_SUPPORT_PROFILE[0][1:]
    if pitch >= FOOT_SUPPORT_PROFILE[-1][0]:
        return FOOT_SUPPORT_PROFILE[-1][1:]
    for first, second in zip(FOOT_SUPPORT_PROFILE, FOOT_SUPPORT_PROFILE[1:]):
        if first[0] <= pitch <= second[0]:
            amount = (pitch - first[0]) / (second[0] - first[0])
            return (
                first[1] + (second[1] - first[1]) * amount,
                first[2] + (second[2] - first[2]) * amount,
            )
    raise RuntimeError(f"No push-up foot support profile for pitch {pitch}")


def chain_distance(first: float, second: float, angle: float) -> float:
    """Return endpoint distance for a two-bone chain at ``angle`` degrees."""
    return math.sqrt(
        first * first
        + second * second
        - 2.0 * first * second * math.cos(math.radians(angle))
    )


def solve_linear_system(matrix, values):
    """Solve the small 3x3 Newton system without external dependencies."""
    augmented = [list(row) + [value] for row, value in zip(matrix, values)]
    count = len(values)
    for column in range(count):
        pivot = max(
            range(column, count),
            key=lambda row: abs(augmented[row][column]),
        )
        if abs(augmented[pivot][column]) < 1e-12:
            raise RuntimeError("Singular push-up body solve")
        augmented[column], augmented[pivot] = (
            augmented[pivot],
            augmented[column],
        )
        scale = augmented[column][column]
        augmented[column] = [value / scale for value in augmented[column]]
        for row in range(count):
            if row == column:
                continue
            factor = augmented[row][column]
            augmented[row] = [
                value - factor * source
                for value, source in zip(
                    augmented[row], augmented[column]
                )
            ]
    return [augmented[row][-1] for row in range(count)]


def solve_body_pose(
    rig,
    hand_target: Vector,
    ankle_target: Vector,
    arm_distance: float,
    leg_distance: float,
    initial,
):
    """Solve root pitch/location for arm depth, leg length and a rigid torso."""

    def residual(values):
        pitch, location_y, location_z = values
        rig.rotation_quaternion = Quaternion((1.0, 0.0, 0.0), pitch)
        rig.location = (0.0, location_y, location_z)
        bpy.context.view_layer.update()
        shoulder = world_bone_head(rig, "upperarm_l")
        hip = world_bone_head(rig, "thigh_l")
        pelvis = world_bone_head(rig, "pelvis")
        line_y = ankle_target.y - shoulder.y
        line_z = ankle_target.z - shoulder.z
        line_length = math.hypot(line_y, line_z)
        if line_length < 1e-8:
            raise RuntimeError("Collapsed push-up torso line")
        signed_body_line = (
            line_y * (pelvis.z - shoulder.z)
            - line_z * (pelvis.y - shoulder.y)
        ) / line_length
        return (
            (shoulder - hand_target).length - arm_distance,
            (hip - ankle_target).length - leg_distance,
            signed_body_line,
        )

    values = list(initial)
    for _iteration in range(12):
        errors = residual(values)
        if max(abs(value) for value in errors) < 1e-6:
            return tuple(values)
        delta = 1e-5
        jacobian = [[], [], []]
        for column in range(3):
            shifted = list(values)
            shifted[column] += delta
            shifted_errors = residual(shifted)
            for row in range(3):
                jacobian[row].append(
                    (shifted_errors[row] - errors[row]) / delta
                )
        step = solve_linear_system(jacobian, [-value for value in errors])
        for index, amount in enumerate(step):
            values[index] += max(-0.20, min(0.20, amount))
    errors = residual(values)
    raise RuntimeError(
        "Push-up body solve did not converge: "
        + ", ".join(f"{value:.7f}" for value in errors)
    )


def elbow_pole_for(
    shoulder: Vector,
    wrist: Vector,
    upper_length: float,
    lower_length: float,
    side_sign: float,
    desired_flare_degrees: float,
) -> Vector:
    """Construct a stable pole that sends the elbow back/out on its form rail."""
    axis_vector = wrist - shoulder
    distance = axis_vector.length
    if distance < 1e-8:
        raise RuntimeError("Cannot solve a collapsed push-up arm chain")
    axis = axis_vector / distance
    along = (
        upper_length * upper_length
        - lower_length * lower_length
        + distance * distance
    ) / (2.0 * distance)
    radius_squared = upper_length * upper_length - along * along
    radius = math.sqrt(max(0.0, radius_squared))
    desired_flare = (
        Vector((0.0, 1.0, 0.0))
        * math.cos(math.radians(desired_flare_degrees))
        + Vector((side_sign, 0.0, 0.0))
        * math.sin(math.radians(desired_flare_degrees))
    ).normalized()
    radial = desired_flare - axis * desired_flare.dot(axis)
    if radial.length < 1e-6:
        radial = Vector((side_sign, 0.0, 0.0))
    radial.normalize()
    desired_elbow = shoulder + axis * along + radial * radius
    return desired_elbow + radial * 0.70


def sagittal_knee_pole(hip: Vector, ankle: Vector, side_sign: float) -> Vector:
    axis = (ankle - hip).normalized()
    direction = Vector((0.0, -axis.z, axis.y))
    if direction.length < 1e-6:
        direction = Vector((0.0, -1.0, 0.0))
    direction.normalize()
    if direction.y > 0.0:
        direction.negate()
    pole = (hip + ankle) * 0.5 + direction * 1.10
    pole.x = 0.473 * side_sign
    return pole


def bone_point(rig, bone_name: str, endpoint: str = "head") -> Vector:
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def distance_to_line_yz(point: Vector, start: Vector, end: Vector) -> float:
    line_y = end.y - start.y
    line_z = end.z - start.z
    length = math.hypot(line_y, line_z)
    if length < 1e-8:
        raise RuntimeError("Cannot validate a zero-length push-up body line")
    point_y = point.y - start.y
    point_z = point.z - start.z
    return abs(line_y * point_z - line_z * point_y) / length


def set_static_keys(obj) -> None:
    obj.keyframe_insert("location", frame=1)
    obj.keyframe_insert("location", frame=FRAME_END)
    if obj.rotation_mode == "QUATERNION":
        obj.keyframe_insert("rotation_quaternion", frame=1)
        obj.keyframe_insert("rotation_quaternion", frame=FRAME_END)


def configure_ponytail_gravity() -> None:
    """Let the loose ponytail hang down instead of following the torso rigidly."""
    ponytail = bpy.data.objects.get("Human.ponytail01")
    if ponytail is None or ponytail.type != "MESH":
        raise RuntimeError("Approved athlete is missing the ponytail mesh")
    if ponytail.data.shape_keys is not None:
        raise RuntimeError("Push-up ponytail unexpectedly already has shape keys")

    basis = ponytail.shape_key_add(name="Basis", from_mix=False)
    gravity = ponytail.shape_key_add(name="Push-up gravity", from_mix=False)
    gravity.interpolation = "KEY_LINEAR"
    pivot = Vector((0.0, 0.055, 1.42))
    rotation = Matrix.Rotation(
        math.radians(-PONYTAIL_COMPENSATION_DEGREES), 4, "X"
    )
    tail_z_min = min(point.co.z for point in basis.data)
    tail_z_base = 1.43
    tail_height = tail_z_base - tail_z_min
    affected = 0
    for index, point in enumerate(basis.data):
        source = point.co.copy()
        if source.y <= 0.02 or source.z >= tail_z_base:
            continue
        weight = max(0.0, min(1.0, (tail_z_base - source.z) / tail_height))
        weight = weight * weight * (3.0 - 2.0 * weight)
        gravity.data[index].co = source.lerp(
            pivot + rotation @ (source - pivot), weight
        )
        affected += 1
    if affected == 0:
        raise RuntimeError("Push-up gravity correction selected no hair vertices")
    gravity.value = 1.0


def configure_weight_bearing_hands(rig) -> None:
    """Support the broad palm and uncurl the shaped athlete's resting digits.

    Negative local flex offsets cancel the imported anatomical rest curl;
    the evaluated fingers are gently extended, not bent backward at the joints.
    """
    flatten_hands(rig)
    finger_pose = {
        "index": (-5.0, (-10.0, -2.0, -1.0)),
        "middle": (0.0, (-11.0, -2.0, -1.0)),
        "ring": (5.0, (-11.0, -3.0, -1.0)),
        "pinky": (9.0, (-14.0, -3.0, -1.0)),
    }
    for side, sign in (("l", 1.0), ("r", -1.0)):
        for finger, (spread, flexes) in finger_pose.items():
            for joint, flex in zip(("01", "02", "03"), flexes):
                bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                bone.rotation_mode = "XYZ"
                bone.rotation_euler = (
                    math.radians(flex),
                    0.0,
                    math.radians(spread * sign if joint == "01" else 0.0),
                )
        for joint, flex in zip(("01", "02", "03"), (-30.0, -10.0, -5.0)):
            thumb = rig.pose.bones[f"thumb_{joint}_{side}"]
            thumb.rotation_mode = "XYZ"
            thumb.rotation_euler = (math.radians(flex), 0.0, 0.0)


def animate_push_up(rig, standing_foot_rotations):
    """Create fixed hand/foot contacts and the two-repetition body motion."""
    rig.rotation_mode = "QUATERNION"
    rig.scale = (1.0, 1.0, 1.0)

    hand_targets = {}
    elbow_poles = {}
    hand_rotations = {}
    foot_targets = {}
    knee_poles = {}
    foot_rotations = {}
    base_foot_rotations = {}
    upper_lengths = {
        side: rig.data.bones[f"upperarm_{side}"].length
        for side in ("l", "r")
    }
    lower_lengths = {
        side: rig.data.bones[f"lowerarm_{side}"].length
        for side in ("l", "r")
    }
    thigh_length = rig.data.bones["thigh_l"].length
    calf_length = rig.data.bones["calf_l"].length
    leg_support_distance = chain_distance(
        thigh_length, calf_length, TARGET_KNEE_DEGREES
    )

    for side, sign in (("l", 1.0), ("r", -1.0)):
        hand_targets[side] = empty(
            f"{side.upper()} push-up wrist target",
            (HAND_TARGET.x * sign, HAND_TARGET.y, HAND_TARGET.z),
        )
        elbow_poles[side] = empty(
            f"{side.upper()} push-up elbow pole",
            (0.220 * sign, -0.230, 0.270),
        )
        add_ik(
            rig,
            f"lowerarm_{side}",
            hand_targets[side],
            elbow_poles[side],
        )
        rig.pose.bones[f"upperarm_{side}"].ik_stretch = 0.0
        rig.pose.bones[f"lowerarm_{side}"].ik_stretch = 0.0

        hand_rotation = empty(f"{side.upper()} push-up hand rotation")
        hand_rotation.rotation_mode = "QUATERNION"
        hand_rotation.rotation_quaternion = (
            Quaternion((0.0, 1.0, 0.0), math.radians(HAND_ROLL_DEGREES * sign))
            @ Quaternion((1.0, 0.0, 0.0), math.radians(HAND_PITCH_DEGREES))
            @ hand_basis_rotation(rig, side, Vector((0.0, -1.0, 0.0)))
        )
        copy_world_rotation(rig, f"hand_{side}", hand_rotation)
        hand_rotations[side] = hand_rotation

        foot_targets[side] = empty(
            f"{side.upper()} push-up ankle target",
            (
                FOOT_TARGET_X * sign,
                0.697,
                FOOT_SUPPORT_PROFILE[0][2]
                + CONTACT_CLEARANCE_CORRECTION,
            ),
        )
        knee_poles[side] = empty(
            f"{side.upper()} push-up knee pole",
            (0.473 * sign, 0.066, -0.945),
        )
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        rig.pose.bones[f"thigh_{side}"].ik_stretch = 0.0
        rig.pose.bones[f"calf_{side}"].ik_stretch = 0.0

        foot_rotation = empty(f"{side.upper()} push-up foot rotation")
        foot_rotation.matrix_world = standing_foot_rotations[side]
        foot_rotation.rotation_mode = "QUATERNION"
        base = foot_rotation.rotation_quaternion.copy()
        base_foot_rotations[side] = base
        foot_rotation.rotation_quaternion = (
            Quaternion(
                (1.0, 0.0, 0.0), math.radians(TOP_FOOT_PITCH_DEGREES)
            )
            @ base
        )
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)
        foot_rotations[side] = foot_rotation

    configure_weight_bearing_hands(rig)

    for side in ("l", "r"):
        clavicle = rig.pose.bones[f"clavicle_{side}"]
        clavicle.rotation_mode = "XYZ"
        clavicle.rotation_euler = (math.radians(4.0), 0.0, 0.0)
        clavicle.keyframe_insert("rotation_euler", frame=1)
        clavicle.keyframe_insert("rotation_euler", frame=FRAME_END)

    for bone_name, degrees in (
        ("neck_01", NECK_EXTENSION_DEGREES),
        ("head", HEAD_EXTENSION_DEGREES),
    ):
        bone = rig.pose.bones[bone_name]
        bone.rotation_mode = "QUATERNION"
        bone.rotation_quaternion = (
            bone.rotation_quaternion
            @ Quaternion((1.0, 0.0, 0.0), math.radians(degrees))
        )
        for frame in (1, 121, FRAME_END):
            bone.keyframe_insert("rotation_quaternion", frame=frame)

    root_solution = (math.radians(70.0), 0.760, 0.100)
    for frame in range(1, FRAME_END + 1):
        depth = pose_depth(frame)
        desired_elbow = (
            TOP_ELBOW_DEGREES
            + (BOTTOM_ELBOW_DEGREES - TOP_ELBOW_DEGREES) * depth
        )
        foot_pitch = (
            TOP_FOOT_PITCH_DEGREES
            + (BOTTOM_FOOT_PITCH_DEGREES - TOP_FOOT_PITCH_DEGREES) * depth
        )
        sole_offset_y, ankle_z = interpolate_profile(foot_pitch)
        ankle_y = FOREFOOT_CONTACT_Y - sole_offset_y
        ankle_z += CONTACT_CLEARANCE_CORRECTION

        for side, sign in (("l", 1.0), ("r", -1.0)):
            foot_targets[side].location = (
                FOOT_TARGET_X * sign,
                ankle_y,
                ankle_z,
            )
            foot_rotations[side].rotation_quaternion = (
                Quaternion((1.0, 0.0, 0.0), math.radians(foot_pitch))
                @ base_foot_rotations[side]
            )

        bpy.context.view_layer.update()
        root_solution = solve_body_pose(
            rig,
            hand_targets["l"].matrix_world.translation,
            foot_targets["l"].matrix_world.translation,
            chain_distance(
                upper_lengths["l"], lower_lengths["l"], desired_elbow
            ),
            leg_support_distance,
            root_solution,
        )
        rig.rotation_quaternion = Quaternion(
            (1.0, 0.0, 0.0), root_solution[0]
        )
        rig.location = (0.0, root_solution[1], root_solution[2])
        bpy.context.view_layer.update()

        for side, sign in (("l", 1.0), ("r", -1.0)):
            hip = world_bone_head(rig, f"thigh_{side}")
            knee_poles[side].location = sagittal_knee_pole(
                hip, foot_targets[side].matrix_world.translation, sign
            )
            shoulder = world_bone_head(rig, f"upperarm_{side}")
            desired_flare = 39.0
            elbow_poles[side].location = elbow_pole_for(
                shoulder,
                hand_targets[side].matrix_world.translation,
                upper_lengths[side],
                lower_lengths[side],
                sign,
                desired_flare,
            )

            foot_targets[side].keyframe_insert("location", frame=frame)
            knee_poles[side].keyframe_insert("location", frame=frame)
            foot_rotations[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )
            elbow_poles[side].keyframe_insert("location", frame=frame)

        rig.keyframe_insert("rotation_quaternion", frame=frame)
        rig.keyframe_insert("location", frame=frame)

    for control in (
        *hand_targets.values(),
        *hand_rotations.values(),
    ):
        set_static_keys(control)

    for animated in (
        rig,
        *hand_targets.values(),
        *elbow_poles.values(),
        *hand_rotations.values(),
        *foot_targets.values(),
        *knee_poles.values(),
        *foot_rotations.values(),
    ):
        if animated.animation_data is None or animated.animation_data.action is None:
            continue
        for curve in animated.animation_data.action.fcurves:
            for keyframe in curve.keyframe_points:
                keyframe.interpolation = "LINEAR"

    bpy.context.scene.frame_set(1)
    bpy.context.view_layer.update()
    return {
        "hands": hand_targets,
        "elbow_poles": elbow_poles,
        "hand_rotations": hand_rotations,
        "feet": foot_targets,
        "knees": knee_poles,
        "foot_rotations": foot_rotations,
    }


def vertex_group_weight(obj, group_name: str, vertex_index: int) -> float:
    group = obj.vertex_groups.get(group_name)
    if group is None:
        return 0.0
    for membership in obj.data.vertices[vertex_index].groups:
        if membership.group == group.index:
            return membership.weight
    return 0.0


def evaluated_source_points(obj) -> list[Vector]:
    """Return armature-evaluated source vertices without subdivision."""
    subdivisions = [
        modifier for modifier in obj.modifiers if modifier.type == "SUBSURF"
    ]
    visibility = [modifier.show_viewport for modifier in subdivisions]
    try:
        for modifier in subdivisions:
            modifier.show_viewport = False
        bpy.context.view_layer.update()
        depsgraph = bpy.context.evaluated_depsgraph_get()
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh(
            preserve_all_data_layers=False, depsgraph=depsgraph
        )
        try:
            points = [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
        finally:
            evaluated.to_mesh_clear()
    finally:
        for modifier, visible in zip(subdivisions, visibility):
            modifier.show_viewport = visible
        bpy.context.view_layer.update()
    if len(points) != len(obj.data.vertices):
        raise RuntimeError("Push-up source vertex correspondence was lost")
    return points


def hand_source_geometry(obj, *, rest: bool = False):
    """Evaluate shape keys without losing source indices to helper masks.

    Raw mesh coordinates predate the athlete's MakeHuman shape keys, while
    the visible masked mesh has different indices.  Palm classification must
    use the shaped rest surface and measure those same vertices after posing.
    """
    disabled_types = {"MASK", "SUBSURF"}
    if rest:
        disabled_types.add("ARMATURE")
    modifiers = [m for m in obj.modifiers if m.type in disabled_types]
    visibility = [m.show_viewport for m in modifiers]
    try:
        for modifier in modifiers:
            modifier.show_viewport = False
        bpy.context.view_layer.update()
        depsgraph = bpy.context.evaluated_depsgraph_get()
        evaluated = obj.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
        try:
            points = [
                vertex.co.copy() if rest else evaluated.matrix_world @ vertex.co
                for vertex in mesh.vertices
            ]
            normals = [vertex.normal.copy() for vertex in mesh.vertices]
        finally:
            evaluated.to_mesh_clear()
    finally:
        for modifier, visible in zip(modifiers, visibility):
            modifier.show_viewport = visible
        bpy.context.view_layer.update()
    if len(points) != len(obj.data.vertices):
        raise RuntimeError("Push-up hand source vertex correspondence was lost")
    return points, normals


def hand_contact_regions(rig):
    """Classify real palm pads separately from fingers and hidden helpers."""
    body = bpy.data.objects["Human"]
    rest_points, normals = hand_source_geometry(body, rest=True)
    masks = [
        modifier for modifier in body.modifiers
        if modifier.type == "MASK" and modifier.show_viewport
    ]
    visible = []
    for index in range(len(rest_points)):
        keep = True
        for modifier in masks:
            inside = (
                vertex_group_weight(body, modifier.vertex_group, index)
                > modifier.threshold
            )
            if inside == modifier.invert_vertex_group:
                keep = False
                break
        if keep:
            visible.append(index)

    regions = {}
    for side, sign in (("l", 1.0), ("r", -1.0)):
        hand = rig.data.bones[f"hand_{side}"]
        along = (hand.tail_local - hand.head_local).normalized()
        across = (
            rig.data.bones[f"pinky_01_{side}"].head_local
            - rig.data.bones[f"index_01_{side}"].head_local
        )
        across = (across - along * across.dot(along)).normalized()
        palm_normal = across.cross(along).normalized() * sign
        coordinates = {
            index: (
                (rest_points[index] - hand.head_local).dot(across),
                (rest_points[index] - hand.head_local).dot(along),
                (rest_points[index] - hand.head_local).dot(palm_normal),
            )
            for index in visible
        }
        palm = [
            index for index in visible
            if vertex_group_weight(body, f"hand_{side}", index) >= 0.50
            and normals[index].dot(palm_normal) > 0.10
            and coordinates[index][2] > 0.005
        ]
        side_regions = {
            "heel": [
                index for index in palm
                if 0.012 <= coordinates[index][1] <= 0.040
                and abs(coordinates[index][0]) <= 0.027
            ],
            "radial_pad": [
                index for index in palm
                if 0.050 <= coordinates[index][1] <= 0.088
                and -0.026 <= coordinates[index][0] <= 0.007
            ],
            "ulnar_pad": [
                index for index in palm
                if 0.040 <= coordinates[index][1] <= 0.073
                and 0.014 <= coordinates[index][0] <= 0.043
            ],
        }
        for digit in ("thumb", "index", "middle", "ring", "pinky"):
            side_regions[f"{digit}_pad"] = [
                index for index in visible
                if sum(
                    vertex_group_weight(body, f"{digit}_{joint}_{side}", index)
                    for joint in ("02", "03")
                ) >= 0.50
                and normals[index].dot(palm_normal) > 0.15
            ]
        deform_groups = [f"hand_{side}"] + [
            f"{digit}_{joint}_{side}"
            for digit in ("thumb", "index", "middle", "ring", "pinky")
            for joint in ("01", "02", "03")
        ]
        side_regions["surface"] = [
            index for index in visible
            if sum(vertex_group_weight(body, name, index) for name in deform_groups)
            >= 0.50
        ]
        for name, indices in side_regions.items():
            if not indices:
                raise RuntimeError(f"Push-up {side} {name} has no source vertices")
        regions[side] = side_regions
    return regions


def measure_hand_contact(points, regions):
    lows = {
        name: min(points[index].z for index in indices)
        for name, indices in regions.items()
    }
    palm_indices = set(
        index
        for name in ("heel", "radial_pad", "ulnar_pad")
        for index in regions[name]
    )
    patch = [points[index] for index in palm_indices if points[index].z <= 0.006]
    span = tuple(
        max(point[axis] for point in patch) - min(point[axis] for point in patch)
        if patch else 0.0
        for axis in (0, 1)
    )
    return {"lows": lows, "palm_patch_span": span, "palm_patch_vertices": len(patch)}


def hand_contact_errors(frame, side, metrics):
    """Reject fingertip-only support even if its bounding box looks broad."""
    errors = []
    for name, low in metrics["lows"].items():
        if name == "surface":
            if low < 0.0:
                errors.append(f"frame {frame}: {side} hand surface penetrates {low:.6f}m")
        elif not 0.000 <= low <= 0.004:
            errors.append(f"frame {frame}: {side} anatomical {name} contact z={low:.6f}m")
    width, length = metrics["palm_patch_span"]
    if width < 0.040 or length < 0.050 or metrics["palm_patch_vertices"] < 6:
        errors.append(
            f"frame {frame}: {side} palm-only support patch "
            f"{width:.4f}x{length:.4f}m, vertices={metrics['palm_patch_vertices']}"
        )
    return errors


def configure_sportsuit(_rig) -> None:
    """Stabilize only the shoulder transitions and recessed collar vertices."""
    shirt = bpy.data.objects.get("Human.female_sportsuit01")
    if shirt is None or shirt.type != "MESH":
        raise RuntimeError("Approved athlete is missing the sportsuit mesh")

    for name in (
        "Push-up shoulder corrective",
        "Push-up chest Y contour",
        "Push-up chest normal contour",
        "Push-up collar fit",
    ):
        modifier = shirt.modifiers.get(name)
        if modifier is not None:
            shirt.modifiers.remove(modifier)
    for name in (
        "Push-up shoulders",
        "Push-up chest contour",
        "Push-up collar",
    ):
        group = shirt.vertex_groups.get(name)
        if group is not None:
            shirt.vertex_groups.remove(group)

    collar_core = {541, 542, 545, 546, 550, 551, 555, 556, 557}
    collar_ring_one = {161, 166, 233, 234, 428, 435, 511, 512, 513, 540, 549}
    collar_ring_two = {49, 54, 160, 162, 172, 308, 313, 427, 429, 431, 441, 544, 554}
    collar_core = {index for index in collar_core if index < len(shirt.data.vertices)}
    collar_ring_one = {
        index for index in collar_ring_one if index < len(shirt.data.vertices)
    }
    collar_ring_two = {
        index for index in collar_ring_two if index < len(shirt.data.vertices)
    }
    collar_exclusion = collar_core | collar_ring_one | collar_ring_two

    shoulder_group = shirt.vertex_groups.new(name="Push-up shoulders")
    selected = 0
    for index, vertex in enumerate(shirt.data.vertices):
        if index in collar_exclusion:
            continue
        point = vertex.co
        shoulder_cap = (
            1.15 <= point.z <= 1.245
            and 0.10 <= abs(point.x) <= 0.205
            and point.y >= -0.01
        )
        side = "l" if point.x >= 0.0 else "r"
        upperarm_weight = vertex_group_weight(
            shirt, f"upperarm_{side}", index
        )
        support_weight = (
            vertex_group_weight(shirt, f"clavicle_{side}", index)
            + vertex_group_weight(shirt, "spine_03", index)
        )
        armhole = (
            1.15 <= point.z <= 1.245
            and 0.10 <= abs(point.x) <= 0.205
            and -0.065 <= point.y < -0.01
            and upperarm_weight > 0.10
            and support_weight > 0.15
        )
        weight = 1.0 if shoulder_cap else min(0.85, upperarm_weight + support_weight)
        if not shoulder_cap and not armhole:
            weight = 0.0
        if weight > 0.0:
            shoulder_group.add([index], weight, "REPLACE")
            selected += 1
    if selected == 0:
        raise RuntimeError("Push-up shoulder correction selected no vertices")

    chest_group = shirt.vertex_groups.new(name="Push-up chest contour")
    chest_selected = 0
    for index, vertex in enumerate(shirt.data.vertices):
        point = vertex.co
        wx = 1.0 - max(0.0, min(1.0, (abs(point.x) - 0.085) / 0.050))
        wy = max(0.0, min(1.0, (-point.y - 0.085) / 0.050))
        lower_z = max(0.0, min(1.0, (point.z - 1.005) / 0.040))
        upper_z = 1.0 - max(0.0, min(1.0, (point.z - 1.115) / 0.040))
        weight = max(0.0, min(wx, wy, lower_z, upper_z))
        if weight > 0.0:
            chest_group.add([index], weight, "REPLACE")
            chest_selected += 1
    if chest_selected == 0:
        raise RuntimeError("Push-up chest correction selected no vertices")

    collar_group = shirt.vertex_groups.new(name="Push-up collar")
    collar_weights = {index: 1.0 for index in collar_core}
    collar_weights.update({index: 0.15 for index in collar_ring_one})
    collar_weights.update({index: 0.03 for index in collar_ring_two})
    for index, weight in collar_weights.items():
        if weight > 0.0:
            collar_group.add([index], weight, "REPLACE")
    if not collar_weights:
        raise RuntimeError("Push-up collar correction selected no vertices")

    chest_y = shirt.modifiers.new("Push-up chest Y contour", "DISPLACE")
    chest_y.direction = "Y"
    chest_y.space = "LOCAL"
    chest_y.strength = 0.010
    chest_y.mid_level = 0.0
    chest_y.vertex_group = chest_group.name

    chest_normal = shirt.modifiers.new(
        "Push-up chest normal contour", "DISPLACE"
    )
    chest_normal.direction = "NORMAL"
    chest_normal.strength = 0.004
    chest_normal.mid_level = 0.0
    chest_normal.vertex_group = chest_group.name

    corrective = shirt.modifiers.new(
        "Push-up shoulder corrective", "CORRECTIVE_SMOOTH"
    )
    corrective.factor = 0.30
    corrective.iterations = 4
    corrective.smooth_type = "LENGTH_WEIGHTED"
    corrective.rest_source = "ORCO"
    corrective.use_pin_boundary = True
    corrective.vertex_group = shoulder_group.name

    body = bpy.data.objects.get("Human")
    if body is None or body.type != "MESH":
        raise RuntimeError("Approved athlete is missing the body mesh")
    collar = shirt.modifiers.new("Push-up collar fit", "SHRINKWRAP")
    collar.target = body
    collar.wrap_method = "NEAREST_SURFACEPOINT"
    collar.wrap_mode = "OUTSIDE_SURFACE"
    collar.offset = 0.002
    collar.vertex_group = collar_group.name

    bpy.context.view_layer.objects.active = shirt
    shirt.select_set(True)
    bpy.ops.object.modifier_move_to_index(modifier=chest_y.name, index=1)
    bpy.ops.object.modifier_move_to_index(modifier=chest_normal.name, index=2)
    bpy.ops.object.modifier_move_to_index(modifier=corrective.name, index=3)
    bpy.ops.object.modifier_move_to_index(modifier=collar.name, index=4)
    shirt.select_set(False)
    bpy.context.view_layer.update()


def upperarm_flare_degrees(
    shoulder: Vector, elbow: Vector, ankle_mid: Vector
) -> float:
    body_back = Vector((0.0, ankle_mid.y - shoulder.y, 0.0))
    upperarm = Vector((elbow.x - shoulder.x, elbow.y - shoulder.y, 0.0))
    if min(body_back.length, upperarm.length) < 1e-8:
        raise RuntimeError("Cannot measure push-up elbow flare")
    cosine = max(
        -1.0, min(1.0, body_back.normalized().dot(upperarm.normalized()))
    )
    return math.degrees(math.acos(cosine))


def validate_motion(rig, controls) -> None:
    scene = bpy.context.scene
    original_frame = scene.frame_current
    errors = []
    elbow_angles = {"l": [], "r": []}
    knee_angles = {"l": [], "r": []}
    flare_angles = {"l": [], "r": []}
    max_body_line_error = 0.0
    max_head_line_error = 0.0
    max_wrist_error = 0.0
    max_ankle_error = 0.0
    max_elbow_step = 0.0
    previous_elbows = {}
    pose_samples = {}
    contact_metrics = {}
    bottom_shirt_clearances = []
    forefoot_baselines = {}
    body = bpy.data.objects["Human"]
    hand_regions = hand_contact_regions(rig)
    hand_contact_baselines = {}
    max_hand_contact_drift = 0.0
    hand_height_ranges = {
        side: {name: [] for name in regions}
        for side, regions in hand_regions.items()
    }
    try:
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            hand_points, _ = hand_source_geometry(body)
            frame_hand_metrics = {}
            for side, regions in hand_regions.items():
                metrics = measure_hand_contact(hand_points, regions)
                frame_hand_metrics[side] = metrics
                errors.extend(hand_contact_errors(frame, side, metrics))
                for name, indices in regions.items():
                    hand_height_ranges[side][name].append(metrics["lows"][name])
                    if name == "surface":
                        continue
                    key = (side, name)
                    if key not in hand_contact_baselines:
                        index = min(indices, key=lambda item: hand_points[item].z)
                        hand_contact_baselines[key] = (index, hand_points[index].copy())
                    index, baseline = hand_contact_baselines[key]
                    drift = (hand_points[index] - baseline).length
                    max_hand_contact_drift = max(max_hand_contact_drift, drift)
                    if drift > 0.003:
                        errors.append(
                            f"frame {frame}: {side} anatomical {name} slides {drift:.6f}m"
                        )
            shoulders = (
                world_bone_head(rig, "upperarm_l")
                + world_bone_head(rig, "upperarm_r")
            ) * 0.5
            pelvis = world_bone_head(rig, "pelvis")
            ankles = (
                world_bone_head(rig, "foot_l")
                + world_bone_head(rig, "foot_r")
            ) * 0.5
            body_line_error = distance_to_line_yz(pelvis, shoulders, ankles)
            max_body_line_error = max(max_body_line_error, body_line_error)
            if body_line_error > 0.015:
                errors.append(
                    f"frame {frame}: pelvis body-line error {body_line_error:.4f}m"
                )
            head = world_bone_head(rig, "head")
            head_line_error = distance_to_line_yz(head, shoulders, ankles)
            max_head_line_error = max(max_head_line_error, head_line_error)
            if head_line_error > 0.040:
                errors.append(
                    f"frame {frame}: head body-line error {head_line_error:.4f}m"
                )

            left_shoulder = world_bone_head(rig, "upperarm_l")
            right_shoulder = world_bone_head(rig, "upperarm_r")
            left_hip = world_bone_head(rig, "thigh_l")
            right_hip = world_bone_head(rig, "thigh_r")
            if abs(left_shoulder.z - right_shoulder.z) > 0.008:
                errors.append(f"frame {frame}: asymmetric shoulder height")
            if abs(left_hip.z - right_hip.z) > 0.010:
                errors.append(f"frame {frame}: asymmetric pelvis height")

            amount = pose_depth(frame)
            for side in ("l", "r"):
                shoulder = world_bone_head(rig, f"upperarm_{side}")
                elbow = world_bone_head(rig, f"lowerarm_{side}")
                wrist = bone_point(rig, f"lowerarm_{side}", "tail")
                hip = world_bone_head(rig, f"thigh_{side}")
                knee = world_bone_head(rig, f"calf_{side}")
                ankle = world_bone_head(rig, f"foot_{side}")
                elbow_angle = joint_angle(shoulder, elbow, wrist)
                knee_angle = joint_angle(hip, knee, ankle)
                elbow_angles[side].append(elbow_angle)
                knee_angles[side].append(knee_angle)

                wrist_error = (
                    wrist - controls["hands"][side].matrix_world.translation
                ).length
                ankle_error = (
                    ankle - controls["feet"][side].matrix_world.translation
                ).length
                max_wrist_error = max(max_wrist_error, wrist_error)
                max_ankle_error = max(max_ankle_error, ankle_error)
                if wrist_error > 0.001:
                    errors.append(
                        f"frame {frame}: {side} wrist target error {wrist_error:.5f}m"
                    )
                if ankle_error > 0.0015:
                    errors.append(
                        f"frame {frame}: {side} ankle target error {ankle_error:.5f}m"
                    )
                if knee_angle < 165.0:
                    errors.append(
                        f"frame {frame}: {side} knee angle {knee_angle:.2f}deg"
                    )

                if amount <= 0.001 and not 169.0 <= elbow_angle <= 179.0:
                    errors.append(
                        f"frame {frame}: {side} top elbow {elbow_angle:.2f}deg"
                    )
                if amount <= 0.001 and abs(shoulder.y - wrist.y) > 0.040:
                    errors.append(
                        f"frame {frame}: {side} top shoulder/wrist y offset "
                        f"{shoulder.y - wrist.y:.4f}m"
                    )
                if amount >= 0.999 and not 80.0 <= elbow_angle <= 100.0:
                    errors.append(
                        f"frame {frame}: {side} bottom elbow {elbow_angle:.2f}deg"
                    )
                if frame in (31, 91, 151, 211) and not 125.0 <= elbow_angle <= 145.0:
                    errors.append(
                        f"frame {frame}: {side} mid elbow {elbow_angle:.2f}deg"
                    )

                if elbow_angle <= 145.0:
                    flare = upperarm_flare_degrees(shoulder, elbow, ankles)
                    flare_angles[side].append(flare)
                    if not 30.0 <= flare <= 45.0:
                        errors.append(
                            f"frame {frame}: {side} elbow flare {flare:.2f}deg"
                        )

                previous = previous_elbows.get(side)
                if previous is not None:
                    step = (elbow - previous).length
                    max_elbow_step = max(max_elbow_step, step)
                    if step > 0.035:
                        errors.append(
                            f"frame {frame}: {side} elbow jumps {step:.4f}m"
                        )
                previous_elbows[side] = elbow.copy()

            if frame in {item for pair in REPEAT_PAIRS for item in pair} | {241}:
                pose_samples[frame] = {
                    name: bone_point(rig, name)
                    for name in (
                        "head",
                        "pelvis",
                        "upperarm_l",
                        "upperarm_r",
                        "lowerarm_l",
                        "lowerarm_r",
                        "hand_l",
                        "hand_r",
                        "calf_l",
                        "calf_r",
                        "foot_l",
                        "foot_r",
                    )
                }

            if frame in CONTACT_FRAMES:
                lows = {}
                for object_name in ATHLETE_MESHES:
                    if object_name not in bpy.data.objects:
                        continue
                    points = evaluated_vertices(object_name)
                    lows[object_name] = min(point.z for point in points)
                    if lows[object_name] < FLOOR_PENETRATION_LIMIT:
                        errors.append(
                            f"frame {frame}: {object_name} floor penetration "
                            f"{lows[object_name]:.4f}m"
                        )

                shoe_points = evaluated_vertices("Human.shoes05")
                shoe_object = bpy.data.objects["Human.shoes05"]
                shoe_source_points = evaluated_source_points(shoe_object)
                hands = frame_hand_metrics
                feet = {}
                for side, sign in (("l", 1.0), ("r", -1.0)):
                    ankle = controls["feet"][side].matrix_world.translation
                    shoe = [
                        point
                        for point in shoe_points
                        if point.x * sign > 0.0
                        and abs(point.y - ankle.y) < 0.28
                    ]
                    if not shoe:
                        errors.append(f"frame {frame}: {side} missing contact vertices")
                        continue
                    anatomical_forefoot = [
                        point
                        for index, point in enumerate(shoe_source_points)
                        if point.x * sign > 0.0
                        and vertex_group_weight(
                            shoe_object, f"ball_{side}", index
                        )
                        >= 0.50
                    ]
                    anatomical_heel = [
                        point
                        for index, point in enumerate(shoe_source_points)
                        if point.x * sign > 0.0
                        and vertex_group_weight(
                            shoe_object, f"foot_{side}", index
                        )
                        >= 0.50
                    ]
                    if not anatomical_forefoot or not anatomical_heel:
                        errors.append(
                            f"frame {frame}: {side} missing anatomical foot zones"
                        )
                        continue
                    shoe_low = min(point.z for point in shoe)
                    forefoot_low = min(
                        point.z for point in anatomical_forefoot
                    )
                    heel_low = min(point.z for point in anatomical_heel)
                    sole_patch = [
                        point for point in shoe if point.z <= shoe_low + 0.008
                    ]
                    sole_centroid_y = sum(point.y for point in sole_patch) / len(
                        sole_patch
                    )
                    baseline = forefoot_baselines.setdefault(side, sole_centroid_y)
                    if abs(sole_centroid_y - baseline) > 0.0033:
                        errors.append(
                            f"frame {frame}: {side} forefoot slides "
                            f"{sole_centroid_y - baseline:.4f}m"
                        )
                    if not 0.000 <= shoe_low <= 0.004:
                        errors.append(
                            f"frame {frame}: {side} forefoot contact "
                            f"z={shoe_low:.4f}m"
                        )
                    if not 0.000 <= forefoot_low <= 0.006:
                        errors.append(
                            f"frame {frame}: {side} weighted forefoot contact "
                            f"z={forefoot_low:.4f}m"
                        )
                    if heel_low < 0.025:
                        errors.append(
                            f"frame {frame}: {side} heel did not clear platform "
                            f"z={heel_low:.4f}m"
                        )
                    feet[side] = (
                        forefoot_low,
                        heel_low,
                        sole_centroid_y,
                    )
                contact_metrics[frame] = (hands, feet, lows)

                if motion_amount(frame) >= 0.999:
                    shirt_points = evaluated_vertices(
                        "Human.female_sportsuit01"
                    )
                    hip_mid = (left_hip + right_hip) * 0.5
                    chest_points = [
                        point.z
                        for point in shirt_points
                        if shoulders.y - 0.08 <= point.y <= hip_mid.y - 0.06
                    ]
                    if not chest_points:
                        errors.append(
                            f"frame {frame}: no sportsuit chest vertices"
                        )
                        continue
                    shirt_clearance = min(chest_points)
                    bottom_shirt_clearances.append(shirt_clearance)
                    if not 0.030 <= shirt_clearance <= 0.180:
                        errors.append(
                            f"frame {frame}: bottom shirt clearance "
                            f"{shirt_clearance:.4f}m"
                        )

        for first, second in REPEAT_PAIRS:
            for name, first_point in pose_samples[first].items():
                delta = (pose_samples[second][name] - first_point).length
                if delta > 0.001:
                    errors.append(
                        f"frames {first}/{second}: {name} repeat delta {delta:.6f}m"
                    )
        for name, first_point in pose_samples[1].items():
            delta = (pose_samples[241][name] - first_point).length
            if delta > 0.001:
                errors.append(
                    f"frames 1/241: {name} loop delta {delta:.6f}m"
                )

        if errors:
            unique = list(dict.fromkeys(errors))
            raise RuntimeError(
                "PUSH_UP_MOTION_CHECK FAILED\n" + "\n".join(unique[:120])
            )

        print(
            "PUSH_UP_MOTION_CHECK PASS",
            "frames=1-241",
            f"elbow_l={min(elbow_angles['l']):.2f}-{max(elbow_angles['l']):.2f}deg",
            f"elbow_r={min(elbow_angles['r']):.2f}-{max(elbow_angles['r']):.2f}deg",
            f"knee_l={min(knee_angles['l']):.2f}-{max(knee_angles['l']):.2f}deg",
            f"knee_r={min(knee_angles['r']):.2f}-{max(knee_angles['r']):.2f}deg",
            f"flare_l={min(flare_angles['l']):.2f}-{max(flare_angles['l']):.2f}deg",
            f"flare_r={min(flare_angles['r']):.2f}-{max(flare_angles['r']):.2f}deg",
            f"body_line={max_body_line_error:.4f}m",
            f"head_line={max_head_line_error:.4f}m",
            f"wrist_error={max_wrist_error:.6f}m",
            f"ankle_error={max_ankle_error:.6f}m",
            f"elbow_step={max_elbow_step:.4f}m/frame",
            f"hand_contact_drift={max_hand_contact_drift:.6f}m",
            "bottom_shirt="
            + ",".join(f"{value:.4f}m" for value in bottom_shirt_clearances),
            "loop=frames-1-121-241-equal",
        )
        for side, ranges in hand_height_ranges.items():
            print(
                "PUSH_UP_PALM_CHECK PASS",
                f"side={side} frames=1-241",
                ",".join(
                    f"{name}={min(values) * 1000:.3f}-{max(values) * 1000:.3f}mm"
                    for name, values in ranges.items()
                ),
            )
        for frame, (hands, feet, lows) in contact_metrics.items():
            print(
                "PUSH_UP_CONTACT",
                f"frame={frame}",
                f"hands={hands}",
                f"feet={feet}",
                "lows=" + ",".join(
                    f"{name}:{value:.4f}" for name, value in lows.items()
                ),
            )
    finally:
        scene.frame_set(original_frame)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -3.45, 1.45), (0.0, 0.08, 0.26), 58),
        ("side", (3.45, 0.04, 0.76), (0.0, 0.08, 0.255), 56),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Push-up {name} camera"
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
        lamp.name = f"Push-up {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.05, 0.28)))
    return cameras


def preview_directory(blend_path: str) -> str:
    return os.path.join(os.path.dirname(os.path.abspath(blend_path)), "previews")


def render_previews(cameras, blend_path: str) -> None:
    scene = bpy.context.scene
    output_dir = preview_directory(blend_path)
    os.makedirs(output_dir, exist_ok=True)
    for frame, suffix in PREVIEW_FRAMES:
        scene.frame_set(frame)
        for name, camera in cameras.items():
            scene.camera = camera
            scene.render.image_settings.file_format = "PNG"
            scene.render.filepath = os.path.join(
                output_dir, f"human_{EXERCISE}_{name}_{suffix}.png"
            )
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)
    render_contact_previews(blend_path)


def render_contact_previews(blend_path: str) -> None:
    scene = bpy.context.scene
    output_dir = preview_directory(blend_path)
    os.makedirs(output_dir, exist_ok=True)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Push-up inspection camera"
    camera.data.lens = 75
    scene.camera = camera
    inspections = (
        (
            "contact_palms",
            TOP_FRAME,
            (0.86, -0.78, 0.20),
            (0.17, -0.40, 0.025),
        ),
        (
            "contact_palms_front",
            TOP_FRAME,
            (0.0, -1.9, 0.43),
            (0.0, -0.49, 0.025),
        ),
        (
            "contact_toes",
            BOTTOM_FRAME,
            (0.72, 0.78, 0.075),
            (0.0, 0.5795, 0.002),
        ),
        (
            "elbow_path_overhead",
            BOTTOM_FRAME,
            (0.85, -0.95, 1.55),
            (0.0, -0.40, 0.18),
        ),
        (
            "scapula_rear_angle",
            BOTTOM_FRAME,
            (-1.35, 0.15, 0.85),
            (0.0, -0.48, 0.24),
        ),
        (
            "chest_clearance",
            BOTTOM_FRAME,
            (1.05, -0.40, 0.16),
            (0.0, -0.40, 0.04),
        ),
    )
    for suffix, frame, location, target in inspections:
        scene.frame_set(frame)
        camera.location = location
        camera.data.lens = 82 if suffix == "contact_toes" else 75
        look_at(camera, Vector(target))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            output_dir, f"human_{EXERCISE}_{suffix}.png"
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
    bpy.context.scene.frame_end = FRAME_END
    rig, standing_foot_rotations = reset_squat_scene()
    configure_athlete_materials()
    configure_ponytail_gravity()
    controls = animate_push_up(rig, standing_foot_rotations)
    configure_sportsuit(rig)
    validate_motion(rig, controls)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, blend_path)
    elif args.mode == "contact":
        render_contact_previews(blend_path)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
