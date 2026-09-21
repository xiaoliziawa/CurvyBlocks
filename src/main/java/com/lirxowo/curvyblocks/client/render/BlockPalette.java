package com.lirxowo.curvyblocks.client.render;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

final class BlockPalette {
    private static final long MODEL_SEED = 42L;
    private static final Direction[] FACES = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH,
            Direction.DOWN, Direction.UP};
    private final Surface[] surfaces = new Surface[FACES.length];
    private final Long2ObjectMap<int[]> colors = new Long2ObjectOpenHashMap<>();
    private final BlockState state;
    private final boolean translucent;

    BlockPalette(BlockState state) {
        this.state = state;
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create(MODEL_SEED);
        TextureAtlasSprite fallback = model.getParticleIcon(ModelData.EMPTY);
        List<BakedQuad> unculled = model.getQuads(state, null, random, ModelData.EMPTY, null);
        for (int face = 0; face < FACES.length; face++) {
            random.setSeed(MODEL_SEED);
            List<BakedQuad> quads = model.getQuads(state, FACES[face], random, ModelData.EMPTY, null);
            BakedQuad chosen = quads.isEmpty() ? null : quads.getFirst();
            if (chosen == null) {
                for (BakedQuad quad : unculled) {
                    if (quad.getDirection() == FACES[face]) {
                        chosen = quad;
                        break;
                    }
                }
            }
            surfaces[face] = chosen == null ? new Surface(fallback, -1)
                    : new Surface(chosen.getSprite(), chosen.isTinted() ? chosen.getTintIndex() : -1);
        }
        boolean transparent = false;
        for (RenderType layer : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            transparent |= layer == RenderType.translucent() || layer == RenderType.tripwire();
        }
        translucent = transparent;
    }

    Surface surface(int face) {
        return surfaces[face];
    }

    boolean translucent() {
        return translucent;
    }

    int color(int face, BlockPos position) {
        long key = position.asLong();
        int[] cached = colors.get(key);
        if (cached == null) {
            cached = new int[surfaces.length];
            Minecraft minecraft = Minecraft.getInstance();
            for (int i = 0; i < surfaces.length; i++) {
                int tint = surfaces[i].tint();
                cached[i] = tint < 0 ? 0xFFFFFF : minecraft.getBlockColors().getColor(state, minecraft.level, position, tint);
            }
            colors.put(key, cached);
        }
        return cached[face];
    }

    record Surface(TextureAtlasSprite sprite, int tint) {
    }
}
