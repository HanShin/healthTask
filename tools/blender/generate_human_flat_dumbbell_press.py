"""Build the offline flat-dumbbell-press guide from the approved squat athlete.

Run with Blender after opening the packed squat source file, for example:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_flat_dumbbell_press.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/flat_dumbbell_press_human_sample.blend \
      --mode preview

The source blend contains the approved MakeHuman/MPFB mesh, clothing, rig and
materials, so regenerating this exercise does not require the MPFB extension.
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

from generate_squat_sample import (  # noqa: E402
    FRAME_END,
    P,
    configure_scene,
    cylinder,
    look_at,
    material,
    rounded_cube,
    smoothstep,
    torus,
)


EXERCISE = "flat_dumbbell_press"


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render", "build"),
        default="preview",
    )
    return parser.parse_args(argv)


def press_depth(frame: int) -> float:
    """One controlled repetition with holds at lockout and chest level."""
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


def empty(name: str, location=(0, 0, 0), display="SPHERE"):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.empty_display_type = display
    obj.empty_display_size = 0.06
    obj.location = location
    obj.hide_render = True
    return obj


def remove_object(obj) -> None:
    bpy.data.objects.remove(obj, do_unlink=True)


def reset_squat_scene():
    required = ("Human.rig", "Human", "Human platform")
    missing = [name for name in required if name not in bpy.data.objects]
    if missing:
        raise RuntimeError(
            "Open design/motion/squat_human_sample.blend before this script; "
            f"missing objects: {', '.join(missing)}"
        )

    scene = bpy.context.scene
    scene.frame_set(1)
    rig = bpy.data.objects["Human.rig"]

    # The approved standing foot controls already contain a natural planted
    # shoe orientation. Preserve it before deleting the squat controls.
    foot_rotations = {
        side: bpy.data.objects[f"{side.upper()} foot rotation target"].matrix_world.copy()
        for side in ("l", "r")
    }

    keep_prefixes = (
        "Human prism light",
        "Human platform",
    )
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
    rig.location = (0.0, -0.75, 0.62)
    rig.rotation_mode = "XYZ"
    rig.rotation_euler = (math.radians(-90), 0.0, 0.0)
    rig.scale = (1.0, 1.0, 1.0)

    for pose_bone in rig.pose.bones:
        for constraint in list(pose_bone.constraints):
            pose_bone.constraints.remove(constraint)
        pose_bone.location = (0.0, 0.0, 0.0)
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = Quaternion((1.0, 0.0, 0.0, 0.0))
        pose_bone.scale = (1.0, 1.0, 1.0)

    return rig, foot_rotations


def configure_athlete_materials():
    """Give the reused athlete a natural, self-contained human palette."""
    palette = {
        # Warm medium-light skin in Blender's linear color space. The previous
        # near-white value clipped under the colored stage lights and rendered
        # as neon pink.
        "Human.body": ((0.66, 0.36, 0.23, 1.0), 0.62, 0.0),
        "Human.female_sportsuit01": ((0.025, 0.070, 0.130, 1.0), 0.58, 0.0),
        "Compression shorts": ((0.012, 0.024, 0.055, 1.0), 0.60, 0.08),
        "Human.shoes05": ((0.68, 0.72, 0.74, 1.0), 0.64, 0.0),
        "Human.ponytail01": ((0.014, 0.008, 0.005, 1.0), 0.66, 0.0),
        "Human.eyebrow004": ((0.020, 0.010, 0.006, 1.0), 0.68, 0.0),
        "Human.eyelashes01": ((0.012, 0.006, 0.004, 1.0), 0.70, 0.0),
    }
    for material_name, (base_color, roughness, coat_weight) in palette.items():
        mat = bpy.data.materials.get(material_name)
        if mat is None or mat.node_tree is None:
            continue
        mat.diffuse_color = base_color

        # The approved source blend references optional MPFB image files that
        # are not part of this repository. Blender renders those unresolved
        # image nodes as bright magenta. Disconnect only missing images so the
        # packed flat palette below is deterministic and works fully offline.
        for image_node in mat.node_tree.nodes:
            if image_node.type != "TEX_IMAGE":
                continue
            image = image_node.image
            if image is not None and image.size[0] > 0 and image.size[1] > 0:
                continue
            for output in image_node.outputs:
                for link in list(output.links):
                    mat.node_tree.links.remove(link)

        for node in mat.node_tree.nodes:
            if node.type != "BSDF_PRINCIPLED":
                continue
            node.inputs["Base Color"].default_value = base_color
            node.inputs["Roughness"].default_value = roughness
            coat = node.inputs.get("Coat Weight")
            if coat is not None:
                coat.default_value = coat_weight
            subsurface = node.inputs.get("Subsurface Weight")
            if subsurface is not None:
                subsurface.default_value = 0.055 if material_name == "Human.body" else 0.0


def build_bench_and_dumbbells():
    mats = {
        "pad": material("Press bench pad", (0.035, 0.045, 0.075, 1.0), roughness=0.48),
        "frame": material("Press bench steel", P.metal, metallic=0.92, roughness=0.18),
        "rubber": material("Dumbbell rubber", (0.025, 0.032, 0.052, 1.0), metallic=0.18, roughness=0.34),
        "handle": material("Dumbbell handle", (0.46, 0.52, 0.60, 1.0), metallic=0.95, roughness=0.14),
        "teal": material("Dumbbell teal", P.teal, roughness=0.2, emission=P.teal, emission_strength=1.8),
        "violet": material("Dumbbell violet", P.violet, roughness=0.2, emission=P.violet, emission_strength=1.8),
    }

    # Center the 1.22 x 0.30 m pad between the athlete's glutes and head. Its
    # 0.55 m top surface closes the small visible gap under the glutes and
    # shoulder blades without flattening the natural lumbar curve.
    rounded_cube("Flat bench pad", (0.0, 0.41, 0.49), (0.15, 0.61, 0.06), mats["pad"], bevel=0.035)
    rounded_cube("Flat bench spine", (0.0, 0.41, 0.30), (0.035, 0.50, 0.035), mats["frame"], bevel=0.018)
    for y in (0.02, 0.80):
        rounded_cube(f"Bench leg {y:+.2f}", (0.0, y, 0.24), (0.035, 0.035, 0.20), mats["frame"], bevel=0.015)
        rounded_cube(f"Bench foot {y:+.2f}", (0.0, y, 0.045), (0.31, 0.045, 0.035), mats["frame"], bevel=0.018)

    dumbbells = {}
    for side, accent in (("l", "violet"), ("r", "teal")):
        root = empty(f"{side.upper()} dumbbell root", display="PLAIN_AXES")
        root.empty_display_size = 0.14

        handle = cylinder(f"{side.upper()} dumbbell handle", (0, 0, 0), 0.018, 0.20, mats["handle"], vertices=32)
        handle.rotation_euler[1] = math.radians(90)
        handle.parent = root
        handle.location = (0, 0, 0)

        for end_sign in (-1, 1):
            plate = cylinder(
                f"{side.upper()} dumbbell plate {end_sign:+d}",
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
                f"{side.upper()} dumbbell cap {end_sign:+d}",
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


def add_ik(rig, bone_name, target, pole, *, pole_angle=-90.0):
    constraint = rig.pose.bones[bone_name].constraints.new("IK")
    constraint.target = target
    constraint.pole_target = pole
    constraint.chain_count = 2
    constraint.pole_angle = math.radians(pole_angle)
    constraint.use_stretch = False
    return constraint


def copy_world_rotation(rig, bone_name, target):
    constraint = rig.pose.bones[bone_name].constraints.new("COPY_ROTATION")
    constraint.target = target
    constraint.owner_space = "WORLD"
    constraint.target_space = "WORLD"
    constraint.mix_mode = "REPLACE"
    return constraint


def configure_grip(rig):
    rotation_targets = {}
    # Rotate the squat's validated overhand-grip basis with the athlete. The
    # handle still runs on world X, while the palm and wrist now face correctly
    # for the supine press.
    desired_hand_length = Vector((0.0, 0.60, 0.80)).normalized()
    for side, sign in (("l", 1), ("r", -1)):
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

        desired_finger_spread = Vector((sign, 0, 0))
        desired_palm_normal = desired_finger_spread.cross(desired_hand_length).normalized()
        grip_basis = Matrix(
            (desired_finger_spread, desired_hand_length, desired_palm_normal)
        ).transposed()

        target = empty(f"{side.upper()} press hand rotation")
        target.rotation_mode = "QUATERNION"
        target.rotation_quaternion = (grip_basis @ source_basis.transposed()).to_quaternion()
        copy_world_rotation(rig, f"hand_{side}", target)
        rotation_targets[side] = target

        # Joint-specific values are the approved squat grip, reused because the
        # dumbbell handle has the same axis and a comparable 36 mm diameter.
        for finger, curls in {
            "index": (95, 30, 15),
            "middle": (80, 40, 45),
            "ring": (75, 45, 35),
            "pinky": (85, 30, 15),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (math.radians(degrees), 0, 0)
        for joint, degrees in (("01", 38), ("02", 52), ("03", 36)):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (math.radians(degrees), 0, 0)
    return rotation_targets


def animate(rig, foot_rotations, dumbbells):
    # Motion definition:
    # - Fixed contacts: head, upper back and glutes on the pad; both feet planted.
    # - Elbows descend slightly below the torso at roughly 50 degrees from it.
    # - Forearms remain close to vertical at the bottom and wrists stay neutral.
    # - Dumbbells lower beside the chest and rise in a slight inward arc without
    #   colliding at lockout.
    # - Frame 241 matches frame 1 and is omitted from the encoded loop.
    foot_targets = {
        "l": empty("L press foot target", (0.255, -0.15, 0.062)),
        "r": empty("R press foot target", (-0.255, -0.15, 0.062)),
    }
    knee_poles = {
        "l": empty("L press knee pole", (0.275, -0.38, 0.76)),
        "r": empty("R press knee pole", (-0.275, -0.38, 0.76)),
    }
    for side in ("l", "r"):
        add_ik(rig, f"calf_{side}", foot_targets[side], knee_poles[side])
        foot_rotation = empty(f"{side.upper()} press foot rotation")
        foot_rotation.matrix_world = foot_rotations[side]
        copy_world_rotation(rig, f"foot_{side}", foot_rotation)

    hand_targets = {
        "l": empty("L press hand target"),
        "r": empty("R press hand target"),
    }
    elbow_poles = {
        "l": empty("L press elbow pole"),
        "r": empty("R press elbow pole"),
    }
    for side in ("l", "r"):
        add_ik(rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side])
    configure_grip(rig)

    for frame in range(1, FRAME_END + 1):
        depth = press_depth(frame)
        # The handle centers lower outside the chest, then converge above it.
        center_x = 0.255 + 0.115 * depth
        center_y = 0.355 - 0.035 * depth
        center_z = 1.015 - 0.295 * depth
        for side, sign in (("l", 1), ("r", -1)):
            center = Vector((center_x * sign, center_y, center_z))
            dumbbells[side].location = center
            dumbbells[side].keyframe_insert("location", frame=frame)

            # The wrist is just below and slightly footward of the handle so
            # the curled fingers, rather than the palm center, wrap the shaft.
            hand_targets[side].location = center + Vector((0, -0.018, -0.035))
            hand_targets[side].keyframe_insert("location", frame=frame)

            # Animate the anatomical elbow path itself instead of aiming at a
            # distant generic pole. At lockout the elbow remains outside the
            # shoulder and directly below the wrist; during descent it travels
            # laterally and just below the torso. This avoids the inward elbow
            # flip that a nearly straight two-bone IK chain produced before.
            elbow_poles[side].location = Vector(
                (
                    (0.230 + 0.090 * depth) * sign,
                    0.340 + 0.040 * depth,
                    0.790 - 0.270 * depth,
                )
            )
            elbow_poles[side].keyframe_insert("location", frame=frame)

    bpy.context.scene.frame_set(1)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        # The head-end view exposes both elbow paths and the dumbbell spacing;
        # a foot-end view hid the torso behind the bent legs.
        ("front", (0.0, 4.10, 0.95), (0.0, 0.18, 0.66), 62),
        ("side", (3.70, -0.12, 0.90), (0.0, 0.16, 0.64), 64),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Press {name.title()} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera

    # Mostly neutral portrait lighting keeps skin and clothing recognizable;
    # the platform emissives retain the futuristic teal/violet stage accents.
    for name, location, energy, size, color in (
        ("Key", (-2.4, -2.5, 3.2), 980, 2.8, (1.0, 0.92, 0.84)),
        ("Fill", (2.4, -1.3, 2.2), 430, 2.2, (0.78, 0.88, 1.0)),
        ("Rim", (0.3, 2.2, 2.7), 560, 2.0, (0.72, 0.62, 1.0)),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        lamp = bpy.context.object
        lamp.name = f"Press {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0.0, 0.20, 0.64)))
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
    for frame, suffix in ((1, "top"), (61, "mid"), (121, "bottom")):
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


def render_grip_previews(output_dir):
    """Render the bottom-position left grip from three inspection angles."""
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(121)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Press grip inspection camera"
    camera.data.lens = 78
    scene.camera = camera
    target = Vector((0.37, 0.32, 0.72))
    for name, location in (
        ("front", (0.37, -1.20, 0.80)),
        ("angle", (1.35, -0.62, 1.14)),
        ("rear", (0.37, 1.48, 0.84)),
    ):
        camera.location = location
        look_at(camera, target)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir, f"human_{EXERCISE}_grip_{name}.png"
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)


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
            # Blender's RNA may advertise FFMPEG even when the packaged build
            # cannot select it, so capability detection must use assignment.
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
            # Homebrew's Blender 5.2 build can omit the FFmpeg output enum.
            # Render lossless frames and encode the same H.264/yuv420p resource
            # with the system ffmpeg, keeping the intermediate files temporary.
            ffmpeg = shutil.which("ffmpeg")
            if not ffmpeg:
                raise RuntimeError(
                    "This Blender build has no FFmpeg output; install ffmpeg "
                    "or use Blender 4.5 LTS with bundled FFmpeg support."
                )
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
    rig, foot_rotations = reset_squat_scene()
    configure_athlete_materials()
    dumbbells = build_bench_and_dumbbells()
    animate(rig, foot_rotations, dumbbells)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
