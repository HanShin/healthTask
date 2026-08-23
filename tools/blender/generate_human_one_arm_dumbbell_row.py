"""Build the offline one-arm dumbbell row guide from the approved athlete.

Run with Blender after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_one_arm_dumbbell_row.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/one_arm_dumbbell_row_human_sample.blend \
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
    FRAME_END,
    P,
    configure_scene,
    cylinder,
    look_at,
    material,
    rounded_cube,
    smoothstep,
)


EXERCISE = "one_arm_dumbbell_row"


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render"),
        default="preview",
    )
    return parser.parse_args(argv)


def row_pull(frame: int) -> float:
    """One controlled pull with holds at the stretch and contraction."""
    t = (frame - 1) / (FRAME_END - 1)
    if t < 0.10:
        return 0.0
    if t < 0.40:
        return smoothstep((t - 0.10) / 0.30)
    if t < 0.52:
        return 1.0
    if t < 0.82:
        return 1.0 - smoothstep((t - 0.52) / 0.30)
    return 0.0


def set_hand_rotation(rig, side: str, desired_spread: Vector, desired_length: Vector, name: str):
    """Aim a hand from its rest-pose palm basis without Euler guessing."""
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

    desired_spread = desired_spread.normalized()
    desired_length = desired_length.normalized()
    desired_normal = desired_spread.cross(desired_length).normalized()
    desired_basis = Matrix(
        (desired_spread, desired_length, desired_normal)
    ).transposed()

    target = empty(name)
    target.rotation_mode = "QUATERNION"
    target.rotation_quaternion = (desired_basis @ source_basis.transposed()).to_quaternion()
    copy_world_rotation(rig, f"hand_{side}", target)
    return target


def curl_working_grip(rig):
    """Close the right hand around the dumbbell's front-to-back handle."""
    for finger, curls in {
        "index": (92, 34, 22),
        "middle": (86, 42, 44),
        "ring": (82, 44, 38),
        "pinky": (88, 34, 22),
    }.items():
        for joint, degrees in zip(("01", "02", "03"), curls):
            pose_bone = rig.pose.bones[f"{finger}_{joint}_r"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (math.radians(degrees), 0, 0)
    for joint, degrees in (("01", 40), ("02", 54), ("03", 38)):
        pose_bone = rig.pose.bones[f"thumb_{joint}_r"]
        pose_bone.rotation_mode = "XYZ"
        pose_bone.rotation_euler = (math.radians(degrees), 0, 0)


def flatten_support_hand(rig):
    """Pose a relaxed, weight-bearing left hand with five readable digits."""
    # Preserve a small anatomical fan instead of forcing every finger into the
    # same direction. The shorter ulnar fingers flex a little more, producing a
    # natural resting arc while every fingertip still reaches the pad.
    finger_pose = {
        "index": (-1.0, (2.0, 1.0, 0.0)),
        "middle": (9.0, (3.0, 2.0, 1.0)),
        "ring": (17.0, (4.0, 3.0, 2.0)),
        "pinky": (22.0, (5.0, 4.0, 2.0)),
    }
    for finger, (spread_degrees, curls) in finger_pose.items():
        for joint, flex_degrees in zip(("01", "02", "03"), curls):
            pose_bone = rig.pose.bones[f"{finger}_{joint}_l"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (
                math.radians(flex_degrees),
                0.0,
                math.radians(spread_degrees if joint == "01" else 0.0),
            )

    # A single Euler angle made the thumb look truncated from the side. Let the
    # complete three-bone chain solve toward an actual fingertip contact point
    # instead, so the metacarpal and both phalanges form one continuous curve.
    for joint in ("01", "02", "03"):
        pose_bone = rig.pose.bones[f"thumb_{joint}_l"]
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = (1.0, 0.0, 0.0, 0.0)
    thumb_tip = empty("L row support thumb tip target", (0.018, -0.004, 0.456))
    thumb_ik = rig.pose.bones["thumb_03_l"].constraints.new("IK")
    thumb_ik.name = "Natural support thumb"
    thumb_ik.target = thumb_tip
    thumb_ik.chain_count = 3
    thumb_ik.iterations = 32
    thumb_ik.use_stretch = False


def build_equipment():
    mats = {
        "pad": material("Row bench pad", (0.035, 0.045, 0.075, 1.0), roughness=0.48),
        "frame": material("Row bench steel", P.metal, metallic=0.92, roughness=0.18),
        "rubber": material("Row dumbbell rubber", (0.025, 0.032, 0.052, 1.0), metallic=0.18, roughness=0.34),
        "handle": material("Row dumbbell handle", (0.46, 0.52, 0.60, 1.0), metallic=0.95, roughness=0.14),
        "teal": material("Row dumbbell teal", P.teal, roughness=0.2, emission=P.teal, emission_strength=1.8),
    }

    # A lower bench keeps the supporting arm and thigh close to vertical while
    # the opposite foot can remain planted with this athlete's proportions.
    rounded_cube("Row bench pad", (0.0, 0.45, 0.38), (0.15, 0.61, 0.06), mats["pad"], bevel=0.035)
    rounded_cube("Row bench spine", (0.0, 0.45, 0.23), (0.035, 0.50, 0.035), mats["frame"], bevel=0.018)
    for y in (0.06, 0.84):
        rounded_cube(f"Row bench leg {y:+.2f}", (0.0, y, 0.19), (0.035, 0.035, 0.13), mats["frame"], bevel=0.015)
        rounded_cube(f"Row bench foot {y:+.2f}", (0.0, y, 0.045), (0.31, 0.045, 0.035), mats["frame"], bevel=0.018)

    root = empty("R row dumbbell root", display="PLAIN_AXES")
    root.empty_display_size = 0.14
    handle = cylinder("R row dumbbell handle", (0, 0, 0), 0.018, 0.20, mats["handle"], vertices=32)
    handle.rotation_euler[0] = math.radians(90)
    handle.parent = root
    for end_sign in (-1, 1):
        plate = cylinder(
            f"R row dumbbell plate {end_sign:+d}",
            (0, 0, 0),
            0.086,
            0.050,
            mats["rubber"],
            vertices=48,
        )
        plate.rotation_euler[0] = math.radians(90)
        plate.parent = root
        plate.location = (0, 0.105 * end_sign, 0)
        cap = cylinder(
            f"R row dumbbell cap {end_sign:+d}",
            (0, 0, 0),
            0.055,
            0.006,
            mats["teal"],
            vertices=48,
        )
        cap.rotation_euler[0] = math.radians(90)
        cap.parent = root
        cap.location = (0, 0.133 * end_sign, 0)
    return root


def animate(rig, standing_foot_rotations, dumbbell):
    # Right arm rows; left hand and knee support the athlete on the bench.
    # The torso, head and pelvis remain fixed to make trunk rotation impossible.
    # Rotate the torso 18 degrees above horizontal around the hip. Lowering the
    # rig origin at the same time preserves both leg contacts but raises the
    # shoulders enough for a genuinely extended support arm.
    rig.location = (0.0, 1.40, 0.531)
    rig.rotation_euler = (math.radians(72), 0.0, 0.0)

    # The rest neck slopes down after the athlete is tipped into the row. Its
    # local X axis is the anatomical left-right axis, so a small extension
    # restores the thoracic line without flipping the head's world direction.
    neck = rig.pose.bones["neck_01"]
    neck.rotation_mode = "XYZ"
    neck.rotation_euler = (math.radians(-18.0), 0.0, 0.0)
    head = rig.pose.bones["head"]
    head.rotation_mode = "XYZ"
    head.rotation_euler = (math.radians(18.0), 0.0, 0.0)
    right_foot = empty("R row foot target", (-0.30, 0.67, 0.062))
    right_knee = empty("R row knee pole", (-0.22, 0.50, 0.40))
    add_ik(rig, "calf_r", right_foot, right_knee)
    right_foot_rotation = empty("R row foot rotation")
    right_foot_rotation.matrix_world = standing_foot_rotations["r"]
    copy_world_rotation(rig, "foot_r", right_foot_rotation)

    left_ankle = empty("L row ankle target", (0.105, 1.18, 0.46))
    left_knee = empty("L row knee pole", (0.105, 0.84, 0.18))
    add_ik(rig, "calf_l", left_ankle, left_knee)

    # Keep the entire palm inside the 0.30 m pad. The wrist sits above the pad
    # by the hand's measured thickness so the palm surface, not its edge,
    # contacts the 0.44 m top plane.
    support_hand = empty("L row support hand target", (0.08, 0.14, 0.484))
    support_elbow = empty("L row support elbow pole", (0.24, 0.15, 0.68))
    add_ik(rig, "lowerarm_l", support_hand, support_elbow)
    set_hand_rotation(
        rig,
        "l",
        Vector((1, 0, 0)),
        # Keep the palm nearly parallel to the pad; the small downward pitch
        # lets the finger pads touch without wrapping over the front edge.
        Vector((0, -1, -0.06)),
        "L row support hand rotation",
    )
    flatten_support_hand(rig)

    working_hand = empty("R row working hand target")
    working_elbow = empty("R row working elbow pole")
    add_ik(rig, "lowerarm_r", working_hand, working_elbow)
    set_hand_rotation(
        rig,
        "r",
        # Keep the palm on the body-facing side of the handle so the fingers
        # travel across its underside and close on the far side.
        Vector((0, -1, 0)),
        Vector((0.12, 0, -1)),
        "R row grip rotation",
    )
    curl_working_grip(rig)

    for frame in range(1, FRAME_END + 1):
        pull = row_pull(frame)
        center = Vector(
            (
                -0.220,
                0.280 + 0.150 * pull,
                0.424 + 0.174 * pull,
            )
        )
        dumbbell.location = center
        dumbbell.keyframe_insert("location", frame=frame)

        # Keep the wrist directly above the handle and within the arm's
        # measured reach. At the bottom this leaves a soft 7-degree elbow bend;
        # at the top the forearm is vertical and the elbow closes to 90 degrees.
        working_hand.location = center + Vector((0, 0, 0.042))
        working_hand.keyframe_insert("location", frame=frame)
        working_elbow.location = Vector(
            (
                -0.240 + 0.020 * pull,
                0.300 + 0.190 * pull,
                0.680 + 0.090 * pull,
            )
        )
        working_elbow.keyframe_insert("location", frame=frame)

    bpy.context.scene.frame_set(1)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -3.55, 1.02), (0.0, 0.42, 0.54), 62),
        ("side", (-3.65, 0.35, 1.00), (-0.02, 0.42, 0.55), 64),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Row {name.title()} camera"
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
        lamp.name = f"Row {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.42, 0.60)))
    return cameras


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
    for frame, suffix in ((1, "bottom"), (61, "mid"), (121, "top")):
        scene.frame_set(frame)
        for name, camera in cameras.items():
            scene.camera = camera
            scene.render.image_settings.file_format = "PNG"
            scene.render.filepath = os.path.join(
                preview_dir, f"human_{EXERCISE}_{name}_{suffix}.png"
            )
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)
    render_grip_previews(output_dir)
    render_support_hand_preview(output_dir)


def render_grip_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(121)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Row grip inspection camera"
    camera.data.lens = 78
    scene.camera = camera
    target = Vector((-0.28, 0.48, 0.59))
    for name, location in (
        ("front", (-0.28, -1.05, 0.72)),
        ("angle", (-1.28, -0.40, 0.96)),
        ("rear", (-0.28, 1.70, 0.78)),
    ):
        camera.location = location
        look_at(camera, target)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_grip_{name}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)


def render_support_hand_preview(output_dir):
    """Render palm contact from the side and from above for anatomy checks."""
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(1)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Row support hand inspection camera"
    camera.data.lens = 82
    scene.camera = camera
    for suffix, location in (
        ("support_hand", (-1.25, -0.55, 0.72)),
        ("support_hand_top", (0.48, -0.58, 1.02)),
    ):
        camera.location = location
        look_at(camera, Vector((0.08, -0.02, 0.45)))
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_{suffix}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("SUPPORT_HAND_PREVIEW", scene.render.filepath)


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
    rig, standing_foot_rotations = reset_squat_scene()
    configure_athlete_materials()
    dumbbell = build_equipment()
    animate(rig, standing_foot_rotations, dumbbell)
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
