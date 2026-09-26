package com.lirxowo.curvyblocks.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

final class GpuMesh implements AutoCloseable {
    private static final Matrix4f TRANSFORM = new Matrix4f();
    private final VertexBuffer buffer;
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

    static ShaderInstance begin(RenderType type, RenderLevelStageEvent event) {
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(type.mode(), event.getModelViewMatrix(), event.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        shader.apply();
        return shader;
    }

    static void end(RenderType type, ShaderInstance shader) {
        shader.clear();
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    void draw(RenderType type, RenderLevelStageEvent event) {
        if (ready) {
            worldTransform(event);
            drawAlone(type, event);
        }
    }

    void draw(RenderType type, RenderLevelStageEvent event, Matrix4fc cameraRelativeTransform) {
        if (ready) {
            TRANSFORM.set(event.getModelViewMatrix()).mul(cameraRelativeTransform);
            drawAlone(type, event);
        }
    }

    void draw(ShaderInstance shader, RenderLevelStageEvent event) {
        if (ready) {
            worldTransform(event);
            drawBuffer(shader);
        }
    }

    void draw(ShaderInstance shader, RenderLevelStageEvent event, Matrix4fc cameraRelativeTransform) {
        if (ready) {
            TRANSFORM.set(event.getModelViewMatrix()).mul(cameraRelativeTransform);
            drawBuffer(shader);
        }
    }

    private void worldTransform(RenderLevelStageEvent event) {
        Vec3 camera = event.getCamera().getPosition();
        TRANSFORM.set(event.getModelViewMatrix()).translate((float) (origin.x - camera.x),
                (float) (origin.y - camera.y), (float) (origin.z - camera.z));
    }

    private void drawAlone(RenderType type, RenderLevelStageEvent event) {
        ShaderInstance shader = begin(type, event);
        try {
            drawBuffer(shader);
        } finally {
            end(type, shader);
        }
    }

    private void drawBuffer(ShaderInstance shader) {
        Uniform modelView = shader.MODEL_VIEW_MATRIX;
        if (modelView != null) {
            modelView.set(TRANSFORM);
            modelView.upload();
        }
        buffer.bind();
        buffer.draw();
    }

    @Override
    public void close() {
        buffer.close();
    }
}
