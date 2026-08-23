"""Mesh collision checks shared by Blender exercise generators.

The checks run on evaluated, world-space meshes so armature deformation and
object transforms are both included. Exercise scripts should call
``assert_no_mesh_intersections`` before saving or rendering a guide that uses
a bench, chair, rack, or other equipment collider.
"""

from __future__ import annotations

from dataclasses import dataclass

import bpy
from mathutils.bvhtree import BVHTree


@dataclass(frozen=True)
class MeshIntersection:
    frame: int
    subject: str
    collider: str
    triangle_pairs: int


def _world_bvh(obj, depsgraph):
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        vertices = [evaluated.matrix_world @ vertex.co for vertex in mesh.vertices]
        polygons = [tuple(polygon.vertices) for polygon in mesh.polygons]
        return BVHTree.FromPolygons(vertices, polygons, all_triangles=False, epsilon=0.0)
    finally:
        evaluated.to_mesh_clear()


def find_mesh_intersections(subject_names, collider_names, frames):
    """Return triangle intersections for each requested animation frame."""
    scene = bpy.context.scene
    results = []
    original_frame = scene.frame_current
    try:
        for frame in frames:
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            depsgraph = bpy.context.evaluated_depsgraph_get()
            colliders = {
                name: _world_bvh(bpy.data.objects[name], depsgraph)
                for name in collider_names
            }
            for subject_name in subject_names:
                subject = _world_bvh(bpy.data.objects[subject_name], depsgraph)
                for collider_name, collider in colliders.items():
                    overlaps = subject.overlap(collider)
                    if overlaps:
                        results.append(
                            MeshIntersection(
                                frame=frame,
                                subject=subject_name,
                                collider=collider_name,
                                triangle_pairs=len(overlaps),
                            )
                        )
    finally:
        scene.frame_set(original_frame)
    return results


def assert_no_mesh_intersections(subject_names, collider_names, frames):
    """Fail generation when an animated subject penetrates equipment."""
    intersections = find_mesh_intersections(subject_names, collider_names, frames)
    if not intersections:
        print(
            "COLLISION_CHECK PASS",
            f"frames={tuple(frames)}",
            f"subjects={tuple(subject_names)}",
            f"colliders={tuple(collider_names)}",
        )
        return

    details = "; ".join(
        f"frame {item.frame}: {item.subject} x {item.collider} "
        f"({item.triangle_pairs} triangle pairs)"
        for item in intersections
    )
    raise RuntimeError(f"Equipment mesh penetration detected: {details}")
