"""Build and render the original 오늘운동 barbell-squat motion sample.

Run with Blender, for example:
  blender --background --factory-startup --python generate_squat_sample.py -- \
    --output-dir app/src/main/res/raw --blend design/motion/squat_sample.blend --mode preview

The avatar and animation are generated entirely by this script so the app does not
depend on a third-party character or motion asset.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import dataclass

import bpy
from mathutils import Vector


FPS = 30
# Frame 241 duplicates frame 1 and is omitted from the encoded movie. The
# resulting 240 frames give every exercise one readable 8-second repetition at
# 30 fps.
FRAME_END = 241
RESOLUTION = 720


@dataclass(frozen=True)
class Palette:
    background: tuple = (0.009, 0.024, 0.043, 1.0)
    floor: tuple = (0.018, 0.055, 0.075, 1.0)
    platform: tuple = (0.025, 0.094, 0.120, 1.0)
    skin: tuple = (0.66, 0.31, 0.19, 1.0)
    skin_light: tuple = (0.88, 0.52, 0.34, 1.0)
    shirt: tuple = (0.015, 0.62, 0.58, 1.0)
    shorts: tuple = (0.075, 0.045, 0.15, 1.0)
    leggings: tuple = (0.025, 0.032, 0.060, 1.0)
    shoes: tuple = (0.80, 0.91, 0.94, 1.0)
    hair: tuple = (0.012, 0.014, 0.025, 1.0)
    eye: tuple = (0.01, 0.015, 0.02, 1.0)
    metal: tuple = (0.34, 0.39, 0.46, 1.0)
    plate: tuple = (0.035, 0.045, 0.070, 1.0)
    teal: tuple = (0.02, 0.95, 0.78, 1.0)
    violet: tuple = (0.50, 0.22, 1.0, 1.0)
    pink: tuple = (1.0, 0.12, 0.48, 1.0)
    gold: tuple = (1.0, 0.63, 0.10, 1.0)


P = Palette()


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument("--mode", choices=("preview", "render", "build"), default="preview")
    return parser.parse_args(argv)


def clear_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in (bpy.data.meshes, bpy.data.curves, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
        for item in list(collection):
            if item.users == 0:
                collection.remove(item)


def material(name: str, color: tuple, *, metallic=0.0, roughness=0.45, emission=None, emission_strength=0.0):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = color
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = color
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if "Coat Weight" in bsdf.inputs:
        bsdf.inputs["Coat Weight"].default_value = 0.28
    if emission is not None:
        if "Emission Color" in bsdf.inputs:
            bsdf.inputs["Emission Color"].default_value = emission
        elif "Emission" in bsdf.inputs:
            bsdf.inputs["Emission"].default_value = emission
        if "Emission Strength" in bsdf.inputs:
            bsdf.inputs["Emission Strength"].default_value = emission_strength
    return mat


def smooth(obj) -> None:
    if obj.type == "MESH":
        for polygon in obj.data.polygons:
            polygon.use_smooth = True


def ellipsoid(name: str, location, scale, mat, segments=32, rings=20):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    smooth(obj)
    obj.data.materials.append(mat)
    return obj


def rounded_cube(name: str, location, scale, mat, bevel=0.12):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    modifier = obj.modifiers.new("Soft edges", "BEVEL")
    modifier.width = bevel
    modifier.segments = 5
    smooth(obj)
    obj.data.materials.append(mat)
    return obj


def cylinder(name: str, location, radius, depth, mat, vertices=40):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    smooth(obj)
    obj.data.materials.append(mat)
    bevel = obj.modifiers.new("Rounded edges", "BEVEL")
    bevel.width = min(radius * 0.16, depth * 0.12)
    bevel.segments = 3
    return obj


def torus(name: str, location, major_radius, minor_radius, mat, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_torus_add(
        major_radius=major_radius,
        minor_radius=minor_radius,
        major_segments=96,
        minor_segments=12,
        location=location,
        rotation=rotation,
    )
    obj = bpy.context.object
    obj.name = name
    smooth(obj)
    obj.data.materials.append(mat)
    return obj


def set_segment(obj, start: Vector, end: Vector, radius: float, frame: int | None = None, squash=(1.0, 1.0)):
    delta = end - start
    obj.location = (start + end) / 2
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(delta.normalized())
    obj.scale = (radius * squash[0], radius * squash[1], delta.length / 2)
    if frame is not None:
        obj.keyframe_insert("location", frame=frame)
        obj.keyframe_insert("rotation_quaternion", frame=frame)
        obj.keyframe_insert("scale", frame=frame)


def set_ellipsoid(obj, location: Vector, scale, frame: int | None = None, direction: Vector | None = None):
    obj.location = location
    obj.scale = scale
    if direction is not None:
        obj.rotation_mode = "QUATERNION"
        obj.rotation_quaternion = Vector((0, 0, 1)).rotation_difference(direction.normalized())
    if frame is not None:
        obj.keyframe_insert("location", frame=frame)
        obj.keyframe_insert("scale", frame=frame)
        if direction is not None:
            obj.keyframe_insert("rotation_quaternion", frame=frame)


def smoothstep(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def squat_depth(frame: int) -> float:
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


def look_at(obj, point: Vector) -> None:
    obj.rotation_euler = (Vector(point) - obj.location).to_track_quat("-Z", "Y").to_euler()


def build_scene():
    mats = {
        "skin": material("Warm skin", P.skin, roughness=0.52),
        "skin_light": material("Face highlight", P.skin_light, roughness=0.48),
        "shirt": material("Prism teal top", P.shirt, roughness=0.3, emission=P.teal, emission_strength=0.08),
        "shorts": material("Deep violet shorts", P.shorts, roughness=0.38),
        "leggings": material("Graphite leggings", P.leggings, roughness=0.32),
        "shoes": material("Pearl trainers", P.shoes, metallic=0.08, roughness=0.28),
        "hair": material("Hair", P.hair, roughness=0.33),
        "eye": material("Eyes", P.eye, roughness=0.2),
        "metal": material("Bar steel", P.metal, metallic=0.92, roughness=0.18),
        "plate": material("Weight plates", P.plate, metallic=0.28, roughness=0.24),
        "teal_glow": material("Teal glow", P.teal, roughness=0.22, emission=P.teal, emission_strength=3.5),
        "pink_glow": material("Muscle highlight", P.pink, roughness=0.28, emission=P.pink, emission_strength=1.8),
        "violet_glow": material("Violet glow", P.violet, roughness=0.25, emission=P.violet, emission_strength=2.5),
        "gold_glow": material("Gold glow", P.gold, roughness=0.25, emission=P.gold, emission_strength=2.0),
        "floor": material("Studio floor", P.floor, metallic=0.15, roughness=0.34),
        "platform": material("Training platform", P.platform, metallic=0.12, roughness=0.3),
    }

    # Studio floor and a restrained prism motif keep the motion readable on a phone.
    bpy.ops.mesh.primitive_plane_add(size=20, location=(0, 0, -0.015))
    floor = bpy.context.object
    floor.name = "Studio floor"
    floor.data.materials.append(mats["floor"])
    cylinder("Platform", (0, 0.02, 0.015), 1.34, 0.06, mats["platform"], vertices=96)
    torus("Platform teal ring", (0, 0.02, 0.052), 1.22, 0.012, mats["teal_glow"])
    torus("Platform violet ring", (0, 0.02, 0.054), 1.30, 0.006, mats["violet_glow"])

    for index, (x, y, color_name, height) in enumerate((
        (-1.48, 0.90, "teal_glow", 1.55),
        (-1.30, 1.02, "violet_glow", 1.10),
        (1.48, 0.90, "pink_glow", 1.55),
        (1.30, 1.02, "gold_glow", 1.10),
    )):
        rounded_cube(f"Prism light {index}", (x, y, height / 2), (0.018, 0.018, height / 2), mats[color_name], bevel=0.025)

    # Character pieces start as unit spheres and are shaped/animated below.
    parts = {
        "torso": ellipsoid("Athletic torso", (0, 0, 1), (1, 1, 1), mats["shirt"]),
        "shoulders": ellipsoid("Shoulder line", (0, 0, 1), (1, 1, 1), mats["shirt"]),
        "pelvis": ellipsoid("Training shorts", (0, 0, 1), (1, 1, 1), mats["shorts"]),
        "neck": ellipsoid("Neck", (0, 0, 1), (1, 1, 1), mats["skin"]),
        "head": ellipsoid("Head", (0, 0, 1), (1, 1, 1), mats["skin_light"]),
        "hair": ellipsoid("Hair cap", (0, 0, 1), (1, 1, 1), mats["hair"]),
        "nose": ellipsoid("Nose", (0, 0, 1), (1, 1, 1), mats["skin"]),
        "mouth": ellipsoid("Mouth", (0, 0, 1), (1, 1, 1), mats["pink_glow"], 20, 12),
        "left_eye": ellipsoid("Left eye", (0, 0, 1), (0.018, 0.010, 0.012), mats["eye"], 20, 12),
        "right_eye": ellipsoid("Right eye", (0, 0, 1), (0.018, 0.010, 0.012), mats["eye"], 20, 12),
    }
    for side in ("left", "right"):
        parts[f"{side}_thigh"] = ellipsoid(f"{side.title()} thigh", (0, 0, 1), (1, 1, 1), mats["leggings"])
        parts[f"{side}_shin"] = ellipsoid(f"{side.title()} shin", (0, 0, 1), (1, 1, 1), mats["skin"])
        parts[f"{side}_upper_arm"] = ellipsoid(f"{side.title()} upper arm", (0, 0, 1), (1, 1, 1), mats["skin"])
        parts[f"{side}_forearm"] = ellipsoid(f"{side.title()} forearm", (0, 0, 1), (1, 1, 1), mats["skin"])
        parts[f"{side}_hand"] = ellipsoid(f"{side.title()} hand", (0, 0, 1), (1, 1, 1), mats["skin"])
        parts[f"{side}_shoe"] = ellipsoid(f"{side.title()} trainer", (0, 0, 1), (1, 1, 1), mats["shoes"])
        parts[f"{side}_quad"] = ellipsoid(f"{side.title()} quad activation", (0, 0, 1), (1, 1, 1), mats["pink_glow"], 24, 14)

    bar = cylinder("Olympic bar", (0, 0, 1), 0.022, 1.65, mats["metal"], vertices=32)
    bar.rotation_euler[1] = math.radians(90)
    plates = []
    for side, x in (("left", -0.73), ("right", 0.73)):
        plate = cylinder(f"{side.title()} plate", (x, 0, 1), 0.19, 0.095, mats["plate"], vertices=64)
        plate.rotation_euler[1] = math.radians(90)
        plates.append(plate)
        ring = torus(
            f"{side.title()} plate accent",
            (x - 0.050 if x < 0 else x + 0.050, 0, 1),
            0.135,
            0.013,
            mats["violet_glow"] if x < 0 else mats["teal_glow"],
            rotation=(0, math.radians(90), 0),
        )
        plates.append(ring)

    # A full set of frame samples avoids limb flips and produces a deterministic loop.
    for frame in range(1, FRAME_END + 1):
        depth = squat_depth(frame)
        hip_center = Vector((0, 0.22 * depth, 1.04 - 0.40 * depth))
        shoulder_center = Vector((0, -0.015 - 0.035 * depth, 1.43 - 0.31 * depth))
        torso_axis = shoulder_center - hip_center
        head_center = shoulder_center + Vector((0, -0.025, 0.285))
        bar_center = shoulder_center + Vector((0, 0.105, -0.025))

        extended_torso_start = hip_center - torso_axis * 0.05
        extended_torso_end = shoulder_center + torso_axis * 0.05
        set_segment(parts["torso"], extended_torso_start, extended_torso_end, 0.225, frame, squash=(1.0, 0.58))
        set_segment(
            parts["shoulders"],
            shoulder_center + Vector((-0.285, 0, -0.018)),
            shoulder_center + Vector((0.285, 0, -0.018)),
            0.084,
            frame,
            squash=(1.0, 0.88),
        )
        set_ellipsoid(parts["pelvis"], hip_center + Vector((0, 0, 0.005)), (0.205, 0.145, 0.135), frame, torso_axis)
        set_segment(parts["neck"], shoulder_center + Vector((0, 0, 0.015)), head_center - Vector((0, 0, 0.102)), 0.058, frame)
        set_ellipsoid(parts["head"], head_center, (0.112, 0.100, 0.145), frame)
        set_ellipsoid(parts["hair"], head_center + Vector((0, 0.022, 0.052)), (0.118, 0.105, 0.110), frame)
        set_ellipsoid(parts["nose"], head_center + Vector((0, -0.104, -0.002)), (0.018, 0.025, 0.029), frame)
        set_ellipsoid(parts["mouth"], head_center + Vector((0, -0.101, -0.052)), (0.028, 0.009, 0.006), frame)
        set_ellipsoid(parts["left_eye"], head_center + Vector((-0.039, -0.100, 0.035)), (0.013, 0.010, 0.009), frame)
        set_ellipsoid(parts["right_eye"], head_center + Vector((0.039, -0.100, 0.035)), (0.013, 0.010, 0.009), frame)

        for side, sign in (("left", -1), ("right", 1)):
            hip = hip_center + Vector((0.175 * sign, 0, -0.035))
            knee = Vector(((0.235 + 0.045 * depth) * sign, -0.17 * depth, 0.61 - 0.16 * depth))
            ankle = Vector((0.235 * sign, 0.005, 0.145))
            toe = Vector((0.235 * sign, -0.20, 0.090))
            shoulder = shoulder_center + Vector((0.285 * sign, 0, -0.01))
            elbow = bar_center + Vector((0.35 * sign, 0.10, -0.22))
            wrist = bar_center + Vector((0.48 * sign, -0.002, -0.012))

            set_segment(parts[f"{side}_thigh"], hip, knee, 0.092, frame, squash=(1.0, 0.92))
            set_segment(parts[f"{side}_shin"], knee, ankle, 0.069, frame, squash=(1.0, 0.90))
            set_segment(parts[f"{side}_upper_arm"], shoulder, elbow, 0.052, frame, squash=(1.0, 0.90))
            set_segment(parts[f"{side}_forearm"], elbow, wrist, 0.044, frame, squash=(1.0, 0.90))
            set_ellipsoid(parts[f"{side}_hand"], wrist, (0.048, 0.037, 0.040), frame)
            set_segment(parts[f"{side}_shoe"], ankle - Vector((0, 0, 0.045)), toe, 0.071, frame, squash=(1.0, 0.82))

            quad_start = hip * 0.70 + knee * 0.30 + Vector((0, -0.090, 0))
            quad_end = hip * 0.25 + knee * 0.75 + Vector((0, -0.075, 0.005))
            set_segment(parts[f"{side}_quad"], quad_start, quad_end, 0.026 + 0.005 * depth, frame, squash=(1.0, 0.52))

        bar.location = bar_center
        bar.keyframe_insert("location", frame=frame)
        for index, plate in enumerate(plates):
            x = -0.73 if index < 2 else 0.73
            plate.location = (x, bar_center.y, bar_center.z)
            plate.keyframe_insert("location", frame=frame)

    # Cameras are kept in one source scene and selected during rendering.
    cameras = {}
    for name, location, target, lens in (
        ("Front", (0, -4.65, 1.00), (0, 0.04, 0.92), 60),
        ("Side", (4.55, -1.85, 1.04), (0, 0.04, 0.94), 62),
    ):
        bpy.ops.object.camera_add(location=location)
        cam = bpy.context.object
        cam.name = f"{name} camera"
        cam.data.lens = lens
        look_at(cam, Vector(target))
        cameras[name.lower()] = cam

    # Three-point lighting plus emissive scene accents.
    for name, location, energy, size, color in (
        ("Key", (-2.4, -3.2, 3.8), 1250, 3.2, (0.68, 0.95, 1.0)),
        ("Fill", (2.8, -1.8, 2.2), 900, 2.5, (0.76, 0.55, 1.0)),
        ("Rim", (0.4, 2.2, 3.0), 1450, 2.2, (1.0, 0.20, 0.48)),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        lamp = bpy.context.object
        lamp.name = f"{name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0, 0, 0.9)))

    return cameras


def configure_scene() -> None:
    scene = bpy.context.scene
    try:
        scene.render.engine = "BLENDER_EEVEE_NEXT"
    except TypeError:
        # Blender 5.2 folded Eevee Next back into the BLENDER_EEVEE enum.
        # Keep the generator reproducible with both the original 4.5 LTS
        # toolchain and newer LTS installations.
        scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = RESOLUTION
    scene.render.resolution_y = RESOLUTION
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.render.fps = FPS
    scene.render.fps_base = 1.0
    scene.frame_start = 1
    scene.frame_end = FRAME_END
    scene.render.image_settings.color_mode = "RGBA"
    scene.world.color = P.background[:3]
    world = scene.world
    world.use_nodes = True
    background = world.node_tree.nodes.get("Background")
    background.inputs["Color"].default_value = P.background
    background.inputs["Strength"].default_value = 0.20
    scene.render.image_settings.color_mode = "RGB"
    scene.view_settings.look = "AgX - Medium High Contrast"
    scene.render.resolution_percentage = 100


def render_preview(output_dir: str, cameras: dict) -> None:
    scene = bpy.context.scene
    preview_dir = os.path.join(os.path.dirname(output_dir), "..", "..", "..", "..", "design", "motion", "previews")
    preview_dir = os.path.abspath(preview_dir)
    os.makedirs(preview_dir, exist_ok=True)
    scene.frame_set(121)
    for name, camera in cameras.items():
        scene.camera = camera
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(preview_dir, f"squat_{name}.png")
        bpy.ops.render.render(write_still=True)
        print(f"PREVIEW={scene.render.filepath}")


def render_movies(output_dir: str, cameras: dict) -> None:
    scene = bpy.context.scene
    os.makedirs(output_dir, exist_ok=True)
    for name, camera in cameras.items():
        scene.camera = camera
        scene.frame_start = 1
        scene.frame_end = FRAME_END - 1  # Frame 241 equals frame 1; omit it for a clean loop.
        scene.render.image_settings.file_format = "FFMPEG"
        scene.render.ffmpeg.format = "MPEG4"
        scene.render.ffmpeg.codec = "H264"
        scene.render.ffmpeg.constant_rate_factor = "MEDIUM"
        scene.render.ffmpeg.ffmpeg_preset = "GOOD"
        scene.render.ffmpeg.audio_codec = "NONE"
        scene.render.filepath = os.path.join(output_dir, f"squat_{name}.mp4")
        bpy.ops.render.render(animation=True)
        print(f"MOVIE={scene.render.filepath}")


def main() -> None:
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    os.makedirs(output_dir, exist_ok=True)
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    clear_scene()
    configure_scene()
    cameras = build_scene()
    bpy.context.scene.camera = cameras["front"]
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print(f"BLEND={blend_path}")
    if args.mode == "preview":
        render_preview(output_dir, cameras)
    elif args.mode == "render":
        render_movies(output_dir, cameras)


if __name__ == "__main__":
    main()
