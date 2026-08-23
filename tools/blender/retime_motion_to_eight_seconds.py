"""Retime an approved 180-frame Blender motion to an 8-second loop.

This preserves every pose and interpolation curve while scaling the complete
timeline from frames 1..180 to 1..241. Frame 241 remains the duplicated loop
endpoint and the movies encode frames 1..240 at 30 fps.

Example:

    blender -b design/motion/flat_dumbbell_press_human_sample.blend \
      --python tools/blender/retime_motion_to_eight_seconds.py -- \
      --blend design/motion/flat_dumbbell_press_human_sample.blend \
      --output-dir app/src/main/res/raw \
      --prefix flat_dumbbell_press \
      --front-camera "Press Front camera" \
      --side-camera "Press Side camera"
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

import bpy


SOURCE_END = 180
TARGET_END = 241
MOVIE_END = TARGET_END - 1
FPS = 30


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--blend", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--front-camera", required=True)
    parser.add_argument("--side-camera", required=True)
    return parser.parse_args(argv)


def retime_actions():
    def action_fcurves(action):
        # Blender 4.x exposes legacy actions directly through action.fcurves.
        # Blender 5.x stores them in layered action strip channel bags.
        legacy = getattr(action, "fcurves", None)
        if legacy is not None:
            yield from legacy
            return
        for layer in action.layers:
            for strip in layer.strips:
                for channelbag in strip.channelbags:
                    yield from channelbag.fcurves

    scale = (TARGET_END - 1) / (SOURCE_END - 1)
    changed_points = 0
    original_max = 0.0
    for action in bpy.data.actions:
        for fcurve in action_fcurves(action):
            for point in fcurve.keyframe_points:
                original_x = point.co.x
                original_max = max(original_max, original_x)
                if original_x < 1.0 or original_x > SOURCE_END + 0.001:
                    continue
                left_x = point.handle_left.x
                right_x = point.handle_right.x
                point.co.x = 1.0 + (original_x - 1.0) * scale
                point.handle_left.x = 1.0 + (left_x - 1.0) * scale
                point.handle_right.x = 1.0 + (right_x - 1.0) * scale
                changed_points += 1

    if changed_points == 0:
        raise RuntimeError("No 1..180 animation keyframes were found to retime")
    if original_max > SOURCE_END + 0.001:
        raise RuntimeError(
            f"Animation already extends beyond frame {SOURCE_END}: {original_max:.3f}"
        )
    print(
        "RETIME PASS",
        f"points={changed_points}",
        f"scale={scale:.6f}",
        f"source=1..{SOURCE_END}",
        f"target=1..{TARGET_END}",
    )


def render_movies(cameras, output_dir, prefix):
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.frame_start = 1
    scene.frame_end = MOVIE_END
    os.makedirs(output_dir, exist_ok=True)
    for name, camera in cameras.items():
        scene.camera = camera
        movie_path = os.path.join(output_dir, f"{prefix}_{name}.mp4")
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
                prefix=f"healthtask-retime-{prefix}-{name}-"
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


def main():
    args = parse_args()
    cameras = {
        "front": bpy.data.objects.get(args.front_camera),
        "side": bpy.data.objects.get(args.side_camera),
    }
    missing = [name for name, camera in cameras.items() if camera is None]
    if missing:
        raise RuntimeError(f"Missing cameras: {', '.join(missing)}")
    if any(camera.type != "CAMERA" for camera in cameras.values()):
        raise RuntimeError("Every named view must be a camera object")

    retime_actions()
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = TARGET_END
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.abspath(args.blend))
    print("BLEND", os.path.abspath(args.blend))
    render_movies(cameras, os.path.abspath(args.output_dir), args.prefix)


if __name__ == "__main__":
    main()
