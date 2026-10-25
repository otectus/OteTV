package com.otectus.otetv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.otectus.otetv.block.TvBlock;
import com.otectus.otetv.block.TvPlacement;
import com.otectus.otetv.item.TvBlockItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the whole footprint before a television is placed (section 5.2): green when every cell, the support and the
 * mount are valid, red otherwise. It uses the same validation code the server applies.
 */
public final class PlacementPreview {
    private PlacementPreview() {
    }

    public static void render(PoseStack pose, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        ItemStack stack = mc.player.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (!(stack.getItem() instanceof TvBlockItem)) {
            stack = mc.player.getOffhandItem();
            hand = InteractionHand.OFF_HAND;
            if (!(stack.getItem() instanceof TvBlockItem)) {
                return;
            }
        }
        TvBlock block = (TvBlock) ((TvBlockItem) stack.getItem()).getBlock();
        BlockPlaceContext ctx = new BlockPlaceContext(mc.player, hand, stack, hit);
        BlockPos pos = ctx.getClickedPos();
        TvPlacement.Result result = TvPlacement.compute(mc.level, mc.player, block, pos, ctx.getClickedFace(), ctx.getHorizontalDirection(), stack);
        float r = result.valid() ? 0.25f : 0.95f;
        float g = result.valid() ? 0.9f : 0.2f;
        float b = 0.25f;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        AABB all = null;
        for (BlockPos cell : result.cells()) {
            AABB box = new AABB(cell).deflate(0.002);
            all = all == null ? box : all.minmax(box);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 0.35f);
        }
        if (all != null) {
            LevelRenderer.renderLineBox(pose, lines, all.inflate(0.004), r, g, b, 0.9f);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
