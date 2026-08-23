"""Build the offline standing dumbbell hammer-curl guide.

Run with Blender after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_hammer_curl.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/hammer_curl_human_sample.blend \
      --mode preview
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
from mathutils import Matrix, Vector


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
)
from motion_collision import assert_no_mesh_intersections  # noqa: E402


EXERCISE = "hammer_curl"
FRAME_END = 241
BOTTOM_FRAME = 1
MID_FRAME = 73
TOP_FRAME = 121

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

DUMBBELL_PLATE_COLLIDERS = (
    "L hammer curl cap +1",
    "L hammer curl cap -1",
    "L hammer curl plate +1",
    "L hammer curl plate -1",
    "R hammer curl cap +1",
    "R hammer curl cap -1",
    "R hammer curl plate +1",
    "R hammer curl plate -1",
)


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render", "thumb-test"),
        default="preview",
    )
    return parser.parse_args(argv)


def curl_height(frame: int) -> float:
    """One controlled eight-second repetition with readable endpoint holds."""
    def gentle_ease(value: float, edge: float = 0.15) -> float:
        # Ramp into a mostly constant angular velocity. A cubic smoothstep
        # peaks at 1.5x the mean speed and made the elbow visibly surge around
        # mid-curl; this C1 trapezoidal profile peaks at only 1/(1-edge).
        velocity = 1.0 / (1.0 - edge)
        if value < edge:
            return velocity * value * value / (2.0 * edge)
        if value > 1.0 - edge:
            remaining = 1.0 - value
            return 1.0 - velocity * remaining * remaining / (2.0 * edge)
        return velocity * (value - edge / 2.0)

    t = (frame - 1) / (FRAME_END - 1)
    if t < 0.15:
        return 0.0
    if t < 0.45:
        return gentle_ease((t - 0.15) / 0.30)
    if t < 0.60:
        return 1.0
    if t < 0.90:
        return 1.0 - gentle_ease((t - 0.60) / 0.30)
    return 0.0


def forearm_direction(side: str, height: float) -> Vector:
    """Return a sagittal curl arc while maintaining a neutral forearm."""
    sign = 1.0 if side == "l" else -1.0
    # Describe the swing as a real elbow arc rather than normalized Cartesian
    # interpolation. This prevents the speed spike that used to occur as the
    # forearm crossed horizontal.
    if height <= 0.5:
        phase = height * 2.0
        sagittal_degrees = 5.9 + (90.0 - 5.9) * phase
        lateral = 0.25 + (0.12 - 0.25) * phase
    else:
        phase = (height - 0.5) * 2.0
        sagittal_degrees = 90.0 + (148.0 - 90.0) * phase
        lateral = 0.12 + (0.07 - 0.12) * phase
    sagittal = math.radians(sagittal_degrees)
    return Vector(
        (
            lateral * sign,
            -math.sin(sagittal),
            -math.cos(sagittal),
        )
    ).normalized()


def build_dumbbells():
    mats = {
        "rubber": material(
            "Hammer curl dumbbell rubber",
            (0.025, 0.032, 0.052, 1.0),
            metallic=0.18,
            roughness=0.34,
        ),
        "handle": material(
            "Hammer curl dumbbell handle",
            (0.46, 0.52, 0.60, 1.0),
            metallic=0.95,
            roughness=0.14,
        ),
        "teal": material(
            "Hammer curl dumbbell teal",
            P.teal,
            roughness=0.2,
            emission=P.teal,
            emission_strength=1.8,
        ),
        "violet": material(
            "Hammer curl dumbbell violet",
            P.violet,
            roughness=0.2,
            emission=P.violet,
            emission_strength=1.8,
        ),
    }

    dumbbells = {}
    for side, accent in (("l", "violet"), ("r", "teal")):
        root = empty(f"{side.upper()} hammer curl dumbbell root", display="PLAIN_AXES")
        root.empty_display_size = 0.14
        # Use a continuous, realistically sized shaft. Its centre is seated
        # against the palm in animate(), while the fingers close around the
        # far half instead of relying on a hidden gap inside the fist.
        handle = cylinder(
            f"{side.upper()} hammer curl handle",
            (0, 0, 0),
            0.016,
            0.20,
            mats["handle"],
            vertices=32,
        )
        handle.parent = root
        for end_sign in (-1, 1):
            plate = cylinder(
                f"{side.upper()} hammer curl plate {end_sign:+d}",
                (0, 0, 0),
                0.080,
                0.050,
                mats["rubber"],
                vertices=48,
            )
            plate.parent = root
            plate.location = (0, 0, 0.105 * end_sign)
            cap = cylinder(
                f"{side.upper()} hammer curl cap {end_sign:+d}",
                (0, 0, 0),
                0.052,
                0.006,
                mats[accent],
                vertices=48,
            )
            cap.parent = root
            cap.location = (0, 0, 0.133 * end_sign)
        dumbbells[side] = root
    return dumbbells


def hand_grip_quaternion(rig, side: str, desired_length: Vector):
    """Build a neutral-grip hand basis with the palm facing inward."""
    hand_bone = rig.data.bones[f"hand_{side}"]
    rest_rotation = hand_bone.matrix_local.to_quaternion()
    finger_spread = (
        rig.data.bones[f"pinky_01_{side}"].head_local
        - rig.data.bones[f"index_01_{side}"].head_local
    )
    finger_spread_local = rest_rotation.inverted() @ finger_spread
    hand_length_local = Vector((0, 1, 0))
    finger_spread_local = (
        finger_spread_local
        - hand_length_local * finger_spread_local.dot(hand_length_local)
    ).normalized()
    palm_normal_local = finger_spread_local.cross(hand_length_local).normalized()
    source_basis = Matrix(
        (finger_spread_local, hand_length_local, palm_normal_local)
    ).transposed()

    sign = 1.0 if side == "l" else -1.0
    desired_length = desired_length.normalized()
    inward_normal = Vector((-sign, 0, 0))
    desired_normal = (
        inward_normal - desired_length * inward_normal.dot(desired_length)
    ).normalized()
    desired_spread = desired_length.cross(desired_normal).normalized()
    desired_basis = Matrix(
        (desired_spread, desired_length, desired_normal)
    ).transposed()
    return (desired_basis @ source_basis.transposed()).to_quaternion()


def dumbbell_quaternion(side: str, desired_length: Vector):
    """Rotate the handle with the hand: horizontal low, vertical near mid-curl."""
    sign = 1.0 if side == "l" else -1.0
    inward_normal = Vector((-sign, 0, 0))
    desired_normal = (
        inward_normal - desired_length * inward_normal.dot(desired_length)
    ).normalized()
    handle_axis = desired_length.cross(desired_normal).normalized()
    return Vector((0, 0, 1)).rotation_difference(handle_axis)


def configure_grips(rig):
    rotation_targets = {}
    for side in ("l", "r"):
        curl_sign = 1.0 if side == "l" else -1.0
        rotation_target = empty(f"{side.upper()} hammer curl grip rotation")
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
                    0,
                    0,
                )

        # Reuse the approved flat dumbbell press thumb arc. Directly posing all
        # three joints keeps the thumb visibly laid over the index/middle
        # fingers; a tip-only IK solve collapses it into this model's palm.
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


def pole_for_elbow(shoulder: Vector, wrist: Vector, elbow: Vector) -> Vector:
    shoulder_to_wrist = wrist - shoulder
    projection = shoulder + shoulder_to_wrist * (
        (elbow - shoulder).dot(shoulder_to_wrist)
        / shoulder_to_wrist.length_squared
    )
    offset = elbow - projection
    if offset.length < 1e-5:
        offset = Vector((0, -1, 0))
    return elbow + offset.normalized() * 0.55


def animate(rig, foot_rotations, dumbbells):
    # Return the reused squat rig to its approved upright standing transform.
    # The torso and pelvis receive no animation, preventing body swing.
    rig.location = (0.0, 0.0, 0.0)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (0.0, 0.0, 0.0)
    # reset_squat_scene leaves the object in the previous supine press
    # transform. Force matrix_world to refresh before converting the rest-pose
    # shoulder coordinates into world-space IK targets.
    bpy.context.view_layer.update()

    foot_targets = {}
    knee_poles = {}
    for side in ("l", "r"):
        calf = rig.data.bones[f"calf_{side}"]
        foot_targets[side] = empty(
            f"{side.upper()} hammer curl foot target",
            calf.tail_local,
        )
        knee_poles[side] = empty(
            f"{side.upper()} hammer curl knee pole",
            rig.data.bones[f"calf_{side}"].head_local + Vector((0, -0.65, 0)),
        )
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotation = empty(f"{side.upper()} hammer curl foot rotation")
        foot_rotation.matrix_world = foot_rotations[side]
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)

    hand_targets = {}
    elbow_poles = {}
    shoulders = {}
    elbows = {}
    forearm_lengths = {}
    for side, sign in (("l", 1.0), ("r", -1.0)):
        upperarm = rig.data.bones[f"upperarm_{side}"]
        shoulder = rig.matrix_world @ upperarm.head_local
        upper_direction = Vector((0.43 * sign, -0.10, -0.897)).normalized()
        shoulders[side] = shoulder
        elbows[side] = shoulder + upper_direction * upperarm.length
        forearm_lengths[side] = rig.data.bones[f"lowerarm_{side}"].length
        hand_targets[side] = empty(f"{side.upper()} hammer curl hand target")
        elbow_poles[side] = empty(f"{side.upper()} hammer curl elbow pole")
        add_ik(
            rig,
            f"lowerarm_{side}",
            hand_targets[side],
            elbow_poles[side],
            pole_angle=-90.0,
        )

    hand_rotations = configure_grips(rig)

    for frame in range(1, FRAME_END + 1):
        height = curl_height(frame)
        for side, sign in (("l", 1.0), ("r", -1.0)):
            direction = forearm_direction(side, height)
            wrist = elbows[side] + direction * forearm_lengths[side]
            inward_normal = Vector((-sign, 0, 0))
            inward_normal = (
                inward_normal - direction * inward_normal.dot(direction)
            ).normalized()
            handle_axis = direction.cross(inward_normal).normalized()
            # Seat the near surface of the 32 mm shaft against the palm rather
            # than centring the cylinder inside it. Centre the grip across all
            # four fingers along the shaft as well; otherwise the fist sits
            # visibly below one plate even when the cross-section is correct.
            center = (
                wrist
                + direction * 0.042
                + inward_normal * 0.024
                + handle_axis * 0.009 * sign
            )

            hand_targets[side].location = wrist
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].location = pole_for_elbow(
                shoulders[side], wrist, elbows[side]
            )
            elbow_poles[side].keyframe_insert("location", frame=frame)

            hand_rotations[side].rotation_quaternion = hand_grip_quaternion(
                rig, side, direction
            )
            hand_rotations[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )

            dumbbells[side].rotation_mode = "QUATERNION"
            dumbbells[side].rotation_quaternion = dumbbell_quaternion(
                side, direction
            )
            dumbbells[side].location = center
            dumbbells[side].keyframe_insert("location", frame=frame)
            dumbbells[side].keyframe_insert(
                "rotation_quaternion", frame=frame
            )

    bpy.context.scene.frame_set(1)


def mirror_right_fingertip_contacts(rig, dumbbells):
    """Pin the right fingertips to an exact mirror of the validated left grip."""
    scene = bpy.context.scene
    scene.frame_set(MID_FRAME)
    bpy.context.view_layer.update()
    right_root = dumbbells["r"]

    for finger in ("index", "middle", "ring", "pinky"):
        left_tip = rig.matrix_world @ rig.pose.bones[f"{finger}_03_l"].tail
        mirrored_tip = Vector((-left_tip.x, left_tip.y, left_tip.z))
        target = empty(f"R hammer curl {finger} fingertip target")
        target.parent = right_root
        target.location = right_root.matrix_world.inverted() @ mirrored_tip

        ik = rig.pose.bones[f"{finger}_03_r"].constraints.new("IK")
        ik.name = f"Mirror left {finger} grip contact"
        ik.target = target
        ik.chain_count = 3
        ik.iterations = 48
        ik.use_stretch = False

    scene.frame_set(1)
    bpy.context.view_layer.update()


def mirror_right_thumb_pose(rig):
    """Mirror the complete left thumb chain instead of approximating one tip."""
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
        right_thumb = rig.pose.bones[f"thumb_{joint}_r"]
        right_thumb.matrix = mirrored_matrices[joint]
        bpy.context.view_layer.update()

    scene.frame_set(original_frame)
    bpy.context.view_layer.update()


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.15, 0.92), (0.0, 0.0, 0.82), 63),
        ("side", (4.05, -1.25, 0.94), (0.0, 0.0, 0.82), 65),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Hammer curl {name.title()} camera"
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
        lamp.name = f"Hammer curl {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.0, 0.85)))
    return cameras


def validate_equipment_clearance():
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        DUMBBELL_PLATE_COLLIDERS,
        (BOTTOM_FRAME, MID_FRAME, TOP_FRAME),
    )


def validate_arm_path(rig):
    scene = bpy.context.scene
    original_frame = scene.frame_current
    checks = {
        BOTTOM_FRAME: ("bottom", (165.0, 179.5)),
        MID_FRAME: ("mid", (85.0, 110.0)),
        TOP_FRAME: ("top", (28.0, 50.0)),
    }
    errors = []
    measurements = []
    try:
        elbow_origins = {}
        for frame, (label, elbow_range) in checks.items():
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            for side in ("l", "r"):
                shoulder = rig.matrix_world @ rig.pose.bones[f"upperarm_{side}"].head
                elbow = rig.matrix_world @ rig.pose.bones[f"lowerarm_{side}"].head
                wrist = rig.matrix_world @ rig.pose.bones[f"hand_{side}"].head
                forearm = (
                    rig.matrix_world.to_3x3()
                    @ rig.pose.bones[f"lowerarm_{side}"].vector
                ).normalized()
                hand = (
                    rig.matrix_world.to_3x3()
                    @ rig.pose.bones[f"hand_{side}"].vector
                ).normalized()
                elbow_degrees = math.degrees((shoulder - elbow).angle(wrist - elbow))
                wrist_degrees = math.degrees(forearm.angle(hand))
                elbow_origins.setdefault(side, elbow.copy())
                elbow_drift = (elbow - elbow_origins[side]).length
                measurements.append(
                    f"{label}/{side}: elbow={elbow_degrees:.1f}, "
                    f"wrist={wrist_degrees:.1f}, drift={elbow_drift:.3f}"
                )
                if not elbow_range[0] <= elbow_degrees <= elbow_range[1]:
                    errors.append(
                        f"{label}/{side} elbow {elbow_degrees:.1f} not in {elbow_range}"
                    )
                if wrist_degrees > 8.0:
                    errors.append(f"{label}/{side} wrist {wrist_degrees:.1f} > 8.0")
                if elbow_drift > 0.025:
                    errors.append(f"{label}/{side} elbow drift {elbow_drift:.3f} > 0.025")

        for side in ("l", "r"):
            elbow_series = []
            wrist_series = []
            elbow_positions = []
            for frame in range(1, FRAME_END):
                scene.frame_set(frame)
                bpy.context.view_layer.update()
                shoulder = rig.matrix_world @ rig.pose.bones[f"upperarm_{side}"].head
                elbow = rig.matrix_world @ rig.pose.bones[f"lowerarm_{side}"].head
                wrist = rig.matrix_world @ rig.pose.bones[f"hand_{side}"].head
                forearm = (
                    rig.matrix_world.to_3x3()
                    @ rig.pose.bones[f"lowerarm_{side}"].vector
                ).normalized()
                hand = (
                    rig.matrix_world.to_3x3()
                    @ rig.pose.bones[f"hand_{side}"].vector
                ).normalized()
                elbow_series.append(
                    math.degrees((shoulder - elbow).angle(wrist - elbow))
                )
                wrist_series.append(math.degrees(forearm.angle(hand)))
                elbow_positions.append(elbow)
            max_delta = max(
                abs(current - previous)
                for previous, current in zip(elbow_series, elbow_series[1:])
            )
            max_wrist = max(wrist_series)
            max_drift = max(
                (position - elbow_positions[0]).length
                for position in elbow_positions
            )
            measurements.append(
                f"timeline/{side}: elbow_delta={max_delta:.1f}, "
                f"max_wrist={max_wrist:.1f}, max_drift={max_drift:.3f}"
            )
            if max_delta > 3.0:
                errors.append(f"timeline/{side} elbow delta {max_delta:.1f} > 3.0")
            if max_wrist > 8.0:
                errors.append(f"timeline/{side} wrist {max_wrist:.1f} > 8.0")
            if max_drift > 0.025:
                errors.append(f"timeline/{side} elbow drift {max_drift:.3f} > 0.025")
    finally:
        scene.frame_set(original_frame)

    if errors:
        print("ARM_PATH_CHECK FAIL", "; ".join(measurements))
        raise RuntimeError("Hammer curl validation failed: " + "; ".join(errors))
    print("ARM_PATH_CHECK PASS", "; ".join(measurements))


def preview_directory(output_dir):
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


def render_previews(cameras, output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    os.makedirs(preview_dir, exist_ok=True)
    for frame, suffix in (
        (BOTTOM_FRAME, "bottom"),
        (MID_FRAME, "mid"),
        (TOP_FRAME, "top"),
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
    render_grip_previews(output_dir)
    render_grip_timeline_previews(output_dir)


def render_grip_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(MID_FRAME)
    hidden = [
        obj for obj in bpy.data.objects if obj.name.startswith("R hammer curl")
    ]
    previous_visibility = {obj.name: obj.hide_render for obj in hidden}
    for obj in hidden:
        obj.hide_render = True
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Hammer curl grip inspection camera"
    camera.data.lens = 80
    scene.camera = camera
    hand = bpy.data.objects["Human.rig"].matrix_world @ bpy.data.objects[
        "Human.rig"
    ].pose.bones["hand_l"].head
    for name, offset in (
        ("front", Vector((0.70, -0.55, 0.15))),
        ("angle", Vector((0.75, -0.25, 0.40))),
        ("rear", Vector((0.68, 0.55, 0.15))),
    ):
        camera.location = hand + offset
        look_at(camera, hand + Vector((0, -0.015, 0.015)))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir,
            f"human_{EXERCISE}_grip_{name}.png",
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)
    for obj in hidden:
        obj.hide_render = previous_visibility[obj.name]

    hidden_left = [
        obj for obj in bpy.data.objects if obj.name.startswith("L hammer curl")
    ]
    previous_left_visibility = {
        obj.name: obj.hide_render for obj in hidden_left
    }
    for obj in hidden_left:
        obj.hide_render = True
    hidden_right = [
        obj for obj in bpy.data.objects if obj.name.startswith("R hammer curl")
    ]
    for obj in hidden_right:
        obj.hide_render = False

    dumbbell = bpy.data.objects["R hammer curl dumbbell root"]
    for frame, suffix in (
        (BOTTOM_FRAME, "bottom"),
        (MID_FRAME, "mid"),
        (TOP_FRAME, "top"),
    ):
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        center = dumbbell.matrix_world.translation.copy()
        camera.location = center + Vector((-0.68, -0.48, 0.30))
        look_at(camera, center)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir,
            f"human_{EXERCISE}_grip_timeline_right_{suffix}.png",
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_TIMELINE_RIGHT_PREVIEW", scene.render.filepath)

    for obj in hidden_left:
        obj.hide_render = previous_left_visibility[obj.name]


def render_grip_timeline_previews(output_dir):
    """Inspect the hand/shaft contact at every key point of the curl."""
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    hidden = [
        obj for obj in bpy.data.objects if obj.name.startswith("R hammer curl")
    ]
    previous_visibility = {obj.name: obj.hide_render for obj in hidden}
    for obj in hidden:
        obj.hide_render = True

    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Hammer curl grip timeline camera"
    camera.data.lens = 82
    scene.camera = camera
    dumbbell = bpy.data.objects["L hammer curl dumbbell root"]
    for frame, suffix in (
        (BOTTOM_FRAME, "bottom"),
        (MID_FRAME, "mid"),
        (TOP_FRAME, "top"),
    ):
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        center = dumbbell.matrix_world.translation.copy()
        camera.location = center + Vector((0.68, -0.48, 0.30))
        look_at(camera, center)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir,
            f"human_{EXERCISE}_grip_timeline_{suffix}.png",
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_TIMELINE_PREVIEW", scene.render.filepath)

    for obj in hidden:
        obj.hide_render = previous_visibility[obj.name]


def render_thumb_tests(output_dir):
    """Render a local-axis grid for choosing a visible neutral-grip thumb arc."""
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(MID_FRAME)
    hidden = [
        obj for obj in bpy.data.objects if obj.name.startswith("L hammer curl")
    ]
    previous_visibility = {obj.name: obj.hide_render for obj in hidden}
    for obj in hidden:
        obj.hide_render = True

    rig = bpy.data.objects["Human.rig"]
    thumb = rig.pose.bones["thumb_01_r"]
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Hammer curl thumb test camera"
    camera.data.lens = 84
    hand = rig.matrix_world @ rig.pose.bones["hand_r"].head
    camera.location = hand + Vector((-0.68, -0.52, 0.18))
    look_at(camera, hand + Vector((0, -0.015, 0.015)))
    scene.camera = camera

    for x_degrees in (25, 45, 65):
        for y_degrees in (-80, -65, -50):
            thumb.rotation_mode = "XYZ"
            thumb.rotation_euler = tuple(
                map(math.radians, (x_degrees, y_degrees, 0))
            )
            bpy.context.view_layer.update()
            scene.render.image_settings.file_format = "PNG"
            scene.render.filepath = os.path.join(
                preview_dir,
                f"human_{EXERCISE}_right_thumb_x{x_degrees:+d}_y{y_degrees:+d}.png",
            )
            bpy.ops.render.render(write_still=True)
            print("THUMB_TEST", scene.render.filepath)

    for obj in hidden:
        obj.hide_render = previous_visibility[obj.name]


def render_movies(cameras, output_dir):
    scene = bpy.context.scene
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
                        ffmpeg,
                        "-y",
                        "-loglevel",
                        "warning",
                        "-framerate",
                        str(scene.render.fps),
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
    rig, foot_rotations = reset_squat_scene()
    configure_athlete_materials()
    dumbbells = build_dumbbells()
    animate(rig, foot_rotations, dumbbells)
    mirror_right_thumb_pose(rig)
    mirror_right_fingertip_contacts(rig, dumbbells)
    validate_arm_path(rig)
    validate_equipment_clearance()
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)
    else:
        render_thumb_tests(output_dir)


if __name__ == "__main__":
    main()
