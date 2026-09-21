package com.lirxowo.curvyblocks;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.network.CurveNetwork;
import com.lirxowo.curvyblocks.server.CurveServer;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CurvyBlocks.MODID)
public final class CurvyBlocks {
    public static final String MODID = "curvyblocks";
    public static final Logger LOGGER = LogUtils.getLogger();
    public CurvyBlocks(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CurveConfig.SPEC);
        modBus.addListener(CurveNetwork::register);
        NeoForge.EVENT_BUS.register(CurveServer.class);
    }
}
