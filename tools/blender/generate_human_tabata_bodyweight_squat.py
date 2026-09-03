"""Build the offline Tabata bodyweight-squat guide from the approved squat.

Run after opening the packed squat source file::

    blender -b design/motion/squat_human_sample.blend \
      --python tools/blender/generate_human_tabata_bodyweight_squat.py -- \
      --output-dir app/src/main/res/raw \
      --blend design/motion/tabata_bodyweight_squat_human_sample.blend \
      --mode preview

The lower-body and torso animation is the approved high-bar squat.  This
variant removes the barbell, resets its grip, and places relaxed open hands in
front of the chest as a readable counterbalance.  Form reference: ACE
Bodyweight Squat, https://www.acefitness.org/resources/everyone/exercise-library/135/bodyweight-squat/
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
from mathutils import Vector


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_human_dumbbell_goblet_squat import (  # noqa: E402
    prepare_approved_squat,
    squat_depth,
)
from generate_human_flat_dumbbell_press import (  # noqa: E402
    add_ik,
    configure_athlete_materials,
    empty,
)
from generate_human_one_arm_dumbbell_row import set_hand_rotation  # noqa: E402
from generate_squat_sample import FRAME_END, configure_scene, look_at  # noqa: E402


EXERCISE = "tabata_bodyweight_squat"
TOP_FRAME = 1
MID_FRAME = 61
BOTTOM_FRAME = 121
KEY_FRAMES = (TOP_FRAME, MID_FRAME, BOTTOM_FRAME)


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--blend", required=True)
    parser.add_argument(
        "--mode",
        choices=("preview", "render", "validate"),
        default="preview",
    )
    return parser.parse_args(argv)


def configure_open_hands(rig):
    """Keep every digit readable without making a rigid flat paddle."""
    for side, sign in (("l", 1.0), ("r", -1.0)):
        # Fingers point forward and the two palms face the platform.  Mirroring
        # the spread vector keeps each pinky on the outside of its hand.
        set_hand_rotation(
            rig,
            side,
            Vector((1.0 * sign, 0.0, 0.0)),
            # Keep the wrist and fingertips on one readable line.  A slight
            # upward pitch offsets the game rig's naturally dropped finger
            # chain without turning the palm away from the platform.
            Vector((0.0, -1.0, 0.11)),
            f"{side.upper()} bodyweight squat hand rotation",
        )
        for finger, curls in {
            "index": (0, 0, 0),
            "middle": (0, 0, 0),
            "ring": (0, 0, 0),
            "pinky": (0, 0, 0),
        }.items():
            for joint, degrees in zip(("01", "02", "03"), curls):
                bone = rig.pose.bones[f"{finger}_{joint}_{side}"]
                bone.rotation_mode = "XYZ"
                bone.rotation_euler = (math.radians(degrees), 0.0, 0.0)
        for joint, rotation in (
            ("01", (8, 12 * sign, 0)),
            ("02", (7, 0, 0)),
            ("03", (3, 0, 0)),
        ):
            bone = rig.pose.bones[f"thumb_{joint}_{side}"]
            bone.rotation_mode = "XYZ"
            bone.rotation_euler = tuple(math.radians(value) for value in rotation)


def animate_counterbalance_arms(rig):
    """Hold softly bent arms forward while the approved squat runs below."""
    hand_targets = {
        side: empty(f"{side.upper()} bodyweight squat hand target")
        for side in ("l", "r")
    }
    elbow_poles = {
        side: empty(f"{side.upper()} bodyweight squat elbow pole")
        for side in ("l", "r")
    }
    for side in ("l", "r"):
        add_ik(rig, f"lowerarm_{side}", hand_targets[side], elbow_poles[side])
    configure_open_hands(rig)

    for frame in range(1, FRAME_END + 1):
        depth = squat_depth(frame)
        for side, sign in (("l", 1.0), ("r", -1.0)):
            hand_targets[side].location = (
                0.128 * sign,
                -0.434 + 0.086 * depth,
                1.214 - 0.285 * depth,
            )
            elbow_poles[side].location = (
                0.305 * sign,
                -0.300 - 0.020 * depth,
                1.205 - 0.270 * depth,
            )
            hand_targets[side].keyframe_insert("location", frame=frame)
            elbow_poles[side].keyframe_insert("location", frame=frame)

    bpy.context.scene.frame_set(TOP_FRAME)
    return hand_targets


def validate_pose(rig, hand_targets):
    """Guard the counterbalance arms and open hands through every frame."""
    mirror_tolerance = 0.001
    platform = bpy.data.objects["Human platform"]
    shoes = bpy.data.objects["Human.shoes05"]
    scene = bpy.context.scene
    original_frame = scene.frame_current
    previous_arm_axes = {}
    max_arm_axis_step = 0.0
    max_wrist_target_error = 0.0
    try:
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            left = hand_targets["l"].matrix_world.translation
            right = hand_targets["r"].matrix_world.translation
            if (
                abs(left.x + right.x) > mirror_tolerance
                or abs(left.y - right.y) > mirror_tolerance
                or abs(left.z - right.z) > mirror_tolerance
            ):
                raise RuntimeError(
                    f"Frame {frame}: asymmetric counterbalance targets: "
                    f"left={tuple(left)}, right={tuple(right)}"
                )

            fingertips = {}
            for side in ("l", "r"):
                shoulder = bone_point(rig, f"upperarm_{side}")
                wrist = bone_point(rig, f"lowerarm_{side}", "tail")
                target_error = (wrist - hand_targets[side].matrix_world.translation).length
                max_wrist_target_error = max(max_wrist_target_error, target_error)
                if target_error > 0.001:
                    raise RuntimeError(
                        f"Frame {frame}: {side} wrist missed its reachable IK "
                        f"target by {target_error:.6f}m"
                    )
                forward_reach = shoulder.y - wrist.y
                wrist_lift = wrist.z - shoulder.z
                if not 0.40 <= forward_reach <= 0.43:
                    raise RuntimeError(
                        f"Frame {frame}: {side} counterbalance reach left its "
                        f"corridor: {forward_reach:.4f}m"
                    )
                if not 0.020 <= wrist_lift <= 0.045:
                    raise RuntimeError(
                        f"Frame {frame}: {side} wrist is no longer level with "
                        f"the shoulder: lift={wrist_lift:.4f}m"
                    )

                upper_axis = bone_axis(rig, f"upperarm_{side}")
                lower_axis = bone_axis(rig, f"lowerarm_{side}")
                bend = math.degrees(upper_axis.angle(lower_axis))
                if not 12.0 <= bend <= 24.0:
                    raise RuntimeError(
                        f"Frame {frame}: {side} elbow lost its soft bend: "
                        f"{bend:.2f} degrees"
                    )
                for segment, axis in (
                    ("upperarm", upper_axis),
                    ("lowerarm", lower_axis),
                ):
                    key = (side, segment)
                    if key in previous_arm_axes:
                        step = math.degrees(axis.angle(previous_arm_axes[key]))
                        max_arm_axis_step = max(max_arm_axis_step, step)
                        if step > 0.25:
                            raise RuntimeError(
                                f"Frame {frame}: {side} {segment} jumped by "
                                f"{step:.3f} degrees"
                            )
                    previous_arm_axes[key] = axis.copy()

                hand_axis = bone_axis(rig, f"hand_{side}")
                if not 0.09 <= hand_axis.z <= 0.13:
                    raise RuntimeError(
                        f"Frame {frame}: {side} wrist pitch no longer keeps "
                        f"the fingers level: z={hand_axis.z:.4f}"
                    )
                for finger in ("index", "middle", "ring", "pinky"):
                    total_curl = sum(
                        abs(
                            rig.pose.bones[
                                f"{finger}_{joint}_{side}"
                            ].rotation_euler.x
                        )
                        for joint in ("01", "02", "03")
                    )
                    if total_curl > math.radians(3.0):
                        raise RuntimeError(
                            f"Frame {frame}: {side} {finger} no longer reads "
                            "as straight and open"
                        )

                for finger in ("ring", "pinky"):
                    tip = bone_point(rig, f"{finger}_03_{side}", "tail")
                    fingertips[(side, finger)] = tip
                    tip_drop = wrist.z - tip.z
                    if tip_drop > 0.008:
                        raise RuntimeError(
                            f"Frame {frame}: {side} {finger} drooped below "
                            f"the wrist by {tip_drop:.4f}m"
                        )

            for finger in ("ring", "pinky"):
                left_tip = fingertips[("l", finger)]
                right_tip = fingertips[("r", finger)]
                if (
                    abs(left_tip.x + right_tip.x) > mirror_tolerance
                    or abs(left_tip.y - right_tip.y) > mirror_tolerance
                    or abs(left_tip.z - right_tip.z) > mirror_tolerance
                ):
                    raise RuntimeError(
                        f"Frame {frame}: mirrored {finger} fingertips diverged: "
                        f"left={tuple(left_tip)}, right={tuple(right_tip)}"
                    )

            if frame not in KEY_FRAMES:
                continue

            # The approved shoe mesh includes a soft sole that sits slightly
            # below the mathematical platform plane.  Keep that calibrated
            # contact band at the three visually distinct depths; the foot-bone
            # lock is checked on every frame below.
            depsgraph = bpy.context.evaluated_depsgraph_get()
            evaluated = shoes.evaluated_get(depsgraph)
            mesh = evaluated.to_mesh(
                preserve_all_data_layers=False,
                depsgraph=depsgraph,
            )
            try:
                shoe_vertices = [
                    evaluated.matrix_world @ vertex.co for vertex in mesh.vertices
                ]
                shoe_min_z = min(vertex.z for vertex in shoe_vertices)
            finally:
                evaluated.to_mesh_clear()
            platform_top = max(
                (platform.matrix_world @ Vector(corner)).z
                for corner in platform.bound_box
            )
            sole_contact_depth = platform_top - shoe_min_z
            if not 0.005 <= sole_contact_depth <= 0.020:
                raise RuntimeError(
                    f"Frame {frame}: shoe/platform contact left its calibrated "
                    f"band: depth={sole_contact_depth:.4f}"
                )
            for side, sign in (("l", 1.0), ("r", -1.0)):
                side_min_z = min(
                    vertex.z for vertex in shoe_vertices if vertex.x * sign > 0.0
                )
                side_contact_depth = platform_top - side_min_z
                if not 0.005 <= side_contact_depth <= 0.020:
                    raise RuntimeError(
                        f"Frame {frame}: {side} shoe/platform contact left its "
                        f"calibrated band: depth={side_contact_depth:.4f}"
                    )
    finally:
        scene.frame_set(original_frame)

    print(
        "BODYWEIGHT_SQUAT_POSE_CHECK PASS",
        "frames=1-241",
        "hands=mirrored-open-level",
        "elbows=soft-bend-continuous",
        f"max_wrist_target_error={max_wrist_target_error:.7f}m",
        f"max_arm_axis_step={max_arm_axis_step:.4f}deg",
        f"shoe_contact_frames={KEY_FRAMES}",
    )


def bone_point(rig, bone_name, endpoint="head"):
    bone = rig.pose.bones[bone_name]
    point = bone.head if endpoint == "head" else bone.tail
    return rig.matrix_world @ point


def bone_axis(rig, bone_name):
    return (
        bone_point(rig, bone_name, "tail") - bone_point(rig, bone_name)
    ).normalized()


def bone_world_rotation(rig, bone_name):
    return (
        rig.matrix_world @ rig.pose.bones[bone_name].matrix
    ).to_quaternion().normalized()


def validate_spine_and_loop(rig):
    """Reject foot slip, knee collapse, torso jumps or a bad loop seam."""
    scene = bpy.context.scene
    original_frame = scene.frame_current
    foot_baseline = {}
    previous_knee_angles = {}
    previous_spine_axes = {}
    previous_shoulders = {}
    max_foot_drift = 0.0
    max_foot_rotation_drift = 0.0
    max_knee_step = 0.0
    max_spine_axis_step = 0.0
    max_shoulder_step = 0.0
    try:
        for frame in range(1, FRAME_END + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()

            spine_axes = []
            for bone_name in ("spine_01", "spine_02", "spine_03"):
                axis = bone_axis(rig, bone_name)
                spine_axes.append(axis)
                if abs(axis.x) > 0.015:
                    raise RuntimeError(
                        f"Frame {frame}: {bone_name} has lateral lean "
                        f"x={axis.x:.4f}"
                    )
                if axis.z < 0.85:
                    raise RuntimeError(
                        f"Frame {frame}: {bone_name} left the neutral squat "
                        f"range: z={axis.z:.4f}"
                    )
                if bone_name in previous_spine_axes:
                    step = math.degrees(
                        axis.angle(previous_spine_axes[bone_name])
                    )
                    max_spine_axis_step = max(max_spine_axis_step, step)
                    if step > 0.60:
                        raise RuntimeError(
                            f"Frame {frame}: {bone_name} jumped by "
                            f"{step:.3f} degrees"
                        )
                previous_spine_axes[bone_name] = axis.copy()
            for lower, upper in zip(spine_axes, spine_axes[1:]):
                segment_angle = math.degrees(lower.angle(upper))
                if segment_angle > 13.0:
                    raise RuntimeError(
                        f"Frame {frame}: adjacent spine segments diverged by "
                        f"{segment_angle:.2f} degrees"
                    )

            left_foot = bone_point(rig, "foot_l")
            right_foot = bone_point(rig, "foot_r")
            if (
                abs(left_foot.x + right_foot.x) > 0.010
                or abs(left_foot.y - right_foot.y) > 0.010
                or abs(left_foot.z - right_foot.z) > 0.010
            ):
                raise RuntimeError(
                    f"Frame {frame}: asymmetric planted stance: "
                    f"left={tuple(left_foot)}, right={tuple(right_foot)}"
                )
            for side, sign in (("l", 1.0), ("r", -1.0)):
                foot_head = bone_point(rig, f"foot_{side}")
                foot_tail = bone_point(rig, f"foot_{side}", "tail")
                foot_rotation = bone_world_rotation(rig, f"foot_{side}")
                if frame == 1:
                    foot_baseline[side] = (
                        foot_head.copy(),
                        foot_tail.copy(),
                        foot_rotation.copy(),
                    )
                head_drift = (foot_head - foot_baseline[side][0]).length
                tail_drift = (foot_tail - foot_baseline[side][1]).length
                rotation_drift = math.degrees(
                    foot_rotation.rotation_difference(
                        foot_baseline[side][2]
                    ).angle
                )
                max_foot_drift = max(max_foot_drift, head_drift, tail_drift)
                max_foot_rotation_drift = max(
                    max_foot_rotation_drift,
                    rotation_drift,
                )
                if head_drift > 0.0025 or tail_drift > 0.0025:
                    raise RuntimeError(
                        f"Frame {frame}: {side} planted foot slipped: "
                        f"head={head_drift:.6f}m, tail={tail_drift:.6f}m"
                    )
                if rotation_drift > 0.05:
                    raise RuntimeError(
                        f"Frame {frame}: {side} foot rotated by "
                        f"{rotation_drift:.3f} degrees"
                    )

                hip = bone_point(rig, f"thigh_{side}")
                knee = bone_point(rig, f"calf_{side}")
                ankle = bone_point(rig, f"calf_{side}", "tail")
                knee_angle = math.degrees(
                    (hip - knee).angle(ankle - knee)
                )
                if not 86.0 <= knee_angle <= 180.2:
                    raise RuntimeError(
                        f"Frame {frame}: {side} knee left the squat flexion "
                        f"range: {knee_angle:.2f} degrees"
                    )
                if side in previous_knee_angles:
                    step = abs(knee_angle - previous_knee_angles[side])
                    max_knee_step = max(max_knee_step, step)
                    if step > 5.0:
                        raise RuntimeError(
                            f"Frame {frame}: {side} knee flexion jumped by "
                            f"{step:.3f} degrees"
                        )
                previous_knee_angles[side] = knee_angle

                knee_lane = knee.x * sign
                knee_forward = ankle.y - knee.y
                if not 0.095 <= knee_lane <= 0.150:
                    raise RuntimeError(
                        f"Frame {frame}: {side} knee left the toe-out lane: "
                        f"x={knee_lane:.4f}m"
                    )
                if not -0.003 <= knee_forward <= 0.165:
                    raise RuntimeError(
                        f"Frame {frame}: {side} knee folded outside the "
                        f"forward squat corridor: {knee_forward:.4f}m"
                    )

                shoulder = bone_point(rig, f"upperarm_{side}")
                if side == "r":
                    left_shoulder = previous_shoulders.get((frame, "l"))
                    if left_shoulder is not None and (
                        abs(left_shoulder.x + shoulder.x) > 0.001
                        or abs(left_shoulder.y - shoulder.y) > 0.001
                        or abs(left_shoulder.z - shoulder.z) > 0.001
                    ):
                        raise RuntimeError(
                            f"Frame {frame}: shoulder centers lost symmetry"
                        )
                previous_shoulders[(frame, side)] = shoulder.copy()
                previous_frame_shoulder = previous_shoulders.get(
                    (frame - 1, side)
                )
                if previous_frame_shoulder is not None:
                    shoulder_step = (
                        shoulder - previous_frame_shoulder
                    ).length
                    max_shoulder_step = max(
                        max_shoulder_step,
                        shoulder_step,
                    )
                    if shoulder_step > 0.012:
                        raise RuntimeError(
                            f"Frame {frame}: {side} shoulder jumped by "
                            f"{shoulder_step:.5f}m"
                        )

        snapshots = []
        for frame in (1, FRAME_END):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            snapshots.append(
                {
                    name: (bone_point(rig, name), bone_point(rig, name, "tail"))
                    for name in (
                        "head",
                        "pelvis",
                        "spine_01",
                        "spine_03",
                        "hand_l",
                        "hand_r",
                        "foot_l",
                        "foot_r",
                        "calf_l",
                        "calf_r",
                        "upperarm_l",
                        "upperarm_r",
                        "lowerarm_l",
                        "lowerarm_r",
                        "ring_03_l",
                        "ring_03_r",
                        "pinky_03_l",
                        "pinky_03_r",
                    )
                }
            )
        loop_error = max(
            (snapshots[0][name][endpoint] - snapshots[1][name][endpoint]).length
            for name in snapshots[0]
            for endpoint in (0, 1)
        )
        if loop_error > 0.0001:
            raise RuntimeError(
                f"Frame 1/241 bodyweight-squat loop error={loop_error:.6f}m"
            )
    finally:
        scene.frame_set(original_frame)

    print(
        "BODYWEIGHT_SQUAT_ALIGNMENT_CHECK PASS",
        "frames=1-241",
        "spine=neutral-continuous",
        "knees=mirrored-toe-out-lane",
        f"max_foot_drift={max_foot_drift:.7f}m",
        f"max_foot_rotation_drift={max_foot_rotation_drift:.4f}deg",
        f"max_knee_step={max_knee_step:.4f}deg",
        f"max_spine_axis_step={max_spine_axis_step:.4f}deg",
        f"max_shoulder_step={max_shoulder_step:.6f}m",
        "loop=frame-1-equals-frame-241",
    )


def build_cameras():
    cameras = {}
    for name, location, target, lens in (
        ("front", (0.0, -4.15, 0.91), (0.0, -0.02, 0.84), 63),
        ("side", (4.10, -1.48, 0.94), (0.0, -0.03, 0.86), 65),
    ):
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"Tabata bodyweight squat {name.title()} camera"
        camera.data.lens = lens
        look_at(camera, Vector(target))
        cameras[name] = camera
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
    hand_targets = animate_counterbalance_arms(rig)
    validate_pose(rig, hand_targets)
    validate_spine_and_loop(rig)
    cameras = build_cameras()
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
