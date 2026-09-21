package com.lirxowo.curvyblocks.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.lirxowo.curvyblocks.client.CurveEditor;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class CurveRenderer {
    private static final int INITIAL_BUFFER_BYTES = 65536;
    private static final int MESH_BUILDS_PER_FRAME = 4;
    private static final int LIGHT_CHECK_INTERVAL = 20;
    private static final int VALID_COLOR = 0xFF68F7D2;
    private static final int INVALID_COLOR = 0xFFFF6262;
    private static final int SELECTED_COLOR = 0xFFFFD67C;
    private static final int ROUTING_COLOR = 0xFFFFBE55;
    private static final Comparator<CurveMesh> BACK_TO_FRONT = Comparator.comparingDouble(CurveMesh::cameraDistance).reversed();
    private final Long2ObjectMap<CurveMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final LongSet dirtyLights = new LongOpenHashSet();
    private final List<CurveMesh> transparent = new ArrayList<>();
    private ByteBufferBuilder scratch;
    private CurveMesh preview;
    private CurveOutline outline;
    private CurveCursor cursor;
    private int lastPreviewRevision = -1;
    private int lastOutlineRevision = -1;
    private long selectedId = -1L;
    private int lightTick;

    public void render(RenderLevelStageEvent event, CurveIndex index, CurveEditor editor) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS
                && event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS
                && event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
        float[] shaderColor = RenderSystem.getShaderColor();
        float red = shaderColor[0];
        float green = shaderColor[1];
        float blue = shaderColor[2];
        float alpha = shaderColor[3];
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS) {
                prepareAndDraw(event, index);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
                for (CurveMesh mesh : transparent) {
                    mesh.draw(event);
                }
            } else if (editor.enabled() && Minecraft.getInstance().screen == null) {
                drawEditor(event, editor);
            } else if (cursor != null) {
                cursor.hide();
            }
        } finally {
            RenderSystem.setShaderColor(red, green, blue, alpha);
        }
    }

    private ByteBufferBuilder scratch() {
        if (scratch == null) {
            scratch = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
        }
        return scratch;
    }

    private void prepareAndDraw(RenderLevelStageEvent event, CurveIndex index) {
        transparent.clear();
        int builds = 0;
        Vec3 camera = event.getCamera().getPosition();
        for (Curve curve : index.all()) {
            if (!event.getFrustum().isVisible(curve.geometry().bounds())) {
                continue;
            }
            CurveMesh mesh = meshes.get(curve.id());
            if (mesh == null) {
                if (builds >= MESH_BUILDS_PER_FRAME) {
                    continue;
                }
                mesh = new CurveMesh(false);
                mesh.update(curve, true, scratch());
                meshes.put(curve.id(), mesh);
                builds++;
            } else if (dirtyLights.contains(curve.id()) && builds < MESH_BUILDS_PER_FRAME) {
                mesh.update(curve, true, scratch());
                dirtyLights.remove(curve.id());
                builds++;
            }
            if (mesh.translucent()) {
                mesh.prepareSort(camera);
                transparent.add(mesh);
            } else {
                mesh.draw(event);
            }
        }
        transparent.sort(BACK_TO_FRONT);
    }

    private void drawEditor(RenderLevelStageEvent event, CurveEditor editor) {
        Curve draft = editor.preview();
        if (draft != null) {
            if (preview == null) {
                preview = new CurveMesh(true);
            }
            if (lastPreviewRevision != editor.previewRevision()) {
                preview.update(draft, editor.valid(), scratch());
                lastPreviewRevision = editor.previewRevision();
            }
            preview.draw(event);
        }
        Curve selected = draft != null ? draft : editor.hit() != null ? editor.hit().curve() : null;
        int color = editor.routing() ? ROUTING_COLOR : draft == null && selected != null ? SELECTED_COLOR
                : editor.valid() ? VALID_COLOR : INVALID_COLOR;
        if (selected != null) {
            if (outline == null) {
                outline = new CurveOutline();
            }
            if (lastOutlineRevision != editor.previewRevision() || selectedId != selected.id()) {
                outline.update(selected, color, scratch());
                selectedId = selected.id();
                lastOutlineRevision = editor.previewRevision();
            }
            outline.draw(event);
        }
        CurvePoint target = editor.target();
        List<CurvePoint> nodes = !editor.points().isEmpty() ? editor.points()
                : selected != null ? selected.points() : List.of();
        if (target == null && nodes.isEmpty()) {
            if (cursor != null) {
                cursor.hide();
            }
            return;
        }
        if (cursor == null) {
            cursor = new CurveCursor(scratch());
        }
        cursor.draw(event, target, nodes, color);
    }

    public void tick() {
        lightTick = (lightTick + 1) % LIGHT_CHECK_INTERVAL;
        for (CurveMesh mesh : meshes.values()) {
            long id = mesh.curve().id();
            if (id % LIGHT_CHECK_INTERVAL == lightTick && !dirtyLights.contains(id) && mesh.lightingChanged()) {
                dirtyLights.add(id);
            }
        }
    }

    public void remove(long id) {
        CurveMesh mesh = meshes.remove(id);
        if (mesh != null) {
            transparent.remove(mesh);
            mesh.close();
        }
        dirtyLights.remove(id);
    }

    public void reset() {
        for (CurveMesh mesh : meshes.values()) {
            mesh.close();
        }
        meshes.clear();
        transparent.clear();
        dirtyLights.clear();
        if (preview != null) {
            preview.close();
            preview = null;
        }
        if (outline != null) {
            outline.close();
            outline = null;
        }
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
        lastPreviewRevision = -1;
        lastOutlineRevision = -1;
        selectedId = -1;
    }
}
