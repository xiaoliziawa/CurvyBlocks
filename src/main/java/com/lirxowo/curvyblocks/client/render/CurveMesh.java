package com.lirxowo.curvyblocks.client.render;

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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class CurveMesh implements AutoCloseable {
    static final int ROUND_SIDES = 12;
    private static final int SQUARE_SIDES = 4;
    private static final float TEXTURE_INSET = 1.0F / 1024.0F;
    private static final int PREVIEW_ALPHA = 150;
    private static final int PREVIEW_VALID = 0x9BFFE3;
    private static final int PREVIEW_INVALID = 0xFF6666;
    private static final RenderType SOLID = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    private static final RenderType TRANSLUCENT = Sheets.translucentCullBlockSheet();
    private static final int[] NO_LIGHTS = new int[0];
    private final GpuMesh mesh;
    private final boolean preview;
    private Curve curve;
    private BlockPalette palette;
    private int[] lights;
    private int[] knotLights;
    private List<CurveKnot> knots = List.of();
    private int tint = 0xFFFFFF;
    private int alpha = 255;
    private double[] horizontal;
    private double[] vertical;
    private Vec3 center;
    private Vec3 origin;
    private double cameraDistance;

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
        tint = preview ? valid ? PREVIEW_VALID : PREVIEW_INVALID : 0xFFFFFF;
        alpha = preview ? PREVIEW_ALPHA : 255;
        List<CurveGeometry.Sample> samples = curve.geometry().samples();
        lights = knotsOnly ? NO_LIGHTS : new int[samples.size()];
        for (int i = 0; i < lights.length; i++) {
            lights[i] = light(samples.get(i));
        }
        knotLights = knots.isEmpty() ? NO_LIGHTS : new int[knots.size()];
        for (int i = 0; i < knotLights.length; i++) {
            knotLights[i] = light(knots.get(i).lightPosition());
        }
    }

    void update(Curve curve, boolean valid, List<CurveKnot> knots, ByteBufferBuilder scratch) {
        prepare(curve, valid, knots, false);
        List<CurveGeometry.Sample> samples = curve.geometry().samples();
        int sides = curve.section() == CrossSection.ROUND ? ROUND_SIDES : SQUARE_SIDES;
        horizontal = new double[sides + 1];
        vertical = new double[sides + 1];
        double radius = curve.section().outerRadius(curve.diameter());
        double shift = curve.section() == CrossSection.SQUARE ? -Math.PI / 4.0 : 0.0;
        for (int side = 0; side <= sides; side++) {
            double angle = side * Math.TAU / sides + shift;
            horizontal[side] = Math.cos(angle) * radius;
            vertical[side] = Math.sin(angle) * radius;
        }
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        for (int i = 1; i < samples.size(); i++) {
            CurveGeometry.Sample start = samples.get(i - 1);
            CurveGeometry.Sample end = samples.get(i);
            double distance = start.distance();
            CurveGeometry.Sample cursor = start;
            while (distance < end.distance() - CurveLimits.EPSILON) {
                double tile = Math.floor(distance + CurveLimits.EPSILON);
                double stop = Math.min(end.distance(), tile + 1.0);
                double fraction = (stop - start.distance()) / (end.distance() - start.distance());
                CurveGeometry.Sample next = start.interpolate(end, fraction);
                strip(builder, cursor, next, (float) Math.max(0.0, distance - tile), (float) (stop - tile), lights[i - 1], lights[i]);
                cursor = next;
                distance = stop;
            }
        }
        cap(builder, samples.getFirst(), false, lights[0]);
        cap(builder, samples.getLast(), true, lights[lights.length - 1]);
        appendKnots(builder);
        mesh.upload(builder.buildOrThrow(), origin);
        scratch.clear();
    }

    void updateKnots(Curve parent, boolean valid, List<CurveKnot> knots, ByteBufferBuilder scratch) {
        prepare(parent, valid, knots, true);
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        appendKnots(builder);
        mesh.upload(builder.buildOrThrow(), origin);
        scratch.clear();
    }

    private void appendKnots(BufferBuilder builder) {
        for (int i = 0; i < knots.size(); i++) {
            CurveKnot knot = knots.get(i);
            Vec3 position = knot.position();
            double radius = knot.radius();
            int light = knotLights[i];
            for (int face = 0; face < BlockPalette.faceCount(); face++) {
                TextureAtlasSprite sprite = palette.surface(face).sprite();
                int color = palette.color(face, knot.lightPosition());
                for (KnotSphere.Vertex sample : KnotSphere.face(face)) {
                    Vec3 normal = sample.normal();
                    vertex(builder, position.x + normal.x * radius, position.y + normal.y * radius,
                            position.z + normal.z * radius, normal, sprite, sample.u(), sample.v(), color, light);
                }
            }
        }
    }

    boolean knotsMatch(List<CurveKnot> next) {
        return knots.equals(next);
    }

    boolean previewKnotsMatch(Curve parent, boolean valid, List<CurveKnot> next) {
        return curve == parent && tint == (valid ? PREVIEW_VALID : PREVIEW_INVALID) && knotsMatch(next) && !lightingChanged();
    }

    private void strip(BufferBuilder builder, CurveGeometry.Sample start, CurveGeometry.Sample end,
                       float startV, float endV, int firstLight, int secondLight) {
        int sides = horizontal.length - 1;
        int perFace = sides / SQUARE_SIDES;
        for (int side = 0; side < sides; side++) {
            int face = side / perFace;
            TextureAtlasSprite sprite = palette.surface(face).sprite();
            float firstU = (side % perFace) / (float) perFace;
            float secondU = (side % perFace + 1) / (float) perFace;
            int firstColor = palette.color(face, start.lightPosition());
            int secondColor = palette.color(face, end.lightPosition());
            Vec3 a = start.offset(horizontal[side], vertical[side]);
            Vec3 b = start.offset(horizontal[side + 1], vertical[side + 1]);
            Vec3 c = end.offset(horizontal[side + 1], vertical[side + 1]);
            Vec3 d = end.offset(horizontal[side], vertical[side]);
            Vec3 na = normal(start, side, false);
            Vec3 nb = normal(start, side, true);
            Vec3 nc = normal(end, side, true);
            Vec3 nd = normal(end, side, false);
            vertex(builder, a, na, sprite, firstU, startV, firstColor, firstLight);
            vertex(builder, b, nb, sprite, secondU, startV, firstColor, firstLight);
            vertex(builder, c, nc, sprite, secondU, endV, secondColor, secondLight);
            vertex(builder, a, na, sprite, firstU, startV, firstColor, firstLight);
            vertex(builder, c, nc, sprite, secondU, endV, secondColor, secondLight);
            vertex(builder, d, nd, sprite, firstU, endV, secondColor, secondLight);
        }
    }

    private Vec3 normal(CurveGeometry.Sample sample, int side, boolean next) {
        double x;
        double y;
        if (curve.section() == CrossSection.SQUARE) {
            x = vertical[side + 1] - vertical[side];
            y = horizontal[side] - horizontal[side + 1];
        } else {
            int index = next ? side + 1 : side;
            x = horizontal[index];
            y = vertical[index];
        }
        return sample.right().scale(x).add(sample.up().scale(y)).normalize();
    }

    private void cap(BufferBuilder builder, CurveGeometry.Sample sample, boolean end, int light) {
        int face = end ? 5 : 4;
        TextureAtlasSprite sprite = palette.surface(face).sprite();
        Vec3 normal = end ? sample.tangent() : sample.tangent().scale(-1.0);
        int color = palette.color(face, sample.lightPosition());
        for (int side = 0; side < horizontal.length - 1; side++) {
            int first = end ? side : side + 1;
            int second = end ? side + 1 : side;
            vertex(builder, sample.position(), normal, sprite, 0.5F, 0.5F, color, light);
            vertex(builder, sample.offset(horizontal[first], vertical[first]), normal, sprite,
                    (float) (0.5 + horizontal[first] / curve.diameter()),
                    (float) (0.5 + vertical[first] / curve.diameter()), color, light);
            vertex(builder, sample.offset(horizontal[second], vertical[second]), normal, sprite,
                    (float) (0.5 + horizontal[second] / curve.diameter()),
                    (float) (0.5 + vertical[second] / curve.diameter()), color, light);
        }
    }

    private void vertex(BufferBuilder builder, Vec3 position, Vec3 normal, TextureAtlasSprite sprite,
                        float u, float v, int color, int light) {
        vertex(builder, position.x, position.y, position.z, normal, sprite, u, v, color, light);
    }

    private void vertex(BufferBuilder builder, double x, double y, double z, Vec3 normal, TextureAtlasSprite sprite,
                        float u, float v, int color, int light) {
        int red = (color >> 16 & 255) * (tint >> 16 & 255) / 255;
        int green = (color >> 8 & 255) * (tint >> 8 & 255) / 255;
        int blue = (color & 255) * (tint & 255) / 255;
        builder.addVertex((float) (x - origin.x), (float) (y - origin.y), (float) (z - origin.z))
                .setColor(red, green, blue, alpha)
                .setUv(sprite.getU(Math.clamp(u, TEXTURE_INSET, 1.0F - TEXTURE_INSET)),
                        sprite.getV(Math.clamp(v, TEXTURE_INSET, 1.0F - TEXTURE_INSET)))
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    boolean lightingChanged() {
        List<CurveGeometry.Sample> samples = curve.geometry().samples();
        for (int i = 0; i < lights.length; i++) {
            if (lights[i] != light(samples.get(i))) {
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

    private int light(CurveGeometry.Sample sample) {
        return light(sample.lightPosition());
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

    void draw(RenderLevelStageEvent event) {
        if (preview) {
            drawPreviewDepth(event);
            drawPreviewColor(event);
        } else {
            mesh.draw(translucent() ? TRANSLUCENT : SOLID, event);
        }
    }

    void drawPreviewDepth(RenderLevelStageEvent event) {
        mesh.draw(CurveRenderTypes.PREVIEW_DEPTH, event);
    }

    void drawPreviewColor(RenderLevelStageEvent event) {
        mesh.draw(CurveRenderTypes.PREVIEW_COLOR, event);
    }

    @Override
    public void close() {
        mesh.close();
    }
}
