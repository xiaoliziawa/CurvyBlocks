package com.lirxowo.curvyblocks.client.render;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class KnotSphere {
    private static final int FACE_STEPS = 8;
    private static final int VERTICES_PER_QUAD = 4;
    private static final Vertex[][] FACES = build();

    private KnotSphere() {
    }

    static Vertex[] face(int face) {
        return FACES[face];
    }

    private static Vertex[][] build() {
        Vertex[][] faces = new Vertex[BlockPalette.faceCount()][];
        for (int face = 0; face < faces.length; face++) {
            Direction direction = BlockPalette.direction(face);
            Vec3 forward = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            Vec3 right = direction.getStepY() == 0 ? new Vec3(direction.getStepZ(), 0.0, -direction.getStepX()) : new Vec3(1.0, 0.0, 0.0);
            Vec3 up = forward.cross(right);
            Vertex[][] grid = new Vertex[FACE_STEPS + 1][FACE_STEPS + 1];
            for (int y = 0; y <= FACE_STEPS; y++) {
                double v = y / (double) FACE_STEPS;
                for (int x = 0; x <= FACE_STEPS; x++) {
                    double u = x / (double) FACE_STEPS;
                    Vec3 normal = forward.add(right.scale(u * 2.0 - 1.0)).add(up.scale(v * 2.0 - 1.0)).normalize();
                    grid[y][x] = new Vertex(normal, (float) u, (float) (1.0 - v));
                }
            }
            Vertex[] vertices = new Vertex[FACE_STEPS * FACE_STEPS * VERTICES_PER_QUAD];
            int index = 0;
            for (int y = 0; y < FACE_STEPS; y++) {
                for (int x = 0; x < FACE_STEPS; x++) {
                    vertices[index++] = grid[y][x];
                    vertices[index++] = grid[y][x + 1];
                    vertices[index++] = grid[y + 1][x + 1];
                    vertices[index++] = grid[y + 1][x];
                }
            }
            faces[face] = vertices;
        }
        return faces;
    }

    record Vertex(Vec3 normal, float u, float v) {
    }
}
