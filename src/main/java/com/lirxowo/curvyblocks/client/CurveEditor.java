package com.lirxowo.curvyblocks.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurveMath;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.network.CurvePayloads;
import com.lirxowo.curvyblocks.placement.CurveInteractions;
import com.lirxowo.curvyblocks.placement.CurveMaterials;
import com.lirxowo.curvyblocks.placement.CurvePathfinder;
import com.lirxowo.curvyblocks.placement.CurvePlacement;
import com.lirxowo.curvyblocks.placement.CurveSnapping;
import com.lirxowo.curvyblocks.placement.PlacementResult;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CurveEditor {
    private static final int[] THICKNESSES = {2, 4, 8, 16};
    private static final double[] GRID_STEPS = {0.0, 0.0625, 0.25, 0.5};
    private static final double DEFAULT_DISTANCE = 4.0;
    private static final double MIN_DISTANCE = 1.0;
    private static final double DISTANCE_STEP = 0.25;
    private static final int RESPONSE_TIMEOUT_TICKS = 200;
    private static final int ROUTE_RECHECK_TICKS = 5;
    private static final int ROUTE_RETRY_TICKS = 10;
    private static final double ROUTE_RETARGET_DISTANCE_SQUARED = 1.0;
    private static final double ROUTE_NORMAL_ALIGNMENT = 0.99;
    private final ClientCurves curves;
    private final List<CurvePoint> points = new ArrayList<>();
    private boolean enabled = true;
    private boolean useConsumed;
    private boolean useCaptureSent;
    private boolean attackConsumed;
    private int thicknessIndex = 2;
    private int gridIndex = 1;
    private CrossSection section = CrossSection.ROUND;
    private double freeDistance = DEFAULT_DISTANCE;
    private ItemStack material = ItemStack.EMPTY;
    private Vec3 rayOrigin;
    private Vec3 rayDirection;
    private CurvePoint target;
    private CurvePoint previewTarget;
    private boolean previewAutoRoute;
    private boolean queuedPlacement;
    private int clientTick;
    private int routeCheckTick;
    private int routeRetryTick;
    private int routeWorkTick = -1;
    private CurvePathfinder routeSearch;
    private Curve directPreview;
    private Curve routedPreview;
    private CurvePoint failedRouteTarget;
    private PlacementResult routeFailure = PlacementResult.ROUTE_NOT_FOUND;
    private CurveIndex.Hit hit;
    private Curve preview;
    private PlacementResult validity = PlacementResult.OK;
    private int previewRevision;
    private int requestCounter;
    private int pendingRequest;
    private int pendingTicks;
    private boolean pendingRemoval;
    private int cost;
    private List<Component> hud = List.of();
    private int hudRevision = -1;
    private int hudPending;
    private int hudAvailable;
    private int hudCost;
    private boolean hudBuild;
    private boolean hudHit;
    private ItemStack hudMaterial = ItemStack.EMPTY;

    public CurveEditor(ClientCurves curves) {
        this.curves = curves;
    }

    public void reset() {
        setUseCapture(false);
        clearDraft();
        rayOrigin = null;
        rayDirection = null;
        target = null;
        hit = null;
        pendingRequest = 0;
        useConsumed = false;
        attackConsumed = false;
        hud = List.of();
        hudRevision = -1;
        hudMaterial = ItemStack.EMPTY;
        clientTick = 0;
    }

    public boolean enabled() {
        return enabled;
    }

    public Curve preview() {
        return preview;
    }

    public CurvePoint target() {
        return target;
    }

    public CurveIndex.Hit hit() {
        return hit;
    }

    public List<CurvePoint> points() {
        return points;
    }

    public int previewRevision() {
        return previewRevision;
    }

    public boolean valid() {
        return validity == PlacementResult.OK;
    }

    public boolean routing() {
        return validity == PlacementResult.ROUTING;
    }

    public List<Component> hud() {
        return hud;
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        clientTick++;
        if (!minecraft.options.keyUse.isDown()) {
            useConsumed = false;
        }
        if (!minecraft.options.keyAttack.isDown()) {
            attackConsumed = false;
        }
        if (pendingRequest != 0 && ++pendingTicks > RESPONSE_TIMEOUT_TICKS) {
            pendingRequest = 0;
            message(Component.translatable("curvyblocks.message.timeout").withStyle(ChatFormatting.RED));
        }
        if (minecraft.screen != null) {
            syncUseCapture();
            return;
        }
        handleKeys();
        ItemStack offhand = minecraft.player.getOffhandItem();
        if (!points.isEmpty() && pendingRequest == 0
                && (!ItemStack.isSameItemSameComponents(material, offhand) || !canBuild())) {
            clearDraft();
        }
        updatePreview();
        updateHud();
        syncUseCapture();
    }

    public void updateFrameTarget(Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !camera.isInitialized()) {
            return;
        }
        if (camera.isDetached()) {
            float partialTick = camera.getPartialTickTime();
            rayOrigin = minecraft.player.getEyePosition(partialTick);
            rayDirection = minecraft.player.getViewVector(partialTick);
        } else {
            rayOrigin = camera.getPosition();
            rayDirection = new Vec3(camera.getLookVector());
        }
        updateTarget();
    }

    private void handleKeys() {
        while (CurveKeys.TOGGLE.consumeClick()) {
            enabled = !enabled;
            clearDraft();
            PacketDistributor.sendToServer(new CurvePayloads.Mode(enabled));
            message(Component.translatable(enabled ? "curvyblocks.message.enabled" : "curvyblocks.message.disabled"));
        }
        while (CurveKeys.CANCEL.consumeClick()) {
            if (pendingRequest == 0) {
                clearDraft();
            }
        }
        while (CurveKeys.UNDO.consumeClick()) {
            undo();
        }
        while (CurveKeys.GRID.consumeClick()) {
            gridIndex = (gridIndex + 1) % GRID_STEPS.length;
            invalidatePreview();
        }
        while (CurveKeys.THICKNESS.consumeClick()) {
            thicknessIndex = (thicknessIndex + 1) % THICKNESSES.length;
            invalidatePreview();
        }
        while (CurveKeys.SECTION.consumeClick()) {
            section = section == CrossSection.ROUND ? CrossSection.SQUARE : CrossSection.ROUND;
            invalidatePreview();
        }
        while (CurveKeys.FINISH.consumeClick()) {
            if (canBuild() && !points.isEmpty() && pendingRequest == 0) {
                updateTarget();
                updatePreview();
                finish();
            }
        }
    }

    private boolean canBuild() {
        return enabled && CurveInteractions.canPlace(Minecraft.getInstance().player);
    }

    public boolean capturesUse() {
        return pendingRequest != 0 || (useConsumed && Minecraft.getInstance().options.keyUse.isDown())
                || canBuild() || (enabled && (!points.isEmpty() || queuedPlacement));
    }

    public boolean syncUseCapture() {
        boolean captured = capturesUse();
        setUseCapture(captured);
        return captured;
    }

    private void setUseCapture(boolean captured) {
        if (Minecraft.getInstance().getConnection() == null) {
            useCaptureSent = false;
            return;
        }
        if (useCaptureSent != captured) {
            PacketDistributor.sendToServer(new CurvePayloads.UseCapture(captured));
            useCaptureSent = captured;
        }
    }

    private void updateTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.player == null || minecraft.level == null || minecraft.player.isSpectator()) {
            target = null;
            hit = null;
            return;
        }
        if (queuedPlacement) {
            return;
        }
        Vec3 eye = rayOrigin != null ? rayOrigin : minecraft.player.getEyePosition();
        Vec3 direction = rayDirection != null ? rayDirection : minecraft.player.getLookAngle();
        double reach = minecraft.player.blockInteractionRange();
        BlockHitResult block = blockHit(minecraft, eye, direction, reach);
        double blockDistance = block.getType() == HitResult.Type.MISS ? reach : eye.distanceTo(block.getLocation());
        hit = curves.index().pick(eye, direction, blockDistance);
        if (!canBuild()) {
            target = null;
            return;
        }
        boolean free = minecraft.options.keySprint.isDown();
        if (!free && hit != null) {
            target = CurveSnapping.onHit(hit);
            return;
        }
        if (!free && block.getType() == HitResult.Type.BLOCK) {
            Vec3 normal = Vec3.atLowerCornerOf(block.getDirection().getNormal());
            Vec3 snapped = CurveMath.snap(block.getLocation(), GRID_STEPS[gridIndex]);
            double offset = block.getLocation().subtract(snapped).dot(normal) + CurveLimits.SURFACE_OFFSET;
            target = new CurvePoint(snapped.add(normal.scale(offset)), normal);
        } else {
            target = new CurvePoint(CurveMath.snap(eye.add(direction.scale(Math.min(freeDistance, reach))), GRID_STEPS[gridIndex]), Vec3.ZERO);
        }
    }

    private static BlockHitResult blockHit(Minecraft minecraft, Vec3 eye, Vec3 direction, double reach) {
        if (minecraft.getCameraEntity() == minecraft.player && minecraft.hitResult instanceof BlockHitResult vanillaHit) {
            return vanillaHit;
        }
        return minecraft.level.clip(new ClipContext(eye, eye.add(direction.scale(reach)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    }

    private void updatePreview() {
        if (points.isEmpty() || target == null || pendingRequest != 0) {
            return;
        }
        boolean autoRoute = queuedPlacement || CurveKeys.AUTO_ROUTE.isDown();
        boolean changed = !target.equals(previewTarget) || autoRoute != previewAutoRoute;
        boolean recheck = autoRoute && routeSearch == null && clientTick >= routeCheckTick;
        Minecraft minecraft = Minecraft.getInstance();
        if (changed || recheck || directPreview == null) {
            previewTarget = target;
            previewAutoRoute = autoRoute;
            routeCheckTick = clientTick + ROUTE_RECHECK_TICKS;
            if (!autoRoute) {
                routeSearch = null;
                routedPreview = null;
            }
            List<CurvePoint> draft = new ArrayList<>(points);
            if (draft.getLast().position().distanceToSqr(target.position())
                    >= CurveLimits.MIN_POINT_DISTANCE * CurveLimits.MIN_POINT_DISTANCE) {
                draft.add(target);
            }
            if (draft.size() < 2) {
                showPreview(null, PlacementResult.INVALID_SHAPE);
                return;
            }
            try {
                BlockState state = CurveMaterials.state(material);
                directPreview = new Curve(0L, state, draft, THICKNESSES[thicknessIndex], section);
                showPreview(directPreview, CurvePlacement.validate(minecraft.level, minecraft.player, directPreview));
            } catch (IllegalArgumentException exception) {
                directPreview = null;
                routeSearch = null;
                showPreview(null, PlacementResult.INVALID_SHAPE);
            }
            if (autoRoute && validity == PlacementResult.INTERSECTS_BLOCK && routedPreview != null) {
                Curve adjusted = retarget(routedPreview);
                if (adjusted != null && CurvePlacement.validate(minecraft.level, minecraft.player, adjusted) == PlacementResult.OK) {
                    routedPreview = adjusted;
                    routeSearch = null;
                    showPreview(adjusted, PlacementResult.OK);
                }
            }
            if (validity != PlacementResult.INTERSECTS_BLOCK && validity != PlacementResult.ROUTING) {
                routeSearch = null;
            }
        }
        if (autoRoute && directPreview != null
                && (validity == PlacementResult.INTERSECTS_BLOCK || validity == PlacementResult.ROUTING)) {
            advanceRoute();
        }
        updateCost();
        if (queuedPlacement && !routing()) {
            queuedPlacement = false;
            if (valid()) {
                finish();
            } else {
                message(Component.translatable(validity.translationKey()).withStyle(ChatFormatting.RED));
            }
        }
    }

    private void showPreview(Curve curve, PlacementResult result) {
        if (preview != curve || validity != result) {
            preview = curve;
            validity = result;
            previewRevision++;
        }
    }

    private void advanceRoute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (routeSearch != null && !nearRouteTarget(routeSearch.target(), target)) {
            routeSearch = null;
        }
        if (routeSearch == null) {
            if (target.equals(failedRouteTarget) && clientTick < routeRetryTick) {
                showPreview(directPreview, routeFailure);
                return;
            }
            routeSearch = new CurvePathfinder(minecraft.level, minecraft.player, directPreview);
            routeWorkTick = -1;
        }
        showPreview(directPreview, PlacementResult.ROUTING);
        if (routeWorkTick != clientTick) {
            routeWorkTick = clientTick;
            routeSearch.advance();
        }
        if (routeSearch.state() == CurvePathfinder.State.SEARCHING) {
            return;
        }
        CurvePathfinder completed = routeSearch;
        routeSearch = null;
        if (completed.state() == CurvePathfinder.State.FOUND) {
            Curve adjusted = retarget(completed.result());
            if (adjusted != null) {
                PlacementResult validation = CurvePlacement.validate(minecraft.level, minecraft.player, adjusted);
                if (validation != PlacementResult.INTERSECTS_BLOCK) {
                    routedPreview = adjusted;
                    failedRouteTarget = null;
                    showPreview(adjusted, validation);
                    return;
                }
            }
            routeFailure = PlacementResult.ROUTE_NOT_FOUND;
        } else {
            routeFailure = completed.failure();
        }
        failedRouteTarget = target;
        routeRetryTick = clientTick + ROUTE_RETRY_TICKS;
        showPreview(directPreview, routeFailure);
    }

    private Curve retarget(Curve routed) {
        CurvePoint previous = routed.points().getLast();
        if (previous.equals(target)) {
            return routed;
        }
        if (!nearRouteTarget(previous, target)) {
            return null;
        }
        List<CurvePoint> adjusted = new ArrayList<>(routed.points());
        adjusted.set(adjusted.size() - 1, target);
        List<CurveBend> bends = routed.bends();
        if (!bends.isEmpty() && !bends.getLast().isEmpty()) {
            CurveBend bend = bends.getLast();
            Vec3 chord = target.position().subtract(adjusted.get(adjusted.size() - 2).position());
            if (chord.lengthSqr() < CurveLimits.EPSILON) {
                return null;
            }
            Vec3 offset = bend.offset().subtract(chord.scale(bend.offset().dot(chord) / chord.lengthSqr()));
            if (offset.lengthSqr() < CurveLimits.EPSILON) {
                return null;
            }
            bends = new ArrayList<>(bends);
            bends.set(bends.size() - 1, new CurveBend(offset.normalize().scale(bend.offset().length()), bend.center()));
        }
        try {
            return new Curve(0L, routed.material(), adjusted, routed.thickness(), routed.section(), bends);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean nearRouteTarget(CurvePoint first, CurvePoint second) {
        if (first.position().distanceToSqr(second.position()) > ROUTE_RETARGET_DISTANCE_SQUARED) {
            return false;
        }
        return first.normal().equals(second.normal()) || first.normal().dot(second.normal()) >= ROUTE_NORMAL_ALIGNMENT;
    }

    private void updateCost() {
        if (preview == null) {
            cost = 0;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        cost = minecraft.player.getAbilities().instabuild ? 0 : preview.materialCost(CurveConfig.MATERIAL_MULTIPLIER.get());
    }

    public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        if (event.isUseItem() && syncUseCapture()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.getHand() == InteractionHand.MAIN_HAND && !useConsumed) {
                useConsumed = true;
                if (pendingRequest != 0 || !canBuild()) {
                    return;
                }
                updateTarget();
                if (target == null) {
                    return;
                }
                if (points.isEmpty()) {
                    material = minecraft.player.getOffhandItem().copyWithCount(1);
                    points.add(target);
                    invalidatePreview();
                } else if (minecraft.player.isShiftKeyDown()) {
                    if (points.size() < CurveLimits.MAX_POINTS - 1
                            && points.getLast().position().distanceToSqr(target.position())
                            >= CurveLimits.MIN_POINT_DISTANCE * CurveLimits.MIN_POINT_DISTANCE) {
                        points.add(target);
                        invalidatePreview();
                    }
                } else {
                    updatePreview();
                    finish();
                }
            }
        } else if (event.isAttack() && enabled) {
            updateTarget();
            if (attackConsumed || !points.isEmpty() || hit != null || pendingRequest != 0) {
                event.setCanceled(true);
                event.setSwingHand(false);
                if (!attackConsumed && pendingRequest == 0) {
                    attackConsumed = true;
                    if (!points.isEmpty()) {
                        undo();
                    } else if (hit != null) {
                        beginRequest(true);
                        PacketDistributor.sendToServer(new CurvePayloads.Remove(pendingRequest, hit.curve().id()));
                    }
                }
            }
        }
    }

    private void finish() {
        if (preview == null || pendingRequest != 0) {
            return;
        }
        if (routing()) {
            queuedPlacement = true;
            previewRevision++;
            return;
        }
        if (!valid()) {
            message(Component.translatable(validity.translationKey()).withStyle(ChatFormatting.RED));
            return;
        }
        if (CurveMaterials.count(Minecraft.getInstance().player, material) < cost) {
            message(Component.translatable(PlacementResult.MISSING_MATERIAL.translationKey()).withStyle(ChatFormatting.RED));
            return;
        }
        beginRequest(false);
        queuedPlacement = false;
        PacketDistributor.sendToServer(new CurvePayloads.Place(pendingRequest, preview.thickness(), preview.section(), preview.points(), preview.bends()));
    }

    private void beginRequest(boolean removal) {
        requestCounter = requestCounter == Integer.MAX_VALUE ? 1 : requestCounter + 1;
        pendingRequest = requestCounter;
        pendingTicks = 0;
        pendingRemoval = removal;
    }

    public void response(CurvePayloads.Response response) {
        if (response.requestId() != pendingRequest) {
            return;
        }
        pendingRequest = 0;
        if (response.result() == PlacementResult.OK) {
            if (!pendingRemoval) {
                clearDraft();
            }
            message(Component.translatable(pendingRemoval ? "curvyblocks.message.removed" : "curvyblocks.message.placed", response.amount()));
        } else {
            invalidatePreview();
            message(Component.translatable(response.result().translationKey()).withStyle(ChatFormatting.RED));
        }
    }

    private void undo() {
        if (points.isEmpty() || pendingRequest != 0) {
            return;
        }
        queuedPlacement = false;
        points.removeLast();
        if (points.isEmpty()) {
            clearDraft();
        } else {
            invalidatePreview();
        }
    }

    private void clearDraft() {
        points.clear();
        material = ItemStack.EMPTY;
        preview = null;
        cost = 0;
        validity = PlacementResult.OK;
        invalidatePreview();
    }

    private void invalidatePreview() {
        previewTarget = null;
        previewAutoRoute = false;
        queuedPlacement = false;
        routeSearch = null;
        directPreview = null;
        routedPreview = null;
        failedRouteTarget = null;
        routeCheckTick = 0;
        routeRetryTick = 0;
        routeWorkTick = -1;
        previewRevision++;
    }

    public void scroll(InputEvent.MouseScrollingEvent event) {
        if (canBuild() && Minecraft.getInstance().screen == null && Screen.hasAltDown()) {
            event.setCanceled(true);
            freeDistance = Math.clamp(freeDistance + event.getScrollDeltaY() * DISTANCE_STEP,
                    MIN_DISTANCE, Minecraft.getInstance().player.blockInteractionRange());
            invalidatePreview();
        }
    }

    private void updateHud() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean build = canBuild();
        ItemStack held = minecraft.player.getOffhandItem();
        int available = preview == null ? 0 : CurveMaterials.count(minecraft.player, material);
        if (hudRevision == previewRevision && hudPending == pendingRequest && hudAvailable == available
                && hudCost == cost && hudBuild == build && hudHit == (hit != null)
                && ItemStack.isSameItemSameComponents(hudMaterial, held)) {
            return;
        }
        hudRevision = previewRevision;
        hudPending = pendingRequest;
        hudAvailable = available;
        hudCost = cost;
        hudBuild = build;
        hudHit = hit != null;
        if (!ItemStack.isSameItemSameComponents(hudMaterial, held)) {
            hudMaterial = held.copyWithCount(1);
        }
        if (!build && points.isEmpty() && hit == null) {
            hud = List.of();
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (build) {
            lines.add(Component.translatable("curvyblocks.hud.material", held.getHoverName(),
                    Component.translatable(section.translationKey()), format(THICKNESSES[thicknessIndex] / CurveLimits.UNITS_PER_BLOCK)));
            lines.add(Component.translatable(points.isEmpty() ? "curvyblocks.hud.start" : "curvyblocks.hud.edit",
                    minecraft.options.keyShift.getTranslatedKeyMessage()));
            lines.add(Component.translatable("curvyblocks.hud.settings", CurveKeys.THICKNESS.getTranslatedKeyMessage(),
                    CurveKeys.SECTION.getTranslatedKeyMessage(), CurveKeys.GRID.getTranslatedKeyMessage(),
                    GRID_STEPS[gridIndex] == 0.0 ? Component.translatable("curvyblocks.grid.off") : Component.literal(format(GRID_STEPS[gridIndex]))));
            if (!points.isEmpty()) {
                lines.add(Component.translatable("curvyblocks.hud.cancel", CurveKeys.UNDO.getTranslatedKeyMessage(),
                        CurveKeys.CANCEL.getTranslatedKeyMessage(), CurveKeys.FINISH.getTranslatedKeyMessage()));
                lines.add(Component.translatable("curvyblocks.hud.auto_route", CurveKeys.AUTO_ROUTE.getTranslatedKeyMessage()));
            }
            if (preview != null) {
                lines.add(Component.translatable("curvyblocks.hud.cost", format(preview.geometry().length()), cost,
                        available));
            }
            if (!valid() && !points.isEmpty()) {
                lines.add(Component.translatable(validity.translationKey()).withStyle(routing() ? ChatFormatting.YELLOW : ChatFormatting.RED));
            }
            lines.add(Component.translatable("curvyblocks.hud.free", minecraft.options.keySprint.getTranslatedKeyMessage(),
                    CurveKeys.TOGGLE.getTranslatedKeyMessage()));
        } else if (hit != null) {
            lines.add(Component.translatable("curvyblocks.hud.remove"));
        }
        if (hit != null) {
            lines.add(Component.translatable("curvyblocks.hud.pick", minecraft.options.keyPickItem.getTranslatedKeyMessage()));
        }
        if (pendingRequest != 0) {
            lines.add(Component.translatable("curvyblocks.hud.wait").withStyle(ChatFormatting.YELLOW));
        }
        if (queuedPlacement) {
            lines.add(Component.translatable("curvyblocks.hud.route_queued", CurveKeys.CANCEL.getTranslatedKeyMessage())
                    .withStyle(ChatFormatting.YELLOW));
        }
        hud = List.copyOf(lines);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void message(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }
}
