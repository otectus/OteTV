package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.media.RuntimeInstaller;
import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.core.capability.CodecMatrix;
import com.otectus.otetv.core.runtime.RuntimeBundle;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * "Video playback needs an additional component" (section 7.4): explains what is missing for this platform, shows
 * the detected codec capability, and offers the supported installation paths (documented manual install, verified
 * bundle download from a trusted manifest, or a bundle file the player already has), plus rollback and uninstall.
 */
public class RuntimeSetupScreen extends OteTvScreen {
    public static final String MEDIA_HELP = "https://gstreamer.freedesktop.org/download/";
    private final boolean browser;
    @Nullable
    private RuntimeInstaller.Job job;

    public RuntimeSetupScreen(@Nullable Screen parent, boolean browser) {
        super(Component.translatable(browser ? "otetv.runtime.browser_title" : "otetv.runtime.media_title"), parent);
        this.browser = browser;
        this.panelWidth = 340;
        this.panelHeight = 240;
    }

    @Override
    protected void init() {
        if (!browser && RuntimeManager.mediaInfo().status() == RuntimeManager.MediaStatus.UNKNOWN) {
            RuntimeManager.probeMedia();
        }
        rebuild();
    }

    private String kind() {
        return browser ? "browser" : "gstreamer";
    }

    private void rebuild() {
        clearWidgets();
        int l = left();
        int y = top() + panelHeight - 50 - 12;
        List<RuntimeBundle> bundles = RuntimeManager.availableBundles().stream()
                .filter(b -> b.id().contains(browser ? "browser" : "gstreamer")).toList();
        if (!bundles.isEmpty()) {
            RuntimeBundle bundle = bundles.get(bundles.size() - 1);
            String size = (bundle.size() / (1024 * 1024)) + " MiB";
            addRenderableWidget(button(Component.translatable("otetv.runtime.download", size), b -> minecraft.setScreen(new ConfirmScreen(ok -> {
                if (ok) {
                    job = RuntimeInstaller.download(bundle);
                }
                minecraft.setScreen(this);
            }, Component.translatable("otetv.runtime.confirm_title"), Component.translatable("otetv.runtime.confirm_body", bundle.directoryName(), size,
                    RuntimeManager.runtimeDir().resolve(kind()).toString()))), l, y, 166, 20));
            addRenderableWidget(button(Component.translatable("otetv.runtime.from_file"), b -> installFromFile(bundle), l + 174, y, 166, 20));
            y -= 24;
        }
        if (!browser) {
            addRenderableWidget(button(Component.translatable("otetv.runtime.help"), ConfirmLinkScreen.confirmLink(MEDIA_HELP, this, true), l, y, 110, 20));
            addRenderableWidget(button(Component.translatable("otetv.runtime.check_again"), b -> {
                RuntimeManager.invalidateProbe();
                RuntimeManager.probeMedia();
            }, l + 115, y, 110, 20));
        } else {
            addRenderableWidget(button(Component.translatable("otetv.runtime.rollback"), b -> {
                try {
                    setStatus(Component.translatable(RuntimeInstaller.rollback(kind()) ? "otetv.screen.done" : "otetv.runtime.no_rollback"), false);
                } catch (Exception e) {
                    setStatus(Component.literal(e.getMessage()), true);
                }
            }, l, y, 110, 20));
            addRenderableWidget(button(Component.translatable("otetv.runtime.uninstall"), b -> {
                try {
                    RuntimeInstaller.uninstall(kind());
                    setStatus(Component.translatable("otetv.screen.done"), false);
                } catch (Exception e) {
                    setStatus(Component.literal(e.getMessage()), true);
                }
            }, l + 115, y, 110, 20));
        }
        addRenderableWidget(button(Component.translatable("otetv.runtime.open_folder"),
                b -> Util.getPlatform().openFile(RuntimeManager.runtimeDir().toFile()), l + 230, y, 110, 20));
        addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l + panelWidth - 80, top() + panelHeight - 26 - 12, 80, 20));
        if (job != null && !job.finished()) {
            addRenderableWidget(button(Component.translatable("gui.cancel"), b -> job.cancel(), l, top() + panelHeight - 26 - 12, 80, 20));
        }
    }

    private void installFromFile(RuntimeBundle bundle) {
        String chosen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.zip"));
            filters.flip();
            chosen = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable("otetv.runtime.from_file").getString(), "", filters, "OteTV runtime bundle", false);
        } catch (Throwable t) {
            setStatus(Component.translatable("otetv.picker.dialog_unavailable"), true);
            return;
        }
        if (chosen != null) {
            job = RuntimeInstaller.installFromFile(bundle, Path.of(chosen));
            rebuild();
        }
    }

    @Override
    public void tick() {
        if (job != null && job.finished() && job.phase == RuntimeInstaller.Phase.DONE) {
            setStatus(Component.translatable("otetv.runtime.installed", job.message), false);
            job = null;
            rebuild();
        } else if (job != null && job.finished()) {
            setStatus(Component.translatable("otetv.runtime.failed", job.message), true);
            job = null;
            rebuild();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int l = left();
        int y = top() + 14;
        for (FormattedCharSequence line : font.split(Component.translatable(browser ? "otetv.runtime.browser_body" : "otetv.runtime.media_body"), panelWidth)) {
            g.drawString(font, line, l, y, textColor());
            y += 10;
        }
        y += 4;
        if (browser) {
            RuntimeManager.BrowserRuntimeInfo info = RuntimeManager.browserInfo();
            g.drawString(font, Component.translatable("otetv.runtime.browser_status." + info.status().name().toLowerCase(Locale.ROOT), info.bundle()), l, y, accentColor());
            y += 12;
            for (FormattedCharSequence line : font.split(Component.translatable("otetv.runtime.browser_sandbox_note"), panelWidth)) {
                g.drawString(font, line, l, y, mutedColor());
                y += 10;
            }
        } else {
            RuntimeManager.MediaRuntimeInfo info = RuntimeManager.mediaInfo();
            g.drawString(font, Component.translatable("otetv.runtime.media_status." + info.status().name().toLowerCase(Locale.ROOT), info.runtimeVersion()), l, y, accentColor());
            y += 12;
            if (!info.detail().isEmpty()) {
                g.drawString(font, font.plainSubstrByWidth(com.otectus.otetv.core.redact.Redactor.redact(info.detail()), panelWidth), l, y, 0xFFFF8A65);
                y += 11;
            }
            for (CodecMatrix.Result r : info.codecs()) {
                if (y > top() + panelHeight - 90) {
                    break;
                }
                CodecMatrix.Combination c = r.combination();
                String mark = switch (r.status()) {
                    case SUPPORTED -> "✔";
                    case MISSING_COMPONENTS -> "✖";
                    case UNSUPPORTED -> "—";
                };
                String text = mark + " " + c.container() + " " + c.video() + " + " + c.audio()
                        + (r.missing().isEmpty() ? "" : "  (" + Component.translatable("otetv.runtime.missing").getString() + " " + String.join(", ", r.missing()) + ")");
                g.drawString(font, font.plainSubstrByWidth(text, panelWidth), l, y, r.status() == CodecMatrix.Status.SUPPORTED ? 0xFF8CE68C : mutedColor());
                y += 10;
            }
        }
        if (job != null) {
            String progress = job.total > 0 ? (job.done * 100 / job.total) + "%" : "";
            g.drawString(font, Component.translatable("otetv.runtime.phase." + job.phase.name().toLowerCase(Locale.ROOT), progress), l, top() + panelHeight - 84, accentColor());
        }
    }
}
