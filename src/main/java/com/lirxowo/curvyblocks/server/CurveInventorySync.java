package com.lirxowo.curvyblocks.server;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

public final class CurveInventorySync {
    private static final int DIRECT_INVENTORY_STATE = 0;

    private CurveInventorySync() {
    }

    public static void syncChanges(ServerPlayer player) {
        player.containerMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) {
            restoreHand(player, InteractionHand.OFF_HAND);
        }
    }

    public static void restoreHand(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            Inventory inventory = player.getInventory();
            int slot = hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : inventory.selected;
            serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(ClientboundContainerSetSlotPacket.PLAYER_INVENTORY,
                    DIRECT_INVENTORY_STATE, slot, inventory.getItem(slot)));
        }
    }
}
