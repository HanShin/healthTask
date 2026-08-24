"""Build the offline dumbbell-goblet-squat guide from the approved squat.

Run after opening the packed squat source file:

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_dumbbell_goblet_squat.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/dumbbell_goblet_squat_human_sample.blend \
      --mode preview

The lower-body animation and torso controls are the approved high-bar squat.
This generator removes only the barbell and arm controls, then builds the
front-loaded dumbbell and a two-hand cup grip around its upper head.
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
from mathutils import Quaternion, Vector


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_human_flat_dumbbell_press import (  # noqa: E402
    add_ik,
    configure_athlete_materials,
    empty,
)
from generate_human_one_arm_dumbbell_row import set_hand_rotation  # noqa: E402
from generate_squat_sample import (  # noqa: E402
    P,
    configure_scene,
    cylinder,
    look_at,
    material,
    smoothstep,
)
from motion_collision import assert_no_mesh_intersections  # noqa: E402


EXERCISE = "dumbbell_goblet_squat"
FRAME_END = 241
TOP_FRAME = 1
MID_FRAME = 61
BOTTOM_FRAME = 121

DUMBBELL_HANDLE_HEIGHT = 0.170
DUMBBELL_HEAD_RADIUS = 0.053
DUMBBELL_HEAD_THICKNESS = 0.056
DUMBBELL_HEAD_CENTER = 0.092
DUMBBELL_CAP_RADIUS = 0.025
DUMBBELL_CAP_THICKNESS = 0.006
DUMBBELL_CAP_CENTER = 0.123
DUMBBELL_HEAD_VERTICES = 8
DUMBBELL_HEAD_ROTATION = math.radians(22.5)

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

DUMBBELL_COLLIDERS = (
    "Goblet dumbbell handle",
    "Goblet dumbbell plate +1",
    "Goblet dumbbell plate -1",
    "Goblet dumbbell cap -1",
)

NON_GRIP_COLLIDERS = (
    "Goblet dumbbell plate -1",
    "Goblet dumbbell cap -1",
)

TORSO_CLEARANCE_MESHES = (
    "Human.female_sportsuit01",
    "Rigged compression shorts",
    "Human.ponytail01",
)


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


def squat_depth(frame: int) -> float:
    """One controlled repetition with readable standing and bottom holds."""
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


def remove_object(obj) -> None:
    bpy.data.objects.remove(obj, do_unlink=True)


def prepare_approved_squat(rig):
    """Keep the approved legs and torso while resetting the barbell arms."""
    required = (
        "Human",
        "Human.rig",
        "Human platform",
        "Left foot target",
        "Right foot target",
    )
    missing = [name for name in required if name not in bpy.data.objects]
    if missing:
        raise RuntimeError(
            "Open design/motion/squat_human_sample.blend before this script; "
            f"missing objects: {', '.join(missing)}"
        )

    remove_names = {
        "Human Olympic bar",
        "Human Left plate",
        "Human Left plate accent",
        "Human Right plate",
        "Human Right plate accent",
        "Left hand target",
        "Right hand target",
        "Left elbow pole",
        "Right elbow pole",
        "L hand rotation target",
        "R hand rotation target",
    }
    for obj in list(bpy.data.objects):
        if obj.name in remove_names or obj.type == "CAMERA":
            remove_object(obj)

    for bone_name in ("lowerarm_l", "lowerarm_r", "hand_l", "hand_r"):
        pose_bone = rig.pose.bones[bone_name]
        for constraint in list(pose_bone.constraints):
            pose_bone.constraints.remove(constraint)

    reset_bones = [
        f"{segment}_{side}"
        for side in ("l", "r")
        for segment in ("upperarm", "lowerarm", "hand")
    ]
    reset_bones.extend(
        f"{finger}_{joint}_{side}"
        for side in ("l", "r")
        for finger in ("thumb", "index", "middle", "ring", "pinky")
        for joint in ("01", "02", "03")
    )
    for bone_name in reset_bones:
        pose_bone = rig.pose.bones[bone_name]
        pose_bone.location = (0.0, 0.0, 0.0)
        pose_bone.rotation_mode = "QUATERNION"
        pose_bone.rotation_quaternion = Quaternion((1.0, 0.0, 0.0, 0.0))
        pose_bone.scale = (1.0, 1.0, 1.0)


def build_dumbbell():
    mats = {
        "rubber": material(
            "Goblet dumbbell rubber",
            (0.025, 0.032, 0.052, 1.0),
            metallic=0.18,
            roughness=0.34,
        ),
        "handle": material(
            "Goblet dumbbell handle",
            (0.46, 0.52, 0.60, 1.0),
            metallic=0.95,
            roughness=0.14,
        ),
        "teal": material(
            "Goblet dumbbell teal",
            P.teal,
            roughness=0.2,
            emission=P.teal,
            emission_strength=1.8,
        ),
        "violet": material(
            "Goblet dumbbell violet",
            P.violet,
            roughness=0.2,
            emission=P.violet,
            emission_strength=1.8,
        ),
    }

    root = empty("Goblet dumbbell root", display="PLAIN_AXES")
    root.empty_display_size = 0.14
    handle = cylinder(
        "Goblet dumbbell handle",
        (0, 0, 0),
        0.018,
        DUMBBELL_HANDLE_HEIGHT,
        mats["handle"],
        vertices=32,
    )
    handle.parent = root
    for end_sign, accent in ((-1, "violet"), (1, "teal")):
        plate = cylinder(
            f"Goblet dumbbell plate {end_sign:+d}",
            (0, 0, 0),
            DUMBBELL_HEAD_RADIUS,
            DUMBBELL_HEAD_THICKNESS,
            mats["rubber"],
            vertices=DUMBBELL_HEAD_VERTICES,
        )
        plate.parent = root
        plate.location = (0, 0, DUMBBELL_HEAD_CENTER * end_sign)
        plate.rotation_euler[2] = DUMBBELL_HEAD_ROTATION
        # Keep the upper gripping surface plain like the reference dumbbell;
        # a raised cap would force the extended index pads into a pinch.
        if end_sign > 0:
            continue
        cap = cylinder(
            f"Goblet dumbbell cap {end_sign:+d}",
            (0, 0, 0),
            DUMBBELL_CAP_RADIUS,
            DUMBBELL_CAP_THICKNESS,
            mats[accent],
            vertices=DUMBBELL_HEAD_VERTICES,
        )
        cap.parent = root
        cap.location = (0, 0, DUMBBELL_CAP_CENTER * end_sign)
        cap.rotation_euler[2] = DUMBBELL_HEAD_ROTATION
    return root


def configure_cup_grip(rig):
    """Wrap both palms and fingers around the vertical dumbbell's upper head."""
    for side, sign in (("l", 1.0), ("r", -1.0)):
        set_hand_rotation(
            rig,
            side,
            # Match the reference grip: the palms press into the opposing
            # sides of the upper head, the hand continues the forearm line,
            # and the fingers rise slightly inward before curling over its
            # upper edge.  This creates a deep wrap rather than a fingertip
            # perch or a flat shelf under the plate.
            Vector((0.0, -1.0, 0.0)),
            Vector((-0.12 * sign, 0.0, 0.993)),
            f"{side.upper()} goblet cup rotation",
        )
        # Fan the fingers across the head instead of giving every digit the
        # same claw-like hook.  The index and middle reach over the top plane,
        # the ring rounds the upper outside bevel with a shallow curve, and
        # the pinky rises straight from the hand edge to the upper corner.
        for finger, curls in {
            "index": (24, 34, 5),
            "middle": (28, 38, 8),
            "ring": (32, 36, 5),
            "pinky": (20, 0, 0),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                pose_bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                pose_bone.rotation_mode = "XYZ"
                pose_bone.rotation_euler = (math.radians(degrees), 0.0, 0.0)
        # Keep the pinky's middle and end joints completely straight.  Rotate
        # only its base as one rigid digit so it rises from the hand edge to
        # the upper head corner instead of curling or drooping downward.
        rig.pose.bones[f"pinky_01_{side}"].rotation_euler[1] = math.radians(
            -10 * sign
        )
        rig.pose.bones[f"pinky_01_{side}"].rotation_euler[2] = math.radians(
            -10 * sign
        )
        # Mirror thumb abduction explicitly.  The MakeHuman thumb bases are
        # mirrored, so applying the same Y rotation to both hands sends one
        # thumb over the head and the other underneath it.
        for joint, degrees in (
            ("01", (28, 25 * sign, 0)),
            ("02", (35, 0, 0)),
            ("03", (8, 0, 0)),
        ):
            pose_bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            pose_bone.rotation_mode = "XYZ"
            pose_bone.rotation_euler = tuple(
                math.radians(value) for value in degrees
            )


def animate_goblet(rig, dumbbell):
    """Keep the vertical dumbbell close to the sternum for the full squat."""
    hand_targets = {
        "l": empty("L goblet hand target"),
        "r": empty("R goblet hand target"),
    }
    elbow_poles = {
        "l": empty("L goblet elbow pole"),
        "r": empty("R goblet elbow pole"),
    }
    for side in ("l", "r"):
        add_ik(rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side])
    configure_cup_grip(rig)

    for frame in range(1, FRAME_END + 1):
        depth = squat_depth(frame)
        # The back edge of each 0.106 m head stays ahead of the shirt instead
        # of disappearing into the sternum as the torso inclines at depth.
        center = Vector((0.0, -0.255, 1.105 - 0.255 * depth))
        dumbbell.location = center
        dumbbell.keyframe_insert("location", frame=frame)

        for side, sign in (("l", 1.0), ("r", -1.0)):
            # Seat the palm against the side of the upper head, with the wrist
            # just below its center and the fingers closing over the top edge.
            hand_targets[side].location = center + Vector(
                (0.072 * sign, -0.005, 0.027)
            )
            elbow_poles[side].location = center + Vector(
                (0.195 * sign, 0.050, -0.215 + 0.020 * depth)
            )
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].keyframe_insert("location", frame=frame)

    bpy.context.scene.frame_set(TOP_FRAME)


def validate_grip_pose(rig, dumbbell):
    """Keep the reference cup grip symmetric and stop short digits drifting."""
    finger_order = ("index", "middle", "ring", "pinky")
    tolerance = 0.0005
    for frame in (TOP_FRAME, MID_FRAME, BOTTOM_FRAME):
        bpy.context.scene.frame_set(frame)
        bpy.context.view_layer.update()
        root = dumbbell.matrix_world.translation
        tips = {
            (side, finger): (
                rig.matrix_world @ rig.pose.bones[f"{finger}_03_{side}"].tail
            )
            - root
            for side in ("l", "r")
            for finger in ("thumb", *finger_order)
        }

        for finger in ("thumb", *finger_order):
            left = tips[("l", finger)]
            right = tips[("r", finger)]
            if (
                abs(left.x + right.x) > tolerance
                or abs(left.y - right.y) > tolerance
                or abs(left.z - right.z) > tolerance
            ):
                raise RuntimeError(
                    f"Frame {frame}: asymmetric {finger} grip: "
                    f"left={tuple(left)}, right={tuple(right)}"
                )

        for side in ("l", "r"):
            upper_stack = ("index", "middle", "ring")
            heights = [tips[(side, finger)].z for finger in upper_stack]
            if not all(a > b for a, b in zip(heights, heights[1:])):
                raise RuntimeError(
                    f"Frame {frame}: {side} finger contact heights are not "
                    f"index-to-ring descending: {heights}"
                )
            pinky = tips[(side, "pinky")]
            thumb = tips[(side, "thumb")]
            head_bottom = DUMBBELL_HEAD_CENTER - DUMBBELL_HEAD_THICKNESS / 2
            head_top = DUMBBELL_HEAD_CENTER + DUMBBELL_HEAD_THICKNESS / 2
            pinky_base = (
                rig.matrix_world @ rig.pose.bones[f"pinky_01_{side}"].head
            ) - root
            if not head_bottom <= pinky_base.z <= head_top:
                raise RuntimeError(
                    f"Frame {frame}: {side} pinky base left the head side: "
                    f"z={pinky_base.z:.4f}, bounds=({head_bottom:.4f}, "
                    f"{head_top:.4f})"
                )
            if not head_top <= pinky.z <= head_top + 0.010:
                raise RuntimeError(
                    f"Frame {frame}: {side} straight pinky no longer reaches "
                    f"the upper head edge: z={pinky.z:.4f}, "
                    f"target=({head_top:.4f}, {head_top + 0.010:.4f})"
                )
            if abs(pinky.x) < DUMBBELL_HEAD_RADIUS - 0.025:
                raise RuntimeError(
                    f"Frame {frame}: {side} pinky crossed too far onto the "
                    f"front face: x={pinky.x:.4f}"
                )
            ring = tips[(side, "ring")]
            if not head_top - 0.015 <= ring.z <= head_top - 0.005:
                raise RuntimeError(
                    f"Frame {frame}: {side} ring left the upper head bevel: "
                    f"z={ring.z:.4f}, target=({head_top - 0.015:.4f}, "
                    f"{head_top - 0.005:.4f})"
                )
            middle = tips[(side, "middle")]
            if not middle.y > ring.y > pinky.y:
                raise RuntimeError(
                    f"Frame {frame}: {side} ring is not layered between the "
                    f"middle and pinky: middle_y={middle.y:.4f}, "
                    f"ring_y={ring.y:.4f}, pinky_y={pinky.y:.4f}"
                )
            if not tips[(side, "middle")].z < pinky.z < tips[(side, "index")].z:
                raise RuntimeError(
                    f"Frame {frame}: {side} straight pinky is not aimed "
                    f"between the middle and index fingertips: "
                    f"middle_z={tips[(side, 'middle')].z:.4f}, "
                    f"pinky_z={pinky.z:.4f}, "
                    f"index_z={tips[(side, 'index')].z:.4f}"
                )
            if pinky.y >= ring.y:
                raise RuntimeError(
                    f"Frame {frame}: {side} pinky crossed behind the ring: "
                    f"ring_y={ring.y:.4f}, pinky_y={pinky.y:.4f}"
                )
            if not 0.006 <= pinky.z - ring.z <= 0.016:
                raise RuntimeError(
                    f"Frame {frame}: {side} ring and pinky lost their natural "
                    f"height step: ring_z={ring.z:.4f}, pinky_z={pinky.z:.4f}"
                )
            for joint in ("02", "03"):
                rotation = rig.pose.bones[f"pinky_{joint}_{side}"].rotation_euler
                if any(abs(value) > math.radians(0.1) for value in rotation):
                    raise RuntimeError(
                        f"Frame {frame}: {side} pinky {joint} is bent: "
                        f"rotation={tuple(rotation)}"
                    )
            if not head_bottom <= thumb.z <= head_top + 0.005:
                raise RuntimeError(
                    f"Frame {frame}: {side} thumb left the head side plane: "
                    f"z={thumb.z:.4f}, bounds=({head_bottom:.4f}, {head_top:.4f})"
                )
    print(
        "GRIP_POSE_CHECK PASS",
        "frames=(1, 61, 121)",
        "thumbs=mirrored",
        "fingers=index>middle>ring>pinky",
        "ring=gently-wrapped-upper-bevel",
        "pinky=straight-upward-side-support",
    )


def build_cameras():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.15, 0.91), (0.0, 0.0, 0.84), 63),
        ("side", (4.10, -1.45, 0.94), (0.0, 0.02, 0.86), 65),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Goblet squat {name.title()} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera
    return cameras


def validate_equipment_clearance():
    """Reject body penetration while allowing the intentional upper-head cup."""
    # The top head and handle are the grip surfaces. Clothing must still clear
    # every dumbbell part, while hands may meet only those two contact surfaces.
    assert_no_mesh_intersections(
        TORSO_CLEARANCE_MESHES,
        DUMBBELL_COLLIDERS,
        (TOP_FRAME, MID_FRAME, BOTTOM_FRAME),
    )
    assert_no_mesh_intersections(
        ATHLETE_MESHES,
        NON_GRIP_COLLIDERS,
        (TOP_FRAME, MID_FRAME, BOTTOM_FRAME),
    )


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
    render_grip_previews(output_dir)


def render_grip_previews(output_dir):
    scene = bpy.context.scene
    preview_dir = preview_directory(output_dir)
    scene.frame_set(TOP_FRAME)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Goblet grip inspection camera"
    camera.data.lens = 80
    scene.camera = camera
    target = Vector((0.0, -0.255, 1.19))
    for name, location in (
        ("front", (0.0, -1.45, 1.23)),
        ("angle", (1.10, -0.80, 1.48)),
        # Tight inspection views keep every fingertip large enough to verify
        # the pinky, thumb, and palm contact against the photo reference.
        ("detail_front", (0.0, -0.93, 1.22)),
        ("detail_angle", (0.58, -0.65, 1.35)),
        # An offset rear-quarter view avoids hiding the entire grip behind
        # the torso while still exposing the backs of both hands.
        ("rear", (1.15, 0.30, 1.48)),
    ):
        camera.location = location
        look_at(camera, target)
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = os.path.join(
            preview_dir,
            f"human_{EXERCISE}_grip_{name}.png",
        )
        bpy.ops.render.render(write_still=True)
        print("GRIP_PREVIEW", scene.render.filepath)


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
    rig = bpy.data.objects.get("Human.rig")
    if rig is None:
        raise RuntimeError("The approved Human.rig is missing from the source blend")
    prepare_approved_squat(rig)
    configure_athlete_materials()
    dumbbell = build_dumbbell()
    animate_goblet(rig, dumbbell)
    validate_grip_pose(rig, dumbbell)
    validate_equipment_clearance()
    cameras = build_cameras()
    bpy.context.scene.camera = cameras["front"]
    os.makedirs(os.path.dirname(blend_path), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print("BLEND", blend_path)
    if args.mode == "preview":
        render_previews(cameras, output_dir)
    elif args.mode == "grip":
        render_grip_previews(output_dir)
    elif args.mode == "render":
        render_movies(cameras, output_dir)


if __name__ == "__main__":
    main()
