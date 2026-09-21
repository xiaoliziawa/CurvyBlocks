package com.lirxowo.curvyblocks.client.render;

import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.world.Curve;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class CurveOutline implements AutoCloseable {
    private static final double OUTLINE_EXPANSION = 0.002;
    private static final int LONGITUDINAL_LINES = 4;
    private final GpuMesh mesh = new GpuMesh(true);

    void update(Curve curve, int color, ByteBufferBuilder scratch) {
        Vec3 origin = curve.points().getFirst().position();
        BufferBuilder builder = new BufferBuilder(scratch, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        double radius = curve.diameter() * 0.5 + OUTLINE_EXPANSION;
        double scale = curve.section() == CrossSection.SQUARE ? Math.sqrt(2.0) : 1.0;
        double shift = curve.section() == CrossSection.SQUARE ? -Math.PI / 4.0 : 0.0;
        for (int side = 0; side < LONGITUDINAL_LINES; side++) {
            double angle = side * Math.TAU / LONGITUDINAL_LINES + shift;
            double x = Math.cos(angle) * radius * scale;
            double y = Math.sin(angle) * radius * scale;
            Vec3 previous = null;
            for (CurveGeometry.Sample sample : curve.geometry().samples()) {
                Vec3 current = sample.offset(x, y);
                if (previous != null) {
                    line(builder, previous, current, origin, color);
                }
                previous = current;
            }
        }
        ring(builder, curve.geometry().samples().getFirst(), radius, curve.section(), origin, color);
        ring(builder, curve.geometry().samples().getLast(), radius, curve.section(), origin, color);
        mesh.upload(builder.build(), origin);
        scratch.clear();
    }

    private static void ring(BufferBuilder builder, CurveGeometry.Sample sample, double radius,
                             CrossSection section, Vec3 origin, int color) {
        int sides = section == CrossSection.ROUND ? CurveMesh.ROUND_SIDES : LONGITUDINAL_LINES;
        double scale = section == CrossSection.SQUARE ? Math.sqrt(2.0) : 1.0;
        double shift = section == CrossSection.SQUARE ? -Math.PI / 4.0 : 0.0;
        Vec3 previous = sample.offset(Math.cos(shift) * radius * scale, Math.sin(shift) * radius * scale);
        for (int side = 1; side <= sides; side++) {
            double angle = side * Math.TAU / sides + shift;
            Vec3 next = sample.offset(Math.cos(angle) * radius * scale, Math.sin(angle) * radius * scale);
            line(builder, previous, next, origin, color);
            previous = next;
        }
    }

    private static void line(BufferBuilder builder, Vec3 start, Vec3 end, Vec3 origin, int color) {
        Vec3 delta = end.subtract(start);
        if (delta.lengthSqr() < CurveLimits.EPSILON) {
            return;
        }
        Vec3 normal = delta.normalize();
        builder.addVertex((float) (start.x - origin.x), (float) (start.y - origin.y), (float) (start.z - origin.z))
                .setColor(color).setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        builder.addVertex((float) (end.x - origin.x), (float) (end.y - origin.y), (float) (end.z - origin.z))
                .setColor(color).setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    void draw(RenderLevelStageEvent event) {
        mesh.draw(RenderType.lines(), event);
    }

    @Override
    public void close() {
        mesh.close();
    }
}
