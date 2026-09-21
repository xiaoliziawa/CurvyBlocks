package com.lirxowo.curvyblocks.client.render;

import java.util.List;

import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

final class CurveCursor implements AutoCloseable {
    private static final int RING_SEGMENTS = 64;
    private static final int RING_BUFFER_BYTES = 8192;
    private static final float INNER_RADIUS = 0.75F;
    private static final float CURSOR_RADIUS = 0.09F;
    private static final float NODE_RADIUS = 0.055F;
    private static final float NODE_OPACITY = 0.7F;
    private static final double FOLLOW_SPEED = 45.0;
    private static final double MAX_SMOOTH_DISTANCE_SQUARED = 1.0;
    private static final double MAX_FRAME_SECONDS = 0.1;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final float COLOR_CHANNEL_MAX = 255.0F;
    private static final RenderType RENDER_TYPE = RenderType.create(
            "curvyblocks_cursor", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, RING_BUFFER_BYTES,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private final GpuMesh mesh = new GpuMesh(false);
    private final Matrix4f pose = new Matrix4f();
    private boolean following;
    private long lastFrameNanos;
    private double x;
    private double y;
    private double z;

    CurveCursor(ByteBufferBuilder scratch) {
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double first = segment * Math.TAU / RING_SEGMENTS;
            double second = (segment + 1) * Math.TAU / RING_SEGMENTS;
            float firstX = (float) Math.cos(first);
            float firstY = (float) Math.sin(first);
            float secondX = (float) Math.cos(second);
            float secondY = (float) Math.sin(second);
            vertex(builder, firstX, firstY);
            vertex(builder, secondX, secondY);
            vertex(builder, secondX * INNER_RADIUS, secondY * INNER_RADIUS);
            vertex(builder, firstX, firstY);
            vertex(builder, secondX * INNER_RADIUS, secondY * INNER_RADIUS);
            vertex(builder, firstX * INNER_RADIUS, firstY * INNER_RADIUS);
        }
        mesh.upload(builder.buildOrThrow(), Vec3.ZERO);
        scratch.clear();
    }

    private static void vertex(BufferBuilder builder, float x, float y) {
        builder.addVertex(x, y, 0.0F).setColor(0xFFFFFFFF);
    }

    void draw(RenderLevelStageEvent event, CurvePoint target, List<CurvePoint> nodes, int color) {
        float[] previous = RenderSystem.getShaderColor();
        float previousRed = previous[0];
        float previousGreen = previous[1];
        float previousBlue = previous[2];
        float previousAlpha = previous[3];
        float red = (color >> 16 & 255) / COLOR_CHANNEL_MAX;
        float green = (color >> 8 & 255) / COLOR_CHANNEL_MAX;
        float blue = (color & 255) / COLOR_CHANNEL_MAX;
        try {
            RenderSystem.setShaderColor(red, green, blue, NODE_OPACITY);
            for (CurvePoint node : nodes) {
                Vec3 position = node.position();
                drawRing(event, position.x, position.y, position.z, NODE_RADIUS);
            }
            if (target == null) {
                hide();
                return;
            }
            follow(target.position());
            RenderSystem.setShaderColor(red, green, blue, 1.0F);
            drawRing(event, x, y, z, CURSOR_RADIUS);
        } finally {
            RenderSystem.setShaderColor(previousRed, previousGreen, previousBlue, previousAlpha);
        }
    }

    private void follow(Vec3 target) {
        long now = System.nanoTime();
        double dx = target.x - x;
        double dy = target.y - y;
        double dz = target.z - z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!following || distanceSquared > MAX_SMOOTH_DISTANCE_SQUARED || distanceSquared < CurveLimits.EPSILON) {
            x = target.x;
            y = target.y;
            z = target.z;
        } else {
            double seconds = Math.clamp((now - lastFrameNanos) / NANOS_PER_SECOND, 0.0, MAX_FRAME_SECONDS);
            double fraction = -Math.expm1(-FOLLOW_SPEED * seconds);
            x += dx * fraction;
            y += dy * fraction;
            z += dz * fraction;
        }
        lastFrameNanos = now;
        following = true;
    }

    private void drawRing(RenderLevelStageEvent event, double x, double y, double z, float radius) {
        Vec3 camera = event.getCamera().getPosition();
        pose.translation((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .rotate(event.getCamera().rotation()).scale(radius);
        mesh.draw(RENDER_TYPE, event, pose);
    }

    void hide() {
        following = false;
        lastFrameNanos = 0L;
    }

    @Override
    public void close() {
        mesh.close();
    }
}
