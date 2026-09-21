package com.lirxowo.curvyblocks.placement;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;

public final class CurveMaterials {
    private CurveMaterials() {
    }

    public static BlockState state(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        return stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                .apply(item.getBlock().defaultBlockState());
    }

    public static int count(Player player, ItemStack material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ItemStack.isSameItemSameComponents(stack, material)) {
                count += stack.getCount();
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (ItemStack.isSameItemSameComponents(offhand, material)) {
            count += offhand.getCount();
        }
        return count;
    }

    public static void consume(Player player, ItemStack material, int amount) {
        Inventory inventory = player.getInventory();
        int remaining = consumeStack(inventory.getSelected(), material, amount);
        for (int slot = 0; slot < inventory.items.size() && remaining > 0; slot++) {
            if (slot != inventory.selected) {
                remaining = consumeStack(inventory.items.get(slot), material, remaining);
            }
        }
        consumeStack(player.getOffhandItem(), material, remaining);
        inventory.setChanged();
    }

    private static int consumeStack(ItemStack stack, ItemStack material, int amount) {
        if (amount == 0 || !ItemStack.isSameItemSameComponents(stack, material)) {
            return amount;
        }
        int consumed = Math.min(stack.getCount(), amount);
        stack.shrink(consumed);
        return amount - consumed;
    }

    public static void refund(Player player, ItemStack material, int amount) {
        int maximum = material.getMaxStackSize();
        while (amount > 0) {
            int count = Math.min(maximum, amount);
            ItemStack stack = material.copyWithCount(count);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            amount -= count;
        }
        player.getInventory().setChanged();
    }
}
