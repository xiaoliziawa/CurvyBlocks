package com.lirxowo.curvyblocks.client;

import com.lirxowo.curvyblocks.CurvyBlocks;
import com.lirxowo.curvyblocks.client.render.CurveHud;
import com.lirxowo.curvyblocks.client.render.CurveRenderer;
import com.lirxowo.curvyblocks.network.CurveNetwork;
import com.lirxowo.curvyblocks.network.CurvePayloads;
import com.lirxowo.curvyblocks.placement.CurveInteractions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = CurvyBlocks.MODID, dist = Dist.CLIENT)
public final class CurvyBlocksClient {
    private static final CurveRenderer RENDERER = new CurveRenderer();
    private static final ClientCurves CURVES = new ClientCurves(RENDERER::remove);
    private static final CurveEditor EDITOR = new CurveEditor(CURVES);
    private static final CurveHud HUD = new CurveHud();
    private static ClientLevel level;

    public CurvyBlocksClient(IEventBus modBus) {
        modBus.addListener(CurveKeys::register);
        modBus.addListener(CurvyBlocksClient::registerReload);
        CurveNetwork.setClientReceiver(CurvyBlocksClient::receive);
        NeoForge.EVENT_BUS.register(CurvyBlocksClient.class);
    }

    private static void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> RENDERER.reset());
    }

    private static void ensureLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (level != current) {
            RENDERER.reset();
            CURVES.bind(current);
            EDITOR.reset();
            level = current;
        }
    }

    private static boolean dimensionMatches(ResourceLocation dimension) {
        return level != null && level.dimension().location().equals(dimension);
    }

    private static void receive(CurvePayloads.Clientbound payload) {
        ensureLevel();
        switch (payload) {
            case CurvePayloads.Response response -> EDITOR.response(response);
            case CurvePayloads.ChunkSync sync -> {
                if (dimensionMatches(sync.dimension())) {
                    CURVES.sync(sync);
                }
            }
            case CurvePayloads.ForgetChunk forget -> {
                if (dimensionMatches(forget.dimension())) {
                    CURVES.forget(forget.chunk());
                }
            }
            case CurvePayloads.Added added -> {
                if (dimensionMatches(added.dimension())) {
                    CURVES.add(added.curve());
                }
            }
            case CurvePayloads.Removed removed -> {
                if (dimensionMatches(removed.dimension())) {
                    CURVES.remove(removed.curveId());
                }
            }
            default -> throw new IllegalArgumentException("Unknown client curve payload");
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ensureLevel();
        if (level != null && !Minecraft.getInstance().isPaused()) {
            EDITOR.tick();
            RENDERER.tick();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        EDITOR.interaction(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (capturesVanillaUse(event)) {
            CurveInteractions.cancel(event);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (capturesVanillaUse(event)) {
            CurveInteractions.cancel(event);
        }
    }

    private static boolean capturesVanillaUse(PlayerInteractEvent event) {
        return event.getLevel().isClientSide() && event.getEntity() == Minecraft.getInstance().player && EDITOR.syncUseCapture();
    }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        EDITOR.scroll(event);
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && Minecraft.getInstance().screen == null) {
            EDITOR.updateFrameTarget(event.getCamera());
        }
        RENDERER.render(event, CURVES.index(), EDITOR);
    }

    @SubscribeEvent
    public static void renderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.screen == null && !minecraft.options.hideGui) {
            HUD.render(event.getGuiGraphics(), EDITOR.hud());
        }
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        ensureLevel();
        PacketDistributor.sendToServer(new CurvePayloads.Mode(EDITOR.enabled()));
        EDITOR.syncUseCapture();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    @SubscribeEvent
    public static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == level) {
            reset();
        }
    }

    private static void reset() {
        CURVES.bind(null);
        RENDERER.reset();
        EDITOR.reset();
        level = null;
    }
}
