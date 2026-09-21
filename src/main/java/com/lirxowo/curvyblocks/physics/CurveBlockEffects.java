package com.lirxowo.curvyblocks.physics;

import com.lirxowo.curvyblocks.CurvyBlocks;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

public final class CurveBlockEffects {
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);
    private static final WorldMutation WORLD_MUTATION = new WorldMutation();

    private CurveBlockEffects() {
    }

    public static boolean apply(Effect effect, CurveContact contact, Entity entity, float fallDistance) {
        Context context = CONTEXT.get();
        BlockState material = contact.material();
        Block block = material.getBlock();
        Level level = entity.level();
        if (!level.hasChunkAt(contact.position()) || !context.begin(block, effect)) {
            return false;
        }
        context.entity = entity;
        try {
            switch (effect) {
                case STEP -> block.stepOn(level, contact.position(), material, entity);
                case FALL -> block.fallOn(level, material, contact.position(), entity, fallDistance);
                case LAND -> block.updateEntityAfterFallOn(level, entity);
                case INSIDE -> material.entityInside(level, contact.position(), entity);
                case FRICTION -> throw new IllegalArgumentException("Use friction for friction queries");
            }
            return true;
        } catch (RuntimeException exception) {
            context.disable(block, effect, exception);
            return effect == Effect.FALL && context.fallHandled;
        } finally {
            context.end();
        }
    }

    public static float friction(CurveContact contact, Entity entity) {
        Context context = CONTEXT.get();
        BlockState material = contact.material();
        Block block = material.getBlock();
        if (!entity.level().hasChunkAt(contact.position()) || !context.begin(block, Effect.FRICTION)) {
            return block.getFriction();
        }
        try {
            return block.getFriction(material, entity.level(), contact.position(), entity);
        } catch (RuntimeException exception) {
            context.disable(block, Effect.FRICTION, exception);
            return block.getFriction();
        } finally {
            context.end();
        }
    }

    @SubscribeEvent(receiveCanceled = true)
    public static void onLivingFall(LivingFallEvent event) {
        Context context = CONTEXT.get();
        if (context.active && context.entity == event.getEntity()) {
            context.fallHandled = true;
        }
    }

    public static boolean active() {
        return CONTEXT.get().active;
    }

    public static void rejectWorldMutation() {
        if (active()) {
            throw WORLD_MUTATION;
        }
    }

    public enum Effect {
        STEP, FALL, LAND, INSIDE, FRICTION;

        private final int mask = 1 << ordinal();
    }

    private static final class Context {
        private final Reference2IntMap<Block> disabled = new Reference2IntOpenHashMap<>();
        private boolean active;
        private Entity entity;
        private boolean fallHandled;

        private boolean begin(Block block, Effect effect) {
            if (active || (disabled.getInt(block) & effect.mask) != 0) {
                return false;
            }
            active = true;
            fallHandled = false;
            return true;
        }

        private void end() {
            active = false;
            entity = null;
        }

        private void disable(Block block, Effect effect, RuntimeException exception) {
            disabled.put(block, disabled.getInt(block) | effect.mask);
            if (!(exception instanceof WorldMutation)) {
                CurvyBlocks.LOGGER.warn("Disabled curved material {} callback for {}", effect,
                        BuiltInRegistries.BLOCK.getKey(block), exception);
            }
        }
    }

    private static final class WorldMutation extends RuntimeException {
        private WorldMutation() {
            super(null, null, false, false);
        }
    }
}
