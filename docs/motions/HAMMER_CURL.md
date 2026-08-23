# Hammer Curl Motion Guide

The app guide uses a bilateral standing dumbbell hammer curl. It keeps the
torso still and shows one complete, controlled repetition from the front and a
front three-quarter side view.

## Reference standard

- Hold a closed neutral grip with the palms facing each other.
- Start with the dumbbells beside the thighs and the elbows extended without
  locking them aggressively.
- Keep the upper arms beside the torso; the elbows must not travel forward.
- Curl by bending only the elbows while the wrists stay neutral.
- Finish near shoulder height without shrugging, then lower under control.

Primary references:

- ACE, *Hammer Curl*: <https://www.acefitness.org/resources/everyone/exercise-library/10/hammer-curl/>
- NASM, *9 Best Arm Exercises*: <https://blog.nasm.org/workout-plans/9-best-arm-exercises/>

## Animation contract

- Duration: 8 seconds, 30 fps, 240 encoded frames.
- Bottom hold: 1.2 seconds.
- Curl: 2.4 seconds.
- Top hold: 1.2 seconds.
- Controlled lowering: 2.4 seconds.
- Final bottom hold: 0.8 seconds.
- The pelvis, torso, shoulders, and feet remain stationary.
- Both elbows may drift no more than 2.5 cm over the complete timeline.
- The wrist-to-forearm angle may not exceed 8 degrees.
- The dumbbell shaft follows the neutral hand basis: front-to-back at the
  bottom and increasingly vertical as the elbow flexes.

## Review gate

Before the final MP4 files are connected to the app, review these generated
images:

- front and side views at the bottom, middle, and top positions;
- three close views of the left-hand grip;
- the complete motion after the still poses have been approved.

The generator is `tools/blender/generate_human_hammer_curl.py` and the packed
scene is saved as `design/motion/hammer_curl_human_sample.blend`.
