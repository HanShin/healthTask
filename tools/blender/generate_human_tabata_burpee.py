"""Build the offline low-impact Tabata burpee guide from the approved athlete.

Run after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_tabata_burpee.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/tabata_burpee_human_sample.blend \
      --mode preview

Form references used for the motion design:
  - National Academy of Sports Medicine, Squat Thrust Burpees:
    https://www.nasm.org/resource-center/exercise-library/squat-thrust-burpees
  - Royal Berkshire NHS Foundation Trust, Strength and conditioning class:
    https://www.royalberkshire.nhs.uk/media/ljrncq23/strength-and-conditioning-exercise-class_jul25.pdf

The delivery is deliberately the lower-impact, no-push-up variant: stand,
squat and plant both palms, step one foot at a time back to a high plank,
step one foot at a time forward, stand, and finish with the arms overhead.
The eight-second guide contains two brisk repetitions and alternates the lead
leg so the controlled step pattern still reads as a dynamic Tabata movement.
Frame 241 duplicates frame 1 and is omitted from the encoded 240-frame movie.
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
    configure_athlete_materials,
    copy_world_rotation,
    empty,
)
from generate_squat_sample import (  # noqa: E402
    FRAME_END,
    P,
    configure_scene,
    look_at,
    smoothstep,
)


EXERCISE = "tabata_burpee"
PLATFORM_TOP_Z = 0.0

CYCLE_FRAMES = 120

# The three user-facing inspection poses from the first repetition.
TOP_FRAME = 101       # standing finish, arms overhead
MID_FRAME = 19        # deep squat, both palms planted
BOTTOM_FRAME = 42     # high plank
ARM_START_FRAME = 81  # upright stance immediately before the forward swing

SUPPORT_FRAMES = (
    19, 20, 31, 42, 44, 55, 66, 67,
    139, 140, 151, 162, 164, 175, 186, 187,
)
VALIDATION_FRAMES = (
    1, 10, 19, 20, 26, 31, 37, 42, 43, 44, 50, 55, 61, 66,
    67, 74, 81, 91, 101, 111, 120, 121, 130, 139, 140, 146,
    151, 157, 162, 163, 164, 170, 175, 181, 186, 187, 194,
    201, 211, 221, 231, 240, 241,
)
FOOT_CONTACT_FRAMES = (
    1, 19, 20, 31, 42, 44, 55, 66, 67, 81, 101, 121,
    139, 140, 151, 162, 164, 175, 186, 187, 201, 221, 241,
)

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


def remove_object(obj):
    bpy.data.objects.remove(obj, do_unlink=True)


def reset_approved_scene():
    """Keep only the approved athlete, platform, palette and prism lights."""
    required = ("Human.rig", "Human", "Human platform", "L foot rotation target")
    missing = [name for name in required if name not in bpy.data.objects]
    if missing:
        raise RuntimeError(
            "Open design/motion/squat_human_sample.blend before this script; "
            f"missing objects: {', '.join(missing)}"
        )

    scene = bpy.context.scene
    scene.frame_set(1)
    rig = bpy.data.objects["Human.rig"]

    keep_prefixes = ("Human prism light", "Human platform")
    keep_names = {
        "Human",
        "Human.rig",
        "Human.eyebrow004",
        "Human.eyelashes01",
        "Human.female_sportsuit01",
        "Human.low-poly",
        "Human.ponytail01",
        "Human.shoes05",
        "Rigged compression shorts",
        "Plane",
    }
    for obj in list(bpy.data.objects):
        if obj.name in keep_names or obj.name.startswith(keep_prefixes):
            obj.animation_data_clear()
            continue
        remove_object(obj)

    rig.animation_data_clear()
    rig.location = (0.0, 0.0, 0.0)
    rig.rotation_mode = "QUATERNION"
    rig.rotation_quaternion = Quaternion((1.0, 0.0, 0.0, 0.0))
    rig.scale = (1.0, 1.0, 1.0)
    for pose_bone in rig.pose.bones:
        for constraint in list(pose_bone.constraints):
            pose_bone.constraints.remove(constraint)
        pose_bone.location = (0.0, 0.0, 0.0)
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = Quaternion((1.0, 0.0, 0.0, 0.0))
        pose_bone.scale = (1.0, 1.0, 1.0)
    return rig


def add_ik(rig, bone_name, target, pole, *, pole_angle=-90.0):
    constraint = rig.pose.bones[bone_name].constraints.new("IK")
    constraint.name = f"Burpee {bone_name} IK"
    constraint.target = target
    constraint.pole_target = pole
    constraint.chain_count = 2
    constraint.pole_angle = math.radians(pole_angle)
    constraint.use_stretch = False
    constraint.iterations = 48
    return constraint


def hand_basis_rotation(rig, side, desired_length):
    """Return a mirrored anatomical hand basis for a flat palm or reach."""
    sign = 1.0 if side == "l" else -1.0
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

    desired_spread = Vector((sign, 0.0, 0.0))
    desired_length = desired_length.normalized()
    desired_normal = desired_spread.cross(desired_length).normalized()
    desired_basis = Matrix(
        (desired_spread, desired_length, desired_normal)
    ).transposed()
    return (desired_basis @ source_basis.transposed()).to_quaternion()


def aim_rest_bone(rig, bone_name, desired_direction):
    bone = rig.data.bones[bone_name]
    rest_direction = (bone.tail_local - bone.head_local).normalized()
    desired_direction = desired_direction.normalized()
    delta = rest_direction.rotation_difference(desired_direction)
    return delta @ bone.matrix_local.to_quaternion()


def flatten_hands(rig):
    """Keep five readable, extended digits on each weight-bearing palm."""
    finger_pose = {
        "index": (-5.0, (2.0, 1.0, 0.0)),
        "middle": (0.0, (3.0, 2.0, 1.0)),
        "ring": (5.0, (4.0, 3.0, 2.0)),
        "pinky": (9.0, (5.0, 4.0, 2.0)),
    }
    for side, sign in (("l", 1.0), ("r", -1.0)):
        for finger, (spread, curls) in finger_pose.items():
            for joint, flex in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (
                    math.radians(flex),
                    0.0,
                    math.radians(spread * sign if joint == "01" else 0.0),
                )
        # A relaxed, nearly straight thumb broadens the support base without
        # curling beneath the palm.
        for joint, flex in (("01", 8.0), ("02", 4.0), ("03", 2.0)):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (math.radians(flex), 0.0, 0.0)


def stage_progress(frame, start, end):
    if frame <= start:
        return 0.0
    if frame >= end:
        return 1.0
    return smoothstep((frame - start) / (end - start))


def brisk_progress(frame, start, end, ease_fraction=0.20):
    """C1 trapezoid progress with short easing and a readable fast middle."""
    if frame <= start:
        return 0.0
    if frame >= end:
        return 1.0
    progress = (frame - start) / (end - start)
    peak_speed = 1.0 / (1.0 - ease_fraction)
    if progress < ease_fraction:
        return peak_speed * progress * progress / (2.0 * ease_fraction)
    if progress <= 1.0 - ease_fraction:
        return peak_speed * (progress - ease_fraction * 0.5)
    remaining = 1.0 - progress
    return 1.0 - peak_speed * remaining * remaining / (2.0 * ease_fraction)


def cycle_phase(frame):
    """Return local 0..119 time and the alternating lead leg."""
    cycle_index = (frame - 1) // CYCLE_FRAMES
    local_frame = (frame - 1) % CYCLE_FRAMES
    lead_side = "l" if cycle_index % 2 == 0 else "r"
    return local_frame, lead_side


def transfer_state(frame, side):
    """Return planted/back amount and the current foot-clearing arc."""
    local_frame, lead_side = cycle_phase(frame)
    if side == lead_side:
        back_start, back_end, forward_start, forward_end = 19, 30, 43, 54
    else:
        back_start, back_end, forward_start, forward_end = 30, 41, 54, 65

    if local_frame <= back_start:
        return 0.0, 0.0
    if local_frame <= back_end:
        transfer = brisk_progress(local_frame, back_start, back_end)
        return transfer, math.sin(math.pi * transfer)
    if local_frame <= forward_start:
        return 1.0, 0.0
    if local_frame <= forward_end:
        transfer = brisk_progress(local_frame, forward_start, forward_end)
        return 1.0 - transfer, math.sin(math.pi * transfer)
    return 0.0, 0.0


def motion_channels(frame):
    """Return crouch, independent step-back and foot-lift channels."""
    local_frame, _ = cycle_phase(frame)
    if local_frame <= 0:
        crouch = 0.0
    elif local_frame <= 18:
        crouch = brisk_progress(local_frame, 0, 18)
    elif local_frame <= 66:
        crouch = 1.0
    elif local_frame <= 80:
        crouch = 1.0 - brisk_progress(local_frame, 66, 80)
    else:
        crouch = 0.0

    states = {side: transfer_state(frame, side) for side in ("l", "r")}
    feet_back = {side: states[side][0] for side in states}
    foot_lifts = {side: states[side][1] for side in states}
    return crouch, feet_back, foot_lifts


def arm_raise_progress(frame):
    """Raise and lower both arms briskly without a high-speed shoulder snap."""
    local_frame, _ = cycle_phase(frame)
    if local_frame <= 80:
        return 0.0
    if local_frame <= 100:
        return brisk_progress(local_frame, 80, 100, ease_fraction=0.10)
    return 1.0 - brisk_progress(
        local_frame, 100, CYCLE_FRAMES, ease_fraction=0.10
    )


def pike_progress(frame):
    """Keep the hips raised continuously while the feet step in sequence."""
    local_frame, _ = cycle_phase(frame)
    step_back_pike = stage_progress(local_frame, 17, 21) * (
        1.0 - stage_progress(local_frame, 39, 41)
    )
    step_forward_pike = stage_progress(local_frame, 43, 47) * (
        1.0 - stage_progress(local_frame, 63, 66)
    )
    return max(step_back_pike, step_forward_pike)


def animate(rig):
    foot_targets = {}
    knee_poles = {}
    foot_rotations = {}
    hand_targets = {}
    elbow_poles = {}
    hand_rotations = {}

    for side, sign in (("l", 1.0), ("r", -1.0)):
        foot_targets[side] = empty(
            f"Burpee {side.upper()} ankle target", (0.16 * sign, -0.02, 0.082)
        )
        knee_poles[side] = empty(
            f"Burpee {side.upper()} knee pole", (0.30 * sign, -0.48, 0.42)
        )
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotations[side] = empty(f"Burpee {side.upper()} foot rotation")
        foot_rotations[side].rotation_mode = "QUATERNION"
        copy_world_rotation(rig, f"foot_{side}", foot_rotations[side])

        hand_targets[side] = empty(
            f"Burpee {side.upper()} wrist target", (0.22 * sign, -0.02, 0.77)
        )
        elbow_poles[side] = empty(
            f"Burpee {side.upper()} elbow pole", (0.38 * sign, -0.18, 0.95)
        )
        add_ik(rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side])
        hand_rotations[side] = empty(f"Burpee {side.upper()} hand rotation")
        hand_rotations[side].rotation_mode = "QUATERNION"
        copy_world_rotation(rig, f"hand_{side}", hand_rotations[side])

    flatten_hands(rig)

    standing_hand_rotations = {
        side: rig.data.bones[f"hand_{side}"].matrix_local.to_quaternion()
        for side in ("l", "r")
    }
    floor_hand_rotations = {
        side: hand_basis_rotation(rig, side, Vector((0.0, -1.0, 0.0)))
        for side in ("l", "r")
    }
    overhead_hand_rotations = {
        side: hand_basis_rotation(rig, side, Vector((0.0, 0.0, 1.0)))
        for side in ("l", "r")
    }
    standing_foot_rotations = {
        side: rig.data.bones[f"foot_{side}"].matrix_local.to_quaternion()
        for side in ("l", "r")
    }
    plank_foot_rotations = {
        side: aim_rest_bone(
            rig, f"foot_{side}", Vector((0.0, 0.10, -0.04))
        )
        for side in ("l", "r")
    }

    # The relaxed wrist sits slightly forward of the shoulder.  The reach then
    # swings through the scapular plane instead of abducting through a wide
    # T-pose, which avoids pinching the shoulder cap and shirt sleeve.
    standing_hand = Vector((0.170, -0.090, 0.792))
    # Evaluated MPFB palm vertices sit about 46 mm below the wrist control;
    # this height puts the palm/finger pads on the 0.0 m platform plane.
    floor_hand = Vector((0.170, -0.33, 0.046))
    # Both the upward reach and recovery follow a constant-radius forward arc
    # around the evaluated shoulder.  This prevents both an elbow collapse and
    # the lateral-abduction pinch visible in the previous shoulder silhouette.
    arm_arc_radius = 0.418
    standing_elbow = Vector((0.34, -0.18, 0.973))
    floor_elbow = Vector((0.255, -0.23, 0.27))
    overhead_elbow = Vector((0.34, -0.12, 1.39))

    clavicles = {
        side: rig.pose.bones[f"clavicle_{side}"] for side in ("l", "r")
    }
    for clavicle in clavicles.values():
        clavicle.rotation_mode = "XYZ"

    def arm_arc_location(shoulder, sign, raise_amount):
        angle_degrees = 169.3 - 157.0 * raise_amount
        angle = math.radians(angle_degrees)
        horizontal_direction = Vector((0.38 * sign, -0.925, 0.0)).normalized()
        return Vector(
            shoulder
            + horizontal_direction * (arm_arc_radius * math.sin(angle))
            + Vector((0.0, 0.0, arm_arc_radius * math.cos(angle)))
        )

    leg_lengths = {
        side: (
            rig.data.bones[f"thigh_{side}"].length,
            rig.data.bones[f"calf_{side}"].length,
        )
        for side in ("l", "r")
    }

    def sagittal_knee_pole(side, hip, ankle):
        """Place the pole in the hip-ankle sagittal plane, toward the camera."""
        leg_axis = ankle - hip
        distance = leg_axis.length
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
        # Keep the knee on the forward branch throughout the step.  The pike
        # channel raises the hips enough for this continuous path to clear the
        # platform when the ankle passes beneath the pelvis.
        if pole_direction.y > 0.0:
            pole_direction.negate()
        return circle_center + pole_direction * 1.20

    def align_knee_to_leg_center(side, pole_location, target_x):
        """Solve pole x so the evaluated knee stays between hip and ankle."""
        low = -2.0
        high = 2.0
        for _ in range(9):
            middle = (low + high) * 0.5
            knee_poles[side].location = (
                middle,
                pole_location.y,
                pole_location.z,
            )
            bpy.context.view_layer.update()
            knee_x = (
                rig.matrix_world @ rig.pose.bones[f"calf_{side}"].head
            ).x
            if knee_x < target_x:
                low = middle
            else:
                high = middle
        knee_poles[side].location.x = (low + high) * 0.5
        bpy.context.view_layer.update()

    # Reference the approved deep-support shoulder position so a brief hip
    # pike can happen without dragging the planted hands or shoulder girdle.
    rig.rotation_quaternion = Quaternion((1.0, 0.0, 0.0), math.radians(67.0))
    rig.location = Vector((0.0, 0.80, 0.022))
    for clavicle in clavicles.values():
        clavicle.rotation_euler = (math.radians(4.0), 0.0, 0.0)
    bpy.context.view_layer.update()
    support_shoulder_center = sum(
        (
            rig.matrix_world @ rig.pose.bones[f"upperarm_{side}"].head
            for side in ("l", "r")
        ),
        Vector((0.0, 0.0, 0.0)),
    ) * 0.5

    for frame in range(1, FRAME_END + 1):
        crouch, feet_back, foot_lifts = motion_channels(frame)
        arm_raise = arm_raise_progress(frame)
        pike = pike_progress(frame)

        # Rotating the whole neutral skeleton to 67 degrees creates a straight
        # shoulder-hip-heel line in the plank.  Fixed IK contacts fold the same
        # rig naturally into the preceding deep squat.
        rig.rotation_quaternion = Quaternion(
            (1.0, 0.0, 0.0), math.radians(67.0 * crouch + 23.0 * pike)
        )
        rig.location = Vector((0.0, 0.80 * crouch, 0.022))

        # The clavicles protract gently while weight-bearing, then contribute
        # a full upward rotation during the reach.  This shares deformation
        # with the shoulder girdle instead of concentrating it in the sleeve.
        for side, sign in (("l", 1.0), ("r", -1.0)):
            clavicles[side].rotation_euler = (
                math.radians(4.0 * crouch + 7.0 * arm_raise),
                0.0,
                math.radians(16.0 * sign * arm_raise),
            )
            clavicles[side].keyframe_insert("rotation_euler", frame=frame)
        bpy.context.view_layer.update()
        if pike > 0.0:
            shoulder_center = sum(
                (
                    rig.matrix_world @ rig.pose.bones[f"upperarm_{side}"].head
                    for side in ("l", "r")
                ),
                Vector((0.0, 0.0, 0.0)),
            ) * 0.5
            shoulder_offset = support_shoulder_center - shoulder_center
            rig.location.y += shoulder_offset.y
            rig.location.z += shoulder_offset.z
            bpy.context.view_layer.update()
        rig.keyframe_insert("rotation_quaternion", frame=frame)
        rig.keyframe_insert("location", frame=frame)

        # Each foot moves separately, keeping three points of support throughout
        # the low-impact transition.  Narrow sagittal knee poles prevent the IK
        # solver from flipping the knees outward in the front view.
        for side, sign in (("l", 1.0), ("r", -1.0)):
            back_amount = feet_back[side]
            forefoot_amount = smoothstep(
                max(0.0, min(1.0, (back_amount - 0.70) / 0.30))
            )
            foot_arc = (
                0.055 * foot_lifts[side]
                + 0.085 * math.sin(math.pi * forefoot_amount)
            )
            foot_targets[side].location = Vector(
                (
                    0.16 * sign,
                    -0.02 + 0.776 * back_amount,
                    0.082 + 0.014 * forefoot_amount + foot_arc,
                )
            )
            foot_targets[side].keyframe_insert("location", frame=frame)
            hip = rig.matrix_world @ rig.pose.bones[f"thigh_{side}"].head
            pole_location = sagittal_knee_pole(
                side, hip, foot_targets[side].matrix_world.translation
            )
            align_knee_to_leg_center(
                side,
                pole_location,
                (hip.x + foot_targets[side].matrix_world.translation.x) * 0.5,
            )
            knee_poles[side].keyframe_insert("location", frame=frame)
            foot_rotations[side].rotation_quaternion = standing_foot_rotations[
                side
            ].slerp(plank_foot_rotations[side], forefoot_amount)
            foot_rotations[side].keyframe_insert("rotation_quaternion", frame=frame)

            stand_hand = Vector(
                (standing_hand.x * sign, standing_hand.y, standing_hand.z)
            )
            planted_hand = Vector(
                (floor_hand.x * sign, floor_hand.y, floor_hand.z)
            )
            hand_location = stand_hand.lerp(planted_hand, crouch)
            if arm_raise > 0.0:
                shoulder = rig.matrix_world @ rig.pose.bones[
                    f"upperarm_{side}"
                ].head
                hand_location = arm_arc_location(shoulder, sign, arm_raise)
            hand_targets[side].location = hand_location
            hand_targets[side].keyframe_insert("location", frame=frame)

            stand_pole = Vector(
                (standing_elbow.x * sign, standing_elbow.y, standing_elbow.z)
            )
            plant_pole = Vector(
                (floor_elbow.x * sign, floor_elbow.y, floor_elbow.z)
            )
            raised_pole = Vector(
                (overhead_elbow.x * sign, overhead_elbow.y, overhead_elbow.z)
            )
            elbow_location = stand_pole.lerp(plant_pole, crouch)
            if arm_raise > 0.0:
                elbow_location = stand_pole.lerp(raised_pole, arm_raise)
            elbow_poles[side].location = elbow_location
            elbow_poles[side].keyframe_insert("location", frame=frame)

            hand_rotation = standing_hand_rotations[side].slerp(
                floor_hand_rotations[side], crouch
            )
            if arm_raise > 0.0:
                hand_rotation = standing_hand_rotations[side].slerp(
                    overhead_hand_rotations[side], arm_raise
                )
            hand_rotations[side].rotation_quaternion = hand_rotation
            hand_rotations[side].keyframe_insert("rotation_quaternion", frame=frame)

    # With one key per frame, linear interpolation preserves the calculated
    # smoothstep trajectory and cannot overshoot the platform contact plane.
    for obj in (rig, *foot_targets.values(), *knee_poles.values(),
                *foot_rotations.values(), *hand_targets.values(),
                *elbow_poles.values(), *hand_rotations.values()):
        if obj.animation_data is None or obj.animation_data.action is None:
            continue
        for curve in obj.animation_data.action.fcurves:
            for point in curve.keyframe_points:
                point.interpolation = "LINEAR"

    bpy.context.scene.frame_set(1)
    return {
        "feet": foot_targets,
        "hands": hand_targets,
        "clavicles": clavicles,
    }


def evaluated_world_vertices(obj):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        return [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
    finally:
        evaluated.to_mesh_clear()


def region_min_z(object_name, center, radius_x, radius_y):
    vertices = evaluated_world_vertices(bpy.data.objects[object_name])
    local = [
        vertex.z
        for vertex in vertices
        if abs(vertex.x - center.x) <= radius_x
        and abs(vertex.y - center.y) <= radius_y
    ]
    if not local:
        raise RuntimeError(
            f"No evaluated {object_name} vertices near contact point {tuple(center)}"
        )
    return min(local)


def bone_point(rig, bone_name, endpoint="head"):
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def joint_angle(first, pivot, third):
    first_direction = (first - pivot).normalized()
    third_direction = (third - pivot).normalized()
    cosine = max(-1.0, min(1.0, first_direction.dot(third_direction)))
    return math.degrees(math.acos(cosine))


def distance_to_line(point, line_start, line_end):
    line = line_end - line_start
    progress = max(
        0.0,
        min(1.0, (point - line_start).dot(line) / line.length_squared),
    )
    return (point - (line_start + line * progress)).length


def validate_motion(rig, controls):
    scene = bpy.context.scene
    original_frame = scene.frame_current
    problems = []
    max_upperarm_delta = 0.0
    shoulder_lifts = []
    try:
        for frame in VALIDATION_FRAMES:
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            # The platform is a horizontal cylinder with a 0.0 m top.  Vertex
            # minima reject knees, hips, torso or head passing through it while
            # allowing at most 12 mm of render-safe palm/shoe compression.
            for object_name in ATHLETE_MESHES:
                if object_name not in bpy.data.objects:
                    continue
                vertices = evaluated_world_vertices(bpy.data.objects[object_name])
                platform_vertices = [
                    vertex.z
                    for vertex in vertices
                    if vertex.x * vertex.x + (vertex.y - 0.02) ** 2 <= 1.23 ** 2
                ]
                if platform_vertices and min(platform_vertices) < PLATFORM_TOP_Z - 0.012:
                    problems.append(
                        f"frame {frame}: {object_name} reaches z={min(platform_vertices):.4f}"
                    )

            # Shoe soles meet the platform at every held pose.  The two transfer
            # frames intentionally contain the small clearing arc above.
            if frame in FOOT_CONTACT_FRAMES:
                for side, sign in (("l", 1.0), ("r", -1.0)):
                    foot_center = controls["feet"][side].matrix_world.translation
                    sole_z = region_min_z(
                        "Human.shoes05", foot_center, radius_x=0.18, radius_y=0.20
                    )
                    if not (-0.008 <= sole_z <= 0.018):
                        problems.append(
                            f"frame {frame}: {side} shoe contact z={sole_z:.4f}"
                        )

        for frame in SUPPORT_FRAMES:
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            for side in ("l", "r"):
                wrist = controls["hands"][side].matrix_world.translation
                palm_z = region_min_z(
                    "Human", wrist, radius_x=0.15, radius_y=0.18
                )
                if not (-0.012 <= palm_z <= 0.018):
                    problems.append(
                        f"frame {frame}: {side} palm contact z={palm_z:.4f}"
                    )

        # Joint-space checks run on all 241 frames.  They catch transient IK
        # flips that can sit entirely between the sampled mesh-contact frames.
        elbow_history = {"l": [], "r": []}
        upperarm_history = {"l": None, "r": None}
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            crouch, feet_back, _ = motion_channels(frame)
            support = crouch >= 0.999
            standing = crouch <= 0.001 and max(feet_back.values()) <= 0.001
            plank = (
                support
                and min(feet_back.values()) >= 0.999
                and pike_progress(frame) <= 0.001
            )

            for side in ("l", "r"):
                hip = bone_point(rig, f"thigh_{side}")
                knee = bone_point(rig, f"calf_{side}")
                ankle = bone_point(rig, f"foot_{side}")
                shoulder = bone_point(rig, f"upperarm_{side}")
                elbow = bone_point(rig, f"lowerarm_{side}")
                wrist = controls["hands"][side].matrix_world.translation

                outside_x = max(
                    min(hip.x, ankle.x) - knee.x,
                    knee.x - max(hip.x, ankle.x),
                    0.0,
                )
                if outside_x > 0.040:
                    problems.append(
                        f"frame {frame}: {side} knee leaves sagittal corridor "
                        f"by {outside_x:.3f} m"
                    )

                knee_angle = joint_angle(hip, knee, ankle)
                if knee_angle < 35.0:
                    problems.append(
                        f"frame {frame}: {side} knee collapses to "
                        f"{knee_angle:.1f} degrees"
                    )
                if support and knee.z < 0.060:
                    problems.append(
                        f"frame {frame}: {side} knee drops to z={knee.z:.3f} m"
                    )
                if standing and knee_angle < 165.0:
                    problems.append(
                        f"frame {frame}: {side} standing knee only "
                        f"{knee_angle:.1f} degrees"
                    )
                if plank and knee_angle < 160.0:
                    problems.append(
                        f"frame {frame}: {side} plank knee only "
                        f"{knee_angle:.1f} degrees"
                    )

                elbow_angle = joint_angle(shoulder, elbow, wrist)
                elbow_history[side].append(elbow_angle)
                if elbow_angle < 160.0:
                    problems.append(
                        f"frame {frame}: {side} elbow folds to "
                        f"{elbow_angle:.1f} degrees"
                    )
                if len(elbow_history[side]) >= 2:
                    delta = abs(
                        elbow_history[side][-1] - elbow_history[side][-2]
                    )
                    if delta > 20.0:
                        problems.append(
                            f"frame {frame}: {side} elbow changes "
                            f"{delta:.1f} degrees in one frame"
                        )
                if len(elbow_history[side]) >= 3:
                    acceleration = abs(
                        elbow_history[side][-1]
                        - 2.0 * elbow_history[side][-2]
                        + elbow_history[side][-3]
                    )
                    if acceleration > 20.0:
                        problems.append(
                            f"frame {frame}: {side} elbow angular acceleration "
                            f"is {acceleration:.1f} degrees/frame^2"
                        )

                upperarm_rotation = (
                    rig.matrix_world @ rig.pose.bones[f"upperarm_{side}"].matrix
                ).to_quaternion()
                previous_rotation = upperarm_history[side]
                if previous_rotation is not None:
                    rotation_delta = math.degrees(
                        previous_rotation.rotation_difference(
                            upperarm_rotation
                        ).angle
                    )
                    rotation_delta = min(rotation_delta, 360.0 - rotation_delta)
                    max_upperarm_delta = max(
                        max_upperarm_delta, rotation_delta
                    )
                    if rotation_delta > 14.0:
                        problems.append(
                            f"frame {frame}: {side} upper arm rotates "
                            f"{rotation_delta:.1f} degrees in one frame"
                        )
                upperarm_history[side] = upperarm_rotation

                if support:
                    wrist_dx = abs(wrist.x - shoulder.x)
                    wrist_dy = abs(wrist.y - shoulder.y)
                    if wrist_dx > 0.050 or wrist_dy > 0.070:
                        problems.append(
                            f"frame {frame}: {side} wrist/shoulder alignment "
                            f"dx={wrist_dx:.3f}, dy={wrist_dy:.3f} m"
                        )

            if plank:
                shoulder_mid = sum(
                    (bone_point(rig, f"upperarm_{side}") for side in ("l", "r")),
                    Vector((0.0, 0.0, 0.0)),
                ) * 0.5
                hip_mid = sum(
                    (bone_point(rig, f"thigh_{side}") for side in ("l", "r")),
                    Vector((0.0, 0.0, 0.0)),
                ) * 0.5
                ankle_mid = sum(
                    (bone_point(rig, f"foot_{side}") for side in ("l", "r")),
                    Vector((0.0, 0.0, 0.0)),
                ) * 0.5
                plank_line_error = distance_to_line(
                    hip_mid, shoulder_mid, ankle_mid
                )
                if plank_line_error > 0.050:
                    problems.append(
                        f"frame {frame}: plank hip line error="
                        f"{plank_line_error:.3f} m"
                    )

        # The clavicle must rise with the overhead reach rather than leaving
        # the shoulder cap pinned while the upper arm rotates above the head.
        for cycle_offset in (0, CYCLE_FRAMES):
            shoulder_heights = {}
            for frame in (
                ARM_START_FRAME + cycle_offset,
                TOP_FRAME + cycle_offset,
            ):
                scene.frame_set(frame)
                bpy.context.view_layer.update()
                shoulder_heights[frame] = sum(
                    bone_point(rig, f"upperarm_{side}").z
                    for side in ("l", "r")
                ) * 0.5
            shoulder_lift = (
                shoulder_heights[TOP_FRAME + cycle_offset]
                - shoulder_heights[ARM_START_FRAME + cycle_offset]
            )
            shoulder_lifts.append(shoulder_lift)
            if not (0.025 <= shoulder_lift <= 0.050):
                problems.append(
                    f"cycle {cycle_offset // CYCLE_FRAMES + 1}: overhead "
                    f"clavicle lift={shoulder_lift:.3f} m"
                )

        # The duplicate endpoint is an evaluated-pose check, not just a channel
        # check, so every visible bone must close the loop exactly.
        snapshots = []
        for frame in (1, FRAME_END):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            snapshots.append(
                {
                    name: (bone_point(rig, name), bone_point(rig, name, "tail"))
                    for name in (
                        "head", "hand_l", "hand_r", "pelvis",
                        "calf_l", "calf_r", "foot_l", "foot_r",
                        "clavicle_l", "clavicle_r",
                        "pinky_03_l", "pinky_03_r",
                    )
                }
            )
        loop_error = max(
            (snapshots[0][name][endpoint] - snapshots[1][name][endpoint]).length
            for name in snapshots[0]
            for endpoint in (0, 1)
        )
        if loop_error > 0.0001:
            problems.append(f"frame 1/241 loop error={loop_error:.6f} m")
    finally:
        scene.frame_set(original_frame)

    if problems:
        raise RuntimeError("BURPEE_POSE_CHECK failed: " + "; ".join(problems))
    print(
        "BURPEE_POSE_CHECK PASS",
        f"support_frames={SUPPORT_FRAMES}",
        f"validation_frames={VALIDATION_FRAMES}",
        f"max_upperarm_delta={max_upperarm_delta:.1f}deg/frame",
        "shoulder_lifts=" + ",".join(
            f"{shoulder_lift:.3f}m" for shoulder_lift in shoulder_lifts
        ),
        "variant=two-rep-alternating-sequential-step-no-push-up-no-jump",
        "loop=frame-1-equals-frame-241",
    )


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.35, 0.94), (0.0, 0.06, 0.82), 58),
        ("side", (4.05, 0.10, 0.94), (0.0, 0.10, 0.78), 58),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Burpee {name.title()} camera"
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
        lamp.name = f"Burpee {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.10, 0.72)))
    return cameras


def preview_directory(output_dir):
    return os.path.abspath(
        os.path.join(
            os.path.dirname(output_dir),
            "..", "..", "..", "..", "design", "motion", "previews",
        )
    )


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


def render_contact_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    os.makedirs(preview_dir, exist_ok=True)
    scene.frame_set(BOTTOM_FRAME)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Burpee palm contact inspection camera"
    camera.data.lens = 76
    scene.camera = camera
    target = Vector((0.0, -0.34, 0.10))
    for suffix, location in (
        ("palm_contact_front", (0.0, -1.60, 0.30)),
        ("palm_contact_side", (1.32, -0.30, 0.27)),
        ("palm_contact_top", (0.65, -0.37, 1.28)),
    ):
        camera.location = location
        look_at(camera, target)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_{suffix}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("CONTACT_PREVIEW", scene.render.filepath)


def render_movies(cameras, output_dir):
    scene = bpy.context.scene
    if hasattr(scene, "eevee"):
        scene.eevee.taa_render_samples = 4
    os.makedirs(output_dir, exist_ok=True)
    for name, camera in cameras.items():
        scene.camera = camera
        scene.frame_start = 1
        scene.frame_end = FRAME_END - 1
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
                        ffmpeg, "-y", "-loglevel", "warning",
                        "-framerate", str(scene.render.fps),
                        "-i", os.path.join(frame_dir, "frame_%04d.png"),
                        "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                        "-an", movie_path,
                    ],
                    check=True,
                )
        print("MOVIE", movie_path)


def main():
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    configure_scene()
    rig = reset_approved_scene()
    configure_athlete_materials()
    controls = animate(rig)
    validate_motion(rig, controls)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    elif args.mode == "contact":
        render_contact_previews(output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
