"""Verify the offline motion-guide MP4 contract without external packages."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


EXPECTED_EXERCISES = (
    "squat",
    "flat_dumbbell_press",
    "one_arm_dumbbell_row",
    "shoulder_press",
    "hammer_curl",
    "dumbbell_goblet_squat",
    "dumbbell_romanian_deadlift",
    "dumbbell_bulgarian_split_squat",
    "plank",
    "push_up",
    "tabata_burpee",
    "tabata_mountain_climber",
    "tabata_bodyweight_squat",
)
EXPECTED_FILES = {
    f"{exercise}_{angle}.mp4"
    for exercise in EXPECTED_EXERCISES
    for angle in ("front", "side")
}
EXPECTED_FILE_COUNT = 26
EXPECTED_CODEC = "avc1"
EXPECTED_DURATION = 8.0
EXPECTED_FRAMES = 240
EXPECTED_FPS = 30.0
EXPECTED_SIZE = (720, 720)


def boxes(data: bytes, start: int, end: int):
    position = start
    while position + 8 <= end:
        size = struct.unpack_from(">I", data, position)[0]
        kind = data[position + 4 : position + 8].decode("ascii", "replace")
        header = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, position + 8)[0]
            header = 16
        elif size == 0:
            size = end - position
        if size < header or position + size > end:
            raise ValueError(f"invalid {kind!r} box at byte {position}")
        yield kind, position + header, position + size
        position += size


def child(data: bytes, start: int, end: int, wanted: str):
    return next((item for item in boxes(data, start, end) if item[0] == wanted), None)


def fullbox_time(data: bytes, start: int):
    version = data[start]
    if version == 0:
        return struct.unpack_from(">II", data, start + 12)
    if version == 1:
        timescale = struct.unpack_from(">I", data, start + 20)[0]
        duration = struct.unpack_from(">Q", data, start + 24)[0]
        return timescale, duration
    raise ValueError(f"unsupported full-box version: {version}")


def inspect(path: Path):
    data = path.read_bytes()
    moov = child(data, 0, len(data), "moov")
    if moov is None:
        raise ValueError("missing moov box")
    _, moov_start, moov_end = moov
    mvhd = child(data, moov_start, moov_end, "mvhd")
    if mvhd is None:
        raise ValueError("missing mvhd box")
    movie_scale, movie_duration = fullbox_time(data, mvhd[1])

    video = None
    audio_tracks = 0
    for kind, trak_start, trak_end in boxes(data, moov_start, moov_end):
        if kind != "trak":
            continue
        tkhd = child(data, trak_start, trak_end, "tkhd")
        mdia = child(data, trak_start, trak_end, "mdia")
        if tkhd is None or mdia is None:
            continue
        _, mdia_start, mdia_end = mdia
        hdlr = child(data, mdia_start, mdia_end, "hdlr")
        if hdlr is None:
            continue
        handler = data[hdlr[1] + 8 : hdlr[1] + 12].decode("ascii", "replace")
        if handler == "soun":
            audio_tracks += 1
            continue
        if handler != "vide":
            continue

        mdhd = child(data, mdia_start, mdia_end, "mdhd")
        minf = child(data, mdia_start, mdia_end, "minf")
        if mdhd is None or minf is None:
            raise ValueError("incomplete video track")
        media_scale, media_duration = fullbox_time(data, mdhd[1])
        stbl = child(data, minf[1], minf[2], "stbl")
        if stbl is None:
            raise ValueError("missing sample table")
        stsd = child(data, stbl[1], stbl[2], "stsd")
        stts = child(data, stbl[1], stbl[2], "stts")
        if stsd is None or stts is None:
            raise ValueError("missing codec or timing table")

        codec = data[stsd[1] + 12 : stsd[1] + 16].decode("ascii", "replace")
        entry_count = struct.unpack_from(">I", data, stts[1] + 4)[0]
        frames = 0
        timing_duration = 0
        position = stts[1] + 8
        for _ in range(entry_count):
            sample_count, sample_delta = struct.unpack_from(">II", data, position)
            frames += sample_count
            timing_duration += sample_count * sample_delta
            position += 8
        width_fixed, height_fixed = struct.unpack_from(">II", data, tkhd[2] - 8)
        width, height = width_fixed >> 16, height_fixed >> 16
        video = {
            "codec": codec,
            "width": width,
            "height": height,
            "frames": frames,
            "fps": frames * media_scale / timing_duration,
            "duration": media_duration / media_scale,
        }

    if video is None:
        raise ValueError("missing video track")
    video["movie_duration"] = movie_duration / movie_scale
    video["audio_tracks"] = audio_tracks
    return video


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "directory",
        type=Path,
        nargs="?",
        default=Path("app/src/main/res/raw"),
    )
    args = parser.parse_args()
    paths = sorted(args.directory.glob("*.mp4"))
    failures = []
    actual_files = {path.name for path in paths}
    if len(EXPECTED_FILES) != EXPECTED_FILE_COUNT:
        failures.append(
            f"verifier expected-set mismatch: "
            f"{len(EXPECTED_FILES)} names for count {EXPECTED_FILE_COUNT}"
        )
    if len(actual_files) != EXPECTED_FILE_COUNT:
        failures.append(
            f"expected {EXPECTED_FILE_COUNT} MP4 files, found {len(actual_files)}"
        )
    missing = sorted(EXPECTED_FILES - actual_files)
    unexpected = sorted(actual_files - EXPECTED_FILES)
    if missing:
        failures.append(f"missing MP4 files: {', '.join(missing)}")
    if unexpected:
        failures.append(f"unexpected MP4 files: {', '.join(unexpected)}")

    for path in paths:
        try:
            info = inspect(path)
            checks = (
                (info["codec"] == EXPECTED_CODEC, f"codec={info['codec']}"),
                ((info["width"], info["height"]) == EXPECTED_SIZE, f"size={info['width']}x{info['height']}"),
                (info["frames"] == EXPECTED_FRAMES, f"frames={info['frames']}"),
                (abs(info["fps"] - EXPECTED_FPS) < 0.001, f"fps={info['fps']:.3f}"),
                (abs(info["duration"] - EXPECTED_DURATION) < 0.001, f"duration={info['duration']:.3f}s"),
                (info["audio_tracks"] == 0, f"audio={info['audio_tracks']}"),
            )
            summary = ", ".join(message for _, message in checks)
            if all(ok for ok, _ in checks):
                print(f"PASS {path.name}: {summary}")
            else:
                failures.append(f"{path.name}: {summary}")
        except (OSError, StopIteration, struct.error, ValueError) as error:
            failures.append(f"{path.name}: {error}")

    if failures:
        raise SystemExit("\n".join(f"FAIL {failure}" for failure in failures))
    print(f"PASS all {len(paths)} motion-guide videos")


if __name__ == "__main__":
    main()
