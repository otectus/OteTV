package com.otectus.otetv.client.screen;

import com.otectus.otetv.client.media.RuntimeManager;
import com.otectus.otetv.client.store.LocalMediaLibrary;
import com.otectus.otetv.client.store.PrivateHistory;
import com.otectus.otetv.client.tv.CatalogCache;
import com.otectus.otetv.client.tv.ClientTvManager;
import com.otectus.otetv.core.protocol.TvActionType;
import com.otectus.otetv.core.protocol.TvPermission;
import com.otectus.otetv.core.provider.SubscriptionService;
import com.otectus.otetv.core.source.MediaSource;
import com.otectus.otetv.core.source.SourceException;
import com.otectus.otetv.core.source.SourceInput;
import com.otectus.otetv.core.source.YouTubeRef;
import com.otectus.otetv.core.source.YouTubeUrls;
import com.otectus.otetv.network.S2C;
import com.otectus.otetv.network.TvSnapshot;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The source picker (section 7.2): server-approved Channels, a Video link, My files, YouTube, Services and the Queue.
 * Each tab states its mode plainly; subscription services only ever offer "Open in browser" with no Play button.
 * Favorites, aliases and recent links stay private to this client.
 */
public class SourcePickerScreen extends OteTvScreen {
    public enum Tab { CHANNELS, LINK, FILES, YOUTUBE, SERVICES, QUEUE }

    private static final int ROWS = 6;
    private final UUID tv;
    private boolean noChannelsShown;
    private Tab tab;
    private int page;
    private EditBox linkBox;
    private EditBox labelBox;
    private String linkDraft = "";
    private String labelDraft = "";
    private final Consumer<S2C.ActionResult> listener = this::onResult;

    public SourcePickerScreen(@Nullable Screen parent, UUID tv) {
        this(parent, tv, Tab.CHANNELS);
    }

    private SourcePickerScreen(@Nullable Screen parent, UUID tv, Tab tab) {
        super(Component.translatable("otetv.screen.picker.title"), parent);
        this.tv = tv;
        this.tab = tab;
        this.panelWidth = 340;
        this.panelHeight = 236;
    }

    public static SourcePickerScreen filesTab(@Nullable Screen parent, UUID tv) {
        return new SourcePickerScreen(parent, tv, Tab.FILES);
    }

    @Override
    protected void init() {
        ClientTvManager.get().addResultListener(listener);
        ClientTvManager.get().catalog().request(false);
        ClientTvManager.get().requestQueue(tv);
        rebuild();
    }

    @Override
    public void removed() {
        ClientTvManager.get().removeResultListener(listener);
    }

    private void onResult(S2C.ActionResult r) {
        if (r.actionId() < 0) {
            return;
        }
        if (r.accepted()) {
            setStatus(Component.translatable("otetv.screen.done"), false);
            ClientTvManager.get().requestQueue(tv);
        } else {
            setStatus(Component.translatable(r.reason().translationKey()).append(r.detail().isEmpty() ? "" : " (" + r.detail() + ")"), true);
        }
    }

    private boolean can(TvPermission p) {
        TvSnapshot s = ClientTvManager.get().snapshot(tv);
        return s != null && s.permissions().contains(p);
    }

    private void rebuild() {
        if (linkBox != null) {
            linkDraft = linkBox.getValue();
        }
        if (labelBox != null) {
            labelDraft = labelBox.getValue();
        }
        clearWidgets();
        linkBox = null;
        labelBox = null;
        int l = left();
        int t = top() + 14;
        int tw = (panelWidth - 5 * 2) / 6;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button b = button(Component.translatable("otetv.picker.tab." + target.name().toLowerCase(Locale.ROOT)), x -> {
                tab = target;
                page = 0;
                setStatus(Component.empty(), false);
                rebuild();
            }, l + i * (tw + 2), t, tw, 18);
            b.active = target != tab;
            addRenderableWidget(b);
        }
        int y = t + 26;
        switch (tab) {
            case CHANNELS -> buildChannels(l, y);
            case LINK -> buildLink(l, y);
            case FILES -> buildFiles(l, y);
            case YOUTUBE -> buildYouTube(l, y);
            case SERVICES -> buildServices(l, y);
            case QUEUE -> buildQueue(l, y);
        }
        addRenderableWidget(button(Component.translatable("gui.back"), b -> onClose(), l + panelWidth - 80, top() + panelHeight - 26 - 12, 80, 20));
    }

    private void pager(int l, int y, int total) {
        int pages = Math.max(1, (total + ROWS - 1) / ROWS);
        if (pages <= 1) {
            return;
        }
        addRenderableWidget(button(Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            rebuild();
        }, l, y, 20, 20, page > 0 ? null : Component.empty()));
        addRenderableWidget(button(Component.literal(">"), b -> {
            page = Math.min(pages - 1, page + 1);
            rebuild();
        }, l + 24, y, 20, 20, page < pages - 1 ? null : Component.empty()));
    }

    // ---- channels -------------------------------------------------------------------------------------------------

    private void buildChannels(int l, int y) {
        List<S2C.ChannelEntry> entries = ClientTvManager.get().catalog().entries();
        boolean allowed = can(TvPermission.CHANNEL);
        for (int i = page * ROWS; i < Math.min(entries.size(), (page + 1) * ROWS); i++) {
            S2C.ChannelEntry e = entries.get(i);
            String alias = PrivateHistory.alias(e.number());
            String name = (PrivateHistory.isFavorite(e.number()) ? "★ " : "") + e.number() + "  "
                    + (alias.isEmpty() ? CatalogCache.label(e) : alias) + (e.enabled() ? "" : " (" + Component.translatable("otetv.picker.disabled").getString() + ")");
            if (e.source().type() == com.otectus.otetv.core.source.SourceType.SHARED_LOCAL_ASSET) {
                name += " [" + Component.translatable("otetv.picker.needs_local_asset").getString() + "]";
            }
            Button tune = button(Component.literal(font.plainSubstrByWidth(name, panelWidth - 60)),
                    b -> ClientTvManager.get().sendAction(tv, TvActionType.SELECT_CHANNEL, 0, e.number(), "", null, null),
                    l, y, panelWidth - 50, 20, !allowed ? Component.translatable("otetv.disabled.no_permission")
                            : !e.enabled() ? Component.translatable("otetv.reject.channel_disabled") : null);
            addRenderableWidget(tune);
            addRenderableWidget(button(Component.literal("★"), b -> {
                PrivateHistory.toggleFavorite(e.number());
                rebuild();
            }, l + panelWidth - 46, y, 20, 20));
            y += 22;
        }
        if (entries.isEmpty()) {
            setStatus(Component.translatable("otetv.picker.no_channels"), false);
            noChannelsShown = true;
        } else if (noChannelsShown) {
            // The catalog arrived after an empty first page was drawn.
            setStatus(Component.empty(), false);
            noChannelsShown = false;
        }
        pager(l, top() + panelHeight - 38, entries.size());
    }

    // ---- link ------------------------------------------------------------------------------------------------------

    private void buildLink(int l, int y) {
        linkBox = new EditBox(font, l, y + 10, panelWidth, 18, Component.translatable("otetv.picker.link"));
        linkBox.setMaxLength(2048);
        linkBox.setValue(linkDraft);
        addRenderableWidget(linkBox);
        labelBox = new EditBox(font, l, y + 42, panelWidth, 18, Component.translatable("otetv.picker.label"));
        labelBox.setMaxLength(96);
        labelBox.setValue(labelDraft);
        addRenderableWidget(labelBox);
        boolean allowed = can(TvPermission.SOURCE);
        boolean queue = can(TvPermission.QUEUE_EDIT);
        addRenderableWidget(button(Component.translatable("otetv.picker.play_on_tv"), b -> submitLink(TvActionType.SET_SOURCE),
                l, y + 66, 120, 20, allowed ? null : Component.translatable("otetv.disabled.no_permission")));
        addRenderableWidget(button(Component.translatable("otetv.picker.add_queue"), b -> submitLink(TvActionType.QUEUE_ADD),
                l + 124, y + 66, 120, 20, queue ? null : Component.translatable("otetv.disabled.no_permission")));
        List<String> recent = PrivateHistory.recentLinks();
        int ry = y + 92;
        for (int i = 0; i < Math.min(3, recent.size()); i++) {
            String link = recent.get(i);
            addRenderableWidget(button(Component.literal(font.plainSubstrByWidth(com.otectus.otetv.core.redact.Redactor.redactUrl(link), panelWidth - 10)),
                    b -> {
                        linkDraft = link;
                        rebuild();
                    }, l, ry, panelWidth, 16));
            ry += 18;
        }
    }

    private void submitLink(TvActionType type) {
        String link = linkBox.getValue().trim();
        try {
            MediaSource source = SourceInput.fromLink(link, labelBox.getValue(), MediaSource.Policy.STRUCTURAL);
            PrivateHistory.recordLink(link);
            ClientTvManager.get().sendAction(tv, type, 0, 0, "", source, null);
        } catch (SourceException e) {
            setStatus(Component.translatable(e.issue().translationKey()), true);
        }
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    private void buildFiles(int l, int y) {
        List<LocalMediaLibrary.Entry> files = LocalMediaLibrary.list();
        boolean share = can(TvPermission.SOURCE);
        for (int i = page * ROWS; i < Math.min(files.size(), (page + 1) * ROWS); i++) {
            LocalMediaLibrary.Entry e = files.get(i);
            addRenderableWidget(button(Component.literal(font.plainSubstrByWidth(e.label(), panelWidth - 150)), b -> {
                ClientTvManager.get().startPrivate(tv, e);
                setStatus(Component.translatable("otetv.picker.playing_private"), false);
            }, l, y, panelWidth - 130, 20));
            addRenderableWidget(button(Component.translatable("otetv.picker.share"), b -> shareFile(e), l + panelWidth - 126, y, 62, 20,
                    share ? null : Component.translatable("otetv.disabled.no_permission")));
            addRenderableWidget(button(Component.translatable("otetv.picker.forget"), b -> {
                LocalMediaLibrary.forget(e.key());
                rebuild();
            }, l + panelWidth - 60, y, 60, 20));
            y += 22;
        }
        if (files.isEmpty()) {
            setStatus(Component.translatable("otetv.picker.no_files"), false);
        }
        int by = top() + panelHeight - 38 - 24;
        addRenderableWidget(button(Component.translatable("otetv.picker.import"), b -> importFile(), l, by, 100, 20));
        addRenderableWidget(button(Component.translatable("otetv.picker.open_folder"),
                b -> Util.getPlatform().openFile(LocalMediaLibrary.mediaFolder().toFile()), l + 104, by, 100, 20));
        addRenderableWidget(button(Component.translatable("otetv.picker.refresh"), b -> rebuild(), l + 208, by, 60, 20));
        pager(l, top() + panelHeight - 38, files.size());
    }

    /** Explicit import through the platform file dialog (the in-game folder is the fallback). */
    private void importFile() {
        String chosen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(LocalMediaLibrary.EXTENSIONS.size());
            for (String ext : LocalMediaLibrary.EXTENSIONS) {
                filters.put(stack.UTF8("*." + ext));
            }
            filters.flip();
            chosen = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable("otetv.picker.import").getString(),
                    LocalMediaLibrary.mediaFolder().toString() + "/", filters, "Video", false);
        } catch (Throwable t) {
            chosen = null;
            setStatus(Component.translatable("otetv.picker.dialog_unavailable"), true);
        }
        if (chosen != null) {
            setStatus(LocalMediaLibrary.grant(Path.of(chosen)).isPresent() ? Component.translatable("otetv.picker.imported")
                    : Component.translatable("otetv.picker.import_failed"), false);
        }
        rebuild();
    }

    /**
     * "Use this local file for a shared asset": computes the content digest locally and shares only that identity
     * and a label; the file and its path never leave this computer (section 11.4).
     */
    private void shareFile(LocalMediaLibrary.Entry entry) {
        setStatus(Component.translatable("otetv.picker.identifying"), false);
        LocalMediaLibrary.identify(entry).whenComplete((identified, error) -> minecraft.execute(() -> {
            if (error != null || identified == null) {
                setStatus(Component.translatable("otetv.picker.import_failed"), true);
                return;
            }
            try {
                String asset = "otetv_shared:" + identified.sha256().substring(0, 16);
                MediaSource source = MediaSource.sharedAsset(asset, identified.sha256(), -1, entry.label());
                ClientTvManager.get().sendAction(tv, TvActionType.SET_SOURCE, 0, 0, "", source, null);
            } catch (SourceException e) {
                setStatus(Component.translatable(e.issue().translationKey()), true);
            }
        }));
    }

    // ---- youtube ---------------------------------------------------------------------------------------------------

    private void buildYouTube(int l, int y) {
        linkBox = new EditBox(font, l, y + 10, panelWidth, 18, Component.translatable("otetv.picker.youtube_link"));
        linkBox.setMaxLength(2048);
        linkBox.setValue(linkDraft);
        addRenderableWidget(linkBox);
        boolean allowed = can(TvPermission.SOURCE);
        addRenderableWidget(button(Component.translatable("otetv.picker.play_on_tv"), b -> submitYouTube(TvActionType.SET_SOURCE), l, y + 34, 110, 20,
                allowed ? null : Component.translatable("otetv.disabled.no_permission")));
        addRenderableWidget(button(Component.translatable("otetv.picker.add_queue"), b -> submitYouTube(TvActionType.QUEUE_ADD), l + 114, y + 34, 110, 20,
                can(TvPermission.QUEUE_EDIT) ? null : Component.translatable("otetv.disabled.no_permission")));
        addRenderableWidget(button(Component.translatable("otetv.screen.open_youtube"), b -> {
            try {
                YouTubeRef ref = YouTubeUrls.parse(linkBox.getValue(), true);
                ConfirmLinkScreen.confirmLinkNow(ref.watchUrl(), this, false);
            } catch (SourceException e) {
                setStatus(Component.translatable(e.issue().translationKey()), true);
            }
        }, l + 228, y + 34, panelWidth - 228, 20));
        if (RuntimeManager.browserInfo().status() != RuntimeManager.BrowserStatus.INSTALLED) {
            addRenderableWidget(button(Component.translatable("otetv.screen.install_runtime"), b -> minecraft.setScreen(new RuntimeSetupScreen(this, true)),
                    l, y + 92, 160, 20));
        }
    }

    private void submitYouTube(TvActionType type) {
        try {
            YouTubeRef ref = YouTubeUrls.parse(linkBox.getValue(), true);
            ClientTvManager.get().sendAction(tv, type, 0, 0, "", MediaSource.youtube(ref, ""), null);
        } catch (SourceException e) {
            setStatus(Component.translatable(e.issue().translationKey()), true);
        }
    }

    // ---- services ----------------------------------------------------------------------------------------------------

    private void buildServices(int l, int y) {
        for (SubscriptionService service : SubscriptionService.values()) {
            addRenderableWidget(button(Component.translatable("otetv.picker.open_in_browser"),
                    ConfirmLinkScreen.confirmLink(service.homepage(), this, false), l + panelWidth - 120, y, 120, 20));
            y += 26;
        }
    }

    // ---- queue -------------------------------------------------------------------------------------------------------

    private void buildQueue(int l, int y) {
        S2C.QueuePage queue = ClientTvManager.get().queue(tv);
        boolean edit = can(TvPermission.QUEUE_EDIT);
        if (queue != null) {
            for (int i = 0; i < Math.min(queue.entries().size(), ROWS); i++) {
                int index = i;
                MediaSource s = queue.entries().get(i);
                String label = (i == queue.index() ? "▶ " : "") + (i + 1) + ". " + s.displayLabel();
                addRenderableWidget(button(Component.literal(font.plainSubstrByWidth(label, panelWidth - 80)), b -> {
                }, l, y, panelWidth - 70, 20, Component.literal(s.type().id())));
                addRenderableWidget(button(Component.translatable("otetv.picker.remove"),
                        b -> ClientTvManager.get().sendAction(tv, TvActionType.QUEUE_REMOVE, 0, index, "", null, null),
                        l + panelWidth - 66, y, 66, 20, edit ? null : Component.translatable("otetv.disabled.no_permission")));
                y += 22;
            }
        }
        int by = top() + panelHeight - 38 - 24;
        addRenderableWidget(button(Component.translatable("otetv.picker.next"), b -> ClientTvManager.get().sendAction(tv, TvActionType.QUEUE_NEXT),
                l, by, 100, 20, can(TvPermission.PLAYBACK) ? null : Component.translatable("otetv.disabled.no_permission")));
        addRenderableWidget(button(Component.translatable("otetv.picker.clear"), b -> ClientTvManager.get().sendAction(tv, TvActionType.QUEUE_CLEAR),
                l + 104, by, 100, 20, edit ? null : Component.translatable("otetv.disabled.no_permission")));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int l = left();
        int y = top() + 40;
        switch (tab) {
            case LINK -> {
                g.drawString(font, Component.translatable("otetv.picker.link"), l, y, mutedColor());
                g.drawString(font, Component.translatable("otetv.picker.label"), l, y + 32, mutedColor());
                g.drawString(font, Component.translatable("otetv.picker.link_note"), l, y + 156, mutedColor());
            }
            case YOUTUBE -> {
                g.drawString(font, Component.translatable("otetv.picker.youtube_link"), l, y, mutedColor());
                boolean installed = RuntimeManager.browserInfo().status() == RuntimeManager.BrowserStatus.INSTALLED;
                g.drawString(font, Component.translatable(installed ? "otetv.mode.in_world" : "otetv.mode.unavailable_platform"), l, y + 60,
                        installed ? 0xFF8CE68C : 0xFFFFB74D);
                g.drawString(font, Component.translatable("otetv.picker.youtube_note"), l, y + 72, mutedColor());
                g.drawString(font, Component.translatable("otetv.picker.search_not_configured"), l, y + 120, mutedColor());
            }
            case SERVICES -> {
                int sy = y + 6;
                for (SubscriptionService service : SubscriptionService.values()) {
                    g.drawString(font, service.displayName(), l, sy, textColor());
                    g.drawString(font, Component.translatable("otetv.picker.service_external"), l, sy + 10, mutedColor());
                    sy += 26;
                }
                g.drawString(font, Component.translatable("otetv.picker.services_note"), l, sy + 4, mutedColor());
            }
            case FILES -> g.drawString(font, Component.translatable("otetv.picker.files_note"), l, top() + panelHeight - 72 - 12, mutedColor());
            default -> {
            }
        }
    }

    @Override
    public void tick() {
        if (tab == Tab.CHANNELS && ClientTvManager.get().catalog().entries().size() != countButtonsHint) {
            countButtonsHint = ClientTvManager.get().catalog().entries().size();
            rebuild();
        }
        if (tab == Tab.QUEUE) {
            S2C.QueuePage q = ClientTvManager.get().queue(tv);
            long rev = q == null ? -1 : q.revision() * 100 + q.entries().size();
            if (rev != queueHint) {
                queueHint = rev;
                rebuild();
            }
        }
    }

    private int countButtonsHint = -1;
    private long queueHint = -2;
}
