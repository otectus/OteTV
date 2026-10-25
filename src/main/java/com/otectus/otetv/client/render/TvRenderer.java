package com.otectus.otetv.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.otectus.otetv.OteTV;
import com.otectus.otetv.block.TvGeometry;
import com.otectus.otetv.block.TvModel;
import com.otectus.otetv.block.entity.TvBlockEntity;
import com.otectus.otetv.block.entity.TvPublicState;
import com.otectus.otetv.client.media.VideoTexture;
import com.otectus.otetv.client.tv.CatalogCache;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.core.state.PlayState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Draws a TV's screen: decoded video with letterboxing, the calm standby/test card, short status text, optional
 * period overlays for eligible direct media, indicator lights and dials, and local captions. One shared renderer
 * instance serves every TV, so it keeps no per-TV state; everything instance-specific lives in sessions (section
 * 14.2). Only the screen is full-bright; no light updates happen per video frame (section 5.3).
 */
public final class TvRenderer implements BlockEntityRenderer<TvBlockEntity> {
    private static final ResourceLocation STANDBY = OteTV.id("textures/screen/standby.png");
    private static final ResourceLocation STANDBY_WIDE = OteTV.id("textures/screen/standby_wide.png");
    private static final ResourceLocation SCANLINES = OteTV.id("textures/screen/scanlines.png");
    private static final ResourceLocation VIGNETTE = OteTV.id("textures/screen/vignette.png");
    private static final ResourceLocation GRAIN = OteTV.id("textures/screen/grain.png");
    private static final ResourceLocation BLACK = OteTV.id("textures/screen/black.png");
    private static final ResourceLocation LED_ON = OteTV.id("textures/screen/led_on.png");
    private static final ResourceLocation LED_STANDBY = OteTV.id("textures/screen/led_standby.png");
    private static final ResourceLocation DIAL = OteTV.id("textures/screen/dial_pointer.png");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    private static final int MAX_CAPTION_ROWS = 4;
    // Viewer-space depth of each layer, in model pixels toward the viewer. Layers are a visible fraction of a pixel apart
    // so a TV across a room does not z-fight; only overlays and text ever overlap the picture.
    private static final float Z_PICTURE = 0f;
    private static final float Z_SCANLINES = -0.05f;
    private static final float Z_VIGNETTE = -0.08f;
    private static final float Z_GRAIN = -0.10f;
    private static final float Z_TEXT_BACK = -0.14f;
    private static final float Z_TEXT = -0.20f;

    private final Font font;
    private long frameCounter;

    public TvRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public boolean shouldRenderOffScreen(TvBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void render(TvBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        frameCounter++;
        TvPublicState pub = be.publicState();
        TvModel model = be.model();
        Direction facing = be.facing();
        TvProfile profile = TvProfiles.get(model, be.mount());
        TvProfile.Aperture a = profile.aperture();
        UUID id = pub.tvId();
        ClientPlaybackSession session = id == null ? null : ClientTvManager.get().session(id);

        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-TvGeometry.yRotation(facing)));
        pose.translate(-0.5, 0, -0.5);

        drawIndicators(pose, buffers, be, profile, pub, light);

        if (pub.powered()) {
            // Viewer space: origin at the aperture's top-left as seen from the front, +x right, +y down, model pixels,
            // +z away from the viewer (the convention Font uses for in-world text: its glyph backgrounds sit at +0.01).
            pose.pushPose();
            pose.translate(a.x1() / 16.0, a.y1() / 16.0, a.z() / 16.0);
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
            float w = (float) a.width();
            float h = (float) a.height();
            VideoTexture tex = session == null ? null : session.prepareFrame();
            if (session != null) {
                measureVisibility(be, session, profile);
            }
            boolean showVideo = tex != null && session.state() != LocalPlaybackState.EXCESS_SESSION && !session.tooSmallForYouTube();
            if (showVideo) {
                float contentAspect = (float) tex.width() / Math.max(1, tex.height());
                float cw = w;
                float ch = w / contentAspect;
                if (ch > h) {
                    ch = h;
                    cw = h * contentAspect;
                }
                float cx = (w - cw) / 2;
                float cy = (h - ch) / 2;
                // Letterbox bars only where the picture does not reach: nothing full-size lies under the picture.
                if (cy > 0.001f) {
                    quad(pose, buffers.getBuffer(RenderType.text(BLACK)), 0, 0, w, cy, Z_PICTURE, 0, 0, 1, 1, 0xFF000000);
                    quad(pose, buffers.getBuffer(RenderType.text(BLACK)), 0, cy + ch, w, h, Z_PICTURE, 0, 0, 1, 1, 0xFF000000);
                }
                if (cx > 0.001f) {
                    quad(pose, buffers.getBuffer(RenderType.text(BLACK)), 0, cy, cx, cy + ch, Z_PICTURE, 0, 0, 1, 1, 0xFF000000);
                    quad(pose, buffers.getBuffer(RenderType.text(BLACK)), cx + cw, cy, w, cy + ch, Z_PICTURE, 0, 0, 1, 1, 0xFF000000);
                }
                quad(pose, buffers.getBuffer(RenderType.text(tex.location())), cx, cy, cx + cw, cy + ch, Z_PICTURE, 0, 0, 1, 1, 0xFFFFFFFF);
                if (session.effectsEnabled()) {
                    if (profile.scanlines()) {
                        quad(pose, buffers.getBuffer(RenderType.text(SCANLINES)), cx, cy, cx + cw, cy + ch, Z_SCANLINES, 0, 0, 1, 1, 0x66FFFFFF);
                    }
                    if (profile.vignette()) {
                        quad(pose, buffers.getBuffer(RenderType.text(VIGNETTE)), 0, 0, w, h, Z_VIGNETTE, 0, 0, 1, 1, 0xFFFFFFFF);
                    }
                    if (!OteTvClientConfig.REDUCE_MOTION_AND_FLICKER.get()) {
                        float ou = (frameCounter * 37 % 64) / 64f;
                        float ov = (frameCounter * 53 % 64) / 64f;
                        quad(pose, buffers.getBuffer(RenderType.text(GRAIN)), 0, 0, w, h, Z_GRAIN, ou, ov, ou + 0.5f, ov + 0.5f, 0x22FFFFFF);
                    }
                }
                drawStatusBadge(pose, buffers, session, w, h);
                drawCaption(pose, buffers, session, w, h);
            } else {
                ResourceLocation card = model.isWidescreen() ? STANDBY_WIDE : STANDBY;
                quad(pose, buffers.getBuffer(RenderType.text(card)), 0, 0, w, h, Z_PICTURE, 0, 0, 1, 1, 0xFFFFFFFF);
                drawStandbyText(pose, buffers, pub, session, id, w, h);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    /** Draws a quad in viewer space with GUI-style winding (top-left, bottom-left, bottom-right, top-right). */
    private static void quad(PoseStack pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z,
                             float u0, float v0, float u1, float v1, int argb) {
        Matrix4f m = pose.last().pose();
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        vc.vertex(m, x0, y0, z).color(r, g, b, a).uv(u0, v0).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x0, y1, z).color(r, g, b, a).uv(u0, v1).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x1, y1, z).color(r, g, b, a).uv(u1, v1).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(m, x1, y0, z).color(r, g, b, a).uv(u1, v0).uv2(FULL_BRIGHT).endVertex();
    }

    private void drawStandbyText(PoseStack pose, MultiBufferSource buffers, TvPublicState pub, ClientPlaybackSession session, UUID id, float w, float h) {
        String title = pub.displayName();
        String line2;
        String line3 = "";
        if (session != null) {
            line2 = I18n.get(session.state().translationKey());
            if (session.state() == LocalPlaybackState.NEEDS_CONSENT) {
                line3 = I18n.get("otetv.screen.consent_needed", session.waitingHost());
            } else if (session.state().isFailure()) {
                line3 = I18n.get(session.error().translationKey());
            } else if (session.tooSmallForYouTube()) {
                line2 = I18n.get("otetv.screen.too_small");
                line3 = I18n.get("otetv.screen.open_viewing");
            } else if (session.isWaitingForLocalResume()) {
                line3 = I18n.get("otetv.screen.press_play");
            } else if (session.state() == LocalPlaybackState.EXCESS_SESSION) {
                line3 = I18n.get("otetv.screen.excess");
            }
        } else if (pub.hasSource()) {
            line2 = pub.playState() == PlayState.PLAYING ? I18n.get("otetv.screen.join_viewing") : I18n.get("otetv.screen.paused_join");
        } else {
            line2 = I18n.get("otetv.screen.standby");
        }
        if (title.isEmpty() && pub.hasSource()) {
            title = pub.sourceLabel();
        }
        if (pub.channelNumber() > 0) {
            title = I18n.get("otetv.screen.channel", pub.channelNumber()) + (title.isEmpty() ? "" : "  " + title);
        }
        if (pub.locked()) {
            line3 = I18n.get("otetv.screen.locked");
        }
        String[] lines = {CatalogCache.localize(title), line2, line3};
        int[] colors = {0xFFFFFFFF, 0xFFE0E0E0, 0xFFFFD27A};
        float scale = Math.max(0.08f, h / 64f);
        float maxWidth = w / scale * 0.9f;
        // The shadow pass sits behind the text pass, a fixed fraction of a pixel apart so they never z-fight.
        for (int pass = 0; pass < 2; pass++) {
            boolean shadow = pass == 0;
            pose.pushPose();
            pose.translate(w / 2, h * 0.68f, shadow ? Z_TEXT_BACK : Z_TEXT);
            pose.scale(scale, scale, scale);
            for (int i = 0; i < lines.length; i++) {
                drawCentered(pose, buffers, lines[i], i * 10, colors[i], maxWidth, shadow);
            }
            pose.popPose();
        }
    }

    private void drawCentered(PoseStack pose, MultiBufferSource buffers, String text, float y, int color, float maxWidth, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String clipped = font.plainSubstrByWidth(text, (int) maxWidth);
        float x = -font.width(clipped) / 2f;
        if (shadow) {
            font.drawInBatch(Component.literal(clipped), x + 0.6f, y + 0.6f, 0xFF000000 | (color & 0x3F3F3F) >> 2, false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        } else {
            font.drawInBatch(Component.literal(clipped), x, y, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
    }

    private void drawStatusBadge(PoseStack pose, MultiBufferSource buffers, ClientPlaybackSession session, float w, float h) {
        LocalPlaybackState s = session.state();
        String text = null;
        if (s == LocalPlaybackState.BUFFERING || s == LocalPlaybackState.LOADING) {
            text = I18n.get("otetv.local_state.buffering");
        } else if (s == LocalPlaybackState.DIVERGED) {
            text = I18n.get("otetv.screen.diverged");
        } else if (session.isWaitingForLocalResume()) {
            text = I18n.get("otetv.screen.press_play");
        } else if (session.mode() == ClientPlaybackSession.Mode.PRIVATE) {
            text = I18n.get("otetv.screen.private");
        }
        if (text == null) {
            return;
        }
        float scale = Math.max(0.06f, h / 90f);
        String clipped = font.plainSubstrByWidth(text, (int) ((w - 2) / scale));
        int textWidth = font.width(clipped);
        pose.pushPose();
        pose.translate(1.0f, 1.0f, Z_TEXT_BACK);
        pose.scale(scale, scale, scale);
        quad(pose, buffers.getBuffer(RenderType.text(BLACK)), -1, -1, textWidth + 1, 9, 0, 0, 0, 1, 1, 0x99000000);
        pose.popPose();
        pose.pushPose();
        pose.translate(1.0f, 1.0f, Z_TEXT);
        pose.scale(scale, scale, scale);
        font.drawInBatch(Component.literal(clipped), 0, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        pose.popPose();
    }

    private void drawCaption(PoseStack pose, MultiBufferSource buffers, ClientPlaybackSession session, float w, float h) {
        Cue cue = session.activeCue();
        if (cue == null || session.isYouTube()) {
            return; // YouTube captions stay inside the official player; no replacement subtitles over it
        }
        float scale = (float) (Math.max(0.06f, h / 80f) * OteTvClientConfig.CAPTION_SCALE.get());
        int maxWidth = (int) (w / scale * 0.95f);
        List<FormattedCharSequence> rows = new ArrayList<>();
        for (String line : cue.text().split("\n")) {
            rows.addAll(font.split(Component.literal(line), maxWidth));
        }
        if (rows.size() > MAX_CAPTION_ROWS) {
            rows = rows.subList(0, MAX_CAPTION_ROWS);
        }
        int widest = 0;
        for (FormattedCharSequence row : rows) {
            widest = Math.max(widest, font.width(row));
        }
        float top = h - (rows.size() * 10 + 2) * scale;
        pose.pushPose();
        pose.translate(w / 2, top, Z_TEXT_BACK);
        pose.scale(scale, scale, scale);
        quad(pose, buffers.getBuffer(RenderType.text(BLACK)), -widest / 2f - 2, -1, widest / 2f + 2, rows.size() * 10, 0, 0, 0, 1, 1, 0xB0000000);
        pose.popPose();
        pose.pushPose();
        pose.translate(w / 2, top, Z_TEXT);
        pose.scale(scale, scale, scale);
        for (int i = 0; i < rows.size(); i++) {
            FormattedCharSequence row = rows.get(i);
            font.drawInBatch(row, -font.width(row) / 2f, i * 10, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
        pose.popPose();
    }

    private void drawIndicators(PoseStack pose, MultiBufferSource buffers, TvBlockEntity be, TvProfile profile, TvPublicState pub, int light) {
        for (TvProfile.Indicator ind : profile.indicators()) {
            pose.pushPose();
            pose.translate(ind.x() / 16.0, ind.y() / 16.0, ind.z() / 16.0);
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.scale(1 / 16f, -1 / 16f, 1 / 16f);
            float half = (float) ind.size() / 2;
            if (ind.type() == TvProfile.IndicatorType.LED) {
                ResourceLocation tex = pub.powered() ? (pub.playState() == PlayState.PLAYING || pub.hasSource() ? LED_ON : LED_STANDBY) : LED_STANDBY;
                int color = pub.powered() ? 0xFFFFFFFF : 0xFF303030;
                VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
                Matrix4f m = pose.last().pose();
                int lightValue = pub.powered() ? FULL_BRIGHT : light;
                vc.vertex(m, -half, -half, 0).color(255, 255, 255, (color >>> 24)).uv(0, 0).uv2(lightValue).endVertex();
                vc.vertex(m, -half, half, 0).color(255, 255, 255, (color >>> 24)).uv(0, 1).uv2(lightValue).endVertex();
                vc.vertex(m, half, half, 0).color(255, 255, 255, (color >>> 24)).uv(1, 1).uv2(lightValue).endVertex();
                vc.vertex(m, half, -half, 0).color(255, 255, 255, (color >>> 24)).uv(1, 0).uv2(lightValue).endVertex();
            } else {
                // The channel dial turns to reflect the confirmed channel (section 5.3).
                pose.mulPose(Axis.ZP.rotationDegrees(DialAnimator.angleFor(be, pub)));
                VertexConsumer vc = buffers.getBuffer(RenderType.text(DIAL));
                Matrix4f m = pose.last().pose();
                vc.vertex(m, -half, -half, 0).color(255, 255, 255, 255).uv(0, 0).uv2(light).endVertex();
                vc.vertex(m, -half, half, 0).color(255, 255, 255, 255).uv(0, 1).uv2(light).endVertex();
                vc.vertex(m, half, half, 0).color(255, 255, 255, 255).uv(1, 1).uv2(light).endVertex();
                vc.vertex(m, half, -half, 0).color(255, 255, 255, 255).uv(1, 0).uv2(light).endVertex();
            }
            pose.popPose();
        }
    }

    /** Projected size and a few occlusion rays, for the YouTube visibility policy and video upload culling. */
    private void measureVisibility(TvBlockEntity be, ClientPlaybackSession session, TvProfile profile) {
        Minecraft mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        TvProfile.Aperture a = profile.aperture();
        Vec3 center = profile.apertureCenter(be.getBlockPos(), be.facing());
        double distance = Math.max(0.1, eye.distanceTo(center));
        double fov = Math.toRadians(mc.options.fov().get());
        double pixelsPerBlock = mc.getWindow().getHeight() / (2 * distance * Math.tan(fov / 2));
        Vec3 toEye = eye.subtract(center).normalize();
        double facingDot = Math.max(0, toEye.dot(TvProfile.normal(be.facing())));
        double widthPx = a.width() / 16.0 * pixelsPerBlock * facingDot;
        double heightPx = a.height() / 16.0 * pixelsPerBlock;
        boolean occluded = false;
        if (session.isYouTube() && frameCounter % 4 == 0) {
            int blocked = 0;
            Vec3[] targets = {center,
                    TvProfile.toWorld(be.getBlockPos(), be.facing(), a.x0() + 0.5, a.y0() + 0.5, a.z() - 0.5),
                    TvProfile.toWorld(be.getBlockPos(), be.facing(), a.x1() - 0.5, a.y1() - 0.5, a.z() - 0.5)};
            for (Vec3 target : targets) {
                Vec3 approach = target.add(TvProfile.normal(be.facing()).scale(0.05));
                BlockHitResult hit = mc.level.clip(new ClipContext(eye, approach, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == HitResult.Type.BLOCK && !(mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof com.otectus.otetv.block.TvBlock)) {
                    blocked++;
                }
            }
            occluded = blocked >= 2;
        }
        session.markRendered(widthPx, heightPx, occluded || facingDot < 0.15);
    }

    /**
     * Animates dials toward the angle for the confirmed channel. Per-TV animation state is keyed weakly by block
     * entity, never stored in the shared renderer instance.
     */
    static final class DialAnimator {
        private static final java.util.Map<TvBlockEntity, float[]> ANGLES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        private DialAnimator() {
        }

        static float angleFor(TvBlockEntity be, TvPublicState pub) {
            float target = pub.powered() ? (pub.channelNumber() % 12) * 30f : -45f;
            float[] current = ANGLES.computeIfAbsent(be, k -> new float[]{target});
            float delta = target - current[0];
            float step = OteTvClientConfig.REDUCE_MOTION_AND_FLICKER.get() ? 12f : 6f;
            current[0] += Math.max(-step, Math.min(step, delta));
            return current[0];
        }
    }
}
