"""Build the offline standing dumbbell Romanian-deadlift guide.

Run with Blender after opening the packed approved squat source, for example::

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_dumbbell_romanian_deadlift.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/dumbbell_romanian_deadlift_human_sample.blend \
      --mode preview

The athlete keeps both feet planted and both knees softly bent while the hips
travel back on a sine-derived path.  Pelvis, spine, neck and head receive the
same world-space hinge rotation so the trunk remains long instead of folding
through the lumbar spine.  The dumbbells use a closed overhand grip with the
backs of both hands facing the front camera, and track close to the front of
the thighs and shins throughout the controlled eight-second repetition.

Form references:
  - https://www.nasm.org/resource-center/exercise-library/dumbbell-romanian-deadlift
  - https://www.acefitness.org/continuing-education/certified/may-2025/8865/the-ace-do-it-better-series-the-romanian-deadlift/
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
    smoothstep,
)
from motion_collision import assert_no_mesh_intersections  # noqa: E402


EXERCISE = "dumbbell_romanian_deadlift"
FPS = 30
FRAME_END = 241
ENCODED_FRAMES = FRAME_END - 1
TOP_FRAME = 1
MID_FRAME = 61
BOTTOM_FRAME = 121
KEY_FRAMES = (TOP_FRAME, MID_FRAME, BOTTOM_FRAME, FRAME_END)

# The root moves mostly backward, not downward: solve_root_height supplies only
# the small vertical correction required to preserve the same soft knee angle
# through the hinge.  These values also keep the shins nearly vertical and put
# each handle at upper-shin height instead of stopping above the knee.
HINGE_DEGREES = 60.0
HIP_BACK_TRAVEL = 0.18
KNEE_ANGLE = 162.0
ELBOW_FLEXION = 10.0
# The overhand version keeps the hands in front of the thighs rather than at
# the athlete's sides.  The top pose starts slightly forward; the arm ray then
# sweeps back as the shoulders travel forward during the hinge so the weights
# remain close to the shins instead of swinging away from the body.
ARM_LATERAL_OFFSET = 0.03
ARM_TOP_FORWARD_COMPONENT = 0.42
ARM_BOTTOM_BACK_COMPONENT = 0.08
ARM_REACH_SCALE = 1.15
ARM_IK_STRETCH = 0.20
DUMBBELL_FOREARM_OFFSET = 0.080
DUMBBELL_PALM_OFFSET = 0.024
DUMBBELL_HANDLE_AXIS_OFFSET = 0.009

# The approved compact dumbbell proportions from the original prototype.
DUMBBELL_HANDLE_RADIUS = 0.016
DUMBBELL_HANDLE_LENGTH = 0.20
DUMBBELL_PLATE_RADIUS = 0.075
DUMBBELL_PLATE_THICKNESS = 0.050
DUMBBELL_PLATE_CENTER = 0.105
DUMBBELL_CAP_RADIUS = 0.052
DUMBBELL_CAP_THICKNESS = 0.006
DUMBBELL_CAP_CENTER = 0.133

SHORTS_FABRIC_THICKNESS = 0.006
PONYTAIL_PIVOT = Vector((0.0, 0.055, 1.42))
PONYTAIL_HALF_COMPENSATION = -HINGE_DEGREES * 0.5
PONYTAIL_FULL_COMPENSATION = -HINGE_DEGREES

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

DUMBBELL_PLATE_COLLIDERS = tuple(
    f"{side} RDL dumbbell {part} {end_sign:+d}"
    for side in ("L", "R")
    for part in ("plate", "cap")
    for end_sign in (-1, 1)
)

DUMBBELL_COMPONENTS = {
    side: (
        f"{side} RDL dumbbell handle",
        *(f"{side} RDL dumbbell plate {end_sign:+d}" for end_sign in (-1, 1)),
        *(f"{side} RDL dumbbell cap {end_sign:+d}" for end_sign in (-1, 1)),
    )
    for side in ("L", "R")
}

GRIP_DIGITS = ("index", "middle", "ring", "pinky", "thumb")
GRIP_FINGERS = GRIP_DIGITS[:-1]

# BVH intersection checks supplement the analytic all-frame path validator.
# The movement is smooth and monotonic, so dense phase samples plus every key
# pose cover the closest plate/body relationships without adding several
# thousand evaluated-mesh builds to each preview run.
COLLISION_FRAMES = tuple(sorted({*range(1, FRAME_END + 1, 15), *KEY_FRAMES}))

MAX_FOOT_TARGET_DRIFT = 0.0005
MAX_FOOT_BONE_DRIFT = 0.0030
MAX_FOOT_ROTATION_DRIFT = 0.20
MIN_SOLE_DEPTH = 0.003
MAX_SOLE_DEPTH = 0.024
MAX_KNEE_ANGLE_ERROR = 0.75
MAX_KNEE_SYMMETRY_ERROR = 0.008
MAX_KNEE_FRAME_TRAVEL = 0.012
MAX_KNEE_FRAME_ACCELERATION = 0.004
MAX_KNEE_ANGLE_DELTA = 1.5
MAX_KNEE_ANGLE_ACCELERATION = 0.75
MAX_BOTTOM_SHANK_FROM_VERTICAL = 9.0
MIN_BOTTOM_KNEE_FLEXION = 12.0
MAX_BOTTOM_KNEE_FLEXION = 20.0
MAX_TRUNK_LATERAL_OFFSET = 0.012
MAX_HINGE_ANGLE_ERROR = 1.5
MAX_SPINE_SHAPE_DRIFT = 1.25
MAX_WRIST_TARGET_ERROR = 0.004
MIN_ELBOW_ANGLE = 165.0
MAX_ELBOW_ANGLE = 179.0
MAX_ARM_STRETCH = 1.17
MAX_DUMBBELL_SYMMETRY_ERROR = 0.003
MAX_DUMBBELL_OFFSET_ERROR = 0.003
MAX_DUMBBELL_FRAME_TRAVEL = 0.018
MAX_DUMBBELL_FRAME_ACCELERATION = 0.005
MAX_GRIP_BASIS_ERROR = 1.0
MIN_PALM_POSTERIOR_DOT = 0.88
MIN_HANDLE_LATERAL_DOT = 0.94
MIN_DUMBBELL_PAIR_GAP = 0.010
MAX_GRIP_TIP_AXIAL_OFFSET = 0.050
MIN_GRIP_TIP_RADIAL_DISTANCE = 0.014
MAX_GRIP_TIP_RADIAL_DISTANCE = 0.045
MAX_THUMB_FINGER_OPPOSITION_DOT = -0.45
MAX_GRIP_TIP_MIRROR_ERROR = 0.003
MIN_DUMBBELL_PLATFORM_CLEARANCE = 0.035
MIN_DUMBBELL_VERTICAL_TRAVEL = 0.20
MIN_BOTTOM_HANDLE_SHIN_FRACTION = 0.85
MAX_BOTTOM_HANDLE_SHIN_FRACTION = 1.00
MAX_LOOP_POSITION_ERROR = 0.0005
MAX_LOOP_ROTATION_ERROR = 0.10


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "grip", "render", "validate"),
        default="preview",
    )
    return parser.parse_args(argv)


def depth_at(frame: int) -> float:
    """One controlled repetition with readable top and bottom holds."""
    t = (frame - 1) / ENCODED_FRAMES
    if t < 0.10:
        return 0.0
    if t < 0.40:
        return smoothstep((t - 0.10) / 0.30)
    if t < 0.52:
        return 1.0
    if t < 0.82:
        return 1.0 - smoothstep((t - 0.52) / 0.30)
    return 0.0


def hip_back_at(depth: float) -> float:
    """Ease the hips backward along the approved sine-derived path."""
    if depth <= 0.0:
        return 0.0
    hinge = math.radians(HINGE_DEGREES * depth)
    return HIP_BACK_TRAVEL * (
        math.sin(hinge) / math.sin(math.radians(HINGE_DEGREES))
    )


def configure_rdl_clothing() -> None:
    """Separate the compression shorts from the fitted outer sportsuit."""
    shorts = bpy.data.objects.get("Rigged compression shorts")
    if shorts is None:
        raise RuntimeError("RDL source is missing Rigged compression shorts")
    fabric = shorts.modifiers.get("Fabric thickness")
    if fabric is None or fabric.type != "SOLIDIFY":
        raise RuntimeError("RDL shorts are missing the Fabric thickness modifier")
    fabric.thickness = SHORTS_FABRIC_THICKNESS


def configure_ponytail_gravity() -> None:
    """Counter-rotate the loose ponytail as the head follows the hip hinge."""
    ponytail = bpy.data.objects.get("Human.ponytail01")
    if ponytail is None or ponytail.type != "MESH":
        raise RuntimeError("RDL source is missing the ponytail mesh")
    if ponytail.data.shape_keys is not None:
        raise RuntimeError("RDL ponytail unexpectedly already has shape keys")

    basis = ponytail.shape_key_add(name="Basis", from_mix=False)
    half = ponytail.shape_key_add(name="RDL gravity half", from_mix=False)
    full = ponytail.shape_key_add(name="RDL gravity full", from_mix=False)
    half.interpolation = "KEY_LINEAR"
    full.interpolation = "KEY_LINEAR"
    half_rotation = Matrix.Rotation(
        math.radians(PONYTAIL_HALF_COMPENSATION), 4, "X"
    )
    full_rotation = Matrix.Rotation(
        math.radians(PONYTAIL_FULL_COMPENSATION), 4, "X"
    )
    tail_z_min = min(point.co.z for point in basis.data)
    tail_z_base = 1.43
    tail_height = tail_z_base - tail_z_min
    if tail_height <= 0.0:
        raise RuntimeError("RDL ponytail has invalid local-space bounds")

    affected = 0
    for index, point in enumerate(basis.data):
        source = point.co.copy()
        if source.y <= 0.02 or source.z >= tail_z_base:
            continue
        weight = smoothstep((tail_z_base - source.z) / tail_height)
        half_target = PONYTAIL_PIVOT + half_rotation @ (
            source - PONYTAIL_PIVOT
        )
        full_target = PONYTAIL_PIVOT + full_rotation @ (
            source - PONYTAIL_PIVOT
        )
        half.data[index].co = source.lerp(half_target, weight)
        full.data[index].co = source.lerp(full_target, weight)
        affected += 1
    if affected == 0:
        raise RuntimeError("RDL ponytail gravity keys affect no vertices")

    for frame in range(1, FRAME_END + 1):
        depth = depth_at(frame)
        if depth <= 0.5:
            half.value = 2.0 * depth
            full.value = 0.0
        else:
            half.value = 2.0 - 2.0 * depth
            full.value = 2.0 * depth - 1.0
        half.keyframe_insert("value", frame=frame)
        full.keyframe_insert("value", frame=frame)
    half.value = 0.0
    full.value = 0.0


def bone_point(rig, bone_name: str, endpoint: str = "head") -> Vector:
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def joint_angle(first: Vector, joint: Vector, last: Vector) -> float:
    return math.degrees((first - joint).angle(last - joint))


def knee_angle(rig, side: str) -> float:
    return joint_angle(
        bone_point(rig, f"thigh_{side}"),
        bone_point(rig, f"calf_{side}"),
        bone_point(rig, f"calf_{side}", "tail"),
    )


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


def add_rotation_control(rig, bone_name: str, label: str):
    """Rotate a pose bone in world space while retaining its rest basis."""
    bone = rig.data.bones[bone_name]
    control = empty(f"RDL {label} rotation")
    control.rotation_mode = "QUATERNION"
    rest_world = (rig.matrix_world @ bone.matrix_local).to_quaternion()
    control.rotation_quaternion = rest_world
    copy_world_rotation(rig, bone_name, control)
    return control, rest_world


def solve_root_height(rig, y_position: float) -> float:
    """Find the root Z that preserves the approved soft knee bend."""
    low = -0.20
    high = 0.05
    for _ in range(24):
        middle = (low + high) * 0.5
        rig.location = (0.0, y_position, middle)
        bpy.context.view_layer.update()
        angle = 0.5 * (knee_angle(rig, "l") + knee_angle(rig, "r"))
        if angle > KNEE_ANGLE:
            high = middle
        else:
            low = middle
    return (low + high) * 0.5


def elbow_solution(
    shoulder: Vector,
    wrist: Vector,
    upper_length: float,
    lower_length: float,
    side: str,
) -> tuple[Vector, Vector]:
    """Return a softly bent lateral elbow and a stable two-bone IK pole."""
    shoulder_to_wrist = wrist - shoulder
    distance = shoulder_to_wrist.length
    if distance < 1e-6:
        raise RuntimeError(f"{side} RDL shoulder and wrist targets overlap")
    direction = shoulder_to_wrist / distance
    along = (
        upper_length * upper_length
        - lower_length * lower_length
        + distance * distance
    ) / (2.0 * distance)
    height = math.sqrt(max(upper_length * upper_length - along * along, 0.0))
    sign = 1.0 if side == "l" else -1.0
    outward = Vector((sign, 0.0, 0.0))
    normal = outward - direction * outward.dot(direction)
    if normal.length < 1e-6:
        normal = Vector((sign, 0.0, 0.0))
    normal.normalize()
    elbow = shoulder + direction * along + normal * height
    return elbow, elbow + normal * 0.55


def build_dumbbells():
    """Create a matched compact dumbbell pair for the RDL overhand grip."""
    mats = {
        "rubber": material(
            "RDL dumbbell rubber",
            (0.025, 0.032, 0.052, 1.0),
            metallic=0.18,
            roughness=0.34,
        ),
        "handle": material(
            "RDL dumbbell handle",
            (0.46, 0.52, 0.60, 1.0),
            metallic=0.95,
            roughness=0.14,
        ),
        "teal": material(
            "RDL dumbbell teal",
            P.teal,
            roughness=0.2,
            emission=P.teal,
            emission_strength=1.8,
        ),
        "violet": material(
            "RDL dumbbell violet",
            P.violet,
            roughness=0.2,
            emission=P.violet,
            emission_strength=1.8,
        ),
    }

    dumbbells = {}
    for side, accent in (("l", "violet"), ("r", "teal")):
        label = side.upper()
        root = empty(f"{label} RDL dumbbell root", display="PLAIN_AXES")
        root.empty_display_size = 0.14
        handle = cylinder(
            f"{label} RDL dumbbell handle",
            (0.0, 0.0, 0.0),
            DUMBBELL_HANDLE_RADIUS,
            DUMBBELL_HANDLE_LENGTH,
            mats["handle"],
            vertices=32,
        )
        handle.parent = root
        for end_sign in (-1, 1):
            plate = cylinder(
                f"{label} RDL dumbbell plate {end_sign:+d}",
                (0.0, 0.0, 0.0),
                DUMBBELL_PLATE_RADIUS,
                DUMBBELL_PLATE_THICKNESS,
                mats["rubber"],
                vertices=48,
            )
            plate.parent = root
            plate.location = (0.0, 0.0, DUMBBELL_PLATE_CENTER * end_sign)
            cap = cylinder(
                f"{label} RDL dumbbell cap {end_sign:+d}",
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
    """Return the finger-spread, hand-length and palm-normal rest vectors."""
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


def overhand_axes(
    side: str, desired_length: Vector
) -> tuple[Vector, Vector, Vector]:
    """Return mirrored handle, hand-basis and physical-palm directions."""
    sign = 1.0 if side == "l" else -1.0
    desired_length = desired_length.normalized()
    lateral = Vector((sign, 0.0, 0.0))
    handle_axis = lateral - desired_length * lateral.dot(desired_length)
    if handle_axis.length < 1e-6:
        raise RuntimeError("RDL forearm cannot be parallel to the handle axis")
    handle_axis.normalize()
    basis_normal = handle_axis.cross(desired_length).normalized()
    physical_palm_normal = basis_normal * sign
    return handle_axis, basis_normal, physical_palm_normal


def hand_grip_quaternion(rig, side: str, desired_length: Vector) -> Quaternion:
    """Build an overhand basis with the palm toward the thighs."""
    finger_spread_local, hand_length_local, palm_normal_local = hand_rest_basis(
        rig, side
    )
    source_basis = Matrix(
        (finger_spread_local, hand_length_local, palm_normal_local)
    ).transposed()

    desired_length = desired_length.normalized()
    desired_spread, desired_normal, _ = overhand_axes(side, desired_length)
    desired_basis = Matrix(
        (desired_spread, desired_length, desired_normal)
    ).transposed()
    return (desired_basis @ source_basis.transposed()).to_quaternion()


def dumbbell_quaternion(side: str, desired_length: Vector) -> Quaternion:
    """Aim the dumbbell handle left-to-right across the overhand palm."""
    handle_axis, _, _ = overhand_axes(side, desired_length)
    return Vector((0.0, 0.0, 1.0)).rotation_difference(handle_axis)


def configure_grips(rig):
    rotation_targets = {}
    for side in ("l", "r"):
        curl_sign = 1.0 if side == "l" else -1.0
        rotation_target = empty(f"{side.upper()} RDL grip rotation")
        rotation_target.rotation_mode = "QUATERNION"
        copy_world_rotation(rig, f"hand_{side}", rotation_target)
        rotation_targets[side] = rotation_target

        for finger, curls in {
            "index": (90, 30, 18),
            "middle": (84, 38, 40),
            "ring": (80, 40, 34),
            "pinky": (86, 30, 18),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (
                    math.radians(degrees * curl_sign),
                    0.0,
                    0.0,
                )

        for joint, degrees in (
            ("01", (38, 35, 0)),
            ("02", (52, 0, 0)),
            ("03", (36, 0, 0)),
        ):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = tuple(
                math.radians(value) for value in degrees
            )
    return rotation_targets


def mirror_right_thumb_pose(rig) -> None:
    """Mirror the complete approved left-thumb chain onto the right hand."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()
    mirror_x = Matrix.Diagonal((-1.0, 1.0, 1.0, 1.0))
    mirrored_matrices = {
        joint: mirror_x
        @ rig.pose.bones[f"thumb_{joint}_l"].matrix.copy()
        @ mirror_x
        for joint in ("01", "02", "03")
    }
    for joint in ("01", "02", "03"):
        rig.pose.bones[f"thumb_{joint}_r"].matrix = mirrored_matrices[joint]
        bpy.context.view_layer.update()
    scene.frame_set(original_frame)
    bpy.context.view_layer.update()


def mirror_right_fingertip_contacts(rig) -> None:
    """Keep every right fingertip on an all-frame mirror of the left grip."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    targets = {}
    for finger in ("index", "middle", "ring", "pinky"):
        target = empty(f"R RDL {finger} fingertip target")
        targets[finger] = target
        constraint = rig.pose.bones[f"{finger}_03_r"].constraints.new("IK")
        constraint.name = f"Mirror left RDL {finger} contact"
        constraint.target = target
        constraint.chain_count = 3
        constraint.iterations = 48
        constraint.use_stretch = False

    for frame in range(1, FRAME_END + 1):
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        for finger, target in targets.items():
            left_tip = bone_point(rig, f"{finger}_03_l", "tail")
            target.location = Vector((-left_tip.x, left_tip.y, left_tip.z))
            target.keyframe_insert("location", frame=frame)
    scene.frame_set(original_frame)
    bpy.context.view_layer.update()


def animate(rig, standing_foot_rotations, dumbbells):
    """Create the planted, neutral-spine dumbbell RDL repetition."""
    rig.location = (0.0, 0.0, 0.0)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (0.0, 0.0, 0.0)
    rig.scale = (1.0, 1.0, 1.0)
    bpy.context.view_layer.update()

    foot_targets = {}
    knee_poles = {}
    foot_rotation_targets = {}
    for side in ("l", "r"):
        calf = rig.data.bones[f"calf_{side}"]
        foot_targets[side] = empty(
            f"{side.upper()} RDL foot target", calf.tail_local
        )
        knee_poles[side] = empty(
            f"{side.upper()} RDL knee pole",
            calf.head_local + Vector((0.0, -0.65, 0.0)),
        )
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotation = empty(f"{side.upper()} RDL foot rotation")
        foot_rotation.matrix_world = standing_foot_rotations[side]
        foot_rotation.rotation_mode = "QUATERNION"
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)
        foot_rotation_targets[side] = foot_rotation

    torso_controls = {
        bone_name: add_rotation_control(rig, bone_name, label)
        for bone_name, label in (
            ("pelvis", "pelvis"),
            ("spine_01", "lumbar"),
            ("spine_02", "mid spine"),
            ("spine_03", "upper spine"),
            ("neck_01", "neck"),
            ("head", "head"),
        )
    }

    hand_targets = {}
    elbow_poles = {}
    arm_lengths = {}
    for side in ("l", "r"):
        hand_targets[side] = empty(f"{side.upper()} RDL hand target")
        elbow_poles[side] = empty(f"{side.upper()} RDL elbow pole")
        arm_ik = add_ik(
            rig,
            f"lowerarm_{side}",
            hand_targets[side],
            elbow_poles[side],
        )
        arm_ik.use_stretch = True
        rig.pose.bones[f"upperarm_{side}"].ik_stretch = ARM_IK_STRETCH
        rig.pose.bones[f"lowerarm_{side}"].ik_stretch = ARM_IK_STRETCH
        arm_lengths[side] = (
            rig.data.bones[f"upperarm_{side}"].length,
            rig.data.bones[f"lowerarm_{side}"].length,
        )
    hand_rotations = configure_grips(rig)

    for frame in range(1, FRAME_END + 1):
        scene = bpy.context.scene
        scene.frame_set(frame)
        depth = depth_at(frame)
        hinge = math.radians(HINGE_DEGREES * depth)
        for control, rest_world in torso_controls.values():
            control.rotation_quaternion = (
                Quaternion((1.0, 0.0, 0.0), hinge) @ rest_world
            )
            control.keyframe_insert("rotation_quaternion", frame=frame)

        root_y = hip_back_at(depth)
        root_z = solve_root_height(rig, root_y)
        rig.location = (0.0, root_y, root_z)
        rig.keyframe_insert("location", frame=frame)
        bpy.context.view_layer.update()

        for side, sign in (("l", 1.0), ("r", -1.0)):
            shoulder = bone_point(rig, f"upperarm_{side}")
            upper_length, lower_length = arm_lengths[side]
            reach = math.sqrt(
                upper_length * upper_length
                + lower_length * lower_length
                + 2.0
                * upper_length
                * lower_length
                * math.cos(math.radians(ELBOW_FLEXION))
            ) * ARM_REACH_SCALE
            arm_direction = Vector(
                (
                    ARM_LATERAL_OFFSET * sign,
                    -ARM_TOP_FORWARD_COMPONENT * (1.0 - depth)
                    + ARM_BOTTOM_BACK_COMPONENT * depth,
                    -1.0,
                )
            ).normalized()
            wrist = shoulder + arm_direction * reach
            elbow, pole = elbow_solution(
                shoulder,
                wrist,
                upper_length * ARM_REACH_SCALE,
                lower_length * ARM_REACH_SCALE,
                side,
            )
            hand_targets[side].location = wrist
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].location = pole
            elbow_poles[side].keyframe_insert("location", frame=frame)
            bpy.context.view_layer.update()

            # Align the palm and handle to the evaluated forearm, not merely
            # the target ray.  This preserves the approved soft elbow while
            # keeping the dumbbell seated in the hand on every frame.
            actual_elbow = bone_point(rig, f"lowerarm_{side}")
            actual_wrist = bone_point(rig, f"hand_{side}")
            forearm_direction = (actual_wrist - actual_elbow).normalized()
            handle_axis, _, palm_normal = overhand_axes(
                side, forearm_direction
            )
            center = (
                wrist
                + forearm_direction * DUMBBELL_FOREARM_OFFSET
                + palm_normal * DUMBBELL_PALM_OFFSET
                + handle_axis * DUMBBELL_HANDLE_AXIS_OFFSET
            )

            hand_rotations[side].rotation_quaternion = hand_grip_quaternion(
                rig, side, forearm_direction
            )
            hand_rotations[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )
            dumbbells[side].rotation_mode = "QUATERNION"
            dumbbells[side].rotation_quaternion = dumbbell_quaternion(
                side, forearm_direction
            )
            dumbbells[side].location = center
            dumbbells[side].keyframe_insert("location", frame=frame)
            dumbbells[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )

    bpy.context.scene.frame_set(TOP_FRAME)
    return {
        "feet": foot_targets,
        "knees": knee_poles,
        "foot_rotations": foot_rotation_targets,
        "torso": torso_controls,
        "hands": hand_targets,
        "elbows": elbow_poles,
        "hand_rotations": hand_rotations,
        "arm_lengths": arm_lengths,
        "dumbbells": dumbbells,
    }


def _spine_shape(rig) -> tuple[float, ...]:
    axes = []
    for bone_name in ("pelvis", "spine_01", "spine_02", "spine_03", "neck_01", "head"):
        axes.append(
            (bone_point(rig, bone_name, "tail") - bone_point(rig, bone_name)).normalized()
        )
    return tuple(
        math.degrees(lower.angle(upper))
        for lower, upper in zip(axes, axes[1:])
    )


def _frame_snapshot(rig, controls) -> dict[str, object]:
    points = {}
    for bone_name in (
        "pelvis",
        "spine_01",
        "spine_03",
        "head",
        "thigh_l",
        "thigh_r",
        "calf_l",
        "calf_r",
        "foot_l",
        "foot_r",
        "upperarm_l",
        "upperarm_r",
        "lowerarm_l",
        "lowerarm_r",
        "hand_l",
        "hand_r",
    ):
        points[f"{bone_name}.head"] = bone_point(rig, bone_name)
        points[f"{bone_name}.tail"] = bone_point(rig, bone_name, "tail")
    for side in ("l", "r"):
        for digit in GRIP_DIGITS:
            bone_name = f"{digit}_03_{side}"
            points[f"{bone_name}.head"] = bone_point(rig, bone_name)
            points[f"{bone_name}.tail"] = bone_point(
                rig, bone_name, "tail"
            )
    for group in ("feet", "knees", "hands", "elbows"):
        for side, control in controls[group].items():
            points[f"{group}.{side}"] = control.matrix_world.translation.copy()
    for side, dumbbell in controls["dumbbells"].items():
        points[f"dumbbell.{side}"] = dumbbell.matrix_world.translation.copy()
    rotations = {
        f"foot.{side}": control.matrix_world.to_quaternion()
        for side, control in controls["foot_rotations"].items()
    }
    rotations.update(
        {
            f"hand.{side}": control.matrix_world.to_quaternion()
            for side, control in controls["hand_rotations"].items()
        }
    )
    rotations.update(
        {
            f"dumbbell.{side}": dumbbell.matrix_world.to_quaternion()
            for side, dumbbell in controls["dumbbells"].items()
        }
    )
    return {"points": points, "rotations": rotations}


def validate_motion(rig, controls) -> None:
    """Validate every frame of the planted hip hinge and its loop seam."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    errors = []
    seam_samples = {}
    knee_positions = {"l": [], "r": []}
    knee_angles = {"l": [], "r": []}
    dumbbell_positions = {"l": [], "r": []}
    root_positions = []
    torso_floor_angles = []
    max_foot_target_drift = 0.0
    max_foot_bone_drift = 0.0
    max_foot_rotation_drift = 0.0
    max_knee_error = 0.0
    max_knee_symmetry = 0.0
    max_knee_travel = 0.0
    max_knee_acceleration = 0.0
    max_knee_angle_delta = 0.0
    max_knee_angle_acceleration = 0.0
    max_bottom_shank_from_vertical = 0.0
    max_hinge_error = 0.0
    max_spine_shape_drift = 0.0
    max_wrist_error = 0.0
    min_elbow_angle = float("inf")
    max_elbow_angle = 0.0
    max_arm_stretch = 0.0
    max_dumbbell_symmetry = 0.0
    max_dumbbell_offset_error = 0.0
    max_dumbbell_travel = 0.0
    max_dumbbell_acceleration = 0.0
    max_grip_basis_error = 0.0
    min_palm_posterior_dot = 1.0
    min_handle_lateral_dot = 1.0
    min_dumbbell_pair_gap = float("inf")
    max_grip_tip_axial_offset = 0.0
    min_grip_tip_radial_distance = float("inf")
    max_grip_tip_radial_distance = 0.0
    max_thumb_finger_opposition_dot = -1.0
    max_grip_tip_mirror_error = 0.0
    min_dumbbell_clearance = float("inf")
    bottom_handle_shin_fractions = []

    try:
        scene.frame_set(TOP_FRAME)
        bpy.context.view_layer.update()
        foot_target_bases = {
            side: target.matrix_world.translation.copy()
            for side, target in controls["feet"].items()
        }
        foot_bone_bases = {
            side: bone_point(rig, f"foot_{side}") for side in ("l", "r")
        }
        foot_rotation_bases = {
            side: target.matrix_world.to_quaternion()
            for side, target in controls["foot_rotations"].items()
        }
        spine_shape_base = _spine_shape(rig)

        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            depth = depth_at(frame)
            expected_hinge = HINGE_DEGREES * depth
            expected_root_y = hip_back_at(depth)
            root = rig.matrix_world.translation.copy()
            root_positions.append(root)
            if abs(root.y - expected_root_y) > 0.0005:
                errors.append(
                    f"frame {frame}: root left sine hip-back path "
                    f"({root.y:.4f} vs {expected_root_y:.4f})"
                )

            hip_mid = 0.5 * (
                bone_point(rig, "thigh_l") + bone_point(rig, "thigh_r")
            )
            shoulder_mid = 0.5 * (
                bone_point(rig, "upperarm_l")
                + bone_point(rig, "upperarm_r")
            )
            torso = shoulder_mid - hip_mid
            torso_floor = math.degrees(math.atan2(torso.z, abs(torso.y)))
            torso_floor_angles.append(torso_floor)
            actual_hinge = 90.0 - torso_floor
            hinge_error = abs(actual_hinge - expected_hinge)
            max_hinge_error = max(max_hinge_error, hinge_error)
            if hinge_error > MAX_HINGE_ANGLE_ERROR:
                errors.append(
                    f"frame {frame}: trunk hinge error={hinge_error:.2f}deg"
                )

            trunk_points = (
                bone_point(rig, "pelvis"),
                bone_point(rig, "spine_01"),
                bone_point(rig, "spine_02"),
                bone_point(rig, "spine_03"),
                bone_point(rig, "neck_01"),
                bone_point(rig, "head"),
            )
            lateral_offset = max(abs(point.x) for point in trunk_points)
            if lateral_offset > MAX_TRUNK_LATERAL_OFFSET:
                errors.append(
                    f"frame {frame}: trunk lateral offset={lateral_offset:.4f}m"
                )
            spine_shape = _spine_shape(rig)
            shape_drift = max(
                abs(current - baseline)
                for current, baseline in zip(spine_shape, spine_shape_base)
            )
            max_spine_shape_drift = max(max_spine_shape_drift, shape_drift)
            if shape_drift > MAX_SPINE_SHAPE_DRIFT:
                errors.append(
                    f"frame {frame}: neutral-spine shape drift={shape_drift:.2f}deg"
                )

            shoe_vertices = evaluated_vertices("Human.shoes05")
            platform_top = 0.0
            frame_grip_tips = {}
            for side, sign in (("l", 1.0), ("r", -1.0)):
                target = controls["feet"][side].matrix_world.translation
                target_drift = (target - foot_target_bases[side]).length
                max_foot_target_drift = max(max_foot_target_drift, target_drift)
                if target_drift > MAX_FOOT_TARGET_DRIFT:
                    errors.append(
                        f"frame {frame}: {side} foot target drift={target_drift:.5f}m"
                    )
                foot = bone_point(rig, f"foot_{side}")
                foot_drift = (foot - foot_bone_bases[side]).length
                max_foot_bone_drift = max(max_foot_bone_drift, foot_drift)
                if foot_drift > MAX_FOOT_BONE_DRIFT:
                    errors.append(
                        f"frame {frame}: {side} planted foot drift={foot_drift:.5f}m"
                    )
                rotation = controls["foot_rotations"][side].matrix_world.to_quaternion()
                rotation_drift = quaternion_delta_degrees(
                    foot_rotation_bases[side], rotation
                )
                max_foot_rotation_drift = max(
                    max_foot_rotation_drift, rotation_drift
                )
                if rotation_drift > MAX_FOOT_ROTATION_DRIFT:
                    errors.append(
                        f"frame {frame}: {side} foot rotation drift="
                        f"{rotation_drift:.3f}deg"
                    )

                side_vertices = [
                    point for point in shoe_vertices if point.x * sign > 0.0
                ]
                sole_depth = platform_top - min(point.z for point in side_vertices)
                if not MIN_SOLE_DEPTH <= sole_depth <= MAX_SOLE_DEPTH:
                    errors.append(
                        f"frame {frame}: {side} sole contact depth="
                        f"{sole_depth:.4f}m"
                    )

                hip = bone_point(rig, f"thigh_{side}")
                knee = bone_point(rig, f"calf_{side}")
                ankle = bone_point(rig, f"calf_{side}", "tail")
                angle = joint_angle(hip, knee, ankle)
                angle_error = abs(angle - KNEE_ANGLE)
                max_knee_error = max(max_knee_error, angle_error)
                if angle_error > MAX_KNEE_ANGLE_ERROR:
                    errors.append(
                        f"frame {frame}: {side} knee={angle:.2f}deg, "
                        f"expected {KNEE_ANGLE:.1f}deg"
                    )
                outside_x = max(
                    min(hip.x, ankle.x) - knee.x,
                    knee.x - max(hip.x, ankle.x),
                    0.0,
                )
                if outside_x > 0.030:
                    errors.append(
                        f"frame {frame}: {side} knee left sagittal corridor "
                        f"by {outside_x:.4f}m"
                    )
                shank_from_vertical = math.degrees(
                    math.atan2(abs(knee.y - ankle.y), abs(knee.z - ankle.z))
                )
                max_bottom_shank_from_vertical = max(
                    max_bottom_shank_from_vertical,
                    shank_from_vertical,
                )
                if shank_from_vertical > MAX_BOTTOM_SHANK_FROM_VERTICAL:
                    errors.append(
                        f"frame {frame}: {side} shank is "
                        f"{shank_from_vertical:.2f}deg from vertical"
                    )
                if depth > 0.99:
                    knee_flexion = 180.0 - angle
                    if not (
                        MIN_BOTTOM_KNEE_FLEXION
                        <= knee_flexion
                        <= MAX_BOTTOM_KNEE_FLEXION
                    ):
                        errors.append(
                            f"frame {frame}: {side} bottom knee flexion="
                            f"{knee_flexion:.2f}deg"
                        )

                position_history = knee_positions[side]
                position_history.append(knee.copy())
                if len(position_history) >= 2:
                    travel = (position_history[-1] - position_history[-2]).length
                    max_knee_travel = max(max_knee_travel, travel)
                    if travel > MAX_KNEE_FRAME_TRAVEL:
                        errors.append(
                            f"frame {frame}: {side} knee IK travel="
                            f"{travel:.4f}m/frame"
                        )
                if len(position_history) >= 3:
                    acceleration = (
                        position_history[-1]
                        - 2.0 * position_history[-2]
                        + position_history[-3]
                    ).length
                    max_knee_acceleration = max(
                        max_knee_acceleration, acceleration
                    )
                    if acceleration > MAX_KNEE_FRAME_ACCELERATION:
                        errors.append(
                            f"frame {frame}: {side} knee trajectory acceleration="
                            f"{acceleration:.4f}m/frame^2"
                        )

                angle_history = knee_angles[side]
                angle_history.append(angle)
                if len(angle_history) >= 2:
                    delta = abs(angle_history[-1] - angle_history[-2])
                    max_knee_angle_delta = max(max_knee_angle_delta, delta)
                    if delta > MAX_KNEE_ANGLE_DELTA:
                        errors.append(
                            f"frame {frame}: {side} knee angle delta="
                            f"{delta:.2f}deg/frame"
                        )
                if len(angle_history) >= 3:
                    acceleration = abs(
                        angle_history[-1]
                        - 2.0 * angle_history[-2]
                        + angle_history[-3]
                    )
                    max_knee_angle_acceleration = max(
                        max_knee_angle_acceleration, acceleration
                    )
                    if acceleration > MAX_KNEE_ANGLE_ACCELERATION:
                        errors.append(
                            f"frame {frame}: {side} knee angular acceleration="
                            f"{acceleration:.2f}deg/frame^2"
                        )

                shoulder = bone_point(rig, f"upperarm_{side}")
                elbow = bone_point(rig, f"lowerarm_{side}")
                wrist = bone_point(rig, f"hand_{side}")
                target_wrist = controls["hands"][side].matrix_world.translation
                wrist_error = (wrist - target_wrist).length
                max_wrist_error = max(max_wrist_error, wrist_error)
                if wrist_error > MAX_WRIST_TARGET_ERROR:
                    errors.append(
                        f"frame {frame}: {side} wrist target error="
                        f"{wrist_error:.4f}m"
                    )
                elbow_angle = joint_angle(shoulder, elbow, wrist)
                min_elbow_angle = min(min_elbow_angle, elbow_angle)
                max_elbow_angle = max(max_elbow_angle, elbow_angle)
                if not MIN_ELBOW_ANGLE <= elbow_angle <= MAX_ELBOW_ANGLE:
                    errors.append(
                        f"frame {frame}: {side} elbow={elbow_angle:.2f}deg, "
                        f"expected {MIN_ELBOW_ANGLE:.0f}-"
                        f"{MAX_ELBOW_ANGLE:.0f}deg"
                    )
                for bone_name, child_name in (
                    (f"upperarm_{side}", f"lowerarm_{side}"),
                    (f"lowerarm_{side}", f"hand_{side}"),
                ):
                    stretch = (
                        bone_point(rig, child_name) - bone_point(rig, bone_name)
                    ).length / rig.data.bones[bone_name].length
                    max_arm_stretch = max(max_arm_stretch, stretch)
                    if stretch > MAX_ARM_STRETCH:
                        errors.append(
                            f"frame {frame}: {bone_name} stretch={stretch:.3f}"
                        )

                forearm_direction = (wrist - elbow).normalized()
                handle_axis, basis_normal, palm_normal = overhand_axes(
                    side, forearm_direction
                )
                expected_center = (
                    target_wrist
                    + forearm_direction * DUMBBELL_FOREARM_OFFSET
                    + palm_normal * DUMBBELL_PALM_OFFSET
                    + handle_axis * DUMBBELL_HANDLE_AXIS_OFFSET
                )
                center = controls["dumbbells"][side].matrix_world.translation
                offset_error = (center - expected_center).length
                max_dumbbell_offset_error = max(
                    max_dumbbell_offset_error, offset_error
                )
                if offset_error > MAX_DUMBBELL_OFFSET_ERROR:
                    errors.append(
                        f"frame {frame}: {side} dumbbell/grip offset error="
                        f"{offset_error:.4f}m"
                    )
                _, _, palm_normal_local = hand_rest_basis(rig, side)
                hand_world_rotation = (
                    rig.matrix_world @ rig.pose.bones[f"hand_{side}"].matrix
                ).to_quaternion()
                actual_basis_normal = (
                    hand_world_rotation @ palm_normal_local
                ).normalized()
                actual_palm_normal = actual_basis_normal * sign
                grip_basis_error = math.degrees(
                    actual_basis_normal.angle(basis_normal)
                )
                max_grip_basis_error = max(
                    max_grip_basis_error, grip_basis_error
                )
                if grip_basis_error > MAX_GRIP_BASIS_ERROR:
                    errors.append(
                        f"frame {frame}: {side} overhand palm basis error="
                        f"{grip_basis_error:.2f}deg"
                    )
                palm_posterior_dot = actual_palm_normal.dot(
                    Vector((0.0, 1.0, 0.0))
                )
                min_palm_posterior_dot = min(
                    min_palm_posterior_dot, palm_posterior_dot
                )
                if palm_posterior_dot < MIN_PALM_POSTERIOR_DOT:
                    errors.append(
                        f"frame {frame}: {side} palm is not facing the thighs "
                        f"(posterior dot={palm_posterior_dot:.3f})"
                    )
                actual_axis = (
                    controls["dumbbells"][side].matrix_world.to_quaternion()
                    @ Vector((0.0, 0.0, 1.0))
                ).normalized()
                axis_error = math.degrees(actual_axis.angle(handle_axis))
                axis_error = min(axis_error, abs(180.0 - axis_error))
                if axis_error > 1.0:
                    errors.append(
                        f"frame {frame}: {side} dumbbell handle axis error="
                        f"{axis_error:.2f}deg"
                    )
                handle_lateral_dot = abs(actual_axis.x)
                min_handle_lateral_dot = min(
                    min_handle_lateral_dot, handle_lateral_dot
                )
                if handle_lateral_dot < MIN_HANDLE_LATERAL_DOT:
                    errors.append(
                        f"frame {frame}: {side} handle is not left-to-right "
                        f"(lateral dot={handle_lateral_dot:.3f})"
                    )

                frame_grip_tips[side] = {}
                radial_directions = {}
                for digit in GRIP_DIGITS:
                    tip = bone_point(rig, f"{digit}_03_{side}", "tail")
                    frame_grip_tips[side][digit] = tip
                    delta = tip - center
                    axial_offset = delta.dot(actual_axis)
                    radial = delta - actual_axis * axial_offset
                    radial_distance = radial.length
                    max_grip_tip_axial_offset = max(
                        max_grip_tip_axial_offset, abs(axial_offset)
                    )
                    min_grip_tip_radial_distance = min(
                        min_grip_tip_radial_distance, radial_distance
                    )
                    max_grip_tip_radial_distance = max(
                        max_grip_tip_radial_distance, radial_distance
                    )
                    if abs(axial_offset) > MAX_GRIP_TIP_AXIAL_OFFSET:
                        errors.append(
                            f"frame {frame}: {side} {digit} left the handle "
                            f"axially ({axial_offset:.4f}m)"
                        )
                    if not (
                        MIN_GRIP_TIP_RADIAL_DISTANCE
                        <= radial_distance
                        <= MAX_GRIP_TIP_RADIAL_DISTANCE
                    ):
                        errors.append(
                            f"frame {frame}: {side} {digit} handle radius="
                            f"{radial_distance:.4f}m"
                        )
                    radial_directions[digit] = radial.normalized()

                average_finger_direction = sum(
                    (
                        radial_directions[finger]
                        for finger in GRIP_FINGERS
                    ),
                    Vector((0.0, 0.0, 0.0)),
                ).normalized()
                thumb_finger_opposition_dot = average_finger_direction.dot(
                    radial_directions["thumb"]
                )
                max_thumb_finger_opposition_dot = max(
                    max_thumb_finger_opposition_dot,
                    thumb_finger_opposition_dot,
                )
                if (
                    thumb_finger_opposition_dot
                    > MAX_THUMB_FINGER_OPPOSITION_DOT
                ):
                    errors.append(
                        f"frame {frame}: {side} thumb does not oppose the "
                        f"fingers ({thumb_finger_opposition_dot:.3f})"
                    )
                if depth > 0.99:
                    shin_height = knee.z - ankle.z
                    if shin_height <= 0.0:
                        errors.append(
                            f"frame {frame}: {side} shank has invalid height="
                            f"{shin_height:.4f}m"
                        )
                    else:
                        shin_fraction = (center.z - ankle.z) / shin_height
                        bottom_handle_shin_fractions.append(shin_fraction)
                        if not (
                            MIN_BOTTOM_HANDLE_SHIN_FRACTION
                            <= shin_fraction
                            <= MAX_BOTTOM_HANDLE_SHIN_FRACTION
                        ):
                            errors.append(
                                f"frame {frame}: {side} handle shin fraction="
                                f"{shin_fraction:.3f}"
                            )

                history = dumbbell_positions[side]
                history.append(center.copy())
                if len(history) >= 2:
                    travel = (history[-1] - history[-2]).length
                    max_dumbbell_travel = max(max_dumbbell_travel, travel)
                    if travel > MAX_DUMBBELL_FRAME_TRAVEL:
                        errors.append(
                            f"frame {frame}: {side} dumbbell travel="
                            f"{travel:.4f}m/frame"
                        )
                if len(history) >= 3:
                    acceleration = (
                        history[-1] - 2.0 * history[-2] + history[-3]
                    ).length
                    max_dumbbell_acceleration = max(
                        max_dumbbell_acceleration, acceleration
                    )
                    if acceleration > MAX_DUMBBELL_FRAME_ACCELERATION:
                        errors.append(
                            f"frame {frame}: {side} dumbbell acceleration="
                            f"{acceleration:.4f}m/frame^2"
                        )

            for digit in GRIP_DIGITS:
                left_tip = frame_grip_tips["l"][digit]
                mirrored_left_tip = Vector(
                    (-left_tip.x, left_tip.y, left_tip.z)
                )
                mirror_error = (
                    mirrored_left_tip - frame_grip_tips["r"][digit]
                ).length
                max_grip_tip_mirror_error = max(
                    max_grip_tip_mirror_error, mirror_error
                )
                if mirror_error > MAX_GRIP_TIP_MIRROR_ERROR:
                    errors.append(
                        f"frame {frame}: {digit} grip mirror error="
                        f"{mirror_error:.4f}m"
                    )

            left_knee = bone_point(rig, "calf_l")
            right_knee = bone_point(rig, "calf_r")
            knee_symmetry = max(
                abs(left_knee.x + right_knee.x),
                abs(left_knee.y - right_knee.y),
                abs(left_knee.z - right_knee.z),
            )
            max_knee_symmetry = max(max_knee_symmetry, knee_symmetry)
            if knee_symmetry > MAX_KNEE_SYMMETRY_ERROR:
                errors.append(
                    f"frame {frame}: knee symmetry error={knee_symmetry:.4f}m"
                )

            left_center = controls["dumbbells"]["l"].matrix_world.translation
            right_center = controls["dumbbells"]["r"].matrix_world.translation
            dumbbell_symmetry = max(
                abs(left_center.x + right_center.x),
                abs(left_center.y - right_center.y),
                abs(left_center.z - right_center.z),
            )
            max_dumbbell_symmetry = max(
                max_dumbbell_symmetry, dumbbell_symmetry
            )
            if dumbbell_symmetry > MAX_DUMBBELL_SYMMETRY_ERROR:
                errors.append(
                    f"frame {frame}: dumbbell symmetry error="
                    f"{dumbbell_symmetry:.4f}m"
                )

            left_axis = (
                controls["dumbbells"]["l"].matrix_world.to_quaternion()
                @ Vector((0.0, 0.0, 1.0))
            ).normalized()
            right_axis = (
                controls["dumbbells"]["r"].matrix_world.to_quaternion()
                @ Vector((0.0, 0.0, 1.0))
            ).normalized()
            if left_axis.x < 0.0:
                left_axis.negate()
            if right_axis.x < 0.0:
                right_axis.negate()
            axial_half_length = DUMBBELL_CAP_CENTER + 0.5 * DUMBBELL_CAP_THICKNESS
            left_inner_face_x = left_center.x - left_axis.x * axial_half_length
            right_inner_face_x = right_center.x + right_axis.x * axial_half_length
            pair_gap = left_inner_face_x - right_inner_face_x
            min_dumbbell_pair_gap = min(min_dumbbell_pair_gap, pair_gap)
            if pair_gap < MIN_DUMBBELL_PAIR_GAP:
                errors.append(
                    f"frame {frame}: inner dumbbell gap={pair_gap:.4f}m"
                )

            for collider_name in DUMBBELL_PLATE_COLLIDERS:
                clearance = min(
                    point.z - platform_top
                    for point in evaluated_vertices(collider_name)
                )
                min_dumbbell_clearance = min(min_dumbbell_clearance, clearance)
                if clearance < MIN_DUMBBELL_PLATFORM_CLEARANCE:
                    errors.append(
                        f"frame {frame}: {collider_name} platform clearance="
                        f"{clearance:.4f}m"
                    )

            if frame in (TOP_FRAME, FRAME_END):
                seam_samples[frame] = _frame_snapshot(rig, controls)

        left_path = dumbbell_positions["l"]
        vertical_travel = left_path[TOP_FRAME - 1].z - left_path[BOTTOM_FRAME - 1].z
        if vertical_travel < MIN_DUMBBELL_VERTICAL_TRAVEL:
            errors.append(
                f"dumbbell vertical travel={vertical_travel:.3f}m, "
                f"expected at least {MIN_DUMBBELL_VERTICAL_TRAVEL:.3f}m"
            )
        for previous, current in zip(left_path[24:96], left_path[25:97]):
            if current.z > previous.z + 0.001:
                errors.append("dumbbell rose during the descent phase")
                break
        for previous, current in zip(left_path[125:197], left_path[126:198]):
            if current.z < previous.z - 0.001:
                errors.append("dumbbell fell during the ascent phase")
                break

        start = seam_samples[TOP_FRAME]
        end = seam_samples[FRAME_END]
        for name, start_point in start["points"].items():
            loop_error = (start_point - end["points"][name]).length
            if loop_error > MAX_LOOP_POSITION_ERROR:
                errors.append(
                    f"loop position mismatch {name}={loop_error:.6f}m"
                )
        for name, start_rotation in start["rotations"].items():
            loop_error = quaternion_delta_degrees(
                start_rotation, end["rotations"][name]
            )
            if loop_error > MAX_LOOP_ROTATION_ERROR:
                errors.append(
                    f"loop rotation mismatch {name}={loop_error:.4f}deg"
                )
    finally:
        scene.frame_set(original_frame)
        bpy.context.view_layer.update()

    if errors:
        detail = "; ".join(errors[:24])
        if len(errors) > 24:
            detail += f"; ... {len(errors) - 24} more"
        raise RuntimeError(f"RDL motion validation failed: {detail}")

    print(
        "RDL_MOTION_CHECK PASS",
        f"frames=1-{FRAME_END}",
        f"foot_target_drift={max_foot_target_drift:.5f}m",
        f"foot_bone_drift={max_foot_bone_drift:.5f}m",
        f"foot_rotation_drift={max_foot_rotation_drift:.3f}deg",
        f"knee_error={max_knee_error:.3f}deg",
        f"knee_symmetry={max_knee_symmetry:.4f}m",
        f"knee_travel={max_knee_travel:.4f}m/frame",
        f"knee_acceleration={max_knee_acceleration:.4f}m/frame^2",
        f"knee_angle_delta={max_knee_angle_delta:.3f}deg/frame",
        f"knee_angle_acceleration={max_knee_angle_acceleration:.3f}deg/frame^2",
        f"max_shank={max_bottom_shank_from_vertical:.2f}deg",
        f"hinge_error={max_hinge_error:.3f}deg",
        f"spine_shape_drift={max_spine_shape_drift:.3f}deg",
        f"wrist_error={max_wrist_error:.4f}m",
        f"elbow_range={min_elbow_angle:.2f}-{max_elbow_angle:.2f}deg",
        f"arm_stretch={max_arm_stretch:.3f}x",
        f"dumbbell_symmetry={max_dumbbell_symmetry:.4f}m",
        f"dumbbell_offset={max_dumbbell_offset_error:.4f}m",
        f"dumbbell_travel={max_dumbbell_travel:.4f}m/frame",
        f"dumbbell_acceleration={max_dumbbell_acceleration:.4f}m/frame^2",
        f"grip_basis_error={max_grip_basis_error:.3f}deg",
        f"palm_posterior_dot={min_palm_posterior_dot:.3f}",
        f"handle_lateral_dot={min_handle_lateral_dot:.3f}",
        f"dumbbell_pair_gap={min_dumbbell_pair_gap:.4f}m",
        f"grip_tip_axial={max_grip_tip_axial_offset:.4f}m",
        "grip_tip_radial="
        f"{min_grip_tip_radial_distance:.4f}-"
        f"{max_grip_tip_radial_distance:.4f}m",
        f"thumb_opposition={max_thumb_finger_opposition_dot:.3f}",
        f"grip_mirror_error={max_grip_tip_mirror_error:.4f}m",
        f"dumbbell_clearance={min_dumbbell_clearance:.4f}m",
        "bottom_handle_shin_fraction="
        f"{min(bottom_handle_shin_fractions):.3f}-"
        f"{max(bottom_handle_shin_fractions):.3f}",
        "loop=frame-1-equals-frame-241",
    )


def validate_equipment_clearance() -> None:
    """Reject plate/cap penetration while allowing the intentional handle grip."""
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        DUMBBELL_PLATE_COLLIDERS,
        COLLISION_FRAMES,
    )
    assert_no_mesh_intersections(
        DUMBBELL_COMPONENTS["L"],
        DUMBBELL_COMPONENTS["R"],
        COLLISION_FRAMES,
    )


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.15, 0.91), (0.0, 0.05, 0.80), 63),
        ("side", (4.25, -0.62, 0.91), (0.0, 0.06, 0.78), 65),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"RDL {name.title()} camera"
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
        lamp.name = f"RDL {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.05, 0.82)))
    return cameras


def preview_directory(output_dir: str) -> str:
    return os.path.abspath(
        os.path.join(
            os.path.dirname(output_dir),
            "..",
            "..",
            "..",
            "..",
            "design",
            "motion",
            "previews",
        )
    )


def render_previews(cameras, output_dir: str) -> None:
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
                preview_dir,
                f"human_{EXERCISE}_{name}_{suffix}.png",
            )
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)


def render_grip_previews(rig, controls, output_dir: str) -> None:
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    os.makedirs(preview_dir, exist_ok=True)
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()

    hidden = [obj for obj in bpy.data.objects if obj.name.startswith("R RDL")]
    previous_visibility = {obj.name: obj.hide_render for obj in hidden}
    for obj in hidden:
        obj.hide_render = True

    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "RDL grip inspection camera"
    camera.data.lens = 82
    scene.camera = camera
    hand = bone_point(rig, "hand_l")
    for name, offset in (
        ("front", Vector((0.12, -0.72, 0.16))),
        ("angle", Vector((0.35, -0.64, 0.32))),
        ("rear", Vector((0.22, 0.65, -0.20))),
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
        obj.hide_render = previous_visibility[obj.name]
    scene.frame_set(TOP_FRAME)


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


def main() -> None:
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    configure_scene()
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = FRAME_END
    rig, standing_foot_rotations = reset_squat_scene()
    rig.location = (0.0, 0.0, 0.0)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (0.0, 0.0, 0.0)
    configure_athlete_materials()
    configure_rdl_clothing()
    configure_ponytail_gravity()
    bpy.context.view_layer.update()

    dumbbells = build_dumbbells()
    controls = animate(rig, standing_foot_rotations, dumbbells)
    mirror_right_thumb_pose(rig)
    mirror_right_fingertip_contacts(rig)
    validate_motion(rig, controls)
    validate_equipment_clearance()
    cameras = build_cameras_and_lights()
    scene.camera = cameras["front"]

    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    elif args.mode == "grip":
        render_grip_previews(rig, controls, output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
