package com.lirxowo.curvyblocks.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderStateShard.DepthTestStateShard;
import net.minecraft.client.renderer.RenderStateShard.TextureStateShard;
import net.minecraft.client.renderer.RenderStateShard.TransparencyStateShard;
import net.minecraft.client.renderer.RenderStateShard.WriteMaskStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlas;

final class CurveRenderTypes {
    private static final int BUFFER_BYTES = 65536;
    static final RenderType SOLID = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    static final RenderType TRANSLUCENT = Sheets.translucentCullBlockSheet();
    static final RenderType PREVIEW_DEPTH = preview("curvyblocks_preview_depth", RenderStateShard.LEQUAL_DEPTH_TEST,
            RenderStateShard.DEPTH_WRITE, RenderStateShard.NO_TRANSPARENCY);
    static final RenderType PREVIEW_COLOR = preview("curvyblocks_preview_color", RenderStateShard.EQUAL_DEPTH_TEST,
            RenderStateShard.COLOR_WRITE, RenderStateShard.TRANSLUCENT_TRANSPARENCY);

    private CurveRenderTypes() {
    }

    private static RenderType preview(String name, DepthTestStateShard depthTest, WriteMaskStateShard writeMask,
                                      TransparencyStateShard transparency) {
        return RenderType.create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, BUFFER_BYTES,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(depthTest)
                        .setWriteMaskState(writeMask)
                        .setTransparencyState(transparency)
                        .createCompositeState(false));
    }
}
