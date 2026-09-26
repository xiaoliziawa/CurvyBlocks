package com.lirxowo.curvyblocks.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveMath;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public final class CurveContactCache implements Consumer<Curve> {
    private static final double CONTACT_MARGIN = 1.0E-4;
    private static final int INSIDE_EFFECT = 1;
    private static final int STEP_EFFECT = 2;
    private static final Comparator<CurveContact> CONTACT_ORDER = Comparator.comparingLong(contact -> contact.curve().id());
    private final List<CurveContact> touching = new ArrayList<>();
    private final Long2IntMap appliedEffects = new Long2IntOpenHashMap();
    private CurveIndex index;
    private AABB bounds;
    private AABB expanded;
    private long revision = -1L;
    private int effectTick = Integer.MIN_VALUE;
    private boolean applyingEffects;
    private double centerX;
    private double centerY;
    private double centerZ;
    private double supportArea;
    private double supportDistance;
    private Curve supportCurve;
    private AABB supportBounds;
    private CurveContact support;
    private CurveContact body;
    private double bodyDistance;

    public void refresh(Entity entity, CurveIndex nextIndex) {
        if (applyingEffects) {
            return;
        }
        AABB nextBounds = entity.getBoundingBox();
        if (index == nextIndex && bounds == nextBounds && revision == nextIndex.revision()) {
            return;
        }
        if (index != nextIndex) {
            appliedEffects.clear();
        }
        index = nextIndex;
        bounds = nextBounds;
        revision = index.revision();
        expanded = bounds.inflate(CONTACT_MARGIN);
        centerX = (bounds.minX + bounds.maxX) * 0.5;
        centerY = (bounds.minY + bounds.maxY) * 0.5;
        centerZ = (bounds.minZ + bounds.maxZ) * 0.5;
        support = null;
        supportCurve = null;
        supportBounds = null;
        body = null;
        supportArea = -1.0;
        supportDistance = Double.POSITIVE_INFINITY;
        bodyDistance = Double.POSITIVE_INFINITY;
        touching.clear();
        index.visit(expanded, this);
        if (supportCurve != null) {
            support = contact(supportCurve, supportBounds, bounds.minY - CONTACT_MARGIN);
        }
        touching.sort(CONTACT_ORDER);
    }

    @Override
    public void accept(Curve curve) {
        AABB closestTouch = null;
        double closestTouchDistance = Double.POSITIVE_INFINITY;
        AABB closestBody = null;
        double closestBodyDistance = Double.POSITIVE_INFINITY;
        boolean solid = CurvePhysics.hasCollision(curve.material());
        CurveGeometry geometry = curve.geometry();
        List<AABB> segments = geometry.segmentBounds();
        for (int i = geometry.nextSegment(expanded, 0); i >= 0; i = geometry.nextSegment(expanded, i + 1)) {
            AABB box = segments.get(i);
            double overlapX = Math.min(bounds.maxX, box.maxX) - Math.max(bounds.minX, box.minX);
            double overlapZ = Math.min(bounds.maxZ, box.maxZ) - Math.max(bounds.minZ, box.minZ);
            if (solid && overlapX > 0.0 && overlapZ > 0.0 && Math.abs(bounds.minY - box.maxY) <= CONTACT_MARGIN) {
                double area = overlapX * overlapZ;
                double distance = CurveMath.distanceToBoxSquared(box, centerX, bounds.minY, centerZ);
                if (area > supportArea || area == supportArea && (distance < supportDistance
                        || distance == supportDistance && supportCurve != null && curve.id() < supportCurve.id())) {
                    supportArea = area;
                    supportDistance = distance;
                    supportCurve = curve;
                    supportBounds = box;
                }
            }
            double distance = CurveMath.distanceToBoxSquared(box, centerX, centerY, centerZ);
            if (distance < closestTouchDistance) {
                closestTouchDistance = distance;
                closestTouch = box;
            }
            if (bounds.minY < box.maxY - CONTACT_MARGIN && bounds.maxY > box.minY + CONTACT_MARGIN) {
                if (distance < closestBodyDistance) {
                    closestBodyDistance = distance;
                    closestBody = box;
                }
            }
        }
        if (closestTouch != null) {
            CurveContact contact = contact(curve, closestBody == null ? closestTouch : closestBody, centerY);
            touching.add(contact);
            if (closestBody != null && (closestBodyDistance < bodyDistance || closestBodyDistance == bodyDistance
                    && body != null && curve.id() < body.curve().id())) {
                bodyDistance = closestBodyDistance;
                body = contact;
            }
        }
    }

    private CurveContact contact(Curve curve, AABB box, double y) {
        BlockPos position = BlockPos.containing(Math.clamp(centerX, box.minX, Math.nextDown(box.maxX)),
                Math.clamp(y, box.minY, Math.nextDown(box.maxY)),
                Math.clamp(centerZ, box.minZ, Math.nextDown(box.maxZ)));
        return new CurveContact(curve, box, position);
    }

    public CurveContact support() {
        return support;
    }

    public CurveContact body() {
        return body;
    }

    public List<CurveContact> touching() {
        return touching;
    }

    public boolean beginInsideEffect(long curveId, int entityTick) {
        return beginEffect(curveId, entityTick, INSIDE_EFFECT);
    }

    public boolean beginStepEffect(long curveId, int entityTick) {
        return beginEffect(curveId, entityTick, STEP_EFFECT);
    }

    private boolean beginEffect(long curveId, int entityTick, int effect) {
        if (effectTick != entityTick) {
            effectTick = entityTick;
            appliedEffects.clear();
        }
        int applied = appliedEffects.get(curveId);
        if ((applied & effect) != 0) {
            return false;
        }
        appliedEffects.put(curveId, applied | effect);
        return true;
    }

    public void setApplyingEffects(boolean applyingEffects) {
        this.applyingEffects = applyingEffects;
    }
}
