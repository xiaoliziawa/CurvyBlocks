package com.lirxowo.curvyblocks.client.render;

import java.util.Arrays;
import java.util.List;

import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveKnot;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FastColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class CurveMesh implements AutoCloseable {
    static final int ROUND_SIDES = 12;
    private static final int SQUARE_SIDES = 4;
    private static final int START_CAP_FACE = 4;
    private static final int END_CAP_FACE = 5;
    private static final float TEXTURE_INSET = 1.0F / 1024.0F;
    private static final float CAP_CENTER_UV = 0.5F;
    private static final int PREVIEW_ALPHA = 150;
    private static final int PREVIEW_VALID = 0x9BFFE3;
    private static final int PREVIEW_INVALID = 0xFF6666;
    private static final int NO_SHADING = 0xFFFFFFFF;
    private static final int[] EMPTY_INTS = new int[0];
    private static final BlockPos[] NO_CELLS = new BlockPos[0];
    private final GpuMesh mesh;
    private final boolean preview;
    private Curve curve;
    private BlockPalette palette;
    private BlockPos[] lightCells = NO_CELLS;
    private int[] cellLights = EMPTY_INTS;
    private int[] sampleCells = EMPTY_INTS;
    private int[] knotLights = EMPTY_INTS;
    private List<CurveKnot> knots = List.of();
    private int shading = NO_SHADING;
    private double[] horizontal;
    private double[] vertical;
    private double[] normalRight;
    private double[] normalUp;
    private Vec3 center;
    private Vec3 origin;
    private double cameraDistance;
    private int visibleTick;

    CurveMesh(boolean preview) {
        this.preview = preview;
        mesh = new GpuMesh(preview);
    }

    void update(Curve curve, boolean valid, ByteBufferBuilder scratch) {
        update(curve, valid, List.of(), scratch);
    }

    private void prepare(Curve curve, boolean valid, List<CurveKnot> knots, boolean knotsOnly) {
        if (this.curve == null || this.curve.material() != curve.material()) {
            palette = new BlockPalette(curve.material());
        } else {
            palette.clearColors();
        }
        this.curve = curve;
        this.knots = List.copyOf(knots);
        origin = curve.points().getFirst().position();
        AABB bounds = knotsOnly ? knots.getFirst().bounds() : curve.geometry().bounds();
        for (CurveKnot knot : knots) {
            bounds = bounds.minmax(knot.bounds());
        }
        center = bounds.getCenter();
        shading = shading(valid);
        if (knotsOnly) {
            lightCells = NO_CELLS;
            cellLights = EMPTY_INTS;
            sampleCells = EMPTY_INTS;
        } else {
            indexLightCells(curve.geometry().samples());
        }
        knotLights = knots.isEmpty() ? EMPTY_INTS : new int[knots.size()];
        for (int i = 0; i < knotLights.length; i++) {
            knotLights[i] = light(knots.get(i).lightPosition());
        }
    }

    private int shading(boolean valid) {
        return preview ? FastColor.ARGB32.color(PREVIEW_ALPHA, valid ? PREVIEW_VALID : PREVIEW_INVALID) : NO_SHADING;
    }

    private void indexLightCells(List<CurveGeometry.Sample> samples) {
        BlockPos[] cells = new BlockPos[samples.size()];
        sampleCells = new int[samples.size()];
        int count = 0;
        for (int i = 0; i < samples.size(); i++) {
            BlockPos position = samples.get(i).lightPosition();
            if (count == 0 || !cells[count - 1].equals(position)) {
                cells[count++] = position;
            }
            sampleCells[i] = count - 1;
        }
        lightCells = Arrays.copyOf(cells, count);
        cellLights = new int[count];
        for (int i = 0; i < count; i++) {
            cellLights[i] = light(lightCells[i]);
        }
    }

    private int sampleLight(int sample) {
        return cellLights[sampleCells[sample]];
    }

    void update(Curve curve, boolean valid, List<CurveKnot> knots, ByteBufferBuilder scratch) {
        prepare(curve, valid, knots, false);
        buildProfile();
        List<CurveGeometry.Sample> samples = curve.geometry().samples();
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        Ring from = new Ring();
        Ring to = new Ring();
        from.set(samples.getFirst());
        for (int i = 1; i < samples.size(); i++) {
            CurveGeometry.Sample start = samples.get(i - 1);
            CurveGeometry.Sample end = samples.get(i);
            if (from.sample != start) {
                from.set(start);
            }
            double distance = start.distance();
            while (distance < end.distance() - CurveLimits.EPSILON) {
                double tile = Math.floor(distance + CurveLimits.EPSILON);
                double stop = Math.min(end.distance(), tile + 1.0);
                double fraction = (stop - start.distance()) / (end.distance() - start.distance());
                to.set(start.interpolate(end, fraction));
                strip(builder, from, to, (float) Math.max(0.0, distance - tile), (float) (stop - tile),
                        sampleLight(i - 1), sampleLight(i));
                Ring swap = from;
                from = to;
                to = swap;
                distance = stop;
            }
        }
        to.set(samples.getFirst());
        cap(builder, to, false, sampleLight(0));
        if (from.sample != samples.getLast()) {
            from.set(samples.getLast());
        }
        cap(builder, from, true, sampleLight(samples.size() - 1));
        appendKnots(builder);
        mesh.upload(builder.buildOrThrow(), origin);
        scratch.clear();
    }

    void updateKnots(Curve parent, boolean valid, List<CurveKnot> knots, ByteBufferBuilder scratch) {
        prepare(parent, valid, knots, true);
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        appendKnots(builder);
        mesh.upload(builder.buildOrThrow(), origin);
        scratch.clear();
    }

    private void buildProfile() {
        boolean square = curve.section() == CrossSection.SQUARE;
        int sides = square ? SQUARE_SIDES : ROUND_SIDES;
        horizontal = new double[sides + 1];
        vertical = new double[sides + 1];
        normalRight = new double[sides + 1];
        normalUp = new double[sides + 1];
        double radius = curve.section().outerRadius(curve.diameter());
        double shift = square ? -Math.PI / 4.0 : 0.0;
        for (int side = 0; side <= sides; side++) {
            double angle = side * Math.TAU / sides + shift;
            horizontal[side] = Math.cos(angle) * radius;
            vertical[side] = Math.sin(angle) * radius;
        }
        for (int slot = 0; slot <= sides; slot++) {
            int side = slot % sides;
            double right = square ? vertical[side + 1] - vertical[side] : horizontal[slot];
            double up = square ? horizontal[side] - horizontal[side + 1] : vertical[slot];
            double length = Math.hypot(right, up);
            normalRight[slot] = right / length;
            normalUp[slot] = up / length;
        }
    }

    private void appendKnots(BufferBuilder builder) {
        for (int i = 0; i < knots.size(); i++) {
            CurveKnot knot = knots.get(i);
            double centerX = knot.position().x - origin.x;
            double centerY = knot.position().y - origin.y;
            double centerZ = knot.position().z - origin.z;
            double radius = knot.radius();
            int light = knotLights[i];
            for (int face = 0; face < BlockPalette.faceCount(); face++) {
                TextureAtlasSprite sprite = palette.surface(face).sprite();
                int color = shade(palette.color(face, knot.lightPosition()));
                for (KnotSphere.Vertex sample : KnotSphere.face(face)) {
                    Vec3 normal = sample.normal();
                    vertex(builder, (float) (centerX + normal.x * radius), (float) (centerY + normal.y * radius),
                            (float) (centerZ + normal.z * radius), (float) normal.x, (float) normal.y, (float) normal.z,
                            sprite, sample.u(), sample.v(), color, light);
                }
            }
        }
    }

    boolean knotsMatch(List<CurveKnot> next) {
        return knots.equals(next);
    }

    boolean previewKnotsMatch(Curve parent, boolean valid, List<CurveKnot> next) {
        return curve == parent && shading == shading(valid) && knotsMatch(next) && !lightingChanged();
    }

    private void strip(BufferBuilder builder, Ring from, Ring to, float startV, float endV, int firstLight, int secondLight) {
        int sides = horizontal.length - 1;
        int perFace = sides / SQUARE_SIDES;
        boolean square = curve.section() == CrossSection.SQUARE;
        for (int side = 0; side < sides; side++) {
            int face = side / perFace;
            TextureAtlasSprite sprite = palette.surface(face).sprite();
            float firstU = (side % perFace) / (float) perFace;
            float secondU = (side % perFace + 1) / (float) perFace;
            int secondNormal = square ? side : side + 1;
            ringVertex(builder, from, side, side, sprite, firstU, startV, from.colors[face], firstLight);
            ringVertex(builder, from, side + 1, secondNormal, sprite, secondU, startV, from.colors[face], firstLight);
            ringVertex(builder, to, side + 1, secondNormal, sprite, secondU, endV, to.colors[face], secondLight);
            ringVertex(builder, to, side, side, sprite, firstU, endV, to.colors[face], secondLight);
        }
    }

    private void cap(BufferBuilder builder, Ring ring, boolean end, int light) {
        int face = end ? END_CAP_FACE : START_CAP_FACE;
        TextureAtlasSprite sprite = palette.surface(face).sprite();
        CurveGeometry.Sample sample = ring.sample;
        Vec3 tangent = end ? sample.tangent() : sample.tangent().scale(-1.0);
        float normalX = (float) tangent.x;
        float normalY = (float) tangent.y;
        float normalZ = (float) tangent.z;
        int color = shade(palette.color(face, sample.lightPosition()));
        float centerX = (float) (sample.position().x - origin.x);
        float centerY = (float) (sample.position().y - origin.y);
        float centerZ = (float) (sample.position().z - origin.z);
        for (int side = 0; side < horizontal.length - 1; side += 2) {
            vertex(builder, centerX, centerY, centerZ, normalX, normalY, normalZ, sprite, CAP_CENTER_UV, CAP_CENTER_UV, color, light);
            capVertex(builder, ring, end ? side : side + 2, normalX, normalY, normalZ, sprite, color, light);
            capVertex(builder, ring, side + 1, normalX, normalY, normalZ, sprite, color, light);
            capVertex(builder, ring, end ? side + 2 : side, normalX, normalY, normalZ, sprite, color, light);
        }
    }

    private void capVertex(BufferBuilder builder, Ring ring, int corner, float normalX, float normalY, float normalZ,
                           TextureAtlasSprite sprite, int color, int light) {
        int position = corner * 3;
        vertex(builder, ring.positions[position], ring.positions[position + 1], ring.positions[position + 2],
                normalX, normalY, normalZ, sprite, (float) (CAP_CENTER_UV + horizontal[corner] / curve.diameter()),
                (float) (CAP_CENTER_UV + vertical[corner] / curve.diameter()), color, light);
    }

    private static void ringVertex(BufferBuilder builder, Ring ring, int corner, int normal, TextureAtlasSprite sprite,
                                   float u, float v, int color, int light) {
        int position = corner * 3;
        int direction = normal * 3;
        vertex(builder, ring.positions[position], ring.positions[position + 1], ring.positions[position + 2],
                ring.normals[direction], ring.normals[direction + 1], ring.normals[direction + 2], sprite, u, v, color, light);
    }

    private static void vertex(BufferBuilder builder, float x, float y, float z, float normalX, float normalY, float normalZ,
                               TextureAtlasSprite sprite, float u, float v, int color, int light) {
        builder.addVertex(x, y, z)
                .setColor(color)
                .setUv(sprite.getU(inset(u)), sprite.getV(inset(v)))
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(normalX, normalY, normalZ);
    }

    private static float inset(float value) {
        return Math.clamp(value, TEXTURE_INSET, 1.0F - TEXTURE_INSET);
    }

    private int shade(int color) {
        return FastColor.ARGB32.multiply(FastColor.ARGB32.opaque(color), shading);
    }

    boolean lightingChanged() {
        for (int i = 0; i < lightCells.length; i++) {
            if (cellLights[i] != light(lightCells[i])) {
                return true;
            }
        }
        for (int i = 0; i < knotLights.length; i++) {
            if (knotLights[i] != light(knots.get(i).lightPosition())) {
                return true;
            }
        }
        return false;
    }

    private int light(BlockPos position) {
        return LevelRenderer.getLightColor(Minecraft.getInstance().level, curve.material(), position);
    }

    boolean translucent() {
        return palette.translucent();
    }

    Curve curve() {
        return curve;
    }

    void prepareSort(Vec3 camera) {
        cameraDistance = center.distanceToSqr(camera);
    }

    double cameraDistance() {
        return cameraDistance;
    }

    int visibleTick() {
        return visibleTick;
    }

    void markVisible(int tick) {
        visibleTick = tick;
    }

    void draw(ShaderInstance shader, RenderLevelStageEvent event) {
        mesh.draw(shader, event);
    }

    @Override
    public void close() {
        mesh.close();
    }

    private final class Ring {
        private final float[] positions = new float[horizontal.length * 3];
        private final float[] normals = new float[horizontal.length * 3];
        private final int[] colors = new int[SQUARE_SIDES];
        private CurveGeometry.Sample sample;

        private void set(CurveGeometry.Sample next) {
            sample = next;
            Vec3 right = next.right();
            Vec3 up = next.up();
            double x = next.position().x - origin.x;
            double y = next.position().y - origin.y;
            double z = next.position().z - origin.z;
            for (int corner = 0; corner < horizontal.length; corner++) {
                int index = corner * 3;
                positions[index] = (float) (x + right.x * horizontal[corner] + up.x * vertical[corner]);
                positions[index + 1] = (float) (y + right.y * horizontal[corner] + up.y * vertical[corner]);
                positions[index + 2] = (float) (z + right.z * horizontal[corner] + up.z * vertical[corner]);
                normals[index] = (float) (right.x * normalRight[corner] + up.x * normalUp[corner]);
                normals[index + 1] = (float) (right.y * normalRight[corner] + up.y * normalUp[corner]);
                normals[index + 2] = (float) (right.z * normalRight[corner] + up.z * normalUp[corner]);
            }
            for (int face = 0; face < colors.length; face++) {
                colors[face] = shade(palette.color(face, next.lightPosition()));
            }
        }
    }
}
