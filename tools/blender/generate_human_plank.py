"""Build the offline forearm-plank guide from the approved athlete.

Run after opening the packed squat source file, for example::

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_plank.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/plank_human_sample.blend \
      --mode preview

The encoded loop is a static forearm plank with two very small breathing
cycles. Elbows, forearms, palms and both forefeet remain fixed throughout.
The numeric production contract lives in ``docs/motions/PLANK.md``.
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
from generate_human_tabata_mountain_climber import (  # noqa: E402
    configure_flat_hands,
    evaluated_vertices,
    joint_angle,
    world_bone_head,
)
from generate_squat_sample import configure_scene, look_at  # noqa: E402


EXERCISE = "plank"
FPS = 30
FRAME_END = 241
ENCODED_FRAMES = 240
PREVIEW_FRAMES = ((1, "exhale"), (61, "inhale"), (241, "loop"))
CONTACT_FRAMES = (1, 61, 121, 181, 241)
PLATFORM_TOP_Z = 0.0
BODY_ROTATION_DEGREES = 83.0
ELBOW_BONE_Z = 0.0495
WRIST_DROP = 0.0035
FOOT_TARGET_Z = 0.107
TARGET_KNEE_ANGLE = 172.0
TARGET_HIP_ANGLE_MIN = 170.0
SHOULDER_TARGET_Y = -0.565
FOOT_PITCH_DEGREES = 15.0
BREATH_SCALE_XY = 0.0012
BREATH_SCALE_Z = 0.0018
FLOOR_PENETRATION_LIMIT = -0.014
PONYTAIL_COMPENSATION_DEGREES = 45.0
NECK_FLEXION_DEGREES = -16.0
HEAD_COUNTER_ROTATION_DEGREES = 20.0


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


def bone_point(rig, bone_name: str, endpoint: str = "head") -> Vector:
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def distance_to_line_yz(point: Vector, start: Vector, end: Vector) -> float:
    line_y = end.y - start.y
    line_z = end.z - start.z
    length = math.hypot(line_y, line_z)
    if length < 1e-8:
        raise RuntimeError("Cannot validate a zero-length YZ line")
    point_y = point.y - start.y
    point_z = point.z - start.z
    return abs(line_y * point_z - line_z * point_y) / length


def joint_angle_yz(first: Vector, joint: Vector, third: Vector) -> float:
    """Measure a side-view joint angle without left/right lane offset."""
    first_direction = Vector((0.0, first.y - joint.y, first.z - joint.z))
    third_direction = Vector((0.0, third.y - joint.y, third.z - joint.z))
    if min(first_direction.length, third_direction.length) < 1e-8:
        raise RuntimeError("Cannot measure a collapsed YZ joint")
    cosine = max(
        -1.0,
        min(1.0, first_direction.normalized().dot(third_direction.normalized())),
    )
    return math.degrees(math.acos(cosine))


def pole_for_joint(start: Vector, end: Vector, joint: Vector) -> Vector:
    axis = end - start
    if axis.length < 1e-8:
        raise RuntimeError("Cannot build an IK pole for a zero-length chain")
    unit_axis = axis.normalized()
    projection = start + unit_axis * (joint - start).dot(unit_axis)
    offset = joint - projection
    if offset.length < 1e-6:
        offset = Vector((0.0, -1.0, 0.0))
    return joint + offset.normalized() * 0.75


def breathing_amount(frame: int) -> float:
    """Two endpoint-zero inhale pulses over the encoded 240 frames."""
    phase = math.tau * 2.0 * (frame - 1) / ENCODED_FRAMES
    return 0.5 - 0.5 * math.cos(phase)


def configure_ponytail_gravity() -> None:
    """Counter-rotate only the loose tail so it hangs toward the platform."""
    ponytail = bpy.data.objects.get("Human.ponytail01")
    if ponytail is None or ponytail.type != "MESH":
        raise RuntimeError("Approved athlete is missing the ponytail mesh")
    if ponytail.data.shape_keys is not None:
        raise RuntimeError("Plank ponytail unexpectedly already has shape keys")

    basis = ponytail.shape_key_add(name="Basis", from_mix=False)
    gravity = ponytail.shape_key_add(name="Plank gravity", from_mix=False)
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
        target = pivot + rotation @ (source - pivot)
        gravity.data[index].co = source.lerp(target, weight)
        affected += 1
    if affected == 0:
        raise RuntimeError("Plank gravity correction selected no ponytail vertices")
    gravity.value = 1.0


def configure_sportsuit_for_forearm_support() -> None:
    """Relax only the collar and armholes in the weight-bearing pose.

    MPFB's thin sportsuit can fold into the shoulder when both upper arms are
    vertical.  A small corrective pass followed by a 4 mm normal offset keeps
    the collar outside the body and removes the jagged armhole pinch.  The
    modifier is deliberately vertex-grouped; a global Solidify pass produces
    non-manifold triangles on this clothing asset.
    """
    shirt = bpy.data.objects.get("Human.female_sportsuit01")
    if shirt is None or shirt.type != "MESH":
        raise RuntimeError("Approved athlete is missing the sportsuit mesh")

    subdivisions = [
        modifier for modifier in shirt.modifiers if modifier.type == "SUBSURF"
    ]
    subdivision_visibility = [
        modifier.show_viewport for modifier in subdivisions
    ]
    try:
        for modifier in subdivisions:
            modifier.show_viewport = False
        bpy.context.view_layer.update()
        depsgraph = bpy.context.evaluated_depsgraph_get()
        evaluated = shirt.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh(
            preserve_all_data_layers=False, depsgraph=depsgraph
        )
        try:
            points = [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
        finally:
            evaluated.to_mesh_clear()
    finally:
        for modifier, visible in zip(subdivisions, subdivision_visibility):
            modifier.show_viewport = visible
        bpy.context.view_layer.update()

    if len(points) != len(shirt.data.vertices):
        raise RuntimeError(
            "Sportsuit support selection lost source vertex correspondence"
        )
    old_group = shirt.vertex_groups.get("Plank shoulder and collar")
    if old_group is not None:
        shirt.vertex_groups.remove(old_group)
    group = shirt.vertex_groups.new(name="Plank shoulder and collar")
    selected = 0
    for index, point in enumerate(points):
        collar = (
            abs(point.x) < 0.135
            and point.y < -0.525
            and point.z > 0.255
        )
        armhole = (
            abs(point.x) > 0.095
            and point.y < -0.385
            and point.z > 0.115
        )
        if collar or armhole:
            group.add([index], 1.0, "REPLACE")
            selected += 1
    if selected == 0:
        raise RuntimeError("Sportsuit support selection contains no vertices")

    corrective = shirt.modifiers.new(
        "Plank localized corrective smooth", "CORRECTIVE_SMOOTH"
    )
    corrective.factor = 0.45
    corrective.iterations = 3
    corrective.smooth_type = "LENGTH_WEIGHTED"
    corrective.rest_source = "ORCO"
    corrective.use_pin_boundary = True
    corrective.vertex_group = group.name

    displacement = shirt.modifiers.new(
        "Plank localized surface clearance", "DISPLACE"
    )
    displacement.direction = "NORMAL"
    displacement.strength = 0.004
    displacement.mid_level = 0.0
    displacement.vertex_group = group.name

    bpy.context.view_layer.objects.active = shirt
    shirt.select_set(True)
    bpy.ops.object.modifier_move_to_index(
        modifier=corrective.name, index=1
    )
    bpy.ops.object.modifier_move_to_index(
        modifier=displacement.name, index=2
    )
    shirt.select_set(False)
    bpy.context.view_layer.update()


def set_static_keys(obj) -> None:
    obj.keyframe_insert("location", frame=1)
    obj.keyframe_insert("location", frame=241)
    if obj.rotation_mode == "QUATERNION":
        obj.keyframe_insert("rotation_quaternion", frame=1)
        obj.keyframe_insert("rotation_quaternion", frame=241)


def sagittal_knee_pole(hip: Vector, ankle: Vector) -> Vector:
    axis = (ankle - hip).normalized()
    direction = Vector((0.0, -axis.z, axis.y))
    if direction.length < 1e-6:
        direction = Vector((0.0, -1.0, 0.0))
    direction.normalize()
    if direction.y > 0.0:
        direction.negate()
    return (hip + ankle) * 0.5 + direction * 1.10


def animate_plank(rig, standing_foot_rotations):
    """Pose the approved standing athlete on fixed forearms and forefeet."""
    rig.location = (0.0, 0.0, 0.0)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (math.radians(BODY_ROTATION_DEGREES), 0.0, 0.0)
    rig.scale = (1.0, 1.0, 1.0)

    for side in ("l", "r"):
        clavicle = rig.pose.bones[f"clavicle_{side}"]
        clavicle.rotation_mode = "XYZ"
        clavicle.rotation_euler = (math.radians(4.0), 0.0, 0.0)
        clavicle.keyframe_insert("rotation_euler", frame=1)
        clavicle.keyframe_insert("rotation_euler", frame=241)
    neck = rig.pose.bones["neck_01"]
    neck.rotation_mode = "XYZ"
    neck.rotation_euler = (
        math.radians(NECK_FLEXION_DEGREES), 0.0, 0.0
    )
    neck.keyframe_insert("rotation_euler", frame=1)
    neck.keyframe_insert("rotation_euler", frame=241)
    head = rig.pose.bones["head"]
    head.rotation_mode = "XYZ"
    head.rotation_euler = (
        math.radians(HEAD_COUNTER_ROTATION_DEGREES), 0.0, 0.0
    )
    head.keyframe_insert("rotation_euler", frame=1)
    head.keyframe_insert("rotation_euler", frame=241)
    bpy.context.view_layer.update()

    upper_lengths = {
        side: rig.data.bones[f"upperarm_{side}"].length for side in ("l", "r")
    }
    target_shoulder_z = ELBOW_BONE_Z + sum(upper_lengths.values()) * 0.5
    shoulders = (
        world_bone_head(rig, "upperarm_l")
        + world_bone_head(rig, "upperarm_r")
    ) * 0.5
    rig.location += Vector(
        (0.0, SHOULDER_TARGET_Y - shoulders.y, target_shoulder_z - shoulders.z)
    )
    bpy.context.view_layer.update()

    hand_targets = {}
    elbow_poles = {}
    desired_elbows = {}
    for side in ("l", "r"):
        shoulder = world_bone_head(rig, f"upperarm_{side}")
        upper_length = upper_lengths[side]
        lower_length = rig.data.bones[f"lowerarm_{side}"].length
        desired_elbow = Vector((shoulder.x, shoulder.y, shoulder.z - upper_length))
        desired_wrist = desired_elbow + Vector((0.0, -lower_length, -WRIST_DROP))
        desired_elbows[side] = desired_elbow
        hand_targets[side] = empty(
            f"{side.upper()} plank wrist target", desired_wrist
        )
        elbow_poles[side] = empty(
            f"{side.upper()} plank elbow pole",
            pole_for_joint(shoulder, desired_wrist, desired_elbow),
        )
        add_ik(
            rig,
            f"lowerarm_{side}",
            hand_targets[side],
            elbow_poles[side],
        )
    configure_flat_hands(rig)
    bpy.context.view_layer.update()

    foot_targets = {}
    knee_poles = {}
    foot_rotations = {}
    for side in ("l", "r"):
        hip = world_bone_head(rig, f"thigh_{side}")
        upper_length = rig.data.bones[f"thigh_{side}"].length
        lower_length = rig.data.bones[f"calf_{side}"].length
        support_distance = math.sqrt(
            upper_length**2
            + lower_length**2
            - 2.0
            * upper_length
            * lower_length
            * math.cos(math.radians(TARGET_KNEE_ANGLE))
        )
        target_x = hip.x
        delta_z = FOOT_TARGET_Z - hip.z
        delta_y_squared = support_distance**2 - delta_z**2
        if delta_y_squared <= 0.0:
            raise RuntimeError(f"No extended-leg plank solution for {side}")
        ankle = Vector(
            (target_x, hip.y + math.sqrt(delta_y_squared), FOOT_TARGET_Z)
        )
        foot_targets[side] = empty(f"{side.upper()} plank foot target", ankle)
        knee_poles[side] = empty(
            f"{side.upper()} plank knee pole", sagittal_knee_pole(hip, ankle)
        )
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])

        rotation = empty(f"{side.upper()} plank foot rotation")
        rotation.matrix_world = standing_foot_rotations[side]
        rotation.rotation_mode = "QUATERNION"
        base = rotation.rotation_quaternion.copy()
        rotation.rotation_quaternion = (
            Quaternion((1.0, 0.0, 0.0), math.radians(FOOT_PITCH_DEGREES))
            @ base
        )
        copy_world_rotation(rig, f"foot_{side}", rotation)
        foot_rotations[side] = rotation

    bpy.context.view_layer.update()
    for control in (
        *hand_targets.values(),
        *elbow_poles.values(),
        *foot_targets.values(),
        *knee_poles.values(),
        *foot_rotations.values(),
    ):
        control.rotation_mode = "QUATERNION"
        set_static_keys(control)

    rig.keyframe_insert("location", frame=1)
    rig.keyframe_insert("rotation_euler", frame=1)
    rig.keyframe_insert("location", frame=241)
    rig.keyframe_insert("rotation_euler", frame=241)

    for frame in range(1, FRAME_END + 1):
        breath = breathing_amount(frame)
        for bone_name in ("spine_02", "spine_03"):
            bone = rig.pose.bones[bone_name]
            bone.scale = (
                1.0 + BREATH_SCALE_XY * breath,
                1.0 + BREATH_SCALE_XY * breath,
                1.0 + BREATH_SCALE_Z * breath,
            )
            bone.keyframe_insert("scale", frame=frame)

    for animated in (
        rig,
        *hand_targets.values(),
        *elbow_poles.values(),
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
        "desired_elbows": desired_elbows,
        "feet": foot_targets,
        "knees": knee_poles,
        "foot_rotations": foot_rotations,
    }


def validate_motion(rig, controls) -> None:
    scene = bpy.context.scene
    original_frame = scene.frame_current
    errors = []
    baselines = {}
    loop_samples = {}
    max_hip_line_error = 0.0
    max_head_line_error = 0.0
    max_shoulder_motion = 0.0
    max_pelvis_motion = 0.0
    elbow_angles = {"l": [], "r": []}
    knee_angles = {"l": [], "r": []}
    hip_angles = {"l": [], "r": []}
    mesh_lows = {}
    contact_metrics = {}
    try:
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            shoulders = (
                world_bone_head(rig, "upperarm_l")
                + world_bone_head(rig, "upperarm_r")
            ) * 0.5
            pelvis = world_bone_head(rig, "pelvis")
            head = world_bone_head(rig, "head")
            heels = (
                world_bone_head(rig, "foot_l")
                + world_bone_head(rig, "foot_r")
            ) * 0.5
            hip_line_error = distance_to_line_yz(pelvis, shoulders, heels)
            head_line_error = distance_to_line_yz(head, shoulders, heels)
            max_hip_line_error = max(max_hip_line_error, hip_line_error)
            max_head_line_error = max(max_head_line_error, head_line_error)
            if hip_line_error > 0.040:
                errors.append(
                    f"frame {frame}: pelvis line error {hip_line_error:.4f}m"
                )
            if head_line_error > 0.075:
                errors.append(
                    f"frame {frame}: head line error {head_line_error:.4f}m"
                )

            if not baselines:
                baselines = {
                    "shoulders": shoulders.copy(),
                    "pelvis": pelvis.copy(),
                    "head": head.copy(),
                }
            max_shoulder_motion = max(
                max_shoulder_motion,
                (shoulders - baselines["shoulders"]).length,
            )
            max_pelvis_motion = max(
                max_pelvis_motion, (pelvis - baselines["pelvis"]).length
            )
            if max_shoulder_motion > 0.004:
                errors.append(
                    f"frame {frame}: breathing moved shoulders "
                    f"{max_shoulder_motion:.4f}m"
                )
            if max_pelvis_motion > 0.004:
                errors.append(
                    f"frame {frame}: breathing moved pelvis {max_pelvis_motion:.4f}m"
                )

            for side in ("l", "r"):
                shoulder = world_bone_head(rig, f"upperarm_{side}")
                elbow = world_bone_head(rig, f"lowerarm_{side}")
                wrist = bone_point(rig, f"lowerarm_{side}", "tail")
                hip = world_bone_head(rig, f"thigh_{side}")
                knee = world_bone_head(rig, f"calf_{side}")
                ankle = world_bone_head(rig, f"foot_{side}")
                toe = bone_point(rig, f"foot_{side}", "tail")

                elbow_angle = joint_angle(shoulder, elbow, wrist)
                knee_angle = joint_angle(hip, knee, ankle)
                hip_angle = joint_angle_yz(shoulders, hip, knee)
                elbow_angles[side].append(elbow_angle)
                knee_angles[side].append(knee_angle)
                hip_angles[side].append(hip_angle)
                if not 78.0 <= elbow_angle <= 102.0:
                    errors.append(
                        f"frame {frame}: {side} elbow {elbow_angle:.2f}deg"
                    )
                if knee_angle < 168.0:
                    errors.append(
                        f"frame {frame}: {side} knee {knee_angle:.2f}deg"
                    )
                if hip_angle < TARGET_HIP_ANGLE_MIN:
                    errors.append(
                        f"frame {frame}: {side} hip {hip_angle:.2f}deg"
                    )
                if abs(shoulder.y - elbow.y) > 0.030:
                    errors.append(
                        f"frame {frame}: {side} shoulder/elbow y offset "
                        f"{abs(shoulder.y - elbow.y):.4f}m"
                    )
                if abs(shoulder.x - elbow.x) > 0.035:
                    errors.append(
                        f"frame {frame}: {side} shoulder/elbow x offset "
                        f"{abs(shoulder.x - elbow.x):.4f}m"
                    )
                forearm = (wrist - elbow).normalized()
                if abs(forearm.x) > 0.12 or abs(forearm.z) > 0.12 or forearm.y > -0.96:
                    errors.append(
                        f"frame {frame}: {side} forearm direction "
                        f"{tuple(round(value, 3) for value in forearm)}"
                    )
                wrist_error = (
                    wrist - controls["hands"][side].matrix_world.translation
                ).length
                ankle_error = (
                    ankle - controls["feet"][side].matrix_world.translation
                ).length
                if wrist_error > 0.001:
                    errors.append(
                        f"frame {frame}: {side} wrist target error {wrist_error:.5f}m"
                    )
                if ankle_error > 0.001:
                    errors.append(
                        f"frame {frame}: {side} ankle target error {ankle_error:.5f}m"
                    )
                if toe.z < FLOOR_PENETRATION_LIMIT:
                    errors.append(
                        f"frame {frame}: {side} toe bone penetrates {toe.z:.4f}m"
                    )

            if frame in (1, 121, 241):
                loop_samples[frame] = {
                    "shoulders": shoulders.copy(),
                    "pelvis": pelvis.copy(),
                    "head": head.copy(),
                    "l_elbow": world_bone_head(rig, "lowerarm_l"),
                    "r_elbow": world_bone_head(rig, "lowerarm_r"),
                    "l_foot": world_bone_head(rig, "foot_l"),
                    "r_foot": world_bone_head(rig, "foot_r"),
                }

            if frame in CONTACT_FRAMES:
                lows = {}
                for object_name in ATHLETE_MESHES:
                    points = evaluated_vertices(object_name)
                    lows[object_name] = min(point.z for point in points)
                    if lows[object_name] < FLOOR_PENETRATION_LIMIT:
                        errors.append(
                            f"frame {frame}: {object_name} floor penetration "
                            f"{lows[object_name]:.4f}m"
                        )
                mesh_lows[frame] = lows

                body_points = evaluated_vertices("Human")
                shoes = evaluated_vertices("Human.shoes05")
                per_side = {}
                for side, sign in (("l", 1.0), ("r", -1.0)):
                    shoulder = world_bone_head(rig, f"upperarm_{side}")
                    wrist = controls["hands"][side].matrix_world.translation
                    ankle = controls["feet"][side].matrix_world.translation
                    arm_points = [
                        point
                        for point in body_points
                        if point.x * sign > 0.03
                        and wrist.y - 0.10 <= point.y <= shoulder.y + 0.10
                        and point.z < 0.18
                    ]
                    hand_points = [
                        point
                        for point in body_points
                        if point.x * sign > 0.03
                        and point.y <= wrist.y + 0.09
                        and point.z < 0.16
                    ]
                    shoe_points = [
                        point
                        for point in shoes
                        if point.x * sign > 0.0 and abs(point.y - ankle.y) < 0.25
                    ]
                    if not arm_points or not hand_points or not shoe_points:
                        errors.append(f"frame {frame}: {side} missing contact vertices")
                        continue
                    arm_min = min(point.z for point in arm_points)
                    hand_min = min(point.z for point in hand_points)
                    shoe_min = min(point.z for point in shoe_points)
                    per_side[side] = (arm_min, hand_min, shoe_min)
                    if not -0.014 <= arm_min <= 0.025:
                        errors.append(
                            f"frame {frame}: {side} forearm contact z={arm_min:.4f}"
                        )
                    if not -0.014 <= hand_min <= 0.025:
                        errors.append(
                            f"frame {frame}: {side} palm contact z={hand_min:.4f}"
                        )
                    if not -0.014 <= shoe_min <= 0.012:
                        errors.append(
                            f"frame {frame}: {side} forefoot contact z={shoe_min:.4f}"
                        )
                contact_metrics[frame] = per_side

        for sample_frame in (121, 241):
            for label, start in loop_samples[1].items():
                delta = (loop_samples[sample_frame][label] - start).length
                if delta > 0.001:
                    errors.append(
                        f"frame {sample_frame}: loop {label} delta {delta:.6f}m"
                    )

        if errors:
            unique_errors = list(dict.fromkeys(errors))
            raise RuntimeError(
                "PLANK_MOTION_CHECK FAILED\n" + "\n".join(unique_errors[:80])
            )

        print(
            "PLANK_MOTION_CHECK PASS",
            "frames=1-241",
            f"elbow_l={min(elbow_angles['l']):.2f}-{max(elbow_angles['l']):.2f}deg",
            f"elbow_r={min(elbow_angles['r']):.2f}-{max(elbow_angles['r']):.2f}deg",
            f"knee_l={min(knee_angles['l']):.2f}-{max(knee_angles['l']):.2f}deg",
            f"knee_r={min(knee_angles['r']):.2f}-{max(knee_angles['r']):.2f}deg",
            f"hip_l={min(hip_angles['l']):.2f}-{max(hip_angles['l']):.2f}deg",
            f"hip_r={min(hip_angles['r']):.2f}-{max(hip_angles['r']):.2f}deg",
            f"hip_line={max_hip_line_error:.4f}m",
            f"head_line={max_head_line_error:.4f}m",
            f"shoulder_breath={max_shoulder_motion:.4f}m",
            f"pelvis_breath={max_pelvis_motion:.4f}m",
            f"contacts={contact_metrics}",
            "loop=frames-1-121-241-equal",
        )
        for frame, lows in mesh_lows.items():
            print(
                "PLANK_FLOOR_METRICS",
                f"frame={frame}",
                " ".join(f"{name}={value:.4f}" for name, value in lows.items()),
            )
    finally:
        scene.frame_set(original_frame)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -3.35, 1.85), (0.0, 0.12, 0.20), 58),
        ("side", (3.35, 0.08, 0.72), (0.0, 0.08, 0.235), 56),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Plank {name} camera"
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
        lamp.name = f"Plank {name} light"
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


def render_contact_previews(blend_path: str) -> None:
    scene = bpy.context.scene
    output_dir = preview_directory(blend_path)
    os.makedirs(output_dir, exist_ok=True)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Plank contact inspection camera"
    camera.data.lens = 66
    scene.camera = camera
    inspections = (
        ("elbow", (1.10, -1.05, 0.46), (0.0, -0.565, 0.045)),
        ("forearm", (0.95, -1.43, 0.16), (0.0, -0.70, 0.035)),
        ("toes", (0.62, 1.72, 0.30), (0.0, 0.55, 0.035)),
        ("scapula_rear_angle", (-1.72, 0.50, 1.08), (0.0, -0.34, 0.30)),
    )
    scene.frame_set(61)
    for suffix, location, target in inspections:
        camera.location = location
        look_at(camera, Vector(target))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            output_dir, f"human_{EXERCISE}_contact_{suffix}.png"
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
    controls = animate_plank(rig, standing_foot_rotations)
    configure_sportsuit_for_forearm_support()
    validate_motion(rig, controls)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, blend_path)
        render_contact_previews(blend_path)
    elif args.mode == "contact":
        render_contact_previews(blend_path)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
