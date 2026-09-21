package com.lirxowo.curvyblocks.client;

import java.util.function.LongConsumer;

import com.lirxowo.curvyblocks.network.CurvePayloads;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveWorlds;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public final class ClientCurves {
    private final CurveIndex index = new CurveIndex();
    private final Long2ObjectMap<LongSet> watched = new Long2ObjectOpenHashMap<>();
    private final Long2IntMap references = new Long2IntOpenHashMap();
    private final LongConsumer onRemoved;
    private ClientLevel level;

    public ClientCurves(LongConsumer onRemoved) {
        this.onRemoved = onRemoved;
    }

    public void bind(ClientLevel nextLevel) {
        if (level == nextLevel) {
            return;
        }
        if (level != null) {
            CurveWorlds.unload(level);
        }
        for (Curve curve : index.all()) {
            onRemoved.accept(curve.id());
        }
        watched.clear();
        references.clear();
        index.clear();
        level = nextLevel;
        if (level != null) {
            CurveWorlds.register(level, index);
        }
    }

    public CurveIndex index() {
        return index;
    }

    public void sync(CurvePayloads.ChunkSync payload) {
        if (payload.replace()) {
            forget(payload.chunk());
        }
        LongSet ids = watched.computeIfAbsent(payload.chunk(), key -> new LongOpenHashSet());
        for (Curve curve : payload.curves()) {
            retain(ids, curve);
        }
    }

    public void add(Curve curve) {
        AABB bounds = curve.geometry().bounds();
        for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
            for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                LongSet ids = watched.get(ChunkPos.asLong(x, z));
                if (ids != null) {
                    retain(ids, curve);
                }
            }
        }
    }

    private void retain(LongSet ids, Curve curve) {
        if (ids.add(curve.id())) {
            references.put(curve.id(), references.get(curve.id()) + 1);
            if (index.get(curve.id()) == null) {
                index.put(curve);
            }
        }
    }

    public void forget(long chunk) {
        LongSet ids = watched.remove(chunk);
        if (ids == null) {
            return;
        }
        LongIterator iterator = ids.iterator();
        while (iterator.hasNext()) {
            long id = iterator.nextLong();
            int remaining = references.get(id) - 1;
            if (remaining <= 0) {
                references.remove(id);
                index.remove(id);
                onRemoved.accept(id);
            } else {
                references.put(id, remaining);
            }
        }
    }

    public void remove(long id) {
        Curve curve = index.remove(id);
        if (curve == null) {
            return;
        }
        AABB bounds = curve.geometry().bounds();
        for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
            for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                LongSet ids = watched.get(ChunkPos.asLong(x, z));
                if (ids != null) {
                    ids.remove(id);
                }
            }
        }
        references.remove(id);
        onRemoved.accept(id);
    }
}
