package com.lirxowo.curvyblocks.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.lirxowo.curvyblocks.client.CurveEditor;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveJoints;
import com.lirxowo.curvyblocks.world.CurveKnot;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
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
    private final Long2ObjectMap<CurveMesh> previewKnots = new Long2ObjectOpenHashMap<>();
    private final LongSet dirtyLights = new LongOpenHashSet();
    private final List<CurveMesh> opaque = new ArrayList<>();
    private final List<CurveMesh> transparent = new ArrayList<>();
    private final List<CurveMesh> previews = new ArrayList<>();
    private ByteBufferBuilder scratch;
    private CurveMesh preview;
    private CurveOutline outline;
    private CurveCursor cursor;
    private int lastPreviewRevision = -1;
    private int lastOutlineRevision = -1;
    private long lastJointRevision = -1L;
    private long lastOutlineJointRevision = -1L;
    private List<CurveKnot> previewKnotShapes = List.of();
    private long selectedId = -1L;
    private int lightTick;
    private int renderTick;

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
                drawBatch(event, transparent, CurveRenderTypes.TRANSLUCENT);
            } else if (editor.enabled() && Minecraft.getInstance().screen == null) {
                drawEditor(event, editor, index);
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
        opaque.clear();
        transparent.clear();
        int builds = 0;
        Vec3 camera = event.getCamera().getPosition();
        CurveJoints joints = index.joints();
        for (Curve curve : index.all()) {
            if (!event.getFrustum().isVisible(joints.bounds(curve))) {
                continue;
            }
            long id = curve.id();
            List<CurveKnot> knots = joints.forCurve(id);
            CurveMesh mesh = meshes.get(id);
            if (mesh == null) {
                if (builds >= MESH_BUILDS_PER_FRAME) {
                    continue;
                }
                mesh = new CurveMesh(false);
                mesh.update(curve, true, knots, scratch());
                meshes.put(id, mesh);
                builds++;
            } else {
                if (renderTick - mesh.visibleTick() > LIGHT_CHECK_INTERVAL && mesh.lightingChanged()) {
                    dirtyLights.add(id);
                }
                if ((dirtyLights.contains(id) || !mesh.knotsMatch(knots)) && builds < MESH_BUILDS_PER_FRAME) {
                    mesh.update(curve, true, knots, scratch());
                    dirtyLights.remove(id);
                    builds++;
                }
            }
            mesh.markVisible(renderTick);
            if (mesh.translucent()) {
                mesh.prepareSort(camera);
                transparent.add(mesh);
            } else {
                opaque.add(mesh);
            }
        }
        transparent.sort(BACK_TO_FRONT);
        drawBatch(event, opaque, CurveRenderTypes.SOLID);
    }

    private static void drawBatch(RenderLevelStageEvent event, List<CurveMesh> batch, RenderType type) {
        if (batch.isEmpty()) {
            return;
        }
        ShaderInstance shader = GpuMesh.begin(type, event);
        try {
            for (CurveMesh mesh : batch) {
                mesh.draw(shader, event);
            }
        } finally {
            GpuMesh.end(type, shader);
        }
    }

    private void drawEditor(RenderLevelStageEvent event, CurveEditor editor, CurveIndex index) {
        Curve draft = editor.preview();
        CurveJoints joints = index.joints();
        long jointRevision = joints.revision();
        if (draft != null) {
            if (preview == null) {
                preview = new CurveMesh(true);
            }
            boolean changed = lastPreviewRevision != editor.previewRevision();
            if (changed) {
                preview.update(draft, editor.valid(), scratch());
                lastPreviewRevision = editor.previewRevision();
            }
            if (changed || lastJointRevision != jointRevision) {
                updatePreviewKnots(index, draft, editor.valid());
                lastJointRevision = jointRevision;
            }
            previews.clear();
            previews.add(preview);
            previews.addAll(previewKnots.values());
            drawBatch(event, previews, CurveRenderTypes.PREVIEW_DEPTH);
            drawBatch(event, previews, CurveRenderTypes.PREVIEW_COLOR);
        } else {
            clearPreviewKnots();
        }
        Curve selected = draft != null ? draft : editor.hit() != null ? editor.hit().curve() : null;
        int color = editor.routing() ? ROUTING_COLOR : draft == null && selected != null ? SELECTED_COLOR
                : editor.valid() ? VALID_COLOR : INVALID_COLOR;
        if (selected != null) {
            if (outline == null) {
                outline = new CurveOutline();
            }
            if (lastOutlineRevision != editor.previewRevision() || selectedId != selected.id()
                    || lastOutlineJointRevision != jointRevision) {
                outline.update(selected, draft != null ? previewKnotShapes : joints.forCurve(selected.id()), color, scratch());
                selectedId = selected.id();
                lastOutlineRevision = editor.previewRevision();
                lastOutlineJointRevision = jointRevision;
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

    private void updatePreviewKnots(CurveIndex index, Curve draft, boolean valid) {
        Long2ObjectMap<List<CurveKnot>> groups = index.joints().preview(draft);
        List<CurveKnot> shapes = new ArrayList<>();
        for (Long2ObjectMap.Entry<List<CurveKnot>> entry : groups.long2ObjectEntrySet()) {
            long id = entry.getLongKey();
            Curve parent = index.get(id);
            CurveMesh mesh = previewKnots.get(id);
            if (mesh == null) {
                mesh = new CurveMesh(true);
                previewKnots.put(id, mesh);
            }
            if (!mesh.previewKnotsMatch(parent, valid, entry.getValue())) {
                mesh.updateKnots(parent, valid, entry.getValue(), scratch());
            }
            shapes.addAll(entry.getValue());
        }
        LongIterator iterator = previewKnots.keySet().iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextLong();
            if (!groups.containsKey(id)) {
                previewKnots.get(id).close();
                iterator.remove();
            }
        }
        previewKnotShapes = List.copyOf(shapes);
    }

    private void clearPreviewKnots() {
        if (previewKnots.isEmpty()) {
            return;
        }
        for (CurveMesh mesh : previewKnots.values()) {
            mesh.close();
        }
        previewKnots.clear();
        previewKnotShapes = List.of();
    }

    public void tick() {
        renderTick++;
        lightTick = (lightTick + 1) % LIGHT_CHECK_INTERVAL;
        for (CurveMesh mesh : meshes.values()) {
            long id = mesh.curve().id();
            if (id % LIGHT_CHECK_INTERVAL == lightTick && renderTick - mesh.visibleTick() <= LIGHT_CHECK_INTERVAL
                    && !dirtyLights.contains(id) && mesh.lightingChanged()) {
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
        opaque.clear();
        transparent.clear();
        previews.clear();
        dirtyLights.clear();
        clearPreviewKnots();
        BlockPalette.clearAppearances();
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
        lastJointRevision = -1L;
        lastOutlineJointRevision = -1L;
        selectedId = -1;
    }
}
