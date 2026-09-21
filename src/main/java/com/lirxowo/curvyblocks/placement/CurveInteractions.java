package com.lirxowo.curvyblocks.placement;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class CurveInteractions {
    private CurveInteractions() {
    }

    public static boolean canPlace(Player player) {
        return player != null && player.isAlive() && player.mayBuild() && !player.isSpectator()
                && player.getOffhandItem().getItem() instanceof BlockItem;
    }

    public static void cancel(PlayerInteractEvent.RightClickBlock event) {
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    public static void cancel(PlayerInteractEvent.RightClickItem event) {
        event.setCancellationResult(InteractionResult.CONSUME);
        event.setCanceled(true);
    }
}
