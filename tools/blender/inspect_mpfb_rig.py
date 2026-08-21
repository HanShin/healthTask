"""Create a temporary MPFB athlete and print the generated game rig layout.

The scene is intentionally not saved; this is a read-only rig inspection helper.
"""

import importlib
import sys

import bpy


def dynamic_import(package_suffix, key):
    for module_name in sys.modules:
        if module_name.endswith(package_suffix):
            return getattr(importlib.import_module(module_name), key)
    raise RuntimeError(f"MPFB module is not loaded: {package_suffix}")


HumanService = dynamic_import("mpfb.services.humanservice", "HumanService")
TargetService = dynamic_import("mpfb.services.targetservice", "TargetService")
HumanObjectProperties = dynamic_import("mpfb.entities.objectproperties", "HumanObjectProperties")

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)

human = HumanService.create_human(scale=0.1)
for name, value in {
    "gender": 0.62,
    "age": 0.40,
    "muscle": 0.72,
    "weight": 0.43,
    "proportions": 0.65,
    "height": 0.55,
    "asian": 0.65,
    "caucasian": 0.35,
    "african": 0.0,
}.items():
    HumanObjectProperties.set_value(name, value, entity_reference=human)
TargetService.reapply_macro_details(human)
rig = HumanService.add_builtin_rig(human, "game_engine")

print("HUMAN", human.name, tuple(round(v, 4) for v in human.dimensions))
print("HUMAN_SCALE", tuple(round(v, 4) for v in human.scale))
print(
    "HUMAN_VERTEX_Z",
    round(min(vertex.co.z for vertex in human.data.vertices), 4),
    round(max(vertex.co.z for vertex in human.data.vertices), 4),
)
print("RIG", rig.name)
for bone in rig.data.bones:
    print(
        "BONE",
        bone.name,
        "parent=", bone.parent.name if bone.parent else "-",
        "head=", tuple(round(v, 4) for v in bone.head_local),
        "tail=", tuple(round(v, 4) for v in bone.tail_local),
    )
