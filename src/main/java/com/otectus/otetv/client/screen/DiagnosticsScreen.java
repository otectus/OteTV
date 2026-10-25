package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.client.tv.ClientPlaybackSession;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.core.OteTvCore;
import com.otectus.otetv.core.capability.CodecMatrix;
import com.otectus.otetv.core.redact.Redactor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Locally generated, redacted technical details (sections 7.4 and 16.4). Shared only if the player copies them; the
 * text never contains cookies, tokens, query strings, local paths or account identifiers.
 */
public class DiagnosticsScreen extends OteTvScreen {
    @Nullable
    private final UUID tv;
    @Nullable
    private final String serverSummary;
    private int scroll;

    public DiagnosticsScreen(@Nullable Screen parent, @Nullable UUID tv) {
        this(parent, tv, null);
    }

    public DiagnosticsScreen(@Nullable Screen parent, @Nullable UUID tv, @Nullable String serverSummary) {
        super(Component.translatable("otetv.screen.diagnostics"), parent);
        this.tv = tv;
        this.serverSummary = serverSummary;
        this.panelWidth = 360;
        this.panelHeight = 230;
    }

    public static List<String> collect(@Nullable UUID tv, @Nullable String serverSummary) {
        List<String> lines = new ArrayList<>();
        String version = ModList.get().getModContainerById(OteTvCore.MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        lines.add("OteTV " + version + ", network protocol " + OteTvCore.NETWORK_PROTOCOL_VERSION + ", worker protocol " + OteTvCore.IPC_PROTOCOL_VERSION);
        lines.add("platform: " + RuntimeManager.platform().id() + ", Java " + System.getProperty("java.version"));
        RuntimeManager.MediaRuntimeInfo media = RuntimeManager.mediaInfo();
        lines.add("media runtime: " + media.status() + " " + media.runtimeVersion() + (media.detail().isEmpty() ? "" : " - " + media.detail()));
        for (CodecMatrix.Result r : media.codecs()) {
            lines.add("  " + r.combination().id() + ": " + r.status() + (r.missing().isEmpty() ? "" : " missing " + r.missing()));
        }
        RuntimeManager.BrowserRuntimeInfo browser = RuntimeManager.browserInfo();
        lines.add("browser runtime: " + browser.status() + " " + browser.bundle());
        if (serverSummary != null && !serverSummary.isEmpty()) {
            lines.add(serverSummary);
        }
        for (ClientPlaybackSession s : ClientTvManager.get().allSessions()) {
            if (tv == null || s.tvId().equals(tv)) {
                lines.addAll(s.diagnostics());
            }
        }
        List<String> redacted = new ArrayList<>(lines.size());
        for (String l : lines) {
            redacted.add(Redactor.redact(l));
        }
        return redacted;
    }

    @Override
    protected void init() {
        int l = left();
        int y = top() + panelHeight - 26 - 12;
        addRenderableWidget(button(Component.translatable("otetv.diagnostics.copy"), b -> {
            minecraft.keyboardHandler.setClipboard(String.join("\n", collect(tv, serverSummary)));
            setStatus(Component.translatable("otetv.diagnostics.copied"), false);
        }, l, y, 160, 20));
        addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l + panelWidth - 80, y, 80, 20));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta) * 3);
        return true;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<String> lines = collect(tv, serverSummary);
        int y = top() + 14;
        int max = (panelHeight - 54) / 10;
        for (int i = scroll; i < Math.min(lines.size(), scroll + max); i++) {
            g.drawString(font, font.plainSubstrByWidth(lines.get(i), panelWidth), left(), y, textColor());
            y += 10;
        }
    }
}
