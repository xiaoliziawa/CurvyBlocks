package com.lirxowo.curvyblocks.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

final class GpuMesh implements AutoCloseable {
    private final VertexBuffer buffer;
    private final Matrix4f transform = new Matrix4f();
    private Vec3 origin = Vec3.ZERO;
    private boolean ready;

    GpuMesh(boolean dynamic) {
        buffer = new VertexBuffer(dynamic ? VertexBuffer.Usage.DYNAMIC : VertexBuffer.Usage.STATIC);
    }

    void upload(MeshData data, Vec3 meshOrigin) {
        ready = data != null;
        if (!ready) {
            return;
        }
        origin = meshOrigin;
        buffer.bind();
        try {
            buffer.upload(data);
        } finally {
            VertexBuffer.unbind();
        }
    }

    void draw(RenderType type, RenderLevelStageEvent event) {
        if (!ready) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        transform.set(event.getModelViewMatrix()).translate((float) (origin.x - camera.x),
                (float) (origin.y - camera.y), (float) (origin.z - camera.z));
        drawBuffer(type, event);
    }

    void draw(RenderType type, RenderLevelStageEvent event, Matrix4fc cameraRelativeTransform) {
        if (!ready) {
            return;
        }
        transform.set(event.getModelViewMatrix()).mul(cameraRelativeTransform);
        drawBuffer(type, event);
    }

    private void drawBuffer(RenderType type, RenderLevelStageEvent event) {
        type.setupRenderState();
        buffer.bind();
        try {
            buffer.drawWithShader(transform, event.getProjectionMatrix(), RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    @Override
    public void close() {
        buffer.close();
    }
}
