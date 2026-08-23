"""Render the final 사람형 barbell-squat sample with MPFB's CC0 base mesh.

Prerequisites:
  - Blender 4.2+
  - MPFB 2 extension enabled
  - makehuman_system_assets_cc0 installed in MPFB's user data directory
"""

from __future__ import annotations

import argparse
import bmesh
import importlib
import math
import os
import sys

import bpy
from mathutils import Matrix, Quaternion, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_squat_sample import (
    FRAME_END,
    P,
    configure_scene,
    cylinder,
    clear_scene,
    ellipsoid,
    look_at,
    material,
    rounded_cube,
    smoothstep,
    torus,
)


FPS = 30


def dynamic_import(package_suffix, key):
    for module_name in sys.modules:
        if module_name.endswith(package_suffix):
            return getattr(importlib.import_module(module_name), key)
    raise RuntimeError(f"MPFB module is not loaded: {package_suffix}")


HumanService = dynamic_import("mpfb.services.humanservice", "HumanService")
TargetService = dynamic_import("mpfb.services.targetservice", "TargetService")
AssetService = dynamic_import("mpfb.services.assetservice", "AssetService")
HumanObjectProperties = dynamic_import("mpfb.entities.objectproperties", "HumanObjectProperties")


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render", "build", "iktest", "legtest"),
        default="preview",
    )
    return parser.parse_args(argv)


def depth_at(frame: int) -> float:
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


def set_principled_color(obj, color, metallic=0.0, roughness=0.36, emission=None):
    for mat in obj.data.materials:
        if not mat or not mat.use_nodes:
            continue
        bsdf = mat.node_tree.nodes.get("Principled BSDF")
        if not bsdf:
            continue
        bsdf.inputs["Base Color"].default_value = color
        bsdf.inputs["Metallic"].default_value = metallic
        bsdf.inputs["Roughness"].default_value = roughness
        if emission:
            if "Emission Color" in bsdf.inputs:
                bsdf.inputs["Emission Color"].default_value = emission
            if "Emission Strength" in bsdf.inputs:
                bsdf.inputs["Emission Strength"].default_value = 0.18


def required_asset(filename: str, subdir: str) -> str:
    path = AssetService.find_asset_absolute_path(filename, asset_subdir=subdir)
    if not path:
        raise RuntimeError(f"Required MakeHuman system asset is missing: {subdir}/{filename}")
    return path


def create_compression_shorts(human):
    """Create a rigged high-waist layer from the already fitted body surface.

    The bundled sports suit pulls apart around the pelvis at deep flexion. A
    thin duplicate of the correctly weighted body vertices gives the athlete a
    stable compression-short silhouette without introducing a new asset.
    """
    shorts = human.copy()
    shorts.data = human.data.copy()
    bpy.context.collection.objects.link(shorts)
    shorts.name = "Rigged compression shorts"
    if shorts.data.shape_keys:
        shorts.shape_key_clear()

    mesh = bmesh.new()
    mesh.from_mesh(shorts.data)
    outside = [vertex for vertex in mesh.verts if vertex.co.z < 0.60 or vertex.co.z > 0.875]
    bmesh.ops.delete(mesh, geom=outside, context="VERTS")
    mesh.to_mesh(shorts.data)
    mesh.free()
    shorts.data.materials.clear()
    shorts.data.materials.append(material("Compression shorts", P.shorts, roughness=0.52))
    solidify = shorts.modifiers.new("Fabric thickness", "SOLIDIFY")
    solidify.thickness = 0.0025
    solidify.offset = 1.0
    return shorts


def create_athlete():
    human = HumanService.create_human(scale=0.1)
    for name, value in {
        "gender": 0.0,
        "age": 0.40,
        # A balanced athletic build deforms more naturally than the previous
        # long-limbed, very lean proportions at deep knee and hip flexion.
        "muscle": 0.58,
        "weight": 0.52,
        "proportions": 0.55,
        "height": 0.56,
        "asian": 0.75,
        "caucasian": 0.25,
        "african": 0.0,
    }.items():
        HumanObjectProperties.set_value(name, value, entity_reference=human)
    TargetService.reapply_macro_details(human)

    skin_path = required_asset("young_asian_female.mhmat", "skins")
    HumanService.set_character_skin(skin_path, human, skin_type="GAMEENGINE")
    rig = HumanService.add_builtin_rig(human, "game_engine")
    for skin_material in human.data.materials:
        if not skin_material or not skin_material.use_nodes:
            continue
        bsdf = skin_material.node_tree.nodes.get("Principled BSDF")
        if not bsdf:
            continue
        bsdf.inputs["Roughness"].default_value = 0.58
        if "Coat Weight" in bsdf.inputs:
            bsdf.inputs["Coat Weight"].default_value = 0.0
        if "Specular IOR Level" in bsdf.inputs:
            bsdf.inputs["Specular IOR Level"].default_value = 0.28

    asset_specs = (
        ("eyes", "low-poly.mhclo", "Eyes", None),
        ("eyebrows", "eyebrow004.mhclo", "Eyebrows", None),
        ("eyelashes", "eyelashes01.mhclo", "Eyelashes", None),
        ("hair", "ponytail01.mhclo", "Hair", P.hair),
        ("clothes", "female_sportsuit01.mhclo", "Clothes", P.shirt),
        ("clothes", "shoes05.mhclo", "Clothes", P.shoes),
    )
    loaded = []
    for subdir, filename, asset_type, color in asset_specs:
        path = required_asset(filename, subdir)
        obj = HumanService.add_mhclo_asset(path, human, asset_type=asset_type, material_type="GAMEENGINE")
        loaded.append(obj)
        if color:
            # Matte fabric prevents the leggings from reading as distorted
            # metallic tubes around the knees in the guide render.
            set_principled_color(obj, color, roughness=0.52)

    loaded.append(create_compression_shorts(human))

    # Slight subdivision is enough at the 720px delivery resolution.
    for obj in [human, *loaded]:
        if obj.type == "MESH":
            for modifier in obj.modifiers:
                if modifier.type == "SUBSURF":
                    modifier.levels = min(modifier.levels, 1)
                    modifier.render_levels = min(modifier.render_levels, 1)
    return human, rig, loaded


def empty(name: str, location=(0, 0, 0), display="SPHERE"):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.empty_display_type = display
    obj.empty_display_size = 0.06
    obj.location = location
    obj.hide_render = True
    return obj


def add_ik(rig, bone_name, target, pole, chain_count=2, pole_angle=0.0):
    pose_bone = rig.pose.bones[bone_name]
    constraint = pose_bone.constraints.new("IK")
    constraint.target = target
    constraint.pole_target = pole
    constraint.chain_count = chain_count
    constraint.pole_angle = pole_angle
    constraint.use_stretch = False
    return constraint


def add_world_rotation_control(rig, bone_name, label):
    """Drive a pose bone from an empty in world space.

    MPFB's game rig has different bone rolls along the spine. Copying a world
    orientation avoids guessing local Euler axes and keeps the lumbar chain
    neutral while the torso inclines.
    """
    bone = rig.data.bones[bone_name]
    control = empty(f"{label} rotation control")
    control.rotation_mode = "QUATERNION"
    rest_world = (rig.matrix_world @ bone.matrix_local).to_quaternion()
    control.rotation_quaternion = rest_world
    constraint = rig.pose.bones[bone_name].constraints.new("COPY_ROTATION")
    constraint.name = f"{label} world rotation"
    constraint.target = control
    constraint.owner_space = "WORLD"
    constraint.target_space = "WORLD"
    constraint.mix_mode = "REPLACE"
    return control, rest_world


def build_studio():
    mats = {
        "floor": material("Human studio floor", P.floor, metallic=0.15, roughness=0.34),
        "platform": material("Human training platform", P.platform, metallic=0.12, roughness=0.3),
        "teal": material("Human teal glow", P.teal, roughness=0.2, emission=P.teal, emission_strength=3.5),
        "violet": material("Human violet glow", P.violet, roughness=0.2, emission=P.violet, emission_strength=2.8),
        "pink": material("Human pink glow", P.pink, roughness=0.2, emission=P.pink, emission_strength=2.8),
        "gold": material("Human gold glow", P.gold, roughness=0.2, emission=P.gold, emission_strength=2.3),
        "metal": material("Human bar steel", P.metal, metallic=0.95, roughness=0.16),
        "plate": material("Human weight plate", P.plate, metallic=0.34, roughness=0.23),
    }
    bpy.ops.mesh.primitive_plane_add(size=20, location=(0, 0, -0.065))
    bpy.context.object.data.materials.append(mats["floor"])
    cylinder("Human platform", (0, 0.02, -0.030), 1.26, 0.06, mats["platform"], vertices=96)
    torus("Human platform teal", (0, 0.02, 0.007), 1.15, 0.011, mats["teal"])
    torus("Human platform violet", (0, 0.02, 0.009), 1.22, 0.006, mats["violet"])
    for index, (x, y, mat_name, height) in enumerate((
        (-1.42, 0.86, "teal", 1.48),
        (-1.27, 0.96, "violet", 1.05),
        (1.42, 0.86, "pink", 1.48),
        (1.27, 0.96, "gold", 1.05),
    )):
        rounded_cube(f"Human prism light {index}", (x, y, height / 2), (0.016, 0.016, height / 2), mats[mat_name], bevel=0.022)

    # A 26 mm shaft matches the hand scale and the 25 mm women's Olympic-bar
    # standard closely enough for this stylized athlete.
    bar = cylinder("Human Olympic bar", (0, 0, 1.2), 0.013, 1.42, mats["metal"], vertices=36)
    bar.rotation_euler[1] = math.radians(90)
    weights = []
    for label, x, accent in (("Left", -0.62, "violet"), ("Right", 0.62, "teal")):
        plate = cylinder(f"Human {label} plate", (x, 0, 1.2), 0.125, 0.068, mats["plate"], vertices=64)
        plate.rotation_euler[1] = math.radians(90)
        ring = torus(
            f"Human {label} plate accent",
            (x - 0.043 if x < 0 else x + 0.043, 0, 1.2),
            0.086,
            0.011,
            mats[accent],
            rotation=(0, math.radians(90), 0),
        )
        weights.extend([plate, ring])
    return mats, bar, weights


def animate(rig, bar, weights):
    # Reference motion:
    # - https://www.youtube.com/shorts/KqbKmBSDVS4 (side view)
    # - https://www.youtube.com/shorts/lHyQ4Jy0LSA (front / three-quarter)
    # The feet stay planted while the hips travel down and back. The bar remains
    # over the mid-foot and the knees track over the slightly turned-out toes.
    left_ankle = Vector(rig.data.bones["calf_l"].tail_local)
    right_ankle = Vector(rig.data.bones["calf_r"].tail_local)
    left_ankle.x = 0.185
    right_ankle.x = -0.185
    foot_targets = {
        "l": empty("Left foot target", left_ankle),
        "r": empty("Right foot target", right_ankle),
    }
    knee_poles = {
        "l": empty("Left knee pole", (0.315, -0.70, 0.44)),
        "r": empty("Right knee pole", (-0.315, -0.70, 0.44)),
    }
    # -90 degrees is the MPFB game-rig plane that bends the knees forward.
    # The previous +90 plane folded the shins underneath the body.
    add_ik(rig, "calf_l", foot_targets["l"], knee_poles["l"], pole_angle=math.radians(-90))
    add_ik(rig, "calf_r", foot_targets["r"], knee_poles["r"], pole_angle=math.radians(-90))

    # Preserve a stable, slightly toe-out foot while the calf IK bends the legs.
    for side, toe_out in (("l", 8.0), ("r", -8.0)):
        rotation_target = empty(f"{side.upper()} foot rotation target")
        rotation_target.matrix_world = rig.matrix_world @ rig.data.bones[f"foot_{side}"].matrix_local
        rotation_target.rotation_mode = "QUATERNION"
        rotation_target.rotation_quaternion = (
            Quaternion((0, 0, 1), math.radians(toe_out)) @ rotation_target.rotation_quaternion
        )
        copy_rotation = rig.pose.bones[f"foot_{side}"].constraints.new("COPY_ROTATION")
        copy_rotation.target = rotation_target
        copy_rotation.owner_space = "WORLD"
        copy_rotation.target_space = "WORLD"
        copy_rotation.mix_mode = "REPLACE"

    hand_targets = {"l": empty("Left hand target"), "r": empty("Right hand target")}
    elbow_poles = {"l": empty("Left elbow pole"), "r": empty("Right elbow pole")}
    add_ik(rig, "lowerarm_l", hand_targets["l"], elbow_poles["l"], pole_angle=math.radians(-90))
    add_ik(rig, "lowerarm_r", hand_targets["r"], elbow_poles["r"], pole_angle=math.radians(-90))

    # Build a true overhand grip. The MPFB rest hand is rolled so its four
    # finger roots stack vertically; merely pitching that pose produces a fist
    # beside the bar. Rebuild the hand basis so index-to-pinky runs parallel to
    # the shaft, while the palm rises from a wrist below and behind the bar.
    desired_hand_length = Vector((0, -0.80, 0.60)).normalized()
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

        rotation_target = empty(f"{side.upper()} hand rotation target")
        rotation_target.rotation_mode = "QUATERNION"
        rotation_target.rotation_quaternion = (
            grip_basis @ source_basis.transposed()
        ).to_quaternion()
        copy_rotation = rig.pose.bones[f"hand_{side}"].constraints.new("COPY_ROTATION")
        copy_rotation.target = rotation_target
        copy_rotation.owner_space = "WORLD"
        copy_rotation.target_space = "WORLD"
        copy_rotation.mix_mode = "REPLACE"

    # Wrap the fingers around the bar without collapsing them into a fist.
    for side in ("l", "r"):
        finger_curls = {
            "index": (95, 30, 15),
            "middle": (80, 40, 45),
            "ring": (75, 45, 35),
            "pinky": (85, 30, 15),
        }
        for finger, curls in finger_curls.items():
            # These joint-specific angles were solved against the shaft: every
            # joint stays outside it, while each fingertip lands on the front-
            # lower contact surface instead of closing into an empty fist.
            for joint, degrees in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (math.radians(degrees), 0, 0)
        # Oppose the thumb across the index finger instead of leaving it
        # beside the palm as a separate hook. Twisting it sideways produces an
        # unnatural OK-sign gap, so the final grip uses flexion only.
        for joint, x_degrees in (("01", 38), ("02", 52), ("03", 36)):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = (math.radians(x_degrees), 0, 0)

    torso_controls = {}
    for bone_name, label, bottom_pitch in (
        ("pelvis", "Pelvis", 8.0),
        ("spine_01", "Lumbar", 12.0),
        ("spine_02", "Mid spine", 16.0),
        ("spine_03", "Upper spine", 20.0),
        ("neck_01", "Neck", 10.0),
        ("head", "Head", 3.0),
    ):
        control, rest_world = add_world_rotation_control(rig, bone_name, label)
        torso_controls[bone_name] = (control, rest_world, bottom_pitch)

    for frame in range(1, FRAME_END + 1):
        depth = depth_at(frame)
        rig.rotation_mode = "XYZ"
        rig.location = (0, 0.195 * depth, -0.265 * depth)
        rig.rotation_euler = (0, 0, 0)
        rig.keyframe_insert("location", frame=frame)
        rig.keyframe_insert("rotation_euler", frame=frame)

        for control, rest_world, bottom_pitch in torso_controls.values():
            control.rotation_quaternion = (
                Quaternion((1, 0, 0), math.radians(bottom_pitch * depth)) @ rest_world
            )
            control.keyframe_insert("rotation_quaternion", frame=frame)

        # A high-bar squat keeps the bar nearly vertical over the mid-foot.
        # The rig controls follow the skeleton centerline, so account for the
        # athlete's upper-back thickness and rest the shaft on the rear delts.
        bar_center = Vector((0, 0.105, 1.225 - 0.260 * depth))
        bar.location = bar_center
        bar.keyframe_insert("location", frame=frame)
        for obj in weights:
            obj.location.y = bar_center.y
            obj.location.z = bar_center.z
            obj.keyframe_insert("location", frame=frame)

        for side, sign in (("l", 1), ("r", -1)):
            # Use a shoulder-width-plus grip. The wrist stays close to the
            # shaft while the hand and curled fingers pass over it. Keeping
            # the elbows below and slightly behind the shoulders avoids the
            # wide, internally rotated "scarecrow" pose.
            # The wrist sits below and behind the shaft. The palm then rises
            # toward it, placing the knuckles above the bar and the curled
            # fingertips just beneath its surface.
            hand_targets[side].location = bar_center + Vector((0.355 * sign, 0.035, -0.018))
            elbow_poles[side].location = bar_center + Vector(
                (0.285 * sign, -0.005 + 0.090 * depth, -0.580)
            )
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].keyframe_insert("location", frame=frame)

    bpy.context.scene.frame_set(1)


def build_cameras_and_lights():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0, -4.25, 0.91), (0, 0.02, 0.84), 63),
        ("side", (4.20, -1.70, 0.94), (0, 0.04, 0.86), 65),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Human {name.title()} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera

    for name, location, energy, size, color in (
        ("Key", (-2.5, -3.0, 3.6), 820, 3.0, (0.72, 0.94, 1.0)),
        ("Fill", (2.6, -1.5, 2.4), 430, 2.4, (0.72, 0.58, 1.0)),
        ("Rim", (0.4, 2.0, 3.0), 900, 2.1, (1.0, 0.28, 0.48)),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        lamp = bpy.context.object
        lamp.name = f"Human {name} light"
        lamp.data.energy = energy
        lamp.data.shape = "DISK"
        lamp.data.size = size
        lamp.data.color = color
        look_at(lamp, Vector((0, 0, 0.82)))
    return cameras


def render_preview(cameras, output_dir):
    scene = bpy.context.scene
    preview_dir = os.path.abspath(os.path.join(os.path.dirname(output_dir), "..", "..", "..", "..", "design", "motion", "previews"))
    os.makedirs(preview_dir, exist_ok=True)
    for frame, suffix in ((1, "top"), (61, "mid"), (121, "bottom")):
        scene.frame_set(frame)
        for name, camera in cameras.items():
            scene.camera = camera
            scene.render.image_settings.file_format = "PNG"
            scene.render.filepath = os.path.join(preview_dir, f"human_squat_{name}_{suffix}.png")
            bpy.ops.render.render(write_still=True)
            print("PREVIEW", scene.render.filepath)


def render_movies(cameras, output_dir):
    scene = bpy.context.scene
    os.makedirs(output_dir, exist_ok=True)
    for name, camera in cameras.items():
        scene.camera = camera
        scene.frame_start = 1
        scene.frame_end = FRAME_END - 1
        scene.render.image_settings.file_format = "FFMPEG"
        scene.render.ffmpeg.format = "MPEG4"
        scene.render.ffmpeg.codec = "H264"
        scene.render.ffmpeg.constant_rate_factor = "MEDIUM"
        scene.render.ffmpeg.ffmpeg_preset = "GOOD"
        scene.render.ffmpeg.audio_codec = "NONE"
        scene.render.filepath = os.path.join(output_dir, f"squat_{name}.mp4")
        bpy.ops.render.render(animation=True)
        print("MOVIE", scene.render.filepath)


def render_ik_tests(rig, cameras, output_dir):
    scene = bpy.context.scene
    test_dir = os.path.abspath(os.path.join(os.path.dirname(output_dir), "..", "..", "..", "..", "design", "motion", "ik-tests"))
    os.makedirs(test_dir, exist_ok=True)
    scene.camera = cameras["front"]
    original_x = scene.render.resolution_x
    original_y = scene.render.resolution_y
    scene.render.resolution_x = 420
    scene.render.resolution_y = 420
    for left_degrees, right_degrees in ((0, 0), (90, 90), (-90, -90), (90, -90), (-90, 90), (180, 180)):
        rig.pose.bones["calf_l"].constraints["IK"].pole_angle = math.radians(left_degrees)
        rig.pose.bones["calf_r"].constraints["IK"].pole_angle = math.radians(right_degrees)
        scene.frame_set(120)
        scene.frame_set(121)
        bpy.context.view_layer.update()
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(test_dir, f"legs_l{left_degrees}_r{right_degrees}.png")
        bpy.ops.render.render(write_still=True)
        print("IKTEST", scene.render.filepath)
    rig.pose.bones["lowerarm_l"].constraints["IK"].pole_angle = math.radians(-90)
    rig.pose.bones["lowerarm_r"].constraints["IK"].pole_angle = math.radians(-90)
    finger_bones = [
        f"{finger}_{joint}_{side}"
        for side in ("l", "r")
        for finger in ("index", "middle", "ring", "pinky")
        for joint in ("01", "02", "03")
    ]
    for axis, degrees in (("x", 65), ("x", -65), ("y", 65), ("y", -65), ("z", 65), ("z", -65)):
        for bone_name in finger_bones:
            pose_bone = rig.pose.bones[bone_name]
            pose_bone.rotation_mode = "XYZ"
            values = [0.0, 0.0, 0.0]
            values[{"x": 0, "y": 1, "z": 2}[axis]] = math.radians(degrees)
            pose_bone.rotation_euler = values
        scene.frame_set(2)
        scene.frame_set(1)
        bpy.context.view_layer.update()
        scene.render.filepath = os.path.join(test_dir, f"fingers_{axis}{degrees}.png")
        bpy.ops.render.render(write_still=True)
        print("IKTEST", scene.render.filepath)
    rig.pose.bones["calf_l"].constraints["IK"].pole_angle = math.radians(-90)
    rig.pose.bones["calf_r"].constraints["IK"].pole_angle = math.radians(-90)
    scene.frame_set(1)
    for left_degrees, right_degrees in ((0, 0), (90, 90), (-90, -90), (90, -90), (-90, 90), (180, 180)):
        rig.pose.bones["lowerarm_l"].constraints["IK"].pole_angle = math.radians(left_degrees)
        rig.pose.bones["lowerarm_r"].constraints["IK"].pole_angle = math.radians(right_degrees)
        scene.frame_set(2)
        scene.frame_set(1)
        bpy.context.view_layer.update()
        scene.render.filepath = os.path.join(test_dir, f"arms_l{left_degrees}_r{right_degrees}.png")
        bpy.ops.render.render(write_still=True)
        print("IKTEST", scene.render.filepath)
    scene.render.resolution_x = original_x
    scene.render.resolution_y = original_y


def render_leg_tests(rig, cameras, output_dir):
    scene = bpy.context.scene
    test_dir = os.path.abspath(
        os.path.join(os.path.dirname(output_dir), "..", "..", "..", "..", "design", "motion", "leg-tests")
    )
    os.makedirs(test_dir, exist_ok=True)
    scene.camera = cameras["side"]
    original_x = scene.render.resolution_x
    original_y = scene.render.resolution_y
    scene.render.resolution_x = 420
    scene.render.resolution_y = 420
    scene.render.image_settings.file_format = "PNG"
    for degrees in (-135, -90, -45, 0, 45, 90, 135, 180):
        rig.pose.bones["calf_l"].constraints["IK"].pole_angle = math.radians(degrees)
        rig.pose.bones["calf_r"].constraints["IK"].pole_angle = math.radians(degrees)
        scene.frame_set(120)
        scene.frame_set(121)
        bpy.context.view_layer.update()
        scene.render.filepath = os.path.join(test_dir, f"legs_side_{degrees:+04d}.png")
        bpy.ops.render.render(write_still=True)
        print("LEGTEST", scene.render.filepath)
    scene.render.resolution_x = original_x
    scene.render.resolution_y = original_y


def main():
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    blend_path = os.path.abspath(args.blend)
    clear_scene()
    configure_scene()
    human, rig, _ = create_athlete()
    _, bar, weights = build_studio()
    animate(rig, bar, weights)
    cameras = build_cameras_and_lights()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_preview(cameras, output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)
    elif args.mode == "iktest":
        render_ik_tests(rig, cameras, output_dir)
    elif args.mode == "legtest":
        render_leg_tests(rig, cameras, output_dir)


if __name__ == "__main__":
    main()
