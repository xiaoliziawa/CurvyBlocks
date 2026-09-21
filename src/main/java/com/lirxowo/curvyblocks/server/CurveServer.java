package com.lirxowo.curvyblocks.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lirxowo.curvyblocks.api.CurveEditEvent;
import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.network.CurvePayloads;
import com.lirxowo.curvyblocks.placement.CurveInteractions;
import com.lirxowo.curvyblocks.placement.CurveMaterials;
import com.lirxowo.curvyblocks.placement.CurvePlacement;
import com.lirxowo.curvyblocks.placement.PlacementResult;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveSavedData;
import com.lirxowo.curvyblocks.world.CurveWorlds;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class CurveServer {
    private static final int ACTION_INTERVAL_TICKS = 2;
    private static final double REACH_TOLERANCE = 0.5;
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static final Set<UUID> DISABLED_PLAYERS = new HashSet<>();
    private static final Set<UUID> CAPTURED_PLAYERS = new HashSet<>();

    private CurveServer() {
    }

    public static void mode(CurvePayloads.Mode payload, IPayloadContext context) {
        UUID player = context.player().getUUID();
        if (payload.enabled()) {
            DISABLED_PLAYERS.remove(player);
        } else {
            DISABLED_PLAYERS.add(player);
        }
    }

    public static void captureUse(CurvePayloads.UseCapture payload, IPayloadContext context) {
        UUID player = context.player().getUUID();
        if (payload.captured()) {
            CAPTURED_PLAYERS.add(player);
        } else {
            CAPTURED_PLAYERS.remove(player);
        }
    }

    public static void place(CurvePayloads.Place payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (!beginAction(player, payload.requestId(), context)) {
            return;
        }
        ItemStack held = player.getOffhandItem();
        BlockState state = CurveMaterials.state(held);
        if (state == null || !enabled(player) || !CurveInteractions.canPlace(player)) {
            respond(context, payload.requestId(), PlacementResult.DENIED, 0);
            return;
        }
        Curve draft;
        try {
            draft = new Curve(0L, state, payload.points(), payload.thickness(), payload.section(), payload.bends());
        } catch (IllegalArgumentException exception) {
            respond(context, payload.requestId(), PlacementResult.INVALID_SHAPE, 0);
            return;
        }
        ServerLevel level = player.serverLevel();
        PlacementResult validation = CurvePlacement.validate(level, player, draft);
        if (validation != PlacementResult.OK) {
            respond(context, payload.requestId(), validation, 0);
            return;
        }
        CurveSavedData data = CurveSavedData.get(level);
        if (!withinChunkLimit(data.index(), draft)) {
            respond(context, payload.requestId(), PlacementResult.CHUNK_LIMIT, 0);
            return;
        }
        int cost = player.getAbilities().instabuild ? 0 : draft.materialCost(CurveConfig.MATERIAL_MULTIPLIER.get());
        ItemStack material = held.copyWithCount(1);
        int available = CurveMaterials.count(player, material);
        if (available < cost) {
            respond(context, payload.requestId(), PlacementResult.MISSING_MATERIAL, cost - available);
            return;
        }
        if (!canEdit(player, draft, false)) {
            respond(context, payload.requestId(), PlacementResult.DENIED, 0);
            return;
        }
        CurveMaterials.consume(player, material, cost);
        Curve placed = data.add(draft, player.getUUID(), material, cost);
        if (cost > 0) {
            CurveInventorySync.syncChanges(player);
        }
        broadcast(level, placed, new CurvePayloads.Added(level.dimension().location(), placed));
        playSound(level, player, placed, false);
        respond(context, payload.requestId(), PlacementResult.OK, cost);
    }

    public static void remove(CurvePayloads.Remove payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (!beginAction(player, payload.requestId(), context)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        CurveSavedData data = CurveSavedData.get(level);
        CurveSavedData.StoredCurve entry = data.get(payload.curveId());
        if (entry == null) {
            respond(context, payload.requestId(), PlacementResult.NOT_FOUND, 0);
            return;
        }
        if (!player.mayBuild() || player.isSpectator()) {
            respond(context, payload.requestId(), PlacementResult.DENIED, 0);
            return;
        }
        if (CurveConfig.OWNER_ONLY_REMOVAL.get() && !entry.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
            respond(context, payload.requestId(), PlacementResult.NOT_OWNER, 0);
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getLookAngle();
        double range = player.blockInteractionRange() + REACH_TOLERANCE;
        BlockHitResult block = level.clip(new ClipContext(eye, eye.add(direction.scale(range)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            range = Math.min(range, eye.distanceTo(block.getLocation()));
        }
        CurveIndex.Hit hit = data.index().pick(eye, direction, range);
        if (hit == null || hit.curve().id() != payload.curveId()) {
            respond(context, payload.requestId(), PlacementResult.TOO_FAR, 0);
            return;
        }
        if (!canEdit(player, entry.curve(), true)) {
            respond(context, payload.requestId(), PlacementResult.DENIED, 0);
            return;
        }
        data.remove(payload.curveId());
        broadcast(level, entry.curve(), new CurvePayloads.Removed(level.dimension().location(), payload.curveId()));
        int refund = player.getAbilities().instabuild ? 0 : entry.paidCost();
        CurveMaterials.refund(player, entry.material(), refund);
        if (refund > 0) {
            CurveInventorySync.syncChanges(player);
        }
        playSound(level, player, entry.curve(), true);
        respond(context, payload.requestId(), PlacementResult.OK, refund);
    }

    private static boolean beginAction(ServerPlayer player, int requestId, IPayloadContext context) {
        if (requestId <= 0) {
            respond(context, requestId, PlacementResult.INVALID_SHAPE, 0);
            return false;
        }
        long time = player.serverLevel().getGameTime();
        Long previous = LAST_ACTION.get(player.getUUID());
        if (previous != null && time >= previous && time - previous < ACTION_INTERVAL_TICKS) {
            respond(context, requestId, PlacementResult.BUSY, 0);
            return false;
        }
        LAST_ACTION.put(player.getUUID(), time);
        return true;
    }

    private static boolean canEdit(ServerPlayer player, Curve curve, boolean removal) {
        ServerLevel level = player.serverLevel();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        LongIterator blocks = CurvePlacement.occupiedBlocks(curve).iterator();
        while (blocks.hasNext()) {
            position.set(blocks.nextLong());
            if (!level.mayInteract(player, position)
                    || (!removal && !player.mayUseItemAt(position, Direction.UP, player.getOffhandItem()))) {
                return false;
            }
        }
        return !NeoForge.EVENT_BUS.post(new CurveEditEvent(player, curve, removal)).isCanceled();
    }

    private static boolean withinChunkLimit(CurveIndex index, Curve curve) {
        AABB bounds = curve.geometry().bounds();
        int limit = CurveConfig.MAX_CURVES_PER_CHUNK.get();
        for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
            for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                if (index.inChunk(ChunkPos.asLong(x, z)).size() >= limit) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void respond(IPayloadContext context, int requestId, PlacementResult result, int amount) {
        context.reply(new CurvePayloads.Response(requestId, result, amount));
    }

    private static void playSound(ServerLevel level, Player player, Curve curve, boolean removal) {
        Vec3 position = curve.points().getLast().position();
        SoundType sound = curve.material().getSoundType(level, BlockPos.containing(position), player);
        level.playSound(null, position.x, position.y, position.z,
                removal ? sound.getBreakSound() : sound.getPlaceSound(), SoundSource.BLOCKS,
                sound.getVolume(), sound.getPitch());
    }

    private static void broadcast(ServerLevel level, Curve curve, CustomPacketPayload payload) {
        Set<ServerPlayer> recipients = new HashSet<>();
        AABB bounds = curve.geometry().bounds();
        for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
            for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                recipients.addAll(level.getChunkSource().chunkMap.getPlayers(new ChunkPos(x, z), false));
            }
        }
        for (ServerPlayer player : recipients) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static boolean enabled(Player player) {
        return !DISABLED_PLAYERS.contains(player.getUUID());
    }

    private static boolean capturesInteraction(Player player) {
        return !player.level().isClientSide() && (CAPTURED_PLAYERS.contains(player.getUUID())
                || enabled(player) && CurveInteractions.canPlace(player));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (capturesInteraction(event.getEntity())) {
            CurveInteractions.cancel(event);
            CurveInventorySync.restoreHand(event.getEntity(), event.getHand());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (capturesInteraction(event.getEntity())) {
            CurveInteractions.cancel(event);
            CurveInventorySync.restoreHand(event.getEntity(), event.getHand());
        }
    }

    @SubscribeEvent
    public static void levelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CurveSavedData.get(level);
        }
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            CurveWorlds.unload(level);
        }
    }

    @SubscribeEvent
    public static void chunkSent(ChunkWatchEvent.Sent event) {
        List<Curve> curves = new ArrayList<>(CurveSavedData.get(event.getLevel()).index().inChunk(event.getPos().toLong()));
        if (curves.isEmpty()) {
            PacketDistributor.sendToPlayer(event.getPlayer(), new CurvePayloads.ChunkSync(
                    event.getLevel().dimension().location(), event.getPos().toLong(), true, List.of()));
            return;
        }
        for (int start = 0; start < curves.size(); start += CurvePayloads.MAX_BATCH_SIZE) {
            List<Curve> batch = List.copyOf(curves.subList(start, Math.min(curves.size(), start + CurvePayloads.MAX_BATCH_SIZE)));
            PacketDistributor.sendToPlayer(event.getPlayer(), new CurvePayloads.ChunkSync(
                    event.getLevel().dimension().location(), event.getPos().toLong(), start == 0, batch));
        }
    }

    @SubscribeEvent
    public static void chunkUnwatch(ChunkWatchEvent.UnWatch event) {
        PacketDistributor.sendToPlayer(event.getPlayer(), new CurvePayloads.ForgetChunk(
                event.getLevel().dimension().location(), event.getPos().toLong()));
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_ACTION.remove(id);
        DISABLED_PLAYERS.remove(id);
        CAPTURED_PLAYERS.remove(id);
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        LAST_ACTION.clear();
        DISABLED_PLAYERS.clear();
        CAPTURED_PLAYERS.clear();
    }
}
