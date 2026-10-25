package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.config.OteTvClientConfig;
import com.otectus.otetv.core.capability.ActionAvailability;
import com.otectus.otetv.core.capability.PlayerAction;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvRole;
import com.otectus.otetv.core.state.PlayState;
import com.otectus.otetv.network.C2S;
import com.otectus.otetv.network.OteTvNetwork;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The universal remote (section 6.2): power, home, back, directional navigation, select, play/pause, stop, ten-second
 * seek, channels, captions, the shared TV speaker level and the personal mute. Shared operations go through the
 * server-approved controller with a renewable lease; local ones never leave this client. Unsupported actions stay
 * visible but disabled, with the reason as a tooltip.
 */
public class RemoteScreen extends OteTvScreen {
    private final UUID tv;
    @Nullable
    private final InteractionHand hand;
    private boolean unavailable;
    private long seenRevision = -2;
    private long nextLeaseRenew;
    private final Consumer<S2C.ActionResult> listener = this::onResult;

    public RemoteScreen(@Nullable Screen parent, UUID tv, @Nullable InteractionHand hand) {
        super(Component.translatable("otetv.screen.remote.title"), parent);
        this.tv = tv;
        this.hand = hand;
        this.panelWidth = 230;
        this.panelHeight = 262;
    }

    @Override
    protected void init() {
        ClientTvManager.get().requestSnapshot(tv);
        ClientTvManager.get().addResultListener(listener);
        rebuild();
    }

    @Override
    public void removed() {
        ClientTvManager.get().removeResultListener(listener);
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        if (s != null && s.leaseIsYours()) {
            OteTvNetwork.sendToServer(new C2S.ReleaseControl(tv, s.leaseGeneration()));
        }
    }

    private void onResult(S2C.ActionResult r) {
        if (r.actionId() == -1 && !r.accepted() && ClientTvManager.get().snapshot(tv) == null) {
            unavailable = true;
            rebuild();
        } else if (!r.accepted()) {
            setStatus(r.reason() == com.otectus.otetv.core.protocol.RejectReason.LEASE_HELD && !r.detail().isEmpty()
                    ? Component.translatable("otetv.reject.lease_held_by", r.detail()) : Component.translatable(r.reason().translationKey()), true);
        } else {
            setStatus(Component.empty(), false);
        }
    }

    private void rebuild() {
        clearWidgets();
        int l = left();
        int t = top() + 34;
        ClientTvManager mgr = ClientTvManager.get();
        TvSnapshot s = mgr.snapshot(tv);
        if (s == null || unavailable) {
            if (hand != null) {
                addRenderableWidget(button(Component.translatable("otetv.remote.unpair"), b -> {
                    OteTvNetwork.sendToServer(new C2S.UnpairRemote(hand));
                    onClose();
                }, l, top() + panelHeight - 50, panelWidth, 20));
            }
            addRenderableWidget(button(Component.translatable("gui.done"), b -> onClose(), l, top() + panelHeight - 26, panelWidth, 20));
            return;
        }
        ClientPlaybackSession session = mgr.session(tv);
        ActionAvailability.Context ctx = new ActionAvailability.Context(s.permissions(), s.hasLeaseHolder() && !s.leaseIsYours(),
                s.yourRole().atLeast(TvRole.OWNER), s.powered(), s.source() == null ? null : s.source().type(),
                session == null || session.backend() == null ? null : session.backend().status().capabilities(),
                mgr.isJoined(tv), OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.get(), session != null && session.hasCaptions(),
                true, session != null && session.isDiverged(), true);
        int w3 = (panelWidth - 8) / 3;
        int w4 = (panelWidth - 12) / 4;
        addRenderableWidget(button(Component.translatable("otetv.remote.power"),
                b -> act(TvActionType.SET_POWER, 0, s.powered() ? 0 : 1), l, t, w3, 20, why(PlayerAction.POWER, ctx)));
        addRenderableWidget(button(Component.translatable("otetv.remote.home"),
                b -> minecraft.setScreen(new TvMenuScreen(tv)), l + w3 + 4, t, w3, 20));
        addRenderableWidget(button(Component.translatable("otetv.remote.back"), b -> onClose(), l + 2 * (w3 + 4), t, w3, 20));
        t += 26;
        int cx = l + (panelWidth - 50) / 2;
        addRenderableWidget(button(Component.literal("▲"), b -> navigate(GLFW.GLFW_KEY_UP), cx, t, 50, 18, why(PlayerAction.CHANNEL_NEXT, ctx)));
        t += 20;
        addRenderableWidget(button(Component.literal("◀"), b -> navigate(GLFW.GLFW_KEY_LEFT), cx - 54, t, 50, 18, why(PlayerAction.SEEK_BACK, ctx)));
        addRenderableWidget(button(Component.translatable("otetv.remote.select"), b -> togglePlay(s), cx, t, 50, 18, why(PlayerAction.PLAY_PAUSE, ctx)));
        addRenderableWidget(button(Component.literal("▶"), b -> navigate(GLFW.GLFW_KEY_RIGHT), cx + 54, t, 50, 18, why(PlayerAction.SEEK_FORWARD, ctx)));
        t += 20;
        addRenderableWidget(button(Component.literal("▼"), b -> navigate(GLFW.GLFW_KEY_DOWN), cx, t, 50, 18, why(PlayerAction.CHANNEL_PREV, ctx)));
        t += 26;
        addRenderableWidget(button(Component.literal("-10s"), b -> act(TvActionType.SEEK_RELATIVE, -10_000, 0), l, t, w4, 20, why(PlayerAction.SEEK_BACK, ctx)));
        boolean playing = s.timeline().state() == PlayState.PLAYING;
        addRenderableWidget(button(Component.translatable(playing ? "otetv.remote.pause" : "otetv.remote.play"), b -> togglePlay(s),
                l + w4 + 4, t, w4, 20, why(PlayerAction.PLAY_PAUSE, ctx)));
        addRenderableWidget(button(Component.literal("+10s"), b -> act(TvActionType.SEEK_RELATIVE, 10_000, 0), l + 2 * (w4 + 4), t, w4, 20, why(PlayerAction.SEEK_FORWARD, ctx)));
        addRenderableWidget(button(Component.translatable("otetv.remote.stop"), b -> act(TvActionType.STOP, 0, 0), l + 3 * (w4 + 4), t, w4, 20, why(PlayerAction.STOP, ctx)));
        t += 24;
        addRenderableWidget(button(Component.translatable("otetv.remote.ch_down"), b -> act(TvActionType.CHANNEL_STEP, 0, -1), l, t, w3, 20, why(PlayerAction.CHANNEL_PREV, ctx)));
        addRenderableWidget(button(Component.translatable("otetv.remote.ch_up"), b -> act(TvActionType.CHANNEL_STEP, 0, 1), l + w3 + 4, t, w3, 20, why(PlayerAction.CHANNEL_NEXT, ctx)));
        boolean cc = OteTvClientConfig.CAPTIONS.get();
        addRenderableWidget(button(Component.translatable(cc ? "otetv.remote.cc_on" : "otetv.remote.cc_off"), b -> {
            OteTvClientConfig.CAPTIONS.set(!cc);
            if (session != null && session.backend() != null) {
                session.backend().setCaptions(!cc);
            }
            rebuild();
        }, l + 2 * (w3 + 4), t, w3, 20, why(PlayerAction.CAPTIONS, ctx)));
        t += 24;
        float gain = s.speakerGain();
        addRenderableWidget(button(Component.translatable("otetv.remote.vol_down"),
                b -> act(TvActionType.SET_SPEAKER_GAIN, Math.round(Math.max(0, gain - 0.1f) * 1000), 0), l, t, w3, 20, why(PlayerAction.SPEAKER_VOLUME, ctx)));
        addRenderableWidget(button(Component.translatable("otetv.remote.vol_up"),
                b -> act(TvActionType.SET_SPEAKER_GAIN, Math.round(Math.min(1, gain + 0.1f) * 1000), 0), l + w3 + 4, t, w3, 20, why(PlayerAction.SPEAKER_VOLUME, ctx)));
        boolean muted = session != null && session.isLocalMuted();
        addRenderableWidget(button(Component.translatable(muted ? "otetv.remote.unmute_me" : "otetv.remote.mute_me"), b -> {
            if (session != null) {
                session.setLocalMute(!session.isLocalMuted());
            }
            rebuild();
        }, l + 2 * (w3 + 4), t, w3, 20, why(PlayerAction.LOCAL_MUTE, ctx)));
        t += 24;
        addRenderableWidget(new AbstractSliderButton(l, t, panelWidth, 20, Component.empty(), OteTvClientConfig.LOCAL_VOLUME.get()) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("otetv.remote.my_volume", Math.round(value * 100)));
            }

            @Override
            protected void applyValue() {
                OteTvClientConfig.LOCAL_VOLUME.set(Math.round(value * 100) / 100.0);
            }
        });
        t += 26;
        if (s.hasLeaseHolder() && !s.leaseIsYours() || !s.hasLeaseHolder()) {
            addRenderableWidget(button(Component.translatable(s.hasLeaseHolder() ? "otetv.remote.reclaim" : "otetv.remote.take_control"),
                    b -> OteTvNetwork.sendToServer(new C2S.AcquireControl(tv, s.leaseGeneration())), l, t, w3 * 2 + 4, 20,
                    s.permissions().contains(com.otectus.otetv.core.protocol.TvPermission.PLAYBACK)
                            && (!s.hasLeaseHolder() || s.yourRole().atLeast(TvRole.OWNER)) ? null : Component.translatable("otetv.disabled.lease_held")));
        }
        if (hand != null) {
            addRenderableWidget(button(Component.translatable("otetv.remote.unpair"), b -> {
                OteTvNetwork.sendToServer(new C2S.UnpairRemote(hand));
                onClose();
            }, l + 2 * (w3 + 4), t, w3, 20));
        }
    }

    @Nullable
    private static Component why(PlayerAction action, ActionAvailability.Context ctx) {
        ActionAvailability.State st = ActionAvailability.evaluate(action, ctx);
        return st.enabled() ? null : Component.translatable(st.reasonKey());
    }

    private void act(TvActionType type, long longArg, int intArg) {
        ClientTvManager.get().sendAction(tv, type, longArg, intArg, "", null, null);
        playBeep();
    }

    private void playBeep() {
        if (OteTvClientConfig.STARTUP_SOUNDS.get() && minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(com.otectus.otetv.registry.ModSounds.REMOTE_BEEP.get(), 0.25f, 1.0f);
        }
    }

    /** Play/pause resolved into an explicit desired state before sending (no network toggle, section 9.4). */
    private void togglePlay(TvSnapshot s) {
        act(s.timeline().state() == PlayState.PLAYING ? TvActionType.PAUSE : TvActionType.PLAY, 0, 0);
    }

    /** D-pad: navigates inside the official YouTube player locally; otherwise channels and seeking. */
    private void navigate(int key) {
        ClientPlaybackSession session = ClientTvManager.get().session(tv);
        if (session != null && session.isYouTube() && session.backend() != null) {
            session.backend().key(com.otectus.otetv.core.ipc.Msg.Key.DOWN, key, 0, 0, 0);
            session.backend().key(com.otectus.otetv.core.ipc.Msg.Key.UP, key, 0, 0, 0);
            return;
        }
        switch (key) {
            case GLFW.GLFW_KEY_UP -> act(TvActionType.CHANNEL_STEP, 0, 1);
            case GLFW.GLFW_KEY_DOWN -> act(TvActionType.CHANNEL_STEP, 0, -1);
            case GLFW.GLFW_KEY_LEFT -> act(TvActionType.SEEK_RELATIVE, -10_000, 0);
            case GLFW.GLFW_KEY_RIGHT -> act(TvActionType.SEEK_RELATIVE, 10_000, 0);
            default -> {
            }
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        if (s != null && getFocused() == null) {
            switch (key) {
                case GLFW.GLFW_KEY_SPACE -> {
                    togglePlay(s);
                    return true;
                }
                case GLFW.GLFW_KEY_M -> {
                    ClientPlaybackSession session = ClientTvManager.get().session(tv);
                    if (session != null) {
                        session.setLocalMute(!session.isLocalMuted());
                        rebuild();
                    }
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void tick() {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        long rev = s == null ? -1 : s.revision();
        if (rev != seenRevision) {
            seenRevision = rev;
            if (s != null) {
                unavailable = false;
            }
            rebuild();
        }
        // Keep a held lease alive while the remote is open; it expires on its own once closed.
        long now = System.nanoTime();
        if (s != null && s.leaseIsYours() && now >= nextLeaseRenew) {
            nextLeaseRenew = now + 10_000_000_000L;
            OteTvNetwork.sendToServer(new C2S.AcquireControl(tv, s.leaseGeneration()));
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int l = left();
        int y = top() + 12;
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        if (s == null) {
            g.drawCenteredString(font, Component.translatable(unavailable ? "otetv.remote.unavailable" : "otetv.screen.loading"),
                    width / 2, y + 30, unavailable ? 0xFFFF8A65 : mutedColor());
            if (unavailable) {
                g.drawCenteredString(font, Component.translatable("otetv.remote.unavailable_hint"), width / 2, y + 44, mutedColor());
            }
            return;
        }
        String label = s.displayName().isEmpty() ? Component.translatable("block.otetv." + s.model()).getString() : s.displayName();
        g.drawString(font, font.plainSubstrByWidth(label, panelWidth), l, y, textColor());
        ClientPlaybackSession session = ClientTvManager.get().session(tv);
        String time = session == null ? "" : format(session.positionMs()) + (session.durationMs() > 0 ? " / " + format(session.durationMs()) : "");
        String lease = s.leaseIsYours() ? Component.translatable("otetv.remote.you_control").getString()
                : s.hasLeaseHolder() ? Component.translatable("otetv.remote.controlled_by", s.leaseHolder()).getString() : "";
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("otetv.play_state." + s.timeline().state().name().toLowerCase(Locale.ROOT)).getString()
                + "  " + time + "  " + lease, panelWidth), l, y + 11, mutedColor());
    }

    static String format(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long sec = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
