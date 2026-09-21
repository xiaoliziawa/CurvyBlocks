package com.lirxowo.curvyblocks.client;

import com.lirxowo.curvyblocks.CurvyBlocks;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.placement.CurveSnapping;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveWorlds;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientHooks;
import org.lwjgl.glfw.GLFW;

public final class CurvePicking {
    private static final ReferenceSet<Block> FAILED_CLONES = new ReferenceOpenHashSet<>();

    private CurvePicking() {
    }

    public static boolean pickBlock(Minecraft minecraft) {
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return false;
        }
        CurveIndex curves = CurveWorlds.get(minecraft.level);
        Entity camera = minecraft.getCameraEntity();
        if (curves == null || curves.isEmpty() || camera == null) {
            return false;
        }
        float partialTick = minecraft.gameRenderer.getMainCamera().getPartialTickTime();
        Vec3 origin = camera.getEyePosition(partialTick);
        Vec3 direction = camera.getViewVector(partialTick);
        double range = minecraft.player.blockInteractionRange();
        HitResult vanillaHit = minecraft.hitResult;
        if (vanillaHit != null) {
            range = Math.min(range, Math.nextDown(origin.distanceTo(vanillaHit.getLocation())));
        }
        CurveIndex.Hit hit = curves.pick(origin, direction, range);
        if (hit == null) {
            return false;
        }
        if (ClientHooks.onClickInput(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, minecraft.options.keyPickItem,
                InteractionHand.MAIN_HAND).isCanceled()) {
            return true;
        }
        ItemStack material = cloneMaterial(minecraft, hit);
        if (material.isEmpty()) {
            return true;
        }
        Inventory inventory = minecraft.player.getInventory();
        if (minecraft.player.getAbilities().instabuild) {
            inventory.setPickedItem(material);
            minecraft.gameMode.handleCreativeModeItemAdd(minecraft.player.getMainHandItem(),
                    InventoryMenu.USE_ROW_SLOT_START + inventory.selected);
        } else {
            int slot = inventory.findSlotMatchingItem(material);
            if (Inventory.isHotbarSlot(slot)) {
                inventory.selected = slot;
            } else if (slot >= 0) {
                minecraft.gameMode.handlePickItem(slot);
            }
        }
        return true;
    }

    private static ItemStack cloneMaterial(Minecraft minecraft, CurveIndex.Hit hit) {
        BlockState state = hit.curve().material();
        Block block = state.getBlock();
        if (!FAILED_CLONES.contains(block)) {
            Vec3 normal = CurveSnapping.onHit(hit).normal();
            BlockPos position = BlockPos.containing(hit.position().subtract(normal.scale(CurveLimits.EPSILON)));
            BlockHitResult target = new BlockHitResult(hit.position(), Direction.getNearest(normal.x, normal.y, normal.z), position, false);
            try {
                ItemStack result = state.getCloneItemStack(target, minecraft.level, position, minecraft.player);
                if (result != null) {
                    return result.copy();
                }
            } catch (RuntimeException exception) {
                FAILED_CLONES.add(block);
                CurvyBlocks.LOGGER.warn("Using the block item for curved material {} because its pick callback failed",
                        BuiltInRegistries.BLOCK.getKey(block), exception);
            }
        }
        return new ItemStack(block);
    }
}
