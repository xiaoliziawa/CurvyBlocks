package com.lirxowo.curvyblocks.world;

import java.util.List;

import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;

import net.minecraft.world.level.block.state.BlockState;

public record Curve(long id, BlockState material, List<CurvePoint> points, int thickness,
                    CrossSection section, List<CurveBend> bends, CurveGeometry geometry) {
    public Curve(long id, BlockState material, List<CurvePoint> points, int thickness, CrossSection section) {
        this(id, material, points, thickness, section, List.of());
    }

    public Curve(long id, BlockState material, List<CurvePoint> points, int thickness, CrossSection section, List<CurveBend> bends) {
        this(id, material, List.copyOf(points), thickness, section, CurveBend.compact(bends),
                new CurveGeometry(points, thickness, section, bends));
    }

    public Curve withId(long newId) {
        return new Curve(newId, material, points, thickness, section, bends, geometry);
    }

    public double diameter() {
        return thickness / CurveLimits.UNITS_PER_BLOCK;
    }

    public int materialCost(double multiplier) {
        return Math.max(1, (int) Math.ceil(geometry.length() * section.area(diameter()) * multiplier));
    }
}
