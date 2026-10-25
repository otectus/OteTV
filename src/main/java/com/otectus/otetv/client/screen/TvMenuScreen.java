package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.client.tv.CatalogCache;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.core.capability.ActionAvailability;
import com.otectus.otetv.core.capability.PlayerAction;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.source.SourceType;
import com.otectus.otetv.core.state.ErrorCode;
import com.otectus.otetv.core.state.LocalPlaybackState;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Using a TV opens this menu (section 7.1): Watch, Choose source and Settings, plus power and recovery actions that
 * keep a TV usable without a remote. A shared TV shows its title and "Join viewing"; nothing is fetched until the
 * player joins and, for external hosts, consents.
 */
public class TvMenuScreen extends OteTvScreen {
    private final UUID tv;
    private long seenRevision = -1;
    private LocalPlaybackState seenLocal;
    private final Consumer<S2C.ActionResult> listener = this::onResult;

    public TvMenuScreen(UUID tv) {
        super(Component.translatable("otetv.screen.menu.title"), null);
        this.tv = tv;
        this.panelWidth = 300;
        this.panelHeight = 232;
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
    }

    private void onResult(S2C.ActionResult r) {
        if (r.actionId() == -1 && !r.accepted()) {
            setStatus(Component.translatable("otetv.remote.unavailable"), true);
        } else if (r.accepted()) {
            setStatus(Component.translatable("otetv.screen.done"), false);
        } else {
            setStatus(Component.translatable(r.reason().translationKey()), true);
        }
    }

    private void rebuild() {
        clearWidgets();
        ClientTvManager mgr = ClientTvManager.get();
        TvSnapshot s = mgr.snapshot(tv);
        ClientPlaybackSession session = mgr.session(tv);
        int l = left();
        int t = top() + 62;
        int bw = 144;
        int col2 = l + panelWidth - bw;
        if (s == null) {
            addRenderableWidget(button(Component.translatable("gui.done"), b -> onClose(), l + (panelWidth - 100) / 2, top() + panelHeight - 26, 100, 20));
            return;
        }
        boolean joined = mgr.isJoined(tv);
        ActionAvailability.Context ctx = new ActionAvailability.Context(s.permissions(), s.hasLeaseHolder() && !s.leaseIsYours(),
                s.yourRole().atLeast(com.otectus.otetv.core.protocol.TvRole.OWNER), s.powered(),
                s.source() == null ? null : s.source().type(), session == null || session.backend() == null ? null : session.backend().status().capabilities(),
                joined, com.otectus.otetv.config.OteTvClientConfig.DISABLE_EXTERNAL_MEDIA.get(), session != null && session.hasCaptions(),
                true, session != null && session.isDiverged(), true);
        addRenderableWidget(button(Component.translatable(s.powered() ? "otetv.action.power_off" : "otetv.action.power_on"),
                b -> mgr.sendAction(tv, TvActionType.SET_POWER, 0, s.powered() ? 0 : 1, "", null, null), l, t, bw, 20,
                reason(PlayerAction.POWER, ctx)));
        if (joined) {
            addRenderableWidget(button(Component.translatable("otetv.action.leave"), b -> {
                mgr.leave(tv);
                rebuild();
            }, col2, t, bw, 20));
        } else {
            addRenderableWidget(button(Component.translatable("otetv.screen.watch"), b -> {
                mgr.join(tv);
                rebuild();
            }, col2, t, bw, 20, reason(PlayerAction.JOIN, ctx)));
        }
        t += 24;
        boolean canPick = s.permissions().contains(TvPermission.SOURCE) || s.permissions().contains(TvPermission.CHANNEL);
        addRenderableWidget(button(Component.translatable("otetv.screen.choose_source"), b -> minecraft.setScreen(new SourcePickerScreen(this, tv)),
                l, t, bw, 20, canPick ? null : Component.translatable("otetv.disabled.no_permission")));
        addRenderableWidget(button(Component.translatable("otetv.screen.remote"), b -> minecraft.setScreen(new RemoteScreen(this, tv, null)), col2, t, bw, 20));
        t += 24;
        addRenderableWidget(button(Component.translatable("otetv.screen.viewing_mode"), b -> minecraft.setScreen(new ViewingScreen(this, tv)),
                l, t, bw, 20, session == null ? Component.translatable("otetv.disabled.not_joined") : null));
        boolean canSettings = s.permissions().contains(TvPermission.SETTINGS) || s.permissions().contains(TvPermission.ACL);
        addRenderableWidget(button(Component.translatable("otetv.screen.settings"), b -> minecraft.setScreen(new TvSettingsScreen(this, tv)),
                col2, t, bw, 20, canSettings ? null : Component.translatable("otetv.disabled.no_permission")));
        t += 24;
        // Context-specific recovery actions (section 7.4).
        if (session != null && session.state() == LocalPlaybackState.NEEDS_CONSENT) {
            addRenderableWidget(button(Component.translatable("otetv.screen.allow_host", session.waitingHost()),
                    b -> minecraft.setScreen(new ConsentScreen(this, session.waitingHost())), l, t, panelWidth, 20));
            t += 24;
        } else if (session != null && session.error() == ErrorCode.RUNTIME_MISSING) {
            boolean browser = session.isYouTube();
            addRenderableWidget(button(Component.translatable("otetv.screen.install_runtime"),
                    b -> minecraft.setScreen(new RuntimeSetupScreen(this, browser)), l, t, bw, 20));
            if (browser && s.source() != null) {
                addRenderableWidget(button(Component.translatable("otetv.screen.open_youtube"),
                        ConfirmLinkScreen.confirmLink(s.source().youtube().orElseThrow().watchUrl(), this, false), col2, t, bw, 20));
            }
            t += 24;
        } else if (session != null && (session.error() == ErrorCode.LOCAL_FILE_ABSENT || session.error() == ErrorCode.LOCAL_FILE_MISMATCH)) {
            addRenderableWidget(button(Component.translatable("otetv.screen.select_matching_file"),
                    b -> minecraft.setScreen(SourcePickerScreen.filesTab(this, tv)), l, t, panelWidth, 20));
            t += 24;
        } else if (session != null && session.state().isFailure()) {
            addRenderableWidget(button(Component.translatable("otetv.screen.retry"), b -> session.retryNow(), l, t, bw, 20));
            addRenderableWidget(button(Component.translatable("otetv.screen.diagnostics"),
                    b -> minecraft.setScreen(new DiagnosticsScreen(this, tv)), col2, t, bw, 20));
            t += 24;
            if (s.source() != null && s.source().type() == SourceType.YOUTUBE) {
                // Embedding disabled, unavailable videos and a browser runtime that cannot run sandboxed all still
                // leave the official site, opened in the player's own browser after confirmation.
                addRenderableWidget(button(Component.translatable("otetv.screen.open_youtube"),
                        ConfirmLinkScreen.confirmLink(s.source().youtube().orElseThrow().watchUrl(), this, false), l, t, panelWidth, 20));
                t += 24;
            }
        } else if (session != null && session.isWaitingForLocalResume()) {
            addRenderableWidget(button(Component.translatable("otetv.screen.press_play"), b -> session.localResume(), l, t, panelWidth, 20));
            t += 24;
        } else if (s.source() != null && s.source().type() == SourceType.YOUTUBE) {
            addRenderableWidget(button(Component.translatable("otetv.screen.open_youtube"),
                    ConfirmLinkScreen.confirmLink(s.source().youtube().orElseThrow().watchUrl(), this, false), l, t, panelWidth, 20));
            t += 24;
        }
        if (session != null && mgr.hasPrivate(tv)) {
            addRenderableWidget(button(Component.translatable("otetv.screen.stop_private"), b -> {
                mgr.stopPrivate(tv);
                rebuild();
            }, l, t, panelWidth, 20));
        }
        int bottom = top() + panelHeight - 26;
        addRenderableWidget(button(Component.translatable("otetv.screen.privacy"), b -> minecraft.setScreen(new PrivacyScreen(this)), l, bottom, 96, 20));
        addRenderableWidget(button(Component.translatable("otetv.screen.diagnostics"), b -> minecraft.setScreen(new DiagnosticsScreen(this, tv)),
                l + 102, bottom, 96, 20));
        addRenderableWidget(button(Component.translatable("gui.done"), b -> onClose(), l + 204, bottom, 96, 20));
    }

    private static Component reason(PlayerAction action, ActionAvailability.Context ctx) {
        ActionAvailability.State st = ActionAvailability.evaluate(action, ctx);
        return st.enabled() ? null : Component.translatable(st.reasonKey());
    }

    @Override
    public void tick() {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        ClientPlaybackSession session = ClientTvManager.get().session(tv);
        long rev = s == null ? -1 : s.revision();
        LocalPlaybackState local = session == null ? null : session.state();
        if (rev != seenRevision || local != seenLocal) {
            seenRevision = rev;
            seenLocal = local;
            rebuild();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        int l = left();
        int y = top() + 14;
        if (s == null) {
            g.drawString(font, Component.translatable("otetv.screen.loading"), l, y, mutedColor());
            return;
        }
        String name = s.displayName().isEmpty() ? Component.translatable("block.otetv." + s.model()).getString() : s.displayName();
        g.drawString(font, name, l, y, textColor());
        g.drawString(font, Component.translatable("otetv.screen.owner", s.ownerName().isEmpty() ? "-" : s.ownerName(),
                Component.translatable(s.yourRole().translationKey())), l, y + 11, mutedColor());
        String source;
        if (s.source() == null) {
            source = Component.translatable("otetv.screen.no_source").getString();
        } else if (s.channelNumber() > 0) {
            String channel = CatalogCache.localize(s.channelLabel());
            String label = s.source().displayLabel();
            source = Component.translatable("otetv.screen.channel", s.channelNumber()).getString() + " " + channel
                    + (label.isEmpty() || label.equals(channel) ? "" : " - " + label);
        } else {
            source = s.source().displayLabel();
        }
        g.drawString(font, font.plainSubstrByWidth(source, panelWidth), l, y + 22, textColor());
        ClientPlaybackSession session = ClientTvManager.get().session(tv);
        Component mode = s.source() == null ? Component.empty() : Component.translatable(sourceModeKey(s));
        Component local = session == null ? Component.translatable("otetv.local_state.not_joined") : Component.translatable(session.state().translationKey());
        g.drawString(font, Component.translatable("otetv.screen.state_line", Component.translatable("otetv.play_state." + s.timeline().state().name().toLowerCase(java.util.Locale.ROOT)),
                local, mode), l, y + 33, mutedColor());
        if (session != null && session.state().isFailure()) {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable(session.error().translationKey()).getString(), panelWidth), l, y + 44, 0xFFFF8A65);
        }
    }

    /** Plain source mode labels (section 7.2): in-world playback, open in browser, experimental, unavailable. */
    private static String sourceModeKey(TvSnapshot s) {
        if (s.source().type() == SourceType.YOUTUBE) {
            if (!s.youtubeAllowed()) {
                return "otetv.mode.unavailable_server";
            }
            return RuntimeManager.browserInfo().status() == RuntimeManager.BrowserStatus.INSTALLED ? "otetv.mode.in_world" : "otetv.mode.unavailable_platform";
        }
        return "otetv.mode.in_world";
    }
}
