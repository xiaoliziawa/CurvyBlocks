package com.lirxowo.curvyblocks.api;

import com.lirxowo.curvyblocks.world.Curve;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public final class CurveEditEvent extends Event implements ICancellableEvent {
    private final ServerPlayer player;
    private final Curve curve;
    private final boolean removal;

    public CurveEditEvent(ServerPlayer player, Curve curve, boolean removal) {
        this.player = player;
        this.curve = curve;
        this.removal = removal;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public Curve getCurve() {
        return curve;
    }

    public boolean isRemoval() {
        return removal;
    }
}
