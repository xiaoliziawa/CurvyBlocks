package com.lirxowo.curvyblocks.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.lirxowo.curvyblocks.CurvyBlocks;
import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

public final class CurveSavedData extends SavedData {
    private static final int FORMAT_VERSION = 3;
    private static final Factory<CurveSavedData> FACTORY = new Factory<>(CurveSavedData::new, CurveSavedData::load);
    private final CurveIndex index = new CurveIndex();
    private final Long2ObjectMap<StoredCurve> entries = new Long2ObjectOpenHashMap<>();
    private long nextId = 1L;

    public static CurveSavedData get(ServerLevel level) {
        CurveSavedData data = level.getDataStorage().computeIfAbsent(FACTORY, CurvyBlocks.MODID);
        if (CurveWorlds.get(level) != data.index) {
            CurveWorlds.register(level, data.index);
        }
        return data;
    }

    public CurveIndex index() {
        return index;
    }

    public Curve add(Curve draft, UUID owner, ItemStack material, int paidCost) {
        if (nextId == Long.MAX_VALUE) {
            throw new IllegalStateException("Curve IDs exhausted");
        }
        Curve curve = draft.withId(nextId++);
        entries.put(curve.id(), new StoredCurve(curve, owner, material.copyWithCount(1), paidCost));
        index.put(curve);
        setDirty();
        return curve;
    }

    public StoredCurve get(long id) {
        return entries.get(id);
    }

    public StoredCurve remove(long id) {
        StoredCurve removed = entries.remove(id);
        if (removed != null) {
            index.remove(id);
            setDirty();
        }
        return removed;
    }

    private static CurveSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CurveSavedData data = new CurveSavedData();
        ListTag savedCurves = tag.getList("curves", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedCurves.size(); i++) {
            CompoundTag saved = savedCurves.getCompound(i);
            try {
                long id = saved.getLong("id");
                ItemStack material = ItemStack.parseOptional(registries, saved.getCompound("item"));
                BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), saved.getCompound("state"));
                if (id <= 0 || id == Long.MAX_VALUE || material.isEmpty() || state.isAir() || data.entries.containsKey(id)) {
                    throw new IllegalArgumentException("Missing material or invalid curve ID");
                }
                int sectionId = saved.getByte("section");
                if (sectionId < 0 || sectionId >= CrossSection.values().length) {
                    throw new IllegalArgumentException("Invalid curve section");
                }
                ListTag pointTags = saved.getList("points", Tag.TAG_COMPOUND);
                if (pointTags.size() < 2 || pointTags.size() > CurveLimits.MAX_POINTS) {
                    throw new IllegalArgumentException("Invalid curve point count");
                }
                List<CurvePoint> points = new ArrayList<>(pointTags.size());
                for (int p = 0; p < pointTags.size(); p++) {
                    CompoundTag point = pointTags.getCompound(p);
                    points.add(new CurvePoint(new Vec3(point.getDouble("x"), point.getDouble("y"), point.getDouble("z")),
                            new Vec3(point.getDouble("nx"), point.getDouble("ny"), point.getDouble("nz")), point.getLong("parent")));
                }
                Curve curve = new Curve(id, state, points, saved.getInt("thickness"), CrossSection.values()[sectionId],
                        loadBends(saved.getList("bends", Tag.TAG_COMPOUND), points.size() - 1));
                data.entries.put(id, new StoredCurve(curve, saved.getUUID("owner"), material,
                        Math.max(0, saved.getInt("paidCost"))));
                data.index.put(curve);
                data.nextId = Math.max(data.nextId, id + 1L);
            } catch (RuntimeException exception) {
                CurvyBlocks.LOGGER.warn("Skipping invalid saved curve {}", saved.getLong("id"), exception);
            }
        }
        data.nextId = Math.max(data.nextId, tag.getLong("nextId"));
        return data;
    }

    private static List<CurveBend> loadBends(ListTag tags, int spanCount) {
        if (tags.isEmpty()) {
            return List.of();
        }
        if (tags.size() > spanCount) {
            throw new IllegalArgumentException("Invalid saved bend count");
        }
        List<CurveBend> bends = new ArrayList<>(Collections.nCopies(spanCount, CurveBend.NONE));
        boolean[] seen = new boolean[spanCount];
        for (int i = 0; i < tags.size(); i++) {
            CompoundTag tag = tags.getCompound(i);
            int span = tag.getInt("span");
            if (span < 0 || span >= spanCount || seen[span]) {
                throw new IllegalArgumentException("Invalid saved bend span");
            }
            seen[span] = true;
            bends.set(span, new CurveBend(new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")), tag.getDouble("center")));
        }
        return CurveBend.compact(bends);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("format", FORMAT_VERSION);
        tag.putLong("nextId", nextId);
        ListTag savedCurves = new ListTag();
        for (StoredCurve entry : entries.values()) {
            Curve curve = entry.curve();
            CompoundTag saved = new CompoundTag();
            saved.putLong("id", curve.id());
            saved.putUUID("owner", entry.owner());
            saved.putInt("paidCost", entry.paidCost());
            saved.put("item", entry.material().save(registries));
            saved.put("state", NbtUtils.writeBlockState(curve.material()));
            saved.putInt("thickness", curve.thickness());
            saved.putByte("section", (byte) curve.section().ordinal());
            ListTag pointTags = new ListTag();
            for (CurvePoint point : curve.points()) {
                CompoundTag pointTag = new CompoundTag();
                pointTag.putDouble("x", point.position().x);
                pointTag.putDouble("y", point.position().y);
                pointTag.putDouble("z", point.position().z);
                pointTag.putDouble("nx", point.normal().x);
                pointTag.putDouble("ny", point.normal().y);
                pointTag.putDouble("nz", point.normal().z);
                if (point.parentId() > 0L) {
                    pointTag.putLong("parent", point.parentId());
                }
                pointTags.add(pointTag);
            }
            saved.put("points", pointTags);
            if (!curve.bends().isEmpty()) {
                ListTag bendTags = new ListTag();
                for (int span = 0; span < curve.bends().size(); span++) {
                    CurveBend bend = curve.bends().get(span);
                    if (bend.isEmpty()) {
                        continue;
                    }
                    CompoundTag bendTag = new CompoundTag();
                    bendTag.putInt("span", span);
                    bendTag.putDouble("x", bend.offset().x);
                    bendTag.putDouble("y", bend.offset().y);
                    bendTag.putDouble("z", bend.offset().z);
                    bendTag.putDouble("center", bend.center());
                    bendTags.add(bendTag);
                }
                saved.put("bends", bendTags);
            }
            savedCurves.add(saved);
        }
        tag.put("curves", savedCurves);
        return tag;
    }

    public record StoredCurve(Curve curve, UUID owner, ItemStack material, int paidCost) {
    }
}
