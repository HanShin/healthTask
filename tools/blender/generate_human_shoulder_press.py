"""Build the offline seated dumbbell shoulder-press guide.

Run with Blender after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_shoulder_press.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/shoulder_press_human_sample.blend \
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
from motion_collision import assert_no_mesh_intersections  # noqa: E402
from generate_squat_sample import (  # noqa: E402
    P,
    configure_scene,
    cylinder,
    look_at,
    material,
    rounded_cube,
    smoothstep,
)


EXERCISE = "shoulder_press"
# Frame 241 duplicates frame 1 and is omitted from the movie, leaving exactly
# 240 frames (8 seconds at 30 fps) for one readable repetition.
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

BENCH_COLLIDERS = (
    "Shoulder press seat",
    "Shoulder press backrest",
    "Shoulder press back frame",
    "Shoulder press adjustment arm",
    "Shoulder press main spine",
    "Shoulder press front leg",
    "Shoulder press front stabilizer",
    "Shoulder press rear stabilizer",
    "Shoulder press hinge axle",
)

DUMBBELL_PLATE_COLLIDERS = (
    "L shoulder press cap +1",
    "L shoulder press cap -1",
    "L shoulder press plate +1",
    "L shoulder press plate -1",
    "R shoulder press cap +1",
    "R shoulder press cap -1",
    "R shoulder press plate +1",
    "R shoulder press plate -1",
)


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument("--mode", choices=("preview", "render"), default="preview")
    return parser.parse_args(argv)


def press_height(frame: int) -> float:
    """One controlled eight-second press with readable endpoint holds."""
    t = (frame - 1) / (FRAME_END - 1)
    if t < 0.15:
        return 0.0
    if t < 0.45:
        return smoothstep((t - 0.15) / 0.30)
    if t < 0.60:
        return 1.0
    if t < 0.90:
        return 1.0 - smoothstep((t - 0.60) / 0.30)
    return 0.0


def rounded_beam_yz(name, start, end, half_width, half_thickness, mat, bevel=0.012):
    """Create a rectangular steel tube between two points in the YZ plane."""
    start = Vector(start)
    end = Vector(end)
    delta = end - start
    length = math.hypot(delta.y, delta.z)
    if length <= 0:
        raise ValueError(f"{name} must have a non-zero YZ length")
    beam = rounded_cube(
        name,
        (start + end) * 0.5,
        (half_width, length * 0.5, half_thickness),
        mat,
        bevel=bevel,
    )
    beam.rotation_euler[0] = math.atan2(delta.z, delta.y)
    return beam


def build_bench_and_dumbbells():
    mats = {
        "pad": material("Shoulder press bench pad", (0.035, 0.045, 0.075, 1.0), roughness=0.48),
        "frame": material("Shoulder press bench steel", P.metal, metallic=0.92, roughness=0.18),
        "hardware": material(
            "Shoulder press bench hardware",
            (0.34, 0.40, 0.47, 1.0),
            metallic=0.96,
            roughness=0.16,
        ),
        "foot": material(
            "Shoulder press bench foot rubber",
            (0.018, 0.022, 0.032, 1.0),
            metallic=0.05,
            roughness=0.68,
        ),
        "rubber": material("Shoulder press dumbbell rubber", (0.025, 0.032, 0.052, 1.0), metallic=0.18, roughness=0.34),
        "handle": material("Shoulder press dumbbell handle", (0.46, 0.52, 0.60, 1.0), metallic=0.95, roughness=0.14),
        "teal": material("Shoulder press dumbbell teal", P.teal, roughness=0.2, emission=P.teal, emission_strength=1.8),
        "violet": material("Shoulder press dumbbell violet", P.violet, roughness=0.2, emission=P.violet, emission_strength=1.8),
    }

    # A commercial adjustable bench has a narrow pad rather than an office-chair
    # sized cushion. The seat still follows the seated thigh line, but its angle
    # is subtle enough to read as upholstery instead of a wedge.
    seat = rounded_cube(
        "Shoulder press seat",
        (0.0, 0.040, 0.355),
        (0.180, 0.170, 0.045),
        mats["pad"],
        bevel=0.028,
    )
    seat.rotation_euler[0] = math.radians(4.0)
    backrest = rounded_cube(
        "Shoulder press backrest",
        (0.0, 0.305, 0.850),
        (0.170, 0.050, 0.425),
        mats["pad"],
        bevel=0.030,
    )
    backrest.rotation_euler[0] = math.radians(-8.0)

    # Real adjustable benches use one long rectangular spine with a narrow
    # front foot and a wide rear stabilizer. This keeps the athlete's feet clear
    # while giving the silhouette a credible load path down to the floor.
    rounded_beam_yz(
        "Shoulder press main spine",
        (0.0, -0.105, 0.275),
        (0.0, 0.505, 0.095),
        0.040,
        0.034,
        mats["frame"],
        bevel=0.016,
    )
    rounded_beam_yz(
        "Shoulder press front leg",
        (0.0, -0.075, 0.285),
        (0.0, -0.145, 0.080),
        0.036,
        0.032,
        mats["frame"],
        bevel=0.014,
    )
    rounded_cube(
        "Shoulder press front stabilizer",
        (0.0, -0.155, 0.045),
        (0.115, 0.045, 0.032),
        mats["frame"],
        bevel=0.016,
    )
    rounded_cube(
        "Shoulder press rear stabilizer",
        (0.0, 0.520, 0.045),
        (0.310, 0.050, 0.034),
        mats["frame"],
        bevel=0.018,
    )

    # The back pad is carried by a separate rail pivoting at the seat gap. A
    # diagonal adjustment arm completes the familiar incline-bench triangle.
    back_frame = rounded_cube(
        "Shoulder press back frame",
        (0.0, 0.387, 0.835),
        (0.040, 0.026, 0.335),
        mats["frame"],
        bevel=0.014,
    )
    back_frame.rotation_euler[0] = math.radians(-8.0)
    rounded_beam_yz(
        "Shoulder press adjustment arm",
        (0.0, 0.388, 0.705),
        (0.0, 0.470, 0.155),
        0.031,
        0.024,
        mats["frame"],
        bevel=0.011,
    )
    hinge = cylinder(
        "Shoulder press hinge axle",
        (0.0, 0.235, 0.418),
        0.048,
        0.390,
        mats["hardware"],
        vertices=48,
    )
    hinge.rotation_euler[1] = math.radians(90)
    for sign in (-1, 1):
        bolt = cylinder(
            f"Shoulder press hinge bolt {sign:+d}",
            (0.203 * sign, 0.235, 0.418),
            0.020,
            0.014,
            mats["hardware"],
            vertices=32,
        )
        bolt.rotation_euler[1] = math.radians(90)

    # Rubber end caps and compact transport wheels are recognizable commercial
    # bench details and stop the floor frame from looking like abstract bars.
    for sign in (-1, 1):
        rounded_cube(
            f"Shoulder press rear foot cap {sign:+d}",
            (0.278 * sign, 0.520, 0.044),
            (0.037, 0.054, 0.035),
            mats["foot"],
            bevel=0.014,
        )
        wheel = cylinder(
            f"Shoulder press transport wheel {sign:+d}",
            (0.235 * sign, 0.555, 0.092),
            0.050,
            0.028,
            mats["foot"],
            vertices=40,
        )
        wheel.rotation_euler[1] = math.radians(90)
    for sign in (-1, 1):
        rounded_cube(
            f"Shoulder press front foot cap {sign:+d}",
            (0.093 * sign, -0.155, 0.044),
            (0.026, 0.048, 0.033),
            mats["foot"],
            bevel=0.012,
        )

    dumbbells = {}
    for side, accent in (("l", "violet"), ("r", "teal")):
        root = empty(f"{side.upper()} shoulder press dumbbell root", display="PLAIN_AXES")
        root.empty_display_size = 0.14
        handle = cylinder(
            f"{side.upper()} shoulder press handle",
            (0, 0, 0),
            0.018,
            0.20,
            mats["handle"],
            vertices=32,
        )
        # Standard pronated shoulder-press grip: the shaft runs left-to-right
        # while both palms face forward. The previous front-to-back shaft was
        # a neutral-grip variation and did not match the primary reference.
        handle.rotation_euler[1] = math.radians(90)
        handle.parent = root
        for end_sign in (-1, 1):
            plate = cylinder(
                f"{side.upper()} shoulder press plate {end_sign:+d}",
                (0, 0, 0),
                0.086,
                0.050,
                mats["rubber"],
                vertices=48,
            )
            plate.rotation_euler[1] = math.radians(90)
            plate.parent = root
            plate.location = (0.105 * end_sign, 0, 0)
            cap = cylinder(
                f"{side.upper()} shoulder press cap {end_sign:+d}",
                (0, 0, 0),
                0.055,
                0.006,
                mats[accent],
                vertices=48,
            )
            cap.rotation_euler[1] = math.radians(90)
            cap.parent = root
            cap.location = (0.133 * end_sign, 0, 0)
        dumbbells[side] = root
    return dumbbells


def hand_grip_quaternion(rig, side: str, desired_length: Vector):
    """Build a pronated hand rotation with an orthonormal palm basis."""
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

    sign = 1 if side == "l" else -1
    desired_length = desired_length.normalized()
    handle_axis = Vector((sign, 0, 0))
    desired_spread = (
        handle_axis - desired_length * handle_axis.dot(desired_length)
    ).normalized()
    desired_normal = desired_spread.cross(desired_length).normalized()
    desired_basis = Matrix(
        (desired_spread, desired_length, desired_normal)
    ).transposed()
    return (desired_basis @ source_basis.transposed()).to_quaternion()


def curl_grips(rig):
    """Create neutral-wrist pronated grips with thumbs locking the handles."""
    rotation_targets = {}
    thumb_targets = {}
    for side, sign in (("l", 1), ("r", -1)):
        rotation_target = empty(f"{side.upper()} shoulder press grip rotation")
        rotation_target.rotation_mode = "QUATERNION"
        rotation_target.rotation_quaternion = hand_grip_quaternion(
            rig,
            side,
            Vector((0.07 * sign, 0.03, 1.0)),
        )
        copy_world_rotation(rig, f"hand_{side}", rotation_target)
        rotation_targets[side] = rotation_target

        for finger, curls in {
            "index": (92, 34, 22),
            "middle": (86, 42, 44),
            "ring": (82, 44, 38),
            "pinky": (88, 34, 22),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (math.radians(degrees), 0, 0)

        # Solve the complete thumb chain to the far side of the handle. Euler
        # curling put the thumb beside the fingertips and made the hand read as
        # a closed fist instead of a secure dumbbell grip.
        for joint in ("01", "02", "03"):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "QUATERNION"
            pose_bone.rotation_quaternion = (1.0, 0.0, 0.0, 0.0)
        thumb_target = empty(f"{side.upper()} shoulder press thumb tip target")
        thumb_ik = rig.pose.bones[f"thumb_03_{side}"].constraints.new("IK")
        thumb_ik.name = "Natural dumbbell thumb lock"
        thumb_ik.target = thumb_target
        thumb_ik.chain_count = 3
        thumb_ik.iterations = 32
        thumb_ik.use_stretch = False
        thumb_targets[side] = thumb_target
    return rotation_targets, thumb_targets


def animate(rig, foot_rotations, dumbbells):
    # Recline the complete rig with the backrest, then use leg IK to keep both
    # soles planted. The pelvis remains still, preventing leg drive or lumbar
    # arching while the dumbbells move.
    rig.location = (0.0, -0.030, -0.255)
    rig.rotation_euler = (math.radians(-8.0), 0.0, 0.0)

    foot_targets = {
        "l": empty("L shoulder press foot target", (0.225, -0.30, 0.062)),
        "r": empty("R shoulder press foot target", (-0.225, -0.30, 0.062)),
    }
    knee_poles = {
        "l": empty("L shoulder press knee pole", (0.225, -0.27, 0.48)),
        "r": empty("R shoulder press knee pole", (-0.225, -0.27, 0.48)),
    }
    for side in ("l", "r"):
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotation = empty(f"{side.upper()} shoulder press foot rotation")
        foot_rotation.matrix_world = foot_rotations[side]
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)

    hand_targets = {
        "l": empty("L shoulder press hand target"),
        "r": empty("R shoulder press hand target"),
    }
    elbow_poles = {
        "l": empty("L shoulder press elbow pole"),
        "r": empty("R shoulder press elbow pole"),
    }
    for side in ("l", "r"):
        add_ik(rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side])
    hand_rotations, thumb_targets = curl_grips(rig)

    for frame in range(1, FRAME_END + 1):
        height = press_height(frame)
        # ACE's 4/8-o'clock setup keeps each wrist stacked over its elbow at the
        # bottom. The old 0.370 m start spread forced both forearms to flare
        # outward before snapping back into a goal-post pose. Hold a nearly
        # constant shoulder-width track through the first half, then converge
        # smoothly above the head.
        center_x = 0.305 + 0.065 * height - 0.130 * height * height
        center_y = 0.100 * height
        # Start with the elbow near 90 degrees rather than the previous deeply
        # folded 63-degree position. The progressive rise then opens the elbow
        # smoothly to roughly 165 degrees without hyperextending at lockout.
        center_z = 1.173 + 0.111 * height + 0.086 * height * height
        for side, sign in (("l", 1), ("r", -1)):
            center = Vector((center_x * sign, center_y, center_z))
            dumbbells[side].location = center
            dumbbells[side].keyframe_insert("location", frame=frame)

            # The handle rests just above the wrist crease, inside the heel of
            # the palm. Fingers then travel over and around the shaft while the
            # hand bone remains a continuation of the forearm.
            hand_targets[side].location = center + Vector((0, -0.012, -0.048))
            hand_targets[side].keyframe_insert("location", frame=frame)

            # The thumb closes from the opposite side of the shaft and meets
            # the index/middle fingers without collapsing into the palm.
            thumb_targets[side].location = center + Vector(
                (0.020 * sign, 0.022, -0.004)
            )
            thumb_targets[side].keyframe_insert("location", frame=frame)

            # At shoulder level the elbows stay slightly forward of the trunk,
            # never directly out at 3 and 9 o'clock. As the arms extend, the
            # poles rise inward to keep elbow and wrist in the same plane.
            elbow_poles[side].location = Vector(
                (
                    (0.350 - 0.130 * height) * sign,
                    -0.050 + 0.140 * height,
                    0.865 + 0.225 * height,
                )
            )
            elbow_poles[side].keyframe_insert("location", frame=frame)

        # Match wrist flexion/extension to the evaluated forearm at every frame.
        # Retain only part of its side-to-side angle so the wrist relaxes while
        # the knuckle line remains close to the left-to-right handle axis.
        bpy.context.scene.frame_set(frame)
        bpy.context.view_layer.update()
        for side in ("l", "r"):
            forearm = (
                rig.matrix_world.to_3x3()
                @ rig.pose.bones[f"lowerarm_{side}"].vector
            ).normalized()
            desired_hand = Vector((0.55 * forearm.x, forearm.y, forearm.z))
            hand_rotations[side].rotation_quaternion = hand_grip_quaternion(
                rig,
                side,
                desired_hand,
            )
            hand_rotations[side].keyframe_insert("rotation_quaternion", frame=frame)

    bpy.context.scene.frame_set(1)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -3.75, 0.98), (0.0, 0.0, 0.76), 61),
        # A shallow front three-quarter angle preserves the side view of the
        # spine/backrest while separating the two dumbbells and exposing the
        # face. A mathematically pure side view stacks both weights into one.
        ("side", (3.65, -1.25, 1.00), (0.0, 0.0, 0.76), 63),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Shoulder press {name.title()} camera"
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
        lamp.name = f"Shoulder press {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.0, 0.82)))
    return cameras


def validate_equipment_clearance():
    """Reject any frame where the athlete penetrates the bench."""
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        BENCH_COLLIDERS,
        (BOTTOM_FRAME, MID_FRAME, TOP_FRAME),
    )
    # The handle is intentionally enclosed by the palm, but the plates and end
    # caps must remain clear of fingers, forearms, face, hair, and clothing.
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        DUMBBELL_PLATE_COLLIDERS,
        (BOTTOM_FRAME, MID_FRAME, TOP_FRAME),
    )


def validate_arm_path(rig):
    """Keep the press inside the approved elbow, forearm, and wrist ranges."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    checks = {
        BOTTOM_FRAME: ("bottom", (80.0, 100.0), 10.0),
        MID_FRAME: ("mid", (95.0, 120.0), 15.0),
        TOP_FRAME: ("top", (155.0, 175.0), 10.0),
    }
    errors = []
    measurements = []
    try:
        for frame, (label, elbow_range, vertical_limit) in checks.items():
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
                vertical_degrees = math.degrees(forearm.angle(Vector((0, 0, 1))))
                wrist_degrees = math.degrees(forearm.angle(hand))
                measurements.append(
                    f"{label}/{side}: elbow={elbow_degrees:.1f}, "
                    f"forearm={vertical_degrees:.1f}, wrist={wrist_degrees:.1f}"
                )
                if not elbow_range[0] <= elbow_degrees <= elbow_range[1]:
                    errors.append(
                        f"{label}/{side} elbow {elbow_degrees:.1f} not in {elbow_range}"
                    )
                if vertical_degrees > vertical_limit:
                    errors.append(
                        f"{label}/{side} forearm {vertical_degrees:.1f} > {vertical_limit:.1f}"
                    )
                if wrist_degrees > 8.0:
                    errors.append(f"{label}/{side} wrist {wrist_degrees:.1f} > 8.0")
                if frame == BOTTOM_FRAME and shoulder.y - elbow.y < 0.08:
                    errors.append(f"{label}/{side} elbow is not far enough forward")

        # Endpoint checks cannot detect a one-frame IK flip between poses. Scan
        # the encoded timeline and reject any discontinuity before rendering.
        for side in ("l", "r"):
            elbow_series = []
            wrist_series = []
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
            max_delta = max(
                abs(current - previous)
                for previous, current in zip(elbow_series, elbow_series[1:])
            )
            max_wrist = max(wrist_series)
            measurements.append(
                f"timeline/{side}: elbow_delta={max_delta:.1f}, "
                f"max_wrist={max_wrist:.1f}"
            )
            if max_delta > 3.0:
                errors.append(f"timeline/{side} elbow delta {max_delta:.1f} > 3.0")
            if max_wrist > 8.0:
                errors.append(f"timeline/{side} wrist {max_wrist:.1f} > 8.0")
    finally:
        scene.frame_set(original_frame)

    if errors:
        raise RuntimeError("Arm path validation failed: " + "; ".join(errors))
    print("ARM_PATH_CHECK PASS", "; ".join(measurements))


def preview_directory(output_dir):
    return os.path.abspath(
        os.path.join(os.path.dirname(output_dir), "..", "..", "..", "..", "design", "motion", "previews")
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
            scene.render.filepath = os.path.join(preview_dir, f"human_{EXERCISE}_{name}_{suffix}.png")
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)
    render_grip_previews(output_dir)


def render_grip_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(1)
    # Isolate the inspected left grip. With both dumbbells visible, the right
    # dumbbell lines up behind it from the side and hides the thumb/fingertips.
    hidden_for_inspection = [
        obj for obj in bpy.data.objects if obj.name.startswith("R shoulder press")
    ]
    previous_visibility = {obj.name: obj.hide_render for obj in hidden_for_inspection}
    for obj in hidden_for_inspection:
        obj.hide_render = True
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Shoulder press grip inspection camera"
    camera.data.lens = 80
    scene.camera = camera
    target = Vector((0.37, 0.0, 1.035))
    for name, location in (
        ("front", (1.05, -0.72, 1.105)),
        ("angle", (1.25, -0.35, 1.265)),
        ("rear", (1.05, 0.72, 1.105)),
    ):
        camera.location = location
        look_at(camera, target)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(preview_dir, f"human_{EXERCISE}_grip_{name}.png")
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)
    for obj in hidden_for_inspection:
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
                raise RuntimeError("Blender has no FFmpeg output and system ffmpeg is unavailable")
            with tempfile.TemporaryDirectory(prefix=f"healthtask-{EXERCISE}-{name}-") as frame_dir:
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
    dumbbells = build_bench_and_dumbbells()
    animate(rig, foot_rotations, dumbbells)
    validate_arm_path(rig)
    validate_equipment_clearance()
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    else:
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
