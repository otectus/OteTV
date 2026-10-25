package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.media.PlaybackBackend;
import com.otectus.otetv.client.media.VideoTexture;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.captions.Cue;
import com.otectus.otetv.core.ipc.Msg;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Enlarged viewing tied to a world TV (section 7.3): the picture on the left, the remote beside it, never on top of
 * the official player. Stereo listener-relative audio for direct media. For YouTube, clicking the picture routes
 * mouse and keyboard to the player; Escape first releases that input, then closes, so movement keys never stick.
 */
public class ViewingScreen extends OteTvScreen {
    private final UUID tv;
    private boolean captured;
    private int vx;
    private int vy;
    private int vw;
    private int vh;
    private long seenRevision = -2;

    public ViewingScreen(@Nullable Screen parent, UUID tv) {
        super(Component.translatable("otetv.screen.viewing.title"), parent);
        this.tv = tv;
    }

    @Nullable
    private ClientPlaybackSession session() {
        return ClientTvManager.get().session(tv);
    }

    @Override
    protected void init() {
        ClientPlaybackSession s = session();
        if (s != null) {
            s.setViewingMode(true);
        }
        int column = Math.max(110, width / 5);
        int areaW = width - column - 24;
        int areaH = height - 40;
        vw = areaW;
        vh = (int) (areaW * 9 / 16.0);
        if (vh > areaH) {
            vh = areaH;
            vw = (int) (areaH * 16 / 9.0);
        }
        vx = 8 + (areaW - vw) / 2;
        vy = 20 + (areaH - vh) / 2;
        rebuild(column);
    }

    private void rebuild(int column) {
        clearWidgets();
        int x = width - column - 8;
        int y = 24;
        int w = column;
        TvSnapshot snap = ClientTvManager.get().snapshot(tv);
        ClientPlaybackSession s = session();
        boolean playing = snap != null && snap.timeline().state() == PlayState.PLAYING;
        boolean privateMode = s != null && s.mode() == ClientPlaybackSession.Mode.PRIVATE;
        addRenderableWidget(button(Component.translatable(playing && !privateMode ? "otetv.remote.pause" : "otetv.remote.play"), b -> {
            if (privateMode) {
                s.localTogglePause();
            } else {
                ClientTvManager.get().sendAction(tv, playing ? TvActionType.PAUSE : TvActionType.PLAY);
            }
        }, x, y, w, 20));
        y += 24;
        addRenderableWidget(button(Component.literal("-10s"), b -> seek(-10_000), x, y, w / 2 - 2, 20));
        addRenderableWidget(button(Component.literal("+10s"), b -> seek(10_000), x + w / 2 + 2, y, w / 2 - 2, 20));
        y += 24;
        addRenderableWidget(button(Component.translatable("otetv.remote.stop"), b -> ClientTvManager.get().sendAction(tv, TvActionType.STOP), x, y, w, 20));
        y += 24;
        addRenderableWidget(button(Component.translatable("otetv.remote.ch_down"), b -> ClientTvManager.get().sendAction(tv, TvActionType.CHANNEL_STEP, 0, -1, "", null, null),
                x, y, w / 2 - 2, 20));
        addRenderableWidget(button(Component.translatable("otetv.remote.ch_up"), b -> ClientTvManager.get().sendAction(tv, TvActionType.CHANNEL_STEP, 0, 1, "", null, null),
                x + w / 2 + 2, y, w / 2 - 2, 20));
        y += 24;
        addRenderableWidget(button(Component.translatable(s != null && s.isLocalMuted() ? "otetv.remote.unmute_me" : "otetv.remote.mute_me"), b -> {
            if (s != null) {
                s.setLocalMute(!s.isLocalMuted());
                rebuild(column);
            }
        }, x, y, w, 20));
        y += 24;
        boolean cc = OteTvClientConfig.CAPTIONS.get();
        addRenderableWidget(button(Component.translatable(cc ? "otetv.remote.cc_on" : "otetv.remote.cc_off"), b -> {
            OteTvClientConfig.CAPTIONS.set(!cc);
            if (s != null && s.backend() != null) {
                s.backend().setCaptions(!cc);
            }
            rebuild(column);
        }, x, y, w, 20));
        y += 24;
        if (s != null && s.isDiverged()) {
            addRenderableWidget(button(Component.translatable("otetv.action.rejoin_position"), b -> s.rejoinCurrentPosition(), x, y, w, 20));
            y += 24;
        }
        if (s != null && s.isWaitingForLocalResume()) {
            addRenderableWidget(button(Component.translatable("otetv.screen.press_play"), b -> s.localResume(), x, y, w, 20));
            y += 24;
        }
        addRenderableWidget(button(Component.translatable("otetv.screen.viewing.exit"), b -> onClose(), x, height - 28, w, 20));
    }

    private void seek(long delta) {
        ClientPlaybackSession s = session();
        if (s != null && s.mode() == ClientPlaybackSession.Mode.PRIVATE) {
            s.localSeekRelative(delta);
        } else {
            ClientTvManager.get().sendAction(tv, TvActionType.SEEK_RELATIVE, delta, 0, "", null, null);
        }
    }

    @Override
    public void removed() {
        ClientPlaybackSession s = session();
        if (s != null) {
            s.setViewingMode(false);
        }
        captured = false;
    }

    @Override
    public void tick() {
        TvSnapshot snap = ClientTvManager.get().snapshot(tv);
        long rev = snap == null ? -1 : snap.revision();
        if (rev != seenRevision) {
            seenRevision = rev;
            rebuild(Math.max(110, width / 5));
        }
        if (session() == null) {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF000000);
        ClientPlaybackSession s = session();
        if (s != null) {
            s.markViewed();
            VideoTexture tex = s.prepareFrame();
            if (tex != null) {
                float aspect = (float) tex.width() / Math.max(1, tex.height());
                int w = vw;
                int h = (int) (vw / aspect);
                if (h > vh) {
                    h = vh;
                    w = (int) (vh * aspect);
                }
                int x = vx + (vw - w) / 2;
                int y = vy + (vh - h) / 2;
                g.blit(tex.location(), x, y, 0, 0, w, h, w, h);
                if (captured) {
                    g.renderOutline(x - 1, y - 1, w + 2, h + 2, 0xFF55FFFF);
                }
            } else {
                g.drawCenteredString(font, Component.translatable(s.state().translationKey()), vx + vw / 2, vy + vh / 2, 0xFFFFFFFF);
            }
            Cue cue = s.activeCue();
            if (cue != null && !s.isYouTube()) {
                int cy = Math.min(height - 30, vy + vh + 4);
                float scale = (float) (1.5 * OteTvClientConfig.CAPTION_SCALE.get());
                g.pose().pushPose();
                g.pose().scale(scale, scale, 1);
                for (String line : cue.text().split("\n")) {
                    for (FormattedCharSequence seq : font.split(Component.literal(line), (int) (vw / scale))) {
                        int lw = font.width(seq);
                        int lx = (int) ((vx + vw / 2f) / scale - lw / 2f);
                        g.fill(lx - 2, (int) (cy / scale) - 1, lx + lw + 2, (int) (cy / scale) + 9, 0xB0000000);
                        g.drawString(font, seq, lx, (int) (cy / scale), 0xFFFFFFFF, false);
                        cy += (int) (10 * scale);
                    }
                }
                g.pose().popPose();
            }
            String position = RemoteScreen.format(s.positionMs()) + (s.durationMs() > 0 ? " / " + RemoteScreen.format(s.durationMs()) : "");
            g.drawString(font, position, vx, 6, 0xFFB0B0B0);
            if (s.isYouTube()) {
                g.drawString(font, Component.translatable(captured ? "otetv.screen.viewing.input_captured" : "otetv.screen.viewing.click_to_interact"),
                        vx + 120, 6, 0xFFB0B0B0);
            }
        }
        for (var renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }
    }

    // ---- YouTube input routing -------------------------------------------------------------------------------------

    private boolean overVideo(double mx, double my) {
        return mx >= vx && my >= vy && mx < vx + vw && my < vy + vh;
    }

    /** Maps screen coordinates to the browser viewport with the same letterbox transform used for drawing. */
    private int[] toViewport(double mx, double my) {
        ClientPlaybackSession s = session();
        PlaybackBackend b = s == null ? null : s.backend();
        int bw = b == null ? 1280 : Math.max(1, b.status().videoWidth());
        int bh = b == null ? 720 : Math.max(1, b.status().videoHeight());
        if (bw <= 1 || bh <= 1) {
            bw = 1280;
            bh = 720;
        }
        float aspect = (float) bw / bh;
        int w = vw;
        int h = (int) (vw / aspect);
        if (h > vh) {
            h = vh;
            w = (int) (vh * aspect);
        }
        int x = vx + (vw - w) / 2;
        int y = vy + (vh - h) / 2;
        int px = (int) ((mx - x) * bw / w);
        int py = (int) ((my - y) * bh / h);
        return new int[]{Math.max(0, Math.min(bw - 1, px)), Math.max(0, Math.min(bh - 1, py))};
    }

    private boolean youtube() {
        ClientPlaybackSession s = session();
        return s != null && s.isYouTube() && s.backend() != null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (youtube() && overVideo(mx, my)) {
            captured = true;
            int[] p = toViewport(mx, my);
            session().backend().mouse(Msg.Mouse.DOWN, p[0], p[1], Math.min(button, 2), 0, 0);
            return true;
        }
        if (!overVideo(mx, my)) {
            captured = false;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (youtube() && captured && overVideo(mx, my)) {
            int[] p = toViewport(mx, my);
            session().backend().mouse(Msg.Mouse.UP, p[0], p[1], Math.min(button, 2), 0, 0);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (youtube() && overVideo(mx, my)) {
            int[] p = toViewport(mx, my);
            session().backend().mouse(Msg.Mouse.MOVE, p[0], p[1], 0, 0, 0);
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (youtube() && captured && overVideo(mx, my)) {
            int[] p = toViewport(mx, my);
            session().backend().mouse(Msg.Mouse.WHEEL, p[0], p[1], 0, 0, (int) (delta * 120));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && captured) {
            captured = false; // first Escape releases player input
            return true;
        }
        if (youtube() && captured) {
            session().backend().key(Msg.Key.DOWN, key, scan, mods, 0);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (youtube() && captured) {
            session().backend().key(Msg.Key.UP, key, scan, mods, 0);
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (youtube() && captured) {
            session().backend().key(Msg.Key.CHAR, -1, 0, mods, c);
            return true;
        }
        return super.charTyped(c, mods);
    }
}
